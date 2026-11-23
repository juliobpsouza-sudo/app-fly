plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.zarpa"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.zarpa"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "0.5.9"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
