# Phase 3 — 状态栏（View 实现）

> 在 Phase 1（进程存活）+ Phase 2（壁纸引擎）基础上，用 **纯 View（非 Compose）** 绘制状态栏，尽量对齐 AOSP SystemUI 现代 View 架构。

## 1. 目标与成功判据

**目标**：绘制一个与 AOSP `PhoneStatusBarView` 结构一致的状态栏窗口——
- 左：时钟（`h:mm` / `HH:mm`，每分钟 tick）
- 右：系统图标（信号 + WiFi + 电池）
- 中：挖孔屏 spacer（`cutout_space_view`）
- 实时监听：电池（`ACTION_BATTERY_CHANGED`）、WiFi（RSSI）、蜂窝（`PhoneStateListener`）
- 自动隐藏（`AutoHideController`，2.25s 超时）
- 沉浸模式 / transient 状态（`StatusBarServiceBridge`，预留接口）
- 壁纸驱动暗/亮色调（订阅 Phase 2 `WallpaperProvider.isDark`）

**成功判据**：
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL，Hilt 生成 `StatusBarManager_Factory`
2. 部署后进程仍存活，无 `IllegalStateException`
3. `adb shell dumpsys window windows | grep -E "StatusBar|TYPE_STATUS_BAR"` 可见 TYPE_STATUS_BAR 窗口
4. 时钟实时刷新；电池/WiFi/信号图标响应状态变化
5. 挖孔屏适配：居中挖孔显示 spacer，角落挖孔左右 padding

## 2. 与 AOSP 现代架构的对照

AOSP 现代 SystemUI 的状态栏已重构为 `CollapsedStatusBarFragment` + Dagger `@IntoMap` 管线，对独立项目是过度设计。本方案以 `SystemUI-Lite`（已运行的 View 参考项目）为**主要移植源**，参照 AOSP 架构模型，保留扩展余地。

| AOSP 现代实现 | 本方案（对应 / 简化） |
|---|---|
| `CentralSurfacesImpl`（CoreStartable 编排） | `StatusBarManager`（CoreStartable）— `start()` 内创建窗口 |
| `StatusBarWindowController`（Window 管理） | 同：`WindowManager.addView` 在 `start()` |
| `PhoneStatusBarView` + `CollapsedStatusBarFragment` + slot-icon 管线 | `inflate(R.layout.status_bar)` + 固定 ImageView 图标（clock/battery/wifi/signal），预留 `StatusBarIconList`/`IconManager` 接口便于后续替换 |
| `Clock extends TextView` + broadcasts | `ClockController`（`SimpleDateFormat` + `ACTION_TIME_TICK`） |
| `BatteryMeterView`（drawable 链） | `BatteryController`（`ACTION_BATTERY_CHANGED` + listener） |
| `StatusBarSignalPolicy` + `StatusBarWifiView/StatusBarMobileView` | `WifiController` + `SignalController`（`PhoneStateListener` / RSSI / 飞行模式） |
| `CommandQueue extends IStatusBar.Stub` + `registerStatusBar` 握手 | `StatusBarServiceBridge extends IStatusBar.Stub` — 沉浸/transient 回调；本阶段延迟注册到 `IStatusBarService`（避免触发系统握手依赖），专注本地 UI |
| `DarkIconDispatcher` + `DarkIconManager` | 状态栏元素在 `onDarkChanged` 接收来自 `WallpaperProvider` 的 `isDark`，切换颜色（与 Phase 2 颜色提取直接对接） |
| `SysuiStatusBarStateController` + `AutoHideController` | `AutoHideController` + `AutoHideUiElement` 接口（对 transient 隐藏 + 用户触摸自动隐藏） |

## 3. 核心原理：Window 创建

AOSP `StatusBarWindowController` 的 `WindowManager.LayoutParams` 是本次参数权威：

```kotlin
val lp = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    height,                                        // 读 system resource status_bar_height，兜底 24dp
    WindowManager.LayoutParams.TYPE_STATUS_BAR,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
        or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP
    setTitle("StatusBar")
    layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    setFitInsetsTypes(0)
}
windowManager.addView(statusBarView, lp)
```

