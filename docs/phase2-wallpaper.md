# Phase 2 — 壁纸引擎（ImageWallpaper） + 颜色提取（WallpaperProvider）

> 在进程存活（Phase 1）基础上，实现 AOSP SystemUI 的壁纸绘制：用户选择本服务后，桌面背景从黑色变为真实壁纸；同时提取壁纸主/次/三色，为未来的 state bar/monet 主题提供颜色脚手架。

## 1. 目标与成功判据

**目标**：
1. `ImageWallpaper`（`WallpaperService`）：用户选中后绘制当前系统壁纸，替代黑背景。
2. `WallpaperProvider`（`CoreStartable`）：SystemUI 启动时提取 `WallpaperColors` 变化监听，提供颜色脚手架。

**成功判据**：
1. APK 构建 BUILD SUCCESSFUL，Hilt 成功生成 `WallpaperProvider_Factory`
2. **无障碍回归**：进程仍存活（`ps | grep systemui`），授权名单配置后**启动时不抛 privapp-permissions `IllegalStateException`**
3. manifest 含 `BIND_WALLPAPER` 服务声明 + `WallpaperService` intent-filter + `wallpaper_metadata` 元数据（可在 picker 被选择）
4. logcat 可见：`Wallpaper colors updated: primary=#XXXXXXXX, isDark=boolean`
5. **用户选择本壁纸后**，桌面从黑色变为真实壁纸（`ImageWallpaper` logcat：`Wallpaper loaded` / `drawFrame`）

## 2. 参考实现

| 来源 | 文件 | 用途 |
|---|---|---|
| AOSP 原始 | `SystemUI/src/com/android/systemui/wallpapers/ImageWallpaper.java` | 权威参照：`CanvasEngine.drawFrameOnCanvas()` 是核心 |
| 直接复用 | `SystemUI-Lite2/.../wallpapers/ImageWallpaper.kt`（164 行） | 纯 framework 实现，零 Compose/Koin，直接抄入 |
| 适配来源 | `SystemUI-Lite2/.../lite/data/WallpaperProvider.kt` | 颜色提取逻辑优秀，但依赖 Compose Color + coroutines，需二去其一 |

**关键 API 验证**（`javap` 对 `framework-minus-apex.jar`）：

```java
// 全部 public API，compileOnly stub 与真机均可编译 + 调用
public android.graphics.drawable.Drawable WallpaperManager.getDrawable();
public void WallpaperManager.forgetLoadedWallpaper();
public void WallpaperManager.clear() throws IOException;
public android.app.WallpaperColors WallpaperManager.getWallpaperColors(int);
public void WallpaperManager.addOnColorsChangedListener(OnColorsChangedListener, Handler);
public int WallpaperColors.getPrimaryColor().toArgb();   // 返回 android.graphics.Color → .toArgb() 得 int
```

**与 Lite2 的刻意差异**（Lite2 的 wallpaper 实际上是"死"的）：

| 项 | Lite2 | v0.2（本次） | 原因 |
|---|---|---|---|
| `<service>` 的 `WallpaperService` intent-filter | **无** | 有 | 让 picker 能发现本服务；Lite2 缺此导致 picker 看不到 |
| `<service>` 的 `wallpaper_metadata` 元数据 | **无** | 有 | 提供 picker 显示用的 label/缩略图 |
| 声明签权墙纸权限 | 仅 `SET_WALLPAPER`（dangerous，自动授予） | 加 `SET_WALLPAPER_DIM_AMOUNT` + `READ_WALLPAPER_INTERNAL`（signature，需白名单） | 支持完整的壁纸读取/设置流程 |

Lite2 因此从来没真正激活过 wallpaper 引擎——picker 里看不到它。本次是**真上线**，需要走完签权 + picker 注册的全部路径。

## 3. 核心原理：AOSP 到最小实现的收缩

AOSP 完整实现（`ImageWallpaper.java` ~506 行）含：硬件广色域画布、引用计数 bitmap 延迟卸载、`peekBitmapDimensions` 固定 surface、多页视差、DisplayListener 等。

