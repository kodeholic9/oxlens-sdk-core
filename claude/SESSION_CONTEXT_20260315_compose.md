# 세션 컨텍스트 — 2026-03-15 (Compose 전환 + UI 고도화)

> **데모앱 XML → Jetpack Compose 전면 전환 완료 + Home UI 미러링 고도화**

---

## 완료 작업

### 1. Gradle 설정 ✔️
- Compose BOM 2024.12.01 + material3 + material-icons-extended
- activity-compose 1.9.3 + lifecycle-viewmodel-compose 2.8.7

### 2. 테마/컬러 (ui/theme/) ✔️
- `Color.kt` — Brand, Text, Status, Control 컬러
- `Theme.kt` — Material3 darkColorScheme

### 3. ViewModel (DemoViewModel.kt) ✔️
- `DemoUiState` data class — 단일 진실 소스 (StateFlow)
- `WsState`, `RoomMode`, `PttState`, `Participant` 등 enum/data class
- SDK 콜백(OxLensEventListener) → StateFlow 자동 업데이트
- `MediaPreset` enum — Home의 `MEDIA_PRESETS` 미러 (ECO/NORMAL/HD/HD_PLUS/FHD)
- `ServerPreset` + `SERVER_PRESETS` — Home의 `<select id="srv-url">` 3개 옵션 미러
- `RoomInfo` data class — 방 목록 항목
- Toast: `MutableSharedFlow<Pair<String,String>>` → UI `LaunchedEffect`로 수집
- 오디오 장치: `audioDeviceNames`, `selectedAudioDevice` 상태 (연결 준비됨)

### 4. ConnectionPanel — Home 미러 ✔️
- **1행**: 서버 주소 `ExposedDropdownMenuBox` (3개 프리셋) + 전원 버튼
- **2행**: 방 목록 `ExposedDropdownMenuBox` (`{name} [PTT] (n/30)` 포맷) + 입장/퇴장 버튼
- 2행 항상 노출, 연결 전에는 disabled (드롭다운 + 버튼 모두)
- `onRoomList` 파싱 수정: `JSONObject` → `.optJSONArray("rooms")`
- `selectServer()`, `selectRoom()`, `joinSelectedRoom()`, `leaveRoom()` 액션

### 5. Toast 시스템 (Gemini 디자인) ✔️
- `ToastData`, `ToastStack`, `ToastItem` 컴포저블
- 우상단 오버레이, AnimatedVisibility 슬라이드 인/아웃
- 타입별 아이콘+컬러 (ok=초록, err=빨강, warn=노랑, signal/media/ice=시안)
- **Home과 동일한 3개 toast만 사용:**
  - `joinSelectedRoom()` → media: "카메라/마이크 준비 중…"
  - `onRoomJoined` → ice: "미디어 연결 중…"
  - `onPublishReady` → ok: "미디어 연결 완료"

### 6. Settings 바텀시트 (Gemini 디자인) ✔️ (UI만)
- Header 톱니 아이콘 → `SettingsBottomSheet` 열기
- `ModalBottomSheet` + BrandSurface 배경
- 화질 드롭다운 (5단계) + 마이크/스피커/카메라 장치 선택
- **현재 정적 옵션 — SDK 연결 미완**

### 7. Conference/PTT 안정화 ✔️
- `key(userId)`로 참가자 타일 안정화 — 추가/제거 시 WebRtcSurface 재생성 방지
- `key("local-pip")`로 로컬 PIP 안정화
- PTT `onRemoteVideoTrack`: 발화자 매칭 조건 강화 (TALKING + userId 일치)

### 8. MainActivity.kt ✔️
- `onSelectServer`, `onSelectRoom`, `onRoomJoin`, `onRoomLeave` 올바르게 연결
- `toastEvent` SharedFlow 연결

---

## 변경된 파일

```
# 수정
demo-app/.../DemoViewModel.kt      ← ServerPreset, MediaPreset, RoomInfo 추가
                                      roomList/selectedRoomId/selectedServerIndex 상태
                                      toast SharedFlow + Home 미러 3개 toast
                                      onRoomList 파싱 수정 (JSONObject→rooms배열)
                                      오디오장치/화질 상태 추가 (연결 미완)

demo-app/.../ui/OxLensScreen.kt    ← ConnectionPanel: 서버+방 ExposedDropdownMenuBox
                                      2행 항상 노출 + disabled 제어
                                      ToastData/ToastStack/ToastItem 추가
                                      SettingsBottomSheet/SettingDropdown 추가
                                      key() 안정화 (참가자 타일, 로컬 PIP)
                                      SharedFlow toastEvent 수집

demo-app/.../MainActivity.kt       ← 파라미터 올바르게 연결
```

