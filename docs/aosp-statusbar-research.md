# AOSP SystemUI 状态栏实现深度研究

> 基于 `~/android-os/SystemUI`（Android U / 现代架构）源码的深度调研，目的是在 `SystemUI-Lite-v0.2` 中复刻状态栏的**布局、手势、动画、事件处理**等核心功能。

---

## 目录

1. [整体架构总览](#1-整体架构总览)
2. [目录结构与核心类职责](#2-目录结构与核心类职责)
3. [状态栏布局与视图层级](#3-状态栏布局与视图层级)
4. [窗口创建与初始化流程](#4-窗口创建与初始化流程)
5. [触摸与手势处理管线](#5-触摸与手势处理管线)
6. [展开/收起动画系统](#6-展开收起动画系统)
7. [状态栏转场（BarTransitions）](#7-状态栏转场bartransitions)
8. [系统事件响应管线](#8-系统事件响应管线)
9. [自动隐藏（AutoHide）](#9-自动隐藏autohide)
10. [可触摸区域管理（StatusBarTouchableRegionManager）](#10-可触摸区域管理statustouchableregionmanager)
11. [关键资源文件清单](#11-关键资源文件清单)
12. [移植到 SystemUI-Lite 的关键模式](#12-移植到-systemui-lite-的关键模式)

---

## 1. 整体架构总览

AOSP 现代 SystemUI 的状态栏以 **`CentralSurfacesImpl`** 为编排中枢，采用 Dagger `@IntoMap` + `CoreStartable` 依赖注入体系。整体职责划分：

```
┌─────────────────────────────────────────────────────────────┐
│                    StatusBar (system process)               │
│  CoreStartable.start()                                      │
│    └── CentralSurfacesImpl.start()                          │
│          ├── makeStatusBarView()                            │
│          │     ├── inflateStatusBarWindow()   ← 加载根布局   │
│          │     ├── statusBarInitializer.initializeStatusBar()│
│          │     │     └── PhoneStatusBarView inflate         │
│          │     ├── setup touch/scrim/autohide               │
│          │     └── createNavigationBar()                    │
│          └── CommandQueueCallbacks 注册                     │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────────┐
              ▼               ▼                   ▼
    PhoneStatusBarView   NotificationPanelView   BarTransitions
    (状态栏窗口根视图)    (通知面板/控制中心)      (透明/半透转场)
              │               │                   │
              ▼               ▼                   ▼
    StatusBarWindowController  ShadeViewController   PhoneStatusBarTransitions
    (WindowManager 添加)       (展开/收起控制器)      (图标 alpha 动画)
```

**核心设计原则**：
- **View 驱动**：状态栏根视图 `PhoneStatusBarView extends FrameLayout`，通过 `setOnTouchListener` / `onTouchEvent` 直接接收触摸。
- **接口解耦**：`Gefingerpoken`（singlemethod "who can handle this touch"）+ `ShadeViewController` 接口隔离 Controller 与 View。
- **Window 分离（两个独立窗口）**：状态栏在**自己的窗口** (`super_status_bar.xml`, `TYPE_STATUS_BAR`)，通知面板/Scrim 在**另一个窗口** (`super_notification_shade.xml` + `status_bar_expanded.xml`, `TYPE_NOTIFICATION_SHADE`)，由 `NotificationShadeWindowControllerImpl` 分别添加管理。

---

## 2. 目录结构与核心类职责

### 2.1 `statusbar/phone/` — 状态栏专属（约 95 个文件）

| 类 | 职责 |
|---|---|
| `CentralSurfacesImpl.java` (177KB) | **编排中枢**：`CoreStartable`，创建/协调所有状态栏组件，注册 `CommandQueueCallbacks` |
| `PhoneStatusBarView.java` | 状态栏根视图 `FrameLayout`，分发触摸到 `mTouchEventHandler`（`Gefingerpoken`），处理挖孔屏适配 |
| `PhoneStatusBarViewController.kt` | 控制单个图标可见性、Dark 模式、disable flag |
| `StatusBarIconController[Impl].java` | 管理右侧 system_icons 区域内图标增删改 |
| `NotificationIconAreaController.java` | 管理左侧通知图标区域 |
| `NotificationIconContainer.java` | 通知图标容器布局 |
| `PhoneStatusBarTransitions.java` | 状态栏图标 alpha 转场动画（继承 `BarTransitions`） |
| `BarTransitions.java` | 抽象转场基类：定义 `MODE_OPAQUE/TRANSLUCENT/LIGHTS_OUT` 等 7 种模式 |
| `BarTransitions.BarBackgroundDrawable` | 背景 Drawable 的渐变/颜色动画 |
| `KeyguardStatusBarView.java` | 锁屏态状态栏 |
| `KeyguardBottomAreaView.kt` | 锁屏底部快捷入口（相机/电话） |
| `StatusBarWindowCallback.java` | 状态栏窗口回调 |
| `StatusBarTouchableRegionManager.java` | 计算状态栏可触摸区域（含 HeadsUp 突出部分） |
| `AutoHideController.java` | 自动隐藏 TransientBar 控制器（2.25s 超时） |
| `HeadsUpManagerPhone.java` | 通知 HeadsUp 管理 |
| `HeadsUpTouchHelper.java` | HeadsUp 触摸辅助 |
| `NotificationIconAreaController.java` | 通知图标区控制 |
| `ScrimController.java` | Scrim（模糊遮罩）控制 |
| `LightBarController.java` | 导航栏 Light Bar |
| `StatusBarNotificationPresenter.java` | 通知呈现 |

### 2.2 `statusbar/gesture/` — 手势检测（4 个文件）

> ⚠️ **重要澄清（常见误解）**：这个包**不用于面板展开/收起**！它是通过 `InputMonitorCompat` 全局监听屏幕手势，实际消费者是 `OngoingCallController`（滑动赶走 Ongoing Call chip）和 `ChipbarCoordinator`。面板拖拽走的是完全不同的路径：`NPVC.TouchHandler` + `DragDownHelper`。

| 类 | 职责 |
|---|---|
| `GenericGestureDetector.kt` | 抽象基类：通过 `InputMonitorCompat` 监听整个屏幕的输入事件，懒注册（有 callback 才监听） |
| `TapGestureDetector.kt` | 单击检测：封装 `GestureDetector.SimpleOnGestureListener.onSingleTapUp` |
| `SwipeUpGestureHandler.kt` | 上滑手势：检测起点在 bounds 内 + 上滑距离 ≥ 阈值 + 时间 < 500ms |
| `SwipeStatusBarAwayGestureHandler.kt` | "滑动赶走"：起点在 `statusBarHeight ~ 3*statusBarHeight` 之间，供 `OngoingCallController` 使用 |

### 2.3 `shade/` — 通知面板容器（约 40 个文件）

| 类 | 职责 |
|---|---|
| `NotificationPanelViewController.java` (5098行) | **通知面板核心控制器**：展开/收起/滚动/QS 切换/手势追踪 |
| `NotificationPanelView.java` | 面板根视图 (`ConstraintLayout`) |
| `ShadeViewController.kt` | 面板控制器接口 |
| `ShadeExpansionStateManager.kt` | 面板展开状态 (`expansionFraction`, `expanded`, `tracking`) 的注册/通知中心 |
| `ShadeExpansionListener.kt` / `ShadeExpansionChangeEvent.kt` | 展开事件 listener + 事件 data class |
| `NotificationShadeWindowView.java` | 通知面板的 **根 FrameLayout** |
| `NotificationShadeWindowControllerImpl.java` | 管理通知面板窗口的 WindowManager 添加/参数 |
| `NotificationShadeWindowViewController.java` | 窗口级触摸/手势路由 |
| `QuickSettingsController.java` | 快捷设置面板控制 |
| `NotificationsQuickSettingsContainer.java` | 通知+QS 的纵向滚动容器 |
| `NotificationsQSContainerController.kt` | 通知+QS 容器控制器 |
| `LockscreenShadeTransitionController.kt` | **锁屏→通知面板** 滑动过渡：拖拽锁屏打开通知 |
| `ShadeController.java` | 面板展开/收起命令入口（`collapseShade()`、`expand()`） |

### 2.4 `statusbar/notification/` — 通知管线（约 54 个文件）

| 类 | 职责 |
|---|---|
| `NotificationShelf.java` | 通知 shelf（锁屏/展开态过渡区域） |
| `NotificationStackScrollLayout` (`stack/` 下) | 通知列表滚动容器 |
| `NotificationEntryListener.java` | 通知条目监听（添加/更新/移除） |
| `NotificationClicker.java` | 通知点击处理 |
| `NotificationSectionsFeatureManager.kt` | 通知分段（Alerting/沉默/沉默） |

### 2.5 `animation/` — 动画框架（共享）

| 类 | 职责 |
|---|---|
| `LaunchAnimator.kt` | 启动动画协调（通知点击展开动画） |
| `ViewHierarchyAnimator.kt` | 层级视图动画 |
| `ShadeInterpolation.kt` | 通知面板展开的插值工具 |
| `GhostedViewLaunchAnimatorController.kt` | Ghosted View 启动控制 |

---

## 3. 状态栏布局与视图层级

### 3.1 `res/layout/status_bar.xml` — 状态栏根

这是 **`PhoneStatusBarView`** 的完整结构。由 `status_bar_expanded.xml` 中的 `<include>` 或直接 inflate 加载。

```
PhoneStatusBarView (FrameLayout, id=status_bar, height=status_bar_height)
│
├── ImageView (id=notification_lights_out)           ← 小圆点指示灯，默认 gone
│
└── LinearLayout (id=status_bar_contents, horizontal, clipChildren=false)
    │
    ├── FrameLayout (id=status_bar_start_side_container, weight=1)     ← 左侧区域
    │   └── FrameLayout (id=status_bar_start_side_content)
    │       ├── <include layout="@layout/heads_up_status_bar_layout"/>  ← HeadsUp 区域
    │       └── LinearLayout (id=status_bar_start_side_except_heads_up)
    │           ├── ViewStub (id=operator_name)                          ← 运营商名称
    │           ├── Clock (id=clock, TextAppearance.StatusBar.Clock)    ← 时钟
    │           ├── <include layout="@layout/ongoing_call_chip"/>        ← 通话 chip
    │           └── AlphaOptimizedFrameLayout (id=notification_icon_area) ← 通知图标区
    │
    ├── Space (id=cutout_space_view, 默认 gone)                         ← 挖孔屏 spacer
    │
    └── FrameLayout (id=status_bar_end_side_container, weight=1)        ← 右侧区域
        └── AlphaOptimizedLinearLayout (id=status_bar_end_side_content, gravity=end)
            ├── <include layout="@layout/status_bar_user_chip_container"/> ← 用户头像
            └── <include layout="@layout/system_icons"/>                   ← 系统图标
```

### 3.2 `res/layout/system_icons.xml` — 系统图标

```xml
<merge>
  system_icons (LinearLayout horizontal)
  ├── statusIcons (LinearLayout horizontal, weight=1)
  │   ├── signal (StatusBarMobileView / StatusBarWifiView)
  │   └── ... (其他 status icons via StatusBarIconController)
  └── battery (BatteryView)
      └── battery_level (TextView, 百分比)
```

### 3.3 `res/layout/status_bar_expanded.xml` — 展开态面板根

```
NotificationPanelView (id=notification_panel, ConstraintLayout, match_parent)
│
├── LongPressHandlingView (id=keyguard_long_press)                      ← 锁屏长按处理
├── ViewStub (id=keyguard_qs_user_switch_stub)                          ← QS 用户切换
├── <include layout="@layout/status_bar_expanded_plugin_frame"/>         ← 插件 frame
│
└── NotificationsQuickSettingsContainer (id=notification_container_parent, 纵向滚动容器)
    │
    ├── <include layout="@layout/keyguard_status_view"/> (gone)          ← 锁屏视图
    ├── <include layout="@layout/dock_info_overlay"/>                    ← dock 信息
    │
    ├── FrameLayout (id=qs_frame)                                       ← QS 框架
    │   └── inflate qs_panel.xml
    │
    ├── ViewStub (id=qs_header_stub)                                    ← QS header
    ├── Guideline (id=qs_edge_guideline)                                ← 中心参考线
    │
    ├── <include layout="@layout/notification_stack_scroll_layout"/>     ← 通知列表
    ├── <include layout="@layout/photo_preview_overlay"/>
    ├── <include layout="@layout/keyguard_status_bar"/> (invisible)
    │
    ├── Button (id=report_rejected_touch, gone)                          ← 被拒触摸报告
    └── TapAgainView (id=shade_falsing_tap_again, gone)                  ← 再次点击提示
```

### 3.4 关键 View ID 契约

| ID | 宿主 | 作用 |
|---|---|---|
| `status_bar` | PhoneStatusBarView | 状态栏根，用于 WindowManager addView 和 dumpsys |
| `status_bar_contents` | LinearLayout | 内容容器，padding 动态设置 |
| `clock` | Clock | 时钟 TextView，Dark 模式 tint |
| `battery` | BatteryView | 电池视图 |
| `status_bar_start_side_except_heads_up` | LinearLayout | 除 HeadsUp 外左侧视图，alpha 受 `PhoneStatusBarTransitions` 控制 |
| `cutout_space_view` | Space | 挖孔屏 spacer，尺寸=挖孔 bounding rect |
| `notification_panel` | NotificationPanelView | 展开态面板根 |
| `notification_container_parent` | NotificationsQuickSettingsContainer | 通知/QS 滚动容器 |
| `qs_frame` | FrameLayout | QS 面板框架 |
| `notification_icon_area` | AlphaOptimizedFrameLayout | 通知图标区域 |

---

## 4. 窗口创建与初始化流程

### 4.1 启动入口

```
SystemUIApplication.onCreate()
  → CoreStartable.start() [Dagger 注入]
    → CentralSurfacesImpl.start()
      → createAndAddWindows()
        → makeStatusBarView(result)
```

### 4.2 `makeStatusBarView()` — 视图构建核心

```java
// CentralSurfacesImpl.java:1267
protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
    updateDisplaySize();        // populates mDisplayMetrics
    updateResources();          // 资源
    updateTheme();              // 主题

    inflateStatusBarWindow();   // ① 加载 NotificationShadeWindowView

    mNotificationShadeWindowView.setOnTouchListener(getStatusBarWindowTouchListener());

    // ② 设置 HeadsUp 的 shelf
    mNotificationIconAreaController.setupShelf(mNotificationShelfController);

    // ③ PhoneStatusBarView inflate + 绑定 controller
    mStatusBarInitializer.setStatusBarViewUpdatedListener(
        (statusBarView, statusBarViewController, statusBarTransitions) -> {
            mStatusBarView = statusBarView;
            mPhoneStatusBarViewController = statusBarViewController;
            mStatusBarTransitions = statusBarTransitions;
            // ...重新传播展开状态
            mShadeSurface.updateExpansionAndVisibility();
            checkBarModes();
        });
    mStatusBarInitializer.initializeStatusBar(...);

    // ④ 触摸区域、导航栏、AutoHide、Scrim
    mStatusBarTouchableRegionManager.setup(this, mNotificationShadeWindowView);
    createNavigationBar(result);
    mAutoHideController.setStatusBar(new AutoHideUiElement() { ... });
    mScrimController.attachViews(scrimBehind, notificationsScrim, scrimInFront);

    // ⑤ 面板 init
    mShadeSurface.initDependencies(this, mGestureRec, ...);
}
```

### 4.3 `inflateStatusBarWindow()` — 窗口 inflate

```java
private void inflateStatusBarWindow() {
    mNotificationShadeWindowView = mCentralSurfacesComponent
        .getNotificationShadeWindowView();   // factory 构建
    // 设置 layout params、touch listener
    mNotificationShadeWindowController
        .setNotificationShadeWindowView(mNotificationShadeWindowView);
    // WindowManager addView 在 StatusBarWindowController 完成
}
```

### 4.4 两个窗口的 LayoutParams（移植核心）

#### 状态栏窗口（`super_status_bar.xml`, PhoneStatusBarView 宿主）

```kotlin
WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    height,                                        // status_bar_height，兜底 24dp
    WindowManager.LayoutParams.TYPE_STATUS_BAR,     // 系统状态栏窗口类型
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
        or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP
    setTitle("StatusBar")
    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    setFitInsetsTypes(0)
}
```

#### 通知面板窗口（`super_notification_shade.xml`, NotificationShadeWindowView 宿主）

```kotlin
// NotificationShadeWindowControllerImpl.attach()
WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
        or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP
    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    // insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    // privateFlags |= OPTIMIZE_MEASURE | BEHAVIOR_CONTROLLED
}
```

**状态栏字段契约**：

**字段契约**：

| 字段 | 值 | 原因 |
|---|---|---|
| `type` | `TYPE_STATUS_BAR` | 系统窗口，需 `INTERNAL_SYSTEM_WINDOW` 权限 |
| `flags` | `NOT_FOCUSABLE \| SPLIT_TOUCH \| TOUCHABLE_WHEN_WAKING \| DRAWS_SYSTEM_BAR_BACKGROUNDS` | 不抢焦点/支持 split touch/唤醒可触摸/绘制系统栏背景 |
| `gravity` | `Gravity.TOP` | 吸顶 |
| `format` | `TRANSLUCENT` | 透明背景 |
| `layoutInDisplayCutoutMode` | `ALWAYS` | 延伸到挖孔区域 |
| `setFitInsetsTypes` | `0` | 手动处理 insets |

---

## 5. 触摸与手势处理管线

### 5.1 触摸事件流

```
MotionEvent (系统 InputDispatcher)
  ↓
PhoneStatusBarView.onInterceptTouchEvent()
  → mTouchEventHandler.onInterceptTouchEvent(event)    ← 先询问 handler 是否拦截
  → super.onInterceptTouchEvent(event)
  ↓ (若拦截)
PhoneStatusBarView.onTouchEvent(event)
  → mTouchEventHandler.onTouchEvent(event)            ← 交给 handler 处理
```

### 5.2 `PhoneStatusBarView` 触摸分发

```java
// PhoneStatusBarView.java:176-196
@Override
public boolean onTouchEvent(MotionEvent event) {
    if (mTouchEventHandler == null) {
        Log.w(TAG, "onTouch: No touch handler provided; eating gesture");
        return true;   // 没有 handler 也要吃掉事件，防止穿透
    }
    return mTouchEventHandler.onTouchEvent(event);
}

@Override
public boolean onInterceptTouchEvent(MotionEvent event) {
    mTouchEventHandler.onInterceptTouchEvent(event);
    return super.onInterceptTouchEvent(event);
}

void setTouchEventHandler(Gefingerpoken handler) {
    mTouchEventHandler = handler;
}
```

**`Gefingerpoken` 接口**（single method，"谁会处理这个 touch"）：
```java
public interface Gefingerpoken {
    boolean onTouchEvent(MotionEvent ev);
    default boolean onInterceptTouchEvent(MotionEvent ev) { return false; }
}
```

### 5.3 手势检测器体系

`statusbar/gesture/` 下的手势检测器通过 **`InputMonitorCompat`** 直接监听整个屏幕的输入（而不是通过 View 回调），这使得它们在面板未展开时也能检测手势。

#### `GenericGestureDetector.kt` — 抽象基类

```kotlin
abstract class GenericGestureDetector(
    private val tag: String,
    private val displayId: Int,
) {
    private val callbacks: MutableMap<String, (MotionEvent) -> Unit> = mutableMapOf()
    private var inputMonitor: InputMonitorCompat? = null

    fun addOnGestureDetectedCallback(tag: String, callback: (MotionEvent) -> Unit) {
        val callbacksWasEmpty = callbacks.isEmpty()
        callbacks[tag] = callback
        if (callbacksWasEmpty) startGestureListening()   // 懒启动
    }

    internal open fun startGestureListening() {
        inputMonitor = InputMonitorCompat(tag, displayId).also {
            inputReceiver = it.getInputReceiver(
                Looper.getMainLooper(),
                Choreographer.getInstance(),
                this::onInputEvent   // 每帧回调到子类
            )
        }
    }

    abstract fun onInputEvent(ev: InputEvent)   // 子类实现

    internal fun onGestureDetected(e: MotionEvent) {
        callbacks.values.forEach { it.invoke(e) }   // 通知所有回调
    }
}
```

**设计亮点**：只有注册 callback 时才启动 InputMonitor，避免无谓电量消耗。

#### `TapGestureDetector.kt` — 单击检测

```kotlin
class TapGestureDetector @Inject constructor(
    context: Context, displayTracker: DisplayTracker
) : GenericGestureDetector(...) {

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onGestureDetected(e)
            return true
        }
    }

    override fun onInputEvent(ev: InputEvent) {
        gestureDetector!!.onTouchEvent(ev)   // 委托给 Android GestureDetector
    }
}
```

#### `SwipeUpGestureHandler.kt` — 上滑手势检测

```kotlin
abstract class SwipeUpGestureHandler(
    context, displayTracker, logger, loggerTag
) : GenericGestureDetector(...) {

    private var startY: Float = 0f
    private var startTime: Long = 0L
    private var monitoringCurrentTouch: Boolean = false
    private var swipeDistanceThreshold = resources.getDimensionPixelSize(
        com.android.internal.R.dimen.system_gestures_start_threshold)

    override fun onInputEvent(ev: InputEvent) {
        when (ev.actionMasked) {
            ACTION_DOWN -> {
                if (startOfGestureIsWithinBounds(ev)) {   // 子类定义合法起点
                    startY = ev.y
                    startTime = ev.eventTime
                    monitoringCurrentTouch = true
                }
            }
            ACTION_MOVE -> {
                if (!monitoringCurrentTouch) return
                if (ev.y < startY &&                                    // 向上
                    (startY - ev.y) >= swipeDistanceThreshold &&        // 距离足够
                    (ev.eventTime - startTime) < SWIPE_TIMEOUT_MS) {    // 时间 < 500ms
                    monitoringCurrentTouch = false
                    onGestureDetected(ev)   // 触发回调
                }
            }
            ACTION_CANCEL, ACTION_UP -> { monitoringCurrentTouch = false }
        }
    }

    abstract fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean
}
```

**识别条件三要素**：
1. 起点在合法区域（子类定义，"下滑赶走状态栏"要求 `y ∈ [statusBarHeight, 3*statusBarHeight]`）
2. 上滑距离 ≥ `system_gestures_start_threshold`
3. 完成时间 < 500ms

#### `SwipeStatusBarAwayGestureHandler.kt` — 下滑赶走

```kotlin
class SwipeStatusBarAwayGestureHandler @Inject constructor(
    ..., private val statusBarWindowController: StatusBarWindowController
) : SwipeUpGestureHandler(...) {

    override fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean {
        // 起点必须在状态栏下方 ~ 3 倍状态栏高度之间
        return ev.y >= statusBarWindowController.statusBarHeight &&
               ev.y <= 3 * statusBarWindowController.statusBarHeight
    }
}
```

### 5.4 通知面板触摸 — `NPVC.TouchHandler`（触摸的核心）

`NotificationPanelViewController` (5098行) 的触摸由内部类 **`TouchHandler`** 处理，它同时实现 `View.OnTouchListener` 和 `Gefingerpoken`。

**`TouchHandler.onInterceptTouchEvent` 的严格优先级链**：

```
① mQsController.disallowTouches() → 直接 false
② initDownStates(event) ← 记录 mDownX/mDownY/mLastDownEvents(50事件环形缓冲)
③ 若 Bouncer 正在显示 → true（下滑 dismiss bouncer）
④ HeadsUpTouchHelper.onInterceptTouchEvent() ← HUN peek 优先
⑤ PulseExpansionHandler.onInterceptTouchEvent() ← pulsing 展开
⑥ QsController.onIntercept() ← QS 拖拽（仅非完全收起时）
⑦ 多指处理（KEYGUARD 下第二指打断）
⑧ ACTION_MOVE: 垂直拖动 > touchSlop → startExpandMotion + return true
```

**`TouchHandler.onTouch()` → `onTouchEvent(event)`**：

```
① 重复 down 过滤
② bouncer-scrimmed / over-dream 守卫
③ PulseExpansionHandler.onTouchEvent()
④ HeadsUpTouchHelper.onTouchEvent()
⑤ QsController.handleTouch() ← QS 自有触摸
⑥ handleTouch(event)
    ├── ACTION_DOWN  → startExpandMotion(x,y,false), onTrackingStarted()
    ├── ACTION_MOVE  → 当 |h|>touchSlop: setExpandedHeightInternal(newHeight)  ← 实时驱动面板
    ├── ACTION_UP/CANCEL → endMotionEvent → fling/停止 tracking
    └── 多指处理（tracking-pointer 切换）
```

> 注意：这个 `TouchHandler` **才是面板展开的真正入口**，不是 `gesture/` 包。

关键概念：
- **`mQsExpanded`** — QS 是否展开
- **`mQsFullyExpanded`** — QS 是否完全展开
- **`mExpanding`** — 面板是否在用户手指拖动下展开（tracking）
- **`muminationFraction`** — 展开比例 0.0~1.0

### 5.5 锁屏→通知面板滑动过渡

`LockscreenShadeTransitionController.kt` (951行) + 内部 `DragDownHelper (Gefingerpoken)` 处理从锁屏拖拽打开通知面板：

```
用户从屏幕顶部下滑
  → DragDownHelper.onInterceptTouchEvent: ACTION_MOVE 且 h>touchSlop && h>|x-initialX|
      → isDraggingDown=true, captureStartingChild, onDragDownStarted
  → DragDownHelper.onTouchEvent ACTION_MOVE
      → dragDownCallback.dragDownAmount = lastHeight + dragDownAmountOnStart ← 核心属性
      → dragDownAmount setter 自动计算 fractionToShade 并传播到：
          ├── nsslController.setTransitionToFullShadeAmount (通知列表)
          ├── qsTransitionController.dragDownAmount (QS)
          ├── mediaHierarchyManager (媒体)
          ├── scrimTransitionController (scrim)
          ├── keyguardTransitionController (锁屏)
          ├── shadeOverScroller.expansionDragDownAmount
          └── centralSurfaces.setTransitionToFullShadeProgress
      → rubberbanding: RUBBERBAND_FACTOR_STATIC=0.15f / EXPANDABLE=0.5f
  → ACTION_UP → onDraggedDown(startingChild, dragLength)
      → 通过 falsing 检测 → goToLockedShadeInternal()
          → shadeViewController.transitionToExpandedShade(delay)
          → setTransitionToFullShadeAmount
```

**`canDragDown()` 条件**：`(KEYGUARD || lockedDownShade) && (QS fully collapsed || splitShade)`

---

## 6. 展开/收起动画系统

### 6.1 ShadeExpansionStateManager — 展开状态中心

```kotlin
// ShadeExpansionStateManager.kt 核心
data class ShadeExpansionChangeEvent(
    val expansionFraction: Float,   // 0.0 = 收起, 1.0 = 完全展开
    val expanded: Boolean,          // 是否展开
    val tracking: Boolean,          // 用户手指正在拖动
    ...
)

interface ShadeExpansionListener {
    fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent)
}
```

多个组件注册 listener：`WakeUpCoordinator`、`StatusBarStateController`、`ShadeHeaderController`、`QsFrameTranslateController` 等。

### 6.2 ShadeController — 展开/收起命令

```java
// ShadeController.java 关键方法
public interface ShadeController {
    void collapseShade();                           // 收起
    void collapseShade(boolean animate);            // 带动画收起
    void collapseShade(int duration);               // 指定时长
    void collapseFromQS();                          // 从 QS 收起
    void collapsePanels();                          // 收起所有面板
    void postAnimateCollapse();                     // post 到主线程收起
    void expandWithQs();                            // 展开带 QS
    void expandToQs();                              // 展开到 QS
    void expandNotificationsPanel();                // 展开通知面板
    void addPostCollapseAction(Runnable action);    // 收起后执行
}
```

### 6.3 收起动画时长

```java
// NotificationPanelViewController 中的典型值
private static final int COLLAPSE_FADE_ANIMATION_DURATION_MS = 200;
private static final int FLING_COLLAPSE_DURATION = 300;
```

### 6.4 通知点击展开动画（LaunchAnimator）

```kotlin
// LaunchAnimator.kt — 通知点击 → 应用展开动画
// 通知行点击 → 触发 StatusBarLaunchAnimatorController
//   → 使用 GhostedView 拉伸动画
//   → 跟随手指/内容展开
```

### 6.5 物理动画框架（PhysicsAnimationLayout）

用于 Bubbles 等场景：

```kotlin
// PhysicsAnimationLayout + PhysicsAnimationController
// 配置: getAnimatedProperties() = {TRANSLATION_X, TRANSLATION_Y}
// 控制: animationForChild(view).translationX(100).translationY(200).start()
// 链式: getNextAnimationInChain() 设置动画跟随
```

---

## 7. 状态栏转场（BarTransitions）

### 7.1 7 种模式

```java
// BarTransitions.java
public static final int MODE_TRANSPARENT         = 0;   // 完全透明
public static final int MODE_SEMI_TRANSPARENT    = 1;   // 半透明
public static final int MODE_TRANSLUCENT         = 2;   // 微透明
public static final int MODE_LIGHTS_OUT          = 3;   // Lights out（锁屏暗光）
public static final int MODE_OPAQUE              = 4;   // 不透明
public static final int MODE_WARNING             = 5;   // 警告色
public static final int MODE_LIGHTS_OUT_TRANSPARENT = 6; // Lights out 透明
```

### 7.2 转场时长

```java
public static final int LIGHTS_IN_DURATION   = 250;   // 从 lights out 恢复
public static final int LIGHTS_OUT_DURATION  = 1500;  // 进入 lights out
public static final int BACKGROUND_DURATION  = 200;   // 背景渐变
```

### 7.3 `PhoneStatusBarTransitions` — 图标 alpha 动画

```java
// PhoneStatusBarTransitions.java
private float getNonBatteryClockAlphaFor(int mode) {
    return isLightsOut(mode) ? 0                              // Lights out 全隐
         : !isOpaque(mode) ? 1                                // 非 opaque 全显
         : mIconAlphaWhenOpaque;                              // opaque 用配置值
}

private float getBatteryClockAlpha(int mode) {
    return isLightsOut(mode) ? 0.5f : getNonBatteryClockAlphaFor(mode);
    // 注：Lights out 时电池/时钟仍 0.5f 可见（非电池时钟全隐）
}

private void applyMode(int mode, boolean animate) {
    float newAlpha = getNonBatteryClockAlphaFor(mode);
    float newAlphaBC = getBatteryClockAlpha(mode);
    if (animate) {
        AnimatorSet anims = new AnimatorSet();
        anims.playTogether(
            animateTransitionTo(mStartSide, newAlpha),      // 左侧 alpha
            animateTransitionTo(mStatusIcons, newAlpha),     // 状态图标 alpha
            animateTransitionTo(mBattery, newAlphaBC)        // 电池 alpha
        );
        if (isLightsOut(mode)) anims.setDuration(LIGHTS_OUT_DURATION);
        anims.start();
    } else {
        mStartSide.setAlpha(newAlpha);
        mStatusIcons.setAlpha(newAlpha);
        mBattery.setAlpha(newAlphaBC);
    }
}
```

**关键逻辑**：状态栏图标的可见性不是简单的 show/hide，而是根据模式计算 alpha 值做渐变动画。**电池和时钟在 Lights out 时保持 0.5f 可见**，其他图标 Lights out 时完全隐藏。

### 7.4 `BarBackgroundDrawable` — 背景渐变动画

```java
// BarTransitions.BarBackgroundDrawable.draw()
int targetColor;
if (mMode == MODE_WARNING)         targetColor = mWarning;
else if (mMode == MODE_TRANSLUCENT) targetColor = mSemiTransparent;
else if (mMode == MODE_SEMI_TRANSPARENT) targetColor = mSemiTransparent;
else if (mMode == MODE_TRANSPARENT || mMode == MODE_LIGHTS_OUT_TRANSPARENT)
                                   targetColor = mTransparent;
else                               targetColor = mOpaque;

// 动画使用 LinearInterpolator，逐帧 invalidateSelf() 重绘
final float t = (now - mStartTime) / (float)(mEndTime - mStartTime);
final float v = Interpolators.LINEAR.getInterpolation(t);
// 对 RGBA 各通道分别插值
mColor = Color.argb(
    (int)(v * Color.alpha(targetColor) + Color.alpha(mColorStart) * (1 - v)), ...
);
```

---

## 8. 系统事件响应管线

### 8.1 CommandQueue — 系统与 SystemUI 的 IPC 桥

```java
// CommandQueue.java (77KB) — IStatusBar.Stub 的 SystemUI 侧
// 系统侧通过 IStatusBarService 调用：
//   animateExpandNotificationsPanel()
//   animateCollapsePanels()
//   setWindowState()
//   disable() / disable2()
//   onSystemBarAttributesChanged()
```

`CentralSurfacesImpl` 注册 `CommandQueueCallbacks` 接收系统侧调用：

```java
// CentralSurfacesImpl.CommandQueueCallbacks
@Override
public void animateExpandSettingsPanel(@Nullable String subpanel) {
    mCommandQueueCallbacks.animateExpandSettingsPanel(subpanel);
}
@Override
public void togglePanel() {
    mCommandQueueCallbacks.togglePanel();
}
```

### 8.2 面板展开事件流（完整链路）

```
系统/用户触发展开（通知下滑/togglePanel）
  → CommandQueueCallbacks.animateExpandSettingsPanel()
    → ShadeController → NotificationPanelViewController.expand()
      → 设置 mExpandedFraction = 1.0（或动画过渡）
      → ShadeExpansionStateManager 通知所有 listener
        ├── StatusBarStateController.onPanelExpansionChanged()
        ├── QsPanel 显示/隐藏
        ├── ScrimController 更新 scrim alpha
        └── PhoneStatusBarView alpha 更新
```

### 8.3 面板收起事件流

```
系统触发收起（点击空白处/Home键/超时）
  → ShadeController.collapseShade()
    → NotificationPanelViewController.cancelHeightAnimator()
    → 动画 mExpandedFraction → 0.0
    → StatusBarState 切回 STATE_KEYGUARD / STATE_SHADE_LOCKED
    → ShadeExpansionStateManager 通知
```

### 8.4 电池/网络事件流

```
系统广播 ACTION_BATTERY_CHANGED
  → BatteryController (BroadcastReceiver)
    → 解析 level/scale/status/plugged
    → 通知 listener 更新 BatteryView
      → BatteryMeterView 更新图标/百分比/充电动画
```

```
系统广播 RSSI_CHANGED / 基站信息变化
  → WifiController / MobileController (PhoneStateListener)
    → 更新 WifiView / MobileView
      → 更新信号强度图标
```

### 8.5 DarkIconDispatcher 模式

```java
// DarkIconDispatcher — 通知各 View 当前 dark 程度
public interface DarkReceiver {
    void onDarkChanged(@NonNull Rect area, float darkIntensity, @NonNull int[] insets);
}

// PhoneStatusBarView.onAttachedToWindow() 注册：
Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClock);
// onDetachedFromWindow() 移除
```

`darkIntensity` 0.0~1.0 表示背景暗度，View 据此决定自身颜色（白↔深灰）。

---

## 9. 自动隐藏（AutoHide）

### 9.1 AutoHideController

```java
// AutoHideController.java
private static final int AUTO_HIDE_TIMEOUT_MS = 2250;      // 默认超时
private static final int USER_AUTO_HIDE_TIMEOUT_MS = 350;  // 用户触摸后的超时

public void touchAutoHide() {
    if (isAnyTransientBarShown()) {
        scheduleAutoHide();    // 有 bar 显示 → 启动 2.25s 倒计时
    } else {
        cancelAutoHide();
    }
}

public void checkUserAutoHide(MotionEvent event) {
    // ACTION_OUTSIDE + x=0,y=0 = 触摸到了其他 bar 以外的地方
    boolean shouldHide = isAnyTransientBarShown()
        && event.getAction() == MotionEvent.ACTION_OUTSIDE
        && event.getX() == 0 && event.getY() == 0;
    // + shouldHideOnTouch() 检查
    if (shouldHide) userAutoHide();
}

private final Runnable mAutoHide = () -> {
    if (isAnyTransientBarShown()) hideTransientBars();
};
```

### 9.2 AutoHideUiElement 接口

```java
public interface AutoHideUiElement {
    void synchronizeState();        // 同步状态（checkBarModes）
    boolean shouldHideOnTouch();    // 触摸时是否隐藏
    boolean isVisible();            // 当前是否可见
    void hide();                    // 执行隐藏
}
```

`CentralSurfacesImpl` 把自己的 `AutoHideUiElement` 实现注册到 `AutoHideController`：

```java
mAutoHideController.setStatusBar(new AutoHideUiElement() {
    @Override public void synchronizeState() { checkBarModes(); }
    @Override public boolean shouldHideOnTouch() {
        return !mRemoteInputManager.isRemoteInputActive();   // RemoteInput 时不隐藏
    }
    @Override public boolean isVisible() { return isTransientShown(); }
    @Override public void hide() { clearTransient(); }
});
```

---

## 10. 可触摸区域管理（StatusBarTouchableRegionManager）

### 10.1 职责

计算状态栏的 **可触摸区域 Region**，通过 `OnComputeInternalInsetsListener` 上报给系统。主要影响：**HeadsUp 通知突出状态栏时，突出部分也应响应触摸**。

### 10.2 关键逻辑

```java
// StatusBarTouchableRegionManager.java
public StatusBarTouchableRegionManager(
    Context context,
    NotificationShadeWindowController notificationShadeWindowController,
    ConfigurationController configurationController,
    HeadsUpManagerPhone headsUpManager,
    ShadeExpansionStateManager shadeExpansionStateManager,
    UnlockedScreenOffAnimationController unlockedScreenOffAnimationController
) {
    // 监听配置变化/面板展开/HeadsUp 变化
    // 计算 mTouchableRegion
}

// 通过 ViewTreeObserver.OnComputeInternalInsetsListener 上报
private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener = ...
```

### 10.3 影响区域

- 状态栏自身区域（`status_bar_height` 高度横条）
- HeadsUp 突出部分（通知弹出时向下延伸）
- 展开态面板（展开时整个屏幕可触摸）

---

## 11. 关键资源文件清单

### 11.1 布局文件

| 文件 | 作用 |
|---|---|
| `status_bar.xml` | 状态栏根布局（PhoneStatusBarView） |
| `status_bar_expanded.xml` | 展开态面板根（NotificationPanelView） |
| `status_bar_mobile_signal_group.xml` | 蜂窝信号组 |
| `status_bar_wifi_group.xml` / `status_bar_wifi_group_inner.xml` / `new_status_bar_wifi_group.xml` | WiFi 图标组 |
| `system_icons.xml` | 系统图标容器 |
| `heads_up_status_bar_layout.xml` | HeadsUp 状态栏内布局 |
| `keyguard_status_bar.xml` | 锁屏态状态栏 |
| `status_bar_notification_shelf.xml` | 通知 shelf |
| `status_bar_notification_row.xml` | 单条通知行 |
| `status_bar_notification_section_header.xml` | 分段 header |
| `status_bar_notification_footer.xml` | 通知 footer |
| `status_bar_no_notifications.xml` | 无通知占位 |
| `status_bar_user_chip_container.xml` | 用户头像容器 |
| `super_status_bar.xml` | 超级状态栏 |
| `dock_info_overlay.xml` | dock 信息 |
| `quick_status_bar_expanded_header.xml` | QS header |

### 11.2 关键 dimens

| 资源 | 含义 |
|---|---|
| `status_bar_height` | 状态栏高度 |
| `status_bar_icon_size` | 图标尺寸 |
| `status_bar_padding_start/end/top` | 内边距 |
| `status_bar_clock_size` | 时钟字体 |
| `status_bar_left_clock_starting_padding` | 时钟左侧 padding |
| `status_bar_battery_icon_height/width` | 电池图标尺寸 |
| `display_cutout_margin_consumption` | 挖孔侧边偏移 |
| `status_bar_icon_drawing_alpha` | opaque 模式下图标 alpha |

### 11.3 颜色

| 资源 | 含义 |
|---|---|
| `status_bar_icon_tint_dark` | 暗色模式图标 tint（白） |
| `status_bar_icon_tint_light` | 亮色模式图标 tint（深灰） |
| `system_bar_background_opaque` | 不透明背景色 |
| `system_bar_background_semi_transparent` | 半透明背景色 |
| `system_bar_background_transparent` | 透明背景色 |

### 11.4 动画相关

| 资源/类 | 作用 |
|---|---|
| `ShadeInterpolation.kt` | 面板展开插值 |
| `Interpolators` (app/animation) | 动画插值器 |
| `BarTransitions.LIGHTS_IN_DURATION` | 250ms |
| `BarTransitions.LIGHTS_OUT_DURATION` | 1500ms |
| `BarTransitions.BACKGROUND_DURATION` | 200ms |

### 11.5 关键 drawable

- `stat_sys_*.xml` — 状态栏 vector 图标（信号/电池/WiFi/蓝牙等），装在 SystemUI APK 内，移植需复制
- `status_background` — 状态栏背景
- `ic_sysbar_lights_out_dot_small` — 小圆点指示灯

---

## 12. 移植到 SystemUI-Lite 的关键模式

> **移植核心洞察**：
> 1. `gesture/` 包是"红鲱鱼"——面板展开走 `NPVC.TouchHandler`，不走手势检测器
> 2. 状态变更用**拉取式** `apply(state)` 状态机，而非推送
> 3. 拖拽传播的核心是 `dragDownAmount` setter，它自动分发到 scrim/QS/UDFPS/媒体
> 4. 触摸拦截有**严格优先级链**：bouncer → HUN → Pulse → QS → 垂直拖拽

### 12.1 触摸处理模式（简化实现）

```kotlin
// 模式 1: 触摸代理（对齐 PhoneStatusBarView.setTouchEventHandler）
class StatusBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var touchHandler: ((MotionEvent) -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler?.invoke(event) ?: true   // 无 handler 也吃掉事件
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // 可在此决定是否拦截，交给子 view 先处理
        return super.onInterceptTouchEvent(event)
    }
}

// 使用: statusBarView.touchHandler = controller::handleTouch
```

### 12.2 手势检测模式（简化实现）

```kotlin
// 模式 2: 面板展开手势（对齐 SwipeUpGestureHandler 的三要素判定）
class SwipeOpenGestureHandler(
    private val statusBarHeight: Int,
    private val onGestureTriggered: () -> Unit
) : View.OnTouchListener {

    private var startY = 0f
    private var startTime = 0L
    private var monitoring = false
    private val threshold = resources.getDimensionPixelSize(
        R.dimen.swipe_open_threshold)

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            ACTION_DOWN -> {
                // 起点必须在状态栏区域内
                if (event.y <= statusBarHeight) {
                    startY = event.y
                    startTime = System.currentTimeMillis()
                    monitoring = true
                }
            }
            ACTION_MOVE -> {
                if (!monitoring) return false
                val dy = startY - event.y
                val elapsed = System.currentTimeMillis() - startTime
                if (dy >= threshold && elapsed < 500) {
                    monitoring = false
                    onGestureTriggered()  // 触发面板展开
                    return true
                }
            }
            ACTION_UP, ACTION_CANCEL -> { monitoring = false }
        }
        return false
    }
}
```

### 12.3 状态栏转场模式（简化实现）

```kotlin
// 模式 3: BarTransitions 简化（alpha 模式切换 + 渐变动画）
enum class BarMode { TRANSPARENT, SEMI_TRANSPARENT, OPAQUE, LIGHTS_OUT }

class StatusBarTransitions(
    private val startSide: View,
    private val statusIcons: View,
    private val battery: View
) {
    private var currentMode = BarMode.TRANSPARENT
    private var currentAnimation: AnimatorSet? = null

    fun transitionTo(mode: BarMode, animate: Boolean = true) {
        if (mode == currentMode) return
        currentMode = mode
        applyMode(mode, animate)
    }

    private fun applyMode(mode: BarMode, animate: Boolean) {
        val iconAlpha = when (mode) {
            BarMode.LIGHTS_OUT -> 0f
            else -> 1f
        }
        val batteryAlpha = if (mode == BarMode.LIGHTS_OUT) 0.5f else iconAlpha

        currentAnimation?.cancel()
        if (animate) {
            currentAnimation = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(startSide, "alpha", iconAlpha),
                    ObjectAnimator.ofFloat(statusIcons, "alpha", iconAlpha),
                    ObjectAnimator.ofFloat(battery, "alpha", batteryAlpha)
                )
                duration = if (mode == BarMode.LIGHTS_OUT) 1500 else 250
                start()
            }
        } else {
            startSide.alpha = iconAlpha
            statusIcons.alpha = iconAlpha
            battery.alpha = batteryAlpha
        }
    }
}
```

### 12.4 展开状态管理（简化实现）

```kotlin
// 模式 4: ShadeExpansionStateManager 简化
data class ShadeExpansionState(
    val fraction: Float = 0f,   // 0=收起, 1=完全展开
    val expanded: Boolean = false,
    val tracking: Boolean = false
)

interface ShadeExpansionListener {
    fun onPanelExpansionChanged(state: ShadeExpansionState)
}

class ShadeExpansionManager {
    private val listeners = mutableListOf<ShadeExpansionListener>()
    var state = ShadeExpansionState()
        private set

    fun addListener(l: ShadeExpansionListener) { listeners.add(l) }

    fun setExpansion(fraction: Float, tracking: Boolean) {
        state = ShadeExpansionState(
            fraction = fraction,
            expanded = fraction > 0f,
            tracking = tracking
        )
        listeners.forEach { it.onPanelExpansionChanged(state) }
    }
}
```

### 12.5 挖孔屏适配模式（对齐 PhoneStatusBarView.updateCutoutLocation）

```kotlin
// 模式 5: 挖孔屏 spacer 动态宽度
fun updateCutoutSpace(displayCutout: DisplayCutout?, cutoutSpace: View) {
    if (displayCutout == null || displayCutout.isEmpty) {
        cutoutSpace.visibility = View.GONE
        return
    }
    val bounds = displayCutout.getBoundingRectTop()
    val lp = cutoutSpace.layoutParams
    lp.width = bounds.width()
    lp.height = bounds.height()
    cutoutSpace.visibility = View.VISIBLE
    cutoutSpace.layoutParams = lp
}
```

### 12.6 触摸处理优先级链（移植核心 — 对齐 TouchHandler.onInterceptTouchEvent）

```kotlin
// 模式 6: 面板触摸拦截的严格优先级（对齐 NPVC.TouchHandler）
val TOUCH_PRIORITY_CHAIN = listOf(
    { ev: MotionEvent -> qsController.disallowTouches() },             // ① QS 禁止
    { ev: MotionEvent -> initDownStates(ev); false },                  // ② 记录状态
    { ev: MotionEvent -> if (bouncerShowing) true else null },         // ③ Bouncer
    { ev: MotionEvent -> headsUpTouchHelper.onInterceptTouchEvent(ev) },// ④ HUN
    { ev: MotionEvent -> pulseExpansionHandler.onInterceptTouchEvent(ev) },// ⑤ Pulse
    { ev: MotionEvent -> qsController.onIntercept(ev) },               // ⑥ QS 拖拽
    { ev: MotionEvent -> null }                                         // ⑦ NPVC 自身
    // 注意：gesture/ 包不参与此链！
)
```

### 12.7 apply(state) 拉取式状态机（对齐 NotificationShadeWindowControllerImpl）

```kotlin
// 模式 7: 窗口属性的拉取式状态机（所有 setter 最终调用 apply()）
data class ShadeWindowState(
    var keyguardShowing: Boolean = false,
    var panelVisible: Boolean = false,
    var bouncerShowing: Boolean = false,
    var qsExpanded: Boolean = false,
    var backgroundBlurRadius: Int = 0,
    var scrimsVisible: Boolean = false,
    // ...
)

class ShadeWindowController {
    var state = ShadeWindowState()
        set(value) { field = value; apply() }  // 任何状态变化触发 apply

    private fun apply() {
        applyKeyguardFlags()        // FLAG_SECURE, FLAG_SHOW_WALLPAPER
        applyFocusableFlag()        // FLAG_NOT_FOCUSABLE / ALT_FOCUSABLE_IM
        applyVisibility()           // VISIBLE / INVISIBLE
        applyBrightness()           // 屏幕亮度
        applyHasTopUi()             // ActivityManager.setHasTopUi
        applyWindowLayoutParams()   // WindowManager.updateViewLayout
    }
}
```

### 12.8 完整初始化序列（移植对照）

```
SystemUI-Lite StatusBarManager.start() 应对齐的 AOSP 流程：

1. inflate 状态栏根视图（对齐 inflateStatusBarWindow）
2. 设置 WindowManager.LayoutParams 参数（type/flags/gravity/format）
3. WindowManager.addView(statusBarView, lp)
4. 注册 DarkIconDispatcher listener（对齐 onAttachedToWindow）
5. 设置 onTouchListener 到状态栏根视图（对齐 setOnTouchListener）
6. 注册电池/网络/时钟广播接收器
7. 初始化 BarTransitions（对齐 mStatusBarTransitions 创建）
8. 设置 AutoHide（对齐 mAutoHideController.setStatusBar）
9. 初始化挖孔屏适配（对齐 updateLayoutForCutout）
```

---

## 附录：关键类源码位置速查

| 类 | 路径 |
|---|---|
| `CentralSurfacesImpl` | `statusbar/phone/CentralSurfacesImpl.java` |
| `CentralSurfaces` | `statusbar/phone/CentralSurfaces.java` |
| `PhoneStatusBarView` | `statusbar/phone/PhoneStatusBarView.java` |
| `PhoneStatusBarViewController` | `statusbar/phone/PhoneStatusBarViewController.kt` |
| `PhoneStatusBarTransitions` | `statusbar/phone/PhoneStatusBarTransitions.java` |
| `BarTransitions` | `statusbar/phone/BarTransitions.java` |
| `NotificationPanelView` | `shade/NotificationPanelView.java` |
| `NotificationPanelViewController` | `shade/NotificationPanelViewController.java` |
| `ShadeViewController` | `shade/ShadeViewController.kt` |
| `ShadeExpansionStateManager` | `shade/ShadeExpansionStateManager.kt` |
| `ShadeController` | `shade/ShadeController.java` |
| `LockscreenShadeTransitionController` | `statusbar/LockscreenShadeTransitionController.kt` |
| `QuickSettingsController` | `shade/QuickSettingsController.java` |
| `NotificationShadeWindowControllerImpl` | `shade/NotificationShadeWindowControllerImpl.java` |
| `NotificationShadeWindowViewController` | `shade/NotificationShadeWindowViewController.java` |
| `StatusBarIconController` | `statusbar/phone/StatusBarIconController.java` |
| `NotificationIconAreaController` | `statusbar/phone/NotificationIconAreaController.java` |
| `NotificationIconContainer` | `statusbar/phone/NotificationIconContainer.java` |
| `AutoHideController` | `statusbar/phone/AutoHideController.java` |
| `AutoHideUiElement` | `statusbar/AutoHideUiElement.java` |
| `StatusBarTouchableRegionManager` | `statusbar/phone/StatusBarTouchableRegionManager.java` |
| `HeadsUpManagerPhone` | `statusbar/phone/HeadsUpManagerPhone.java` |
| `HeadsUpTouchHelper` | `statusbar/phone/HeadsUpTouchHelper.java` |
| `GenericGestureDetector` | `statusbar/gesture/GenericGestureDetector.kt` |
| `TapGestureDetector` | `statusbar/gesture/TapGestureDetector.kt` |
| `SwipeUpGestureHandler` | `statusbar/gesture/SwipeUpGestureHandler.kt` |
| `SwipeStatusBarAwayGestureHandler` | `statusbar/gesture/SwipeStatusBarAwayGestureHandler.kt` |
| `StatusBarStateControllerImpl` | `statusbar/StatusBarStateControllerImpl.java` |
| `DarkIconDispatcherImpl` | `statusbar/phone/DarkIconDispatcherImpl.java` |
| `CommandQueue` | `statusbar/CommandQueue.java` |
| `KeyguardStatusBarView` | `statusbar/phone/KeyguardStatusBarView.java` |
| `StatusBarWifiView` | `statusbar/StatusBarWifiView.java` |
| `StatusBarMobileView` | `statusbar/StatusBarMobileView.java` |
| `NotificationShelf` | `statusbar/notification/NotificationShelf.java` |
| `NotificationStackScrollLayout` | `statusbar/notification/stack/NotificationStackScrollLayout.java` |

---

*文档生成时间：2026-07-18*
*源码基地：~/android-os/SystemUI (Android U, 现代架构)*
*目标平台：SystemUI-Lite-v0.2*
