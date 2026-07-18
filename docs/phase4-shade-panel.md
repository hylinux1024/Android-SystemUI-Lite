# Phase 4 — 状态栏手势、动画与通知面板（复刻方案）

> 在 Phase 3（状态栏窗口 + 图标 + 挖孔适配 + 暗色 tint）基础上，复刻 AOSP SystemUI 现代架构的**四大核心能力**：
> 1. **布局结构层级** — 通知面板窗口 + 可展开容器 + 触摸拦截层
> 2. **手势（展开与收起）** — `TouchHandler` 优先级链 + 拖拽展开 + fling
> 3. **动画** — 物理展开/收起动画 + 状态栏转场（Lights-out）+ 拖拽实时插值
> 4. **事件** — 展开状态分发 + 背压返回 + 系统栏属性 + 动画结束回调

---

## 目录

- [1. 与 AOSP 的对照与范围](#1-与-aosp-的对照与范围)
- [2. 总体架构](#2-总体架构)
- [3. 模块一：布局结构层级](#3-模块一布局结构层级)
- [4. 模块二：手势（展开与收起）](#4-模块二手势展开与收起)
- [5. 模块三：动画](#5-模块三动画)
- [6. 模块四：事件](#6-模块四事件)
- [7. DI 与生命周期集成](#7-di-与生命周期集成)
- [8. 资源文件清单](#8-资源文件清单)
- [9. 实现步骤与验证判据](#9-实现步骤与验证判据)

---

## 1. 与 AOSP 的对照与范围

| AOSP 现代实现 | 本方案（Phase 4） |
|---|---|
| `NotificationShadeWindowControllerImpl` | `NotificationShadeWindowController`（精简：窗口创建 + `apply(state)` 状态机） |
| `NotificationShadeWindowView` (FrameLayout) | `NotificationShadeWindowView` (自定义 FrameLayout) |
| `NotificationPanelViewController` (5098行) | `NotificationPanelViewController`（精简核心：TouchHandler + 展开/收起 + 动画驱动） |
| `NotificationPanelView` (ConstraintLayout) | `notification_panel.xml` 根视图 |
| `ShadeExpansionStateManager` | `ShadeExpansionStateManager`（展开状态单一数据源） |
| `ShadeController` | `ShadeController`（展开/收起命令入口） |
| `BarTransitions` + `PhoneStatusBarTransitions` | `StatusBarTransitions`（状态栏图标 alpha 模式转场） |

**明确不纳入 Phase 4 的范围**（避免过度设计）：

| 排除项 | 原因 |
|---|---|
| QuickSettings (QS) 面板及其动画 | Phase 5；Phase 4 仅占位 `qs_frame` |
| ScrimController / LightRevealScrim | 涉及锁屏 + 解锁动画，独立阶段 |
| Keyguard / Bouncer | 锁屏逻辑另行通知面板 |
| HeadsUp 通知弹出 | 依赖通知管线 |
| 通知列表 (NotificationStackScrollLayout) | Phase 4 面板内占位，无真实通知 |
| `gesture/` 包全局手势检测 | AOSP 中它**不**用于面板展开，仅服务 OngoingCall/Chipbar |
| CommandQueue / `IStatusBar` 注册 | `StatusBarServiceBridge` 已预留，但 Phase 3/4 保持"本地 UI 模式" |

---

## 2. 总体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                    TYPE_NOTIFICATION_SHADE 窗口                      │
│  NotificationShadeWindowView (FrameLayout, match_parent)             │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  NotificationPanelView (notification_panel.xml 根)             │  │
│  │  ┌──────────────────────────────────────────────────────────┐  │  │
│  │  │  NotificationContainerParent (纵向容器, clipChildren=F)  │  │  │
│  │  │  ┌────────────────────────────────────────────────────┐  │  │  │
│  │  │  │  qs_frame (FrameLayout, ViewStub 占位)             │  │  │  │
│  │  │  ├────────────────────────────────────────────────────┤  │  │  │
│  │  │  │  notification_stack_scroll (通知列表占位, 空)       │  │  │  │
│  │  │  └────────────────────────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘

事件流:
StatusBarManager.handleStatusBarTouch()
  → NotificationPanelViewController.TouchHandler.onTouchEvent()
      → 实时计算 expandedHeight
      → ShadeExpansionStateManager 分发 fraction
          ├── StatusBarTransitions.applyMode()  ← 状态栏图标 alpha
          ├── NotificationPanelView 实时更新
          └── (未来) Scrim / QS 联动
```

**双窗口分离**（对齐 AOSP）：
- `TYPE_STATUS_BAR` 窗口（已有 Phase 3）：`status_bar.xml`，吸顶，高度 `status_bar_height`
- `TYPE_NOTIFICATION_SHADE` 窗口（Phase 4 新增）：`notification_panel.xml`，全屏，默认不可见，展开时可见

---

## 3. 模块一：布局结构层级

### 3.1 新增类总览

| 类 | 包 | 职责 |
|---|---|---|
| `NotificationShadeWindowController` | `statusbar.shade` | 创建/管理 `TYPE_NOTIFICATION_SHADE` 窗口 + `apply(state)` 拉取式状态机 |
| `NotificationShadeWindowView` | `statusbar.shade` | 自定义 FrameLayout，面板窗口根视图 |
| `NotificationPanelViewController` | `statusbar.shade` | **核心**：TouchHandler + 展开/收起 + 动画驱动 + 状态分发 |
| `ShadeExpansionStateManager` | `statusbar.shade` | 展开状态单一数据源（`fraction` / `expanded` / `tracking`） |
| `ShadeController` | `statusbar.shade` | 展开/收起命令入口 |
| `NotificationContainerParent` | `statusbar.shade` | 纵向容器（对齐 `NotificationsQuickSettingsContainer`） |
| `StatusBarTransitions` | `statusbar` | 状态栏图标 alpha 模式转场（对齐 `PhoneStatusBarTransitions`） |

### 3.2 `notification_panel.xml` — 面板根布局

对齐 AOSP `status_bar_expanded.xml` 的精简版：

```xml
<!-- res/layout/notification_panel.xml -->
<com.android.systemui.statusbar.shade.NotificationPanelView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/notification_panel"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/transparent"
    android:visibility="gone">

    <!-- 纵向容器：承载 QS 占位 + 通知列表占位 -->
    <com.android.systemui.statusbar.shade.NotificationContainerParent
        android:id="@+id/notification_container_parent"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipChildren="false"
        android:clipToPadding="false">

        <!-- QS 占位：Phase 5 替换为真实 QS 面板 -->
        <FrameLayout
            android:id="@+id/qs_frame"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="@dimen/notification_panel_margin_horizontal" />

        <!-- 通知列表占位：Phase 5 替换为 NotificationStackScrollLayout -->
        <FrameLayout
            android:id="@+id/notification_stack_scroll"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />

    </com.android.systemui.statusbar.shade.NotificationContainerParent>

</com.android.systemui.statusbar.shade.NotificationPanelView>
```

### 3.3 `NotificationPanelView` — 面板根视图

```kotlin
// statusbar/shade/NotificationPanelView.kt
class NotificationPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 面板整体 alpha，对齐 AOSP 的 Paint alpha 机制 */
    private val paint = Paint()
    var panelAlpha: Float = 1f
        set(value) { field = value; invalidate() }

    override fun draw(canvas: Canvas) {
        paint.alpha = (panelAlpha * 255).toInt().coerceIn(0, 255)
        // 使用 layer 实现整体 alpha
        canvas.saveLayer(null, paint)
        super.draw(canvas)
        canvas.restore()
    }
}
```

### 3.4 `NotificationShadeWindowView` — 窗口根视图

```kotlin
// statusbar/shade/NotificationShadeWindowView.kt
class NotificationShadeWindowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 触摸处理器（由 NotificationPanelViewController 设置） */
    var touchHandler: ((MotionEvent) -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler?.invoke(event) ?: false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // 仅在面板展开时拦截，收起时不拦截（让事件穿透到桌面）
        return touchHandler?.invoke(event) ?: false
    }
}
```

### 3.5 `NotificationContainerParent` — 纵向容器

```kotlin
// statusbar/shade/NotificationContainerParent.kt
class NotificationContainerParent @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    init {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
}
```

### 3.6 视图层级全景图

```
super_notification_shade (新增布局, 可选)
└── NotificationShadeWindowView (id=notification_shade_window, match_parent)
    └── NotificationPanelView (id=notification_panel, match_parent, gone)
        └── NotificationContainerParent (id=notification_container_parent)
            ├── qs_frame (FrameLayout, wrap_content)
            └── notification_stack_scroll (FrameLayout, weight=1)
```

---

## 4. 模块二：手势（展开与收起）

这是 Phase 4 的核心。手写实现 `TouchHandler` 优先级链，对齐 AOSP `NPVC.TouchHandler` 的精简版。

### 4.1 `TouchHandler` — 触摸处理核心

> 移植要点：AOSP 面板展开走 `NPVC.TouchHandler.onInterceptTouchEvent` 的**严格优先级链**，**不走** `gesture/` 包。

```kotlin
// statusbar/shade/NotificationPanelViewController.TouchHandler (内部类)
internal inner class TouchHandler : OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var startX = 0f
    private var tracking = false           // 是否正在拖拽
    private var collapsedOnDown = true     // 按下时是否处于收起态
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null

    // ---- 常量 ----
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                collapsedOnDown = isFullyCollapsed()
                initVelocityTracker()
                velocityTracker?.addMovement(ev)
            }
            ACTION_MOVE -> {
                val h = ev.y - downY
                val w = ev.x - downX
                // 垂直拖动超过 touchSlop 且垂直为主 → 拦截
                if (h > touchSlop && h > abs(w) && !isFullyExpanded()) {
                    return true   // 拦截：开始拖拽
                }
            }
        }
        return false   // 不拦截，让子 view 处理
    }

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            ACTION_DOWN -> {
                startX = ev.x; startY = ev.y
                return true   // 消费 DOWN 以接收后续事件
            }
            ACTION_MOVE -> {
                val dy = startY - ev.y   // 上滑距离（正=上滑）
                if (abs(dy) > touchSlop && !tracking) {
                    tracking = true
                    onTrackingStarted()
                }
                if (tracking) {
                    // 实时设置展开高度
                    setExpandedHeightInternal(currentExpandedHeight + dy.coerceAtLeast(0f))
                    startY = ev.y
                }
                return true
            }
            ACTION_UP, ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocity = velocityTracker?.yVelocity ?: 0f
                    endMotionEvent(velocity)
                }
                recycleVelocityTracker()
                return true
            }
        }
        return false
    }

    private fun endMotionEvent(velocity: Float) {
        val fraction = expansionFraction
        when {
            // Fling 速度足够 → 根据方向展开/收起
            abs(velocity) > minFlingVelocity -> {
                if (velocity < 0) fling(expand = true)   // 上滑 fling → 展开
                else fling(expand = false)                // 下滑 fling → 收起
            }
            // 过半展开
            fraction > 0.5f -> fling(expand = true)
            else -> collapse(animate = true)
        }
    }

    private fun initVelocityTracker() {
        velocityTracker = velocityTracker ?: VelocityTracker.obtain()
    }
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle(); velocityTracker = null
    }
}
```

### 4.2 拦截优先级链（对齐 AOSP 严格顺序）

AOSP 的完整链：`bouncer → HUN → Pulse → QS → NPVC 拖拽`。Phase 4 精简为：

```
TouchHandler.onInterceptTouchEvent 优先级链:
① 若面板已完全展开且触摸在面板区域内 → 消费（面板内滚动）
② 垂直拖拽 > touchSlop 且垂直为主 → 拦截（拖拽展开）
③ 否则 → 不拦截，事件穿透到子 view 或桌面
```

### 4.3 手势判定三要素

对齐 AOSP `SwipeUpGestureHandler` 的判定逻辑（仅用于 fling 速度判断，不用于拦截）：

| 条件 | 判定 |
|---|---|
| 垂直位移 | `abs(dy) > touchSlop` |
| 垂直为主 | `abs(dy) > abs(dx)` |
| 展开方向 | `dy > 0` = 上滑展开 |

### 4.4 与现有 `StatusBarManager` 的集成

改造 `StatusBarManager.handleStatusBarTouch()`（当前 only feed autohide）：

```kotlin
// StatusBarManager.kt 改造后
private fun handleStatusBarTouch(event: MotionEvent): Boolean {
    autoHideController.checkUserAutoHide(event)
    return when (event.actionMasked) {
        ACTION_DOWN -> {
            autoHideController.touchAutoHide()
            // 转发到面板 TouchHandler 的拦截判断
            val intercept = notificationPanelViewController?.onInterceptTouch(event) ?: false
            if (intercept) {
                // 面板开始接管 → 记录触摸起始位置
                true
            } else {
                // 仅在状态栏区域内消费（防止穿透到桌面）
                event.y <= getStatusBarHeight()
            }
        }
        ACTION_MOVE, ACTION_UP -> {
            // 转发到面板 TouchHandler
            notificationPanelViewController?.handleTouch(event) ?: true
        }
        else -> true
    }
}
```

---

## 5. 模块三：动画

### 5.1 展开/收起动画 — `fling()` + `collapse()` + `expand()`

对齐 AOSP `NPVC.fling(vel, expand)`：

```kotlin
// statusbar/shade/NotificationPanelViewController.kt
class NotificationPanelViewController(...) {

