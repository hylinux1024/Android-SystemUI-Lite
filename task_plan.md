# Task Plan — 手势架构统一重构

> 目标：把「状态栏下拉抽屉」「左右侧滑返回」「底部上滑最近应用」三套手势统一到一个架构下，解决手势与业务逻辑耦合的问题。

## 设计方案概览

### 核心思路

用一个 **GesturePipeline** 组件把整条链路抽象成四个可替换的层：

```
  Touch Events
      │
      ▼
┌─────────────────────────────────────┐
│  1. GestureZone       (纯函数)       │  ← 坐标 → 区域分类 (已有 GestureZones)
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  2. GestureDetector   (状态机)       │  ← 每个 Zone 一个，跟踪 touch → progress + state
│     - EdgeGestureDetector            │
│     - BottomGestureDetector          │
│     - ShadeGestureDetector (新)      │
└──────────────┬──────────────────────┘
               │ StateFlow<GestureState>
               ▼
┌─────────────────────────────────────┐
│  3. GestureAffordance  (Compose)     │  ← 纯渲染层，读 state 画视觉反馈
│     - EdgePillAffordance             │
│     - BottomHandleAffordance         │
│     - ShadeScrimAffordance (新)      │
└──────────────┬──────────────────────┘
               │ committed / cancelled events
               ▼
┌─────────────────────────────────────┐
│  4. GestureActionSink  (业务接口)    │  ← 业务侧实现，解耦 keyevent / recents / shade
└─────────────────────────────────────┘
```

### 关键设计决策（已与用户对齐 ✅）

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 统一入口 | 每个 zone 独立 detector | **B** | 三个手势的触发区域、方向、阈值、状态机完全不同 |
| 状态暴露 | StateFlow 替代 oneway 回调 | **B** | Affordance 和 ActionSink 各自独立订阅 |
| 业务解耦 | 接口注入 (GestureActionSink) | **B** | 手势层不知道 keycode 的存在 |
| 状态栏手势 | 独立 ShadeGestureDetector | **B** | 三套手势真正统一 |
| 包结构 | 独立 gesture/ 包 | **B** | gesture 是跨 statusbar + navbar 的横切关注点 |
| 组合方式 | **GesturePipeline 作为 Koin 单例** | 用户确认 | 三个 detector 被 statusbar + navbar 两处窗口共享订阅 |
| 兼容策略 | **旧 GestureHandler 保留 1 个 phase 纯委托** | 用户确认 | 降低 Phase 2 风险，Phase 4 删除 |
| 迁移节奏 | **渐进式，每 phase 验证测试** | 用户确认 | 可单独验证、单独回滚 |

### 新包结构

```
com.android.systemui.lite.gesture/          ← 新包，所有手势基础设施
├── model/
│   ├── GestureState.kt                     ← 状态枚举 (GONE / TRACKING / COMMITTED / CANCELLED)
│   ├── GestureSession.kt                   ← 不可变会话数据 (zone, startX/Y, progress, state)
│   ├── TouchZone.kt                        ← 区域枚举 (LEFT_EDGE / RIGHT_EDGE / BOTTOM / TOP / NONE)
│   └── GestureType.kt                      ← 手势类型 (BACK / HOME / RECENTS / SHADE)
├── zone/
│   └── GestureZones.kt                     ← 纯函数分类器 (从 navigation 包搬过来)
├── detector/
│   ├── GestureDetector.kt                  ← 接口：onDown / onMove / onUp + session StateFlow
│   ├── EdgeGestureDetector.kt              ← 左右侧滑返回
│   ├── BottomGestureDetector.kt            ← 底部上滑 home/recents
│   └── ShadeGestureDetector.kt             ← 状态栏下拉 (从 StatusBar.kt 抽出)
├── affordance/
│   ├── EdgePillAffordance.kt               ← 从 GestureEdgePanel.kt 搬 + 重命名
│   ├── BottomHandleAffordance.kt           ← 从 NavigationBarView.kt 抽出
│   └── ShadeScrimAffordance.kt             ← 新：下拉时的背景遮罩
├── sink/
│   └── GestureActionSink.kt                ← 接口：onGestureCommitted(type) / onGestureCancelled(type)
└── GesturePipeline.kt                      ← 组合根：持有三个 detector + 注入 sink

com.android.systemui.lite.navigation/       ← 瘦身后只保留 navbar 窗口管理
├── NavigationBarCoreStartable.kt           ← 实现 GestureActionSink，不再直接持有 GestureHandler
└── ui/navigation/
    ├── NavigationBarView.kt                ← 三键模式 UI
    └── GestureOverlayView.kt               ← 新：把三个 strip 的 ComposeView 创建集中到一个文件

com.android.systemui.lite.ui/               ← 瘦身
├── StatusBar.kt                            ← 删除 detectDragGestures，只暴露 draggable anchor
└── NotificationShade.kt                    ← 不变
```

### 分层职责

#### Layer 1 — GestureZones (纯函数，零依赖)
- 输入：坐标 + 屏幕尺寸 + 边宽
- 输出：`TouchZone`
- 不变：边缘优先于底部、排除角判定
- 搬出 navigation 包，成为 gesture 包的公共基础

