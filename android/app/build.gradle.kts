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
        versionCode = 1
        versionName = "0.1a"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// Keine Fremdbibliotheken: nur Android-Bordmittel (TextToSpeech, MediaCodec, MediaMuxer)