**字段契约**：
| 字段 | 值 | 原因 |
|---|---|---|
| `type` | `TYPE_STATUS_BAR` | 系统状态栏窗口类型，需 `INTERNAL_SYSTEM_WINDOW` 权限 |
| `flags` | `FLAG_NOT_FOCUSABLE \| FLAG_SPLIT_TOUCH \| FLAG_TOUCHABLE_WHEN_WAKING \| FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` | 对齐 AOSP：不抢焦点、支持 split touch、唤醒时可触摸、绘制系统栏背景 |
| `gravity` | `Gravity.TOP` | 窗口吸顶 |
| `format` | `TRANSLUCENT` | 透明背景，让壁纸/应用内容透出 |
| `layoutInDisplayCutoutMode` | `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` | 始终延伸到挖孔区域 |
| `setFitInsetsTypes(0)` | 0 | 不自动适配 insets，由 `handleWindowInsets` 手动处理 |

**高度解析**：
```kotlin
private fun getStatusBarHeight(): Int {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id)
    else (24 * context.resources.displayMetrics.density.toInt())
}
```

## 4. 工程实现

### 4.1 根依赖 & 权限（零新依赖）

`libs.versions.toml` / `app/build.gradle.kts` — 无变更。纯 View + 框架（android.* / androidx.core 已含 ktx）。

`AndroidManifest.xml` — `STATUS_BAR_SERVICE / STATUS_BAR / EXPAND_STATUS_BAR / INTERNAL_SYSTEM_WINDOW / SYSTEM_ALERT_WINDOW / WRITE_SECURE_SETTINGS` 已在 Phase 1/2 声明，满足 `TYPE_STATUS_BAR + addView` 要求，本阶段无需新权限。无需新增 `<service>` 状态栏服务 — 状态栏由 `SystemUIApplication.onCreate` 经 CoreStartable 启动（与 `WallpaperProvider` 同路径）。

### 4.2 ApplicationModule 加 WindowManager

文件：`app/src/main/java/com/android/systemui/di/ApplicationModule.kt`

在已有 `@Provides` 中追加：
```kotlin
@Provides @Singleton
fun provideWindowManager(@ApplicationContext context: Context): WindowManager =
    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
```

### 4.3 资源（res/）

#### 4.3.1 dimens.xml

```xml
<dimen name="status_bar_height">@*android:dimen/status_bar_height</dimen>
<dimen name="status_bar_icon_size">@*android:dimen/status_bar_system_icon_size</dimen>
<dimen name="status_bar_wifi_signal_size">@*android:dimen/status_bar_system_icon_size</dimen>
<dimen name="status_bar_padding_start">8dp</dimen>
<dimen name="status_bar_padding_end">8dp</dimen>
<dimen name="status_bar_padding_top">0dp</dimen>
<dimen name="status_bar_clock_size">14sp</dimen>
<dimen name="status_bar_battery_icon_height">13dp</dimen>
<dimen name="status_bar_battery_icon_width">7.8dp</dimen>
<dimen name="status_bar_horizontal_padding">2.5dp</dimen>
<dimen name="status_bar_clock_starting_padding">0dp</dimen>
<dimen name="status_bar_clock_end_padding">0dp</dimen>
<dimen name="signal_icon_size_roaming">16dp</dimen>
```

#### 4.3.2 colors.xml

```xml
<color name="status_bar_icon_tint_dark">#ffffffff</color>
<color name="status_bar_icon_tint_light">#99000000</color>
<color name="status_bar_clock_color_dark">#ffffffff</color>
<color name="status_bar_clock_color_light">#ff202124</color>
<color name="status_bar_battery_level">#99ffffff</color>
```

#### 4.3.3 strings.xml

```xml
<string name="clock_cd_format">Clock, %s</string>
<string name="cd_wifi_connected">Wifi connected.</string>
<string name="cd_wifi_disconnected">Wifi disconnected.</string>
<string name="cd_signal_null">No signal.</string>
<string name="cd_signal_strength">Mobile signal %d</string>
<string name="cd_battery">Battery %d percent</string>
<string name="cd_battery_charging">Battery charging %d percent</string>
```

