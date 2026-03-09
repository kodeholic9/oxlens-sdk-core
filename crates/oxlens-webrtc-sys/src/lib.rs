// author: kodeholic (powered by Claude)
//
// oxlens-webrtc-sys — C++ FFI 레이어
//
// LiveKit webrtc-sys를 기반으로 하되, oxlens에 필요한 추가 바인딩을 제공한다:
// - NetEQ 설정 API (max_delay, fast_accelerate, buffer flush)
// - RtpPacketRewriter (Rx 인터셉터: Opus silence injection + seq/ts offset)
// - ICE Ping 주기 동적 조정
//
// Phase 1에서는 webrtc-sys를 re-export만 하고,
// Phase 2에서 커스텀 바인딩을 추가한다.

pub use webrtc_sys;

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
