// author: kodeholic (powered by Claude)
//! OxLensClient — SDK 오케스트레이터 (Arc 핸들 패턴)
//!
//! LiveKit Rust SDK의 `(Room, event_rx)` 패턴을 채택.
//! connect()가 내부에서 이벤트 루프를 spawn하고,
//! Clone 가능한 핸들 + event_rx를 반환한다.
//!
//! ## 사용법
//! ```ignore
//! use oxlens_core::{OxLensClient, ClientConfig, ClientEvent};
//!
//! let (client, mut events) = OxLensClient::connect(ClientConfig {
//!     server_url: "ws://127.0.0.1:1974/ws".into(),
//!     token: "my-token".into(),
//!     user_id: Some("user-1".into()),
//! }).await?;
//!
//! // 오디오 트랙 추가 — connect 후 아무 때나 (&self)
//! let audio_src = client.add_audio_source(Default::default())?;
//!
//! // 이벤트 수신 + 명령 전송
//! while let Some(event) = events.recv().await {
//!     match event {
//!         ClientEvent::Identified => {
//!             client.create_room("my-room", 30, "conference").await?;
//!         }
//!         ClientEvent::RoomCreated { room_id, .. } => {
//!             client.join_room(&room_id).await?;
//!         }
//!         ClientEvent::RoomJoined { room_id, mode } => {
//!             println!("joined room {} ({:?})", room_id, mode);
//!         }
//!         ClientEvent::FloorTaken { user_id, .. } => {
//!             println!("speaker: {}", user_id);
//!         }
//!         _ => {}
//!     }
//! }
//! ```
//!
//! ## 핵심 설계
//! - `OxLensClient`는 `Arc<ClientInner>`를 감싸는 경량 핸들
//! - `Clone` 가능 — tokio::spawn에 자유롭게 전달
//! - 모든 공개 API는 `&self` — connect 후 어디서든 호출
//! - 내부 상태는 `Mutex`/`RwLock`으로 보호
//! - 이벤트 루프는 connect() 내부에서 자동 spawn

use std::sync::Arc;

use tokio::sync::{mpsc, Mutex, RwLock};
use tracing::{error, info, warn};

use crate::sdp;
use crate::sdp::types::{RoomJoinResponse, RoomMode, TrackDesc};
use crate::signaling::client::{SignalClient, SignalConfig, SignalEvent, SignalSender};
use crate::signaling::message::{
    FloorPingMsg, FloorReleaseMsg, FloorRequestMsg,
    PublishTrackItem, PublishTracksRequest,
    RoomCreateRequest, RoomJoinRequest, RoomLeaveRequest,
};
use crate::signaling::opcode;

use futures_util::StreamExt;
use oxlens_webrtc::{
    AudioSourceOptions, MediaSession, MediaStreamTrack,
    NativeAudioSource, NativeAudioStream, TrackEvent,
};

// ================================================================
//  ClientEvent — 상위 앱으로 전달되는 이벤트
// ================================================================

/// 상위 앱으로 전달되는 이벤트
#[derive(Debug)]
pub enum ClientEvent {
    /// 서버 연결 완료 (HELLO 수신)
    Connected,
    /// IDENTIFY 성공 — 이 시점부터 명령 전송 가능
    Identified,
    /// 방 생성 완료
    RoomCreated {
        room_id: String,
        name: String,
        mode: String,
    },
    /// 방 목록 수신
    RoomList {
        rooms: Vec<serde_json::Value>,
    },
    /// 방 입장 + 2PC 셋업 완료
    RoomJoined {
        room_id: String,
        mode: RoomMode,
    },
    /// 방 퇴장 완료
    RoomLeft {
        room_id: String,
    },
    /// 다른 참가자 트랙 변경
    TracksUpdated {
        action: String,
        count: usize,
    },
    /// 수신 오디오 프레임 (검증용 — 프레임이 실제로 도달하는지 확인)
    AudioFrameReceived {
        sample_rate: u32,
        num_channels: u32,
        samples_per_channel: u32,
    },
    /// PTT 발화권 획득 (granted)
    FloorGranted {
        room_id: String,
        speaker: String,
    },
    /// PTT 발화권 거부 (denied)
    FloorDenied {
        reason: String,
    },
    /// 다른 사용자가 발화권 획득
    FloorTaken {
        room_id: String,
        user_id: String,
    },
    /// 발화권 해제 (방 idle 상태)
    FloorIdle {
        room_id: String,
    },
    /// 발화권 강제 회수
    FloorRevoke {
        room_id: String,
    },
    /// 발화권 해제 완료 (내 요청에 대한 응답)
    FloorReleased,
    /// 서버 에러
    Error {
        code: u16,
        msg: String,
    },
    /// 연결 해제
    Disconnected {
        reason: String,
    },
}

