# 세션 컨텍스트 — 2026-03-14 (Mute 3-state + Video E2E)

> **Mute 3-state 구현 + Camera2 비디오 전송 E2E 성공 (Android → SFU → 브라우저)**

---

## 이번 세션 완료 작업

### 1. Mute 3-state ✔️
- Conference: UNMUTED → SOFT_MUTED (track.enabled=false) → HARD_MUTED (5초 에스컬레이션, stub)
- PTT: audio는 floor 소유 (toggleMute 차단), video는 _userVideoOff 토글
- `toggleMute(kind)` / `isMuted(kind)` / `getMutePhase(kind)` public API
- `MUTE_UPDATE(ssrc, muted)` 서버 통보 (`notifyMuteServer`)
- `resetMute()` — disconnect/leaveRoom/onDisconnected 3곳에서 호출
- `onMuteChanged(kind, muted, phase)` 리스너 콜백
- 데모앱: Conference 모드에서 Mute 버튼 + 상태 표시 UI

### 2. 비디오 전송 (Camera2 → publish PC) ✔️
- `MediaSession.initCamera()` — Camera2Enumerator + VideoSource + VideoTrack 생성
- **자동 비디오 감지**: `setupPublishPc()` 내부에서 server_config에 video 코덱 존재 여부로 판단
  - 코덱 있으면 SDP 교환 전에 카메라 초기화 + video track 추가
  - 2PC 구조에서는 SDP 교환 전에 track이 있어야 localDescription에 SSRC 포함
- `startCamera()` / `stopCamera()` / `switchCamera()` public API
- `getLocalVideoTrack()` — 데모앱에서 SurfaceViewRenderer 연결용
- `onCameraSwitched(facingMode)` 리스너 콜백
- 리모트 VideoTrack setEnabled 처리

### 3. 인프라 변경 ✔️
- libwebrtc AAR: `implementation` → `api` (데모앱에서 org.webrtc.* 직접 참조 가능)
- CAMERA 퍼미션 추가 (Manifest + 런타임 요청)

### 4. 프로젝트 정비 (이번 세션 전반) ✔️
- 3개 레포 `claude/` 디렉토리 + 세션 컨텍스트 규칙 반영
- oxlens-home: GUIDELINES.md + CHANGELOG.md 신규 생성
- oxlens-sdk-core: SKILL_OXLENS.md 세션 컨텍스트 섹션 업데이트
- 폐기 crate 3개(oxlens-jni, oxlens-webrtc, oxlens-webrtc-sys) DEPRECATED.md
- .gitignore 현행화 (AAR, .so, Android 빌드 산출물 제외)
- SESSION_INDEX.md (전체 세션 이력 인덱스)
- 서버 CHANGELOG 분리 (서버 0.5.5 + home 0.5.4~0.5.6)

---

## 현재 상태

### E2E 동작 확인
```
Android: CAMERA+RECORD_AUDIO granted → connect → ROOM_CREATE(conference) → ROOM_JOIN
→ server codecs: [opus, VP8] → 자동 카메라 초기화
→ publish PC ready — 3 tracks (audio + video + RTX)
→ PUBLISH_TRACKS sent: [audio=..., video=..., video=...(RTX)]
→ publish ICE CONNECTED
→ 브라우저에서 Android 영상 수신 확인 (화질/지연 우수)
→ Mute 토글 정상 (soft mute + 5초 hard escalation + unmute)
```

### 알려진 이슈
- `extractPublishedSsrcs()`에서 RTX SSRC를 별도 video track으로 파싱 → PUBLISH_TRACKS에 video SSRC 2개 전송됨. 서버가 unknown SSRC 무시하므로 당장 문제 없지만 정리 필요
- `onFloorTaken: user=` 빈 문자열 (서버 쪽 확인 필요, 이전 세션부터)
- subscribe SDP 디버그 로그 남아있음
- hard mute 실제 구현은 stub (비디오 추가 완료로 이제 구현 가능)
- `EglBase` dispose 미구현 (stopCamera에서 정리 필요)

---

## 변경된 파일