    private var heightAnimator: ValueAnimator? = null
    private var expandedHeight = 0f        // 当前展开高度（px）
    private var maxExpandedHeight = 0f      // 最大展开高度（px）
    val expansionFraction: Float
        get() = if (maxExpandedHeight > 0) expandedHeight / maxExpandedHeight else 0f

    // ---- 动画器工厂 ----
    private val expandInterpolator = PathInterpolator(0.33f, 0f, 0f, 1f)   // 展开加速
    private val collapseInterpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f) // 收起减速
    private val expandDuration = 350L
    private val collapseDuration = 300L

    /** 物理展开动画（对齐 AOSP fling） */
    fun fling(expand: Boolean, velocity: Float = 0f) {
        val target = if (expand) maxExpandedHeight else 0f
        val anim = createHeightAnimator(target)
        if (expand) {
            // 展开时：加入轻微 overshoot（对齐 AOSP 弹性）
            anim.interpolator = expandInterpolator
            anim.duration = expandDuration
        } else {
            anim.interpolator = collapseInterpolator
            anim.duration = collapseDuration
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!expand) onPanelCollapsed() else onPanelFullyExpanded()
            }
        })
        heightAnimator?.cancel()
        heightAnimator = anim
        anim.start()
    }

    fun collapse(animate: Boolean = true) {
        if (!animate) {
            setExpandedHeightInternal(0f)
            onPanelCollapsed()
            return
        }
        fling(expand = false)
    }

    fun expand(animate: Boolean = true) {
        if (!animate) {
            setExpandedHeightInternal(maxExpandedHeight)
            onPanelFullyExpanded()
            return
        }
        fling(expand = true)
    }

    fun setExpandedHeightInternal(height: Float) {
        expandedHeight = height.coerceIn(0f, maxExpandedHeight)
        updateExpandedHeight()
    }

    private fun createHeightAnimator(target: Float): ValueAnimator {
        return ValueAnimator.ofFloat(expandedHeight, target).apply {
            addUpdateListener {
                setExpandedHeightInternal(it.animatedValue as Float)
            }
        }
    }

    /** 实时更新面板高度 + 分发展开状态 */
    private fun updateExpandedHeight() {
        // ① 更新面板容器布局参数
        (panelView as? ViewGroup)?.let { view ->
            // 这里使用 translationY 偏移实现"从顶部滑出"
            view.translationY = -(maxExpandedHeight - expandedHeight)
        }
        // ② 分发 fraction
        expansionStateManager.setExpansion(expansionFraction, tracking = false)
    }
}
```

### 5.2 `ShadeExpansionStateManager` — 展开状态单一数据源

对齐 AOSP：

```kotlin
// statusbar/shade/ShadeExpansionStateManager.kt