// ================================================================
//  ClientConfig
// ================================================================

/// SDK 오케스트레이터 설정
#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub server_url: String,
    pub token: String,
    pub user_id: Option<String>,
}

// ================================================================
//  내부 상태 — Arc로 감싸 공유
// ================================================================

/// 방 관련 상태 — RwLock으로 보호
struct RoomState {
    room_id: Option<String>,
    room_mode: RoomMode,
    subscribe_tracks: Vec<TrackDesc>,
    next_mid: u32,
    ptt_virtual_ssrc: Option<sdp::PttVirtualSsrc>,
    server_config: Option<sdp::ServerConfig>,
}

impl RoomState {
    fn new() -> Self {
        Self {
            room_id: None,
            room_mode: RoomMode::Conference,
            subscribe_tracks: Vec::new(),
            next_mid: 0,
            ptt_virtual_ssrc: None,
            server_config: None,
        }
    }

    fn reset(&mut self) {
        self.room_id = None;
        self.room_mode = RoomMode::Conference;
        self.subscribe_tracks.clear();
        self.next_mid = 0;
        self.ptt_virtual_ssrc = None;
        self.server_config = None;
    }
}

/// OxLensClient 내부 — Arc로 공유
struct ClientInner {
    sender: SignalSender,
    media: Mutex<MediaSession>,
    room: RwLock<RoomState>,
    event_tx: mpsc::Sender<ClientEvent>,
    /// subscribe PC on_track 이벤트 수신 채널
    track_rx: Mutex<mpsc::UnboundedReceiver<TrackEvent>>,
}

// ================================================================
//  OxLensClient — 공개 핸들
// ================================================================

/// OxLens SDK 메인 클라이언트
///
/// `Arc<ClientInner>`를 감싸는 경량 핸들.
/// `Clone` 가능하여 tokio::spawn에 자유롭게 전달.
/// 모든 공개 API는 `&self`로 호출.
///
/// ## 사용 흐름
/// ```ignore
/// let (client, mut events) = OxLensClient::connect(config).await?;
/// let audio_src = client.add_audio_source(opts)?;
///
/// while let Some(event) = events.recv().await {
///     match event {
///         ClientEvent::Identified => {
///             client.join_room("room-1").await?;
///         }
///         // ...
///         _ => {}
///     }
/// }
/// ```
#[derive(Clone)]
pub struct OxLensClient {
    inner: Arc<ClientInner>,
}

impl OxLensClient {
    /// 서버에 연결하고 이벤트 루프를 자동 시작
    ///
    /// 반환: (client_handle, event_rx)
    /// - client_handle: Clone 가능한 핸들. &self로 모든 명령 전송.
    /// - event_rx: ClientEvent를 수신하는 채널.
    ///
    /// 내부적으로 tokio::spawn으로 이벤트 루프를 돌린다.
    /// 연결이 끊어지면 ClientEvent::Disconnected를 발행하고 루프 종료.
    pub async fn connect(
        config: ClientConfig,
    ) -> Result<(Self, mpsc::Receiver<ClientEvent>), anyhow::Error> {
        // 1. MediaSession 생성 + on_track 채널 셋업
        let mut media = MediaSession::new()
            .map_err(|e| anyhow::anyhow!("MediaSession init failed: {:?}", e))?;
        let (track_tx, track_rx) = mpsc::unbounded_channel::<TrackEvent>();
        media.set_track_sender(track_tx);

        // 2. SignalClient 연결
        let mut signal = SignalClient::new(SignalConfig {
            server_url: config.server_url,
            token: config.token,
            user_id: config.user_id,
        });
        let signal_rx = signal.connect().await?;

        // 3. SignalSender 확보 (이벤트 루프 spawn 전에)
        let sender = signal.clone_sender();

        // 4. 이벤트 채널 (ClientEvent 발행용)
        let (event_tx, event_rx) = mpsc::channel::<ClientEvent>(64);

        // 5. Arc<ClientInner> 조립
        let inner = Arc::new(ClientInner {
            sender,
            media: Mutex::new(media),
            room: RwLock::new(RoomState::new()),
            event_tx,
            track_rx: Mutex::new(track_rx),
        });

        // 6. 이벤트 루프 spawn
        let inner_for_loop = inner.clone();
        tokio::spawn(async move {
            event_loop(inner_for_loop, signal_rx).await;
        });

        // 7. on_track 수신 루프 spawn (1회만 — re-join 시에도 동일 루프 재사용)
        let inner_for_track = inner.clone();
        tokio::spawn(async move {
            track_receive_loop(inner_for_track).await;
        });

        Ok((Self { inner }, event_rx))
    }