```
platform/android/oxlens-sdk/
├── build.gradle.kts                    ← libwebrtc implementation → api
├── src/main/java/com/oxlens/sdk/
│   ├── OxLensClient.kt                 ← Mute 3-state 상태 머신 + 비디오 제어 API
│   ├── OxLensEventListener.kt          ← onMuteChanged + onCameraSwitched 콜백
│   ├── Constants.kt                    ← (변경 없음, MUTE 상수 이미 존재)
│   ├── media/
│   │   └── MediaSession.kt             ← Camera2 initCamera + setVideoMuted + getPublishSsrc
│   │                                      setupPublishPc에 자동 비디오 감지 로직
│   ├── ptt/
│   │   └── FloorFsm.kt                 ← (변경 없음)
│   └── signaling/
│       └── Message.kt                  ← (변경 없음, buildMuteUpdate 이미 존재)

platform/android/demo-app/
├── src/main/AndroidManifest.xml         ← CAMERA 퍼미션 추가
├── src/main/java/.../MainActivity.kt   ← Mute 버튼 + CAMERA 런타임 퍼미션
└── src/main/res/layout/activity_main.xml ← btnMute + tvMute 뷰 추가

# 프로젝트 정비 (3개 레포)
oxlens-sdk-core/
├── .gitignore                          ← 현행화
├── claude/SESSION_INDEX.md             ← 신규
├── doc/SKILL_OXLENS.md                 ← 세션 컨텍스트 섹션 업데이트
├── crates/oxlens-jni/DEPRECATED.md     ← 신규
├── crates/oxlens-webrtc/DEPRECATED.md  ← 신규
└── crates/oxlens-webrtc-sys/DEPRECATED.md ← 신규

oxlens-home/
├── GUIDELINES.md                       ← 신규
├── CHANGELOG.md                        ← 신규
└── claude/                             ← 디렉토리 확인

oxlens-sfu-server/
├── GUIDELINES.md                       ← §14 세션 컨텍스트 섹션 추가
├── CHANGELOG.md                        ← 0.5.5 추가
└── claude/                             ← 디렉토리 생성
```

---

## 다음 세션 작업

### 1순위: extractPublishedSsrcs RTX 필터링
- RTX SSRC(ssrc-group:FID)를 감지하여 publishedTracks에서 제외
- 또는 RTX SSRC를 별도 필드로 관리 (서버 PUBLISH_TRACKS에 rtx_ssrc 포함)

### 2순위: 데모앱 비디오 UI
- SurfaceViewRenderer 2개 (로컬 프리뷰 + 리모트)
- home 데모앱 구조로 전면 개편 (현재 버튼만 있는 테스트 UI)
- 카메라 전환 버튼

### 3순위: hard mute 실제 구현
- video hard mute: stopCamera() + dummy track 교체
- video hard unmute: restartCamera() + replaceTrack
- audio hard mute: AudioRecord 정지 검토

### Backlog
- EglBase dispose 정리
- subscribe SDP 디버그 로그 제거
- HW video codec factory 전환 검토 (현재 SW, 성능 비교)
- 텔레메트리 (음성/영상 지연 분석)
- FLOOR_TAKEN user_id 빈 문자열 서버 확인

---

## 기술 메모

### 비디오 자동 감지 패턴
```
ROOM_JOIN → server_config.codecs에 Video kind 존재 여부 확인
→ 있으면: initCamera() → publish PC에 video track 추가 (SDP 교환 전)
→ 없으면: audio only
```
2PC 구조에서는 SDP 교환 전에 track이 있어야 localDescription에 SSRC 포함.
SDP 교환 후 addTrack하면 re-negotiation 없이는 SSRC가 반영 안 됨.

### Camera2 초기화 순서
```
Camera2Enumerator → createCapturer(deviceName)
→ EglBase.create() → SurfaceTextureHelper.create()
→ factory.createVideoSource() → capturer.initialize() → capturer.startCapture()
→ factory.createVideoTrack() → pc.addTrack()
```

### 화질이 좋은 이유
- Camera2 API → HAL 직접 접근 (브라우저 getUserMedia 대비 추상화 레이어 적음)
- libwebrtc AAR 내부 HW 인코더 자동 사용 가능 (SW 팩토리 설정해도)
- 네이티브 캡처 스레드 독립 동작 (브라우저 렌더링 루프 제약 없음)

### RTX SSRC 이슈
- `extractPublishedSsrcs()`가 `a=ssrc:NNN cname:` 패턴으로 모든 SSRC 추출
- VP8 video track의 RTX SSRC도 별도 video SSRC로 파싱됨
- 서버에 video SSRC 2개 전송 → 서버가 RTX SSRC는 unknown으로 무시
- 수정: `a=ssrc-group:FID primary rtx` 파싱하여 RTX SSRC 필터링

---

## 주의사항 (다음 세션 Claude에게)

1. **비디오는 setupPublishPc 내부에서 자동 시작** — server_config codecs 기반. 앱이 enableVideo를 설정할 필요 없음
2. **libwebrtc AAR은 `api`로 노출** — 데모앱에서 org.webrtc.* 직접 참조 가능
3. **CAMERA 런타임 퍼미션 필수** — MainActivity에서 RECORD_AUDIO + CAMERA 동시 요청
4. **RTX SSRC 중복 전송 중** — PUBLISH_TRACKS에 video SSRC 2개 (primary + RTX). 당장 동작에 문제 없지만 정리 필요
5. **hard mute는 stub** — doHardMute/doHardUnmute가 soft mute로 대체 중. 비디오 추가 완료로 이제 실제 구현 가능
6. **EglBase 누수 가능** — startCamera에서 EglBase.create() 하지만 stopCamera에서 dispose 안 함

---

*author: kodeholic (powered by Claude)*
