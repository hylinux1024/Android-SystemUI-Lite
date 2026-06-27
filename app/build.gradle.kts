import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.android.systemui"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.android.systemui"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("platform") {
      storeFile = file("../platform.keystore")
      storePassword = "android12345"
      keyAlias = "platform"
      keyPassword = "android12345"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("platform")
    }
    debug {
      signingConfig = signingConfigs.getByName("platform")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Copy and rename APK to fixed output path using exec
tasks.register("renameDebugApk") {
  description = "Rename debug APK to SystemUI.apk"
  dependsOn("packageDebug")
  val buildDirectory = layout.buildDirectory
  doLast {
    val source = buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
    val destDir = buildDirectory.dir("outputs/apk/debug").get().asFile
    val dest = File(destDir, "SystemUI.apk")
    if (source.exists()) {
      source.copyTo(dest, overwrite = true)
      println("APK renamed to: ${dest.absolutePath}")
    }
  }
}

tasks.register("renameReleaseApk") {
  description = "Rename release APK to SystemUI.apk"
  dependsOn("packageRelease")
  val buildDirectory = layout.buildDirectory
  doLast {
    val source = buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile
    val destDir = buildDirectory.dir("outputs/apk/release").get().asFile
    val dest = File(destDir, "SystemUI.apk")
    if (source.exists()) {
      source.copyTo(dest, overwrite = true)
      println("APK renamed to: ${dest.absolutePath}")
    }
  }
}

dependencies {
  // Core Android
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)

  // Compose - keep for status bar and notification shade UI
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)

  // Lifecycle
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Test
  testImplementation(libs.junit)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