#### 4.3.4 themes.xml

```xml
<style name="TextAppearance.StatusBar.Clock" parent="@android:style/TextAppearance.StatusBar.Icon">
    <item name="android:textSize">@dimen/status_bar_clock_size</item>
</style>
```

#### 4.3.5 布局

**`res/layout/status_bar.xml`**（根，对齐 AOSP `PhoneStatusBarView`）：

```
status_bar (LinearLayout horizontal, height=status_bar_height, transparent)
├── status_bar_start_side_container (FrameLayout, weight=1)
│   └── status_bar_start_side_content (LinearLayout horizontal)
│       ├── clock (TextView, id=clock, TextAppearance.StatusBar.Clock)
│       └── notification_icon_area (LinearLayout horizontal)
├── cutout_space_view (Space, width=0dp, GONE by default)
└── status_bar_end_side_container (FrameLayout, weight=1)
    └── status_bar_end_side_content (LinearLayout, gravity=end)
        └── <include layout="@layout/system_icons"/>
```

**`res/layout/system_icons.xml`**（对齐 AOSP `system_icons.xml`）：

```
<merge>
  system_icons (LinearLayout horizontal)
  ├── statusIcons (LinearLayout horizontal, weight=1)
  │   ├── signal_icon (ImageView 18dp, centerInside)
  │   └── wifi_icon (ImageView 18dp, centerInside)
  └── battery_container (LinearLayout horizontal)
      ├── battery_level (TextView, TextAppearance.StatusBar.Clock)
      └── battery_icon (ImageView 7.8dp×13dp, centerInside)
```

#### 4.3.6 图标资源

AOSP 状态栏 drawable 来自**两个命名空间**，获取策略不同：

1. **SystemUI 本地 vector drawable**（`SystemUI/res/drawable/stat_sys_*.xml`）— 装在 SystemUI APK 内，**必须复制**到本项目的 `app/src/main/res/drawable/`：

   从 `~/android-os/SystemUI/res/drawable/` 拷贝以下文件（对齐 AOSP 状态栏实际引用的清单）：
   - `stat_sys_vpn_ic.xml` / `stat_sys_branded_vpn.xml`（VPN）
   - `stat_sys_data_bluetooth_connected.xml`（蓝牙）
   - `stat_sys_ethernet.xml` / `stat_sys_ethernet_fully.xml`（有线网）
   - `stat_sys_hotspot.xml`（热点）
   - `stat_sys_cast.xml`（投屏）
   - `stat_sys_headset.xml` / `stat_sys_headset_mic.xml`（耳机）
   - `stat_sys_rotate_landscape.xml` / `stat_sys_rotate_portrait.xml`（旋转）
   - `stat_sys_screen_record*.xml`（录屏）
   - `stat_sys_dnd.xml`（免打扰）、`stat_sys_tty_mode.xml`、`stat_sys_sensors_off.xml`
   - `stat_sys_alarm.xml` / `stat_sys_alarm_dim.xml`（闹钟）
   - `stat_sys_ringer_silent.xml` / `stat_sys_ringer_vibrate.xml`（静音/振动）
   - `stat_sys_speakerphone.xml`、`stat_sys_data_saver.xml`、`stat_sys_managed_profile_status.xml`
   - `stat_sys_adb.xml`、`stat_sys_roaming*.xml`

   以及被引用的子 drawable（AAPT 编译需要）：
   - `ic_alarm.xml` / `ic_alarm_dim.xml`
   - `ic_bluetooth_connected.xml`、`ic_data_saver.xml`、`ic_headset.xml` / `ic_headset_mic.xml`
   - `ic_hotspot.xml`、`ic_speaker_mute.xml`、`ic_volume_ringer_vibrate.xml`

