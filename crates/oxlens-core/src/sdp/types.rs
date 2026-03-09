// author: kodeholic (powered by Claude)
//! SDP types — server_config JSON을 Rust 타입으로 매핑
//!
//! ROOM_JOIN 응답의 `d.server_config`, `d.tracks`, `d.ptt_virtual_ssrc`를
//! 역직렬화하여 SDP 빌더에서 사용한다.
//!
//! 참조: doc/SERVER_CONFIG_SCHEMA.md

use serde::Deserialize;

// ── server_config ──────────────────────────────────────────────

/// ROOM_JOIN 응답 → `d.server_config`
#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    pub ice: IceConfig,
    pub dtls: DtlsConfig,
    pub codecs: Vec<CodecConfig>,
    #[serde(default)]
    pub extmap: Vec<ExtmapEntry>,
    #[serde(default)]
    pub max_bitrate_bps: Option<u32>,
}

/// ICE credentials + 서버 endpoint
#[derive(Debug, Clone, Deserialize)]
pub struct IceConfig {
    pub publish_ufrag: String,
    pub publish_pwd: String,
    pub subscribe_ufrag: String,
    pub subscribe_pwd: String,
    pub ip: String,
    pub port: u16,
}

/// DTLS fingerprint + setup role
#[derive(Debug, Clone, Deserialize)]
pub struct DtlsConfig {
    pub fingerprint: String,
    /// 항상 "passive" (서버 = ICE-Lite)
    pub setup: String,
}

/// 코덱 정의 (audio or video)
#[derive(Debug, Clone, Deserialize)]
pub struct CodecConfig {
    pub kind: MediaKind,
    pub name: String,
    pub pt: u8,
    pub clockrate: u32,
    /// audio 전용 — 없으면 1 (mono)
    #[serde(default)]
    pub channels: Option<u8>,
    /// RTX payload type (video only)
    #[serde(default)]
    pub rtx_pt: Option<u8>,
    /// RTCP feedback types (e.g. ["nack", "nack pli", "ccm fir"])
    #[serde(default)]
    pub rtcp_fb: Vec<String>,
    /// fmtp parameters (e.g. "minptime=10;useinbandfec=1")
    #[serde(default)]
    pub fmtp: Option<String>,
}

/// RTP 헤더 확장
#[derive(Debug, Clone, Deserialize)]
pub struct ExtmapEntry {
    pub id: u8,
    pub uri: String,
}

// ── tracks ──────────────────────────────────────────────────────

/// ROOM_JOIN 응답 → `d.tracks[]` — 기존 참가자의 트랙 정보
#[derive(Debug, Clone, Deserialize)]
pub struct TrackDesc {
    pub user_id: String,
    pub kind: MediaKind,
    pub ssrc: u32,
    pub track_id: String,
    /// video만 — RTX SSRC
    #[serde(default)]
    pub rtx_ssrc: Option<u32>,
    /// re-nego 시 m-line 위치 고정용 mid
    #[serde(default)]
    pub mid: Option<String>,
    /// false이면 inactive m-line (기본 true)
    #[serde(default = "default_active")]
    pub active: bool,
}

fn default_active() -> bool {
    true
}

// ── PTT ─────────────────────────────────────────────────────────

/// ROOM_JOIN 응답 → `d.ptt_virtual_ssrc` (PTT 모드일 때만)
#[derive(Debug, Clone, Deserialize)]
pub struct PttVirtualSsrc {
    pub audio: u32,
    #[serde(default)]
    pub video: Option<u32>,
}

// ── enums ───────────────────────────────────────────────────────

/// 미디어 종류
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind {
    Audio,
    Video,
}

impl MediaKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MediaKind::Audio => "audio",
            MediaKind::Video => "video",
        }
    }
}

impl std::fmt::Display for MediaKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// 방 모드
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoomMode {
    Conference,
    Ptt,
}

impl Default for RoomMode {
    fn default() -> Self {
        RoomMode::Conference
    }
}

// ── ROOM_JOIN 응답 전체 ─────────────────────────────────────────

/// ROOM_JOIN 응답 payload (`d` 필드) — SDP 빌더에 필요한 전체 정보
#[derive(Debug, Clone, Deserialize)]
pub struct RoomJoinResponse {
    pub room_id: String,
    #[serde(default)]
    pub mode: RoomMode,
    #[serde(default)]
    pub participants: Vec<String>,
    pub server_config: ServerConfig,
    #[serde(default)]
    pub tracks: Vec<TrackDesc>,
    #[serde(default)]
    pub ptt_virtual_ssrc: Option<PttVirtualSsrc>,
}

// ── 내부 헬퍼 ───────────────────────────────────────────────────

/// sdes:mid extmap URI — subscribe SDP에서 제거 대상
pub(crate) const EXTMAP_SDES_MID: &str = "urn:ietf:params:rtp-hdrext:sdes:mid";
