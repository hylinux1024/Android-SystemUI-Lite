# Findings — 手势架构重构调研

## 1. 现有手势流程全景

### 流程 A：左右侧滑返回 (Back Gesture)
```
用户从左/右边缘拖拽
  → GestureEdgeZone (ComposeView, 48dp 窄条窗口, TYPE_NAVIGATION_BAR_PANEL)
    → detectDragGestures → handler.onDown/onMove/onUp
      → GestureHandler 状态机 (GestureSession: ENTRY→ACTIVE→COMMITTED/CANCELLED)
        → GestureType.BACK 通过 onAction 回调
          → NavigationBarCoreStartable.wireHandlerAction()
            → sendKeyEvent(4) via Runtime.exec("input keyevent 4")
```

### 流程 B：底部上滑 → 最近应用 / 主屏幕 (Recents / Home)
```
用户从底部上滑
  → GestureBottomZone (ComposeView, 60dp 底部条窗口, TYPE_NAVIGATION_BAR_PANEL)
    → detectDragGestures → handler.onDown/onMove/onUp
      → GestureHandler 状态机 (GestureSession BOTTOM 区域)
        → onUp 时按距离判定:
            ≥ 150dp → GestureType.RECENTS → launchRecents() → RecentsCoreStartable.showRecents()
            ≥  80dp → GestureType.HOME    → sendKeyEvent(3)
            <  80dp → 取消
```

### 流程 C：状态栏下拉抽屉 (Notification Shade Pull-Down)
```
用户从状态栏下拉
  → StatusBar (ComposeView, TYPE_STATUS_BAR)
    → pointerInput { detectDragGestures } —— ⚠️ 完全独立、不经过 GestureHandler
      → onDrag:   onShadeDragUpdate(totalDragY) → shadeController.dragShade(progress)
      → onDragEnd: tap? → onShadeToggle()
                  drag? → fling判定 → shadeController.flingShade(0 or 1)
      → ShadeCoreStartable 驱动 _shadeProgress
        → StatusBar offset + NotificationShade 内容渲染
```

## 2. 问题清单：手势与业务逻辑的耦合点

### 🔴 问题 1: 状态栏下拉是一套完全独立的手势系统
**位置**: `StatusBar.kt:89-118`
**严重性**: 高
**详情**: `detectDragGestures` 直接内嵌在 StatusBar 组合函数里，不经过 GestureHandler。这意味着：
- 状态栏拖拽逻辑无法复用 GestureZones / GestureSession 已有的 zones 判定和状态机
- fling 判定 (`velocity > 800f && totalDragY > 40f`) 是手写魔法数，无测试覆盖
- shade progress 的物理模型（拖拽位移→进度映射）散落在 StatusBar 回调里
- 从架构角度看，「打开面板的手势」这一概念不是 first-class entity

### 🔴 问题 2: GestureHandler 背负了不属于它的职责
**位置**: `GestureHandler.kt`
**严重性**: 中
**详情**: 一个类同时做了 5 件事：
1. TouchZone 分类（边缘/底部/排除角）——合理，但直接依赖 GestureZones 又可
2. 边缘水平拖拽状态机（ENTRY→ACTIVE→INACTIVE→COMMITTED/CANCELLED）
3. 底部上滑距离→HOME/RECENTS 分类（`homeSwipeDp`/`recentsSwipeDp`）
4. 长-press 守卫（继承自旧版 "tap = no-op" 逻辑）
5. 导航模式守卫（直接读 Settings.Secure）
- 第 2、3 项本质上是两个独立的检测器，强塞进一个会话，互相抢占状态
- `onAction: (GestureType) -> Unit` 是一个万能回调，无法承载「返回手势取消了」这类事件；COMMITTED/CANCELLED/INACTIVE 三个状态对调用者来说都是同一个回调入口，无法做精细化 UI 响应（如跟手回弹）
- `Settings.Secure` 直读导致 handler 与 Android 框架耦合，虽然暴露了 `navigationModeProvider` 逃生通道，但默认仍是直读

### 🟡 问题 3: 回调链路过长，发送端必须知道接收端
**位置**: `NavigationBarCoreStartable.wireHandlerAction()`
**严重性**: 中
**详情**: 手势系统通过一个 `(GestureType) -> Unit` 的 oneway 回调把 "BACK/HOME/RECENTS" 扔给 NavigationBarCoreStartable，由它去调 `sendKeyEvent` 和 `launchRecents()`。这意味着：
- 手势系统的"commit"语义只有关/开，没有"committed → recents provider 加载了 → recents 面板可见了"的回路
- 当未来需要「返回手势拉出应用自己的返回抽屉」（如 Chrome 返回手势显示 tab 栈）时，这个 oneway 回调无法表达
- NavigationBarCoreStartable 同时承担 5 个职责（窗口工厂 / 手势回调 / 设置观察者 / Recents 启动器 / 生命周期托管），是事实上的 God Object