    // ================================================================
    //  공개 API — 트랙 관리
    // ================================================================

    /// 오디오 소스 생성 + publish PC에 트랙 추가
    ///
    /// connect() 후 아무 시점에 호출 가능.
    /// 반환된 NativeAudioSource에 capture_frame()으로 PCM 주입.
    pub async fn add_audio_source(
        &self,
        options: AudioSourceOptions,
    ) -> Result<NativeAudioSource, anyhow::Error> {
        let mut media = self.inner.media.lock().await;
        media
            .add_audio_source(options)
            .map_err(|e| anyhow::anyhow!("add_audio_source failed: {:?}", e))
    }

    // ================================================================
    //  공개 API — 방 관리
    // ================================================================

    /// 방 생성
    pub async fn create_room(
        &self,
        name: &str,
        capacity: u32,
        mode: &str,
    ) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(
                opcode::ROOM_CREATE,
                &RoomCreateRequest {
                    name: name.to_string(),
                    capacity: Some(capacity),
                    mode: Some(mode.to_string()),
                },
            )
            .await?;
        Ok(())
    }

    /// 방 목록 요청
    pub async fn list_rooms(&self) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(opcode::ROOM_LIST, &serde_json::json!({}))
            .await?;
        Ok(())
    }

    /// 방 입장
    ///
    /// 서버 응답(RoomJoined) 시 내부에서 2PC 셋업을 자동 수행하고,
    /// 완료되면 ClientEvent::RoomJoined를 발행한다.
    pub async fn join_room(&self, room_id: &str) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(
                opcode::ROOM_JOIN,
                &RoomJoinRequest {
                    room_id: room_id.to_string(),
                },
            )
            .await?;
        Ok(())
    }

    /// 방 퇴장
    pub async fn leave_room(&self) -> anyhow::Result<()> {
        let room = self.inner.room.read().await;
        if let Some(ref room_id) = room.room_id {
            self.inner
                .sender
                .send_packet(
                    opcode::ROOM_LEAVE,
                    &RoomLeaveRequest {
                        room_id: room_id.clone(),
                    },
                )
                .await?;
        }
        Ok(())
    }

    // ================================================================
    //  공개 API — PTT Floor Control
    // ================================================================

    /// PTT 발화권 요청
    pub async fn request_floor(&self, room_id: &str) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(
                opcode::FLOOR_REQUEST,
                &FloorRequestMsg {
                    room_id: room_id.to_string(),
                },
            )
            .await?;
        Ok(())
    }

    /// PTT 발화권 해제
    pub async fn release_floor(&self, room_id: &str) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(
                opcode::FLOOR_RELEASE,
                &FloorReleaseMsg {
                    room_id: room_id.to_string(),
                },
            )
            .await?;
        Ok(())
    }

    /// PTT Floor Ping (발화자 생존 확인)
    pub async fn floor_ping(&self, room_id: &str) -> anyhow::Result<()> {
        self.inner
            .sender
            .send_packet(
                opcode::FLOOR_PING,
                &FloorPingMsg {
                    room_id: room_id.to_string(),
                },
            )
            .await?;
        Ok(())
    }

    // ================================================================
    //  공개 API — 상태 조회
    // ================================================================

    /// 현재 입장한 방 ID
    pub async fn room_id(&self) -> Option<String> {
        self.inner.room.read().await.room_id.clone()
    }

    /// 현재 방 모드
    pub async fn room_mode(&self) -> RoomMode {
        self.inner.room.read().await.room_mode
    }

    /// SignalSender 참조 (고급 사용: 커스텀 패킷 전송)
    pub fn signal_sender(&self) -> &SignalSender {
        &self.inner.sender
    }
}

// ================================================================
//  이벤트 루프 — tokio::spawn으로 내부 실행
// ================================================================

