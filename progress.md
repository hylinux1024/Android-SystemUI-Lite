# Progress — 手势架构重构

## Session 1 — 2026-07-04

### 完成
- 全量阅读现有手势相关代码（~3080 行）
  - navigation/ 包：GestureHandler (355), GestureEdgePanel (133), GestureZones (72)
  - core/ 包：NavigationBarCoreStartable (407), ShadeCoreStartable (294), RecentsCoreStartable (160), StatusBarCoreStartable (183)
  - ui/ 包：StatusBar (260), RecentsPanel (192), NavigationBarView (304)
  - model/SystemUIState.kt, di/SystemUIModule.kt
  - test：GestureHandlerTest (281), GestureZonesTest (118)
- 识别 6 个耦合问题（2 高 / 3 中-低 / 1 正向）
- 产出 findings.md（调研结论）+ task_plan.md（5 阶段迁移方案）

### 关键发现
- StatusBar.kt 自带一套完全独立的 detectDragGestures，不经过 GestureHandler —— 这是最大的架构债务
- GestureHandler 背了 5 个职责（zone 分类 + 边缘状态机 + 底部距离分类 + 长-press 守卫 + 导航模式守卫）
- NavigationBarCoreStartable 是 God Object（窗口工厂 + 回调线 + 设置观察器 + Recents 启动器 + 生命周期）
- GestureZones + GestureEdgePanel 设计良好，值得保留和复用
- 测试覆盖率高（36 个测试），迁移时必须保持

### 下一步
- 用户已确认三个决策点 → Phase 1 开始

## Session 2 — 2026-07-04 (续)

### Phase 1 完成 ✅
- 创建 `gesture/` 包结构（model / zone / detector / affordance / sink 五个子包）
- 新建 `gesture/model/` 四个文件（从 `model/SystemUIState.kt` 拆分）:
  - TouchZone.kt — 新增 TOP 枚举值（shade 专用），保留全部旧值
  - GestureType.kt — 新增 SHADE 枚举值
  - GestureState.kt — 重构为 GONE / TRACKING / COMMITTED / CANCELLED 四态（旧版 ENTRY/ACTIVE/INACTIVE 合并）
  - GestureSession.kt — 不可变 data class，progress 语义文档化
- 新建 `gesture/zone/GestureZones.kt` — 使用新 TouchZone，新增 includeTop 参数（默认 false 保持向后兼容）
- 旧代码零改动：旧 `model/SystemUIState.kt` 和 `navigation/GestureZones.kt` 保持原位不动
- 编译通过 + 既有 30 个单元测试全通过

### Phase 1 → Phase 2 衔接
- 旧 model 类仍被 navigation 包引用（GestureHandler / GestureEdgePanel / NavigationBarView 等）
- 新 model 类尚未被任何 detector 使用（Phase 2 将创建 detector 接口和实现）
- 临时包型 duplication 将在 Phase 4 删除旧类后消除

## Session 3 — 2026-07-05

### Phase 2 完成 ✅
- **新建文件**:
  - `gesture/sink/GestureActionSink.kt` — 业务解耦接口 (onCommitted / onCancelled / onProgress)
  - `gesture/detector/GestureDetector.kt` — 接口 (session / trackedType / sink / onChanged + pointer lifecycle)
  - `gesture/detector/EdgeGestureDetector.kt` — 从旧 GestureHandler 移植边缘状态机（6 态：GONE/TRACKING/ACTIVE/INACTIVE/COMMITTED/CANCELLED）
  - `gesture/detector/BottomGestureDetector.kt` — 从旧 GestureHandler 移植底部上滑分类器
- **重构 navigation/GestureHandler.kt** 为纯委托 facade：
  - 持有 `edgeDetector` + `bottomDetector`
  - onDown/onMove/onUp 转发给两个 detector
  - 通过 `onChanged` 回调保持与 detector session 同步（解决 Guard 异步修改无法通知 facade 的问题）
  - 将新 model 类型翻译回旧 model 类型输出给既有 UI
  - 旧公共 API（onAction、navigationMode、resetSession 等）保持不变
