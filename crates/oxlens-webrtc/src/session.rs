// author: kodeholic (powered by Claude)
//! MediaSession — Publish + Subscribe 2PC PeerConnection 관리
//!
//! oxlens-home/common/media-session.js의 Rust 포팅.
//!
//! 책임:
//!   - PeerConnectionFactory 초기화 (싱글턴)
//!   - Publish PC 생성 + SDP 협상 (fake offer → answer)
//!   - Subscribe PC 생성 + re-negotiation
//!   - 트랙 생성 + PC에 추가 (오디오/비디오)
//!   - 수신 트랙 콜백 전달
//!   - teardown
//!
//! 비책임 (oxlens-core가 담당):
//!   - SDP 문자열 조립 (sdp 모듈)
//!   - 시그널링 프로토콜
//!   - Floor FSM
//!   - SSRC 추출 + PUBLISH_TRACKS 전송

use livekit_webrtc::audio_frame::AudioFrame;
use livekit_webrtc::audio_source::native::NativeAudioSource;
use livekit_webrtc::audio_source::AudioSourceOptions;
use livekit_webrtc::media_stream_track::MediaStreamTrack;
use livekit_webrtc::peer_connection::{
    AnswerOptions, PeerConnection, PeerConnectionState, TrackEvent,
};
use livekit_webrtc::peer_connection_factory::native::PeerConnectionFactoryExt;
use livekit_webrtc::peer_connection_factory::{
    PeerConnectionFactory, RtcConfiguration,
};
use livekit_webrtc::rtp_sender::RtpSender;
use livekit_webrtc::session_description::{SdpType, SessionDescription};
use livekit_webrtc::RtcError;

use tokio::sync::mpsc;
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
    /// publish PC에 추가된 오디오 소스 (프레임 주입용 핸들)
    audio_source: Option<NativeAudioSource>,
    /// publish PC에 추가된 트랙 sender 목록
    pub_senders: Vec<RtpSender>,
    /// subscribe PC on_track 이벤트 전달 채널
    /// oxlens-core에서 set_track_sender()로 주입
    track_tx: Option<mpsc::UnboundedSender<TrackEvent>>,
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
            audio_source: None,
            pub_senders: Vec::new(),
            track_tx: None,
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

    /// 오디오 소스 참조 (프레임 주입용)
    pub fn audio_source(&self) -> Option<&NativeAudioSource> {
        self.audio_source.as_ref()
    }

    /// on_track 이벤트 전달 채널 설정
    ///
    /// oxlens-core가 connect() 시점에 호출.
    /// subscribe PC의 on_track 콜백에서 이 채널로 TrackEvent를 전달한다.
    pub fn set_track_sender(&mut self, tx: mpsc::UnboundedSender<TrackEvent>) {
        self.track_tx = Some(tx);
    }

    /// publish PC에 트랙이 추가되었는지 여부
    ///
    /// OxLensClient::has_publish_tracks()에서 사용.
    /// run() 전에 add_audio_source()로 트랙을 선 추가했는지 판별.
    pub fn pub_senders_empty(&self) -> bool {
        self.pub_senders.is_empty()
    }

    // ================================================================
    //  Publish PC — 생성 (트랙 추가 전에 호출)
    // ================================================================

    /// Publish PC 생성만 수행 (트랙 추가를 위해 SDP 협상 전에 호출)
    ///
    /// 이미 생성되어 있으면 무시.
    pub fn ensure_publish_pc(&mut self) -> Result<(), RtcError> {
        if self.pub_pc.is_some() {
            return Ok(());
        }

        info!("creating publish PC");
        let config = RtcConfiguration::default();
        let pc = self.factory.create_peer_connection(config)?;

        pc.on_ice_connection_state_change(Some(Box::new(move |state| {
            debug!("pub iceConnectionState={:?}", state);
        })));

        pc.on_connection_state_change(Some(Box::new(move |state| {
            info!("pub connectionState={:?}", state);
        })));

        pc.on_ice_candidate(Some(Box::new(move |candidate| {
            debug!("pub local candidate: {:?}", candidate);
        })));

        self.pub_pc = Some(pc);
        Ok(())
    }

    // ================================================================
    //  트랙 관리 — 오디오/비디오 소스 생성 + PC에 추가
    // ================================================================

    /// 오디오 소스 생성 + publish PC에 트랙 추가
    ///
    /// PTT half-duplex이므로 AEC/NS/AGC 기본 off.
    /// 반환된 NativeAudioSource에 capture_frame()으로 PCM 주입.
    ///
    /// 호출 순서: ensure_publish_pc() → add_audio_source() → setup_publish()
    pub fn add_audio_source(&mut self, options: AudioSourceOptions) -> Result<NativeAudioSource, RtcError> {
        self.ensure_publish_pc()?;
        let pc = self.pub_pc.as_ref().unwrap();

        let source = NativeAudioSource::new(options);
        let track = self.factory.create_audio_track("audio0", source.clone());
        info!("audio track created: label=audio0");

        let sender = pc.add_track(MediaStreamTrack::Audio(track), &["stream0"])?;
        info!("audio track added to publish PC");

        self.pub_senders.push(sender);
        self.audio_source = Some(source.clone());
        Ok(source)
    }

    /// 오디오 프레임 주입 편의 메서드
    ///
    /// 20ms @ 48kHz mono = 960 samples. tokio::time::interval로 주기 호출.
    pub fn capture_audio_frame(&self, frame: &AudioFrame) {
        if let Some(ref source) = self.audio_source {
            source.capture_frame(frame);
        }
    }

    // ================================================================
    //  Publish PC — SDP 협상
    // ================================================================

    /// Publish PC SDP 협상 — fake offer → answer 생성
    ///
    /// home의 `_setupPublishPc()`와 동일한 흐름:
    /// 1. PC 생성 (이미 ensure_publish_pc()로 생성됨)
    /// 2. setRemoteDescription(offer)
    /// 3. createAnswer()
    /// 4. setLocalDescription(answer)
    /// 5. answer SDP 문자열 반환 (caller가 SSRC 추출에 사용)
    ///
    /// 트랙은 이 메서드 호출 전에 add_audio_source() 등으로 추가해야
    /// answer SDP에 SSRC가 포함된다.
    pub async fn setup_publish(&mut self, offer_sdp: &str) -> Result<String, RtcError> {
        // PC가 없으면 여기서 생성 (트랙 없이 호출된 경우 대비)
        self.ensure_publish_pc()?;

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
                info!("sub connectionState={:?}", state);
            })));

            pc.on_ice_candidate(Some(Box::new(move |candidate| {
                debug!("sub local candidate: {:?}", candidate);
            })));

            // on_track 콜백 — 수신 트랙을 상위 레이어로 전달
            if let Some(ref tx) = self.track_tx {
                let track_tx = tx.clone();
                pc.on_track(Some(Box::new(move |event| {
                    info!(
                        "sub on_track fired: kind={:?}, streams={}",
                        event.track, event.streams.len()
                    );
                    if track_tx.send(event).is_err() {
                        warn!("track_tx receiver dropped");
                    }
                })));
            } else {
                warn!("no track_tx set — on_track events will be lost");
            }

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
        self.audio_source = None;
        self.pub_senders.clear();

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
