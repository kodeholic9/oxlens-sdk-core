// author: kodeholic (powered by Claude)
//! SDP builder — server_config JSON → fake remote SDP 조립
//!
//! 서버는 SDP를 모른다. 클라이언트가 이 모듈로 SDP를 만들어
//! setRemoteDescription → createAnswer → setLocalDescription 한다.
//!
//! 포팅 원본: oxlens-home/common/sdp-builder.js

use std::fmt::Write;
use std::time::{SystemTime, UNIX_EPOCH};

use super::types::*;

// ============================================================================
// Public API
// ============================================================================

/// publish PC용 remote SDP 생성 (서버 = recvonly offer)
///
/// 브라우저/libwebrtc는 이 offer에 대해 sendonly answer를 만든다.
/// m-line: audio 1개 + video 1개 (고정)
pub fn build_publish_remote_sdp(config: &ServerConfig) -> String {
    let audio_codecs: Vec<&CodecConfig> = config
        .codecs
        .iter()
        .filter(|c| c.kind == MediaKind::Audio)
        .collect();
    let video_codecs: Vec<&CodecConfig> = config
        .codecs
        .iter()
        .filter(|c| c.kind == MediaKind::Video)
        .collect();

    let ice_cred = IceCred {
        ufrag: &config.ice.publish_ufrag,
        pwd: &config.ice.publish_pwd,
    };

    let mut sections: Vec<MediaSectionResult> = Vec::new();
    let mut mid_counter: u32 = 0;

    // audio m-line
    if !audio_codecs.is_empty() {
        let mid = mid_counter.to_string();
        mid_counter += 1;
        sections.push(build_media_section(&MediaSectionOpts {
            mid: &mid,
            kind: MediaKind::Audio,
            codecs: &audio_codecs,
            extmap: &config.extmap,
            direction: Direction::RecvOnly,
            ice: &ice_cred,
            dtls: &config.dtls,
            ip: &config.ice.ip,
            port: config.ice.port,
            ssrc: None,
            rtx_ssrc: None,
            msid: None,
        }));
    }

    // video m-line
    if !video_codecs.is_empty() {
        let mid = mid_counter.to_string();
        let _ = mid_counter; // suppress unused warning
        sections.push(build_media_section(&MediaSectionOpts {
            mid: &mid,
            kind: MediaKind::Video,
            codecs: &video_codecs,
            extmap: &config.extmap,
            direction: Direction::RecvOnly,
            ice: &ice_cred,
            dtls: &config.dtls,
            ip: &config.ice.ip,
            port: config.ice.port,
            ssrc: None,
            rtx_ssrc: None,
            msid: None,
        }));
    }

    // BUNDLE: active m-line만 포함, inactive(port=0) 제외
    let bundle_mids = collect_bundle_mids(&sections);
    let mut sdp = build_session_header(&bundle_mids);
    for s in &sections {
        sdp.push_str(&s.sdp);
    }
    sdp
}

