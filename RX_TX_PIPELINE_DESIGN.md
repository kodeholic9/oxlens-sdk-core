# RX_TX_PIPELINE_DESIGN

> oxlens-sdk-core 클라이언트-서버 하이브리드 제어 아키텍처
>
> author: kodeholic (powered by Claude)  
> created: 2025-03-09  
> status: Draft

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

### 2.3 구현 위치 — libwebrtc Dependencies 주입 (Decorator 패턴)

1. libwebrtc 엔진 초기화 시 `Dependencies` 구조체에 Proxy 클래스를 주입
2. 순정 `PacketReceiver`를 내부에 보유한 Proxy가 `DeliverPacket()` 호출을 가로챔
3. offset 보정 후 순정 수신기로 전달
4. SSRC 기반으로 Audio 패킷만 선별하여 더미 주입 대상 판별

> **기술적 가능 여부: ✅ 확정**
> Dependencies 주입은 libwebrtc 공식 확장 포인트. offset 연산은 정수 덧셈 수준으로 성능 부담 없음.

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

### 3.3 Stage 2 — Hard-Mute

- 하드웨어(카메라 capturer, AudioSource) 해제 → 배터리/발열 보호
- RtpSender의 Track은 유지 (`setTrack(null)` 하지 않음)
- 복구 시 하드웨어 재시작 + `replaceTrack()` 필요 → 300ms+ 지연
- 1분 이상 미사용자에게는 허용 가능한 수준

**Stage 2 진입 시 필수 동작:**

- 서버에 "비디오 중단" 시그널 전송
- 서버 → 수신자들에게 전파 → UI를 아바타/placeholder로 전환
- 누락 시 상대방 화면에 마지막 프레임이 얼어붙어 남는 문제 발생

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

## 5. 카메라 웜업 & 키프레임 확보

### 5.1 방식 — 클라이언트 주도

서버가 임의 타이밍에 PLI를 보내는 대신, 클라이언트가 카메라 준비 완료를 서버에 알린다.

### 5.2 시나리오

1. 클라이언트: 카메라 시작
2. 클라이언트: WebRTC 콜백으로 카메라 구동 및 AE 1차 수렴 확인
   - Android: `CameraCapturer.CameraEventsHandler.onFirstFrameAvailable()`
   - iOS: `RTCVideoCapturerDelegate` 첫 프레임 전달 시점
3. 클라이언트 → 서버: **"카메라 준비됨"** 시그널 전송 (opcode 추가)
4. 서버 → 수신자들: PLI 1발 즉시 발사
5. 서버: **150ms 후 보험 PLI 1발** 추가

### 5.3 근거

- 첫 프레임 캡처 = AE 1차 수렴 완료 → 3발이 아닌 2발이면 충분
- 서버의 타이밍 추측이 불필요 → AE/AWB 수렴 전 허공에 PLI 낭비 없음

> **기술적 가능 여부: ✅ 확정**
> 단, 콜백 이름과 가용성은 플랫폼별 확인 필요 (Android/iOS 각각 검증)

---

## 6. 예외 처리: 시그널링(TCP) 단절

- 시그널링 연결 유실 시 **모든 캐시(SDP, DTLS, ICE) 파기**
- 이후 이벤트 발생 시 **Full Cold Start** (신규 SDP 교환) 수행
- 서버-클라이언트 상태 불일치(좀비 세션) 원천 차단
- **타협 없음**

---

## 7. 서버 연동 — 추가 필요 Opcode

| Opcode            | 방향               | 용도                                              |
| ----------------- | ------------------ | ------------------------------------------------- |
| `CAMERA_READY`    | Client → Server    | 카메라 웜업 완료 알림, 서버가 수신자에게 PLI 발사 |
| `VIDEO_SUSPENDED` | Server → Client(s) | 특정 사용자 비디오 중단 알림, UI placeholder 전환 |
| `VIDEO_RESUMED`   | Server → Client(s) | 특정 사용자 비디오 재개 알림, UI 복원             |

---

## 8. 요약 — 기술적 가능 여부 매트릭스

| 항목                                 | 상태      | 비고                    |
| ------------------------------------ | --------- | ----------------------- |
| Rx Audio: Opus silence + offset 보정 | ✅ 확정   | 서버 로직 포팅          |
| Tx Stage 1 (Soft-Mute)               | ✅ 확정   | 표준 API                |
| Tx Stage 2 (Hard-Mute)               | ✅ 확정   | 표준 API + opcode 추가  |
| Tx Stage 3 (Deep Sleep)              | ⚠️ 조건부 | NAT 타임아웃 실측 필요  |
| ICE Ping 동적 조정                   | ⚠️ 조건부 | 통신사별 실측 필요      |
| 카메라 웜업 PLI                      | ✅ 확정   | 플랫폼별 콜백 검증 필요 |
| 시그널링 단절 → Full Cold Start      | ✅ 확정   | 무조건 수행             |
