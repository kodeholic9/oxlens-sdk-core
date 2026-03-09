// author: kodeholic (powered by Claude)
//! Signaling message types — mirrors oxlens-sfu-server packet format
//!
//! Packet format:
//!   { "op": N, "pid": u64, "d": { ... } }              — request / event
//!   { "op": N, "pid": u64, "ok": true,  "d": { ... } } — success response
//!   { "op": N, "pid": u64, "ok": false, "d": { "code": u16, "msg": "..." } } — error response

use serde::{Deserialize, Serialize};

/// Raw packet from/to WebSocket — 서버와 동일한 구조
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Packet {
    pub op: u16,
    pub pid: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ok: Option<bool>,
    #[serde(default)]
    pub d: serde_json::Value,
}

impl Packet {
    /// 새 요청/이벤트 패킷 생성
    pub fn new(op: u16, pid: u64, d: serde_json::Value) -> Self {
        Self { op, pid, ok: None, d }
    }

    /// 응답 여부 확인
    pub fn is_response(&self) -> bool {
        self.ok.is_some()
    }

    /// 성공 응답인지
    pub fn is_ok(&self) -> bool {
        self.ok == Some(true)
    }

    /// 에러 응답인지
    pub fn is_err(&self) -> bool {
        self.ok == Some(false)
    }
}

// --- Request payloads (Client → Server) ---

#[derive(Debug, Serialize)]
pub struct IdentifyRequest {
    pub token: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct RoomJoinRequest {
    pub room_id: String,
}

#[derive(Debug, Serialize)]
pub struct RoomLeaveRequest {
    pub room_id: String,
}

#[derive(Debug, Serialize)]
pub struct PublishTrackItem {
    pub kind: String,
    pub ssrc: u32,
}

#[derive(Debug, Serialize)]
pub struct PublishTracksRequest {
    pub tracks: Vec<PublishTrackItem>,
}

#[derive(Debug, Serialize)]
pub struct MuteUpdateRequest {
    pub ssrc: u32,
    pub muted: bool,
}

#[derive(Debug, Serialize)]
pub struct FloorRequestMsg {
    pub room_id: String,
}

#[derive(Debug, Serialize)]
pub struct FloorReleaseMsg {
    pub room_id: String,
}

#[derive(Debug, Serialize)]
pub struct FloorPingMsg {
    pub room_id: String,
}

// --- Event payloads (Server → Client) ---

#[derive(Debug, Deserialize)]
pub struct HelloEvent {
    pub heartbeat_interval: u64,
}

#[derive(Debug, Deserialize)]
pub struct RoomEventPayload {
    #[serde(rename = "type")]
    pub event_type: String,
    pub room_id: String,
    #[serde(default)]
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TrackInfo {
    pub user_id: String,
    pub track_id: String,
    pub kind: String,
    pub ssrc: u32,
}

#[derive(Debug, Deserialize)]
pub struct FloorTakenEvent {
    pub room_id: String,
    pub user_id: String,
}

#[derive(Debug, Deserialize)]
pub struct FloorIdleEvent {
    pub room_id: String,
}
