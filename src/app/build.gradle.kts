plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.antirecording"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.antirecording"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
}
