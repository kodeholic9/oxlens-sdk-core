// author: kodeholic (powered by Claude)
//! oxlens-jni — JNI bridge for Android
//!
//! Rust SDK(oxlens-core)를 Android Kotlin에서 호출하기 위한 JNI 레이어.
//!
//! ## 아키텍처
//! ```text
//! Kotlin (OxLensClient.kt)
//!   ↕ JNI
//! oxlens-jni (이 크레이트) — cdylib → liboxlens_jni.so
//!   ↓
//! oxlens-core (비즈니스 로직)
//!   ↓
//! oxlens-webrtc (libwebrtc wrapper)
//! ```
//!
//! ## 핵심 설계
//! - 전용 Tokio Runtime 1개 (lazy 초기화)
//! - OxLensClient 포인터를 Long 핸들로 Kotlin에 전달
//! - ClientEvent → Kotlin OxLensEventListener 콜백 (GlobalRef)
//! - JNI_OnLoad에서 JavaVM 캐시

mod client;
mod callback;

use std::sync::OnceLock;

use jni::JavaVM;
use jni::sys::jint;
use log::LevelFilter;
use tokio::runtime::Runtime;

// ================================================================
//  글로벌 싱글턴
// ================================================================

/// Tokio 비동기 런타임 — .so 수명 동안 유지
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// JavaVM 참조 — JNI_OnLoad에서 캐시, 이벤트 콜백 시 AttachCurrentThread 용도
static JVM: OnceLock<JavaVM> = OnceLock::new();

/// Tokio Runtime 접근자
pub(crate) fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Runtime::new().expect("failed to create tokio runtime")
    })
}

/// JavaVM 접근자
pub(crate) fn jvm() -> &'static JavaVM {
    JVM.get().expect("JVM not initialized — JNI_OnLoad missing?")
}

// ================================================================
//  JNI_OnLoad — .so 로드 시 자동 호출
// ================================================================

/// Android System.loadLibrary("oxlens_jni") 시 자동 호출
///
/// 역할:
/// 1. JavaVM 참조 캐시 (이벤트 콜백에서 AttachCurrentThread 필요)
/// 2. Tokio Runtime 초기화
/// 3. tracing 초기화 (Android logcat 출력)
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    // JavaVM 캐시
    JVM.set(vm).expect("JVM already initialized");

    // Tokio Runtime 초기화 (lazy이지만 여기서 미리 생성)
    let _ = runtime();

    // Android logcat 로깅 초기화
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("oxlens-jni"),
    );

    log::info!("oxlens-jni loaded (JNI_OnLoad)");

    jni::sys::JNI_VERSION_1_6
}
