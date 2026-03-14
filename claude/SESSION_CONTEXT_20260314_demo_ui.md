# 세션 컨텍스트 — 2026-03-14 (데모앱 비디오 UI 전면 개편)

> **Home UI 구조 미러: Conference Grid + PTT View + 다크 테마 + SurfaceViewRenderer E2E**

---

## 이번 세션 완료 작업

### 1. extractPublishedSsrcs RTX SSRC 필터링 ✔️
- 2-pass 파싱: `a=ssrc-group:FID` → RTX SSRC set 수집 → `a=ssrc:` 추출 시 RTX 제외
- PUBLISH_TRACKS: 3 tracks → 2 tracks (audio + video primary only)

### 2. SDK 리스너/클라이언트 확장 ✔️
- `OxLensEventListener` 5개 콜백 추가:
  - `onRemoteVideoTrack(userId, track)` — 리모트 비디오 수신
  - `onRemoteVideoTrackRemoved(userId)` — 리모트 비디오 제거
  - `onParticipantJoined(userId)` — 참가자 입장
  - `onParticipantLeft(userId)` — 참가자 퇴장
  - `onPublishReady()` — publish ICE CONNECTED (로컬 프리뷰 연결 타이밍)
- `OxLensClient`:
  - `onRemoteTrackAdded` → 비디오 트랙을 리스너로 전달 (trackId에서 userId 추출)
  - `onRoomEvent` → participant_joined/left 이벤트 전달
  - `getLocalVideoTrack()` 접근자 추가
  - publish ICE CONNECTED 시 `listener.onPublishReady()` 호출

