// author: kodeholic (powered by Claude)
//! WebSocket signaling client
//!
//! 서버와 opcode 기반 JSON 메시지를 교환한다.
//! 연결 → HELLO 수신 → IDENTIFY → HEARTBEAT 루프

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message, MaybeTlsStream, WebSocketStream};
use tracing::{debug, error, info, warn};

use super::message::*;
use super::opcode;

/// 시그널링 이벤트 — 상위 레이어(OxLensClient)로 전달
#[derive(Debug)]
pub enum SignalEvent {
    Connected { heartbeat_interval: u64 },
    Identified,
    /// ROOM_JOIN 성공 — 전체 payload를 serde_json::Value로 전달
    /// OxLensClient가 RoomJoinResponse로 파싱하여 2PC 셋업에 사용
    RoomJoined { payload: serde_json::Value },
    RoomLeft { room_id: String },
    /// TRACKS_UPDATE — action + tracks (re-nego 트리거)
    TracksUpdate { action: String, tracks: Vec<TrackInfo> },
    FloorTaken { room_id: String, user_id: String },
    FloorIdle { room_id: String },
    FloorRevoke { room_id: String },
    RoomEvent(RoomEventPayload),
    Error { code: u16, msg: String },
    Disconnected { reason: String },
}

/// 시그널링 클라이언트 설정
#[derive(Debug, Clone)]
pub struct SignalConfig {
    pub server_url: String,
    pub token: String,
    pub user_id: Option<String>,
}

/// 시그널링 클라이언트
pub struct SignalClient {
    config: SignalConfig,
    pid_counter: Arc<AtomicU64>,
    /// 외부에서 보내는 커맨드 (send_packet 용)
    cmd_tx: Option<mpsc::Sender<Packet>>,
}

impl SignalClient {
    pub fn new(config: SignalConfig) -> Self {
        Self {
            config,
            pid_counter: Arc::new(AtomicU64::new(1)),
            cmd_tx: None,
        }
    }

    /// 다음 pid 생성 (atomic increment)
    fn next_pid(&self) -> u64 {
        self.pid_counter.fetch_add(1, Ordering::Relaxed)
    }

    /// 패킷 전송 (연결된 상태에서)
    pub async fn send(&self, packet: Packet) -> anyhow::Result<()> {
        if let Some(tx) = &self.cmd_tx {
            tx.send(packet).await?;
            Ok(())
        } else {
            anyhow::bail!("not connected")
        }
    }

    /// 편의 메서드: opcode + payload로 패킷 전송
    pub async fn send_request<T: serde::Serialize>(
        &self,
        op: u16,
        payload: &T,
    ) -> anyhow::Result<u64> {
        let pid = self.next_pid();
        let d = serde_json::to_value(payload)?;
        self.send(Packet::new(op, pid, d)).await?;
        Ok(pid)
    }

    /// 연결 + 이벤트 루프 시작
    /// 반환된 rx에서 SignalEvent를 수신한다.
    pub async fn connect(&mut self) -> anyhow::Result<mpsc::Receiver<SignalEvent>> {
        let url = &self.config.server_url;
        info!(url, "connecting to signaling server");

        let (ws_stream, _response) = connect_async(url).await?;
        info!("websocket connected");

        let (ws_sink, ws_stream) = ws_stream.split();

        // 커맨드 채널 (외부 → WS 송신)
        let (cmd_tx, cmd_rx) = mpsc::channel::<Packet>(64);
        self.cmd_tx = Some(cmd_tx);

        // 이벤트 채널 (WS 수신 → 외부)
        let (event_tx, event_rx) = mpsc::channel::<SignalEvent>(64);

        // 송신 태스크
        let _send_task = tokio::spawn(send_loop(ws_sink, cmd_rx));

        // 수신 태스크
        let config = self.config.clone();
        let pid_counter = self.pid_counter.clone();
        let cmd_tx_for_recv = self.cmd_tx.clone().unwrap();
        let _recv_task = tokio::spawn(recv_loop(
            ws_stream,
            event_tx,
            cmd_tx_for_recv,
            config,
            pid_counter,
        ));

        Ok(event_rx)
    }
}