2. **Framework internal 电量 / WiFi / 信号强度 drawable** — AOSP 通过 `SettingsLib.graph.SignalDrawable` / `BatteryDrawable` 程序化绘制（信号 0..4 格、WiFi 信号强度、电池等级均由代码算 `level`、用 `setDrawableState` 切换），其静态位图源在 framework `com.android.internal.R.drawable.*`（运行时不需复制；在设备上由 framework 提供）：
   - **信号 `stat_sys_signal_0..4` + `stat_sys_signal_null`（飞行模式）**：AOSP 已**完全程序化**，画布用 `SignalDrawable`。**最小移植策略**：在 `SignalController` 中按等级选 `@android:drawable/stat_sys_signal_0..4` 作为 ImageView source（设备有这些资源），飞行模式 / 无 service 用 `@android:drawable/stat_sys_signal_null`。
   - **WiFi `stat_sys_wifi_signal_0..4` + 关闭态**：同上策略。
   - **电池**：AOSP 已程序化用 `BatteryDrawable`；本阶段用 `@android:drawable/stat_sys_battery` + level 叠加，或引用 `stat_sys_battery_charge` 系列。

   **关键约束**：`SignalDrawable`/`BatteryDrawable` 属 `com.android.settingslib`（在 `framework-minus-apex.jar` 可能不含完整实现）。本阶段**优先 `@android:drawable/` + 本地复制兜底**，不引入 SettingsLib 复杂管线；Stage 2 再迁移到程序化 drawable。

**命名规则**：除 `stat_sys_*` 保留原名外，如有本工程新增 drawable，前缀统一 `stat_sys_` 以匹配 SystemUI 命名空间。所有 ImageView `ImageView.setImageResource(R.drawable.stat_sys_xxx)` 引用的是**本项目本地**资源；`@android:drawable/stat_sys_xxx` 是**设备 framework** 资源。

### 4.4 源码（`statusbar/`，对齐 Lite 包结构）

文件均在 `app/src/main/java/com/android/systemui/statusbar/`：

#### 4.4.1 `ClockController.kt` — 时钟驱动

```kotlin
@Singleton
class ClockController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {
    fun bind(view: TextView?) { clockView = view; updateClock() }

    override fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        context.registerReceiver(timeReceiver, filter)
        updateClock()
    }

    private fun updateClock() {
        val format = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val text = SimpleDateFormat(format, Locale.getDefault()).format(Calendar.getInstance().time)
        clockView?.text = text
        clockView?.contentDescription = text
    }
}
```

- 12/24 小时格式由 `DateFormat.is24HourFormat()` 决定
- 每分钟 `ACTION_TIME_TICK` 触发刷新；时区/时间/配置变化立即刷新

#### 4.4.2 `BatteryController.kt` — 电池状态

```kotlin
@Singleton
class BatteryController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    interface BatteryStateListener {
        fun onBatteryLevelChanged(level: Int, isCharging: Boolean)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = (level * 100 / scale).coerceIn(0..100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, ...)
            isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == BatteryManager.BATTERY_STATUS_FULL)
            notifyListeners()
        }
    }
    // register/unregister/addListener/removeListener/getBatteryLevel/isCharging
}
```

- 监听 `ACTION_BATTERY_CHANGED`，算 `level = (level*100/scale)` + `isCharging = status==CHARGING||FULL`
- 通过 `CopyOnWriteArrayList<BatteryStateListener>` 通知状态栏

#### 4.4.3 `WifiController.kt` — WiFi 状态

```kotlin
@Singleton
class WifiController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    fun interface WifiStateListener {
        fun onWifiStateChanged(level: Int, enabled: Boolean, rssi: Int)
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.RSSI_CHANGED_ACTION ->
                    rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -50)
                WifiManager.WIFI_STATE_CHANGED_ACTION ->
                    enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, ...) == WifiManager.WIFI_STATE_ENABLED
            }
            update()
        }
    }
    // register/unregister/start/stop
}
```

- RSSI → 0..4 通过 `WifiManager.calculateSignalLevel(rssi, 5) - 1`
- `enabled=false`（WiFi 关）隐藏图标
- `start()` 内 probe 初始状态，保证首帧正确

#### 4.4.4 `SignalController.kt` — 蜂窝信号

