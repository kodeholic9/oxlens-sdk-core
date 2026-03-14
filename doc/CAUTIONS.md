# OxLens SDK — 주의사항 모음 (CAUTIONS)

> 여러 세션에서 삽질 끝에 확인된 사항들.
> 새 세션 시작 시 이 파일을 반드시 읽을 것.

---

## 1. libwebrtc 네이티브 크래시 방지

### 1-1. EGL 컨텍스트는 반드시 공유

- ViewModel에서 `EglBase.create()` **1개만** 생성
- `PeerConnectionFactory` + `Camera2Capturer` + 모든 `SurfaceViewRenderer`가 동일한 `eglBaseContext` 사용
- **별도 `EglBase.create()` 절대 금지** — SurfaceViewRenderer와 EGL 충돌 → SIGABRT
- 레퍼런스: livekit-android, hmu2020, webrtc-android-codelab 전부 동일 패턴

### 1-2. PeerConnectionFactory에 VideoCodecFactory 필수

- `DefaultVideoEncoderFactory(eglContext, true, true)` — 3인자 시그니처
- `DefaultVideoDecoderFactory(eglContext)`
- **`SoftwareVideoEncoderFactory` 사용 금지** — EGL 충돌 + HW 인코더 못 씀
- 미설정 시 video m-line 처리에서 빈 코덱 벡터 접근 → SIGABRT (`front() called on an empty vector`)

### 1-3. pc.senders 직접 순회 금지

- `peerConnection.senders` 순회 시 libwebrtc 내부에서 이미 dispose된 sender 접근 → SIGABRT
- **해결**: `addTrack()` 반환값을 `videoSender` 필드에 캐시, 이후 캐시된 참조 사용
- `applyMaxBitrate()` 등에서 캐시 사용 + try-catch 방어

### 1-4. disableNetworkMonitor = true

