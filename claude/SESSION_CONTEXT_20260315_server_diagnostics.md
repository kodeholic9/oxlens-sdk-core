# SESSION_CONTEXT — 2026-03-15 서버 진단 + RTX Budget + TRACKS_ACK/RESYNC

> author: kodeholic (powered by Claude)

---

## 세션 목표

1. Lost 버스트 원인 (네트워크 vs PTT gate) 분리
2. Video FPS 변동 (7~24fps) 원인 분석
3. 서버 안정성 확보
4. Subscribe 트랙 누락 이슈 해결 (TRACKS_ACK/RESYNC 프로토콜)

---

## 완료 사항

### 1. Server Relay Counter 추가 (v0.5.4)

**목적:** 클라이언트 getStats()의 lost가 네트워크 로스인지 서버 gate인지 분리

| 카운터 | 파일 | 위치 | 의미 |
|--------|------|------|------|
| `ingress_rtp_received` | ingress.rs | RTP decrypt 성공 직후 | publisher→서버 수신 성공 |
| `egress_rtp_relayed` | ingress.rs | fan-out try_send 성공 | 서버→subscriber relay 성공 |
| `egress_rtcp_relayed` | ingress.rs | SR relay try_send 성공 | SR relay 성공 |

**검증 공식:**
```
ingress_rtp_received = egress_rtp_relayed + ptt_rtp_gated + ptt_video_pending_drop + egress_drop
```

**커밋:** `feat(metrics): add relay counters for network vs gate loss diagnosis`

### 2. RTX Budget — per-subscriber RTX 폭풍 방지

**문제 발견:**
LTE 유저(U306)의 다운링크 패킷 로스 → NACK 폭주 → RTX 1,787~12,027건/3s 생성 → egress 큐 overflow → **정상 참가자(U821/U461)까지 패킷 drop + freeze 연쇄 장애**

**해결:**

| 파일 | 변경 |
|------|------|
| config.rs | `RTX_BUDGET_PER_3S = 200` 상수 추가 |
| participant.rs | `rtx_budget_used: AtomicU64` per-subscriber 카운터 |
| ingress.rs | handle_nack_block에서 budget 체크, 초과 시 RTX drop |
| metrics/mod.rs | `rtx_budget_exceeded` 카운터 + flush JSON |
| transport/udp/mod.rs | flush_metrics에서 3초마다 전원 budget 리셋 |

**커밋:** `fix(rtx): add per-subscriber RTX budget to prevent amplification storm`

### 3. rtx_sent 메트릭 위치 수정

**검증 공식:** `nack_seqs_requested = rtx_sent + rtx_budget_exceeded + rtx_cache_miss`

**커밋:** `fix(metrics): move rtx_sent count after budget check`

### 4. Android 화면 꺼짐 방지

**커밋:** `fix(demo): keep screen on while in room`

### 5. TRACKS_ACK + TRACKS_RESYNC 프로토콜 — 전체 구현 완료 ✅

**문제:** 3인 방에서 동시 TRACKS_UPDATE 도착 시 `_setupSubscribePc()` 경합 → subscribe mid inactive 고착

**프로토콜:**

| op | 이름 | 방향 | payload |
|---|---|---|---|
| 101 | TRACKS_UPDATE | S→C | `{ action, tracks }` (기존) |
| 16 | TRACKS_ACK | **C→S** | `{ "ssrcs": [primary SSRC 목록] }` |
| 106 | TRACKS_RESYNC | **S→C** | `{ "tracks": [전체 트랙 목록] }` |

**주의: op=106 사용** (104=VIDEO_SUSPENDED, 105=VIDEO_RESUMED 이미 사용)

**흐름:**
```
[입장] ROOM_JOIN 응답 → media.setup() → sendTracksAck()
[트랙 변경] TRACKS_UPDATE → onTracksUpdate() → sendTracksAck()
                                                    ↓
서버: client SSRC set == expected? → { synced: true }
서버: 불일치 → TRACKS_RESYNC { tracks: [전체목록] }
                                                    ↓
[복구] TRACKS_RESYNC → subscribe PC close → 트랙 통째 교체(mid 재배치) → PC 재생성 → sendTracksAck()
```

**모드별 expected SSRC 계산:**
- Conference: 다른 참가자들의 실제 primary SSRC (RTX 제외)
- PTT: 가상 SSRC 2개 (audio_rewriter.virtual_ssrc + video_rewriter.virtual_ssrc)

**구현 파일:**