---

## 아키텍처: Toast 흐름

```
SDK 콜백 → ViewModel toast(type, text)
         → MutableSharedFlow.tryEmit()
         → OxLensApp LaunchedEffect { collect }
         → showToast() → toastList (mutableStateListOf)
         → ToastStack UI (3초 후 자동 제거)
```

---

## 다음 세션 작업 — 설정 연동 (1순위)

### 1. 화질 프리셋 → WebRTC 연동
- `DemoUiState.mediaPreset` → SettingsBottomSheet에서 선택 시 `selectMediaPreset(preset)` 호출
- **적용 시점**: "다음 입장 시" (Home 동일)
  - `OxLensClient.setupMedia()` 내 `mediaSession.startCamera(width, height, fps)`에 프리셋 전달
  - **maxBitrate**: `RtpSender.getParameters()` → `encodings[0].maxBitrateBps` → `setParameters()` 
  - 현재 OxLensClient에 mediaPreset을 전달하는 API가 없음 → 추가 필요
  - 방법: `OxLensClient`에 `var mediaWidth/Height/Fps/MaxBitrate` 프로퍼티 추가, 또는 MediaConfig data class

### 2. 오디오 장치 열거/선택 연동
- `AudioDeviceManager`는 이미 구현 완료 (Twilio AudioSwitch)
- ViewModel에서 `client?.device.start()` → `availableDevices` → UI 갱신
- `AudioDeviceListener` 구현 → 핫플러그 시 `audioDeviceNames` 업데이트
- 장치 선택: `client?.device.selectDevice(device)` — 즉시 적용 (Home 동일)
- 스피커/이어피스 전환: `selectSpeaker()` / `selectEarpiece()` 연결

### 3. 카메라 장치 (Android 특성)
- Android는 Web처럼 deviceId로 선택 불가 — 전면/후면 전환만 (`switchCamera()`)
- 설정 드롭다운에 "전면/후면" 2개 옵션, 또는 기존 MediaControls 카메라전환 버튼 유지

### 4. SettingsBottomSheet 동적 연결
- 화질: `MediaPreset.entries.map { it.label }` → 선택 시 `onSelectPreset(preset)` 콜백
- 오디오: `state.audioDeviceNames` → 선택 시 `onSelectAudioDevice(name)` 콜백
- 카메라: "전면" / "후면" → 선택 시 `onSwitchCamera()` 호출

---

## 참고: SDK 공개 API (OxLensClient.kt)

```kotlin
// 연결
fun connect() / disconnect()
// 방
fun listRooms() / createRoom() / joinRoom() / leaveRoom()
// 미디어
fun startCamera(sink?, width=1280, height=720, fps=24)
fun stopCamera() / switchCamera()
fun toggleMute(kind) / isMuted(kind) / getMutePhase(kind)
fun getLocalVideoTrack(): VideoTrack?
// PTT
fun floorRequest() / floorRelease()
val floorFsm: FloorFsm
// 장치
val device: AudioDeviceManager  // .start() .activate() .selectDevice() .selectSpeaker() .selectEarpiece()
```

---

## 주의사항 (다음 세션 Claude에게)

1. **EglBase는 ViewModel에서 관리** — onCleared()에서 release
2. **WebRtcSurface는 key()로 안정화 필수** — 참가자 변경 시 재생성 방지
3. **SDK 콜백은 OkHttp 워커 스레드** — StateFlow.value 직접 변경은 thread-safe (MutableStateFlow)
4. **Toast는 Home과 동일하게 3개만** — 과도한 toast 남발 금지
5. **화질 프리셋은 "다음 입장 시" 적용** — 실시간 변경 아님
6. **오디오 장치는 즉시 적용** — selectDevice 호출 즉시 라우팅 변경
7. **카메라 열거**: Android는 CameraEnumerator로 전면/후면만, Web의 deviceId 방식 아님
8. **maxBitrate 적용**: `RtpSender.getParameters()` → `encodings[0].maxBitrateBps = value` → `setParameters()` — publish PC의 video sender에 적용

---

*author: kodeholic (powered by Claude)*
