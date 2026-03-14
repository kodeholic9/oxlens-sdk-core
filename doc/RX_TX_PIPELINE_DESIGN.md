# RX_TX_PIPELINE_DESIGN

> oxlens-sdk-core 클라이언트-서버 하이브리드 제어 아키텍처
>
> author: kodeholic (powered by Claude)  
> created: 2025-03-09  
> updated: 2026-03-14  
> status: Phase 1 구현 완료 (Rx AudioInterceptor + Tx Hard Mute + 카메라 웜업)

---

## 1. 목적

모바일 환경에서 세 가지를 동시에 만족시킨다.

- 배터리 소모 최소화
- 발언권 획득 시 0ms 오디오 지연
- 카메라 콜드 스타트 극복

---

## 2. 수신(Rx) 파이프라인

### 2.1 Audio — Opus Silent Frame 주입으로 NetEQ Hot 유지

대기 상태에서 로컬에서 **Opus DTX silence frame(3~5바이트)**을 주입한다.

PT=13(Comfort Noise)을 사용하지 않는 이유:

- CNG → Normal 전환 시 NetEQ 내부 fade-in 처리로 수십 ms 지연 발생
- Opus silence frame은 NetEQ 입장에서 "조용한 음성"이므로 모드 전환 비용 0
- 배터리 영향도 CNG 대비 동등하거나 오히려 유리 (전환 연산 없음)

### 2.2 Sequence/Timestamp Offset 보정

SFU 서버의 릴레이 로직을 그대로 차용한다.

- 더미 시퀀스가 100~150까지 진행된 상태에서 실제 패킷 seq=1 도착 시
- offset = 150을 잡고, 1 + 150 = 151로 이어붙임
- timestamp도 동일 원리
- 서버에서 검증된 로직이므로 C++ 포팅만 수행

### 2.3 구현 위치 — Call::DeliverRtpPacket() 인터셉트

> ⚠️ 설계 시점의 "Dependencies 주입" 가정은 **실제 소스 확인 결과 불가**였다.
> `PacketReceiver`는 `Dependencies`에 없고 `Call` 클래스가 직접 구현하고 있었음.
> 아래는 실제 구현된 방식.

**패킷 수신 경로 (확정)**:
```
Network → SrtpTransport::OnRtpPacketReceived() → [SRTP 복호화]
→ WebRtcVoiceReceiveChannel (media/engine/webrtc_voice_engine.cc:2658)
→ call_->Receiver()->DeliverRtpPacket(AUDIO, packet, ...)
→ Call::DeliverRtpPacket() (call/call.cc)
  ┌─ [OxLens AudioInterceptor: offset 보정] ←── 인터셉트 지점
  └→ audio_receiver_controller_.OnRtpPacket(packet)
     → AudioReceiveStream → NetEQ::InsertPacket()
```

**구현 방식**:
1. `oxlens/audio_interceptor.h` — 인터페이스 정의
2. `oxlens/audio_interceptor_impl.cc` — 구현체 (silence injection + offset 보정)
3. `call/call.cc`의 `DeliverRtpPacket()`에서 `MediaType::AUDIO`일 때 interceptor 경유
4. `PeerConnection`에서 lazy init → `Call::SetAudioInterceptor()` 연동
5. JNI 바인딩 4개 → Kotlin에서 FloorFsm 상태 전이 시 제어

**Opus DTX Silence Frame**: `{0xf8, 0xff, 0xfe}` (3바이트, 48kHz 20ms)
**Injection**: `RepeatingTaskHandle` 20ms 주기, worker thread에서 `audio_receiver_controller_.OnRtpPacket()` 직접 호출

> **기술적 가능 여부: ✅ 구현 완료 (2026-03-14)**
> libwebrtc 커스텀 빌드 (WSL, oxlens-custom 브랜치). AAR 빌드 + E2E 검증 통과.
> 빌드 절차: `doc/LIBWEBRTC_BUILD.md` 참조.

### 2.4 Video — 패킷 조작 배제

비디오는 RTP 레벨 조작을 하지 않는다. 키프레임 확보는 섹션 5(카메라 웜업)에서 처리.

---

## 3. 송신(Tx) 상태 머신 (FSM)

### 3.1 단계별 정의

| 단계                     | 시간     | 네트워크 (RtpSender)       | 하드웨어 (Source) | 복구 비용                      |
| ------------------------ | -------- | -------------------------- | ----------------- | ------------------------------ |
| **Stage 1** (Soft-Mute)  | 0 ~ 1분  | `active = false` (0바이트) | ON (웜업 유지)    | **0ms**                        |
| **Stage 2** (Hard-Mute)  | 1 ~ 10분 | `active = false` (0바이트) | OFF (자원 반납)   | **300ms+** (하드웨어 재시작)   |
| **Stage 3** (Deep Sleep) | 10분+    | ICE Ping 장주기 전환       | OFF (자원 반납)   | **1~2초** (ICE Restart 가능성) |

