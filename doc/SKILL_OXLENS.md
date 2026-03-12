---
name: oxlens
description: |
  OxLens SFU 서버 + 네이티브 PTT SDK 프로젝트 작업 컨텍스트.
  코딩, 설계 질문, 리팩터링, 테스트 작성, 코드 리뷰가 언급되면 반드시 이 스킬을 사용할 것.
  "oxlens", "SFU", "sfu-server", "sdk-core", "PTT", "무전", "미디어 릴레이",
  "SRTP", "DTLS", "ICE", "RTP", "MBCP", "Floor Control",
  "OxLensClient", "SignalClient", "MediaSession", "SignalSender",
  "PeerConnection", "2PC", "SDP 빌더", "ROOM_JOIN", "PUBLISH_TRACKS",
  "bench", "e2e-ptt", "sfu-labs", "oxlens-home",
  "UserHub", "ChannelHub", "FloorController", "ingress", "egress"
  등의 키워드가 나오면 즉시 이 스킬을 참조할 것.
---

# OxLens — 작업 컨텍스트

## 프로젝트 개요

- **목적**: 상용화 목적의 경량 Conference SFU 서버 + 네이티브 PTT SDK
- **설계 규모**: 방당 최대 30명 (RPi 기준), 단일 인스턴스
- **개발자**: kodeholic (GitHub)

---

## 프로젝트 구성

| 항목 | 로컬 경로 | 설명 |
|------|-----------|------|
| 서버 | `D:\X.WORK\GitHub\repository\oxlens-sfu-server` | Rust + Tokio + Axum |
| SDK 코어 | `D:\X.WORK\GitHub\repository\oxlens-sdk-core` | Cargo workspace (4 crates) |
| 랩스 | `D:\X.WORK\GitHub\repository\oxlens-sfu-labs` | Cargo workspace (E2E, 벤치마크) |
| 레퍼런스 | `D:\X.WORK\GitHub\repository\oxlens-home` | SDP 조립 참조 (`common/sdp-builder.js`) |

---

## 서버 소스 구조 (oxlens-sfu-server)

```
src/
├── main.rs
├── lib.rs
├── config.rs           — 전역 상수
├── error.rs            — 에러 타입
├── state.rs            — AppState (전역 상태)
│
├── signaling/
│   ├── mod.rs
│   ├── opcode.rs       — 클라이언트/서버 opcode 상수
│   ├── message.rs      — 패킷 타입 정의
│   └── handler.rs      — WS 시그널링 핸들러
│
├── room/
│   ├── mod.rs
│   ├── room.rs         — Room 상태 관리
│   ├── participant.rs  — Participant 상태
│   ├── floor.rs        — FloorController (PTT 발화권)
│   └── ptt_rewriter.rs — PTT 게이팅 (비발화자 패킷 차단)
│
├── media/
│   ├── mod.rs
│   ├── router.rs       — 미디어 라우팅
│   └── track.rs        — 트랙 관리
│
├── transport/
│   ├── mod.rs
│   ├── ice.rs          — ICE
│   ├── stun.rs         — STUN
│   ├── dtls.rs         — DTLS
│   ├── srtp.rs         — SRTP 암복호화
│   ├── demux.rs        — 패킷 디멀티플렉싱
│   ├── demux_conn.rs   — 디멀티플렉스 커넥션
│   └── udp/
│       ├── mod.rs
│       ├── ingress.rs  — UDP 수신 (RTP 파싱, MBCP 처리)
│       ├── egress.rs   — UDP 송신 (릴레이)
│       ├── rtcp.rs     — RTCP 처리 (PLI 등)
│       └── twcc.rs     — TWCC (대역폭 추정)
│
└── metrics/
    ├── mod.rs
    ├── env.rs
    └── tokio_snapshot.rs
```

---

## SDK 코어 소스 구조 (oxlens-sdk-core)

Cargo workspace — 4 crates:

```
crates/
├── oxlens-core/                — PTT 비즈니스 로직, SDK 오케스트레이터
│   └── src/
│       ├── lib.rs
│       ├── client.rs           — OxLensClient (SDK 진입점)
│       ├── signaling/
│       │   ├── mod.rs
│       │   ├── client.rs       — SignalClient + SignalSender
│       │   ├── message.rs      — 메시지 타입
│       │   └── opcode.rs       — opcode 미러
│       └── sdp/
│           ├── mod.rs
│           ├── builder.rs      — SDP 빌더 (oxlens-home 포팅)
│           ├── types.rs        — SDP 타입 정의
│           └── validate.rs     — SDP 검증
│
├── oxlens-webrtc/              — Safe Rust wrapper over webrtc-sys
│   └── src/
│       ├── lib.rs
│       └── session.rs          — MediaSession (PeerConnection 관리)
│
├── oxlens-webrtc-sys/          — C++ FFI (livekit webrtc-sys 기반)
│   └── src/
│
└── oxlens-bench/               — CLI 벤치마크/E2E 도구
    └── src/
        └── main.rs             — --mode conference|ptt|list
```

---

## SDK 핵심 타입 관계

