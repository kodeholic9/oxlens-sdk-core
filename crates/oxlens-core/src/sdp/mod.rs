// author: kodeholic (powered by Claude)
//! SDP module — server_config JSON → fake remote SDP 조립
//!
//! 서버는 SDP-free 프로토콜을 사용한다. ROOM_JOIN 응답의 server_config로
//! 클라이언트가 직접 fake SDP를 조립하여 PeerConnection에 주입한다.
//!
//! ## 사용법
//! ```ignore
//! use oxlens_core::sdp::{self, RoomJoinResponse, RoomMode};
//!
//! let resp: RoomJoinResponse = serde_json::from_value(packet.d)?;
//! let pub_sdp = sdp::build_publish_remote_sdp(&resp.server_config);
//! let sub_sdp = sdp::build_subscribe_remote_sdp(
//!     &resp.server_config,
//!     &resp.tracks,
//!     resp.mode,
//!     resp.ptt_virtual_ssrc.as_ref(),
//! );
//! ```

pub mod builder;
pub mod types;
pub mod validate;

// Re-exports — 외부에서 sdp:: 접두사로 접근
pub use builder::{
    build_publish_remote_sdp,
    build_subscribe_remote_sdp,
    update_subscribe_remote_sdp,
};
pub use types::{
    CodecConfig, DtlsConfig, ExtmapEntry, IceConfig, MediaKind,
    PttVirtualSsrc, RoomJoinResponse, RoomMode, ServerConfig, TrackDesc,
};
pub use validate::{validate_sdp, SdpValidation};
