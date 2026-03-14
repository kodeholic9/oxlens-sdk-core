// author: kodeholic (powered by Claude)
// oxlens-sdk — OxLens Android SDK 라이브러리 모듈 (Kotlin-native)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oxlens.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // libwebrtc AAR (로컬) — org.webrtc.* Java API
    // api로 선언하여 데모앱에서 org.webrtc.* (VideoSink, SurfaceViewRenderer 등) 직접 참조 가능
    api(files("libs/libwebrtc.aar"))

    // OkHttp — WebSocket 시그널링
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.json:json:20231013")

    // AudioSwitch (Twilio) — 오디오 라우팅 (earpiece/speaker/bluetooth/wired)
    implementation("com.twilio:audioswitch:1.1.5")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.annotation:annotation:1.9.1")
}
