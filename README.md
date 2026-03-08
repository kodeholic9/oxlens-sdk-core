# oxlens-sdk-core

네이티브 클라이언트 SDK — libwebrtc(C++) 미디어 엔진 + Rust PTT 비즈니스 로직

## 개요

`oxlens-sdk-core`는 [oxlens-sfu-server](../oxlens-sfu-server)와 통신하는 네이티브 클라이언트 SDK다.
브라우저 WebRTC가 제공하지 못하는 **저지연 PTT 화자 전환**(목표 150~250ms)을 달성하기 위해,
libwebrtc 내부 API를 직접 제어하는 네이티브 접근 방식을 택했다.

### 왜 네이티브인가?

브라우저 WebRTC PTT의 화자 전환 지연은 800ms~1.5초.
병목은 Chrome NetEQ jitter buffer의 보수적 동작(300~600ms)이며, 브라우저 표준 API로는 제어 불가능하다.
libwebrtc 네이티브 API를 통해 NetEQ 파라미터를 직접 튜닝하고, 화자 전환 시 버퍼를 flush한다.

### 왜 Rust + libwebrtc인가?

| 선택지 | 판정 | 사유 |
|--------|------|------|
| Rust webrtc-rs 순수 구현 | **탈락** | NetEQ 없음, AEC 없음, HW 코덱 가속 없음 → 음성/영상 품질 미담보 |
| libwebrtc (C++) 직접 사용 | **채택** | 구글 20년 노하우 (NetEQ, AEC, HW 가속, GCC BWE) 전부 포함 |
| Rust PTT 비즈니스 로직 | **채택** | Floor FSM, 미디어 게이팅, 시그널링을 안전하게 구현 |

---

## 아키텍처

```
┌─────────────────────────────────────────────┐
│            oxlens-sdk-core                  │
│                                             │
│  ┌───────────────┐  ┌───────────────────┐   │
│  │  libwebrtc    │  │   Rust Core       │   │
│  │  (C++ 설정)    │  │                   │   │
│  │               │  │  - Floor FSM      │   │
│  │  - NetEQ 튜닝  │  │  - Floor Timer    │   │
│  │  - JB flush   │  │  - PING 관리       │   │
│  │  - AEC off    │  │  - 미디어 게이팅     │   │
│  │  - ICE 주기    │  │  - 상태 이벤트      │   │
│  │  - 디바이스     │  │  - 시그널링 프로토콜  │   │
│  │    프리워밍     │  │  (opcode JSON/WS) │   │
│  └──────┬────────┘  └────────┬──────────┘   │
│         │    C FFI (cxx)     │              │
│         └────────┬───────────┘              │
│                  │                          │
│  ┌───────────────┴──────────────────────┐   │
│  │  Platform Binding                    │   │
│  │  - Android: JNI (.aar)              │   │
│  │  - iOS: cbindgen (.xcframework)     │   │
│  │  - PC: cdylib (.dll/.so/.dylib)     │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### 레이어별 역할

| 레이어 | 언어 | 역할 |
|--------|------|------|
| **libwebrtc** | C++ | 미디어 엔진 (인코딩/디코딩, NetEQ, AEC, ICE, DTLS, SRTP, HW 가속) |
| **Rust Core** | Rust | PTT 비즈니스 로직 (Floor FSM, 시그널링, 미디어 게이팅, 텔레메트리) |
| **C FFI** | cxx | libwebrtc ↔ Rust 브릿지 (LiveKit `webrtc-sys` 기반) |
| **Platform Binding** | JNI / cbindgen / cdylib | 네이티브 앱에서 호출하는 플랫폼별 인터페이스 |

---

## 기술 스택

### 핵심 의존성

| 크레이트 | 역할 | 라이선스 |
|----------|------|----------|
| [livekit/rust-sdks](https://github.com/livekit/rust-sdks) `webrtc-sys` | libwebrtc C++ → Rust FFI 바인딩 (cxx 기반) | Apache 2.0 |
| [livekit/rust-sdks](https://github.com/livekit/rust-sdks) `livekit-webrtc` | Safe Rust 래퍼 | Apache 2.0 |
| `webrtc-sys-build` | **프리빌드 libwebrtc 바이너리 자동 다운로드 + 링크** | Apache 2.0 |
| `tokio` | 비동기 런타임 | MIT |
| `serde` / `serde_json` | 시그널링 프로토콜 JSON 직렬화 | MIT / Apache 2.0 |

### LiveKit 바인딩 활용 전략

**libwebrtc를 직접 빌드하지 않는다.**

LiveKit `webrtc-sys-build`가 플랫폼별 프리빌드 바이너리를 자동으로 다운로드하고 링크한다.
`cargo build` 한 번이면 끝. 이것이 LiveKit 바인딩을 채택한 최대 이유.

지원 플랫폼: Windows / macOS / Linux / iOS / Android

> **라이선스 의무:** Apache 2.0 — 상업적 사용 자유. LICENSE/NOTICE 파일 포함 + 변경 사항 명시 + "LiveKit" 상표 미사용.

---

## SFU 서버와의 관계

```
oxlens-sdk-core (이 프로젝트)     ←→     oxlens-sfu-server
──────────────────────────────           ─────────────────────
네이티브 클라이언트 SDK                   Rust SFU 서버
libwebrtc + Rust PTT                    Tokio + Axum