| 레포 | 파일 | 변경 |
|------|------|------|
| **sfu-server** | opcode.rs | `TRACKS_ACK=16`, `TRACKS_RESYNC=106` |
| | message.rs | `TracksAckRequest { ssrcs: Vec<u32> }` |
| | handler.rs | `handle_tracks_ack` — expected set 계산 + diff → RESYNC 전송 |
| | metrics/mod.rs | `tracks_ack_mismatch`, `tracks_resync_sent` 카운터 |
| | lib.rs | `recursion_limit = "256"` (json! 매크로 29필드 안전장치) |
| **oxlens-home** | constants.js | opcode 2개 |
| | signaling.js | TRACKS_RESYNC 이벤트 + TRACKS_ACK 응답 로깅 |
| | media-session.js | `onTracksResync()`, `sendTracksAck()`, `_queueSubscribePc()` 직렬화 큐 |
| | client.js | `_onTracksResync()`, ACK 전송 3곳 (join/update/resync) |
| | admin/render-panels.js | `ack_mis`, `resync` 패널 표시 |
| | admin/app.js | `sfu_tracks_mismatch`, `sfu_tracks_resync` 이벤트 로그 |
| **sdk-core (Kotlin)** | signaling/Opcode.kt | opcode 2개 + name() |
| | signaling/Message.kt | `buildTracksAck(ssrcs)` |
| | signaling/SignalClient.kt | dispatch + `SignalListener.onTracksResync()` |
| | media/MediaSession.kt | `closeSubscribePc()` |
| | OxLensClient.kt | `sendTracksAck()`, `onTracksResync`, ACK 3곳 |

**핵심 개선 — subscribe re-nego 직렬화:**
Web: `_queueSubscribePc()` Promise chain으로 동시 TRACKS_UPDATE 순차 처리
Android: MediaSession.closeSubscribePc() → setupSubscribePc() 순차 호출

**커밋:**
- 서버: `feat(signaling): add TRACKS_ACK/RESYNC protocol for subscribe track sync`
- 웹: `feat(signaling): add TRACKS_ACK/RESYNC client protocol for subscribe track sync`
- 안드로이드: `feat(android): add TRACKS_ACK/RESYNC protocol for subscribe track sync`

---

## 텔레메트리 분석 결과

### Lost 버스트 원인

- **PTT gate가 아님** — `ptt_rtp_gated=0` 확인 (Conference 모드 테스트)
- **네트워크/BWE 원인 2가지:**
  1. BWE 초기 수렴 오버슈트 (입장 후 30초~2분, 안정화 후 해소)
  2. LTE 다운링크 로스 → RTX 증폭 → egress overflow (RTX budget으로 해결)

### Video FPS 변동 원인

| 원인 | 심각도 | 대응 |
|------|--------|------|
| CPU 한계 (libvpx SW, enc_time 28ms) | 높 | 해상도/target 낮추기 or HW 코덱 |
| 낮은 bitrate target (800k) | 중 | maxBitrate 1.5M으로 상향 (적용 완료) |
| Tab hidden 브라우저 스로틀링 | 낮 | 측정 아티팩트, 실제 로스 아님 |

---

## 미해결 / 다음 세션

### 1. 메트릭 JSON 분할 리팩터링 (예정)

`counters_json` 29필드 → `json!` 매크로 재귀 한계(128) 아슬아슬.
현재 `recursion_limit = "256"`으로 안전장치만 걸어둔 상태.

**계획:** 3그룹 논리 분할
- `counters_media` — ingress/egress/relay/rtx/cache (~12개)
- `counters_rtcp` — nack/pli/sr/rr/twcc/remb (~12개)
- `counters_sync` — tracks_ack_mismatch, tracks_resync_sent + spawn 레거시 (~5개)

어드민 대시보드 파싱도 flat → nested 구조 변경 필요.

### 2. TRACKS_ACK/RESYNC 실전 검증

3인 방 입퇴장 반복 테스트 후 서버 로그에서 확인:
- `TRACKS_ACK ok` — 정상 동기화
- `TRACKS_ACK mismatch` + `TRACKS_RESYNC sent` — 복구 동작
- 어드민 메트릭: `tracks_ack_mismatch`, `tracks_resync_sent` 수치

이상적: 둘 다 0. 반복 발생 시 클라이언트 subscribe race 여전히 존재.

### 3. Rust SDK crate 코드 원복 완료

Kotlin SDK가 주력이므로 Rust crate (oxlens-core) 4파일 변경 원복:
```
git restore crates/oxlens-core/src/client.rs crates/oxlens-core/src/signaling/client.rs crates/oxlens-core/src/signaling/message.rs crates/oxlens-core/src/signaling/opcode.rs
```

---

## 서버 버전 히스토리 (이 세션)

| 커밋 | 내용 |
|------|------|
| `feat(metrics): add relay counters` | ingress/egress/rtcp relay 카운터 3개 |
| `fix(rtx): add per-subscriber RTX budget` | RTX_BUDGET_PER_3S=200, 폭풍 방지 |
| `fix(metrics): move rtx_sent after budget` | rtx_sent 카운트 위치 수정 |
| `feat(signaling): add TRACKS_ACK/RESYNC` | op=16/106, Conference+PTT 양쪽 지원 |

---

*author: kodeholic (powered by Claude)*
