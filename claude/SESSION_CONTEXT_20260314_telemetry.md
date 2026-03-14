# 세션 컨텍스트 — 2026-03-14 (텔레메트리 Android 포팅)

> **Android SDK 텔레메트리 수집/전송 구현 완료 — Home telemetry.js 1:1 Kotlin 포팅, Conference + PTT 양쪽 검증 완료**

---

## 이번 세션 완료 작업

### 1. doc/CAUTIONS.md 작성 ✔️
- 전체 세션에서 삽질 끝에 확인된 주의사항 28개 항목 정리
- 6개 카테고리: libwebrtc 크래시 방지 / SDP·시그널링 / Android 플랫폼 / Compose UI / 빌드·환경 / 서버 연동

### 2. Telemetry.kt 신규 작성 ✔️ (telemetry/ 패키지)
- Home `telemetry.js` 1:1 Kotlin 포팅
- 구간 S-1: SDP m-line 파싱 + 1회 보고
- 구간 S-2: `codecs[]` 배열 — outbound-rtp encoderImpl/fps/qualityLimitReason + inbound-rtp decoderImpl/fps
- 구간 A: publish outbound-rtp + candidate-pair, delta bitrate/packetsSent/nack/rtx/qld
- 구간 C: subscribe inbound-rtp, delta bitrate/packetsReceived/lost/lossRate/jitterBuffer
- 이벤트 타임라인: publish 6종 + subscribe 5종 상태 전이 감지, 링버퍼 50개
- PTT 진단: track 건강성(enabled/readyState/label) + sender 상태(active/maxBitrate) + PC 연결 상태
- subTracks 카운트 (total/active/inactive)
- `PeerConnectionProvider` 인터페이스로 PC/SDK 상태 접근 추상화

### 3. MediaSession.kt 접근자 추가 ✔️
- `getPublishPc()` / `getSubscribePc()` — Telemetry getStats() 호출용
- `getSubscribeTracks()` — subTracks 카운트용
- `getPublishSenders()` — PTT 진단 track/sender 정보 수집용

### 4. OxLensClient.kt Telemetry 연동 ✔️
- `telemetry` 필드 + `startTelemetry()` — publish ICE CONNECTED에서 호출
- `pcProviderImpl` — PeerConnectionProvider 구현
  - `resolveSourceUser(ssrc)` — remoteTracks에서 SSRC→userId 매핑
  - `collectPttDiagnostics()` — track/sender/PC 상세 (Home 동일 스키마)
  - `getSubscribeTrackCounts()` — total/active/inactive
- 정리 지점 4곳: disconnect(), onRoomLeft(), onDisconnected(), startTelemetry() 중복 방지

### 5. RTCStats.members 타입 캐스팅 수정 ✔️
- **문제**: `as? Long` → Android libwebrtc가 Integer로 내려줘서 null → 모든 값 0으로 보고
- **수정**: `(as? Number)?.toLong()` / `(as? Number)?.toDouble()` 패턴으로 전체 교체 (40+ 곳)
- `numOrNull()` 헬퍼 추가 — Number이면 그대로, 아니면 JSONObject.NULL
- CAUTIONS.md 1-6번으로 추가

### 6. S-2 코덱 + PTT 진단 보강 ✔️
- `collectCodecStatsFromReport()` — raw stats에서 코덱 정보 추출, Home `codecs[]` 동일 스키마
- PTT 진단에 tracks[] + senders[] 배열 추가 — Home `_collectPttDiagnostics()` 동일 수준

---

## 변경된 파일

```
# SDK (oxlens-sdk)
platform/android/oxlens-sdk/src/main/java/com/oxlens/sdk/
├── telemetry/
│   └── Telemetry.kt          ← ★ 신규 (Home telemetry.js 1:1 포팅)
├── media/
│   └── MediaSession.kt       ← getPublishPc/getSubscribePc/getSubscribeTracks/getPublishSenders 접근자 추가
└── OxLensClient.kt           ← telemetry 필드, startTelemetry(), pcProviderImpl, collectPttDiagnostics 확장

# 문서
doc/CAUTIONS.md                ← ★ 신규 (주의사항 28개 항목, 1-6 RTCStats 캐스팅 추가)
```

---

## 현재 상태

- **Conference 모드 텔레메트리**: 전 구간(S/A/B/C) 정상 수집 + 어드민 표시 ✅
- **PTT 모드 텔레메트리**: 전 구간 + PTT 진단(track/sender) 정상 수집 ✅
- **이벤트 타임라인**: CLI/SFU 양쪽 이벤트 어드민 Unified Timeline에 정상 표시 ✅
- **S-2 코덱 상태**: encoderImpl/decoderImpl/fps 등 어드민 표시 ✅
- **Contract Check**: 전 항목 판정 정상 ✅

---

## 검증된 E2E 시나리오

### Conference (U472=Android, U821=Web)
```
publish: audio 32kbps, video 1504kbps@8fps, target=1.5Mbps ✅
subscribe: audio 33kbps, video 1508kbps, jitter=41ms, jb=46ms ✅
network: rtt=3ms, available_bitrate=3.3Mbps ✅
codecs: U472:pub:video impl=libvpx, U472:sub:video impl=libvpx ✅
```

### PTT (U609=Android, U821=Web)
```
PTT 진단: U609 track:audio enabled=false readyState=LIVE ✅
         U609 sender:audio hasTrack=true active=true ✅
이벤트: ptt_granted/released + pli_burst + loss_burst + video_freeze 타임라인 ✅
```

---

## 다음 세션 작업

### 1순위: PTT 전환 시 RTX 폭증 → NetEQ 붕괴 수정 (서버)
- **현상**: PTT 발화자 전환 시마다 RTX 200~500개 + egress drop 200+ → 오디오 loss_burst 100~200패킷 → NetEQ 붕괴
- **원인**: 전환 시 쌓인 NACK에 대한 RTX 캐시 대량 방출 → egress 채널 포화 → 오디오까지 밀려남
- **해결 방향**: 서버 PTT 전환 시 RTX 큐 flush 또는 전환 직후 RTX 일시 억제
- **관련 코드**: `oxlens-sfu-server/src/transport/udp/egress.rs`, `rtcp.rs`

### 2순위: PTT 영상 표시 지연 개선
- PLI 타이밍, subscribe re-nego 지연, SurfaceViewRenderer 초기화 지연

### 3순위: Conference UI 고도화
- 참가자 그리드 레이아웃 (2x2, 3x3 등)

---

## 주의사항 (다음 세션 Claude에게)

1. **RTCStats.members는 `as? Number` 필수** — `as? Long` 직접 캐스팅하면 값 전부 0. CAUTIONS.md 1-6 참조.
2. **getPublishSenders()는 try-catch 감싸서 반환** — PC dispose 후 접근 시 예외. CAUTIONS.md 1-3 참조.
3. **codecs[] 스키마는 Home과 동일** — pub: encoderImpl/powerEfficient/qualityLimitReason/fps, sub: decoderImpl/fps/ssrc
4. **PTT 진단 tracks[]/senders[] 스키마** — Android libwebrtc `track.state()`는 `MediaStreamTrack.State` enum (.LIVE/.ENDED), 브라우저의 "live"/"ended" 문자열과 다름 → 어드민 표시 시 대소문자 차이 있을 수 있음
5. **PTT 전환 시 RTX 폭증은 서버 이슈** — 텔레메트리로 패턴 확인됨, 서버 egress.rs에서 수정 필요

---

*author: kodeholic (powered by Claude)*