```kotlin
@Singleton
class SignalController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    fun interface SignalStateListener {
        fun onSignalStateChanged(level: Int, inService: Boolean, airplaneMode: Boolean)
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener: PhoneStateListener =
        object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                level = signalStrength?.level?.coerceIn(0, LEVELS - 1) ?: -1
                dispatch()
            }
            @Deprecated("Deprecated in Java")
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                inService = serviceState?.state == ServiceState.STATE_IN_SERVICE
                dispatch()
            }
        }
    // register/unregister/start/stop
}
```

- `PhoneStateListener(LISTEN_SIGNAL_STRENGTHS | LISTEN_SERVICE_STATE)`
- 读取 `ServiceState`（`STATE_IN_SERVICE`）、SIM 状态（`TelephonyManager.SIM_STATE_READY`）、飞行模式（`Settings.Global.AIRPLANE_MODE_ON`）
- 飞行模式 / 无 SIM / 不在服务区 → `stat_sys_signal_null`；否则 `stat_sys_signal_0..4`

#### 4.4.5 `AutoHideUiElement.kt` + `AutoHideController.kt` — 自动隐藏

```kotlin
interface AutoHideUiElement {
    fun synchronizeState() {}
    fun shouldHideOnTouch(): Boolean
    fun isVisible(): Boolean
    fun hide()
}

@Singleton
class AutoHideController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val AUTO_HIDE_TIMEOUT_MS = 2250
        const val USER_AUTO_HIDE_TIMEOUT_MS = 350
    }
    fun setStatusBar(element: AutoHideUiElement) { statusBar = element }
    fun checkUserAutoHide(event: MotionEvent) { ... }
    fun touchAutoHide() { isAutoHideSuspended = true; cancelAutoHide() }
    fun scheduleAutoHide() { handler.postDelayed(autoHideRunnable, AUTO_HIDE_TIMEOUT_MS.toLong()) }
    fun cancelAutoHide() { handler.removeCallbacks(autoHideRunnable) }
}
```

- `AUTO_HIDE_TIMEOUT_MS=2250`，`USER_AUTO_HIDE_TIMEOUT_MS=350`
- `checkUserAutoHide`：`ACTION_OUTSIDE` 在 (0,0) 时隐藏
- `touchAutoHide`：触摸到 bar 时暂停自动隐藏

#### 4.4.6 `StatusBarManager.kt` — 核心（AOSP `StatusBarWindowController` + Lite 版融合）

```kotlin
@Singleton
class StatusBarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager,
    private val batteryController: BatteryController,
    private val clockController: ClockController,
    private val wifiController: WifiController,
    private val signalController: SignalController,
    private val wallpaperProvider: WallpaperProvider,
    val autoHideController: AutoHideController   // 公开 val，便于 Application 注册
) : CoreStartable,
    BatteryController.BatteryStateListener,
    WifiController.WifiStateListener,
    SignalController.SignalStateListener,
    AutoHideUiElement {

    override fun start() {
        createStatusBarWindow()   // ★ start() 内创建窗口——对齐 Lite 启动契约
        registerListeners()
    }

    override fun stop() {
        unregisterListeners()
        removeStatusBarWindow()
    }
}
```

**关键方法**：
- `createStatusBarWindow()`：inflate `status_bar.xml` → bind 子视图 → 设置 `OnApplyWindowInsetsListener`（挖孔适配）+ `OnTouchListener`（auto-hide + 预留 Stage 2 shade-forward）→ `WindowManager.addView`
- `registerListeners()`：注册 battery/wifi/signal 监听 + 订阅 `WallpaperProvider` 暗色调
- `handleWindowInsets(view, insets)`：读取 `displayCutout.boundingRectTop`，区分居中挖孔（显示 `cutout_space`）vs 角落挖孔（左右 padding）
- `handleStatusBarTouch(event)`：`autoHideController.checkUserAutoHide` + 预留 Stage 2 通知栏下拉
- `updateBatteryUI / updateWifiIcon / updateSignalIcon`：按状态选 `@android:drawable/stat_sys_*` 资源
- `applyDarkMode(isDark)`：按壁纸 `isDark` 切换 icon tint + clock color

