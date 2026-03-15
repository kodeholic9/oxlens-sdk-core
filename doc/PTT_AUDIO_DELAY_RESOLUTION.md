# PTT 오디오 지연 해결 과정 — 기술 기록

> 2026-03-14 ~ 03-15, ~18시간 디버깅 세션.
> NetEQ jitter buffer 폭주 현상의 원인 분석, 시도한 접근법, 실측 데이터, 최종 해결책.

---

## 1. 증상

PTT 모드에서 화자 전환을 반복하면 **수신 오디오 지연이 단조 증가**.
30초 만에 1초 이상 누적되어 체감상 "말한 뒤 한참 뒤에 들림".
한 번 누적된 지연은 **절대 줄어들지 않음**.

---

## 2. 측정 환경

- 서버: Rust SFU (oxlens-sfu-server v0.5.4), WSL2 Ubuntu
- 클라이언트: Android SDK (Kotlin), Samsung Galaxy A23 (arm64)
- libwebrtc: 커스텀 빌드 AAR (oxlens-custom 브랜치)
- 네트워크: 동일 LAN (192.168.0.x), WiFi
- 측정: Android getStats() → [RX:AUDIO] logcat 출력

### 측정 지표

| 지표 | 의미 |
|------|------|
| jbDelay | jitter buffer delay — NetEQ가 패킷을 버퍼에 보관하는 시간 (ms) |
| shrink | NetEQ가 버퍼를 축소한 횟수 (0이면 한 번도 줄이지 않음) |
| lost | 수신 안 된 것으로 집계된 패킷 수 |
| conceal% | concealment ratio — NetEQ가 보간/묵음으로 채운 비율 |

---

## 3. 시도 1 — C++ AudioInterceptor (실패)

### 가설

서버 ptt_rewriter가 화자 전환 시 seq/ts를 연속으로 리라이팅하지만, idle 구간에 패킷이 없으면 NetEQ가 "패킷 손실"로 판단할 수 있다. 클라이언트에서 idle 구간에 Opus DTX silence를 주입하면 NetEQ가 연속 스트림으로 인식할 것.

### 구현

- WSL에서 libwebrtc C++ 소스에 `AudioInterceptorImpl` 추가
- `audio_receive_stream.cc`의 `OnRtpPacket()` 파이프라인에 hook
- 로컬 20ms 타이머로 silence 프레임 주입
- `last_output_seq_/ts_` 추적으로 offset 보정

### 실측 (LogFilter_20260315_005022 — interceptor ON)

| 시각 | jbDelay | shrink | lost | 비고 |
|------|---------|--------|------|------|
| 00:50 | null | 0 | 0 | 시작 |
| 00:51 | 26ms | 0 | 0 | |
| 00:52 | 225ms | 0 | 55 | silence cycle 51회 |
| 00:53 | 375ms | 0 | 0 | |
| 00:54 | 638ms | 0 | 87 | |
| 00:55 | **1055ms** | 0 | 0 | 5분 만에 1초 돌파 |

### 실패 원인 — 이중 보정 문제 (구조적)

```
문제의 본질:
  서버 ptt_rewriter → 인코더 clock 기반 연속 ts 출력 (완성품)
  interceptor → 로컬 20ms timer 기반 silence ts 주입 (제2 clock)

  서버 ts: encoder clock (48kHz, drift ≒ 0)
  interceptor ts: System.nanoTime() 기반 (로컬 벽시계)

  두 독립 clock의 offset이 매 화자 전환마다 달라지며,
  이 차이가 NetEQ에 "jitter"로 보임.
```

**결론: interceptor는 구조적으로 해결 불가. 비활성화.**

---

## 4. 시도 2 — 서버 silence flush 3발 (부분 해결)

### 가설

화자가 끝나면 서버에서 Opus DTX silence 3발(60ms)을 보내 NetEQ에게 "스트림 끝"을 알리면, idle 진입 후 cold 상태로 자연스럽게 전환될 것.

### 구현