### 3. 데모앱 전면 개편 ✔️
- **리소스 신규**:
  - `values/colors.xml` — Home 브랜드 컬러 (brand_dark=#0B0F19, brand_surface=#151B2B, brand_rust=#E45B25 등)
  - `values/themes.xml` — `Theme.OxLensDemo` 다크 테마
  - `drawable/` — badge_bg, circle_btn_bg, circle_btn_red, mode_btn_bg, tile_bg
- **AndroidManifest.xml** — 테마 → `Theme.OxLensDemo`
- **build.gradle.kts** — `gridlayout:1.0.0` 의존성 추가
- **activity_main.xml** — 전면 재작성: Header + 연결패널 + 컨트롤바 + Conference Grid + PTT View + 로컬 PIP
- **MainActivity.kt** — 전면 재작성:
  - EglBase 초기화 + SurfaceViewRenderer(로컬/PTT) init
  - Conference Grid: 참가자 동적 타일 생성 (GridLayout + SurfaceViewRenderer per participant)
  - 리모트 비디오 → 타일 바인딩 (addSink)
  - 로컬 프리뷰 PIP (우하단 120x160dp)
  - PTT View: 발화자 전체화면 + IDLE/REQUESTING/TALKING 상태 표시
  - 미디어 컨트롤 바 (마이크/비디오/카메라전환/연결해제)
  - WS 상태 뱃지 (OFF/CONNECTING/READY/모드명)

---

## E2E 동작 확인

### Conference 모드
```
연결 → 모드 선택 → Conference → 방 생성+입장
→ publish ICE CONNECTED → 로컬 프리뷰 연결됨 (PIP)
→ 상대방 입장 → 타일 추가 (1명)
→ 리모트 비디오 수신 → 타일에 바인딩
→ subscribe ICE CONNECTED → 양방향 음성+영상
→ 상대방 퇴장 → 타일 제거 (0명)
```

### RTX 필터링
```
PUBLISH_TRACKS sent: [audio=77131632, video=3582927920] (2 tracks only)
```

---

## 알려진 이슈

- `No package ID ff found for resource ID 0xffffffff` — ImageButton android:tint 속성 경고 (동작 무관, 추후 app:tint로 변경)
- `disconnected: unknown error` (heartbeat timeout) — 1분 무동작 시 서버 타임아웃, 정상 동작
- `onFloorTaken: user=` 빈 문자열 — 서버 이슈 (기존 backlog)
- `EglImage dataspace changed` — libwebrtc 내부 EGL 워닝 (카메라 시작 시 일시적, 정상)

---

## 변경된 파일

```
# SDK (oxlens-sdk)
platform/android/oxlens-sdk/src/main/java/com/oxlens/sdk/
├── OxLensEventListener.kt      ← 5개 콜백 추가
├── OxLensClient.kt              ← 리모트 비디오/참가자 이벤트 전달 + getLocalVideoTrack + onPublishReady
└── media/
    └── MediaSession.kt          ← extractPublishedSsrcs RTX 필터링

# 데모앱 (demo-app)
platform/android/demo-app/
├── build.gradle.kts             ← gridlayout 의존성 추가
├── src/main/AndroidManifest.xml ← 테마 변경
├── src/main/java/.../MainActivity.kt ← ★ 전면 재작성
└── src/main/res/
    ├── layout/activity_main.xml ← ★ 전면 재작성
    ├── drawable/                 ← ★ 5개 신규
    │   ├── badge_bg.xml
    │   ├── circle_btn_bg.xml
    │   ├── circle_btn_red.xml
    │   ├── mode_btn_bg.xml
    │   └── tile_bg.xml
    └── values/
        ├── colors.xml           ← ★ 신규
        └── themes.xml           ← ★ 신규
```

---

## 다음 세션 작업

### 1순위: 데모앱 Compose 전환
- 현재 XML + Activity → Jetpack Compose 전면 재작성
- Home UI 품질을 제대로 따라가려면 Compose가 필수 (XML으로는 한계)
- `SurfaceViewRenderer`는 `AndroidView`로 래핑 — 성능 오버헤드 없음 (SVR이 별도 window surface에 그림)
- 브랜드 컨러 (brand_dark, brand_surface, brand_rust, brand_cyan) 재사용
- build.gradle.kts에 Compose BOM + material3 의존성 추가 필요
- 참고: Home 스크린샷 2장 (`claude/` 또는 세션 컨텍스트 참조)

### 2순위: hard mute 실제 구현
- video hard mute: stopCamera() + dummy track 교체
- video hard unmute: restartCamera() + replaceTrack
- audio hard mute: AudioRecord 정지 검토

### 2순위: 데모앱 UI 정리
- ImageButton tint → app:tint 전환 (경고 제거)
- 아이콘 리소스 커스텀 (현재 android:drawable 기본 아이콘)
- PTT 모드 E2E 테스트 (데모앱에서 PTT 터치 확인)
- 멀티 참가자 그리드 레이아웃 실사 테스트 (2~4명)

### 3순위: EglBase dispose 정리
- stopCamera에서 EglBase dispose
- Activity onDestroy에서 전체 정리

### Backlog
- subscribe SDP 디버그 로그 제거
- HW video codec factory 전환 검토
- 텔레메트리
- FLOOR_TAKEN user_id 빈 문자열 서버 확인

---

## 주의사항 (다음 세션 Claude에게)

1. **로컬 프리뷰는 onPublishReady에서 연결** — enterRoomUi가 아님. 카메라 초기화 비동기이므로 ICE CONNECTED 이후가 안전
2. **EglBase는 Activity 레벨에서 관리** — 모든 SurfaceViewRenderer가 같은 eglBaseContext 공유
3. **리모트 비디오 트랙 userId는 trackId에서 추출** — "{userId}_{mid}" 포맷 의존
4. **GridLayout rebuildGrid()는 전체 재구성** — 참가자 변동마다 removeAllViews + 재생성 (소규모이므로 OK)
5. **tileSurfaces의 SurfaceViewRenderer는 재사용** — rebuildGrid 시 parent에서 detach 후 재배치
6. **RTX SSRC 필터링 적용됨** — PUBLISH_TRACKS에 primary SSRC만 2개 전송

---

*author: kodeholic (powered by Claude)*
