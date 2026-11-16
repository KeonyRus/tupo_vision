import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.keonyrus.tupovision"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.keonyrus.tupovision"
    minSdk = 31
    targetSdk = 36
    versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
    versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

    // Meta Wearables Device Access Toolkit. With Developer Mode switched on in the Meta AI app the
    // SDK skips attestation, so both values stay "0". A published build needs the real values from
    // the Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  signingConfigs {
    // One fixed key for every build, so a new APK installs over the old one and the ChatGPT login
    // survives the update. It signs sideloaded test builds only.
    getByName("debug") {
      storeFile = rootProject.file("signing/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("debug") }
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.browser)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
}
