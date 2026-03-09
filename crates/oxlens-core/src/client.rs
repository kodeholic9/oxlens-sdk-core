// author: kodeholic (powered by Claude)
//! OxLensClient — SDK 오케스트레이터
//!
//! oxlens-home/common/livechat-sdk.js (LiveChatSDK)의 Rust 포팅.
//!
//! SignalClient + MediaSession + SDP 빌더를 연결하는 접착제.
//!
//! 흐름:
//!   connect() → HELLO → IDENTIFY → Identified
//!   join_room() → ROOM_JOIN → _on_join_ok() →
//!     SDP 빌더 → MediaSession.setup_publish/subscribe →
//!     SSRC 추출 → PUBLISH_TRACKS
//!   leave_room() → teardown
//!
//! 이벤트 루프는 run() 메서드에서 SignalEvent를 소비하며,
//! 상위 앱에 ClientEvent를 mpsc로 전달한다.

use tokio::sync::mpsc;
use tracing::{error, info, warn};

use crate::sdp;
use crate::sdp::types::{RoomJoinResponse, RoomMode, TrackDesc};
use crate::signaling::client::{SignalClient, SignalConfig, SignalEvent};
use crate::signaling::message::{PublishTrackItem, PublishTracksRequest, RoomJoinRequest};
use crate::signaling::opcode;

use oxlens_webrtc::MediaSession;

/// 상위 앱으로 전달되는 이벤트
#[derive(Debug)]
pub enum ClientEvent {
    Connected,
    Identified,
    RoomJoined { room_id: String, mode: RoomMode },
    RoomLeft { room_id: String },
    TracksUpdated { action: String, count: usize },
    FloorTaken { room_id: String, user_id: String },
    FloorIdle { room_id: String },
    FloorRevoke { room_id: String },
    Error { code: u16, msg: String },
    Disconnected { reason: String },
}

/// SDK 오케스트레이터 설정
#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub server_url: String,
    pub token: String,
    pub user_id: Option<String>,
}

/// OxLens SDK 메인 클라이언트
///
/// home의 LiveChatSDK 클래스와 1:1 대응.
/// SignalClient(시그널링) + MediaSession(미디어) + SDP 빌더를 조립한다.
pub struct OxLensClient {
    signal: SignalClient,
    media: MediaSession,
    event_tx: mpsc::Sender<ClientEvent>,

    // 방 상태
    room_id: Option<String>,
    room_mode: RoomMode,
    subscribe_tracks: Vec<TrackDesc>,
    next_mid: u32,

    // SDP 옵션 (PTT 가상 SSRC 등)
    ptt_virtual_ssrc: Option<sdp::PttVirtualSsrc>,
    server_config: Option<sdp::ServerConfig>,
}

impl OxLensClient {
    /// 클라이언트 생성
    ///
    /// 반환: (client, event_rx) — event_rx에서 ClientEvent를 수신한다.
    pub fn new(config: ClientConfig) -> Result<(Self, mpsc::Receiver<ClientEvent>), anyhow::Error> {
        let signal = SignalClient::new(SignalConfig {
            server_url: config.server_url,
            token: config.token,
            user_id: config.user_id,
        });

        let media = MediaSession::new()
            .map_err(|e| anyhow::anyhow!("MediaSession init failed: {:?}", e))?;

        let (event_tx, event_rx) = mpsc::channel::<ClientEvent>(64);

        Ok((
            Self {
                signal,
                media,
                event_tx,
                room_id: None,
                room_mode: RoomMode::Conference,
                subscribe_tracks: Vec::new(),
                next_mid: 0,
                ptt_virtual_ssrc: None,
                server_config: None,
            },
            event_rx,
        ))
    }

