plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.escala"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.escala"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
// Sem dependências externas: só APIs do framework Android.
