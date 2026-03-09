// author: kodeholic (powered by Claude)
//
// oxlens-core — PTT 비즈니스 로직
//
// - client: OxLensClient 오케스트레이터 (시그널링 + 미디어 + SDP 빌더 조립)
// - signaling: WS 시그널링 (opcode 기반 JSON over WebSocket)
// - sdp: server_config → fake remote SDP 조립 (SDP-free 2PC 프로토콜)
// - (예정) floor: Floor FSM (발언권 상태 머신)
// - (예정) mbcp: MBCP 클라이언트 (Media Bridge Control Protocol)
// - (예정) gating: 미디어 게이팅 (Tx FSM: Soft-Mute → Hard-Mute → Deep Sleep)

pub mod client;
pub mod sdp;
pub mod signaling;

pub use client::{ClientConfig, ClientEvent, OxLensClient};

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_exists() {
        assert!(!version().is_empty());
    }
}
