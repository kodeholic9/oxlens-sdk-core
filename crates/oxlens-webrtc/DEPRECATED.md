# DEPRECATED

이 crate는 **폐기**되었습니다.

## 사유

Android에서 Kotlin + libwebrtc Java API로 전환 (2026-03-14).
C++ FFI 브릿지가 더 이상 필요하지 않음.

## 대체

`platform/android/oxlens-sdk/media/MediaSession.kt` (org.webrtc.* 직접 사용)

## 보존 이유

`oxlens-core`가 참조하는 타입이 있어 workspace에서 완전 제거하면 빌드 깨짐.
bench/labs에서 사용하는 `oxlens-core` → `oxlens-webrtc` 의존 경로 유지 목적.