**v0.2 保留的核心绘制路径**：
```
onSurfaceChanged / onSurfaceRedrawNeeded / onVisibilityChanged(visible)
    ↓
loadWallpaper()  →  WallpaperManager.drawable  →  drawableToBitmap  →  bitmap
    ↓
drawFrame()  →  surface.lockHardwareCanvas()
                 canvas.drawBitmap(bmp, null, surfaceFrame, null)   // 全屏拉伸，同 AOSP
                 surface.unlockCanvasAndPost(canvas)
```

**v0.2 暂不实现**（后续里程碑追加）：广色域画布、固定 surface 尺寸、引用计数、视差、DisplayListener、WallpaperLocalColorExtractor 实时区域提取。

## 4. 工程实现

### 4.1 新增文件清单

```
app/src/main/
├── java/com/android/systemui/wallpapers/        [新建目录]
│   ├── ImageWallpaper.kt                         # 绘制引擎（抄入）
│   └── WallpaperProvider.kt                      # 颜色提取（CoreStartable，手工适配）
├── res/xml/wallpaper_metadata.xml               # picker 标签/描述/缩略图
├── res/drawable-nodpi/wallpaper_thumbnail.xml    # picker 缩略图（矢量占位）
└── res/values/strings.xml                        # 加 wallpaper_label/description
```

### 4.2 `ImageWallpaper.kt` — 壁纸绘制引擎

**来源**：`SystemUI-Lite2/.../wallpapers/ImageWallpaper.kt` 全文抄入，**仅**改 package 为 `com.android.systemui.wallpapers`。

**结构**：
```kotlin
class ImageWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = ImageWallpaperEngine()

    inner class ImageWallpaperEngine : Engine() {
        /* 生命周期 */
        onCreate      → WallpaperManager.getInstance
        onSurfaceChanged   → loadWallpaper()
        onSurfaceDestroyed → recycleBitmap() + forgetLoadedWallpaper()
        onVisibilityChanged(visible) / onSurfaceRedrawNeeded → drawFrame()

        /* 核心 */
        loadWallpaper()       wm.drawable → drawableToBitmap()；失败时 wm.clear() 重试（兜底默认壁纸）
        drawFrame()           surface.lockHardwareCanvas()（失败降级 lockCanvas(null)）
                              → canvas.drawBitmap(bmp, null, surfaceFrame, null)（全屏拉伸）
                              → surface.unlockCanvasAndPost()
        drawableToBitmap()    BitmapDrawable 直接取；否则 Bitmap.createBitmap + drawable.draw(canvas)
        recycleBitmap()       bitmap?.recycle()
    }
}
```

**唯一构建期修复**：`drawFrame` 中 `canvas` 须为非 null val（Kotlin 与原 Java 区别）：
```kotlin
val canvas: Canvas = try {
    surface.lockHardwareCanvas()
} catch (e: Exception) {
    try { surface.lockCanvas(null) }          // 降级软件画布
    catch (e2: Exception) { return }
}
```
原 Lite2 是 Java 风格的 `var canvas: Canvas? = var; try/catch` 写法，Kotlin 下 `canvas.drawBitmap(...)` 报 "Only safe (?.) calls allowed on nullable receiver"，改变为非 null val 即过。

**关键设计**：此 Service **不在 SystemUI boot 时启动**——只在用户在 picker 选中后由 `WallpaperManagerService` 绑定。因此它不会拖慢/卡死主进程的 boot path。

### 4.3 `WallpaperProvider.kt` — 颜色提取（CoreStartable）

来源：`SystemUI-Lite2/.../lite/data/WallpaperProvider.kt`。其依赖两大不存在于 v0.2 的能力，**二去其一**：

| Lite2 有 | v0.2 无 | 替代方案 |
|---|---|---|
| `androidx.compose.ui.graphics.Color` + `.toArgb()` | compose=false | **`android.graphics.Color`**（framework 类，jar 内可用）；ARGB int = `.toArgb()`；r/g/b = `Color.red/green/blue(int)` |
| `kotlinx.coroutines.*`（StateFlow / CoroutineScope） | 无 coroutines 依赖 | **手工 listener 接口 + volatile current 同步 getter**（`OnColorsChangedListener` 本就要求在主线程回调） |