**autoHideController 必须为 public val**，以便 `SystemUIApplication.onCreate` 调用：`statusBarManager.autoHideController.setStatusBar(statusBarManager)`。

#### 4.4.7 `StatusBarServiceBridge.kt` — IStatusBar 桥

```kotlin
@Singleton
class StatusBarServiceBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    interface BarCallback {
        fun onBarVisibilityChanged(visible: Boolean)
        fun onTransientStateChanged(isTransient: Boolean)
        fun onAppearanceChanged(appearance: Int)
    }
    var statusBarCallback: BarCallback? = null
    var navBarCallback: BarCallback? = null

    override fun start() {
        // 本地 UI 模式：不 bind 到 StatusBarManagerService（独立启动路径跑不起来）
    }
}
```

- 在 AOSP 这是 `CommandQueue extends IStatusBar.Stub`，通过 `IStatusBarService.registerStatusBar()` 握手
- 本阶段本地 UI 模式：不注册，仅保留接口 & 槽位，未来接 system-server 时启用

### 4.5 在 `SystemUIApplication` 注册状态栏

```kotlin
@Inject lateinit var statusBarManager: StatusBarManager

override fun onCreate() {
    coreStartableComponent.register(wallpaperProvider)
    coreStartableComponent.register(statusBarManager)        // ★ 新增
    statusBarManager.autoHideController.setStatusBar(statusBarManager)  // ★ 新增
    coreStartableComponent.register(DummyCoreStartable())
    coreStartableComponent.start()   // StatusBarManager.start() 在此刻创建窗口
}
```

⚠️ **顺序契约**：`register` 必须全部在 `start()` 之前（`CoreStartableComponent` 有一次性 `isStarted` 守卫；延迟 register 的属性永不会被调用 start）。桥接（bridge）若启用也必须在 `start()` 之前 assign callback。

## 5. 构建

```bash
JAVA_HOME=/Users/young/Library/Java/JavaVirtualMachines/corretto-17.0.14/Contents/Home \
ANDROID_HOME=/Users/young/Library/Android/sdk \
./gradlew clean assembleDebug --no-daemon
```

**构建期校验**：
- `StatusBarManager_Factory` 由 Hilt 生成（`@Inject` 构造 + `@Singleton`）
- `StatusBarServiceBridge` 引用 `IStatusBar.Stub` — 需确认 `framework-minus-apex.jar` 含 `android/service/statusbar/IStatusBar`（若不含，仿 `WallpaperService` 把 Stub 写成包装即可）
- 所有 `stat_sys_*.xml` 引用的子 drawable 必须存在（AAPT 编译需要）

## ✅ 设备验证

| 步骤 | 命令 | 预期 |
|---|---|---|
| **构建** | `assembleDebug` | BUILD SUCCESSFUL；Hilt 生成 `StatusBarManager_Factory` |
| **回归：进程存活** | `adb shell ps -A \| grep systemui` | uid=system + 1 行 |
| **回归：boot 无权限错** | `adb logcat -d -s SystemServer:*StrictMode:V` | 无 `IllegalStateException: privapp` |
| **状态栏已添加** | `adb logcat -d -s StatusBarManager:*` | `status bar window added` |
| **窗口在屏上** | `adb shell dumpsys window windows \| grep -E "StatusBar\|TYPE_STATUS_BAR"` | 有 1 条 + height = 系统 `status_bar_height` |
| **时钟显示** | 肉眼观察顶部 | 左上角 `h:mm` 刷新 |
| **电池** | 肉眼观察 | 百分比 + 图标 |
| **WiFi/信号** | 开/关 WiFi、飞行模式 | `icon_0..4` / `signal_null` 切换 |
| **挖孔适配** | 居中 / 角落挖孔 | `cutout_space` 显示或左右 padding |
| **黑暗色调** | 设置深色壁纸 | 图标颜色变浅（接 `WallpaperProvider.isDark`） |
| **触摸/自动隐藏** | 无触摸 2.25s | transient 状态可观察到 alpha 变化 |
| **bootloop 回滚** | `adb cp backup.apk + reboot` | 恢复 Phase 2 状态 |

