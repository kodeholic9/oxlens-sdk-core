// author: kodeholic (powered by Claude)
//! Opcode definitions — mirrors oxlens-sfu-server/src/signaling/opcode.rs
//!
//! Request/Response: Client sends request, Server responds with same op + ok field.
//! Event:           Server sends event, Client responds with same op + ok field.

// --- Client → Server (Request) ---
pub const HEARTBEAT: u16 = 1;
pub const IDENTIFY: u16 = 3;
pub const ROOM_LIST: u16 = 9;
pub const ROOM_CREATE: u16 = 10;
pub const ROOM_JOIN: u16 = 11;
pub const ROOM_LEAVE: u16 = 12;
pub const PUBLISH_TRACKS: u16 = 15;
pub const MUTE_UPDATE: u16 = 17;
pub const MESSAGE: u16 = 20;
pub const TELEMETRY: u16 = 30;

// --- Floor Control (MCPTT/MBCP) ---
pub const FLOOR_REQUEST: u16 = 40;
pub const FLOOR_RELEASE: u16 = 41;
pub const FLOOR_PING: u16 = 42;

// --- Server → Client (Event) ---
pub const HELLO: u16 = 0;
pub const ROOM_EVENT: u16 = 100;
pub const TRACKS_UPDATE: u16 = 101;
pub const TRACK_STATE: u16 = 102;
pub const MESSAGE_EVENT: u16 = 103;
pub const ADMIN_TELEMETRY: u16 = 110;

// --- Floor Control Events ---
pub const FLOOR_TAKEN: u16 = 141;
pub const FLOOR_IDLE: u16 = 142;
pub const FLOOR_REVOKE: u16 = 143;

/// opcode → 디버그 이름
pub fn name(op: u16) -> &'static str {
    match op {
        HEARTBEAT => "HEARTBEAT",
        IDENTIFY => "IDENTIFY",
        ROOM_LIST => "ROOM_LIST",
        ROOM_CREATE => "ROOM_CREATE",
        ROOM_JOIN => "ROOM_JOIN",
        ROOM_LEAVE => "ROOM_LEAVE",
        PUBLISH_TRACKS => "PUBLISH_TRACKS",
        MUTE_UPDATE => "MUTE_UPDATE",
        MESSAGE => "MESSAGE",
        TELEMETRY => "TELEMETRY",
        FLOOR_REQUEST => "FLOOR_REQUEST",
        FLOOR_RELEASE => "FLOOR_RELEASE",
        FLOOR_PING => "FLOOR_PING",
        HELLO => "HELLO",
        ROOM_EVENT => "ROOM_EVENT",
        TRACKS_UPDATE => "TRACKS_UPDATE",
        TRACK_STATE => "TRACK_STATE",
        MESSAGE_EVENT => "MESSAGE_EVENT",
        ADMIN_TELEMETRY => "ADMIN_TELEMETRY",
        FLOOR_TAKEN => "FLOOR_TAKEN",
        FLOOR_IDLE => "FLOOR_IDLE",
        FLOOR_REVOKE => "FLOOR_REVOKE",
        _ => "UNKNOWN",
    }
}