/// subscribe PC용 remote SDP 생성 (서버 = sendonly offer × N)
///
/// conference 모드: 트랙별 m-line
/// PTT 모드: 가상 SSRC 1쌍으로 2 m-line
pub fn build_subscribe_remote_sdp(
    config: &ServerConfig,
    tracks: &[TrackDesc],
    mode: RoomMode,
    ptt_virtual_ssrc: Option<&PttVirtualSsrc>,
) -> String {
    // PTT 모드 — 가상 SSRC로 단일 스트림 subscribe SDP
    if mode == RoomMode::Ptt {
        if let Some(vssrc) = ptt_virtual_ssrc {
            return build_ptt_subscribe_sdp(config, vssrc);
        }
    }

    let ice_cred = IceCred {
        ufrag: &config.ice.subscribe_ufrag,
        pwd: &config.ice.subscribe_pwd,
    };

    // Conference 모드: 트랙 없으면 최소 SDP (BUNDLE 필수이므로 inactive audio 1개)
    if tracks.is_empty() {
        let audio_codecs: Vec<&CodecConfig> = config
            .codecs
            .iter()
            .filter(|c| c.kind == MediaKind::Audio)
            .collect();
        let section = build_media_section(&MediaSectionOpts {
            mid: "0",
            kind: MediaKind::Audio,
            codecs: &audio_codecs,
            extmap: &config.extmap,
            direction: Direction::Inactive,
            ice: &ice_cred,
            dtls: &config.dtls,
            ip: &config.ice.ip,
            port: config.ice.port,
            ssrc: None,
            rtx_ssrc: None,
            msid: None,
        });
        let mut sdp = build_session_header(&["0".to_string()]);
        sdp.push_str(&section.sdp);
        return sdp;
    }

    // subscribe SDP에서는 sdes:mid extmap 제거
    // → 서버가 RTP mid 헤더 확장을 rewrite 안 하므로,
    //   BUNDLE demux를 SSRC 기반으로 fallback시킴
    let sub_extmap: Vec<ExtmapEntry> = config
        .extmap
        .iter()
        .filter(|e| e.uri != EXTMAP_SDES_MID)
        .cloned()
        .collect();

    let mut sections: Vec<MediaSectionResult> = Vec::new();

    for (idx, track) in tracks.iter().enumerate() {
        let active = track.active;
        let kind = track.kind;
        let track_codecs: Vec<&CodecConfig> =
            config.codecs.iter().filter(|c| c.kind == kind).collect();

        if track_codecs.is_empty() {
            continue;
        }

        // mid: 트랙에 고정 할당된 mid 사용 (re-nego 시 불변 보장)
        let mid_owned;
        let mid: &str = if let Some(ref m) = track.mid {
            m.as_str()
        } else {
            mid_owned = idx.to_string();
            &mid_owned
        };

        let direction = if active {
            Direction::SendOnly
        } else {
            Direction::Inactive
        };

        let msid = if active {
            Some(format!("light-{} {}", track.user_id, track.track_id))
        } else {
            None
        };

        let rtx_ssrc = if active && kind == MediaKind::Video {
            track.rtx_ssrc
        } else {
            None
        };

        sections.push(build_media_section(&MediaSectionOpts {
            mid,
            kind,
            codecs: &track_codecs,
            extmap: &sub_extmap,
            direction,
            ice: &ice_cred,
            dtls: &config.dtls,
            ip: &config.ice.ip,
            port: config.ice.port,
            ssrc: if active { Some(track.ssrc) } else { None },
            rtx_ssrc,
            msid: msid.as_deref(),
        }));
    }

    let bundle_mids = collect_bundle_mids(&sections);
    let mut sdp = build_session_header(&bundle_mids);
    for s in &sections {
        sdp.push_str(&s.sdp);
    }
    sdp
}

/// subscribe PC re-negotiation용 SDP 재조립
///
/// 전체 트랙 목록을 받아 SDP를 처음부터 조립한다.
/// 제거된 트랙은 active: false로 넘기면 inactive m-line이 된다.
///
/// SDP에서 m-line은 삭제 불가 → inactive로 변경만 가능.
pub fn update_subscribe_remote_sdp(
    config: &ServerConfig,
    all_tracks: &[TrackDesc],
    mode: RoomMode,
    ptt_virtual_ssrc: Option<&PttVirtualSsrc>,
) -> String {
    // 재조립 = build_subscribe_remote_sdp와 동일
    build_subscribe_remote_sdp(config, all_tracks, mode, ptt_virtual_ssrc)
}

// ============================================================================
// PTT Subscribe SDP
// ============================================================================

