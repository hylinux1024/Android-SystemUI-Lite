# Phase 1 — 最小化 SystemUI 启动

> 让一个签名的 `SystemUI.apk` 在 Android 14 Google Pixel 上安装、启动并**保持进程存活**——验证框架层（平台签名、uid.system、manifest 声明、Hilt DI、AppComponentFactory）完整闭环。本阶段**无任何 UI**。

## 1. 目标与成功判据

**目标**：把 `SystemUIService` 跑起来，证明 `SystemUIApplication` + `CoreStartable` 生命周期 + Hilt 注入在 platform-signed 特权应用里能跑通。

**成功判据（均在真机验证）**：
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/SystemUI.apk`
2. APK 由 AOSP platform 证书签名（`CN=Android, O=Android`）
3. 安装到 `/system_ext/priv-app/SystemUI/SystemUI.apk` 后设备正常启机动，无 `IllegalStateException`
4. `adb shell ps -A | grep systemui` 可见 `system` uid 的进程存活
5. logcat 可见 `DummyStartable: started OK`，无 `FATAL EXCEPTION`

## 2. 工作基础

Gradle 脚手架：

| 已有 | 用途 |
|---|---|
| `libs/framework-minus-apex.jar` | 隐藏 API / `@hide` API 的 `compileOnly` 依赖（Sdk 34+ 用 `framework-minus-apex` 替代旧的 `framework.jar`，因 apex 拆分后 `framework.jar` 不完整） |
| `platform.keystore` | AOSP 测试 platform 签名（alias=`platform`，密码 `android12345`） |
| `gradle wrapper`、`local.properties` | 构建工具链 |


## 3. 技术决策：toolchain 降级

脚手架自带的 toolchain（AGP 9.1.1 + Kotlin 2.2.10）存在复合风险，因此**完全复用 `SystemUI-Lite`（已验证可部署）的 toolchain**：

| 组件 | 脚手架原值 | Phase 1 采用值 | 原因 |
|---|---|---|---|
| AGP | 9.1.1 | **8.4.2** | 9.x 强制 build-tools 36；与 framework-minus-apex (API 34) 配合未验证 |
| Kotlin | 2.2.10 | **1.9.24** | 与 Hilt 2.50 + kapt 完全兼容 |
| DI | (catalog 含 Koin) | **Hilt 2.50 + kapt** | 接近真实 AOSP；用户选择 |
| Gradle wrapper | 9.3.1 | **8.14.5** | 与 AGP 8.4.2 匹配 |

**JDk**：AGP 8.4 要求 JDK 17。本机默认 JDK 是 20，构建命令前须显式：
```
JAVA_HOME=/Users/young/Library/Java/JavaVirtualMachines/corretto-17.0.14/Contents/Home
ANDROID_HOME=/Users/young/Library/Android/sdk
```

保留脚手架原样的核心资产：`framework-minus-apex.jar` 与 `platform.keystore`（跨 toolchain 通用）。

## 4. 工程实现

### 4.1 根配置（3 文件）

**`gradle/libs.versions.toml`**：精简为仅 4 组依赖，删除 Compose / Room / Retrofit / Koin / Firebase 等脚手架遗留项。

**根 `build.gradle.kts`**：四插件（加 kapt）。

**`settings.gradle.kts`**：保留 `include(":app")`，移除 foojay toolchain。

**`gradle.properties`**：**移除 `org.gradle.configuration-cache=true`**（与 kapt 不兼容，会触发 "cannot serialize Kotlin multi-process daemon"）。

**`gradle/wrapper/gradle-wrapper.properties`**：`distributionUrl` 改为 `gradle-8.14.5-bin.zip`。

### 4.2 `app/build.gradle.kts`（核心）

```kotlin
android {
    namespace = "com.android.systemui"      // 必须：SystemServer 按包名绑定 SystemUI
    compileSdk = 34                         // 与 framework-minus-apex.jar 对齐
    defaultConfig {
        applicationId = "com.android.systemui"  // 同样必须，不可改名
        minSdk = 34; targetSdk = 34
    }
    signingConfigs {
        create("platform") {                // 平台签名，签名级权限必需
            storeFile = file("../platform.keystore")
            storePassword = "android12345"; keyAlias = "platform"; keyPassword = "android12345"
        }
    }
    buildTypes {
        release { signingConfig = ...; isMinifyEnabled = false; proguardFiles(...) }
        debug   { signingConfig = ... }     // debug 也强制平台签名
    }
    applicationVariants.all { outputFileName = "SystemUI.apk" }  // 文件名必须精确
}
dependencies {
    compileOnly(files("../libs/framework-minus-apex.jar"))  // 隐藏 API：编译期可见，不进 APK
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
```

- `compileOnly`：`@hide` API（IStatusBarService 回调窗口类型、WallpaperManager 高级方法等）编译期可见但不打包——运行时由设备自带 framework 提供
- `sharedUserId="android.uid.system"` + `coreApp` + platform 签名：三件套，缺任一则 UID/权限校验失败

### 4.3 `AndroidManifest.xml` 关键字段

```xml
<manifest android:sharedUserId="android.uid.system" coreApp="true">
    <uses-permission ... /> <!-- RECEIVE_BOOT_COMPLETED 等最小集 -->
    <permission android:name="com.android.systemui.permission.SELF" android:protectionLevel="signature" />

    <application
        android:name=".SystemUIApplication"
        android:persistent="true"                        // 框架在退出后自动重启
        android:process="com.android.systemui"           // 规范进程名
        android:directBootAware="true"                   // 用户解锁前即可运行
        android:appComponentFactory=".SystemUIAppComponentFactory"  // Hilt 注入 DI 服务所必须
        tools:replace="android:appComponentFactory">

        <service android:name=".SystemUIService" android:exported="true" />
        <receiver android:name=".SysuiRestartReceiver" android:exported="false">
            <intent-filter>
                <action android:name="com.android.systemui.action.RESTART" />
                <data android:scheme="package" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

`appComponentFactory` + `tools:replace`：Hilt 生成 `@AndroidEntryPoint` 的 `SystemUIService` 必须经自定义工厂实例化，否则 DI 无法注入。

### 4.4 源码（7 个 Kotlin 文件）

#### 4.4.1 `CoreStartable.kt` — 生命周期接口

仿 AOSP `com.android.systemui.CoreStartable`，所有方法默认空实现，仅 `start()` 抽象。

```kotlin
interface CoreStartable {
    fun start()
    fun stop() {}
    fun onBootCompleted() {}
    fun onUserSwitch(newUserId: Int) {}
    fun onUserSwitchComplete(userId: Int) {}
    fun onConfigChanged(newConfig: Configuration) {}
}
```

#### 4.4.2 `CoreStartableComponent.kt` — 组件管理器

仿 AOSP 的 `CoreStartableRepository` + `ComponentHelper`。`@Singleton`，维护 `MutableList<CoreStartable>`，`start()/stop()/onBootCompleted()/onConfigChanged()` 逐一 try/catch 派发，不怕单个组件崩溃拖死整体。

#### 4.4.3 `SystemUIApplication.kt` — Hilt 入口

```kotlin
@HiltAndroidApp
class SystemUIApplication : Application() {
    @Inject lateinit var coreStartableComponent: CoreStartableComponent

    override fun onCreate() {
        coreStartableComponent.register(DummyCoreStartable())  // 冒烟测试：一条日志证明 register→start 通路 OK
        coreStartableComponent.start()
    }
}
```

设计要点：**启动触发点放在 `SystemUIApplication.onCreate`** 而非 `SystemUIService.onCreate`——Android 保证 `Application.onCreate` 先于任何 Service 启动，且 Hilt 图此时已就绪。AOSP 原版放在 Service 里，但 Hilt 方更自然。

#### 4.4.4 `DummyCoreStartable.kt` — 冒烟组件

```kotlin
private class DummyCoreStartable : CoreStartable {
    override fun start() { Log.i("DummyStartable", "started OK") }
}
```
logcat 看到这行即证明 DI + register + start 三连通路 OK。后续真实组件稳定后可移除。

#### 4.4.5 `SystemUIService.kt` — 启动入口 Service

```kotlin
@AndroidEntryPoint
class SystemUIService : Service() {
    override fun onCreate() {
        // 启动已由 Application.onCreate 触发，此处仅存在 + 存活
        Log.i("SystemUIService", "onCreate")
    }
    override fun onStartCommand(...) = START_STICKY   // 被杀后系统尝试重启
    override fun onBind(intent: Intent?): IBinder? = null
}
```

`@AndroidEntryPoint` → 必须通过 `SystemUIAppComponentFactory.instantiateService` 实例化，否则 Hilt 注入失败。

#### 4.4.6 `SystemUIAppComponentFactory.kt` — DI 实例化工厂

继承 `AppComponentFactory`，四个 `instantiateXxx` 方法仅日志 + `super`，让 Hilt 介入真实实例化。

#### 4.4.7 `SysuiRestartReceiver.kt` — 重启触发器

`onReceive` 中 `Process.killProcess(Process.myPid())`，配合 `persistent="true"` 框架立即重启——用于可重复测试 boot 路径：
```bash
adb shell am broadcast -a com.android.systemui.action.RESTART -p com.android.systemui
```

#### 4.4.8 `di/ApplicationModule.kt` — Hilt DI 模块

最小集：仅提供 `provideMainHandler()` 和 `provideContentResolver()`。AudioManager / WifiManager / CameraManager 等由各 UI 模块按需追加。

## 5. 构建

```bash
JAVA_HOME=/Users/young/Library/Java/JavaVirtualMachines/corretto-17.0.14/Contents/Home \
ANDROID_HOME=/Users/young/Library/Android/sdk \
./gradlew assembleDebug --no-daemon
```

**首次构建坑**：

| 错误 | 原因 | 修复 |
|---|---|---|
| `checkDebugAarMetadata: android.useAndroidX property is not enabled` | 脚手架精简 `gradle.properties` 时把 `android.useAndroidX=true` 也删了 | 添加回 `gradle.properties` |
| `Unresolved reference: Service` | `SystemUIAppComponentFactory.instantiateService` 返回类型 `Service` 缺 import | 加 `import android.app.Service` |

构建成功后：APK 约 **5.8 MB**（multi-dex classes.dex~classes6.dex），`apksigner verify --print-certs` 确认 `CN=Android, O=Android`。

## 6. 部署与设备验证

### 6.1 部署

```bash
# 0) 备份原始 SystemUI（bootloop 回滚用，务必第一步做）
adb shell cp /system_ext/priv-app/SystemUI/SystemUI.apk /data/local/tmp/SystemUI-backup.apk

# 1) 推送 APK（用户 root + remount）
adb root && adb remount
adb shell mkdir -p /system_ext/priv-app/SystemUI
adb push app/build/outputs/apk/debug/SystemUI.apk /system_ext/priv-app/SystemUI/SystemUI.apk
adb shell chmod 644 /system_ext/priv-app/SystemUI/SystemUI.apk
adb reboot
```

注：`/` 挂载方式为 overlay，需 `remount` 后写入 overlay upperdir，重启持久化。

### 6.2 真机验证

```bash
# 1) 进程存活
adb shell ps -A | grep systemui    # 应有 uid=system 行

# 2) 关键日志
adb logcat -d -s SystemUIApplication:* SystemUIService:* SystemUIAppCompFactory:* DummyStartable:*
```
预期输出（有序）：
```
SystemUIAppCompFactory: Instantiating service: .SystemUIService
SystemUIApplication onCreate — Hilt graph ready, registering CoreStartables
CoreStartableComponent      : Starting: DummyCoreStartable
DummyStartable              : started OK
SystemUIApplication         : All CoreStartable services started; process is alive.
SystemUIService             : onCreate — services already started by Application
```

### 6.3 失败回滚

```bash
adb root && adb remount
adb shell cp /data/local/tmp/SystemUI-backup.apk /system_ext/priv-app/SystemUI/SystemUI.apk
adb reboot
```

## 7. 项目最终结构

```
SystemUI-Lite-v0.2/
├── build.gradle.kts                       # 四插件（+kapt）
├── settings.gradle.kts                    # include(":app")
├── gradle.properties                      # useAndroidX=true，无 configuration-cache
├── gradle/libs.versions.toml              # 4 组依赖（AGP/Kotlin/Hilt/coreKtx）
├── gradle/wrapper/                        # Gradle 8.14.5
├── platform.keystore                      # AOSP platform 签名
├── libs/framework-minus-apex.jar          # 隐藏 API compileOnly stub
└── app/
    ├── build.gradle.kts                   # namespace/appId = com.android.systemui，platform 签名
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml            # persistent/directBootAware/appComponentFactory
        ├── java/com/android/systemui/
        │   ├── CoreStartable.kt
        │   ├── CoreStartableComponent.kt
        │   ├── SystemUIApplication.kt
        │   ├── SystemUIService.kt
        │   ├── SystemUIAppComponentFactory.kt
        │   ├── SysuiRestartReceiver.kt
        │   └── di/ApplicationModule.kt
        └── res/values/                    # strings/themes/colors/dimens
```

## 8. 关键认知（踩坑沉淀）

1. **Package name 必须为 `com.android.systemui`** — SystemServer 按包名绑定，改了就启不来。
2. **Platform 签名不可省** — `uid.system` + `coreApp` 校验签名；debug 构建也须强制 platform signingConfig。
3. **`compileOnly` vs `implementation`** — framework jar 必须 compileOnly（不进 APK），错用 implementation 会让 APK 体积暴涨且与设备 framework 冲突。
4. **`configuration-cache` 与 kapt 冲突** — 直接在 `gradle.properties` 移除。
5. **进程存活靠 `persistent="true"`** — 这是 SystemUI 进程区别于普通 app 的核心：系统保活、崩溃重启。
6. **Application.onCreate 先于 Service** — 这是把启动点放在 Application 的生命周期依据；AOSP 原版放在 Service 是历史原因。
