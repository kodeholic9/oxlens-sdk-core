// author: kodeholic (powered by Claude)
//! MediaSession — Publish + Subscribe 2PC PeerConnection 관리
//!
//! oxlens-home/common/media-session.js의 Rust 포팅.
//!
//! 책임:
//!   - PeerConnectionFactory 초기화 (싱글턴)
//!   - Publish PC 생성 + SDP 협상 (fake offer → answer)
//!   - Subscribe PC 생성 + re-negotiation
//!   - 수신 트랙 콜백 전달
//!   - teardown
//!
//! 비책임 (oxlens-core가 담당):
//!   - SDP 문자열 조립 (sdp 모듈)
//!   - 시그널링 프로토콜
//!   - Floor FSM
//!   - SSRC 추출 + PUBLISH_TRACKS 전송

use livekit_webrtc::peer_connection::{
    AnswerOptions, PeerConnection, PeerConnectionState,
};
use livekit_webrtc::peer_connection_factory::{
    PeerConnectionFactory, RtcConfiguration,
};
use livekit_webrtc::session_description::{SdpType, SessionDescription};
use livekit_webrtc::RtcError;

use tracing::{debug, info, warn};

/// MediaSession 이벤트 — 상위 레이어(oxlens-core)로 전달
#[derive(Debug)]
pub enum MediaEvent {
    /// publish PC 연결 상태 변경
    PublishConnectionState(PeerConnectionState),
    /// subscribe PC 연결 상태 변경
    SubscribeConnectionState(PeerConnectionState),
}

/// WebRTC 미디어 세션 — Publish + Subscribe 2PC
///
/// home의 MediaSession 클래스와 1:1 대응.
/// publish PC: 내 미디어 → 서버 (sendonly answer)
/// subscribe PC: 서버 → 내 수신 (recvonly answer)
pub struct MediaSession {
    factory: PeerConnectionFactory,
    pub_pc: Option<PeerConnection>,
    sub_pc: Option<PeerConnection>,
}

impl MediaSession {
    /// Factory 초기화 + MediaSession 생성
    pub fn new() -> Result<Self, RtcError> {
        let factory = PeerConnectionFactory::default();
        info!("PeerConnectionFactory created");

        Ok(Self {
            factory,
            pub_pc: None,
            sub_pc: None,
        })
    }

    /// PeerConnectionFactory 참조 (트랙 생성 시 필요)
    pub fn factory(&self) -> &PeerConnectionFactory {
        &self.factory
    }

    /// Publish PC 참조
    pub fn pub_pc(&self) -> Option<&PeerConnection> {
        self.pub_pc.as_ref()
    }

    /// Subscribe PC 참조
    pub fn sub_pc(&self) -> Option<&PeerConnection> {
        self.sub_pc.as_ref()
    }

    // ================================================================
    //  Publish PC — 내 미디어 → 서버
    // ================================================================

    /// Publish PC 셋업 — fake offer SDP → answer 생성
    ///
    /// home의 `_setupPublishPc()`와 동일한 흐름:
    /// 1. PC 생성 (iceServers: [], iceTransportPolicy: all)
    /// 2. setRemoteDescription(offer)
    /// 3. createAnswer()
    /// 4. setLocalDescription(answer)
    /// 5. answer SDP 문자열 반환 (caller가 SSRC 추출에 사용)
    ///
    /// 트랙 추가는 이 메서드 호출 전에 `pub_pc()`로 PC 참조를 얻어 직접 하거나,
    /// 이 메서드 호출 후 re-nego로 처리.
    pub async fn setup_publish(&mut self, offer_sdp: &str) -> Result<String, RtcError> {
        // 기존 PC가 있으면 재사용 (home: signalingState !== "closed" 체크)
        if self.pub_pc.is_some() {
            info!("publish PC already exists, skipping creation");
        } else {
            info!("creating publish PC");
            let config = RtcConfiguration::default();
            let pc = self.factory.create_peer_connection(config)?;

            // 콜백 등록
            pc.on_ice_connection_state_change(Some(Box::new(move |state| {
                debug!("pub iceConnectionState={:?}", state);
            })));

            pc.on_connection_state_change(Some(Box::new(move |state| {
                debug!("pub connectionState={:?}", state);
            })));

            pc.on_ice_candidate(Some(Box::new(move |candidate| {
                debug!("pub local candidate: {:?}", candidate);
            })));

            self.pub_pc = Some(pc);
        }

        let pc = self.pub_pc.as_ref().unwrap();

        // setRemoteDescription (fake offer)
        let offer = SessionDescription::parse(offer_sdp, SdpType::Offer)
            .map_err(|e| RtcError {
                error_type: livekit_webrtc::RtcErrorType::Internal,
                message: format!("publish SDP parse failed: {:?}", e),
            })?;
        pc.set_remote_description(offer).await?;
        info!("pub setRemoteDescription OK");

        // createAnswer
        let answer = pc.create_answer(AnswerOptions::default()).await?;
        let answer_sdp = answer.to_string();
        debug!("pub answer SDP length={}", answer_sdp.len());

        // setLocalDescription
        pc.set_local_description(answer).await?;
        info!("pub setLocalDescription OK");

        // answer SDP 반환 — caller가 SSRC 추출에 사용
        Ok(answer_sdp)
    }

