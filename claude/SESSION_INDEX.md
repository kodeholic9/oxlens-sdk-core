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
| 10 | `SESSION_CONTEXT_20260314_subscribe_fix.md` | Subscribe 크래시 해결 + PTT Phase 3 + E2E 양방향 음성 + AudioSwitch |

## Phase 3: Mute + Video + Demo UI (0314 후반)

| 순서 | 파일 | 요약 |
|------|------|------|
| 11 | `SESSION_CONTEXT_20260314_mute_video.md` | Mute 3-state + Camera2 비디오 E2E + 프로젝트 정비 (3레포 GUIDELINES/CHANGELOG) |
| 12 | `SESSION_CONTEXT_20260314_demo_ui.md` | **최신**: RTX SSRC 필터링 + 데모앱 전면 개편 (Home UI 미러, SurfaceViewRenderer E2E) |

---

## 현재 상태 (최신: #12)

- ✅ Android Kotlin SDK — Conference 음성+영상 + PTT 음성 E2E
- ✅ Mute 3-state (Conference soft/hard + PTT audio 차단)
- ✅ Camera2 비디오 전송 (자동 감지: server_config video 코덱 기반)
- ✅ AudioSwitch (Speakerphone 우선, BT 지원)
- ✅ RTX SSRC 필터링 (PUBLISH_TRACKS primary only)
- ✅ 데모앱 전면 개편 (Home UI 미러, 다크 테마, Conference Grid + PTT View + SurfaceViewRenderer E2E)
- ✅ SDK 리스너 확장 (onRemoteVideoTrack, onParticipantJoined/Left, onPublishReady)

## 남은 Backlog

- hard mute 실제 구현 (video: stopCamera + dummy)
- 데모앱 아이콘 커스텀 (android:drawable 기본 → 전용 아이콘)
- ImageButton tint → app:tint 전환 (경고 제거)
- PTT 모드 데모앱 E2E 테스트 (터치 UI)
- 멀티 참가자 그리드 실사 테스트 (2~4명)
- EglBase dispose 정리
- subscribe SDP 디버그 로그 제거
- HW video codec factory 전환 검토
- 텔레메트리
- `onFloorTaken: user=` 빈 문자열 서버 확인

## Rust crate 상태

| crate | 상태 |
|-------|------|
| `oxlens-core` | **유지** — 시그널링/SDP 참조, bench/labs에서 계속 사용 |
| `oxlens-bench` | **유지** — 서버 E2E 테스트용 |
| `oxlens-jni` | **폐기** (DEPRECATED.md) |
| `oxlens-webrtc` | **폐기** (DEPRECATED.md) |
| `oxlens-webrtc-sys` | **폐기** (DEPRECATED.md) |

---

*author: kodeholic (powered by Claude)*
