# SESSION_CONTEXT — 2026-03-15 (오후 세션)

> 이 파일은 Claude 세션 간 컨텍스트 유지용. 새 세션 시작 시 반드시 읽을 것.

---

## 세션 요약

2026-03-15 오후. UI 아이콘 전면 교체 + SurfaceView 안정화 + PTT 화자 전환 버그 수정.

---

## 완료된 작업

### 웹 (oxlens-home)

1. **rx-tx-pipeline.html v2.0 현행화** — PTT Audio Delay Resolution 실측 데이터 반영
   - Rx Pipeline 섹션: 스토리텔링 (Interceptor 실패 → Silence Flush 부분해결 → Dynamic ts_gap 최종해결)
   - 비교 요약 바 차트 + 실측 테이블 + G.114 기준 + 교훈 4가지
   - Hero: "설계 목표 ~0ms" → "실측 jbDelay 97ms"
   - Technology Foundation: libwebrtc 패치 시도→폐기→서버 해결 현실 반영
   - Feasibility Matrix: 4등급 (Measured/Proven/Field-test/Rejected)

### SDK (oxlens-sdk-core) — 데모앱 UI

2. **Phosphor Icons 전면 도입 (MIT)** — 14개 drawable 등록 + `currentColor` → `#FFFFFF` 수정
   - 연결: `ic_ph_power`, `ic_ph_sign_in`, `ic_ph_sign_out`
   - 미디어: `ic_ph_lock`, `ic_ph_lock_open`, `ic_ph_megaphone`, `ic_ph_camera_rotate`
   - A/V: `ic_ph_video`, `ic_ph_video_off`, `ic_ph_mic`, `ic_ph_mic_off`, `ic_ph_speaker`, `ic_ph_speaker_off`
   - 설정: `ic_ph_settings` (크기 36dp/20dp로 축소)
   - PTT: `ic_hand_tap` + animate-pulse (blue-400, alpha 0.4↔1.0)

3. **ControlButton 리팩터링** — `icon: ImageVector` → `iconRes: Int` + `painterResource()`
4. **잠금 버튼 1.5초 long press** — `pointerInput` + `awaitEachGesture` + coroutine delay 1500ms
5. **설정 버튼 크기 축소** — `IconButton` 48dp → 36dp, 아이콘 24dp → 20dp

### SDK — SurfaceView 안정화

6. **로컬 PIP zOrderMediaOverlay** — `WebRtcSurface`에 `zOrderMediaOverlay` 파라미터 추가, PIP에 `true` 적용
7. **라운드 코너 마스크 (Discord 방식)** — `RoundCornerOverlay` Composable 추가 (Canvas clipPath Difference)
8. **clearImage() 제거** — 트랙 교체 시 검은 화면 깜박 방지
9. **setEnableHardwareScaler(false)** — 인코더 ramp-up(360→540) 시 surfaceChanged 연쇄 방지, 유튜브식 흐림→선명 자연 전환

### SDK — PTT 버그 수정

10. **PTT 화자 전환 잔상 방지** — `LaunchedEffect` key를 `speakerTrack` → `speaker`(화자 ID)로 변경, 화자 변경 시 반드시 delay 재실행
11. **PTT 비디오 mute 누락 수정** — `toggleMute("video")` PTT 분기에 `applySoftMute()` 추가, 실제 `track.setEnabled(false)` 동작

---

## 잔여 작업

### 1순위

- [ ] **컨퍼런스 깜박임** — 새 참여자 입장 후 10~30초간 주기적 검은 화면 + 영상 축소 현상. subscribe re-nego / SurfaceView relayout 원인 특정 필요. 앱 태그 필터 logcat 필요
- [ ] **PTT 깜박임 완전 해결** — 입장 직후 2~5초 깜박임. 인코더 ramp-up + SurfaceView 특성. HW scaler OFF로 개선됐으나 추가 확인 필요
- [ ] **CAUTIONS.md 업데이트** — Phosphor Icons(MIT), SurfaceView currentColor 금지, HW scaler OFF 이유, RoundCornerOverlay 패턴

### 2순위

- [ ] **긴급발언(priority preemption)** — 서버 FLOOR_PRIORITY_REQUEST opcode 미구현
- [ ] **웹 중도참여 floor 동기화** — app.js에 floor_speaker 처리 미적용
- [ ] **CHANGELOG.md 업데이트** — 서버 + SDK 양쪽

### 3순위

- [ ] **debug AAR 빌드** — native stats 확보
- [ ] **SW instructor-mode 전체 코드 리뷰**

---

## 수정 파일 목록

### 웹 (oxlens-home)
- `docs/rx-tx-pipeline.html` — v2.0 전면 현행화

### SDK — drawable (신규)
- `ic_hand_tap.xml`, `ic_ph_power.xml`, `ic_ph_sign_in.xml`, `ic_ph_sign_out.xml`
- `ic_ph_lock.xml`, `ic_ph_lock_open.xml`, `ic_ph_megaphone.xml`, `ic_ph_camera_rotate.xml`
- `ic_ph_video.xml`, `ic_ph_video_off.xml`, `ic_ph_mic.xml`, `ic_ph_mic_off.xml`
- `ic_ph_speaker.xml`, `ic_ph_speaker_off.xml`, `ic_ph_settings.xml`

### SDK — Kotlin
- `OxLensScreen.kt` — Phosphor Icons 교체, LockButton 1.5초, RoundCornerOverlay, PTT speaker key
- `WebRtcSurface.kt` — zOrderMediaOverlay, clearImage 제거, HW scaler OFF
- `DemoViewModel.kt` — (onFloorTaken 수정 원복)
- `OxLensClient.kt` — PTT video mute applySoftMute 추가

---

## 핵심 기술 발견

### SurfaceView 깜박임 원인 (2가지)
1. **clearImage()** — 트랙 교체 시 surface buffer를 비워 검은 화면. 제거하면 이전 프레임이 남지만 깜박보다 나음
2. **setEnableHardwareScaler(true)** — 인코더 ramp-up(360→540) 시 `onFrameResolutionChanged` → `updateSurfaceSize` → `surfaceChanged` 연쇄로 surface buffer 재생성. OFF로 설정하면 layout 크기로 고정되어 GPU 스케일링만 발생 (유튜브식 흐림→선명)

### SurfaceView Z-order 문제
- `SurfaceView`는 별도 window layer에 렌더링, Compose z-order 무시
- 나중에 생성된 SurfaceView가 먼저 생성된 것 위를 덮음
- **해결**: `setZOrderMediaOverlay(true)` — media overlay layer에 배치

### SurfaceView 라운드 코너
- `SurfaceView`는 `clip()` 안 먹힘 (별도 window)
- **Discord 방식**: Canvas `clipPath(roundRect, ClipOp.Difference)`로 모서리만 배경색으로 덮음

### Android Vector Drawable에서 currentColor 금지
- `strokeColor="currentColor"` → Android에서 안 먹힘 (웹 전용 개념)
- `#FFFFFF`로 고정 후 Compose `Icon(tint=...)` 로 색상 제어

---

*최종 갱신: 2026-03-15*
*author: kodeholic (powered by Claude)*
