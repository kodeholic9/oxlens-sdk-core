# 세션 컨텍스트 — 2026-03-09 (v5)

> SDP 빌더 포팅 + MediaSession + OxLensClient 오케스트레이터까지 완료.
> 다음 세션: PC 더미 연동 테스트 또는 Android 포팅.

---

## 완료된 작업 (전체)

### 서버 (oxlens-sfu-server)

- MBCP 구현 완료 (v0.5.3) — rtcp.rs, ingress.rs, floor.rs 연결
- MBCP strip + PLI burst AbortHandle 전 경로 + UDP 로그레벨 수정
- E2E PTT 4시나리오 올패스 (oxlens-sfu-labs)
- **커밋 완료**: `fix: PLI burst zombie cancel on all exit paths + MBCP relay strip + UDP log level`

### SDK (oxlens-sdk-core) — 이전 세션 (3/9 오전)

1. 아키텍처 방향 재확정 — Rust + C++ FFI 확정
2. Twilio AudioSwitch 라이브러리 발굴
3. 기존 설계 문서 전체 리뷰 + 문서 현행화
4. 개발 환경 셋업 — LLVM 22.1.0, CMake 4.2.3, LIBCLANG_PATH
5. Cargo 워크스페이스 스캐폴딩 — 3크레이트 구조
6. ✅ cargo check + cargo build 성공 — webrtc-sys 빌드 통과
7. ✅ oxlens-core 시그널링 구현 — WS 연결, opcode 디스패치, HELLO→IDENTIFY→HEARTBEAT
8. ✅ server_config 스키마 문서화

### SDK (oxlens-sdk-core) — 이번 세션 (3/9 오후)

9. ✅ **SDP 빌더 Rust 포팅** — `oxlens-core/src/sdp/` (types.rs, builder.rs, validate.rs)
   - JS sdp-builder.js → Rust 1:1 포팅 (7함수)
   - 9 tests passed, 0 failed
   - publish/subscribe/PTT/inactive/re-nego + JSON 파싱 테스트

10. ✅ **oxlens-webrtc `MediaSession`** — `oxlens-webrtc/src/session.rs`
    - livekit-webrtc cargo doc 기반 API 확인 (추측 없음)
    - PeerConnectionFactory + pub_pc + sub_pc 2PC 구조
    - setup_publish (answer SDP 반환), setup_subscribe (re-nego + rollback)
    - home의 media-session.js 1:1 대응

11. ✅ **oxlens-core `OxLensClient` 오케스트레이터** — `oxlens-core/src/client.rs`
    - SignalClient + MediaSession + SDP 빌더 조립
    - ROOM_JOIN → RoomJoinResponse 파싱 → SDP 빌더 → 2PC 셋업 → SSRC 추출 → PUBLISH_TRACKS
    - TRACKS_UPDATE → 트랙 목록 갱신 (mid 보존) → subscribe re-nego
    - home의 livechat-sdk.js (_onJoinOk, _onTracksUpdate) 1:1 대응

12. ✅ **signaling/client.rs 수정** — RoomJoined에 전체 payload 전달, TracksUpdate에 action 추가

---

## 현재 프로젝트 파일 구조