/// WS 송신 루프: cmd_rx에서 Packet을 받아 JSON으로 직렬화 후 전송
async fn send_loop(
    mut sink: futures_util::stream::SplitSink<
        WebSocketStream<MaybeTlsStream<TcpStream>>,
        Message,
    >,
    mut cmd_rx: mpsc::Receiver<Packet>,
) {
    while let Some(packet) = cmd_rx.recv().await {
        let op_name = opcode::name(packet.op);
        match serde_json::to_string(&packet) {
            Ok(json) => {
                debug!(op = op_name, pid = packet.pid, "→ send");
                if let Err(e) = sink.send(Message::Text(json)).await {
                    error!(?e, "ws send failed");
                    break;
                }
            }
            Err(e) => {
                error!(?e, "packet serialize failed");
            }
        }
    }
    debug!("send_loop exited");
}

/// WS 수신 루프: 서버 패킷을 파싱하고, HELLO → IDENTIFY, HEARTBEAT 자동 처리
async fn recv_loop(
    mut stream: futures_util::stream::SplitStream<
        WebSocketStream<MaybeTlsStream<TcpStream>>,
    >,
    event_tx: mpsc::Sender<SignalEvent>,
    cmd_tx: mpsc::Sender<Packet>,
    config: SignalConfig,
    pid_counter: Arc<AtomicU64>,
) {
    let mut heartbeat_handle: Option<tokio::task::JoinHandle<()>> = None;

    while let Some(msg) = stream.next().await {
        let msg = match msg {
            Ok(m) => m,
            Err(e) => {
                error!(?e, "ws recv error");
                let _ = event_tx
                    .send(SignalEvent::Disconnected {
                        reason: e.to_string(),
                    })
                    .await;
                break;
            }
        };

        let text = match msg {
            Message::Text(t) => t,
            Message::Close(frame) => {
                let reason = frame
                    .map(|f| f.reason.to_string())
                    .unwrap_or_else(|| "closed".into());
                info!(reason, "ws closed by server");
                let _ = event_tx
                    .send(SignalEvent::Disconnected { reason })
                    .await;
                break;
            }
            Message::Ping(_) | Message::Pong(_) => continue,
            _ => continue,
        };

        let packet: Packet = match serde_json::from_str(&text) {
            Ok(p) => p,
            Err(e) => {
                warn!(?e, "invalid packet json");
                continue;
            }
        };

        let op_name = opcode::name(packet.op);
        debug!(op = op_name, pid = packet.pid, ok = ?packet.ok, "← recv");

        match packet.op {
            // --- HELLO: 서버가 보내는 첫 메시지 ---
            opcode::HELLO => {
                if let Ok(hello) = serde_json::from_value::<HelloEvent>(packet.d) {
                    info!(
                        heartbeat_interval = hello.heartbeat_interval,
                        "received HELLO"
                    );

                    // IDENTIFY 자동 전송
                    let pid = pid_counter.fetch_add(1, Ordering::Relaxed);
                    let identify = IdentifyRequest {
                        token: config.token.clone(),
                        user_id: config.user_id.clone(),
                    };
                    let d = serde_json::to_value(&identify).unwrap();
                    let _ = cmd_tx.send(Packet::new(opcode::IDENTIFY, pid, d)).await;

                    // HEARTBEAT 타이머 시작
                    let interval_ms = hello.heartbeat_interval;
                    let cmd_tx2 = cmd_tx.clone();
                    let pid_counter2 = pid_counter.clone();

                    if let Some(h) = heartbeat_handle.take() {
                        h.abort();
                    }
                    heartbeat_handle = Some(tokio::spawn(async move {
                        let mut interval =
                            tokio::time::interval(std::time::Duration::from_millis(interval_ms));
                        loop {
                            interval.tick().await;
                            let pid = pid_counter2.fetch_add(1, Ordering::Relaxed);
                            let pkt =
                                Packet::new(opcode::HEARTBEAT, pid, serde_json::Value::Null);
                            if cmd_tx2.send(pkt).await.is_err() {
                                break;
                            }
                            debug!("heartbeat sent");
                        }
                    }));

                    let _ = event_tx
                        .send(SignalEvent::Connected {
                            heartbeat_interval: interval_ms,
                        })
                        .await;
                }
            }

            // --- IDENTIFY 응답 ---
            opcode::IDENTIFY if packet.is_response() => {
                if packet.is_ok() {
                    info!("identified successfully");
                    let _ = event_tx.send(SignalEvent::Identified).await;
                } else {
                    let code = packet.d.get("code").and_then(|v| v.as_u64()).unwrap_or(0) as u16;
                    let msg = packet
                        .d
                        .get("msg")
                        .and_then(|v| v.as_str())
                        .unwrap_or("unknown")
                        .to_string();
                    error!(code, msg, "identify failed");
                    let _ = event_tx.send(SignalEvent::Error { code, msg }).await;
                }
            }

            // --- HEARTBEAT ACK ---
            opcode::HEARTBEAT if packet.is_response() => {
                debug!("heartbeat ack");
            }

            // --- ROOM_JOIN 응답 ---
            // 전체 payload를 넘김 — OxLensClient가 RoomJoinResponse로 파싱
            opcode::ROOM_JOIN if packet.is_response() => {
                if packet.is_ok() {
                    let room_id = packet
                        .d
                        .get("room_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("");
                    info!(room_id, "room joined");
                    let _ = event_tx
                        .send(SignalEvent::RoomJoined { payload: packet.d })
                        .await;
                } else {
                    let code = packet.d.get("code").and_then(|v| v.as_u64()).unwrap_or(0) as u16;
                    let msg = packet
                        .d
                        .get("msg")
                        .and_then(|v| v.as_str())
                        .unwrap_or("unknown")
                        .to_string();
                    let _ = event_tx.send(SignalEvent::Error { code, msg }).await;
                }
            }

            // --- ROOM_LEAVE 응답 ---
            opcode::ROOM_LEAVE if packet.is_response() => {
                if packet.is_ok() {
                    let room_id = packet
                        .d
                        .get("room_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let _ = event_tx
                        .send(SignalEvent::RoomLeft { room_id })
                        .await;
                }
            }

            // --- TRACKS_UPDATE 이벤트 ---
            opcode::TRACKS_UPDATE => {
                let action = packet
                    .d
                    .get("action")
                    .and_then(|v| v.as_str())
                    .unwrap_or("add")
                    .to_string();
                if let Ok(tracks) = serde_json::from_value::<Vec<TrackInfo>>(
                    packet.d.get("tracks").cloned().unwrap_or_default(),
                ) {
                    let _ = event_tx
                        .send(SignalEvent::TracksUpdate { action, tracks })
                        .await;
                }
            }

            // --- ROOM_EVENT ---
            opcode::ROOM_EVENT => {
                if let Ok(evt) = serde_json::from_value::<RoomEventPayload>(packet.d) {
                    let _ = event_tx.send(SignalEvent::RoomEvent(evt)).await;
                }
            }

            // --- Floor Control Events ---
            opcode::FLOOR_TAKEN => {
                if let Ok(evt) = serde_json::from_value::<FloorTakenEvent>(packet.d) {
                    let _ = event_tx
                        .send(SignalEvent::FloorTaken {
                            room_id: evt.room_id,
                            user_id: evt.user_id,
                        })
                        .await;
                }
            }
            opcode::FLOOR_IDLE => {
                if let Ok(evt) = serde_json::from_value::<FloorIdleEvent>(packet.d) {
                    let _ = event_tx
                        .send(SignalEvent::FloorIdle {
                            room_id: evt.room_id,
                        })
                        .await;
                }
            }
            opcode::FLOOR_REVOKE => {
                let room_id = packet
                    .d
                    .get("room_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let _ = event_tx
                    .send(SignalEvent::FloorRevoke { room_id })
                    .await;
            }

            _ => {
                debug!(op = packet.op, "unhandled opcode");
            }
        }
    }

    // 정리
    if let Some(h) = heartbeat_handle.take() {
        h.abort();
    }
    debug!("recv_loop exited");
}
