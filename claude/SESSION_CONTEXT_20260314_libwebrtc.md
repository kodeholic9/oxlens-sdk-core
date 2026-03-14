# 세션 컨텍스트 — 2026-03-14 (libwebrtc 커스텀 빌드 + 카메라 웜업/Hard Mute)

> **libwebrtc AudioInterceptor C++ 패치 + JNI 바인딩 완료, 커스텀 AAR 빌드 성공 + Conference/PTT E2E 검증 통과. 서버 CAMERA_READY/VIDEO_SUSPENDED/VIDEO_RESUMED opcode 추가, Web/Android 클라이언트 반영 완료.**

---

## 이번 세션 완료 작업

### 1. 서버 opcode 3개 추가 ✔️
- `CAMERA_READY` (op=18): Client → Server — 카메라 첫 프레임 → 서버가 PLI 2발(즉시+150ms) + VIDEO_RESUMED 브로드캐스트
- `VIDEO_SUSPENDED` (op=104): Server → Clients — video hard mute 시 UI avatar 전환
- `VIDEO_RESUMED` (op=105): Server → Clients — 카메라 재개 시 UI 복원
- `handle_mute_update()`에 VIDEO_SUSPENDED 브로드캐스트 추가
- `handle_camera_ready()` 핸들러 신규 구현

### 2. Kotlin SDK Hard Mute 실구현 ✔️
- `doHardMute("video")` — `mediaSession.stopCamera()` (카메라 하드웨어 해제)
- `doHardUnmute("video")` — `mediaSession.restartCamera()` (replaceTrack + CameraEventsHandler)
- `stopCamera()` — `videoSender.setTrack(null)` 추가 (sender 유지, 트랙만 분리)
- `restartCamera()` — 신규 메서드: Camera2 재시작 + `onFirstFrameAvailable()` 콜백 → CAMERA_READY 시그널
- `MediaSessionListener.onCameraFirstFrame()` 콜백 추가
- `OxLensClient.onCameraFirstFrame()` → `CAMERA_READY` 시그널 전송
- `SignalClient/SignalListener` — VIDEO_SUSPENDED/VIDEO_RESUMED 디스패치 + 콜백
- `OxLensEventListener` — `onVideoSuspended(userId)`, `onVideoResumed(userId)`

### 3. Web (oxlens-home) 반영 ✔️
- `constants.js` — CAMERA_READY(18), VIDEO_SUSPENDED(104), VIDEO_RESUMED(105)
- `signaling.js` — VIDEO_SUSPENDED/RESUMED 디스패치, CAMERA_READY ack
- `client.js` — `_sendCameraReady()`, `_doHardUnmute`/`_pttHardUnmute` video일 때 CAMERA_READY 전송
- `demo/client/app.js` — `video:suspended` → avatar 전환, `video:resumed` → 비디오 복원

### 4. libwebrtc 커스텀 빌드 (Phase 0~4) ✔️
- **Phase 0**: WSL 소스 빌드 환경 확인 — 이미 구축됨
- **Phase 1~3 (AudioInterceptor)**: C++ 패치 3커밋, 529줄
  - `oxlens/audio_interceptor.h` — 인터페이스 (Attach, SetEnabled, OnAudioRtpPacket, Silence, Offset, OpusPt)
  - `oxlens/audio_interceptor_impl.h/.cc` — 구현체 (RepeatingTaskHandle 20ms silence injection + seq/ts offset 보정)
  - `call/call.h` — `SetAudioInterceptor()` 가상 메서드
  - `call/call.cc` — `audio_interceptor_` 멤버 + `DeliverRtpPacket()` AUDIO 인터셉트
  - `call/BUILD.gn` — oxlens 소스 등록
  - `api/peer_connection_interface.h` — 4개 가상 메서드 (빈 기본 구현 `{}`)
  - `pc/peer_connection.h/.cc` — override 구현 (lazy init + Call 연동)
  - `sdk/android/api/.../PeerConnection.java` — Java API 4개
  - `sdk/android/src/jni/pc/peer_connection.cc` — JNI 바인딩 4개
- **Phase 4**: AAR 빌드 성공 → demo-app 교체 → Conference + PTT E2E 통과

### 5. 기타
- `DemoViewModel.kt` — 서버 프리셋에 개발PC(192.168.0.18) 추가, 기본 index 수정 (oxlens.com=3)
- git 브랜치: `oxlens-custom` (main 대비 패치 관리)
- `oxlens-patch.diff` + `oxlens-commits.txt` → `doc/`에 저장

---

## 변경된 파일

### 서버 (oxlens-sfu-server) — 3파일
```
src/signaling/opcode.rs    ← CAMERA_READY(18), VIDEO_SUSPENDED(104), VIDEO_RESUMED(105)
src/signaling/message.rs   ← CameraReadyRequest 구조체
src/signaling/handler.rs   ← handle_camera_ready(), handle_mute_update에 VIDEO_SUSPENDED
```

### Kotlin SDK (oxlens-sdk-core) — 7파일
```
platform/android/oxlens-sdk/src/main/java/com/oxlens/sdk/
├── signaling/Opcode.kt           ← 3개 상수 + name()
├── signaling/Message.kt          ← buildCameraReady()
├── signaling/SignalClient.kt     ← VIDEO_SUSPENDED/RESUMED 디스패치 + 콜백
├── OxLensEventListener.kt        ← onVideoSuspended(), onVideoResumed()
├── media/MediaSession.kt         ← stopCamera setTrack(null), restartCamera(), onCameraFirstFrame()
└── OxLensClient.kt               ← doHardMute/Unmute 실구현, CAMERA_READY, VIDEO events

platform/android/demo-app/.../DemoViewModel.kt  ← 서버 프리셋 추가
platform/android/oxlens-sdk/libs/libwebrtc.aar   ← 커스텀 AAR 교체
```

