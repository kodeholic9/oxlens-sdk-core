# 세션 컨텍스트 — 2026-03-14 (설정 연동 + 크래시 수정 + PTT 영상 수정)

> **SettingsBottomSheet 동적 연동 (화질/오디오/카메라) + 사용자 ID UI + libwebrtc 크래시 3건 수정 + PTT 영상 안 나오는 버그 수정**

---

## 이번 세션 완료 작업

### 1. 화질 프리셋 → WebRTC 연동 ✔️
- `OxLensClient`에 `captureWidth/Height/Fps/maxBitrateBps` 프로퍼티 4개 추가
- `MediaSession.setupPublishPc()` 시그니처 확장 — 해상도/fps/bitrate 파라미터
- `MediaSession.applyMaxBitrate()` — 캐시된 `videoSender`에 `RtpParameters.encodings[0].maxBitrateBps` 적용
- publish ICE CONNECTED 시 자동 적용 (`pendingMaxBitrateBps`)
- `DemoViewModel.joinSelectedRoom()`에서 preset → client 프로퍼티 세팅 (다음 입장 시 적용)
- `SettingsBottomSheet` 화질 드롭다운 → `MediaPreset.entries` 동적 연결

### 2. 오디오 장치 열거/선택 연동 ✔️
- `OxLensEventListener.onAudioDevicesChanged(deviceNames, selectedDevice)` 콜백 추가
- `OxLensClient`에 `AudioDeviceListener` 구현 — `device` lazy 초기화로 listener 연결
- `selectAudioDevice(name)` / `selectSpeaker()` / `selectEarpiece()` public API 추가
- `DemoViewModel.selectAudioDevice()` + `toggleSpeaker()` 실제 연동
- `SettingsBottomSheet` 스피커 드롭다운 → AudioSwitch 연동 (즉시 적용)
- 마이크 드롭다운 제거 — Android는 스피커 선택 시 마이크 자동 전환

### 3. 카메라 전면/후면 (설정 시트) ✔️
- `SettingsBottomSheet`에 카메라 드롭다운 ("전면"/"후면") 추가
- `cameraFacing` 상태 + `onSwitchCamera` 콜백 연결

### 4. SettingsBottomSheet 리팩터 ✔️
- `SettingDropdown` — 내부 상태(`selectedText`) 제거 → 외부 `selected` + `onSelect` 콜백 패턴
- 설정 시트 항목: **화질 / 스피커 / 카메라** 3개

### 5. 사용자 ID (Uxxx) 입력 UI ✔️
- `DemoUiState.userId` 필드 + `generateUserId()` (Uxxx 랜덤 3자리)
- ConnectionPanel 1행: `[서버 드롭다운] [userId TextField 72dp] [전원 버튼]` — Home 미러
- `connect()` 시 `_ui.value.userId`를 `OxLensClient`에 전달
- `disconnect()`/`onDisconnected()`에서 userId + mediaPreset 보존
- `onFloorGranted`/`onFloorTaken`에서 `USER_ID` 상수 → `_ui.value.userId`

### 6. libwebrtc 네이티브 크래시 수정 ✔️ (3건)

**6a. EGL 컨텍스트 공유 + HW 코덱 전환**
- 원인: `SoftwareVideoEncoderFactory` + 카메라 캡처 시 별도 `EglBase.create()` → SurfaceViewRenderer와 EGL 충돌 → SIGABRT
- 수정: `MediaSession` 생성자에 `eglContext: EglBase.Context?` 주입
- `DefaultVideoEncoderFactory(eglContext, true, true)` + `DefaultVideoDecoderFactory(eglContext)` — HW 코덱 사용
- `PeerConnectionFactory.Options` — `disableNetworkMonitor = true` 추가
- `OxLensClient.eglContext` 프로퍼티 → `DemoViewModel`에서 `eglBase.eglBaseContext` 주입
- 레퍼런스: livekit-android, hmu2020, webrtc-android-codelab 전부 동일 패턴 확인

**6b. RtpSender disposed 크래시**
- 원인: `applyMaxBitrate()`에서 `pc.senders` 순회 → libwebrtc 내부에서 이미 dispose된 sender 접근 → SIGABRT
- 수정: `addTrack()` 시점에 `videoSender` 캐시, `applyMaxBitrate()`에서 캐시 사용 + try-catch 방어

### 7. PTT 영상 안 나오는 버그 수정 ✔️ (2건)

**7a. FLOOR_TAKEN speaker 필드명 불일치**
- 원인: 서버는 `"speaker": "U821"` 전송 → SDK `SignalClient`에서 `"user_id"`로 파싱 → 빈 문자열 → remoteVideoTracks 매칭 실패
- 수정: `packet.d.optString("user_id")` → `packet.d.optString("speaker")`

**7b. PTT subscribe SDP에 가상 SSRC 미전달**
- 원인: `OxLensClient.setupSubscribe()`에서 `pttVirtualSsrc`를 `MediaSession.setupSubscribePc()`에 안 넘김 → Conference SDP로 빌드 → 서버 PTT rewriter 가상 SSRC와 불일치 → 디코딩 안 됨
- 수정: `OxLensClient`에 `pttVirtualSsrc` 필드 추가, `onRoomJoined`에서 저장, `setupSubscribe()`에 전달