### 3.2 Stage 1 — Soft-Mute

- `RtpSender.encoding.active = false`로 RTP 전송 중단
- 카메라/마이크 하드웨어 ON 유지
- SRTP 세션, DTLS 컨텍스트, Transceiver 모두 유지
- 발언 버튼 → `active = true`만으로 즉시 복구

### 3.3 Stage 2 — Hard-Mute ✅ 구현 완료 (2026-03-14)

- 하드웨어(카메라 capturer, AudioSource) 해제 → 배터리/발열 보호
- **Video**: `videoSender.setTrack(null)` → sender 유지, 트랙만 분리 → `stopCamera()` (하드웨어 해제)
- **복구**: `restartCamera()` → Camera2 재시작 + `setTrack(newTrack)` (replaceTrack) → 300ms+ 지연
- 1분 이상 미사용자에게는 허용 가능한 수준
- **Audio**: 현재 soft mute로 대체 (AudioSource 해제는 후순위)

**Stage 2 진입 시 동작 (구현 완료):**

- `MUTE_UPDATE(muted=true)` → 서버가 `TRACK_STATE` + `VIDEO_SUSPENDED` 브로드캐스트
- 수신자 UI: `onVideoSuspended(userId)` → 아바타/placeholder 전환
- 복구 시: `restartCamera()` → `onFirstFrameAvailable()` → `CAMERA_READY` 시그널
- 서버: PLI 2발(즉시 + 150ms) + `VIDEO_RESUMED` 브로드캐스트
- 수신자 UI: `onVideoResumed(userId)` → 비디오 복원

### 3.4 Stage 3 — Deep Sleep

- PeerConnection 유지, ICE Ping 주기만 극단적으로 늘림 (30~60초)
- 엔진을 완전히 내리지 않는 이유:
  - DTLS SecurityContext(키, 인증서)가 메모리에서 소실됨
  - SDP를 캐싱해도 fingerprint 변경으로 DTLS Handshake 재수행 필요
  - PeerConnection을 살려두면 ICE/DTLS 컨텍스트 보존
- NAT 매핑 유지 시 즉시 복구, 만료 시 ICE Restart (1~2초)
- 10분+ 미사용자에게 1~2초 재연결은 충분히 허용 가능

> **기술적 가능 여부:**
>
> - Stage 1, 2: ✅ 확정. WebRTC 표준 API(`encoding.active`, `replaceTrack`)만으로 구현 가능
> - Stage 3: ⚠️ 조건부 가능. ICE Ping 주기 조정은 libwebrtc C++ 레이어에서 가능하나 NAT 타임아웃 실측 검증 필수

### 3.5 단계 전환 타임아웃

- 1분, 10분 값은 **config 상수로 분리**하여 운영 중 튜닝 가능하게 처리
- 매직넘버 금지 원칙 준수

---

## 4. ICE Keep-alive 최적화

### 4.1 문제

기본 ICE Ping 주기(2.5초)가 미디어 미전송 상태에서도 모뎀을 지속 깨움 → 배터리 낭비

### 4.2 해결

C++ 레이어에서 `ice_candidate_pair_ping_interval`을 FSM 단계별로 동적 조정:

| 단계      | ICE Ping 주기 | 근거                                                         |
| --------- | ------------- | ------------------------------------------------------------ |
| Stage 1~2 | **10~15초**   | 한국 통신사 대칭형 NAT 타임아웃 20~30초 고려, 안전 마진 확보 |
| Stage 3   | **30~60초**   | NAT 매핑 소실 감수, ICE Restart로 복구 전제                  |

### 4.3 실측 필수 사항

- 한국 주요 통신사(SKT / KT / LGU+) LTE / 5G 환경별 NAT 타임아웃 실측
- `ice_candidate_pair_ping_interval`은 libwebrtc 지원 설정값이나, 실제 동작은 네트워크 환경 의존

> **기술적 가능 여부: ⚠️ 가능하나 실측 필수**

---

## 5. 카메라 웜업 & 키프레임 확보 ✅ 구현 완료 (2026-03-14)

### 5.1 방식 — 클라이언트 주도

서버가 임의 타이밍에 PLI를 보내는 대신, 클라이언트가 카메라 준비 완료를 서버에 알린다.

### 5.2 시나리오 (구현됨)

1. 클라이언트: `restartCamera()` → Camera2 재시작
2. 클라이언트: `CameraEventsHandler.onFirstFrameAvailable()` 콜백 수신
   - Android: `MediaSession.restartCamera()` 내부에서 `CameraEventsHandler` 등록
   - Web: `getUserMedia()` + `replaceTrack()` 완료 시점 (동기)