**最终结构**：
```kotlin
@Singleton
class WallpaperProvider @Inject constructor(@ApplicationContext context: Context) : CoreStartable {

    data class WallpaperColorsData(primary:Int, secondary:Int, tertiary:Int, isDark:Boolean)
    fun interface OnWallpaperColorsChangedListener { fun onColorsChanged(data: WallpaperColorsData) }

    @Volatile var current: WallpaperColorsData       // 同步 getter，状态栏将来读取
    private val listeners = CopyOnWriteArrayList<OnWallpaperColorsChangedListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start() {
        refreshWallpaperColors()                       // 立即提取一次
        wm.addOnColorsChangedListener(listener, mainHandler)  // 监听变化
    }
    override fun stop() { wm.removeOnColorsChangedListener(...); listeners.clear() }

    private fun refreshWallpaperColors() {
        val wc = wm.getWallpaperColors(FLAG_SYSTEM) ?: return
        val p = wc.primaryColor.toArgb() or 0xFF000000.toInt()       // 强制不透明
        val s = (wc.secondaryColor?.toArgb() ?: p) or 0xFF000000.toInt()
        val t = (wc.tertiaryColor?.toArgb() ?: p) or 0xFF000000.toInt()
        val isDark = calculateIsDark(p)    // (0.299R+0.587G+0.114B)/255 < 0.5  ← 同 AOSP Tonal 亮度
        current = WallpaperColorsData(p, s, t, isDark)
        listeners.forEach { it.onColorsChanged(current) }
    }
}
```

**理由**：`addOnColorsChangedListener` 的回调本就必须落在主线程（AOSP 合同）；所以我们直接在主线程回调里跑轻量的 `getWallpaperColors`（系统缓存），无需后台 executor 和 coroutines。保留 `OnWallpaperColorsChangedListener` 是为未来 mont/state bar 预留的订阅桥，**本期不引入任何消费方**。

### 4.4 `AndroidManifest.xml` 改造

**(a) 加 3 个墙纸权限**：
```xml
<uses-permission android:name="android.permission.SET_WALLPAPER" />
<uses-permission android:name="android.permission.SET_WALLPAPER_DIM_AMOUNT" />
<uses-permission android:name="android.permission.READ_WALLPAPER_INTERNAL" />
```
- `SET_WALLPAPER_DIM_AMOUNT`、`READ_WALLPAPER_INTERNAL` 是 **signature** 级权限——必须加入设备 priapp-permissions 白名单，否则 `SystemServer` 启动抛 `IllegalStateException`（参见 §5）
- `SET_WALLPAPER` 是 dangerous 级，自动授予，但为了完整能力仍声明

**(b) 在 `<application>` 内追加 `ImageWallpaper` 服务声明**：
```xml
<service android:name=".wallpapers.ImageWallpaper"
         android:exported="true"
         android:permission="android.permission.BIND_WALLPAPER">
    <intent-filter>
        <action android:name="android.service.wallpaper.WallpaperService" />
    </intent-filter>
    <meta-data android:name="android.service.wallpaper"
               android:resource="@xml/wallpaper_metadata" />
</service>
```

**字段契约**（背 wallpaper 子系统安全）：
| 字段 | 原因 |
|---|---|
| `android:permission="android.permission.BIND_WALLPAPER"` | **强制**：仅持有 `BIND_WALLPAPER` 的 system `WallpaperManagerService` 才能 bind 本服务，防劫持 |
| `<intent-filter>` action `android.service.wallpaper.WallpaperService` | 让**系统 wallpaper picker 能发现**并列出本服务；缺此 picker 看不到 |
| `<meta-data android.service.wallpaper>` → `@xml/wallpaper_metadata` | 给 picker 提供 label/descriptor/缩略图；**若 meta-data 被引用，则 xml 必须存在**（manifest merger 校验） |

**构建产物**：Phase 1 基础上 APK 从 6 dex → 7 dex（新增 `ImageWallpaper` + `WallpaperProvider`），Hilt 生成 `WallpaperProvider_Factory`。

### 4.5 `res/xml/wallpaper_metadata.xml` + 缩略图 + 字符串

```xml
<!-- res/xml/wallpaper_metadata.xml -->
<wallpaper xmlns:android="http://schemas.android.com/apk/res/android"
    android:label="@string/wallpaper_label"
    android:description="@string/wallpaper_description"
    android:thumbnail="@drawable/wallpaper_thumbnail" />
```
```xml
<!-- res/drawable-nodpi/wallpaper_thumbnail.xml（矢量占位，自包含无位图） -->
<shape ... android:shape="rectangle">
    <gradient android:startColor="#FF00ADB5" android:endColor="#FF222831" android:angle="135" />
</shape>
```
```xml
<!-- res/values/strings.xml -->
<string name="wallpaper_label">SystemUI-Lite Wallpaper</string>
<string name="wallpaper_description">SystemUI-Lite default wallpaper engine</string>
```

