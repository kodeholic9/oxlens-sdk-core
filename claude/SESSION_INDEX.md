# 세션 컨텍스트 이력 — INDEX

> 실제 작업 시간순 정렬. 파일명의 날짜는 세션 시작 기준이며, 실제 완료는 다음 날에 걸치기도 함.

---

## Phase 1: Rust Core SDK (0309 ~ 0312)

| 순서 | 파일 | 요약 |
|------|------|------|
| 1 | `SESSION_CONTEXT_20260309.md` | SDP 빌더 포팅 + MediaSession + OxLensClient 오케스트레이터 완성 |
| 2 | `SESSION_CONTEXT_20260311.md` | bench ROOM_CREATE + 2PC 미디어 E2E + PTT Floor 전체 검증 |
| 3 | `SESSION_CONTEXT_20260311_2.md` | OxLensClient 통합 1단계: media_mut/signal 접근자 추가 |
| 4 | `SESSION_CONTEXT_20260312.md` | OxLensClient Arc 핸들 리팩터링, bench E2E 올패스, subscribe on_track 검증 |
| 5 | `SESSION_CONTEXT_20260312_v2.md` | Kotlin JNI wrapper 설계 + oxlens-jni crate 스캐폴딩 |

## Phase 2: Android 크로스 빌드 → Kotlin 전환 (0314 ~ 0315)

| 순서 | 파일 | 요약 |
|------|------|------|
| 6 | `SESSION_CONTEXT_20260314.md` | Android 크로스 빌드 성공 (Rust .so 20MB, aarch64) |
| 7 | `SESSION_CONTEXT_20260315.md` | LSE atomics SIGSEGV 디버깅 — lse_init.c constructor 구현, 미해결 |
| 8 | `SESSION_CONTEXT_20260314_kotlin.md` | **전환 결정**: Rust FFI → 순수 Kotlin + libwebrtc Java API |
| 9 | `SESSION_CONTEXT_20260315_kotlin.md` | Kotlin SDK Phase 2 — Publish ICE CONNECTED, Subscribe 코드 완성 |
| 10 | `SESSION_CONTEXT_20260314_subscribe_fix.md` | **최종**: Subscribe 크래시 해결 + PTT Phase 3 + E2E 양방향 음성 + AudioSwitch |

---

## 현재 상태 (최신: #10)

- ✅ Android Kotlin SDK — PTT Floor Control 포함 E2E 동작
- ✅ AudioSwitch (Speakerphone 우선, BT 지원)
- ✅ Conference / PTT 모드 데모앱 UI

## 남은 Backlog

- Mute 3-state (conference soft/hard + PTT 선언적)
- `onFloorTaken: user=` 빈 문자열 서버 확인
- subscribe SDP 디버그 로그 제거
- HW video codec factory 전환 검토
- 텔레메트리 (음성 지연 분석)

## Rust crate 상태

| crate | 상태 |
|-------|------|
| `oxlens-core` | **유지** — 시그널링/SDP 참조, bench/labs에서 계속 사용 |
| `oxlens-bench` | **유지** — 서버 E2E 테스트용 |
| `oxlens-jni` | **폐기** — Kotlin 전환으로 JNI 브릿지 불필요 |
| `oxlens-webrtc` | **폐기** — Java API로 대체 |
| `oxlens-webrtc-sys` | **폐기** — C++ FFI 불필요 |

---

*author: kodeholic (powered by Claude)*