```
OxLensClient (오케스트레이터)
├── SignalClient      — WS 시그널링 (HELLO→IDENTIFY→HEARTBEAT 자동)
│   └── SignalSender  — clone_sender()로 외부 전송 핸들
├── MediaSession      — PeerConnection 관리
│   ├── pub_pc        — Publish PeerConnection
│   ├── sub_pc        — Subscribe PeerConnection (예정)
│   └── pub_senders   — RtpSender 목록
├── media_mut()       — run() 전 트랙 추가용 접근자
├── signal()          — SignalSender 확보용 접근자
└── on_join_ok()      — ROOM_JOIN 응답 → 2PC 자동 수행
```

---

## 시그널링 프로토콜 (디스코드 스타일 opcode)

패킷 형식: `{ "op": N, "d": { ... }, "pid": N }`

### Client → Server
| op | 이름 | 설명 |
|---|---|---|
| 1 | HEARTBEAT | 생존 확인 |
| 3 | IDENTIFY | 인증 |
| 9 | ROOM_LIST | 방 목록 요청 |
| 10 | ROOM_CREATE | 방 생성 |
| 11 | ROOM_JOIN | 방 입장 (→ server_config 수신) |
| 12 | ROOM_LEAVE | 방 퇴장 |
| 15 | PUBLISH_TRACKS | 트랙 SSRC 등록 |
| 17 | MUTE_UPDATE | 트랙 mute/unmute |
| 20 | MESSAGE | 텍스트 메시지 |
| 30 | TELEMETRY | 클라이언트 telemetry |
| 40 | FLOOR_REQUEST | PTT 발화권 요청 |
| 41 | FLOOR_RELEASE | PTT 발화권 해제 |
| 42 | FLOOR_PING | 발화자 생존 확인 |

### Server → Client (Event)
| op | 이름 | 설명 |
|---|---|---|
| 0 | HELLO | heartbeat_interval 전달 |
| 100 | ROOM_EVENT | 입장/퇴장/설정변경 |
| 101 | TRACKS_UPDATE | 트랙 추가/제거 |
| 102 | TRACK_STATE | mute 상태 브로드캐스트 |
| 103 | MESSAGE_EVENT | 메시지 브로드캐스트 |
| 110 | ADMIN_TELEMETRY | 어드민 telemetry 중계 |
| 141 | FLOOR_TAKEN | 발화권 획득 브로드캐스트 |
| 142 | FLOOR_IDLE | 발화권 해제 브로드캐스트 |
| 143 | FLOOR_REVOKE | 강제 발화권 회수 |

---

## 미디어 흐름

### 2PC (Two PeerConnection) 구조
```
Client                            Server
  │  ROOM_JOIN →                    │
  │  ← server_config (ICE, DTLS)   │
  │                                 │
  │  [Publish PC]                   │
  │  SDP offer (client) ─────→     │
  │  ←───── SDP answer (server)    │
  │  ICE + DTLS → SRTP 확립        │
  │  RTP 송신 ──────────────→      │
  │                                 │
  │  PUBLISH_TRACKS(ssrc) ────→    │
  │  ← TRACKS_UPDATE (to others)   │
```

### 서버 듀얼 Floor 경로
- **WS 시그널링**: FLOOR_REQUEST/RELEASE/PING (op=40/41/42)
- **MBCP over UDP**: RTCP APP(PT=204) → FloorController
- 현재 bench/SDK는 WS 경로만 사용

---

## 랩스 (oxlens-sfu-labs)

```
oxlens-sfu-labs/
├── common/         — 공유 라이브러리 (signaling, stun, media, mbcp)
├── bench/          — 성능 벤치마크
└── e2e-ptt/        — PTT E2E 테스트 (4 시나리오 전체 통과)
```

---

## 빌드 환경

- Windows 11 + WSL2 (Ubuntu 22.04), Git Bash 주 터미널
- `.cargo/config.toml`: `+crt-static` 필수 (LiveKit 프리빌드 /MT 호환)
- LLVM 22.1.0, CMake 4.2.3
- 풀 클린 빌드: 10~15분 / 증분 빌드: 수초

---

## livekit-webrtc 0.2.0 API 요약

```rust
NativeAudioSource::new(AudioSourceOptions) → NativeAudioSource
NativeAudioSource::capture_frame(&AudioFrame)
factory.create_audio_track(label, source) → RtcAudioTrack
pc.add_track(MediaStreamTrack::Audio(track), &["stream0"]) → Result<RtpSender>
```

- 고수준 `Room::connect()` API는 LiveKit 서버 전용
- 커스텀 시그널링에는 저수준 PeerConnectionFactory/PeerConnection만 사용

---

## 코딩 규칙

- 파일 상단 `// author: kodeholic (powered by Claude)` 명시
- 매직 넘버 금지 → `config.rs` 상수 사용
- `unwrap()` 남용 금지 → `LiveResult<T>` 또는 로그 후 `continue`
- 새 기능 추가 시 `CHANGELOG.md` 업데이트
- **"코딩해줘" 명시적 요청 시에만 코드 작성**

---

## 세션 컨텍스트

세션 간 컨텍스트 유지를 위해 `doc/SESSION_CONTEXT_*.md` 파일 관리.
새 세션 시작 시 해당 파일 먼저 읽기.

---

*author: kodeholic (powered by Claude)*
