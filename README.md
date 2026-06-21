# SystemUI-Lite

轻量级 Android SystemUI 替代品，复刻 AOSP SystemUI 核心交互，适用于 Google Pixel 设备（挖孔屏）。

## 功能

### 状态栏
- 时钟显示（h:mm 格式）
- 信号强度图标（实时监听 `PhoneStateListener`）
- WiFi 图标（实时监听 `RSSI_CHANGED`）
- 电池图标 + 电量百分比（实时监听 `ACTION_BATTERY_CHANGED`）
- 挖孔屏自适应（角落挖孔 + 居中挖孔自动处理）

### 通知面板
- **下拉展开**：从状态栏下拉展开通知面板（匹配 AOSP 手势逻辑）
- **跟手追踪**：面板 1:1 跟随手指移动
- **速度追踪**：使用 `VelocityTracker` 计算 fling 速度
- **释放决策**：`|vectorVel| < minFlingVelocity` 时看 `fraction > 0.5`，否则 `vel > 0`
- **Fling 动画**：展开带速度比例过冲（`lerp(0.2, 1.0, saturate(vel / 4000))`），收起无过冲
- **Spring-back**：过冲后用 `FAST_OUT_SLOW_IN` 弹性回归 1.0
- **上滑收起**：通知列表滚动到底后，继续上滑触发收起（`onInterceptTouchEvent` 拦截）
- **点击空白收起**：点击 scrim 区域收起面板
- **Back 键收起**：按 Back 键收起面板

### Scrim 动画
- 背景 scrim 透明度由展开比例驱动（`alpha = fraction * 0.7`）
- 面板内容透明度跟随展开比例

### Quick Settings
- 8 个功能磁贴：WiFi、蓝牙、手电筒、飞行模式、自动旋转、勿扰模式、位置、热点
- 磁贴状态动画（300ms 颜色过渡）
- 亮度滑块控制
- 2 列网格布局（匹配 AOSP 竖屏配置）

### 通知
- 系统通知监听（`SystemNotificationListener` 实时转发）
- 通知列表显示（标题、内容、时间、应用图标）
- 单条通知清除

### 导航栏
- 手势导航栏（底部横条）
- 边缘返回手势（`EdgeBackGestureHandler`）

## 架构

```
SystemUI-Lite/
├── app/src/main/java/com/android/systemui/
│   ├── SystemUIApplication.kt          # Hilt Application 入口
│   ├── SystemUIService.kt              # CoreStartable 启动服务
│   ├── CoreStartable.kt                # 组件生命周期接口
│   ├── statusbar/
│   │   ├── StatusBarManager.kt         # 状态栏窗口 + 触摸转发
│   │   └── BatteryController.kt        # 电池状态监听
│   ├── notification/
│   │   ├── NotificationShadeManager.kt # 通知面板核心（触摸、动画、scrim）
│   │   ├── NotificationShadeView.kt    # 自定义 FrameLayout（onInterceptTouchEvent）
│   │   └── SystemNotificationListener.kt # 系统通知监听转发
│   ├── qs/
│   │   ├── QSPanelController.kt        # QS 面板控制器
│   │   ├── QSTileBase.kt              # 磁贴基类（状态动画）
│   │   └── tiles/                      # 各功能磁贴实现
│   ├── navigationbar/
│   │   ├── NavigationBarManager.kt     # 导航栏窗口
│   │   └── EdgeBackGestureHandler.kt   # 边缘返回手势
│   └── di/ApplicationModule.kt         # Hilt 依赖注入
├── app/src/main/res/layout/
│   ├── notification_panel.xml          # 通知面板布局（NotificationShadeView 根）
│   ├── status_bar.xml                  # 状态栏布局
│   ├── qs_panel.xml                    # QS 面板布局
│   ├── qs_tile.xml                     # 磁贴布局（水平：图标+标签）
│   └── notification_row.xml            # 通知行布局
└── deploy/                             # 部署脚本和权限文件
```

## 触摸交互流程（匹配 AOSP）

```
用户触摸状态栏
  → StatusBarManager.handleStatusBarTouch(DOWN)
    → NotificationShadeManager.handleExternalTouch(DOWN)
      → 创建 NotificationShadeView
      → handleTouchEvent(DOWN): startExpandMotion, addMovement

用户拖拽超过 touchSlop
  → StatusBarManager.handleStatusBarTouch(MOVE)
    → NotificationShadeManager.handleExternalTouch(MOVE)
      → handleTouchEvent(MOVE): onTrackingStarted, setExpandedHeightInternal

用户松手
  → StatusBarManager.handleStatusBarTouch(UP)
    → NotificationShadeManager.handleExternalTouch(UP)
      → handleTouchEvent(UP): endMotionEvent
        → VelocityTracker.computeCurrentVelocity(1000)
        → flingExpands(vel, vectorVel)
        → flingToHeight(vel, expand, target)
```

## 构建

```bash
./gradlew assembleDebug
```

输出：`app/build/outputs/apk/debug/SystemUI.apk`

## 部署

### 前提条件
- 已 root 的 Google Pixel 设备
- 已禁用 dm-verity（`adb disable-verity` + 重启）
- USB 调试已开启

### 方法一：完整部署（含 verity 处理）
```bash
./deploy.sh
```

### 方法二：快速推送
```bash
adb root && adb remount
adb push app/build/outputs/apk/debug/SystemUI.apk /system_ext/priv-app/SystemUI/SystemUI.apk
adb shell stop && adb shell start
```

### 查看日志
```bash
adb logcat -s NotifShadeManager:* StatusBarManager:* QSPanelController:*
```

## 依赖

- Android SDK 34+
- Kotlin 1.9+
- Hilt 2.51+
- Gradle 8.14+

## 权限

应用使用 `android.uid.system` 共享 UID，需要系统签名。主要权限：

| 权限 | 用途 |
|------|------|
| `INTERNAL_SYSTEM_WINDOW` | 创建系统窗口（状态栏、通知面板） |
| `SYSTEM_ALERT_WINDOW` | 通知面板覆盖窗口 |
| `STATUS_BAR_SERVICE` | 状态栏服务 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 监听系统通知 |
| `WRITE_SETTINGS` | 亮度控制 |
| `BLUETOOTH_*` | 蓝牙磁贴控制 |
| `ACCESS_WIFI_STATE` | WiFi 状态和磁贴控制 |

## 已知限制

- 仅支持竖屏模式
- 无锁屏/Keyguard 集成
- 无分屏模式
- 通知面板不支持分屏 shade
- QS 磁贴数量固定为 8 个
