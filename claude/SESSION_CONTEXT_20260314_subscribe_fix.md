# 세션 컨텍스트 — 2026-03-14 (Subscribe Fix + PTT Phase 3)

> **Subscribe PC 크래시 해결 + PTT Floor Control 구현 + 양방향 음성 E2E 완료**

---

## 이번 세션 완료 작업

### 1. Subscribe PC 크래시 해결 ✔️
- **증상**: setRemoteDescription 직후 SIGABRT (`front() called on an empty vector`, worker_thread)
- **근본 원인**: `PeerConnectionFactory`에 `VideoDecoderFactory`/`VideoEncoderFactory` 미설정
  - Android libwebrtc Java API는 코덱 팩토리를 명시적으로 설정 필수
  - 미설정 시 video m-line 처리에서 빈 코덱 벡터 접근 → 크래시
- **수정**: `SoftwareVideoEncoderFactory` + `SoftwareVideoDecoderFactory` 추가
- **디버깅 과정**: extmap 필터링, RTX PT 제거, addTransceiver 시도 → 전부 효과 없음 → audio-only 테스트로 video가 원인 특정 → codec factory 미설정 발견

### 2. SDP 빌더 방어 코드 ✔️
- video m-line에서 `ssrc-audio-level` extmap 제거 (`AUDIO_ONLY_EXTMAP_URIS`)
- subscribe video m-line에서 `rtxSsrc` 없으면 코덱의 `rtxPt` null 처리
- `TrackInfo`에 `rtx_ssrc` 파싱 추가, `OxLensClient`에서 `TrackDesc`로 전달

### 3. RECORD_AUDIO 런타임 퍼미션 ✔️
- MainActivity에서 `ActivityCompat.requestPermissions()` → 승인 후 connect
- AudioRecord 에러 해결, 마이크 캡처 정상 동작

### 4. 양방향 음성 E2E 확인 ✔️
- Android → SFU → 브라우저: 음성 청취 확인
- 브라우저 → SFU → Android: 음성 청취 확인

### 5. PTT Floor Control (Phase 3) ✔️
- **FloorFsm.kt** 전면 구현: IDLE → REQUESTING → GRANTED → RELEASING → IDLE
- Floor Ping 자동 타이머 (2초 주기)
- 마이크 mute/unmute 자동 연동 (`MediaSession.setAudioMuted()`)
- OxLensClient에서 FloorFsm 연동 (시그널링 이벤트 ↔ FSM 전이)
- PTT 모드 입장 시 초기 마이크 muted

### 6. 데모앱 UI 개선 ✔️
- Conference / PTT 모드 선택 버튼
- PTT TALK 버튼 (길게 누르기: ACTION_DOWN=request, ACTION_UP=release)
- Floor 상태 UI 표시 (SPEAKING/IDLE/DENIED/REVOKED)

---

## 현재 상태

### E2E 동작 확인
```
Android: RECORD_AUDIO granted → connect → ROOM_CREATE(ptt) → ROOM_JOIN
→ publish ICE CONNECTED → subscribe ICE CONNECTED
→ PTT TALK 길게 누름 → FLOOR_REQUEST → Granted → mic unmute → 상대에게 음성 전달
→ PTT TALK 뗌 → FLOOR_RELEASE → mic mute → Floor IDLE
→ 반복 테스트 6회 정상
```

### 알려진 이슈
- `onFloorTaken: user=` — FLOOR_TAKEN 이벤트에서 user_id가 빈 문자열 (서버 쪽 확인 필요)
- subscribe SDP 전문 디버그 로그 남아있음 — 안정화 후 제거
- 음성 지연 체감됨 — 텔레메트리 추가 시 분석 예정

---

## 변경된 파일

```
platform/android/oxlens-sdk/src/main/java/com/oxlens/sdk/
├── media/
│   ├── MediaSession.kt     ← SW video codec factory + setAudioMuted()
│   ├── SdpBuilder.kt       ← video extmap 필터링, rtxSsrc 방어
│   └── SdpTypes.kt         ← AUDIO_ONLY_EXTMAP_URIS 상수
├── signaling/
│   └── Message.kt          ← TrackInfo.rtxSsrc 파싱
├── ptt/
│   └── FloorFsm.kt         ← ★ 전면 구현 (Phase 3)
└── OxLensClient.kt         ← FloorFsm 연동, PTT 초기 mute

platform/android/demo-app/
├── src/main/java/.../MainActivity.kt   ← RECORD_AUDIO 퍼미션 + Conference/PTT UI + TALK 버튼
└── src/main/res/layout/activity_main.xml ← 모드 선택 + TALK 버튼 + 상태 표시
```

---

## 다음 세션 작업

### 1순위: Mute 3-state
- Conference: UNMUTED → SOFT_MUTED → HARD_MUTED (home과 동일)
- PTT: 선언적 제어 (floor + videoOff → 자동 결정)
- `toggleMute(kind)` / `isMuted(kind)` API 추가

### Backlog
- subscribe SDP 디버그 로그 제거
- HW video codec factory 전환 검토
- 텔레메트리 (음성 지연 분석)
- FLOOR_TAKEN user_id 빈 문자열 서버 확인

### 완료된 작업 (이번 세션 후반)
- ✅ API 네이밍 통일: disconnect/floorRequest/floorRelease/TALKING/LISTENING
- ✅ Constants.kt (home constants.js 미러)
- ✅ AudioSwitch 연동 (Twilio 1.1.5, preferSpeaker=ptt, BT 지원)
- ✅ AudioDeviceManager.kt 신규
- ✅ BLUETOOTH_CONNECT 퍼미션 추가

---

## 기술 메모

### Android libwebrtc 핵심 교훈
- **PeerConnectionFactory에 VideoDecoderFactory/VideoEncoderFactory 필수**
- **extmap은 kind별 필터링**: audio 전용을 video에 넣으면 크래시 가능
- **AAR은 순정 libwebrtc** (WSL 빌드, livekit 래퍼 아님)
- VP8/VP9/H264 디코더 포함, addTransceiver 지원

### home SDK 구조 (Kotlin 포팅 참조)
```
OxLensClient (EventEmitter facade)
├── sig: Signaling         — WS + Floor FSM
├── media: MediaSession    — Publish/Subscribe PC
├── tel: Telemetry         — stats 수집
├── device: DeviceManager  — 장치 열거/전환/핫플러그
```
- 상수: `OP`, `CONN`, `FLOOR` (IDLE/REQUESTING/TALKING/LISTENING), `MUTE` (UNMUTED/SOFT/HARD)
- Mute: conference=3-state 에스컬레이션, PTT=선언적 (floor+videoOff 계산)
- Device: Android에서는 Twilio AudioSwitch 사용 예정

---

*author: kodeholic (powered by Claude)*