```
oxlens-sdk-core/
├── Cargo.toml                    ← 워크스페이스 루트
├── .cargo/config.toml
├── .gitignore
├── README.md
├── RX_TX_PIPELINE_DESIGN.md
├── libwebrtc.aar
│
├── crates/
│   ├── oxlens-core/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs            ← pub mod client, sdp, signaling
│   │       ├── client.rs         ← ★ OxLensClient 오케스트레이터 (NEW)
│   │       ├── sdp/
│   │       │   ├── mod.rs        ← pub use (NEW)
│   │       │   ├── types.rs      ← ServerConfig, TrackDesc 등 (NEW)
│   │       │   ├── builder.rs    ← SDP 조립 함수 + 7 tests (NEW)
│   │       │   └── validate.rs   ← SDP 검증 + 2 tests (NEW)
│   │       └── signaling/
│   │           ├── mod.rs
│   │           ├── opcode.rs
│   │           ├── message.rs
│   │           └── client.rs     ← SignalEvent 수정 (RoomJoined payload)
│   ├── oxlens-webrtc/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs            ← pub mod session + re-exports (MODIFIED)
│   │       └── session.rs        ← ★ MediaSession 2PC 래퍼 (NEW)
│   └── oxlens-webrtc-sys/
│       ├── Cargo.toml
│       └── src/lib.rs            ← webrtc_sys re-export
│
├── doc/
│   ├── SESSION_CONTEXT_20260309_v2.md  ← 이 파일 (v5로 갱신)
│   ├── DEV_ENVIRONMENT_SETUP.md
│   ├── SERVER_CONFIG_SCHEMA.md
│   └── 기술문서_RTP파이프라인제어-20260308.md
```

---

## 크레이트 책임 분리 (확정)

```
oxlens-core  ──depends──→  oxlens-webrtc  ──depends──→  oxlens-webrtc-sys
 (비즈니스)                   (미디어 엔진)                  (C++ FFI)
```

| 크레이트 | 책임 |
|----------|------|
| oxlens-core | 시그널링, SDP 빌더, OxLensClient 오케스트레이터, Floor FSM(예정) |
| oxlens-webrtc | MediaSession (pub/sub 2PC), libwebrtc 래퍼, NetEQ 튜닝(Phase 2) |
| oxlens-webrtc-sys | webrtc-sys re-export, 커스텀 C++ 바인딩(Phase 2) |

---

## livekit-webrtc 0.2.0 API (cargo doc 확인 완료)

### PeerConnectionFactory
- `Default::default()` → 생성
- `create_peer_connection(RtcConfiguration)` → `Result<PeerConnection>`
- `create_audio_track(label, NativeAudioSource)` (PeerConnectionFactoryExt)
- `create_video_track(label, NativeVideoSource)` (PeerConnectionFactoryExt)

### PeerConnection
- `set_remote_description(SessionDescription)` → async
- `create_answer(AnswerOptions)` → async → `Result<SessionDescription>`
- `set_local_description(SessionDescription)` → async
- `on_connection_state_change`, `on_ice_candidate`, `on_track` 등 콜백

### SessionDescription
- `SessionDescription::parse(sdp: &str, SdpType)` → `Result<Self, SdpParseError>`
- `.to_string()` → SDP 문자열
- `SdpType::Offer`, `SdpType::Answer`, `SdpType::Rollback`

---

## 다음 할 일

### 옵션 A: PC 더미 연동 테스트
- examples/bench 에서 서버 연결 → ROOM_JOIN → 2PC → ICE→DTLS→SRTP 경로 확인
- NativeAudioSource에 더미 PCM 주입
- 하드웨어 없이 연결 경로만 검증

### 옵션 B: Android 포팅 직행
- platform/android/ JNI 바인딩
- .aar 빌드
- 실제 디바이스에서 마이크/스피커 테스트

### 공통 후속
- Floor FSM Rust 포팅 (Phase 1 로드맵 3번)
- NetEQ 튜닝 API 바인딩 (Phase 2)

---

## 환경 정보

- Rust: 1.93.1, MSVC cl.exe 19.44, LLVM 22.1.0, CMake 4.2.3
- Windows 11, Ryzen 5 3500U, RAM 16GB
- WSL2: Ubuntu 22.04
- 서버: `D:\X.WORK\GitHub\repository\oxlens-sfu-server` (v0.5.3)
- SDK: `D:\X.WORK\GitHub\repository\oxlens-sdk-core` (cargo build 성공)
- Labs: `D:\X.WORK\GitHub\repository\oxlens-sfu-labs`
- Web 클라이언트: `D:\X.WORK\GitHub\repository\oxlens-home`

---

*author: kodeholic (powered by Claude)*