/// 내부 이벤트 루프 — SignalEvent를 소비하고 ClientEvent를 발행
async fn event_loop(inner: Arc<ClientInner>, mut signal_rx: mpsc::Receiver<SignalEvent>) {
    while let Some(event) = signal_rx.recv().await {
        match event {
            SignalEvent::Connected { heartbeat_interval } => {
                info!(heartbeat_interval, "connected to server");
                let _ = inner.event_tx.send(ClientEvent::Connected).await;
            }

            SignalEvent::Identified => {
                info!("identified");
                let _ = inner.event_tx.send(ClientEvent::Identified).await;
            }

            SignalEvent::RoomCreated { payload } => {
                let room_id = payload
                    .get("room_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let name = payload
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let mode = payload
                    .get("mode")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                info!(room_id, name, mode, "room created");
                let _ = inner
                    .event_tx
                    .send(ClientEvent::RoomCreated {
                        room_id,
                        name,
                        mode,
                    })
                    .await;
            }

            SignalEvent::RoomList { payload } => {
                let rooms = payload
                    .get("rooms")
                    .and_then(|r| r.as_array())
                    .cloned()
                    .unwrap_or_default();
                let _ = inner.event_tx.send(ClientEvent::RoomList { rooms }).await;
            }

            SignalEvent::RoomJoined { payload } => {
                handle_room_joined(&inner, payload).await;
            }

            SignalEvent::RoomLeft { room_id } => {
                handle_room_left(&inner, room_id).await;
            }

            SignalEvent::TracksUpdate { action, tracks } => {
                handle_tracks_update(&inner, &action, tracks).await;
            }

            SignalEvent::FloorResponse { granted, payload } => {
                if granted {
                    let room_id = payload
                        .get("room_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let speaker = payload
                        .get("speaker")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    info!(room_id, speaker, "floor granted");
                    let _ = inner
                        .event_tx
                        .send(ClientEvent::FloorGranted { room_id, speaker })
                        .await;
                } else {
                    let reason = payload
                        .get("msg")
                        .and_then(|v| v.as_str())
                        .unwrap_or("denied")
                        .to_string();
                    warn!(reason, "floor denied");
                    let _ = inner
                        .event_tx
                        .send(ClientEvent::FloorDenied { reason })
                        .await;
                }
            }

            SignalEvent::FloorReleaseResponse => {
                info!("floor released");
                let _ = inner.event_tx.send(ClientEvent::FloorReleased).await;
            }

            SignalEvent::FloorTaken { room_id, user_id } => {
                let _ = inner
                    .event_tx
                    .send(ClientEvent::FloorTaken { room_id, user_id })
                    .await;
            }

            SignalEvent::FloorIdle { room_id } => {
                let _ = inner
                    .event_tx
                    .send(ClientEvent::FloorIdle { room_id })
                    .await;
            }

            SignalEvent::FloorRevoke { room_id } => {
                let _ = inner
                    .event_tx
                    .send(ClientEvent::FloorRevoke { room_id })
                    .await;
            }

            SignalEvent::Error { code, msg } => {
                let _ = inner
                    .event_tx
                    .send(ClientEvent::Error { code, msg })
                    .await;
            }

            SignalEvent::Disconnected { reason } => {
                warn!(reason, "disconnected");
                {
                    let mut media = inner.media.lock().await;
                    media.close();
                }
                {
                    let mut room = inner.room.write().await;
                    room.reset();
                }
                let _ = inner
                    .event_tx
                    .send(ClientEvent::Disconnected { reason })
                    .await;
                break;
            }

            SignalEvent::RoomEvent(_) => {
                // TODO: participant join/leave 이벤트 전달
            }
        }
    }

    info!("event loop exited");
}

// ================================================================
//  내부 핸들러 — ROOM_JOIN 성공 처리 (2PC 셋업)
// ================================================================

/// ROOM_JOIN 성공 → 2PC 셋업 → ClientEvent::RoomJoined 발행
async fn handle_room_joined(inner: &Arc<ClientInner>, payload: serde_json::Value) {
    // 1. RoomJoinResponse 파싱
    let resp: RoomJoinResponse = match serde_json::from_value(payload) {
        Ok(r) => r,
        Err(e) => {
            error!(?e, "RoomJoinResponse parse failed");
            let _ = inner
                .event_tx
                .send(ClientEvent::Error {
                    code: 4001,
                    msg: format!("RoomJoinResponse parse failed: {}", e),
                })
                .await;
            return;
        }
    };

    // 2. room state 업데이트
    let pre_added = {
        let media = inner.media.lock().await;
        media.pub_pc().is_some() && !media.pub_senders_empty()
    };

    info!(
        room_id = %resp.room_id,
        mode = ?resp.mode,
        tracks = resp.tracks.len(),
        pre_added,
        "room joined, starting 2PC setup"
    );

    {
        let mut room = inner.room.write().await;
        room.room_id = Some(resp.room_id.clone());
        room.room_mode = resp.mode;
        room.ptt_virtual_ssrc = resp.ptt_virtual_ssrc.clone();
        room.server_config = Some(resp.server_config.clone());

        // subscribe 트랙 목록 초기화 (mid 할당)
        room.next_mid = 0;
        room.subscribe_tracks = resp
            .tracks
            .iter()
            .map(|t| {
                let mid = room.next_mid;
                room.next_mid += 1;
                TrackDesc {
                    user_id: t.user_id.clone(),
                    kind: t.kind,
                    ssrc: t.ssrc,
                    track_id: t.track_id.clone(),
                    rtx_ssrc: t.rtx_ssrc,
                    mid: Some(mid.to_string()),
                    active: true,
                }
            })
            .collect();
    }

    // 3. Publish PC — SDP 조립 → 협상
    let pub_sdp = sdp::build_publish_remote_sdp(&resp.server_config);
    let answer_sdp = {
        let mut media = inner.media.lock().await;
        match media.setup_publish(&pub_sdp).await {
            Ok(sdp) => sdp,
            Err(e) => {
                error!(?e, "publish PC setup failed");
                let _ = inner
                    .event_tx
                    .send(ClientEvent::Error {
                        code: 4002,
                        msg: format!("publish PC setup failed: {:?}", e),
                    })
                    .await;
                return;
            }
        }
    };

    // 4. SSRC 추출 → PUBLISH_TRACKS 전송
    send_publish_tracks(&inner.sender, &answer_sdp).await;

    // 5. Subscribe PC — SDP 조립 → 협상
    let (sub_sdp, room_id, mode) = {
        let room = inner.room.read().await;
        let sub_sdp = sdp::build_subscribe_remote_sdp(
            &resp.server_config,
            &room.subscribe_tracks,
            room.room_mode,
            room.ptt_virtual_ssrc.as_ref(),
        );
        (sub_sdp, resp.room_id.clone(), resp.mode)
    };

    {
        let mut media = inner.media.lock().await;
        if let Err(e) = media.setup_subscribe(&sub_sdp).await {
            error!(?e, "subscribe PC setup failed");
            let _ = inner
                .event_tx
                .send(ClientEvent::Error {
                    code: 4003,
                    msg: format!("subscribe PC setup failed: {:?}", e),
                })
                .await;
            return;
        }
    }

    info!("2PC setup complete (pre_added={})", pre_added);

    // on_track 수신은 connect() 시점에 spawn된 track_receive_loop가 처리

    let _ = inner
        .event_tx
        .send(ClientEvent::RoomJoined { room_id, mode })
        .await;
}

/// on_track 이벤트 수신 → NativeAudioStream 생성 → 프레임 수신 로그
async fn track_receive_loop(inner: Arc<ClientInner>) {
    let mut track_rx = inner.track_rx.lock().await;

    while let Some(event) = track_rx.recv().await {
        // Audio 트랙만 처리 (Video는 현재 미지원)
        match event.track {
            MediaStreamTrack::Audio(rtc_audio_track) => {
                info!("on_track: audio track received, creating NativeAudioStream");
                let event_tx = inner.event_tx.clone();

                // 프레임 수신 태스크 spawn (트랙당 1개)
                tokio::spawn(async move {
                    let mut audio_stream = NativeAudioStream::new(rtc_audio_track);
                    let mut local_count: u64 = 0;

                    while let Some(frame) = audio_stream.next().await {
                        local_count += 1;
                        // 처음 5프레임 + 이후 500프레임마다(10초) 로그
                        if local_count <= 5 || local_count % 500 == 0 {
                            info!(
                                local_count,
                                sample_rate = frame.sample_rate,
                                channels = frame.num_channels,
                                samples = frame.samples_per_channel,
                                "rx audio frame"
                            );
                        }
                        // 검증용 이벤트 — 처음 1회만 전달
                        if local_count == 1 {
                            let _ = event_tx
                                .send(ClientEvent::AudioFrameReceived {
                                    sample_rate: frame.sample_rate,
                                    num_channels: frame.num_channels,
                                    samples_per_channel: frame.samples_per_channel,
                                })
                                .await;
                        }
                    }
                    info!("audio stream ended (frames={})", local_count);
                });
            }
            _ => {
                info!("on_track: non-audio track received, ignoring");
            }
        }
    }
    info!("track_receive_loop exited");
}

// ================================================================
//  내부 핸들러 — ROOM_LEAVE 처리
// ================================================================

async fn handle_room_left(inner: &Arc<ClientInner>, room_id: String) {
    info!(room_id, "room left");
    {
        let mut media = inner.media.lock().await;
        media.close();
    }
    {
        let mut room = inner.room.write().await;
        room.reset();
    }
    let _ = inner
        .event_tx
        .send(ClientEvent::RoomLeft { room_id })
        .await;
}

// ================================================================
//  내부 핸들러 — TRACKS_UPDATE 처리
// ================================================================

async fn handle_tracks_update(
    inner: &Arc<ClientInner>,
    action: &str,
    tracks: Vec<crate::signaling::message::TrackInfo>,
) {
    info!(action, count = tracks.len(), "tracks_update");

    // room state 업데이트 + subscribe SDP 재조립
    let sub_sdp = {
        let mut room = inner.room.write().await;
        let config = match &room.server_config {
            Some(c) => c.clone(),
            None => {
                warn!("tracks_update before room join, ignoring");
                return;
            }
        };

        match action {
            "add" => {
                for t in &tracks {
                    let existing = room
                        .subscribe_tracks
                        .iter_mut()
                        .find(|st| st.track_id == t.track_id);
                    if let Some(st) = existing {
                        st.ssrc = t.ssrc;
                        st.active = true;
                    } else {
                        let mid = room.next_mid;
                        room.next_mid += 1;
                        room.subscribe_tracks.push(TrackDesc {
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
                    if let Some(st) = room
                        .subscribe_tracks
                        .iter_mut()
                        .find(|st| st.track_id == t.track_id)
                    {
                        st.active = false;
                    }
                }
            }
            _ => {
                warn!(action, "unknown tracks_update action");
            }
        }

        sdp::build_subscribe_remote_sdp(
            &config,
            &room.subscribe_tracks,
            room.room_mode,
            room.ptt_virtual_ssrc.as_ref(),
        )
    };

    // subscribe PC re-nego
    {
        let mut media = inner.media.lock().await;
        if let Err(e) = media.setup_subscribe(&sub_sdp).await {
            error!(?e, "subscribe re-nego failed");
            let _ = inner
                .event_tx
                .send(ClientEvent::Error {
                    code: 4003,
                    msg: format!("subscribe re-nego failed: {:?}", e),
                })
                .await;
            return;
        }
    }

    let _ = inner
        .event_tx
        .send(ClientEvent::TracksUpdated {
            action: action.to_string(),
            count: tracks.len(),
        })
        .await;
}

// ================================================================
//  유틸: SSRC 추출 + PUBLISH_TRACKS 전송
// ================================================================

/// answer SDP에서 각 m-line의 SSRC를 추출하여 PUBLISH_TRACKS 전송
async fn send_publish_tracks(sender: &SignalSender, answer_sdp: &str) {
    let mut tracks = Vec::new();

    for section in answer_sdp.split("\r\nm=").skip(1) {
        let kind = if section.starts_with("audio") {
            "audio"
        } else if section.starts_with("video") {
            "video"
        } else {
            continue;
        };

        for line in section.lines() {
            if let Some(rest) = line.strip_prefix("a=ssrc:") {
                if let Some(ssrc_str) = rest.split_whitespace().next() {
                    if let Ok(ssrc) = ssrc_str.parse::<u32>() {
                        info!(kind, ssrc, "publish track SSRC extracted");
                        tracks.push(PublishTrackItem {
                            kind: kind.to_string(),
                            ssrc,
                        });
                        break;
                    }
                }
            }
        }
    }

    if !tracks.is_empty() {
        if let Err(e) = sender
            .send_packet(opcode::PUBLISH_TRACKS, &PublishTracksRequest { tracks })
            .await
        {
            error!(?e, "PUBLISH_TRACKS send failed");
        }
    }
}