### 4.6 Hilt 注册（`SystemUIApplication`）

```kotlin
class SystemUIApplication : Application() {
    @Inject lateinit var coreStartableComponent: CoreStartableComponent
    @Inject lateinit var wallpaperProvider: WallpaperProvider    // 新增

    override fun onCreate() {
        coreStartableComponent.register(wallpaperProvider)         // 新增：启动即提取颜色 + 注册变化监听
        coreStartableComponent.register(DummyCoreStartable())     // 保留冒烟测试
        coreStartableComponent.start()
    }
}
```

`WallpaperProvider` 已是 `@Inject constructor(@ApplicationContext context)` + `@Singleton`，Hilt 直接构造，`ApplicationModule` 无需修改。

## 5. ✅ 关键坑：privapp-permissions 白名单

### 5.1 现象

部署新 APK 后重启抛：
```
java.lang.IllegalStateException: Signature|privileged permissions not in privapp-permissions allowlist:
  {com.android.systemui (/system/priv-app/SystemUI):
    android.permission.SET_WALLPAPER_DIM_AMOUNT,
    android.permission.READ_WALLPAPER_INTERNAL}
at PermissionManagerServiceImpl.onSystemReady(PermissionManagerServiceImpl.java:4447)
```

### 5.2 根因

Android 14 `PermissionManagerServiceImpl` 在 `onSystemReady` 扫描 **每个 app 的 `uses-permission`**，任一 signature/privileged 级别权限**未出现在设备 allowlist** 即拒绝 boot。

`SET_WALLPAPER_DIM_AMOUNT` 与 `READ_WALLPAPER_INTERNAL` 均为 signature 级，且我们在 manifest 里**显式声明**了它们 → 被扫描到 → 设备 allowlist 里没有 → reboot 失败。

对比 Lite2：Lite2 只声明了 `SET_WALLPAPER`（dangerous，自动授予），没声明这两个 signature 权限，因此从来不被 allowlist 扫描 → 从来不崩。**这就是 Lite2 "不出问题"的秘密**——它从来没真正上线 wallpaper（picker 也看不到它）。

### 5.3 修复

**`deploy/privapp-permissions-systemui-lite.xml`** 加 3 行：
```xml
<permission name="android.permission.SET_WALLPAPER" />
<permission name="android.permission.SET_WALLPAPER_DIM_AMOUNT" />
<permission name="android.permission.READ_WALLPAPER_INTERNAL" />
```

**设备部署**：
```bash
adb root && adb remount
adb push deploy/privapp-permissions-systemui-lite.xml /system/etc/permissions/
adb shell sync
adb reboot
```
注：从 stack trace 可见 APK 实际位于 `/system/priv-app/SystemUI`（非 `/system_ext`），因此白名单放在 `/system/etc/permissions/`（系统扫 `/system/etc/permissions` + `/vendor/etc/permissions` + `/system_ext/etc/permissions`）。

## 6. ⚠️ 激活约束：壁纸不会自动启用

`BIND_WALLPAPER` **不会自动启用**——这是 wallpaper 子系统的安全契约：**一个 WallpaperService 只在用户在系统壁纸选择器中点选后才被绑定、绘制**。选中前背景始终是黑色，**这不是 bug**。

**激活步骤**（部署并重启后，**必须做一次**）：
```
Settings → Wallpaper → Live wallpapers（或 Wallpapers）
    → 找到 "SystemUI-Lite Wallpaper"
    → Set wallpaper
```

**备选 adb 激活**（部分版本支持）：
```bash
adb shell cmd wallpaper set-component com.android.systemui/.wallpapers.ImageWallpaper
```

激活后永久生效（`persistent` 系统壁纸插件特性），无需再选。

## 7. 设备验证清单

