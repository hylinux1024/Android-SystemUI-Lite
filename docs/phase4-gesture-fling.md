# Phase 4 — 面板手势展开/收起 + 物理 fling 动画（实现记录）

> 本章记录 `NotificationPanelViewController` + `FlingAnimations` 的**实际实现**——状态栏手势驱动面板展开/收起，并对齐 AOSP `NPVC.flingToHeight` / `FlingAnimationUtils` / `springBack` 的核心物理手感：速度驱动时长、过冲回弹、慢拖位置判定。
>
> 设计阶段文档见 [fling-fix-plan.md](./fling-fix-plan.md)，本章以代码实际落地为准。

---

## 目录

- [1. 实现范围与文件清单](#1-实现范围与文件清单)
- [2. 总体流程](#2-总体流程)
- [3. 释放判定：展开还是收起](#3-释放判定展开还是收起)
- [4. `flingToHeight` 动画分支全表](#4-flingtoheight-动画分支全表)
- [5. `FlingAnimations` 纯函数 API](#5-flinganimations-纯函数-api)
- [6. 关键常量与时长边界](#6-关键常量与时长边界)
- [7. `< 0.3` 收起的 snap-back 路径](#7--03-收起的-snap-back-路径)
- [8. 收起动画修复（collapse too long）](#8-收起动画修复collapse-too-long)
- [9. 验证判据](#9-验证判据)

---

## 1. 实现范围与文件清单

| 文件 | 状态 | 职责 |
|---|---|---|
| `statusbar/fling/FlingAnimations.kt` | 新增 | 纯函数：速度→时长、速度→过冲量、fling/drag 判定、展开/收起判定 |
| `statusbar/shade/NotificationPanelViewController.kt` | 修改 | `TouchHandler.endMotionEvent` + `flingToHeight` + `springBack` + forced snap-back |

**明确不在本次实现内**（与 phase4-shade-panel.md 一致的范围边界）：QuickSettings 面板、Scrim、锁屏/Bouncer、HeadsUp、通知列表滚动。

---

## 2. 总体流程

```
用户手指在状态栏下滑 (ACTION_DOWN)
  ↓
TouchHandler.onTouch
  → ACTION_DOWN:  initVelocityTracker(), 记录 startY
  → ACTION_MOVE:  velocityTracker.addMovement(ev)   // 每帧喂给追踪器
                  dy = ev.y - startY
                  setExpandedHeightInternal(startExpandedHeight + dy)
  → ACTION_UP:    velocityTracker.computeCurrentVelocity(1000)  // px/s
                  endMotionEvent(vy, vx, ev.x, ev.y)
                      ├─ vectorVel = hypot(vx, vy)
                      ├─ expand = shouldExpandOnRelease(vy, vectorVel, fraction)
                      ├─ !expand && vy==0 → forced short snap-back (80~180ms)
                      └─ else → flingToHeight(vy, expand, target)
                                  ├─ forcedDuration 分支
                                  ├─ expand 分支（含 overshoot + springBack）
                                  └─ collapse 分支（含速度驱动 / 无速度 snap）
```

---

## 3. 释放判定：展开还是收起

`FlingAnimations.shouldExpandOnRelease(velocityY, vectorVelocity, fraction)`：

```kotlin
return if (isFling(vectorVelocity)) {
    // 真正的 fling（|v| >= 200 px/s）：方向决定
    velocityY > 0f        // 向下=展开，向上=收起
} else {
    // 慢拖释放（|v| < 200 px/s）：位置决定
    fraction > 0.3f       // 超过 0.3 展开，否则收起
}
```

**阈值 0.3**（代码中唯一出现处）：设计文档原写 0.5，实际实现采用 0.3——让"稍微拉开一点就自动展开"更容易触发，同时小于 0.3 的轻触拖动会被视为"误触/无聊"而自动收回。

`isFling` 的边界 = `MIN_VELOCITY_PX_PER_SEC = 200 px/s`。

---

## 4. `flingToHeight` 动画分支全表

`flingToHeight(velocity, expand, target, collapseSpeedUpFactor = 1f, forcedDuration: Long? = null)` 根据参数走**互斥**的五条路径：

| # | 条件 | 插值器 | 时长 | 备注 |
|---|---|---|---|---|
| ① | `forcedDuration != null` | `PathInterpolator(0.33, 0, 0.67, 1)` | 外部传入（snap-back: 80~180ms） | `< 0.3` 松开专用的短 snap |
| ② | `expand && velocity==0` | `PathInterpolator(0.4, 0, 0, 1)` | `computeSnapDistance(d, H, 150, 350)` | 无速度展开 |
| ③ | `expand && velocity!=0` | `PathInterpolator(0.4, 0, 0, 1)` | `computeFlingDuration(d, v)` | 速度驱动展开 + overshoot → springBack |
| ④ | `!expand && velocity==0` | `PathInterpolator(0.33, 0, 0.67, 1)` | `computeSnapDistance(d, H, 100, 350)` | 无速度收起 snap |
| ⑤ | `!expand && velocity!=0` | `PathInterpolator(0.33, 0, 1, 1)` | `computeFlingDuration(d, v)` ∈ [100, **320**]ms | **速度驱动收起（修复重点）** |

**Overshoot（路径③专有）**：仅当 `expand && velocity > 0 && distance > 0` 时启用。过冲量 = `lerp(0.2, 1.0, saturate(vel / 4000)) × maxExpandedHeight × 0.15`，过冲后通过 `springBack(target)` 以 `FAST_OUT_SLOW_IN (0.4, 0, 0.2, 1)` 回弹，固定 **350ms**。

> 路径⑤是本次修复重点，详见[第 8 节](#8-收起动画修复collapse-too-long)。

---

## 5. `FlingAnimations` 纯函数 API

```kotlin
/** 动画时长：速度越快 → 越短。 clamp 到 [MIN_FLING_DURATION_MS, MAX_FLING_DURATION_MS] */
fun computeFlingDuration(distancePx: Float, velocityPxPerSec: Float): Long

/** 无速度 snap 时长：线性距离比例，clamp 到外部传入的 [min, max] */
fun computeSnapDuration(distancePx: Float, fullHeightPx: Float, min: Long, max: Long): Long

/** 速率是否够格做"真正的 fling"（>= 200 px/s） */
fun isFling(vectorVelocity: Float): Boolean

/** 向下 fling 的过冲量（px）：速度越大过冲越多 */
fun computeOvershoot(velocityY: Float, maxOvershootPx: Float): Float

/** 释放时应展开还是收起 */
fun shouldExpandOnRelease(velocityY: Float, vectorVelocity: Float, fraction: Float): Boolean
```

为何把计算抽成纯函数：AOSP 的 `FlingAnimationUtils` 依赖 WMShell 模块，这里自包含复刻，便于单测、便于 `NotificationPanelViewController` 不耦合 WMShell。

---

## 6. 关键常量与时长边界

```kotlin
// FlingAnimations.kt
private const val MIN_FLING_DURATION_MS = 100L      // 单次动画最短
private const val MAX_FLING_DURATION_MS = 320L      // 单次动画最长（原 600L，已修复）
private const val MIN_VELOCITY_PX_PER_SEC = 200f    // fling/drag 分界
private const val HIGH_VELOCITY_PX_PER_SEC = 4000f  // 过冲饱和速度

// NotificationPanelViewController.kt
private const val EXPAND_OVERSHOOT_FRAC = 0.15f     // 最大过冲 = 面板高度 × 15%
```

**注意**：`EXPAND_DURATION_MS = 350L` 与 `COLLAPSE_DURATION_MS = 300L` 仍为 dead code（声明但未在动画路径中引用），实际时长由各分支的 `computeSnapDuration` / `computeFlingDuration` 动态产出。保留是为了将来可能切换为固定时长模式。

各路径时长边界汇总：

| 路径 | 范围 | 插值器 |
|---|---|---|
| forced snap-back (`< 0.3` 收起) | **80 ~ 180ms** | ease-out (0.33,0,0.67,1) |
| 无速度展开 | 150 ~ 350ms | accelerate (0.4,0,0,1) |
| 速度驱动展开/收起 | 100 ~ **320ms** | 展开 accelerate / 收起 (0.33,0,1,1) |
| 无速度收起 | 100 ~ 350ms | ease-out (0.33,0,0.67,1) |
| springBack 回弹 | 固定 350ms | FAST_OUT_SLOW_IN (0.4,0,0.2,1) |

---

## 7. `< 0.3` 收起的 snap-back 路径

当用户慢拖展开不足 0.3 后就松开（`velocityY == 0`），面板需要"弹回"收起。这是一条**独立的短快路径**，在 `endMotionEvent` 里直接构造：

```kotlin
if (!expand && velocityY == 0f) {
    val distance = expandedHeight
    heightAnimator?.cancel()
    val duration = FlingAnimations.computeSnapDuration(
        distance, maxExpandedHeight, min = 80L, max = 180L)
    flingToHeight(velocity = 0f, expand = false, target = 0f,
        forcedDuration = duration)
    return
}
```

要点：
- `min = 80L, max = 180L` 是所有路径中**最紧**的时长区间，确保"弹回"干脆
- 在 fraction = 0.3 的极限位置，duration = `80 + (180-80)×0.3 = 110ms`
- 此路径**不受** `MAX_FLING_DURATION_MS` 修复影响，因其上限本来就是 180ms

> 注意 `velocityY == 0f` 守卫：如果用户在 `< 0.3` 时依然带速度向上轻扫，则走路径⑤（速度驱动）而非此 forced 路径。

---

## 8. 收起动画修复（collapse too long）

### 问题

当面板展开不足 0.3、用户**带向上速度松手**（路径⑤，`computeFlingDuration`），最坏情况下**慢速上滑**会让动画过长：

```
例：面板高 600px，上滑速度 400 px/s
  computed = 600 / 400 × 1000 = 1500ms
  clamp(1500, 100, 600) = 600ms   ← 原上限，体感"太慢"
```

根因：`MAX_FLING_DURATION_MS = 600L` 的天花板太高。

### 修复

```diff
- private const val MAX_FLING_DURATION_MS = 600L
+ private const val MAX_FLING_DURATION_MS = 320L
```

### 修复前后对比（路径⑤）

| 上滑速度 | 修复前 | 修复后 |
|---|---|---|
| 400 px/s（慢速） | 600ms | **320ms** |
| 800 px/s（中速） | 600ms | **320ms** |
| 2000+ px/s（快速） | ~150ms | ~150ms（不变） |

### 副作用评估

`computeFlingDuration` 被展开和收起共用。修复后**向下展开 fling** 的 cap 也从 600→320ms——这仅影响**极低速**的向下展开（通常展开场景下速度已经很快，本身就 < 320ms）。路径①②③④拥有各自独立的 clamp 区间，**不受本次修复影响**。

> 将来如需展开/收起独立 cap，可在 `computeFlingDuration` 增加 `maxOverride` 参数，或拆分为 `computeExpandFlingDuration` / `computeCollapseFlingDuration`。

---

## 9. 验证判据

- [x] 快速向下甩 → 面板冲过目标再 springBack 回弹（有过冲甩动感）
- [x] 快速向上甩 → 面板快速收起，速度越快闭合越快
- [x] 慢拖释放 → 按 0.3 比例判定开/合
- [x] `< 0.3` 慢拖松开 → forced snap-back，80~180ms 干脆弹回
- [x] `< 0.3` 带速向上松手 → 速度驱动收起，最坏 320ms（原 600ms）
- [x] 反复快速 fling 不 crash、不粘连（`heightAnimator?.cancel()` 取消机制）

---

*文档生成时间：2026-07-18*
*对应 commit：`feat(v0.2/shade): notification panel with gesture expand/collapse + animations`*
*AOSP 参考：`NotificationPanelViewController.flingToHeight` / `flingExpands` / `springBack` / `endMotionEvent`；`FlingAnimationUtils` (WMShell)*
