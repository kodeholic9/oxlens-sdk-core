// author: kodeholic (powered by Claude)
//
// oxlens-webrtc — libwebrtc Safe Rust 래퍼
//
// livekit-webrtc 기반으로 PeerConnection, AudioTrack, VideoTrack 등을
// oxlens PTT 용도에 맞게 래핑한다.
// Phase 2에서 NetEQ 튜닝 API, Rx 인터셉터 등을 추가.

pub mod session;

// Re-exports — oxlens-core에서 접근 편의
pub use session::MediaSession;

// livekit-webrtc 타입 re-export (oxlens-core에서 필요한 것들)
pub use livekit_webrtc::peer_connection::PeerConnectionState;
pub use livekit_webrtc::peer_connection_factory::PeerConnectionFactory;
pub use livekit_webrtc::RtcError;

// 오디오 소스 관련 re-export (bench, 앱에서 사용)
pub use livekit_webrtc::audio_frame::AudioFrame;
pub use livekit_webrtc::audio_source::native::NativeAudioSource;
pub use livekit_webrtc::audio_source::AudioSourceOptions;

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