data class ShadeExpansionState(
    val fraction: Float = 0f,       // 0.0 = 收起, 1.0 = 完全展开
    val expanded: Boolean = false,  // 是否展开
    val tracking: Boolean = false   // 用户手指是否正在拖动
)

fun interface ShadeExpansionListener {
    fun onPanelExpansionChanged(state: ShadeExpansionState)
}

@Singleton
class ShadeExpansionStateManager @Inject constructor() {
    private val listeners = CopyOnWriteArrayList<ShadeExpansionListener>()
    var state = ShadeExpansionState()
        private set

    fun addListener(l: ShadeExpansionListener) { listeners.add(l) }
    fun removeListener(l: ShadeExpansionListener) { listeners.remove(l) }

    fun setExpansion(fraction: Float, tracking: Boolean) {
        state = ShadeExpansionState(
            fraction = fraction.coerceIn(0f, 1f),
            expanded = fraction > 0f,
            tracking = tracking
        )
        listeners.forEach { it.onPanelExpansionChanged(state) }
    }
}
```

### 5.3 `StatusBarTransitions` — 状态栏图标 alpha 转场

对齐 AOSP `PhoneStatusBarTransitions` 的 7 种模式 + alpha 渐变：

```kotlin
// statusbar/StatusBarTransitions.kt