/// PTT 모드 subscribe SDP — 가상 SSRC 1쌍(audio+video)으로 2개 m-line
///
/// Conference와 구조적으로 다름:
/// - Conference: publisher N명 × 2 m-line (audio + video each)
/// - PTT: 가상 audio 1개 + 가상 video 1개 = 2 m-line only
///
/// 서버가 화자 교대 시 SSRC/seq/ts를 리라이팅하므로,
/// libwebrtc은 하나의 연속 스트림으로 인식한다.
fn build_ptt_subscribe_sdp(config: &ServerConfig, vssrc: &PttVirtualSsrc) -> String {
    let audio_codecs: Vec<&CodecConfig> = config
        .codecs
        .iter()
        .filter(|c| c.kind == MediaKind::Audio)
        .collect();
    let video_codecs: Vec<&CodecConfig> = config
        .codecs
        .iter()
        .filter(|c| c.kind == MediaKind::Video)
        .collect();

    // subscribe SDP에서는 sdes:mid extmap 제거 (SSRC 기반 demux)
    let sub_extmap: Vec<ExtmapEntry> = config
        .extmap
        .iter()
        .filter(|e| e.uri != EXTMAP_SDES_MID)
        .cloned()
        .collect();

    let ice_cred = IceCred {
        ufrag: &config.ice.subscribe_ufrag,
        pwd: &config.ice.subscribe_pwd,
    };

    let mut sections: Vec<MediaSectionResult> = Vec::new();

    // PTT audio m-line (mid=0)
    if !audio_codecs.is_empty() {
        sections.push(build_media_section(&MediaSectionOpts {
            mid: "0",
            kind: MediaKind::Audio,
            codecs: &audio_codecs,
            extmap: &sub_extmap,
            direction: Direction::SendOnly,
            ice: &ice_cred,
            dtls: &config.dtls,
            ip: &config.ice.ip,
            port: config.ice.port,
            ssrc: Some(vssrc.audio),
            rtx_ssrc: None,
            msid: Some("light-ptt ptt-audio"),
        }));
    }

    // PTT video m-line (mid=1)
    if let Some(video_ssrc) = vssrc.video {
        if !video_codecs.is_empty() {
            sections.push(build_media_section(&MediaSectionOpts {
                mid: "1",
                kind: MediaKind::Video,
                codecs: &video_codecs,
                extmap: &sub_extmap,
                direction: Direction::SendOnly,
                ice: &ice_cred,
                dtls: &config.dtls,
                ip: &config.ice.ip,
                port: config.ice.port,
                ssrc: Some(video_ssrc),
                rtx_ssrc: None,
                msid: Some("light-ptt ptt-video"),
            }));
        }
    }

    let bundle_mids = collect_bundle_mids(&sections);
    let mut sdp = build_session_header(&bundle_mids);
    for s in &sections {
        sdp.push_str(&s.sdp);
    }
    sdp
}

// ============================================================================
// Internal: Session Header
// ============================================================================

fn build_session_header(mids: &[String]) -> String {
    let session_id = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();

    format!(
        "v=0\r\n\
         o=oxlens-sfu {sid} {sid} IN IP4 0.0.0.0\r\n\
         s=-\r\n\
         t=0 0\r\n\
         a=group:BUNDLE {bundle}\r\n\
         a=ice-lite\r\n",
        sid = session_id,
        bundle = mids.join(" "),
    )
}

// ============================================================================
// Internal: Media Section Builder
// ============================================================================

/// SDP direction
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Direction {
    SendOnly,
    RecvOnly,
    Inactive,
}

impl Direction {
    fn as_str(self) -> &'static str {
        match self {
            Direction::SendOnly => "sendonly",
            Direction::RecvOnly => "recvonly",
            Direction::Inactive => "inactive",
        }
    }

    fn is_inactive(self) -> bool {
        self == Direction::Inactive
    }
}

/// ICE credentials (publish 또는 subscribe)
struct IceCred<'a> {
    ufrag: &'a str,
    pwd: &'a str,
}

/// build_media_section 인자
struct MediaSectionOpts<'a> {
    mid: &'a str,
    kind: MediaKind,
    codecs: &'a [&'a CodecConfig],
    extmap: &'a [ExtmapEntry],
    direction: Direction,
    ice: &'a IceCred<'a>,
    dtls: &'a DtlsConfig,
    ip: &'a str,
    port: u16,
    ssrc: Option<u32>,
    rtx_ssrc: Option<u32>,
    msid: Option<&'a str>,
}