- `ptt_rewriter.rs` `clear_speaker()` — Audio rewriter만 Opus DTX silence 3프레임 생성
- `handler.rs` — ROOM_LEAVE, FLOOR_RELEASE, disconnect 3곳에서 fan-out

### 실측 (LogFilter_20260315_092959 — interceptor OFF, silence flush ON)

| 시각 | jbDelay | shrink | lost | 비고 |
|------|---------|--------|------|------|
| 09:30 | null | 0 | 0 | 시작 |
| 09:31 | 52ms | 0 | 0 | |
| 09:31 | 357ms | 0 | 94 | 화자 전환 |
| 09:32 | 506ms | 0 | 0 | |
| 09:32 | **1055ms** | 0 | 0 | |
| 09:33 | **1180ms** | 0 | 152 | max |

### 결과

- **NACK list full 해소** (0회)
- **jbDelay 폭주는 여전** — silence 3발(60ms)로는 idle 2~5초 구간을 커버 못 함
- `shrink=0` 유지 — 한 번 키운 buffer를 줄이지 않음

---

## 5. 시도 3 — Dynamic ts_guard_gap (최종 해결)

### 가설 (부장님 아이디어)

> "ts를 확 늘리면 안되?"

NetEQ는 `arrival_time gap`과 `ts gap`의 불일치를 jitter로 해석한다.
고정 ts_gap=960(20ms)에서 idle 3초 후 패킷이 오면:

```
expected_delay = ts_gap / 48000 = 960 / 48000 = 20ms
actual_delay   = 3000ms (wall clock)
jitter         = 3000 - 20 = 2980ms → buffer 3초로 확장!
```

ts gap을 실제 경과 시간에 맞추면:

```
ts_gap         = 3000ms × 48 = 144000 samples
expected_delay = 144000 / 48000 = 3000ms
actual_delay   = 3000ms
jitter         = 0ms → buffer 안 키움!
```

### 구현

```rust
// ptt_rewriter.rs — clear_speaker()에서 시간 기록
s.cleared_at = Some(Instant::now());

// switch_speaker()에서 경과 시간 → dynamic gap 계산
let elapsed_ms = cleared.elapsed().as_millis() as u32;
let gap = elapsed_ms.saturating_mul(48); // 48kHz: 48 samples/ms
let gap = gap.max(self.ts_guard_gap);     // 최소 960 (20ms)

// silence flush가 last_virtual_ts를 이미 +2880 올려놓았으므로
// (dynamic_gap - silence분)을 추가
let silence_ts = TS_GUARD_GAP_AUDIO * SILENCE_FLUSH_COUNT as u32;
let extra_gap = dynamic_ts_gap.saturating_sub(silence_ts);
s.virtual_base_ts = s.last_virtual_ts.wrapping_add(extra_gap);
```

- Audio만 적용 (Video는 키프레임 게이팅이 대신 처리)
- Video rewriter는 기존 `rewrite()` 첫 패킷 처리 방식 유지

### 서버 로그 (실측)

```
[PTT:REWRITE] dynamic ts_gap: idle=9147ms  → ts_gap=439056  (vs fixed=960)
[PTT:REWRITE] dynamic ts_gap: idle=2575ms  → ts_gap=123600  (vs fixed=960)
[PTT:REWRITE] dynamic ts_gap: idle=31881ms → ts_gap=1530288 (vs fixed=960)
[PTT:REWRITE] dynamic ts_gap: idle=1890ms  → ts_gap=90720   (vs fixed=960)
[PTT:REWRITE] dynamic ts_gap: idle=753ms   → ts_gap=36144   (vs fixed=960)
[PTT:REWRITE] dynamic ts_gap: idle=898ms   → ts_gap=43104   (vs fixed=960)
```

### 실측 (LogFilter_20260315_111535 — dynamic ts_gap 적용)

