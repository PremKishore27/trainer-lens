plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode=<run number>; local builds default to 1.
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = "0.2.$vCode"

// Release signing comes from environment variables (GitHub secrets in CI). Without them the
// release build falls back to the debug key so local builds still work.
val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "dev.trainerlens"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.trainerlens"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }
    signingConfigs {
        create("release") {
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
