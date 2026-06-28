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
    minSdk = 29
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

androidComponents {
  onVariants(selector().all()) { variant ->
    variant.outputs.forEach { output ->
      (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName =
          "SystemUI.apk"
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

  // Koin
  implementation(libs.koin.android)
  implementation(libs.koin.compose)
  implementation(libs.koin.core)

  // Test
  testImplementation(libs.junit)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