| 步骤 | 命令 | 预期 |
|---|---|---|
| **进程仍存活** | `adb shell ps -A \| grep systemui` | 仍有 uid=system 行 |
| **无 boot 错误** | logcat 过滤 `PermissionManagerService` / `SystemServer` | 无 `IllegalStateException` 类错 |
| **颜色提取** | `adb logcat -d -s WallpaperProvider:*` | `Wallpaper colors updated: primary=#XXXXXXXX, isDark=true/false` |
| **服务可在 picker 见** | 进入 Settings → Wallpaper | 能看到 "SystemUI-Lite Wallpaper" |
| **激活后绘制** | 选中壁纸 + `adb logcat -d -s ImageWallpaper:*` | `onCreate` → `Wallpaper loaded: WxH` → `drawFrame` 日志；**视觉上桌面从黑变图片壁纸** |
| **变化监听** | 换一张壁纸（Settings 改壁纸）+ `adb logcat -d -s WallpaperProvider:V` | 再次 `Wallpaper colors updated`，`primary` 值变化 |
| **回滚** | `adb shell cp /data/local/tmp/SystemUI-backup.apk /system/priv-app/SystemUI/SystemUI.apk && adb reboot` | 恢复原 SystemUI |

## 8. 新增/修改文件一览

```
SystemUI-Lite-v0.2/
├── deploy/privapp-permissions-systemui-lite.xml        [改] 追加 3 墙纸权限白名单
├── app/src/main/
│   ├── AndroidManifest.xml                             [改] 加 3 permission + 1 service
│   ├── java/com/android/systemui/
│   │   ├── SystemUIApplication.kt                      [改] 注入 + register WallpaperProvider
│   │   └── wallpapers/                                 [新建]
│   │       ├── ImageWallpaper.kt                       [新建] 壁纸绘制引擎（抄入，仅改 package）
│   │       └── WallpaperProvider.kt                    [新建] 颜色提取（CoreStartable，framework Color）
│   └── res/
│       ├── xml/wallpaper_metadata.xml                  [新建] picker 标签/描述/缩略图
│       ├── drawable-nodpi/wallpaper_thumbnail.xml       [新建] picker 缩略图
│       └── values/strings.xml                          [改] 追加 wallpaper_label/description
└── app/build/outputs/apk/debug/SystemUI.apk            [重建产物]
```

**`app/build.gradle.kts`、`libs.versions.toml` 无变更**——零新增依赖，全部复用 framework / 已有 Hilt。

## 9. 关键认知

1. **WallpaperService 不在 boot 时启动** — 它是系统 picker/绑定驱动，不会卡 boot path。Phase 1 的存活结论**不被壁纸里程碑影响**。
2. **`BIND_WALLPAPER` 不是"申请"的，是"设防"的** — 我们在 `<service>` 上声明它，是要**限制**谁可以 bind 它到系统，不是我们需要这个权限。我们需要的是 `SET_WALLPAPER` / `SET_WALLPAPER_DIM_AMOUNT` / `READ_WALLPAPER_INTERNAL`（读/写壁纸文件）。
3. **Signature 权限必须进 allowlist** — Android 14 硬性要求。manifest 声明 + APK priv-app 安装，**允许**你意向性地使用 signature 权限，但还需 allowlist 文件显式放行；否则 SystemServer 拒绝 boot。
4. **颜色提取最好用 framework `android.graphics.Color`** — 而不是引入 Compose Color 重度依赖；`Color.red/green/blue(int)` 直接作用 ARGB int，零 overhead。
5. **`OnColorsChangedListener` 须在主线程** — AOSP 规定；也因此 "把后台 coroutines 改成主线程回调中的同步调用" 是正确降级，不是偷懒。

## 10. Step N+1 展望（本期不做）

- 颜色接入 state bar / QS tile 的 tint（monet 暗亮适应）
- `LockscreenWallpaper`（非动态壁纸的锁屏壁纸，`WallpaperDrawable` 居中裁切）
- `peekBitmapDimensions` + `setFixedSize` 固定 surface 尺寸（内存 & 性能）
- 多页视差（`onOffsetsChanged` 处理 launcher 滑页偏移）
- 广色域画布（`lockHardwareWideColorGamutCanvas`，@hide API）
- `WallpaperLocalColorExtractor` 实时区域提取（AOSP monet 真实输入）

每步按「最小改动 → 构建 → 设备验证 → 再迭代」推进。
