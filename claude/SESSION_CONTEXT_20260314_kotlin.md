# 세션 컨텍스트 — 2026-03-14 (Kotlin 전환 결정)

> **핵심 결정: libwebrtc Java API + Kotlin 전환**
> Rust FFI (oxlens-webrtc / oxlens-webrtc-sys) → Kotlin + org.webrtc.* 직접 사용

---

## 이번 세션 완료 작업

### 1. LSE atomics / getauxval 문제 해결 시도

**문제**: Rust 1.93에서 PR #144938로 aarch64-linux-android에 outline-atomics 기본 활성화.
compiler_builtins가 getauxval stub을 .so에 포함 → libwebrtc C++ 글로벌 생성자
(`_GLOBAL__sub_I_webrtc.cpp`)가 이 broken stub을 호출 → SIGSEGV.

**시도한 것들**:
- A안: `-Ctarget-feature=-outline-atomics` → ❌ pre-compiled std에 이미 심볼 포함
- B안: `__getauxval()` C 함수 직접 제공 → ❌ 심볼 resolve 순서 문제로 무효
- C안: Rust 1.88 다운그레이드 → 빌드 중 (의존성 MSRV 1.88 충족)

**근본 원인**: Rust 1.93이 Android에 outline-atomics를 처음 활성화한 버전.
LiveKit 포함 아무도 이 조합(Rust 1.93 + libwebrtc static link + Android cdylib)을
production에서 안 쓰고 있음. LiveKit Android SDK는 순수 Kotlin + AAR.

### 2. Kotlin 전환 결정 ✔️

**사유**:
- Rust FFI 경로의 구조적 문제 (getauxval, 20MB .so, 10분 빌드, 디버깅 난이도)
- LiveKit도 Android에서는 Kotlin + Java API 사용
- 멀티플랫폼 코어는 실제 필요 시점에 재검토 (iOS=Phase3, Desktop=Tauri WebView)
- RX_TX_PIPELINE_DESIGN.md 검토 결과 8개 항목 중 6개 그대로 적용 가능
  - Tx FSM (Stage 1~2): Java API의 RtpSender.encoding.active, replaceTrack() 동일
  - 카메라 웜업: Kotlin이 오히려 유리 (onFirstFrameAvailable 직접 접근)
  - Rx offset 보정: 서버(ptt_rewriter.rs)에서 이미 처리 → 클라이언트 측 불필요
  - ICE Ping 조정: RTCConfiguration.iceCheckIntervalRange로 제한적 가능
- 배터리 전략의 핵심은 FSM 설계 자체이지 Rust/Kotlin 선택이 아님

---

## 전환 계획

### 유지하는 것
- `oxlens-sfu-server` (Rust) — 변경 없음
- `oxlens-sfu-labs` — 변경 없음
- `oxlens-home` — SDP 조립 참조 (JavaScript → Kotlin 포팅 시 참조)
- `RX_TX_PIPELINE_DESIGN.md` — 설계 그대로 유지
- 시그널링 프로토콜 (opcode 체계) — 그대로 유지

### Android 새 구조
```
platform/android/
├── oxlens-sdk/              — Kotlin SDK 라이브러리
│   ├── signaling/           — WS 시그널링 (OkHttp WebSocket)
│   │   ├── SignalClient.kt
│   │   ├── Opcode.kt
│   │   └── Message.kt
│   ├── media/               — org.webrtc.* 기반 미디어
│   │   ├── MediaSession.kt  — 2PC PeerConnection 관리
│   │   └── SdpBuilder.kt    — fake SDP 조립 (oxlens-home 포팅)
│   ├── ptt/                 — PTT Floor Control
│   │   ├── FloorFsm.kt
│   │   └── MbcpHandler.kt   — MBCP over RTCP APP (향후)
│   └── OxLensClient.kt      — 오케스트레이터
├── demo-app/                — 데모 앱
└── libwebrtc.aar            — 이미 보유
```

### Rust에서 가져올 것 (포팅)
- SDP 빌더: `oxlens-core/src/sdp/builder.rs` → `SdpBuilder.kt`
- 시그널링 메시지: `oxlens-core/src/signaling/message.rs` → `Message.kt`
- Opcode: `oxlens-core/src/signaling/opcode.rs` → `Opcode.kt`
- Floor FSM 로직: 향후

### Rust crate 처분
- `oxlens-jni` — 폐기 (JNI 브릿지 불필요)
- `oxlens-webrtc` — 폐기 (Java API로 대체)
- `oxlens-webrtc-sys` — 폐기 (C++ FFI 불필요)
- `oxlens-core` — 시그널링/SDP 참조용으로 보존, 향후 bench/labs에서 계속 사용
- `oxlens-bench` — 유지 (서버 E2E 테스트용, Rust 순수 시그널링)

---

## 다음 세션 작업

### 1순위: Kotlin SDK 스캐폴딩
- `oxlens-sdk` 모듈에 signaling/, media/, ptt/ 패키지 구조 생성
- libwebrtc AAR 임포트 확인 (이미 보유)
- OkHttp WebSocket 의존성 추가

### 2순위: 시그널링 Kotlin 포팅
- SignalClient.kt (HELLO→IDENTIFY→HEARTBEAT 자동)
- Opcode.kt / Message.kt

### 3순위: SDP 빌더 포팅 + PeerConnection 연결
- SdpBuilder.kt (oxlens-home의 sdp-builder.js 또는 Rust builder.rs 참조)
- MediaSession.kt (2PC 구조)

### 4순위: 서버 연결 E2E
- 서버 ws://192.168.0.29:1974/ws 연결
- HELLO → IDENTIFY → ROOM_CREATE → ROOM_JOIN → 2PC 미디어 확립

---

## 기술 메모

### getauxval 문제 (기록용)
- Rust 1.93 (2026-03) = outline-atomics Android 활성화 첫 버전
- compiler_builtins의 getauxval이 libwebrtc의 C++ constructor에서 호출되어 SIGSEGV
- lse_init.c의 __getauxval 제공으로도 해결 불가 (심볼 resolve 순서)
- Rust 1.88~1.92 다운그레이드로 회피 가능 (검증 대기)
- 향후 Rust 진영에서 수정될 때까지 Android cdylib + libwebrtc static link 조합은 위험

### 라이선스
- libwebrtc: BSD 3-Clause — 상용 사용 자유, 소스 공개 의무 없음
- org.webrtc.* Java 바인딩도 동일 라이선스
- 저작권 표시만 포함하면 OK

---

*author: kodeholic (powered by Claude)*
