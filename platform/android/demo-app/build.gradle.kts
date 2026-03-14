// author: kodeholic (powered by Claude)
// demo-app — OxLens SDK 검증용 데모 앱
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oxlens.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oxlens.demo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // OxLens SDK (libwebrtc AAR은 SDK 모듈이 transitively 제공)
    implementation(project(":oxlens-sdk"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
