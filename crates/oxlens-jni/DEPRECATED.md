# DEPRECATED

이 crate는 **폐기**되었습니다.

## 사유

Rust FFI 경로의 구조적 문제로 Kotlin 전환 결정 (2026-03-14).

- Rust 1.93 outline-atomics + libwebrtc static link → Android cdylib에서 SIGSEGV
- 20MB .so, 10분 빌드, 디버깅 난이도
- LiveKit도 Android에서는 Kotlin + Java API 사용

## 대체

`platform/android/oxlens-sdk/` (순수 Kotlin + libwebrtc AAR)

## 참조

- `claude/SESSION_CONTEXT_20260314_kotlin.md` — 전환 결정 상세
- `claude/SESSION_CONTEXT_20260315.md` — LSE atomics 디버깅 기록 (참고용)