/// 빌드 결과 — BUNDLE mids 수집용
struct MediaSectionResult {
    mid: String,
    sdp: String,
    active: bool,
}

/// 단일 m= 섹션 생성
fn build_media_section(opts: &MediaSectionOpts<'_>) -> MediaSectionResult {
    // PT 목록 수집 (rtx_pt 포함)
    let mut pts: Vec<u8> = Vec::new();
    for c in opts.codecs {
        pts.push(c.pt);
        if let Some(rtx_pt) = c.rtx_pt {
            pts.push(rtx_pt);
        }
    }

    // inactive이면 port=0
    let m_port: u16 = if opts.direction.is_inactive() {
        0
    } else {
        opts.port
    };

    let pt_list: String = pts
        .iter()
        .map(|p| p.to_string())
        .collect::<Vec<_>>()
        .join(" ");

    let mut sdp = String::with_capacity(1024);

    // m= line
    let _ = write!(sdp, "m={} {} UDP/TLS/RTP/SAVPF {}\r\n",
        opts.kind, m_port, pt_list);

    // connection
    let _ = write!(sdp, "c=IN IP4 {}\r\n", opts.ip);

    // ICE
    let _ = write!(sdp, "a=ice-ufrag:{}\r\n", opts.ice.ufrag);
    let _ = write!(sdp, "a=ice-pwd:{}\r\n", opts.ice.pwd);

    // DTLS
    let _ = write!(sdp, "a=fingerprint:{}\r\n", opts.dtls.fingerprint);
    let _ = write!(sdp, "a=setup:{}\r\n", opts.dtls.setup);

    // mid
    let _ = write!(sdp, "a=mid:{}\r\n", opts.mid);

    // rtcp-mux (BUNDLE 필수)
    sdp.push_str("a=rtcp-mux\r\n");

    // rtcp-rsize (video에만)
    if opts.kind == MediaKind::Video {
        sdp.push_str("a=rtcp-rsize\r\n");
    }

    // direction
    let _ = write!(sdp, "a={}\r\n", opts.direction.as_str());

    // codecs: rtpmap + fmtp + rtcp-fb
    for c in opts.codecs {
        // rtpmap — audio에서 channels > 1이면 채널수 포함
        if opts.kind == MediaKind::Audio && c.channels.map_or(false, |ch| ch > 1) {
            let _ = write!(sdp, "a=rtpmap:{} {}/{}/{}\r\n",
                c.pt, c.name, c.clockrate, c.channels.unwrap());
        } else {
            let _ = write!(sdp, "a=rtpmap:{} {}/{}\r\n",
                c.pt, c.name, c.clockrate);
        }

        // fmtp
        if let Some(ref fmtp) = c.fmtp {
            let _ = write!(sdp, "a=fmtp:{} {}\r\n", c.pt, fmtp);
        }

        // rtcp-fb
        for fb in &c.rtcp_fb {
            let _ = write!(sdp, "a=rtcp-fb:{} {}\r\n", c.pt, fb);
        }

        // RTX codec
        if let Some(rtx_pt) = c.rtx_pt {
            let _ = write!(sdp, "a=rtpmap:{} rtx/{}\r\n", rtx_pt, c.clockrate);
            let _ = write!(sdp, "a=fmtp:{} apt={}\r\n", rtx_pt, c.pt);
        }
    }

    // extmap
    for ext in opts.extmap {
        let _ = write!(sdp, "a=extmap:{} {}\r\n", ext.id, ext.uri);
    }

    // SSRC + msid (sendonly일 때만)
    if let Some(ssrc) = opts.ssrc {
        let _ = write!(sdp, "a=ssrc:{} cname:oxlens-sfu\r\n", ssrc);
        if let Some(msid) = opts.msid {
            let _ = write!(sdp, "a=ssrc:{} msid:{}\r\n", ssrc, msid);
        }

        // RTX SSRC (video only, RFC 4588)
        if let Some(rtx_ssrc) = opts.rtx_ssrc {
            if opts.kind == MediaKind::Video {
                let _ = write!(sdp, "a=ssrc:{} cname:oxlens-sfu\r\n", rtx_ssrc);
                if let Some(msid) = opts.msid {
                    let _ = write!(sdp, "a=ssrc:{} msid:{}\r\n", rtx_ssrc, msid);
                }
                let _ = write!(sdp, "a=ssrc-group:FID {} {}\r\n", ssrc, rtx_ssrc);
            }
        }
    }

    // ICE candidate (inactive가 아닐 때만)
    if !opts.direction.is_inactive() {
        let _ = write!(sdp,
            "a=candidate:1 1 udp 2113937151 {} {} typ host generation 0\r\n",
            opts.ip, opts.port);
        sdp.push_str("a=end-of-candidates\r\n");
    }

    MediaSectionResult {
        mid: opts.mid.to_string(),
        sdp,
        active: !opts.direction.is_inactive(),
    }
}