| 시각 | jbDelay | shrink | lost | 비고 |
|------|---------|--------|------|------|
| 11:16:01 | null | 0 | 0 | 새 세션 시작 |
| 11:16:04 | 53ms | 0 | 0 | |
| 11:16:07 | 36ms | 0 | 0 | |
| 11:16:10 | 61ms | 0 | 0 | |
| 11:16:13 | 80ms | 0 | 76 | 화자 전환 |
| 11:16:16 | 78ms | 0 | 54 | |
| 11:16:22 | **27ms** | 0 | 121 | **lost 후에도 즉시 회복** |
| 11:16:31 | 62ms | 0 | 0 | |
| 11:16:34 | 61ms | 0 | 0 | |
| 11:17:11 | 53ms | 0 | 75 | 화자 전환 |
| 11:17:14 | 93ms | 0 | 59 | |
| 11:17:20 | 34ms | 0 | 0 | |
| 11:17:23 | 46ms | 0 | 71 | |
| 11:17:26 | **97ms** | 0 | 0 | **최대값 = 97ms** |

---

## 6. 비교 요약

| 지표 | interceptor ON | silence flush only | **dynamic ts_gap** |
|------|---------------|-------------------|-------------------|
| jbDelay 최대 | 1055ms | 1180ms | **97ms** |
| jbDelay 추이 | 단조 증가 | 단조 증가 | **안정 (누적 없음)** |
| lost 후 회복 | 안 됨 | 안 됨 | **즉시 회복 (27ms)** |
| shrink | 항상 0 | 항상 0 | 항상 0 (불필요) |
| NACK list full | 2회 | 0회 | 0회 |
| 체감 지연 | 있음 (1초+) | 있음 (1초+) | **없음** |

---

## 7. 원리 — 왜 동작하는가

### RTP timestamp의 본질

ts는 절대 시간이 아니라 **샘플 카운터**. Opus 48kHz에서 20ms = 960 samples씩 증가.
NetEQ는 이 간격으로 "다음 프레임 재생 시점"을 계산.

### NetEQ jitter 추정 공식

```
network_jitter = (arrival_time[n] - arrival_time[n-1]) - (ts[n] - ts[n-1]) / sample_rate
```

고정 ts_gap=960일 때:
- `(ts[n] - ts[n-1]) / 48000 = 20ms` (항상)
- idle 3초 후 도착하면 `arrival_time gap = 3000ms`
- `jitter = 3000 - 20 = 2980ms`
- NetEQ: "2980ms 만큼의 jitter를 흡수하려면 buffer를 3초로 키워야 한다"

dynamic ts_gap일 때:
- `ts gap = 3000ms × 48 = 144000 samples`
- `(144000) / 48000 = 3000ms`
- `jitter = 3000 - 3000 = 0ms`
- NetEQ: "jitter 없음, buffer 유지"

### ITU-T G.114 기준

- 150ms 이하: 사용자 인지 불가
- 150~400ms: 인지 가능 허용
- 400ms 이상: 대화 지장

jbDelay 100ms 이내 → 전체 단방향 지연 ~135-175ms → G.114 정상 범위.
실제 체감: "지연이 한 번도 없었다."

---

## 8. 교훈

1. **수신자(NetEQ) 관점에서 역으로 생각하라** — RFC 준수에 집착하면 "패킷으로 빈 구간을 채워야 한다"는 프레임에 갇힌다. NetEQ가 보는 것은 arrival time과 ts gap의 일치 여부다.

2. **ts gap을 실제 경과 시간에 맞추는 것은 RFC 3550 위반이 아니다** — ts gap이 큰 것은 "오래 침묵했다"를 의미하는 완전히 합법적인 RTP.

3. **shrink=0이 핵심 단서였다** — NetEQ가 buffer를 줄이지 않는다는 것은 "줄일 이유가 없다"가 아니라 "줄일 수 없다"는 의미. arrival_time과 ts가 불일치하면 항상 "아직 jitter가 크다"고 판단.

4. **이중 clock 보정은 구조적으로 실패한다** — 서버 encoder clock과 클라이언트 벽시계는 독립. 매 전환마다 offset이 달라져 drift가 누적.

---

*작성: 2026-03-15*
*author: kodeholic (powered by Claude)*