- **新特性**: 每个 detector 内置 `onChanged` 回调，长-press 守卫/commit/reset 等异步状态变化实时刷新 facade
- **测试**: 全量 `./gradlew :app:testDebugUnitTest` 30 个既有测试全通过（从 22 失败逐步修到 0）
- **编译**: `./gradlew compileDebugKotlin` 通过（只有既有 deprecation 警告）

### Phase 2 关键架构决定记录
1. 选用「onChanged 回调同步」而非「StateFlow collect」方案——后者在 kotlinx-coroutines-test 的 TestScope 下由于 dispatcher 未启动会导致 session 不同步
2. 保留 GestureState.ACTIVE 作为独立态——与旧 GestureHandler 行为对齐，且为 affordance 提供明确中间态
3. GestureDetector 接口不预设 scope/dispatcher——允许注入 TestScope 进行虚拟时间测试；生产代码默认用 Dispatchers.Main

## Session 4 — 2026-07-05

### Phase 3 完成 ✅
- **新建文件**:
  - `gesture/detector/ShadeGestureDetector.kt` — 从 StatusBar.kt 抽出下拉检测逻辑
    - 构造参数: scope, shadeRangePx, topBandPx (60f), slopPx (8f), flingVelocityPxMs (800f), flingDistancePx (40f), openThresholdFraction (1/3f), sink, onChanged
    - onDown: 仅承认 y ≤ topBandPx，消除与边缘/底部 detector 的 zone 冲突
    - onMove: 累加 totalDragY，负向归零，正向 fraction = dragY/shadeRangePx，emit onProgress 给 ShadeController
    - onUp 解码 3 路:
      - tap (|dragY|<slop && |dragX|<slop) → onCommitted(SHADE) 给 host toggle 开关
      - fling (velocity>800 && dragY>40) → dragY>120 → COMMIT
      - 非 fling 子阈值 (dragY < shadeRangePx/3 && progress≤0.5) → CANCEL
    - cancel(): 重置状态机为 CANCELLED
  - `gesture/ShadeGestureDetectorTest.kt` — 7 个 JVM 测试覆盖 6 个状态转换场景 + long-press 守卫
- **修改文件**:
  - `ui/StatusBar.kt` — 删除 inline `detectDragGestures`，删除 `totalDragY/totalDragX/dragStartMs` 局部状态，新增 `shadeDragModifier: Modifier` 参数，modifier 链最后 `.then(shadeDragModifier)`
  - `core/StatusBarCoreStartable.kt` — 新增 `scope` (CoroutineScope Main) + 懒初始化 `shadeDetector`（完整 GestureActionSink 实现：toggleShade / dragShade），构建 `shadeDragModifier`（`pointerInput` + `detectDragGestures` 显式 lambda 类型标注），传给 StatusBar
- **测试**: `./gradlew :app:testDebugUnitTest` — 全量通过（含新增 7 个测试）

### Phase 3 验证细节
- 第一次运行 6/7 通过，唯一失败: `onUp with sub-threshold non-fling drag cancels` 用了 200px drag，但 virtual-time 下 elapsed ms ≈ 0 导致 velocity 爆炸，满足 Fling 分支反而 COMMIT。修正为 20px (< flingDistancePx 40) 后 7/7 全通过
- 该失败属于测试本身设计错误，不是 detector 逻辑错误——detector 行为与 AOSP fling 判定一致

### 下一步
- Phase 4: 创建 GesturePipeline Koin 单例，瘦 NavigationBarCoreStartable，删除旧 GestureHandler facade，集中 strip ComposeView 到 GestureOverlayView
- Phase 5: 删除 navigation 包旧文件，更新 CLAUDE.md，真机验证