#### Layer 2 — GestureDetector 接口 + 三个实现
```kotlin
interface GestureDetector {
    val session: StateFlow<GestureSession?>
    fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int)
    fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float)
    fun onUp()
    fun cancel()
}
```
- **EdgeGestureDetector**: 水平拖拽，过阈值 → COMMITTED (BACK)；反向过起点 → CANCELLED
- **BottomGestureDetector**: 垂直上滑，onUp 按距离判定 HOME / RECENTS
- **ShadeGestureDetector**: 垂直下拉，实时 progress → 同时 emit 给 ShadeController；fling 判定开/关

每个 detector 独立状态机，互不抢占。

#### Layer 3 — GestureAffordance (Compose)
- 纯渲染：读 `session: StateFlow<GestureSession?>`，画对应的视觉反馈
- 不持有 detector 引用，不知道业务
- 三个 affordance 各自独立 composable

#### Layer 4 — GestureActionSink (业务接口)
```kotlin
interface GestureActionSink {
    fun onGestureCommitted(type: GestureType)
    fun onGestureCancelled(type: GestureType)
    fun onGestureProgress(type: GestureType, progress: Float)  // 用于 shade 跟手
}
```
- NavigationBarCoreStartable 实现它
- 内部仍调 sendKeyEvent / launchRecents，但这些细节对 gesture 包不可见

#### 组合根 — GesturePipeline
```kotlin
class GesturePipeline(
    val edge: EdgeGestureDetector,
    val bottom: BottomGestureDetector,
    val shade: ShadeGestureDetector,
    val sink: GestureActionSink,
)
```
- Koin 单例
- 三个 detector 在构造时把 `sink` 注入为 commit 回调
- 提供 `onConfigurationChanged` 统一刷新

### 迁移策略（渐进式，不破坏现有功能）

#### Phase 1: 建立骨架 + 迁移 GestureZones
- [ ] 创建 `gesture/` 包结构
- [ ] 把 `GestureZones.kt` 搬到 `gesture/zone/`，原位置保留 typealias/deprecation 转发
- [ ] 把 model 类 (GestureState, GestureSession, TouchZone, GestureType) 搬到 `gesture/model/`，原位置保留转发
- [ ] 验证现有测试全部通过

#### Phase 2: 抽取 GestureDetector 接口 + Edge/Bottom 实现
- [ ] 定义 `GestureDetector` 接口
- [ ] 把 GestureHandler 的逻辑拆为 `EdgeGestureDetector` + `BottomGestureDetector`
- [ ] 新 detector 构造时接收 `GestureActionSink` 作为 commit 回调
- [ ] 保留 GestureHandler 作为 facade 委托给两个 detector（兼容期）
- [ ] 为每个 detector 写 JVM 单元测试

#### Phase 3: 新增 ShadeGestureDetector
- [ ] 从 StatusBar.kt 抽出下拉检测逻辑
- [ ] 实现 `ShadeGestureDetector`，emit progress 给 ShadeController
- [ ] StatusBar.kt 删除 `detectDragGestures`，改为暴露 draggable modifier
- [ ] 新增 `ShadeScrimAffordance` 背景遮罩 composable

#### Phase 4: 瘦身 NavigationBarCoreStartable + 引入 GesturePipeline
- [ ] 创建 `GesturePipeline` 组合根，Koin 单例
- [ ] NavigationBarCoreStartable 实现 `GestureActionSink`
- [ ] 删除 GestureHandler 旧类（此时已无引用）
- [ ] 把三个 strip 的 ComposeView 创建集中到 `GestureOverlayView.kt`
- [ ] 删除 GestureHandlerTest 中已不适用的测试，迁移到新 detector 测试

#### Phase 5: 清理 + 文档
- [ ] 删除 navigation 包中已搬空的旧文件
- [ ] 更新 CLAUDE.md 架构图
- [ ] 全量测试通过
- [ ] 真机验证三套手势

### 风险与缓解

| 风险 | 缓解 |
|------|------|
| 状态栏下拉抽出后 tap-to-toggle 行为变化 | ShadeGestureDetector 保留 tap 判定（< slop 且 < timeout → toggle） |
| 三个 detector 同时激活时互相干扰 | 三个 detector 监听不同 Zone，物理上不会同时激活；GesturePipeline 可做互斥保护 |
| 迁移期旧 GestureHandler 和新 detector 并存导致状态不一致 | Phase 2 让 GestureHandler 纯委托，不持有独立状态 |
| 真机测试窗口类型/flags 行为变化 | 窗口创建逻辑原封不动搬，只改组合方式 |

## Progress

| Phase | Status | Notes |
|-------|--------|-------|
| 1 — 骨架 + GestureZones 迁移 | **complete** | 新包结构 + 4 个 model 类 + zone/GestureZones；旧代码零改动，编译+测试通过 |
| 2 — GestureDetector 接口 + Edge/Bottom | **complete** | EdgeGestureDetector + BottomGestureDetector + GestureActionSink + onChanged 同步机制；旧 GestureHandler 纯委托 facade；全量测试通过 |
| 3 — ShadeGestureDetector | **complete** | ShadeGestureDetector + StatusBar 抽出 + StatusBarCoreStartable 接 sink；7 JVM 测试全通过 |
| 4 — GesturePipeline + 瘦身 | pending | |
| 5 — 清理 + 文档 | pending | |
