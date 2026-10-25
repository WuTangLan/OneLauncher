plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marcus.onelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marcus.onelauncher"
        minSdk = 28          // Philips PUS8558 runs Google TV (Android 12+)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs straight away.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No extra libraries: plain Android framework only, so it stays tiny and fast on the TV.
