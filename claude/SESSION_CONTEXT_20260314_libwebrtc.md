# 세션 컨텍스트 — 2026-03-14 (libwebrtc 커스텀 빌드 + 카메라 웜업/Hard Mute)

> **libwebrtc AudioInterceptor C++ 패치 + JNI 바인딩 완료, 커스텀 AAR 빌드 성공 + Conference/PTT E2E 검증 통과. 서버 CAMERA_READY/VIDEO_SUSPENDED/VIDEO_RESUMED opcode 추가, Web/Android 클라이언트 반영 완료.**
> **Step 5: Kotlin FloorFsm ↔ AudioInterceptor 연동 완료 — MediaSession Reflection 4개 래퍼 + OxLensClient 상태별 제어 로직.**
> **Step 5a: 커스텀 AAR 재빌드 (Java API 포함) + C++ BlockingCall data race 수정 + is_debug DCHECK 크래시 2건 해결.**

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

### 1순위: 2인 PTT E2E 테스트 (AudioInterceptor 실동작 검증)
- **필수**: 두 명이 같은 PTT 방에 입장해야 subscribe PC 생성 → interceptor 초기화
- Logcat에서 `audioInterceptor enabled=true` + `silence=true/false` 로그 확인
- NetEQ 붕괴 없이 화자 전환 되는지 청감 확인

### 2순위: 서버 cargo build + CAMERA_READY/VIDEO_SUSPENDED/VIDEO_RESUMED E2E 테스트

### 3순위: PTT 전환 시 RTX 폭증 → NetEQ 붕괴 수정 (서버 egress.rs)

### 4순위: SW instructor-mode 전체 코드 리뷰

---

### Step 5 완료 상세 (AudioInterceptor ↔ FloorFsm 연동)

**설계 핵심**: 서버 fan-out에서 speaker 자신은 제외됨 (`entry.key() == &sender.user_id` skip).
따라서 LISTENING(=타인 발화) 상태에서만 실제 오디오 수신, 나머지는 전부 silence injection ON.

| FloorFsm State | 실 오디오 도착? | Silence |
|---|---|---|
| IDLE | X | ON |
| REQUESTING | X | ON |
| TALKING | X (내 오디오는 나에게 안 옴) | ON |
| LISTENING | **O** (타인 오디오 수신) | **OFF** |

**변경 파일**: MediaSession.kt (4개 래퍼), OxLensClient.kt (4개 헬퍼 + 6개 이벤트 훅)

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


### 현 구현 사항 검토 이력 (2026-03-14 10:17)

```
1번 (Data Race) — 부분적으로 맞고, 부분적으로 틀림
Gemini가 놓친 게 하나 있습니다. Call::DeliverRtpPacket() 코드를 다시 보면:
cppvoid Call::DeliverRtpPacket(...) {
  if (!worker_thread_->IsCurrent()) {
    worker_thread_->PostTask(...);  // 다른 스레드면 worker로 넘김
    return;
  }
  RTC_DCHECK_RUN_ON(worker_thread_);  // 여기서부터는 worker thread 보장
  ...
  // OxLens interceptor는 이 아래에서 호출됨
}
즉 OnAudioRtpPacket()도 worker thread, InjectSilenceFrame()도 worker thread. 핵심 경로 두 개는 같은 스레드에서 실행되므로 data race가 아닙니다.
하지만 진짜 문제가 있는 곳은 따로 있습니다: JNI 호출. StartSilenceInjection(), StopSilenceInjection(), ResetOffset()은 Kotlin에서 JNI를 통해 호출되는데, 이때 JNI 스레드 ≠ worker thread이므로 race 가능성이 있습니다.
해결: SetAudioPlayout 패턴 그대로 — PeerConnection 레벨에서 worker_thread()->BlockingCall()로 감싸면 됩니다. 이미 구현된 코드를 수정하면:
cpp// pc/peer_connection.cc — 수정 필요
void PeerConnection::SetAudioInterceptorSilence(bool inject, uint32_t ssrc) {
  if (!worker_thread()->IsCurrent()) {
    worker_thread()->BlockingCall(
        [this, inject, ssrc] { SetAudioInterceptorSilence(inject, ssrc); });
    return;
  }
  if (!audio_interceptor_) return;
  if (inject) audio_interceptor_->StartSilenceInjection(ssrc);
  else audio_interceptor_->StopSilenceInjection();
}
나머지 3개 메서드도 동일 패턴. 이건 다음 세션에서 한번에 수정합니다.
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