enum class BarMode(val alpha: Float) {
    TRANSPARENT(1f),
    SEMI_TRANSPARENT(1f),
    TRANSLUCENT(0.8f),
    LIGHTS_OUT(0f),          // 非电池/时钟全隐
    OPAQUE(0.94f),
    WARNING(1f),
    LIGHTS_OUT_TRANSPARENT(0f);

    val batteryClockAlpha: Float
        get() = if (this == LIGHTS_OUT) 0.5f else alpha   // 电池/时钟 lights-out 时 0.5f
}

class StatusBarTransitions(
    private val startSide: View,
    private val statusIcons: View,
    private val battery: View
) {
    private var currentMode = BarMode.TRANSPARENT
    private var currentAnimation: AnimatorSet? = null

    fun transitionTo(mode: BarMode, animate: Boolean = true) {
        if (mode == currentMode) return
        val oldMode = currentMode
        currentMode = mode
        applyMode(mode, animate, oldMode)
    }

    private fun applyMode(mode: BarMode, animate: Boolean, oldMode: BarMode) {
        val iconAlpha = mode.alpha
        val batteryAlpha = mode.batteryClockAlpha
        currentAnimation?.cancel()
        if (animate) {
            currentAnimation = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(startSide, View.ALPHA, startSide.alpha, iconAlpha),
                    ObjectAnimator.ofFloat(statusIcons, View.ALPHA, statusIcons.alpha, iconAlpha),
                    ObjectAnimator.ofFloat(battery, View.ALPHA, battery.alpha, batteryAlpha)
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

### 5.4 状态栏与面板联动的订阅

```kotlin
// StatusBarTransitions 订阅 ShadeExpansionStateManager
class StatusBarTransitions(...) : ShadeExpansionListener {

    override fun onPanelExpansionChanged(state: ShadeExpansionState) {
        // 面板展开 → 状态栏变透明（让壁纸透出）
        // 面板收起 → 状态栏恢复
        when {
            state.fraction >= 0.9f -> transitionTo(BarMode.TRANSPARENT)
            state.fraction <= 0.05f -> transitionTo(BarMode.SEMI_TRANSPARENT)
            else -> {
                // interpolate alpha between 1.0 → 0.0
                val alpha = 1f - state.fraction
                startSide.alpha = alpha
                statusIcons.alpha = alpha
            }
        }
    }
}
```

---

## 6. 模块四：事件

### 6.1 `ShadeController` — 展开/收起命令入口

对齐 AOSP：

```kotlin
// statusbar/shade/ShadeController.kt

interface ShadeController {
    fun collapseShade(animate: Boolean = true)
    fun expandNotificationsPanel(animate: Boolean = true)
    fun togglePanel()
    fun addPostCollapseAction(action: Runnable)
}

@Singleton
class ShadeControllerImpl @Inject constructor(
    private val panelController: NotificationPanelViewController
) : ShadeController {

    private val postCollapseActions = mutableListOf<Runnable>()

    override fun collapseShade(animate: Boolean) {
        panelController.collapse(animate)
    }

    override fun expandNotificationsPanel(animate: Boolean) {
        panelController.expand(animate)
    }

    override fun togglePanel() {
        if (panelController.isFullyCollapsed()) {
            expandNotificationsPanel()
        } else {
            collapseShade()
        }
    }

    override fun addPostCollapseAction(action: Runnable) {
        postCollapseActions.add(action)
        panelController.addOnCollapsedCallback { action.run() }
    }
}
```

### 6.2 `NotificationShadeWindowController` — 窗口 + 拉取式状态机

对齐 AOSP `NotificationShadeWindowControllerImpl.apply(state)`：

```kotlin
// statusbar/shade/NotificationShadeWindowController.kt

data class ShadeWindowState(
    var panelVisible: Boolean = false,
    var backgroundBlurRadius: Int = 0,
    var scrimsVisible: Boolean = false
)

@Singleton
class NotificationShadeWindowController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager
) : CoreStartable {

    private var shadeView: NotificationShadeWindowView? = null
    private var state = ShadeWindowState()
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun start() {
        createShadeWindow()
    }

    override fun stop() {
        shadeView?.let { windowManager.removeView(it) }
        shadeView = null
    }

    private fun createShadeWindow() {
        if (shadeView != null) return
        val view = NotificationShadeWindowView(context)
        shadeView = view
        layoutParams = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            title = "NotificationShade"
            layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            Log.e("ShadeWindowCtrl", "Failed to add shade window", e)
        }
    }

    // ---- 拉取式状态机（对齐 apply(state)） ----
    fun setPanelVisible(visible: Boolean) {
        state = state.copy(panelVisible = visible)
        apply()
    }

    private fun apply() {
        // 可见性
        shadeView?.visibility = if (isExpanded()) View.VISIBLE else View.INVISIBLE
        // 动态更新 LayoutParams
        layoutParams?.let { lp ->
            windowManager.updateViewLayout(shadeView, lp)
        }
    }

    private fun isExpanded(): Boolean {
        val s = state
        return !s.scrimsVisible && (s.panelVisible || s.backgroundBlurRadius > 0)
    }

    fun getView(): NotificationShadeWindowView? = shadeView
}
```

### 6.3 背压返回（Back Pressed）处理

对齐 AOSP `CentralSurfacesImpl.onBackPressed()`：

```kotlin
// statusbar/shade/NotificationPanelViewController.kt
fun onBackPressed(): Boolean {
    return when {
        !isFullyCollapsed() -> {
            collapse(animate = true)
            true   // 消费 back
        }
        else -> false  // 不消费 → 系统处理（回到桌面/锁屏）
    }
}
```

### 6.4 展开状态事件的完整生命周期

```
用户下滑状态栏
  ↓
StatusBarManager.handleStatusBarTouch(ACTION_DOWN)
  → NotificationPanelViewController.onInterceptTouch(event)
  ↓
TouchHandler.onInterceptTouchEvent → ACTION_MOVE 拖拽超过 slop → return true
  ↓
TouchHandler.onTouch(ACTION_MOVE) → tracking=true → onTrackingStarted()
  → setExpandedHeightInternal(newHeight) → updateExpandedHeight()
      → ShadeExpansionStateManager.setExpansion(fraction, tracking=true)
          ├── StatusBarTransitions.onPanelExpansionChanged() → 图标 alpha 渐变
          ├── NotificationPanelView.translationY 实时更新
          └── (未来) Scrim / QS 联动
  ↓
TouchHandler.onTouch(ACTION_UP) → velocityTracker → endMotionEvent(velocity)
  → fling(expand=true/false) → ValueAnimator 动画到目标高度
      → onAnimationEnd → setExpandedHeight(0/max)
          → ShadeExpansionStateManager.setExpansion(fraction, tracking=false)
              ├── StatusBarTransitions transitionTo()
              └── applyPanelExpandedState()
```

---

## 7. DI 与生命周期集成

### 7.1 `ApplicationModule` 扩展

```kotlin
// di/ApplicationModule.kt — 新增提供

@Provides
@Singleton
fun provideWallpaperManager(@ApplicationContext context: Context): WallpaperManager? {
    return try { WallpaperManager.getInstance(context) } catch (e: Exception) { null }
}
```

### 7.2 `NotificationPanelViewController` 构造

```kotlin
// statusbar/shade/NotificationPanelViewController.kt
@Singleton
class NotificationPanelViewController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shadeWindowController: NotificationShadeWindowController,
    private val expansionStateManager: ShadeExpansionStateManager,
    private val windowManager: WindowManager
) : CoreStartable {

    private var panelView: NotificationPanelView? = null
    private val touchHandler = TouchHandler()

    override fun start() {
        panelView = shadeWindowController.getView()
            ?.findViewById(R.id.notification_panel)
        // 设置 TouchHandler 到窗口根视图
        shadeWindowController.getView()?.touchHandler = touchHandler::onTouch
        // 注册展开状态监听（用于联动动画）
        expansionStateManager.addListener(statusBarTransitions)
    }

    override fun stop() { /* 清理 */ }

    fun addOnCollapsedCallback(cb: () -> Unit) { ... }
}
```

### 7.3 `CoreStartableComponent` 注册顺序

```kotlin
// 在 SystemUIApplication 或 CoreStartableComponent 中:
// 1. StatusBarManager.start()    ← Phase 3 已有
// 2. NotificationShadeWindowController.start()  ← Phase 4 新增：创建 shade 窗口
// 3. NotificationPanelViewController.start()     ← Phase 4 新增：绑定 TouchHandler
```

### 7.4 依赖关系图

```
ApplicationContext ──→ WindowManager ──→ NotificationShadeWindowController
                                           │
                                           ↓
WallpaperProvider ──→ StatusBarTransitions ──→ StatusBarManager (已有)
                        ↑
                        │
ShadeExpansionStateManager ──→ NotificationPanelViewController
        ↑                           │
        │                           │
        └───────────────────────────┘
                        │
                        ↓
                ShadeController (命令入口)
```

---

## 8. 资源文件清单

### 8.1 新增布局

| 文件 | 作用 |
|---|---|
| `res/layout/notification_panel.xml` | 通知面板根布局 |
| `res/layout/super_notification_shade.xml` | 面板窗口根（可选，若直接代码创建则可省略） |

### 8.2 新增/扩展 dimens

```xml
<!-- 追加到 res/values/dimens.xml -->
<dimen name="notification_panel_margin_horizontal">16dp</dimen>
<dimen name="status_bar_expand_threshold">100dp</dimen>     <!-- 过半展开的位移阈值 -->
<dimen name="shade_open_spring_out_duration">350</dimen>    <!-- ms -->
<dimen name="shade_close_duration">300</dimen>              <!-- ms -->
<dimen name="lights_in_duration">250</dimen>
<dimen name="lights_out_duration">1500</dimen>
<dimen name="background_duration">200</dimen>
```

### 8.3 新增 values

```xml
<!-- res/values/integers.xml -->
<integer name="notification_panel_layout_gravity">0x30</integer>  <!-- TOP -->
```

### 8.4 不需要新增权限

现有 manifest 已包含 `STATUS_BAR / EXPAND_STATUS_BAR / INTERNAL_SYSTEM_WINDOW / SYSTEM_ALERT_WINDOW`，满足 `TYPE_NOTIFICATION_SHADE` 窗口添加要求。

---

## 9. 实现步骤与验证判据

### 步骤 1：窗口骨架

- [ ] 创建 `NotificationShadeWindowView` + `NotificationShadeWindowController`
- [ ] `TYPE_NOTIFICATION_SHADE` 窗口 WindowManager addView
- [ ] `notification_panel.xml` inflate + 绑定 TouchHandler
- [ ] **判据**：`adb shell dumpsys window windows | grep NotificationShade` 可见

### 步骤 2：TouchHandler 拦截链

- [ ] `TouchHandler.onInterceptTouchEvent` 垂直拖拽判定
- [ ] `TouchHandler.onTouch` 实时 `setExpandedHeightInternal`
- [ ] 集成到 `StatusBarManager.handleStatusBarTouch` 转发
- [ ] **判据**：手指在状态栏下滑 → 面板跟随手指展开

### 步骤 3：展开/收起动画

- [ ] `fling(expand)` + `collapse(animate)` + `expand(animate)` ValueAnimator
- [ ] `velocityTracker` 计算 fling 速度
- [ ] `endMotionEvent` 过半展开/收起判定（fraction > 0.5f）
- [ ] **判据**：快速上滑 → 面板自动展开到顶；快速下滑 → 自动收起

### 步骤 4：状态分发

- [ ] `ShadeExpansionStateManager` 单一数据源
- [ ] `StatusBarTransitions` 订阅展开状态 → alpha 联动
- [ ] `ShadeController` 命令入口（togglePanel / collapseShade）
- [ ] **判据**：面板展开时状态栏变透明；收起时恢复

### 步骤 5：背压返回 + 自动隐藏

- [ ] `onBackPressed()` 消费 → 收起面板
- [ ] `AutoHideController` 与面板可见状态联动（面板可见时不自动隐藏状态栏）
- [ ] **判据**：面板展开时按 Back → 面板收起不回到桌面

### 步骤 6：边界与异常处理

- [ ] 面板收起时事件穿透到桌面（不拦截）
- [ ] 窗口添加失败 try/catch + 日志
- [ ] `VelocityTracker` 正确 obtain/recycle
- [ ] 动画进行中打断（cancel + 重新 fling）
- [ ] **判据**：反复快速上下滑不 crash、不粘连

---

## 附录 A：调试与诊断

```bash
# 1. 确认两个窗口都存在
adb shell dumpsys window windows | grep -E "StatusBar|NotificationShade"

# 2. 确认触摸事件流（开启输入调试）
adb shell dumpsys input | grep -A 5 "NotificationShade"

# 3. 确认 SystemUI 进程存活
adb shell ps -A | grep systemui

# 4. 日志过滤
adb logcat -s StatusBarManager:* ShadeWindowCtrl:* NotificationPanelVC:*
```

## 附录 B：已知陷阱（来自 AOSP 调研）

| 陷阱 | 原因 | 规避 |
|---|---|---|
| `gesture/` 包误用 | AOSP 中它不用于面板展开，仅服务 OngoingCall/Chipbar | 面板展开必须走 `TouchHandler` |
| 双窗口触摸路由 | 状态栏和面板分属不同窗口 | `StatusBarManager` 必须转发 MotionEvent 到 `TouchHandler` |
| `VelocityTracker` 泄漏 | 每次 DOWN → obtain，UP 必须 recycle | 用 `try/finally` 或 `recycleVelocityTracker()` |
| 面板收起仍拦截事件 | 收起时 `onInterceptTouchEvent` 应返回 false | 始终根据 `isFullyCollapsed()` 判断 |
| 窗口添加异常 | 权限不足或重复 addView | try/catch + null guard |

---

*文档生成时间：2026-07-18*
*前置依赖：Phase 3 状态栏（docs/phase3-status-bar.md）*
*研究依据：docs/aosp-statusbar-research.md*
