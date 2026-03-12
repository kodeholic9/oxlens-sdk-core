# 세션 컨텍스트 — 2026-03-12 v2

> OxLensClient Arc 핸들 패턴 리팩터링 완료.
> LiveKit Rust SDK의 `(Room, event_rx)` 패턴 채택.
> bench를 OxLensClient 기반으로 전환, Conference + PTT E2E 통과.
> **subscribe PC on_track 검증 완료** — NativeAudioStream으로 수신 프레임 확인.

---

## 이번 세션 완료 작업

### 1. `client.rs` (oxlens-core) — Arc 핸들 패턴 전면 리팩터링

**이전 구조**:
```
OxLensClient {
    signal: SignalClient,        // 직접 소유
    media: MediaSession,         // 직접 소유
    event_tx: mpsc::Sender,
}
// run(&mut self) → 이벤트 루프가 &mut self 점유
// run() 후 외부에서 명령 전송 불가 (borrow 충돌)
```

**새 구조** (LiveKit 패턴):
```
OxLensClient {
    inner: Arc<ClientInner>      // Clone 가능!
}

ClientInner {
    sender: SignalSender,            // Clone, &self 전송
    media: Mutex<MediaSession>,      // 트랙 추가/제거
    room: RwLock<RoomState>,         // room_id, mode, tracks
    event_tx: mpsc::Sender,          // 이벤트 발행
}
```

**핵심 변경점**:
- `new()` → `connect()` async fn. 내부에서 tokio::spawn으로 이벤트 루프 자동 시작
- `run(&mut self)` 제거 → `event_loop()` free fn으로 분리 (내부 전용)
- `OxLensClient`는 `#[derive(Clone)]` — 어디서든 `client.clone()`으로 전달
- 모든 공개 API는 `&self` — `join_room()`, `create_room()`, `request_floor()` 등
- `MediaSession`은 `tokio::sync::Mutex`로 보호
- `RoomState`는 `RwLock`으로 보호 (읽기 빈도 >> 쓰기 빈도)
- `add_audio_source()`: async fn (blocking_lock → lock().await, tokio 런타임 충돌 해결)

**추가된 공개 API**:
- `connect(config) → (OxLensClient, Receiver<ClientEvent>)` — SDK 진입점
- `add_audio_source(opts) → NativeAudioSource`
- `create_room(name, capacity, mode)`
- `list_rooms()`
- `join_room(room_id)` — 2PC 자동
- `leave_room()`
- `request_floor(room_id)` / `release_floor(room_id)` / `floor_ping(room_id)`
- `room_id()`, `room_mode()` — 상태 조회
- `signal_sender()` — 고급 사용자용 raw 송신 핸들

**추가된 ClientEvent 변형**:
- `RoomCreated { room_id, name, mode }`
- `RoomList { rooms }`
- `FloorGranted { room_id, speaker }` / `FloorDenied { reason }`
- `FloorReleased`

### 2. `bench/main.rs` — OxLensClient 기반 전면 재작성

- 코드량 약 50% 감소
- `handle_room_joined()`, `extract_ssrc_from_sdp()` 삭제 — OxLensClient가 자동 처리
- FLOOR_PING 태스크에서 `client.clone()` 사용

### 3. 버그 수정
- `blocking_lock()` → `lock().await` (tokio 런타임 내 blocking 호출 패닉)
- bench match에 `RoomLeft` arm 누락 추가

---

## E2E 검증 결과

### Conference 모드 (`cargo run -p oxlens-bench`) ✅
```
✓ OxLensClient connected
✓ audio track added (pre_added=true)
✓ Identified → ROOM_CREATE → ROOM_JOIN → 2PC
✓ PUBLISH_TRACKS ack
✓ ICE Connected → Completed
✓ Ctrl+C → clean shutdown
```

### PTT 모드 (`cargo run -p oxlens-bench -- --mode ptt`) ✅
```
✓ ROOM_CREATE (ptt) → ROOM_JOIN → 2PC
✓ FLOOR_REQUEST → granted (speaker=bench-xxxxx)
✓ FLOOR_PING 2초 주기 (client.clone() 패턴)
✓ FLOOR_REVOKE → PING task 자동 중단
✓ Ctrl+C → FLOOR_RELEASE → clean shutdown
```

### List 모드 — 미검증 (다음 세션)

---

## SDK 설계 참고 분석

| SDK | 핸들 타입 | 이벤트 수신 | 명령 전송 | Clone |
|-----|----------|------------|----------|-------|
| LiveKit | `Room` (Arc 내장) | mpsc rx | `room.method(&self)` | 불필요 (내부 Arc) |
| Serenity | `Client.http` (Arc) | trait 콜백 | `ctx.http.method()` | Arc::clone |
| Twilight | `Arc<HttpClient>` | shard.next_event() | `http.method()` | Arc::clone |
| **OxLens** | `OxLensClient` (Arc 내장) | mpsc rx | `client.method(&self)` | **Clone** |

