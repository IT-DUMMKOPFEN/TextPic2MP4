plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.itdummkopfen.text2video"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.itdummkopfen.text2video"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 2
        versionName = "0.1b"
    }

    // Fester Schlüssel, damit spätere APKs ohne Deinstallation drüberinstalliert werden können
    signingConfigs {
        getByName("debug") {
            storeFile = file("text2video-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// Keine Fremdbibliotheken: nur Android-Bordmittel (TextToSpeech, MediaCodec, MediaMuxer)
