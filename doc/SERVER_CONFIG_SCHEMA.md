# SERVER_CONFIG 스키마 — ROOM_JOIN 응답

> SDP 빌더 포팅을 위한 참조 문서
>
> author: kodeholic (powered by Claude)  
> created: 2026-03-09  
> source: oxlens-sfu-server/src/signaling/handler.rs → handle_room_join()

---

## ROOM_JOIN 응답 전체 구조

```json
{
  "op": 11,
  "pid": 3,
  "ok": true,
  "d": {
    "room_id": "room-abc",
    "mode": "conference" | "ptt",
    "participants": ["user1", "user2"],
    "server_config": { ... },
    "tracks": [ ... ],
    "ptt_virtual_ssrc": { ... }       // PTT 모드일 때만
  }
}
```

---

## server_config 상세

```json
{
  "ice": {
    "publish_ufrag": "xxxxxxxx",       // publish PC용 ICE ufrag
    "publish_pwd": "xxxxxxxxxxxxxxxx", // publish PC용 ICE pwd
    "subscribe_ufrag": "yyyyyyyy",     // subscribe PC용 ICE ufrag
    "subscribe_pwd": "yyyyyyyyyyyyyyyy",
    "ip": "203.0.113.1",              // 서버 공개 IP (PUBLIC_IP .env)
    "port": 19740                      // UDP 포트 (config::UDP_PORT)
  },
  "dtls": {
    "fingerprint": "sha-256 AA:BB:CC:...", // 서버 인증서 fingerprint
    "setup": "passive"                      // 항상 passive (서버 = ICE-Lite)
  },
  "codecs": [
    {
      "kind": "audio",
      "name": "opus",
      "pt": 111,
      "clockrate": 48000,
      "channels": 2,
      "rtcp_fb": ["nack"],
      "fmtp": "minptime=10;useinbandfec=1"
    },
    {
      "kind": "video",
      "name": "VP8",
      "pt": 96,
      "clockrate": 90000,
      "rtx_pt": 97,
      "rtcp_fb": ["nack", "nack pli", "ccm fir", "goog-remb"]
    }
  ],
  "extmap": [
    { "id": 1, "uri": "urn:ietf:params:rtp-hdrext:sdes:mid" },
    { "id": 4, "uri": "urn:ietf:params:rtp-hdrext:ssrc-audio-level" },
    { "id": 5, "uri": "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time" },
    { "id": 6, "uri": "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01" }
  ],
  "max_bitrate_bps": 500000
}
```

> extmap id=6 (TWCC)는 BWE_MODE=twcc일 때만 포함. BWE_MODE=remb이면 제외.

---

## tracks (기존 참가자들의 트랙 목록)

```json
[
  {
    "user_id": "user1",
    "kind": "audio",
    "ssrc": 12345678,
    "track_id": "user1_0"
  },
  {
    "user_id": "user1",
    "kind": "video",
    "ssrc": 87654321,
    "track_id": "user1_1",
    "rtx_ssrc": 11111111         // video만, RTX SSRC (있으면)
  }
]
```

---

## ptt_virtual_ssrc (PTT 모드일 때만)

```json
{
  "audio": 4000000001,
  "video": 4000000002
}
```

> PTT 모드에서는 subscribe SDP에 원본 SSRC 대신 가상 SSRC를 선언.
> 서버가 화자 전환 시 SSRC/seq/ts를 리라이팅하므로, 클라이언트는 하나의 연속 스트림으로 인식.

---

## SDP 빌더 포팅 시 참고사항

### 1. 2개의 PeerConnection

| PC | 용도 | SDP direction | ICE credentials |
|----|------|---------------|-----------------|
| publish PC | 내 미디어 → 서버 | 서버 offer = recvonly, 클라이언트 answer = sendonly | publish_ufrag/pwd |
| subscribe PC | 서버 → 내 수신 | 서버 offer = sendonly, 클라이언트 answer = recvonly | subscribe_ufrag/pwd |

### 2. SDP 조립 흐름 (JS 원본 기준)

```
1. ROOM_JOIN 응답 수신 (server_config)
2. buildPublishRemoteSdp(serverConfig) → fake SDP
3. pubPc.setRemoteDescription({ type: 'offer', sdp: pubSdp })
4. pubPc.createAnswer() → answer
5. pubPc.setLocalDescription(answer)
6. (ICE → STUN → DTLS → SRTP)

7. buildSubscribeRemoteSdp(serverConfig, tracks, { mode, pttVirtualSsrc })
8. subPc.setRemoteDescription({ type: 'offer', sdp: subSdp })
9. subPc.createAnswer() → answer
10. subPc.setLocalDescription(answer)
```

### 3. Rust 포팅 대상 (oxlens-home/common/sdp-builder.js)

| JS 함수 | Rust 함수 (예정) | 설명 |
|---------|-----------------|------|
| `buildPublishRemoteSdp()` | `build_publish_remote_sdp()` | publish PC용 fake offer |
| `buildSubscribeRemoteSdp()` | `build_subscribe_remote_sdp()` | subscribe PC용 fake offer (conference) |
| `buildPttSubscribeSdp()` | `build_ptt_subscribe_sdp()` | subscribe PC용 fake offer (PTT) |
| `updateSubscribeRemoteSdp()` | `update_subscribe_remote_sdp()` | re-negotiation용 |
| `buildSessionHeader()` | `build_session_header()` | SDP v= o= s= t= BUNDLE |
| `buildMediaSection()` | `build_media_section()` | 단일 m= 섹션 |
| `validateSdp()` | `validate_sdp()` | 디버깅용 검증 |

### 4. 핵심 주의사항

- subscribe SDP에서 `sdes:mid` extmap **제거** (서버가 RTP mid 헤더를 rewrite 안 함, SSRC 기반 demux)
- inactive m-line은 port=0, BUNDLE에서 제외
- PTT 모드는 트랙 유무와 무관하게 항상 가상 SSRC 선언
- ICE-Lite: `a=ice-lite` 포함, candidate는 서버 IP:port 1개

---

*author: kodeholic (powered by Claude)*