// ============================================================================
// Internal: Helpers
// ============================================================================

/// BUNDLE에 포함할 active m-line mids 수집
///
/// inactive(port=0)는 BUNDLE에서 제외해야 Chrome re-nego 통과.
/// BUNDLE이 비어있으면 첫 번째 mid라도 넣어야 SDP 유효.
fn collect_bundle_mids(sections: &[MediaSectionResult]) -> Vec<String> {
    let active: Vec<String> = sections
        .iter()
        .filter(|s| s.active)
        .map(|s| s.mid.clone())
        .collect();

    if active.is_empty() {
        vec![sections
            .first()
            .map(|s| s.mid.clone())
            .unwrap_or_else(|| "0".to_string())]
    } else {
        active
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// 테스트용 기본 server_config 생성
    fn test_server_config() -> ServerConfig {
        ServerConfig {
            ice: IceConfig {
                publish_ufrag: "pub_ufrag".to_string(),
                publish_pwd: "pub_pwd_1234567890".to_string(),
                subscribe_ufrag: "sub_ufrag".to_string(),
                subscribe_pwd: "sub_pwd_1234567890".to_string(),
                ip: "203.0.113.1".to_string(),
                port: 19740,
            },
            dtls: DtlsConfig {
                fingerprint: "sha-256 AA:BB:CC:DD:EE:FF".to_string(),
                setup: "passive".to_string(),
            },
            codecs: vec![
                CodecConfig {
                    kind: MediaKind::Audio,
                    name: "opus".to_string(),
                    pt: 111,
                    clockrate: 48000,
                    channels: Some(2),
                    rtx_pt: None,
                    rtcp_fb: vec!["nack".to_string()],
                    fmtp: Some("minptime=10;useinbandfec=1".to_string()),
                },
                CodecConfig {
                    kind: MediaKind::Video,
                    name: "VP8".to_string(),
                    pt: 96,
                    clockrate: 90000,
                    channels: None,
                    rtx_pt: Some(97),
                    rtcp_fb: vec![
                        "nack".to_string(),
                        "nack pli".to_string(),
                        "ccm fir".to_string(),
                        "goog-remb".to_string(),
                    ],
                    fmtp: None,
                },
            ],
            extmap: vec![
                ExtmapEntry {
                    id: 1,
                    uri: "urn:ietf:params:rtp-hdrext:sdes:mid".to_string(),
                },
                ExtmapEntry {
                    id: 4,
                    uri: "urn:ietf:params:rtp-hdrext:ssrc-audio-level".to_string(),
                },
                ExtmapEntry {
                    id: 5,
                    uri: "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"
                        .to_string(),
                },
            ],
            max_bitrate_bps: Some(500000),
        }
    }

    #[test]
    fn publish_sdp_has_required_fields() {
        let config = test_server_config();
        let sdp = build_publish_remote_sdp(&config);

        assert!(sdp.starts_with("v=0\r\n"), "v=0 header");
        assert!(sdp.contains("a=ice-lite\r\n"), "ice-lite");
        assert!(sdp.contains("a=group:BUNDLE"), "BUNDLE");
        assert!(sdp.contains("m=audio"), "audio m-line");
        assert!(sdp.contains("m=video"), "video m-line");
        assert!(sdp.contains("a=recvonly\r\n"), "recvonly direction");
        assert!(sdp.contains("a=ice-ufrag:pub_ufrag\r\n"), "publish ufrag");
        assert!(sdp.contains("a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF\r\n"), "fingerprint");
        assert!(sdp.contains("a=setup:passive\r\n"), "setup passive");
        assert!(sdp.contains("a=rtpmap:111 opus/48000/2\r\n"), "opus rtpmap");
        assert!(sdp.contains("a=rtpmap:96 VP8/90000\r\n"), "VP8 rtpmap");
        assert!(sdp.contains("a=rtpmap:97 rtx/90000\r\n"), "RTX rtpmap");
        assert!(sdp.contains("a=fmtp:97 apt=96\r\n"), "RTX fmtp");
        assert!(sdp.contains("a=candidate:"), "ICE candidate");
        // recvonly이므로 SSRC 없어야 함
        assert!(!sdp.contains("a=ssrc:"), "no SSRC in recvonly");
    }

    #[test]
    fn subscribe_sdp_conference_with_tracks() {
        let config = test_server_config();
        let tracks = vec![
            TrackDesc {
                user_id: "alice".to_string(),
                kind: MediaKind::Audio,
                ssrc: 12345678,
                track_id: "alice_0".to_string(),
                rtx_ssrc: None,
                mid: None,
                active: true,
            },
            TrackDesc {
                user_id: "alice".to_string(),
                kind: MediaKind::Video,
                ssrc: 87654321,
                track_id: "alice_1".to_string(),
                rtx_ssrc: Some(11111111),
                mid: None,
                active: true,
            },
        ];

        let sdp = build_subscribe_remote_sdp(&config, &tracks, RoomMode::Conference, None);

        assert!(sdp.contains("a=sendonly\r\n"), "sendonly direction");
        assert!(sdp.contains("a=ice-ufrag:sub_ufrag\r\n"), "subscribe ufrag");
        assert!(sdp.contains("a=ssrc:12345678 cname:oxlens-sfu\r\n"), "audio SSRC");
        assert!(sdp.contains("a=ssrc:87654321 cname:oxlens-sfu\r\n"), "video SSRC");
        assert!(sdp.contains("a=ssrc:11111111 cname:oxlens-sfu\r\n"), "RTX SSRC");
        assert!(sdp.contains("a=ssrc-group:FID 87654321 11111111\r\n"), "FID group");
        assert!(
            sdp.contains("a=ssrc:12345678 msid:light-alice alice_0\r\n"),
            "audio msid"
        );
        // subscribe SDP에서 sdes:mid 제거 확인
        assert!(!sdp.contains("sdes:mid"), "no sdes:mid in subscribe");
        // 다른 extmap은 존재
        assert!(sdp.contains("ssrc-audio-level"), "audio-level extmap present");
    }

    #[test]
    fn subscribe_sdp_empty_tracks() {
        let config = test_server_config();
        let sdp = build_subscribe_remote_sdp(&config, &[], RoomMode::Conference, None);

        assert!(sdp.contains("a=inactive\r\n"), "inactive when no tracks");
        assert!(sdp.contains("m=audio 0"), "port=0 for inactive");
        assert!(!sdp.contains("a=candidate:"), "no candidate for inactive");
    }

    #[test]
    fn subscribe_sdp_ptt_mode() {
        let config = test_server_config();
        let vssrc = PttVirtualSsrc {
            audio: 4000000001,
            video: Some(4000000002),
        };

        let sdp =
            build_subscribe_remote_sdp(&config, &[], RoomMode::Ptt, Some(&vssrc));

        assert!(
            sdp.contains("a=ssrc:4000000001 cname:oxlens-sfu\r\n"),
            "PTT audio vSSRC"
        );
        assert!(
            sdp.contains("a=ssrc:4000000002 cname:oxlens-sfu\r\n"),
            "PTT video vSSRC"
        );
        assert!(sdp.contains("msid:light-ptt ptt-audio"), "PTT audio msid");
        assert!(sdp.contains("msid:light-ptt ptt-video"), "PTT video msid");
        assert!(!sdp.contains("sdes:mid"), "no sdes:mid in PTT subscribe");
    }

    #[test]
    fn subscribe_sdp_inactive_track() {
        let config = test_server_config();
        let tracks = vec![
            TrackDesc {
                user_id: "alice".to_string(),
                kind: MediaKind::Audio,
                ssrc: 12345678,
                track_id: "alice_0".to_string(),
                rtx_ssrc: None,
                mid: Some("0".to_string()),
                active: true,
            },
            TrackDesc {
                user_id: "bob".to_string(),
                kind: MediaKind::Audio,
                ssrc: 99999999,
                track_id: "bob_0".to_string(),
                rtx_ssrc: None,
                mid: Some("1".to_string()),
                active: false, // bob 떠남 → inactive
            },
        ];

        let sdp = build_subscribe_remote_sdp(&config, &tracks, RoomMode::Conference, None);

        // BUNDLE에는 active mid=0만 포함
        assert!(sdp.contains("a=group:BUNDLE 0\r\n"), "BUNDLE only active mid");
        // inactive m-line port=0
        assert!(sdp.contains("m=audio 0 UDP/TLS/RTP/SAVPF"), "inactive port=0");
        // inactive m-line에 SSRC 없음
        assert!(!sdp.contains("a=ssrc:99999999"), "no SSRC for inactive");
    }

    #[test]
    fn publish_sdp_codec_details() {
        let config = test_server_config();
        let sdp = build_publish_remote_sdp(&config);

        // fmtp 확인
        assert!(
            sdp.contains("a=fmtp:111 minptime=10;useinbandfec=1\r\n"),
            "opus fmtp"
        );
        // rtcp-fb 확인
        assert!(sdp.contains("a=rtcp-fb:111 nack\r\n"), "audio nack");
        assert!(sdp.contains("a=rtcp-fb:96 nack pli\r\n"), "video nack pli");
        assert!(sdp.contains("a=rtcp-fb:96 goog-remb\r\n"), "video goog-remb");
        // extmap 확인 (publish에는 sdes:mid 포함)
        assert!(sdp.contains("sdes:mid"), "publish has sdes:mid");
        // rtcp-rsize는 video에만
        // video 섹션 찾아서 확인
        assert!(sdp.contains("a=rtcp-rsize\r\n"), "video has rtcp-rsize");
    }

    #[test]
    fn server_config_json_parse() {
        let json = r#"{
            "room_id": "test-room",
            "mode": "conference",
            "participants": ["alice"],
            "server_config": {
                "ice": {
                    "publish_ufrag": "pu", "publish_pwd": "pp",
                    "subscribe_ufrag": "su", "subscribe_pwd": "sp",
                    "ip": "1.2.3.4", "port": 19740
                },
                "dtls": { "fingerprint": "sha-256 AA:BB", "setup": "passive" },
                "codecs": [{
                    "kind": "audio", "name": "opus", "pt": 111,
                    "clockrate": 48000, "channels": 2
                }],
                "extmap": []
            },
            "tracks": [{
                "user_id": "alice", "kind": "audio",
                "ssrc": 12345, "track_id": "a_0"
            }]
        }"#;

        let resp: RoomJoinResponse = serde_json::from_str(json).unwrap();
        assert_eq!(resp.room_id, "test-room");
        assert_eq!(resp.mode, RoomMode::Conference);
        assert_eq!(resp.server_config.ice.port, 19740);
        assert_eq!(resp.tracks.len(), 1);
        assert_eq!(resp.tracks[0].ssrc, 12345);
        assert!(resp.tracks[0].active, "default active = true");
        assert!(resp.ptt_virtual_ssrc.is_none());
    }
}