## 📁 新增/修改文件速览

```
SystemUI-Lite-v0.2/
├── app/src/main/
│   ├── AndroidManifest.xml                    [无改：已有 STATUS_BAR* / INTERNAL_SYSTEM_WINDOW]
│   ├── java/com/android/systemui/
│   │   ├── SystemUIApplication.kt             [改] 加 statusBarManager 注册 + autoHideController.setStatusBar
│   │   ├── statusbar/                         [新建]
│   │   │   ├── ClockController.kt             [新建]
│   │   │   ├── BatteryController.kt           [新建]
│   │   │   ├── WifiController.kt              [新建]
│   │   │   ├── SignalController.kt            [新建]
│   │   │   ├── StatusBarManager.kt            [新建] 核心
│   │   │   ├── StatusBarServiceBridge.kt      [新建，暂不注册系统服务]
│   │   │   ├── AutoHideController.kt          [新建]
│   │   │   └── AutoHideUiElement.kt           [新建]
│   │   └── di/
│   │       └── ApplicationModule.kt           [改] + provideWindowManager
│   └── res/
│       ├── drawable/stat_sys_*.xml             [新建] 复制自 SystemUI 源码
│       ├── drawable/ic_*.xml                  [新建] 引用的子 drawable
│       ├── layout/status_bar.xml              [新建]
│       ├── layout/system_icons.xml            [新建]
│       └── values/{dimens,colors,strings,themes}.xml  [改/新建]
└── app/build/outputs/apk/debug/SystemUI.apk   [重建]
```

`app/build.gradle.kts` / `libs.versions.toml` — 无变更。

## ⚠️ 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| R1 | `TYPE_STATUS_BAR` 需要 `INTERNAL_SYSTEM_WINDOW` 权限 | 已在 Phase 1/2 manifest 声明；仍需 privapp whitelist（`/system_ext/etc/permissions/`） |
| R2 | 独立启动的 SystemUI 调用 `IStatusBarService.registerStatusBar` 必失败 | Phase 3 把 `StatusBarServiceBridge` 放入本地 UI 模式，暂不注册；仅在 manifest 声明能力 |
| R3 | `WindowManager.addView` 抛 `BadTokenException` / token 无效 | 对齐 AOSP：在 `start()`（即 onCreate 已完成后）调用；type/flags 严格按 § 参数表 |
| R4 | 图标 drawable 取自 `android:*` 系统资源 + `*_our` fallback | 直接搬 Lite 已验证资源；fallback drawable 同时本地备一份 |
| R5 | transient/AutoHide 立即隐藏状态栏导致屏幕空白 | 默认 disabled（无系统 transient 事件）；autoHide 仅在用户触摸后触发 |
| R6 | 挖孔适配在横屏失效 | 第一期仅处理竖屏居中/角落 cutout（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`），横屏留后续 |
| R7 | 状态栏 touch 拦截影响应用内返回键 | touch listener 仅在 status bar VIEW 上，不影响区域外；AOSP 用 `FLAG_NOT_FOCUSABLE` + `FLAG_SPLIT_TOUCH` |
| R8 | Bootloop 风险 | 保留 Phase 1/2 备份 + 回滚命令；仅视图层不参与系统 IPC，崩溃限于 SystemUI 进程 |

## 🔮 Step N+1 展望

- Stage 2：触摸通知栏下拉 + QS 面板（复用 Lite 的 `NotificationShadeManager` / `NotificationShadeView`）
- 接入 `IStatusBarService.registerStatusBar` 真握手 → 支持第三方应用 `StatusBarManager` API
- 完整 Slot 图标体系（`StatusBarIconList` / `DarkIconManager` / `ModernStatusBarWiFiView`）— 对齐 AOSP 管线
- 横屏 + 多屏 cutout 适配
- 状态栏 icon 暗色调自动切换（与 `WallpaperProvider` 的 `isDark` 之间直连）

每步按「最小改动 → 构建 → 设备验证 → 再迭代」推进。