시그널링: WebSocket JSON (opcode 기반, 디스코드 스타일)
미디어:   UDP (ICE-Lite → DTLS → SRTP)
```

### 시그널링 프로토콜 (공유)

서버와 동일한 opcode 체계를 사용한다:

| op | Name | 방향 |
|----|------|------|
| 1 | HEARTBEAT | C→S |
| 3 | IDENTIFY | C→S |
| 11 | ROOM_JOIN | C→S |
| 15 | PUBLISH_TRACKS | C→S |
| 40 | FLOOR_REQUEST | C→S (PTT) |
| 41 | FLOOR_RELEASE | C→S (PTT) |
| 42 | FLOOR_PING | C→S (PTT) |
| 0 | HELLO | S→C |
| 100 | ROOM_EVENT | S→C |
| 101 | TRACKS_UPDATE | S→C |
| 141 | FLOOR_TAKEN | S→C (PTT) |
| 142 | FLOOR_IDLE | S→C (PTT) |
| 143 | FLOOR_REVOKE | S→C (PTT) |

패킷 포맷: `{ "op": N, "pid": u64, "d": { ... } }`

---

## PTT 최적화 전략

### 현재 (브라우저) vs 목표 (네이티브)

| 구간 | 브라우저 지연 | 네이티브 목표 | 해결 방향 |
|------|-------------|-------------|----------|
| Floor 시그널링 | 50~200ms | 50ms | 동일 (WS) |
| 미디어 트랙 활성화 | 0~500ms | 0ms | soft_off 유지 (디바이스 프리워밍) |
| 인코더 첫 프레임 | 20~50ms | 20ms | HW 인코더 활용 |
| **Jitter Buffer (NetEQ)** | **300~600ms** | **50~100ms** | **NetEQ 직접 튜닝** |
| 네트워크 전파 | 5~180ms | 제어 불가 | — |

### libwebrtc 튜닝 파라미터 (Level 1: 설정만, 소스 패치 없음)

| API | PTT 설정 |
|-----|---------|
| `NetEq::Config.enable_fast_accelerate` | `true` — 공격적 버퍼 축소 |
| `NetEq::Config.max_packets_in_buffer` | 200 → **30~50** |
| `NetEq::Config.max_delay_ms` | 2000 → **300~500** |
| `NetEq::SetMaximumDelay()` | 화자 전환 시 **100ms** 제한 |
| `NetEq::SetMinimumDelay()` | **0ms** 유지 |
| `AudioProcessing.echo_canceller.enabled` | **false** (PTT = half-duplex) |

---

## 로드맵

### Phase 1: 벤치 통합 (3~4주)

`oxlens-sdk-core` 프로토타입을 기존 sfu-bench에서 실행.
libwebrtc 실제 미디어 + PTT Floor FSM + NetEQ 튜닝 검증.

| 단계 | 작업 | 공수 |
|------|------|------|
| 0 | 환경 세팅, 샘플 빌드 확인 | 2~3일 |
| 1 | 시그널링 교체 (LiveKit → oxlens 프로토콜) | 1주 |
| 2 | PTT Floor FSM Rust 포팅 | 3~5일 |
| 3 | libwebrtc 튜닝 API 바인딩 추가 (`webrtc-sys` fork) | 3~5일 |
| 4 | sfu-bench 통합 (fake RTP → 실제 미디어) | 1주 |
| 5 | 실측 + 튜닝 (NetEQ 파라미터 벤치마크) | 3~5일 |

### Phase 2: 네이티브 앱 프로토타입

PC 데스크톱 앱에서 PTT 화자 전환 지연 실측.
OS 오디오 세션 / 카메라 프리워밍 / ICE 전환 검증.

### Phase 3: 모바일

Android `.aar` / iOS `.xcframework` 빌드.
LTE↔WiFi 전환, 백그라운드 동작, 푸시 알림 연동.

### Phase 4: 소스 패치 (필요 시)

NetEQ 버퍼 flush, 비디오 JB fast drain.
libwebrtc 직접 빌드 환경 구축 (프리빌드 바이너리 탈출).

---

## 프로젝트 구조 (예정)

```
oxlens-sdk-core/
├── Cargo.toml              메인 워크스페이스
├── README.md               이 파일
├── LICENSE                  Apache 2.0
│
├── crates/
│   ├── oxlens-core/        PTT 비즈니스 로직 (Floor FSM, 시그널링, 이벤트)
│   ├── oxlens-webrtc/      libwebrtc Safe Rust 래퍼 (livekit-webrtc fork)
│   └── oxlens-webrtc-sys/  libwebrtc C++ FFI (webrtc-sys fork, NetEQ 바인딩 추가)
│
├── examples/
│   └── bench/              sfu-bench 통합 벤치마크
│
└── platform/
    ├── android/            JNI 바인딩 (.aar)
    ├── ios/                cbindgen (.xcframework)
    └── desktop/            cdylib (.dll/.so/.dylib)