### 🟡 问题 4: GestureEdgePanel / GestureBottomHandle 是隐藏的业务泄漏
**位置**: `GestureEdgePanel.kt`、`NavigationBarView.kt:196-247`
**严重性**: 低-中
**详情**: 两个 affordance 都读取 `GestureSession` 的 state + progress 决定渲染。但「Pill 在 COMMITTED 后淡出」「底部拉条随 progress 变宽」是动画配方，不属于模型层。当前模型 `GestureSession.progress` 是 0..1 归一化值，controller 不知道也不该知道这个 0..1 具体对应多少 dp —— 但 progress 是 controller 写入的，语义耦合。

### 🟡 问题 5: 状态栏点击 (tap) 打开 shade 和拖拽打开 shade 的逻辑分裂
**位置**: `StatusBar.kt:97-107`
**严重性**: 低-中
**详情**: tap 和.drag 走同一个 `detectDragGestures`，靠 `absY < 8f && absX < 8f` 分支判定 tap。这虽然能工作，但 tap 是独立语义（不是「零长度拖拽」），合并让 fling 判定更复杂。

### 🟢 问题 6: GestureZones / GestureZonesTest 做得不错
**详情**: `GestureZones` 是纯函数对象、无状态、无 Android 依赖，配合 14 个 GestureZonesTest，可以作为统一的「坐标→区域判定」组件保留并共用。GestureHandler 的 `onDown` 内部也在用 `GestureZones.detectZone/isExcluded`。

## 3. 耦合热点热力图

| 文件 | 行数 | 耦合程度 | 具体问题 |
|------|------|---------|---------|
| StatusBar.kt | 260 | 🔴高 | 自带 detectDragGestures + fling 计算，不走 GestureHandler |
| GestureHandler.kt | 355 | 🟡中 | 背 5 个职责；万能 onAction 回调 |
| NavigationBarView.kt | 304 | 🟡中 | ZoneView 含大量坐标转换逻辑 + BottonHandle 动画配方 |
| NavigationBarCoreStartable.kt | 407 | 🔴高 | God Object：窗口工厂 + 回调线 + 设置观察器 + Recents 启动 |
| ShadeCoreStartable.kt | 294 | 🟡中 | 含手动动画循环和 shadeProgress state 暴露 |
| GestureEdgePanel.kt | 133 | 🟢低 | self-contained，只读 session，没泄漏 |
| GestureZones.kt | 72 | 🟢低 | 纯函数，完美 |

## 4. 值得保留的好设计

1. **GestureZones 纯函数对象**: 无状态、JVM 可测、分类优先级（边缘先于底部）正确。
2. **Insets/conversion 注释**: NavigationBarView 中的坐标转换 dp↔px 已经完整注释了之前的 bug 和修复方式，说明作者认真。
3. **GestureHandler 的长-press 守卫**: 用 `SupervisorJob + scope.delay` 而非 Handler.postDelayed，允许虚拟时间测试。
4. **GestureEdgePanel 自包含**: 仅读 session + type，自己拉 MaterialTheme 配色，不操作 window。
5. **测试先行的风格**: GestureHandler (22 个 test) + GestureZones (14 个 test) 覆盖率很高。
6. **Session 不可变 data class + copy**: 状态机通过替换整个 session 推进，符合 Compose 范式。

## 5. 重构目标

把三套手势统一到同一个架构下：
- **单一概念模型**: 所有手势都是「在某个 Zone 中，按某种 DragRule 跟踪 touch，产生 progress + 触发 Affordance」
- **关注点分离**: 输入识别 / 状态推进 / Affordance 渲染 / 业务动作 四层独立
- **从 oneway 回调升级到事件流**: 用 StateFlow 让 UI 和下游都能反映生命周期中间态（如 INACTIVE → 回弹）
- **StatusBar 不再自带 gesture detection**: 状态栏只负责「暴露一个可拖拽区域 + 提供当前高度」
- **NavigationBarCoreStartable 瘦身**: 窗口工厂和回调线分离

## 6. 不在本次重构范围

- 三键导航（THREE_BUTTON）模式不涉及手势检测，保持不变
- 具体动画配方（Pill 的 dp 数、Pill 颜色、BottomHandle 的 150dp 宽度）不属于架构关注点
- RecentsProvider 的 task 加载逻辑不变