3. 클라이언트 → 서버: `CAMERA_READY` (op=18) 시그널 전송
4. 서버: PLI 즉시 1발 + **150ms 후 보험 PLI 1발** = 총 2발
5. 서버: `VIDEO_RESUMED` 브로드캐스트 → 수신자 UI 복원

### 5.3 구현 세부

- Android `restartCamera()`: `setTrack(null)` → Camera2 재시작 → `setTrack(newTrack)` (SSRC 불변)
- `onFirstFrameAvailable()`은 1회만 발화 (`firstFrameFired` 가드)
- Web `_doHardUnmute()` / `_pttHardUnmute()`: `replaceTrack()` 성공 후 즉시 `_sendCameraReady()`
- 서버 `handle_camera_ready()`: 기존 `handle_floor_request()` PLI burst 패턴 재사용 (cancel + spawn)

> **기술적 가능 여부: ✅ 구현 완료**

---

## 6. 예외 처리: 시그널링(TCP) 단절

- 시그널링 연결 유실 시 **모든 캐시(SDP, DTLS, ICE) 파기**
- 이후 이벤트 발생 시 **Full Cold Start** (신규 SDP 교환) 수행
- 서버-클라이언트 상태 불일치(좀비 세션) 원천 차단
- **타협 없음**

---

## 7. 서버 연동 — Opcode ✅ 구현 완료 (2026-03-14)

| Opcode            | op  | 방향               | 용도                                              | 상태 |
| ----------------- | --- | ------------------ | ------------------------------------------------- | ---- |
| `CAMERA_READY`    | 18  | Client → Server    | 카메라 웜업 완료 알림, 서버가 PLI 2발 + VIDEO_RESUMED | ✅ |
| `VIDEO_SUSPENDED` | 104 | Server → Client(s) | 특정 사용자 비디오 중단 알림, UI placeholder 전환 | ✅ |
| `VIDEO_RESUMED`   | 105 | Server → Client(s) | 특정 사용자 비디오 재개 알림, UI 복원             | ✅ |

---

## 8. 요약 — 구현 상태 매트릭스

| 항목                                 | 상태                 | 비고                                                |
| ------------------------------------ | -------------------- | --------------------------------------------------- |
| Rx Audio: Opus silence + offset 보정 | ✅ **구현 완료**     | libwebrtc C++ 패치, AAR 빌드 완료, FloorFsm 연동 대기 |
| Tx Stage 1 (Soft-Mute)               | ✅ **구현 완료**     | Conference 3-state + PTT 선언적 미디어 제어          |
| Tx Stage 2 (Hard-Mute)               | ✅ **구현 완료**     | Video: stopCamera/restartCamera, CAMERA_READY opcode |
| Tx Stage 3 (Deep Sleep)              | ⚠️ 조건부            | NAT 타임아웃 실측 필요, 후순위                       |
| ICE Ping 동적 조정                   | ⚠️ 조건부            | libwebrtc 빌드 환경 확보됨, 후순위                   |
| 카메라 웜업 PLI                      | ✅ **구현 완료**     | CAMERA_READY → PLI 2발 + VIDEO_RESUMED               |
| 시그널링 단절 → Full Cold Start      | ✅ **기구현**        | 이전 세션에서 구현됨                                 |

---

## 9. libwebrtc 커스텀 빌드 정보

- **소스 위치**: WSL `~/webrtc-android/src` (main 브랜치 기반)
- **커스텀 브랜치**: `oxlens-custom` (main 대비 3커밋, 529줄 패치)
- **빌드 설정**: `is_debug=true`, `target_cpu=arm64`, `rtc_include_tests=false`
- **AAR 출력**: `out/libwebrtc.aar` (~13.5MB)
- **패치 파일**: `doc/oxlens-patch-20260314.diff`
- **빌드 절차 문서**: `doc/LIBWEBRTC_BUILD.md`

### Java API (PeerConnection에 추가)

```java
pc.enableAudioInterceptor(boolean enable)
pc.setAudioInterceptorSilence(boolean inject, long ssrc)
pc.resetAudioInterceptorOffset()
pc.setAudioInterceptorOpusPt(int pt)
```

### 설계 가정 vs 구현 현실

| 설계서 가정                           | 실제 구현                                        |
| ------------------------------------- | ------------------------------------------------ |
| Dependencies에 PacketReceiver Proxy 주입 | Dependencies에 PacketReceiver 없음 — Call 직접 수정 |
| DeliverPacket() 가로채기              | DeliverRtpPacket() — 메서드명만 다름              |
| PeerConnectionFactory 레벨 주입       | PeerConnection + Call 레벨에서 처리               |
| SSRC 기반 Audio 선별                  | MediaType::AUDIO로 이미 분류되어 진입             |
