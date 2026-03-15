# SESSION_CONTEXT — 2026-03-15

> 이 파일은 Claude 세션 간 컨텍스트 유지용. 새 세션 시작 시 반드시 읽을 것.

---

## 세션 요약

2026-03-14 밤 ~ 03-15 오후까지 약 18시간 연속 세션.
PTT 오디오/비디오 품질 문제를 서버+클라이언트 양쪽에서 디버깅.

---

## 완료된 작업

### 서버 (oxlens-sfu-server)

1. **silence flush 3발** — `ptt_rewriter.rs` `clear_speaker()` 반환타입 변경, `handler.rs` 3곳(ROOM_LEAVE, FLOOR_RELEASE, disconnect) fan-out
2. **dynamic ts_guard_gap** — idle 경과 시간 × 48kHz로 ts gap 동적 계산. NetEQ jitter buffer 폭주 완전 해결 (jbDelay max 1180ms → 97ms)
3. **ROOM_JOIN에 floor_speaker 추가** — 중도 참여 시 현재 발화자 정보 전달

### SDK (oxlens-sdk-core)

1. **Subscribe SDP SSRC 불일치 수정** — tracks:0 입장 경로에서 pttVirtualSsrc null 문제
2. **EglRenderer 안정화** — `key("ptt-speaker")`로 매 전환 파괴/재생성 방지 (init 12회→1회)
3. **SurfaceView 잔상 해결** — `offset(x = -2000.dp)` + `clearImage()` + 150ms delay
4. **스피커 on/off 수정** — `selectEarpiece()` → `track.setEnabled(false)` 실제 음소거
5. **PTT 묵시적 스피커 제어** — LISTENING+사용자ON→즉시on, IDLE/TALKING→500ms후off (꼬리 보호)
6. **중도 참여 floor 동기화** — ROOM_JOIN 시 즉시 FloorFsm 반영, subscribe ICE CONNECTED에서 applyPttSpeaker()
7. **LISTENING 중 발언권 요청 차단** — FloorFsm.requestFloor() 반환값 확인, DemoViewModel에서 UI 상태 변경 조건부
8. **Audio receive stats** — Telemetry에 [RX:AUDIO] logcat 출력 (jbDelay, conceal, shrink 등 NetEQ 진단)
9. **Interceptor 비활성화** — C++ AudioInterceptor의 이중 보정이 구조적 문제임을 확인, 서버측 해결로 대체
10. **collectPttDiagnostics 크래시 수정** — signaling thread에서 JNI 호출 제거 (CAUTIONS 1-6)

### 웹 (oxlens-home)

1. **PTT 묵시적 스피커 제어** — `applyPttSpeaker(state)` + `_pttSpeakerTimer` 500ms delay

---

## 잔여 작업

### 1순위

- [ ] **잔상 타이밍 튜닝** — 150ms delay가 적절한지 체감 확인, 필요시 조절
- [ ] **CAUTIONS.md 업데이트** — dynamic ts_gap, EglRenderer key, 묵시적 스피커 등 추가
- [ ] **CHANGELOG.md 업데이트** — 서버 + SDK 양쪽
- [ ] **WebRtcSurface onFirstFrame 제거** — 사용 안 하지만 파라미터 잔존, 정리 필요

### 2순위

- [ ] **긴급발언(priority preemption)** — 서버 `FLOOR_PRIORITY_REQUEST` opcode + 강제 revoke 로직 미구현
- [ ] **lost 버스트 원인** — 화자 전환 시 76~240건 lost, 네트워크 vs gate 타이밍 분석
- [ ] **Video fps 변동** — 7~24fps, PTT video rewriter 키프레임 대기 관련
- [ ] **웹 중도참여 floor 동기화** — app.js에 floor_speaker 처리 미적용

### 3순위

- [ ] **debug AAR 빌드** — native stats 확보 (`is_debug=false` + `rtc_enable_log=true`)
- [ ] **PTT UX 세부 튜닝** — 스피커 off delay 500ms, 잔상 delay 150ms 조절
- [ ] **SW instructor-mode 전체 코드 리뷰**
- [ ] **WebRTC 텔레메트리 3-레이어 프레임워크** — getStats 기반 encoder/decoder 내부 상태

---

## 수정 파일 목록

### 서버
- `src/room/ptt_rewriter.rs` — silence flush, dynamic ts_gap, cleared_at
- `src/signaling/handler.rs` — silence fan-out 3곳, floor_speaker in ROOM_JOIN

### SDK
- `OxLensClient.kt` — setRemoteAudioEnabled, applyPttSpeaker, floorRequest Boolean, mid-join floor, collectPttDiagnostics 크래시 수정
- `MediaSession.kt` — setRemoteAudioEnabled 추가
- `SdpTypes.kt` — RoomJoinResponse.floorSpeaker
- `Telemetry.kt` — [RX:AUDIO] logcat + NetEQ 필드
- `DemoViewModel.kt` — toggleSpeaker, floorRequest accepted
- `OxLensScreen.kt` — PTT video key 안정화, offset, videoReady delay
- `WebRtcSurface.kt` — clearImage, onFirstFrame 콜백

### 웹
- `demo/client/app.js` — applyPttSpeaker, _setAllAudioMuted, PTT 묵시적 스피커 제어

---

## 핵심 기술 발견

### NetEQ jitter buffer 폭주 메커니즘
- NetEQ는 `arrival_time - (timestamp / sample_rate)`로 jitter 추정
- 고정 ts_gap=960(20ms)에서 idle 3초 후 패킷 도착 → jitter=2980ms → buffer 3초로 확장
- `shrink=0` — 한 번 키운 buffer를 절대 줄이지 않음
- **해결**: ts gap을 실제 경과 시간에 맞춤 → jitter=0 → buffer 안 키움

### Interceptor 이중 보정 문제 (구조적)
- 서버 ptt_rewriter가 이미 연속 스트림 생성 (완성품)
- interceptor가 로컬 timer + 서버 clock을 offset 합성 → 두 독립 clock drift 누적

### SurfaceViewRenderer 특성
- `onFirstFrameRendered`는 renderer lifetime에 1회만 호출
- track=null이어도 마지막 프레임이 surface buffer에 남음
- visibility 변경으로 숨길 수 없음 → offset으로 화면 밖 이동

---

*최종 갱신: 2026-03-15*
*author: kodeholic (powered by Claude)*