---

## 알려진 이슈 (사소)

- `FloorGranted`의 `room_id`가 빈 문자열 — 서버가 FLOOR_REQUEST 응답에 room_id 미포함
  → 서버 수정 또는 클라이언트에서 로컬 room_id 사용으로 해결 가능

---

## 설계 결정 사항

### MBCP over UDP → WS(TCP) 확정

- PTT Floor Control은 **WS 시그널링 경로를 primary**로 확정
- UDP MBCP 경로는 보류 (아이디어 나올 때까지)
- 이유:
  - SDK에서 libwebrtc PC의 SRTCP 채널로 RTCP APP 주입 API가 없음
  - 별도 raw UDP 소켓 사용 시 NAT 홀펀칭 유지 부담 (STUN ping 이중화)
  - 같은 포트 평문 MBCP 수신 시 demux 복잡도 + 서버→클라이언트 응답 경로 문제
- labs의 `e2e-ptt`는 MBCP over SRTCP로 서버 내부 검증용으로 유지
- 서버는 듀얼 경로(WS + MBCP) 이미 지원 중 — 웹 클라이언트는 WS, 네이티브는 당분간 WS

---

## 이번 세션 추가 완료 작업

### subscribe PC on_track 검증 ✔️

**변경 파일:**
- `oxlens-webrtc/src/lib.rs` — `NativeAudioStream`, `RtcAudioTrack`, `MediaStreamTrack`, `TrackEvent` re-export 추가
- `oxlens-webrtc/src/session.rs` — `track_tx` 필드 + `set_track_sender()` + on_track 콜백 등록
- `oxlens-core/src/client.rs` — `track_rx` 추가, `track_receive_loop()` spawn, `ClientEvent::AudioFrameReceived`
- `oxlens-bench/src/main.rs` — `AudioFrameReceived` arm 추가

**검증 결과 (bench 2인 접속):**
- on_track fire 타이밍: `setRemoteDescription` 중에 fire (예상보다 이른 시점)
- 수신 프레임 포맷: **48kHz, mono, 480 samples/frame = 10ms** (송신은 20ms/960으로 주입)
- Video 트랙도 수신됨: `ptt-video` (현재 무시 처리)
- libwebrtc ADM: 미확인 (NativeAudioStream으로 가로채서 확인 못함 — 스피커 자동 출력 여부는 Android에서 검증 필요)
- 프레임 수신 안정적: 5초에 500프레임, 정확히 10ms 간격

**설계 패턴 (A안 채택):**
- on_track 콜백에서 `mpsc::Sender`로 트랙만 넘기고, 별도 `track_receive_loop`에서 `NativeAudioStream` 생성
- LiveKit SDK 패턴과 동일한 구조

---

## 알려진 이슈

### 1. `track_receive_loop` 중복 spawn 방지 필요
- `handle_room_joined()` 호출 시마다 `track_receive_loop` spawn
- re-join 시나리오에서 루프 중복 실행 가능성 (두 번째 spawn이 Mutex lock 대기)
- 해결: connect() 시점에 1회만 spawn, 또는 기존 handle abort 후 재 spawn

### 2. 송수신 프레임 사이즈 불일치
- bench 송신: 20ms / 960 samples
- 수신: 10ms / 480 samples (libwebrtc 내부 리샘플링)
- 동작엔 문제없지만 송신도 10ms/480으로 맞추면 불필요한 리샘플링 제거 가능

---

## 다음 세션 작업 계획

### 1순위: 위 알려진 이슈 2건 수정 (5분컯)

### 2순위: List 모드 E2E 검증
- 방 매칭 로직 개선 (동일 이름 여러 개 시 최신 또는 특정 방 선택)

### 3순위: 소스 코드 리뷰 세션 (SW 강사 모드)

---

## 변경 파일 목록

```
oxlens-sdk-core/
├── crates/
│   ├── oxlens-core/
│   │   └── src/
│   │       └── client.rs          ← ★ Arc 핸들 + on_track 수신 루프
│   ├── oxlens-webrtc/
│   │   └── src/
│   │       ├── lib.rs             ← NativeAudioStream 등 re-export 추가
│   │       └── session.rs         ← on_track 콜백 + track_tx
│   └── oxlens-bench/
│       └── src/
│           └── main.rs            ← AudioFrameReceived arm
└── doc/
    └── SESSION_CONTEXT_20260312.md  ← 이 파일 (v2)
```

---

## 빌드 환경 메모 (전 세션과 동일)

- `.cargo/config.toml`: `+crt-static` 필수 (LiveKit 프리빌드 /MT 호환)
- 풀 클린 빌드: ~1분 / 증분 빌드: 수초

---

*author: kodeholic (powered by Claude)*