- ICE-Lite 구조에서 NetworkMonitor 불필요
- 활성화 시 크래시 사례 있음 (LiveKit #415)
- `PeerConnectionFactory.Options`에서 명시적 비활성화

### 1-5. EglBase dispose 관리

- `startCamera()`에서 생성한 리소스는 `stopCamera()`에서 정리
- Activity `onDestroy()`에서 전체 EglBase release
- **현재 상태**: 누수 가능성 있음 — 장기 사용 시 메모리 증가 주의

### 1-6. RTCStats.members 타입 캐스팅은 `as? Number` 사용

- Android libwebrtc는 `RTCStats.members` 값을 `Integer`, `Long`, `Double`, `BigInteger` 등 다양한 타입으로 내려줌
- **`as? Long` 직접 캐스팅 금지** — 실제 `Integer`로 오면 null 반환 → 모든 값이 0으로 보고됨
- 정수: `(r.members["bytesSent"] as? Number)?.toLong() ?: 0L`
- 실수: `(r.members["jitter"] as? Number)?.toDouble() ?: 0.0`
- JSON 삽입용: `numOrNull()` 헬퍼 — `Number`이면 그대로, 아니면 `JSONObject.NULL`
- **실제 사고**: 텔레메트리 최초 배포 시 publish stats가 전부 0으로 보고됨 — `as? Long` → `as? Number` 수정으로 해결

---

## 2. SDP / 시그널링 규칙

### 2-1. 서버 시그널링 필드명 확인 필수

- `FLOOR_TAKEN` 이벤트: 서버는 `"speaker"` 필드 전송 (서버 `handler.rs`에서 확인)
- SDK에서 `"user_id"`로 파싱하면 빈 문자열 → 매칭 실패
- **원칙**: 서버 코드와 SDK 파싱 필드명이 일치하는지 항상 교차 확인

### 2-2. PTT subscribe SDP에 pttVirtualSsrc 필수

- `OxLensClient.setupSubscribe()`에 `pttVirtualSsrc` 전달 필수
- null이면 Conference SDP로 빌드됨 → 서버 PTT rewriter 가상 SSRC와 불일치 → 디코딩 안 됨
- `onRoomJoined`에서 `pttVirtualSsrc` 저장 → `setupSubscribe()`에 전달

### 2-3. RTX SSRC 필터링

- `extractPublishedSsrcs()`에서 `a=ssrc-group:FID` 2-pass 파싱으로 RTX SSRC 제외
- 미필터링 시 PUBLISH_TRACKS에 video SSRC 2개 전송 (primary + RTX)
- 서버가 unknown SSRC 무시하므로 당장 동작에 문제 없지만 정리 필수

### 2-4. Subscribe SDP mid 할당

- `nextMid` 카운터는 새 트랙 추가 시만 increment, **절대 reset 안 함** (room exit 제외)
- 트랙 제거: `active = false` (port=0, inactive), mid 보존
- m-line은 SDP에서 삭제 불가 → re-nego = 전체 SDP 재조립

### 2-5. BUNDLE 그룹 규칙

- active(sendonly) m-line만 BUNDLE에 포함
- inactive(port=0) m-line은 BUNDLE에서 제외
- 모든 m-line이 inactive면 첫 번째 mid를 BUNDLE에 넣음 (SDP 유효성)

### 2-6. Subscribe demux 방식

- subscribe SDP에서 `sdes:mid` extmap 제거
- Chrome이 SSRC 기반 demux로 fallback
- 각 m-line에 SSRC 선언 필수 (sendonly 시)

### 2-7. Video extmap 필터링

- video m-line에서 `ssrc-audio-level` 등 audio 전용 extmap 제거 필수
- audio 전용을 video에 넣으면 크래시 가능

---

## 3. Android 플랫폼 특성

### 3-1. 런타임 퍼미션

- `RECORD_AUDIO` + `CAMERA` + `BLUETOOTH_CONNECT` — MainActivity에서 동시 요청
- 퍼미션 승인 후 connect() 호출

### 3-2. 화질 프리셋 적용 시점

- **"다음 입장 시" 적용** (Home과 동일) — 실시간 변경 아님
- `joinSelectedRoom()`에서 preset → `OxLensClient` 프로퍼티 세팅

### 3-3. maxBitrate 적용 패턴

- `RtpSender.getParameters()` → `encodings[0].maxBitrateBps = value` → `setParameters()`
- publish PC의 video sender(캐시된 참조)에 적용
- ICE CONNECTED 후 적용 (`pendingMaxBitrateBps` 패턴)

### 3-4. 오디오 장치는 즉시 적용

- `selectDevice()` / `selectSpeaker()` / `selectEarpiece()` 호출 즉시 라우팅 변경
- Android는 스피커 선택 시 마이크 자동 전환 — 마이크 별도 선택 UI 불필요

### 3-5. 카메라 열거

- Android는 Web처럼 deviceId로 선택 불가 — 전면/후면 전환만 (`switchCamera()`)
- `Camera2Enumerator`로 전면/후면 감지

### 3-6. 비디오는 setupPublishPc 내부 자동 시작

- `server_config.codecs`에 Video kind 존재 여부로 자동 판단
- 2PC 구조에서는 **SDP 교환 전에 track이 있어야** localDescription에 SSRC 포함
- SDP 교환 후 addTrack하면 re-negotiation 없이 SSRC 반영 안 됨

### 3-7. 로컬 프리뷰 연결 타이밍

- `onPublishReady` (publish ICE CONNECTED) 시점에 SurfaceViewRenderer 연결
- 카메라 초기화 비동기이므로 enterRoomUi 시점은 너무 이름

### 3-8. libwebrtc AAR 노출

- `implementation` → `api` — 데모앱에서 `org.webrtc.*` 직접 참조 가능

---

## 4. Compose UI

### 4-1. WebRtcSurface는 key()로 안정화

- `key(userId)`로 참가자 타일 안정화 — 추가/제거 시 `SurfaceViewRenderer` 재생성 방지
- `key("local-pip")`로 로컬 PIP 안정화

### 4-2. SDK 콜백은 OkHttp 워커 스레드

- `MutableStateFlow.value` 직접 변경은 thread-safe
- UI 갱신이 필요한 작업은 메인 스레드 전환 고려

### 4-3. Toast는 Home과 동일하게 3개만

- `joinSelectedRoom()` → media: "카메라/마이크 준비 중…"
- `onRoomJoined` → ice: "미디어 연결 중…"
- `onPublishReady` → ok: "미디어 연결 완료"
- 과도한 toast 남발 금지

### 4-4. 리모트 비디오 트랙 userId 추출

- trackId에서 추출: `"{userId}_{mid}"` 포맷 의존
- 포맷 변경 시 매칭 로직도 수정 필요

---

## 5. 빌드 / 환경

### 5-1. Rust 1.93 + Android cdylib = getauxval 크래시

- Rust 1.93이 aarch64-linux-android에 outline-atomics 기본 활성화
- `compiler_builtins`의 `getauxval` stub이 libc의 진짜 함수를 가림 → SIGSEGV
- **해결**: `lse_init.c`에서 `dlsym(RTLD_NEXT, "getauxval")`로 libc 함수 직접 호출
- 직접 `getauxval()` 호출하면 안 됨 — .so 내부 stub으로 resolve됨
- LiveKit 포함 아무도 이 조합을 production에서 안 씀 → **현재 Kotlin 전환으로 우회**

### 5-2. webrtc-sys 버전

- `webrtc-sys = "0.2"` only (`livekit-webrtc 0.2`가 끌어옴)
- `webrtc-sys = "0.3"` 제거 — prebuilt 충돌
- webrtc-rs 크레이트 **0.18+ 절대 사용 금지** — Sans-IO 전환으로 API 완전히 다름

### 5-3. TLS 백엔드

- `tokio-tungstenite`: `native-tls` → `rustls-tls-native-roots`로 전환
- OpenSSL 크로스 빌드 실패 문제 해결

### 5-4. libunwind 우회 (Android .so 빌드 시)

- NDK 호스트용 `libunwind.so`를 빌드 전 rename → 빌드 후 복원
- `scripts/build-android.sh`에 자동화됨

### 5-5. webrtc-sys 0.2.0 build.rs 패치

- WSL cargo registry의 `webrtc-sys-0.2.0/build.rs` 182번 줄 근처
- NDK sysroot aarch64 + clang lib 경로 추가 필요
- cargo cache 초기화 시 재패치 필요

### 5-6. 크로스 바운더리 결정 원칙

- "이미 성공한 사례가 있는지" 먼저 확인
- 없으면 그 이유를 파악한 뒤 진행 여부를 보고
- Rust+Android cdylib+libwebrtc static link 조합에서 선례 없이 삽질한 경험에서 나온 원칙

---

## 6. 서버 연동

### 6-1. MBCP 이중 경로

- 서버 `ingress.rs`는 WS 시그널링 + MBCP over RTCP APP(PT=204) UDP 이중 Floor Control 경로 구현 완료
- 현재 SDK는 WS 경로만 사용
- **주의**: 서버 코드 직접 확인 없이 "구현 안 됐다"고 판단하지 말 것

### 6-2. PLI 패킷 구조 (12바이트)

```
Byte 0: 0x81 (V=2, P=0, FMT=1)
Byte 1: 0xCE (PT=206 PSFB)
Bytes 2-3: 0x0002 (length=2)
Bytes 4-7: 0x00000000 (sender SSRC)
Bytes 8-11: media_ssrc (big-endian)
```

### 6-3. Opus DTX > PT=13

- Comfort Noise(PT=13) 더미 패킷 대신 Opus DTX silence 프레임 사용
- NetEQ 모드 전환 레이턴시 회피

### 6-4. 버그픽스는 전체 경로 점검

- "땜방식이 아닌 전체를 훑어보고 빠짐없이 잡아야 한다"
- PLI burst cancel을 모든 퇴장 경로에 적용한 사례: cleanup, ROOM_LEAVE, floor timer revoke, zombie reaper, 새 burst 시 이전 cancel

### 6-5. 클라이언트 탓 핑퐁 방지

- 네트워크가 정상으로 보일 때 클라이언트 탓으로 돌리면 근본 원인 분석 불가
- 경로별 손실 분리로 SFU 상/하류를 명확히 구분

---

*최종 갱신: 2026-03-14*
*author: kodeholic (powered by Claude)*
