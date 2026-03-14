# 세션 컨텍스트 — 2026-03-15 (Kotlin SDK Phase 2 완료)

> **Publish PC ICE CONNECTED 확인, Subscribe PC 코드 완성 — 2-client E2E 테스트 대기**

---

## 이번 세션 완료 작업

### 1. Kotlin SDK 스캐폴딩 ✔️
- 기존 JNI 기반 oxlens-sdk → 순수 Kotlin 전면 재작성
- signaling/, media/, ptt/ 패키지 구조

### 2. 시그널링 E2E ✔️
- SignalClient.kt (OkHttp WebSocket) — HELLO→IDENTIFY→HEARTBEAT 자동
- ROOM_CREATE → ROOM_JOIN → PUBLISH_TRACKS 통과

### 3. SDP 빌더 포팅 ✔️
- SdpTypes.kt — ServerConfig, IceConfig, DtlsConfig, CodecConfig, TrackDesc 등
- SdpBuilder.kt — publish/subscribe/PTT SDP 생성 (Rust builder.rs 1:1 포팅)

### 4. Publish PC ✔️ (ICE CONNECTED 확인)
- PeerConnectionFactory 초기화
- Publish PC: SDP 교환 → ICE CONNECTED + COMPLETED
- SSRC 추출 → PUBLISH_TRACKS 자동 전송 → 서버 ACK

### 5. Subscribe PC 코드 완성 ✔️ (E2E 테스트 대기)
- setupSubscribePc() — 초기 트랙으로 subscribe PC 생성 + SDP 교환
- updateSubscribeTracks(action, tracks) — re-nego (add/remove)
- onAddTrack → AudioTrack 자동 재생 (setEnabled=true)
- mid 순차 할당 + inactive 처리 (m-line 삭제 불가 → port=0)

### 6. 기타
- network_security_config.xml — cleartext 허용 (ws://)
- RECORD_AUDIO 퍼미션 미요청 — AudioRecord 에러 발생하지만 ICE 연결에 영향 없음

---

## 현재 상태

### 동작 확인됨
```
connect → HELLO → IDENTIFY → ROOM_CREATE → ROOM_JOIN
→ server_config 파싱 → PeerConnectionFactory 초기화
→ Publish PC SDP 교환 → ICE CONNECTED → PUBLISH_TRACKS ACK
```

### 코드 완성, 테스트 대기
```
TRACKS_UPDATE(add) → remoteTracks 갱신 → Subscribe PC 생성
→ subscribe SDP 조립 → setRemoteDescription → createAnswer → setLocalDescription
→ ICE → DTLS → SRTP → onAddTrack → AudioTrack 재생
```

### 알려진 이슈
- AudioRecord 에러: RECORD_AUDIO 런타임 퍼미션 미요청
- Subscribe PC: 코드 완성, 2-client E2E 테스트 필요

---

## 파일 목록

```
platform/android/
├── build.gradle.kts                  ← android library 플러그인 추가
├── demo-app/
│   ├── build.gradle.kts              ← libwebrtc 중복 제거
│   ├── src/main/AndroidManifest.xml  ← networkSecurityConfig
│   ├── src/main/res/xml/network_security_config.xml
│   └── src/.../MainActivity.kt       ← context 파라미터 추가
└── oxlens-sdk/
    ├── build.gradle.kts              ← OkHttp + libwebrtc
    ├── libs/libwebrtc.aar
    └── src/main/java/com/oxlens/sdk/
        ├── OxLensClient.kt           ← 시그널링 + publish/subscribe 오케스트레이터
        ├── OxLensEventListener.kt
        ├── signaling/
        │   ├── Opcode.kt
        │   ├── Message.kt
        │   └── SignalClient.kt
        ├── media/
        │   ├── SdpTypes.kt           ← ServerConfig, TrackDesc, RoomJoinResponse
        │   ├── SdpBuilder.kt         ← publish/subscribe/PTT SDP 생성
        │   └── MediaSession.kt       ← Publish PC + Subscribe PC (2PC)
        └── ptt/
            └── FloorFsm.kt           ← placeholder
```

---

## 다음 세션 작업

### 1순위: 2-client Subscribe E2E 테스트
- Android 앱으로 방 생성 + 입장
- oxlens-home 브라우저로 같은 방 입장 + PUBLISH_TRACKS
- Android Logcat에서 확인:
  - `tracks update: action=add count=1`
  - `subscribe PC created`
  - `subscribe ICE: CONNECTED`
  - `subscribe onAddTrack: kind=audio`
  - `remote audio track enabled`

### 2순위: RECORD_AUDIO 런타임 퍼미션
- MainActivity에서 ActivityCompat.requestPermissions() 추가
- 퍼미션 승인 후 connect() 호출
- 실제 마이크 → 서버 → 상대방 스피커 E2E 음성 테스트

### 3순위: PTT Floor Control (Phase 3)
- FloorFsm.kt 구현
- requestFloor() → 마이크 활성화, releaseFloor() → 비활성화

---

## 기술 메모

### 타이밍 (확인됨)
- ROOM_JOIN 응답 → publish PC SDP 교환: ~45ms
- ICE CHECKING → CONNECTED: ~80ms
- 전체 connect() → ICE CONNECTED: ~1.2s

### Subscribe PC re-nego 설계
- m-line은 SDP에서 삭제 불가 → 제거된 트랙은 active=false(port=0, inactive)
- 새 트랙은 nextMid++ 로 순차 할당
- BUNDLE에는 active m-line만 포함 (SdpBuilder.collectBundleMids)
- re-nego = 전체 SDP 재조립 (Rust update_subscribe_remote_sdp와 동일)

### PeerConnectionFactory 공유
- publish PC와 subscribe PC가 같은 factory 인스턴스 공유
- factory.dispose()는 양쪽 PC dispose() 후에만 호출

---

*author: kodeholic (powered by Claude)*