---

## 변경된 파일

```
# SDK (oxlens-sdk)
OxLensClient.kt          ← captureWidth/Height/Fps/maxBitrateBps 프로퍼티
                            eglContext 프로퍼티
                            AudioDeviceListener 구현 (lazy device)
                            selectAudioDevice/selectSpeaker/selectEarpiece API
                            pttVirtualSsrc 필드 + 저장 + setupSubscribe 전달
                            setupMedia()에 화질 파라미터 전달
                            startCamera()에 화질 파라미터 전달

OxLensEventListener.kt   ← onAudioDevicesChanged() 콜백 추가

MediaSession.kt           ← eglContext 생성자 파라미터
                            DefaultVideoEncoder/DecoderFactory (HW, EGL 공유)
                            disableNetworkMonitor = true
                            setupPublishPc() 시그니처 확장 (width/height/fps/bitrate)
                            pendingMaxBitrateBps + applyMaxBitrate() (videoSender 캐시)
                            videoSender 필드 + addTrack 캐시 + dispose 정리

SignalClient.kt            ← FLOOR_TAKEN 파싱: "user_id" → "speaker"

SdpTypes.kt                ← 변경 없음 (PttVirtualSsrc 이미 정의됨)

# 데모앱 (demo-app)
DemoViewModel.kt           ← generateUserId() (Uxxx)
                            DemoUiState.userId 필드
                            updateUserId() 액션
                            selectMediaPreset() 액션
                            selectAudioDevice() 액션
                            connect()에서 userId/eglContext 세팅
                            joinSelectedRoom()에서 preset → client 세팅
                            disconnect/onDisconnected에서 userId/preset 보존
                            onFloorGranted/onFloorTaken에서 USER_ID → _ui.value.userId
                            onAudioDevicesChanged 콜백 → StateFlow
                            toggleSpeaker() 실제 연동

OxLensScreen.kt            ← OxLensApp: onUpdateUserId, onSelectMediaPreset, onSelectAudioDevice 파라미터
                            ConnectionPanel: userId TextField (72dp, 4자 제한)
                            SettingsBottomSheet: 동적 연결 (화질/스피커/카메라)
                            SettingDropdown: 외부 콜백 패턴 전환

MainActivity.kt            ← onUpdateUserId, onSelectMediaPreset, onSelectAudioDevice 연결
```

---

## 현재 상태

- **Conference 모드**: 영상/음성 정상 동작 ✅
- **PTT 모드**: 영상 나옴 ✅ (표시 지연 있음 — 메트릭스 작업 시 개선)
- **설정 시트**: 화질/스피커/카메라 동적 연결 완료 ✅
- **사용자 ID**: Uxxx 랜덤 + 수동 입력 가능 ✅
- **크래시**: libwebrtc 네이티브 크래시 3건 모두 수정 ✅

---

## 다음 세션 작업

### 1순위: PTT 영상 표시 지연 개선
- 현상: 상대방 발화 시작 → 영상 표시까지 체감 지연
- 원인 후보: PLI 타이밍, subscribe PC re-nego 지연, SurfaceViewRenderer 초기화 지연
- 접근: 서버 Telemetry + 클라이언트 로그 타임스탬프 비교

### 2순위: 클라이언트 메트릭 강화
- `getStats()` 기반 encoder/decoder 내부 상태 수집
- publish/subscribe 경로별 패킷 손실 분리
- `STATS_REQUEST`/`STATS_REPORT` 시그널링 스키마 (서버 설계 완료)

### 3순위: Conference 모드 UI 고도화
- 참가자 그리드 레이아웃 (2x2, 3x3 등)
- 로컬 PIP 위치/크기 조정

---

## 주의사항 (다음 세션 Claude에게)

1. **EGL 컨텍스트는 반드시 공유** — ViewModel `eglBase` 1개를 Factory + Capturer + Renderer 전체에서 사용. 별도 `EglBase.create()` 절대 금지.
2. **`pc.senders` 직접 순회 금지** — libwebrtc에서 disposed sender 접근 시 SIGABRT. `addTrack()` 반환값 캐시 사용.
3. **서버 시그널링 필드명 확인 필수** — `FLOOR_TAKEN`은 `"speaker"` 필드 (서버 handler.rs에서 확인). SDK 파싱과 불일치 주의.
4. **PTT subscribe SDP는 `pttVirtualSsrc` 필수** — null이면 Conference SDP로 빠짐.
5. **`DefaultVideoEncoderFactory(eglContext, true, true)`** — 3인자 시그니처. `SoftwareVideoEncoderFactory` 사용 금지.
6. **`disableNetworkMonitor = true`** — ICE-Lite 구조에서 불필요 + LiveKit #415 크래시 사례.
7. **PTT 영상 지연**: 현재 체감 지연 있음 — PLI burst 타이밍, keyframe 대기 시간 확인 필요.

---

*author: kodeholic (powered by Claude)*