```

---

## 관련 프로젝트

| 프로젝트 | 경로 | 역할 |
|----------|------|------|
| [oxlens-sfu-server](../oxlens-sfu-server) | `D:\X.WORK\GitHub\repository\oxlens-sfu-server` | Rust SFU 서버 (Tokio + Axum) |
| **oxlens-sdk-core** (이 프로젝트) | `D:\X.WORK\GitHub\repository\oxlens-sdk-core` | 네이티브 클라이언트 SDK |
| [oxlens-home](../oxlens-home) | `D:\X.WORK\GitHub\repository\oxlens-home` | 웹 클라이언트 + SDP 조립 참조 |

---

## 빌드 (예정)

```bash
# 프리빌드 libwebrtc 바이너리 자동 다운로드 + 링크
cargo build

# 릴리스 빌드
cargo build --release

# 벤치마크 실행
cargo run --example bench
```

> **요구사항:** Rust 1.75+, CMake, LLVM/Clang (webrtc-sys 빌드 시)

---

## 라이선스

Apache 2.0

### 서드파티 라이선스

- **libwebrtc** (Google): BSD 3-Clause
- **LiveKit rust-sdks** (LiveKit Inc.): Apache 2.0
  - 의무: LICENSE/NOTICE 파일 포함 + 변경 사항 명시 + "LiveKit" 상표 미사용

---

## 코딩 규칙

- 파일 상단 `// author: kodeholic (powered by Claude)` 명시
- 매직 넘버 금지 → 상수 모듈 사용
- `unwrap()` 남용 금지 → `Result` 전파 또는 로그 후 `continue`
- 새 기능 추가 시 CHANGELOG.md 업데이트
- 코딩은 "코딩해줘" 명시적 요청 시에만 작성

---

*author: kodeholic (powered by Claude)*