    /// 서버 연결 + 이벤트 루프 시작
    ///
    /// 이 메서드는 연결이 끊어질 때까지 반환하지 않는다.
    /// tokio::spawn으로 백그라운드 실행 권장.
    pub async fn run(&mut self) -> anyhow::Result<()> {
        let mut signal_rx = self.signal.connect().await?;

        while let Some(event) = signal_rx.recv().await {
            match event {
                SignalEvent::Connected { heartbeat_interval } => {
                    info!(heartbeat_interval, "connected to server");
                    let _ = self.event_tx.send(ClientEvent::Connected).await;
                }

                SignalEvent::Identified => {
                    info!("identified");
                    let _ = self.event_tx.send(ClientEvent::Identified).await;
                }

                SignalEvent::RoomJoined { payload } => {
                    self.on_join_ok(payload).await;
                }

                SignalEvent::RoomLeft { room_id } => {
                    self.on_leave(room_id).await;
                }

                SignalEvent::TracksUpdate { action, tracks } => {
                    self.on_tracks_update(&action, tracks).await;
                }

                SignalEvent::FloorTaken { room_id, user_id } => {
                    let _ = self.event_tx.send(ClientEvent::FloorTaken { room_id, user_id }).await;
                }

                SignalEvent::FloorIdle { room_id } => {
                    let _ = self.event_tx.send(ClientEvent::FloorIdle { room_id }).await;
                }

                SignalEvent::FloorRevoke { room_id } => {
                    let _ = self.event_tx.send(ClientEvent::FloorRevoke { room_id }).await;
                }

                SignalEvent::Error { code, msg } => {
                    let _ = self.event_tx.send(ClientEvent::Error { code, msg }).await;
                }

                SignalEvent::Disconnected { reason } => {
                    warn!(reason, "disconnected");
                    self.media.close();
                    self.room_id = None;
                    let _ = self.event_tx.send(ClientEvent::Disconnected { reason }).await;
                    break;
                }

                SignalEvent::RoomEvent(_) => {
                    // TODO: participant join/leave 이벤트 전달
                }
            }
        }

        Ok(())
    }

    /// ROOM_JOIN 요청 전송
    pub async fn join_room(&self, room_id: &str) -> anyhow::Result<()> {
        self.signal
            .send_request(opcode::ROOM_JOIN, &RoomJoinRequest {
                room_id: room_id.to_string(),
            })
            .await?;
        Ok(())
    }

    /// ROOM_LEAVE 요청 전송
    pub async fn leave_room(&self) -> anyhow::Result<()> {
        if let Some(ref room_id) = self.room_id {
            self.signal
                .send_request(opcode::ROOM_LEAVE, &crate::signaling::message::RoomLeaveRequest {
                    room_id: room_id.clone(),
                })
                .await?;
        }
        Ok(())
    }

    /// MediaSession 참조 (트랙 추가, on_track 콜백 등)
    pub fn media(&self) -> &MediaSession {
        &self.media
    }

    // ================================================================
    //  Internal: ROOM_JOIN 성공 처리 — home의 _onJoinOk()
    // ================================================================

    async fn on_join_ok(&mut self, payload: serde_json::Value) {
        // RoomJoinResponse 파싱
        let resp: RoomJoinResponse = match serde_json::from_value(payload) {
            Ok(r) => r,
            Err(e) => {
                error!(?e, "RoomJoinResponse parse failed");
                let _ = self.event_tx.send(ClientEvent::Error {
                    code: 4001,
                    msg: format!("RoomJoinResponse parse failed: {}", e),
                }).await;
                return;
            }
        };

        info!(room_id = %resp.room_id, mode = ?resp.mode,
              tracks = resp.tracks.len(), "room joined, starting 2PC setup");

        self.room_id = Some(resp.room_id.clone());
        self.room_mode = resp.mode;
        self.ptt_virtual_ssrc = resp.ptt_virtual_ssrc.clone();
        self.server_config = Some(resp.server_config.clone());

        // subscribe 트랙 목록 초기화 (mid 할당)
        self.next_mid = 0;
        self.subscribe_tracks = resp.tracks.iter().map(|t| {
            let mid = self.next_mid;
            self.next_mid += 1;
            TrackDesc {
                user_id: t.user_id.clone(),
                kind: t.kind,
                ssrc: t.ssrc,
                track_id: t.track_id.clone(),
                rtx_ssrc: t.rtx_ssrc,
                mid: Some(mid.to_string()),
                active: true,
            }
        }).collect();

        // 1. Publish PC — SDP 조립 → setup → answer SDP 획득
        let pub_sdp = sdp::build_publish_remote_sdp(&resp.server_config);
        let answer_sdp = match self.media.setup_publish(&pub_sdp).await {
            Ok(sdp) => sdp,
            Err(e) => {
                error!(?e, "publish PC setup failed");
                let _ = self.event_tx.send(ClientEvent::Error {
                    code: 4002,
                    msg: format!("publish PC setup failed: {:?}", e),
                }).await;
                return;
            }
        };

        // 2. SSRC 추출 → PUBLISH_TRACKS 전송 (home의 _sendPublishTracks)
        self.send_publish_tracks(&answer_sdp).await;

        // 3. Subscribe PC — SDP 조립 → setup
        let sub_sdp = sdp::build_subscribe_remote_sdp(
            &resp.server_config,
            &self.subscribe_tracks,
            self.room_mode,
            self.ptt_virtual_ssrc.as_ref(),
        );
        if let Err(e) = self.media.setup_subscribe(&sub_sdp).await {
            error!(?e, "subscribe PC setup failed");
            let _ = self.event_tx.send(ClientEvent::Error {
                code: 4003,
                msg: format!("subscribe PC setup failed: {:?}", e),
            }).await;
            return;
        }

        info!("2PC setup complete");
        let _ = self.event_tx.send(ClientEvent::RoomJoined {
            room_id: resp.room_id,
            mode: resp.mode,
        }).await;
    }