### Web (oxlens-home) — 4파일
```
core/constants.js      ← 3개 opcode
core/signaling.js      ← VIDEO_SUSPENDED/RESUMED 디스패치, CAMERA_READY ack
core/client.js         ← _sendCameraReady(), hard unmute video → CAMERA_READY
demo/client/app.js     ← video:suspended/resumed UI 처리
```

### libwebrtc (WSL ~/webrtc-android/src, oxlens-custom 브랜치) — 9파일
```
oxlens/audio_interceptor.h          ← 인터페이스
oxlens/audio_interceptor_impl.h     ← 구현 헤더
oxlens/audio_interceptor_impl.cc    ← 구현 (silence injection + offset)
call/call.h                         ← SetAudioInterceptor 가상 메서드
call/call.cc                        ← 멤버 + Attach + DeliverRtpPacket 인터셉트
call/BUILD.gn                       ← oxlens 소스 등록
api/peer_connection_interface.h     ← 4개 가상 메서드
pc/peer_connection.h                ← override + 멤버
pc/peer_connection.cc               ← 4개 메서드 구현
sdk/android/api/.../PeerConnection.java  ← Java API 4개
sdk/android/src/jni/pc/peer_connection.cc ← JNI 바인딩 4개
```

---

## 현재 상태

- 커스텀 AAR (AudioInterceptor JNI 포함) → demo-app Conference + PTT E2E 통과 ✅
- 서버 CAMERA_READY/VIDEO_SUSPENDED/VIDEO_RESUMED 코드 작성 완료 (cargo build 미확인)
- Web 코드 작성 완료 (브라우저 테스트 미확인)

---

## 다음 세션 작업

### 1순위: Step 5 — Kotlin FloorFsm 연동
- `OxLensClient`에서 FloorFsm 상태 전이 시 interceptor 제어:
  - PTT join 시: `enableAudioInterceptor(true)` + `setAudioInterceptorOpusPt(111)` + `startSilenceInjection(ssrc)`
  - floor granted: `stopSilenceInjection()` → 실제 오디오 전환 (offset 자동 계산)
  - floor released/idle: `startSilenceInjection(ssrc)` → silence 재주입
  - room leave: `resetAudioInterceptorOffset()`
- Subscribe PC의 audio SSRC를 interceptor에 전달해야 함 (remoteTracks에서 audio SSRC 추출)

### 2순위: 서버 cargo build + E2E 테스트
- CAMERA_READY/VIDEO_SUSPENDED/VIDEO_RESUMED 서버 빌드 확인
- RPi에서 Android + Web 간 hard mute/unmute 시나리오 테스트

### 3순위: PTT 전환 시 RTX 폭증 → NetEQ 붕괴 수정 (서버 egress.rs)

---

## 기술 메모

### libwebrtc 패킷 수신 경로 (확정)
```
Network → SrtpTransport::OnRtpPacketReceived() → [SRTP 복호화]
→ WebRtcVoiceReceiveChannel (media/engine/webrtc_voice_engine.cc:2658)
→ call_->Receiver()->DeliverRtpPacket(AUDIO, packet, ...)
→ Call::DeliverRtpPacket() (call/call.cc:1357)
  ┌─ [OxLens interceptor: offset 보정] ←── 여기서 인터셉트
  └→ audio_receiver_controller_.OnRtpPacket(packet)
     → AudioReceiveStream → NetEQ::InsertPacket()
```

### 설계서 가정 vs 현실
| 설계서 (RX_TX_PIPELINE_DESIGN) | 현실 |
|---|---|
| Dependencies에 Proxy 주입 | Dependencies에 PacketReceiver 없음 — Call 직접 수정 |
| DeliverPacket() | DeliverRtpPacket() — 메서드명만 다름 |
| PeerConnectionFactory Dependencies | PeerConnection + Call 레벨에서 처리 |

### Opus DTX Silence Frame
- TOC byte: `0xf8` (48kHz, 20ms, code 0)
- Payload: `{0xf8, 0xff, 0xfe}` (3바이트)
- Timestamp increment: 960 (48kHz × 20ms)
- Injection interval: 20ms (RepeatingTaskHandle, kHigh precision)

### Java API (PeerConnection에 추가됨)
```java
pc.enableAudioInterceptor(boolean enable)
pc.setAudioInterceptorSilence(boolean inject, long ssrc)
pc.resetAudioInterceptorOffset()
pc.setAudioInterceptorOpusPt(int pt)
```

---

## 주의사항 (다음 세션 Claude에게)

1. **libwebrtc 소스는 WSL 경로** — Filesystem MCP 접근 불가, 부장님이 터미널에서 직접 실행
2. **git 브랜치 `oxlens-custom`** — main 대비 패치 관리, `oxlens-patch.diff`로 재적용 가능
3. **AudioInterceptor는 Subscribe PC의 audio SSRC 필요** — Publish가 아닌 Subscribe 쪽
4. **silence injection은 worker thread에서** — RepeatingTaskHandle로 구동, audio_receiver_controller_ 직접 호출
5. **서버 CAMERA_READY 핸들러에서 PLI 2발** — 설계서의 "즉시 + 150ms 보험" 패턴 구현됨
6. **`RTC_LOG`에서 `std::hex`/`std::dec` 사용 불가** — WebRTC LogStreamer는 std::ostream 아님
7. **`PeerConnectionInterface`에 순수 가상 추가 불가** — PeerConnectionProxy 미구현 에러 → 빈 기본 구현 `{}` 사용
8. **`pc/peer_connection.h`에서 impl 직접 include 불가** — forward declare + .cc에서 impl include

---

*author: kodeholic (powered by Claude)*