    // ================================================================
    //  Subscribe PC — 다른 참가자 미디어 ← 서버
    // ================================================================

    /// Subscribe PC 셋업 또는 re-negotiation
    ///
    /// home의 `_setupSubscribePc()`와 동일:
    /// - 새 PC면 생성 + 콜백 등록
    /// - 기존 PC면 signalingState 체크 + rollback 후 re-nego
    /// - setRemoteDescription(offer) → createAnswer() → setLocalDescription(answer)
    pub async fn setup_subscribe(&mut self, offer_sdp: &str) -> Result<(), RtcError> {
        let is_new = self.sub_pc.is_none();

        if is_new {
            info!("creating subscribe PC");
            let config = RtcConfiguration::default();
            let pc = self.factory.create_peer_connection(config)?;

            pc.on_ice_connection_state_change(Some(Box::new(move |state| {
                debug!("sub iceConnectionState={:?}", state);
            })));

            pc.on_connection_state_change(Some(Box::new(move |state| {
                debug!("sub connectionState={:?}", state);
            })));

            pc.on_ice_candidate(Some(Box::new(move |candidate| {
                debug!("sub local candidate: {:?}", candidate);
            })));

            // on_track 콜백은 oxlens-core에서 등록 (sub_pc() 참조로)
            self.sub_pc = Some(pc);
        }

        let pc = self.sub_pc.as_ref().unwrap();

        // re-nego 시 signalingState 체크 + rollback (home과 동일)
        if !is_new {
            let state = pc.signaling_state();
            if state != livekit_webrtc::peer_connection::SignalingState::Stable {
                warn!("sub signalingState={:?}, rolling back", state);
                let rollback = SessionDescription::parse("", SdpType::Rollback)
                    .map_err(|e| RtcError {
                        error_type: livekit_webrtc::RtcErrorType::Internal,
                        message: format!("rollback SDP parse failed: {:?}", e),
                    })?;
                pc.set_local_description(rollback).await?;
            }
        }

        // setRemoteDescription (fake offer)
        let offer = SessionDescription::parse(offer_sdp, SdpType::Offer)
            .map_err(|e| RtcError {
                error_type: livekit_webrtc::RtcErrorType::Internal,
                message: format!("subscribe SDP parse failed: {:?}", e),
            })?;
        pc.set_remote_description(offer).await?;
        info!("sub setRemoteDescription OK ({})", if is_new { "new" } else { "re-nego" });

        // createAnswer
        let answer = pc.create_answer(AnswerOptions::default()).await?;
        pc.set_local_description(answer).await?;
        info!("sub setLocalDescription OK");

        Ok(())
    }

    // ================================================================
    //  Teardown
    // ================================================================

    /// 모든 PC 종료 + 리소스 해제
    ///
    /// home의 teardown()과 동일.
    pub fn close(&mut self) {
        if let Some(pc) = self.pub_pc.take() {
            pc.on_connection_state_change(None);
            pc.on_ice_connection_state_change(None);
            pc.on_ice_candidate(None);
            pc.close();
            info!("publish PC closed");
        }

        if let Some(pc) = self.sub_pc.take() {
            pc.on_connection_state_change(None);
            pc.on_ice_connection_state_change(None);
            pc.on_ice_candidate(None);
            pc.on_track(None);
            pc.close();
            info!("subscribe PC closed");
        }
    }
}

impl Drop for MediaSession {
    fn drop(&mut self) {
        self.close();
    }
}