    // ================================================================
    //  Internal: TRACKS_UPDATE 처리 — home의 _onTracksUpdate()
    // ================================================================

    async fn on_tracks_update(
        &mut self,
        action: &str,
        tracks: Vec<crate::signaling::message::TrackInfo>,
    ) {
        let config = match &self.server_config {
            Some(c) => c,
            None => {
                warn!("tracks_update before room join, ignoring");
                return;
            }
        };

        info!(action, count = tracks.len(), "tracks_update");

        match action {
            "add" => {
                for t in &tracks {
                    let existing = self.subscribe_tracks.iter_mut()
                        .find(|st| st.track_id == t.track_id);
                    if let Some(st) = existing {
                        st.ssrc = t.ssrc;
                        st.active = true;
                    } else {
                        let mid = self.next_mid;
                        self.next_mid += 1;
                        self.subscribe_tracks.push(TrackDesc {
                            user_id: t.user_id.clone(),
                            kind: if t.kind == "video" {
                                sdp::MediaKind::Video
                            } else {
                                sdp::MediaKind::Audio
                            },
                            ssrc: t.ssrc,
                            track_id: t.track_id.clone(),
                            rtx_ssrc: None,
                            mid: Some(mid.to_string()),
                            active: true,
                        });
                    }
                }
            }
            "remove" => {
                for t in &tracks {
                    if let Some(st) = self.subscribe_tracks.iter_mut()
                        .find(|st| st.track_id == t.track_id)
                    {
                        st.active = false;
                        // mid는 유지! 절대 제거 안 함 (home과 동일)
                    }
                }
            }
            _ => {
                warn!(action, "unknown tracks_update action");
            }
        }

        // subscribe PC re-nego
        let sub_sdp = sdp::build_subscribe_remote_sdp(
            config,
            &self.subscribe_tracks,
            self.room_mode,
            self.ptt_virtual_ssrc.as_ref(),
        );
        if let Err(e) = self.media.setup_subscribe(&sub_sdp).await {
            error!(?e, "subscribe re-nego failed");
            let _ = self.event_tx.send(ClientEvent::Error {
                code: 4003,
                msg: format!("subscribe re-nego failed: {:?}", e),
            }).await;
            return;
        }

        let _ = self.event_tx.send(ClientEvent::TracksUpdated {
            action: action.to_string(),
            count: tracks.len(),
        }).await;
    }

    // ================================================================
    //  Internal: SSRC 추출 + PUBLISH_TRACKS
    // ================================================================

    /// answer SDP에서 각 m-line의 SSRC를 추출하여 PUBLISH_TRACKS 전송
    ///
    /// home의 `_sendPublishTracks()` + `extractSsrcFromSdp()`와 동일.
    async fn send_publish_tracks(&self, answer_sdp: &str) {
        let mut tracks = Vec::new();

        // m=audio / m=video 섹션에서 a=ssrc:NNNN 추출
        for section in answer_sdp.split("\r\nm=").skip(1) {
            let kind = if section.starts_with("audio") {
                "audio"
            } else if section.starts_with("video") {
                "video"
            } else {
                continue;
            };

            // a=ssrc:NNNN 첫 번째 매치
            for line in section.lines() {
                if let Some(rest) = line.strip_prefix("a=ssrc:") {
                    if let Some(ssrc_str) = rest.split_whitespace().next() {
                        if let Ok(ssrc) = ssrc_str.parse::<u32>() {
                            info!(kind, ssrc, "publish track SSRC extracted");
                            tracks.push(PublishTrackItem {
                                kind: kind.to_string(),
                                ssrc,
                            });
                            break; // 첫 SSRC만 (RTX SSRC 제외)
                        }
                    }
                }
            }
        }

        if !tracks.is_empty() {
            if let Err(e) = self.signal
                .send_request(opcode::PUBLISH_TRACKS, &PublishTracksRequest { tracks })
                .await
            {
                error!(?e, "PUBLISH_TRACKS send failed");
            }
        }
    }

    // ================================================================
    //  Internal: ROOM_LEAVE 처리
    // ================================================================

    async fn on_leave(&mut self, room_id: String) {
        info!(room_id, "room left");
        self.media.close();
        self.room_id = None;
        self.subscribe_tracks.clear();
        self.next_mid = 0;
        self.server_config = None;
        self.ptt_virtual_ssrc = None;
        let _ = self.event_tx.send(ClientEvent::RoomLeft { room_id }).await;
    }
}
