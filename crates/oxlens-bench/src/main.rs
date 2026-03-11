// author: kodeholic (powered by Claude)
//! bench — PC 더미 연동 테스트 (Low-level API)
//!
//! OxLensClient 오케스트레이터를 우회하고, SignalClient + MediaSession을
//! 직접 조립하여 각 단계를 개별 제어한다.
//!
//! 사용법:
//!   cargo run -p oxlens-bench                                    # conference 모드 (방 생성)
//!   cargo run -p oxlens-bench -- --mode ptt                      # PTT 모드 (방 생성 + FLOOR)
//!   cargo run -p oxlens-bench -- --mode list --room "무전 대화방"  # 기존 방 목록에서 JOIN
//!   cargo run -p oxlens-bench -- --url ws://192.168.1.100:1974/ws
//!
//! 환경변수:
//!   RUST_LOG=debug  (상세 로그)

use std::time::Duration;

use oxlens_core::sdp;
use oxlens_core::sdp::types::{RoomJoinResponse, TrackDesc};
use oxlens_core::signaling::client::{SignalClient, SignalConfig, SignalEvent};
use oxlens_core::signaling::message::{
    FloorPingMsg, FloorRequestMsg, FloorReleaseMsg,
    PublishTrackItem, PublishTracksRequest,
    RoomCreateRequest, RoomJoinRequest,
};
use oxlens_core::signaling::opcode;

use oxlens_webrtc::{AudioFrame, AudioSourceOptions, MediaSession};

use tokio::signal;
use tracing::{error, info, warn};

/// 오디오 프레임 상수
const SAMPLE_RATE: u32 = 48_000;
const NUM_CHANNELS: u32 = 1;
const SAMPLES_PER_CHANNEL: u32 = 960; // 20ms @ 48kHz
const FRAME_INTERVAL_MS: u64 = 20;

/// bench 실행 모드
#[derive(Debug, Clone, PartialEq)]
enum BenchMode {
    /// 새 방 생성 (conference)
    Conference,
    /// 새 방 생성 (PTT) + FLOOR_REQUEST
    Ptt,
    /// 기존 방 목록에서 JOIN (--room 이름 매칭)
    List,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| {
                    "info,oxlens_core=debug,oxlens_webrtc=debug,bench=debug"
                        .parse()
                        .unwrap()
                }),
        )
        .init();

    // CLI 인자 파싱
    let args: Vec<String> = std::env::args().collect();
    let server_url =
        get_arg(&args, "--url").unwrap_or_else(|| "ws://127.0.0.1:1974/ws".to_string());
    let room_name = get_arg(&args, "--room").unwrap_or_else(|| "bench-room".to_string());
    let user_id =
        get_arg(&args, "--user").unwrap_or_else(|| format!("bench-{}", std::process::id()));
    let bench_mode = match get_arg(&args, "--mode").as_deref() {
        Some("ptt") => BenchMode::Ptt,
        Some("list") => BenchMode::List,
        _ => BenchMode::Conference,
    };

    info!(
        server_url,
        room_name,
        user_id,
        mode = ?bench_mode,
        "=== oxlens-sdk-core bench start ==="
    );

    // ================================================================
    //  1. MediaSession 생성 + 오디오 트랙 추가
    // ================================================================
    let mut media = MediaSession::new()
        .map_err(|e| anyhow::anyhow!("MediaSession init failed: {:?}", e))?;

    let audio_source = media
        .add_audio_source(AudioSourceOptions {
            echo_cancellation: false,
            noise_suppression: false,
            auto_gain_control: false,
        })
        .map_err(|e| anyhow::anyhow!("add_audio_source failed: {:?}", e))?;

    info!("✓ MediaSession created, audio track added to publish PC");

    // ================================================================
    //  2. 더미 오디오 프레임 주입 태스크 (20ms 주기 무음)
    // ================================================================
    let audio_source_for_task = audio_source.clone();
    let audio_task = tokio::spawn(async move {
        let silence_frame = AudioFrame {
            data: vec![0i16; (SAMPLES_PER_CHANNEL * NUM_CHANNELS) as usize],
            sample_rate: SAMPLE_RATE,
            num_channels: NUM_CHANNELS,
            samples_per_channel: SAMPLES_PER_CHANNEL,
        };

        let mut interval = tokio::time::interval(Duration::from_millis(FRAME_INTERVAL_MS));
        let mut frame_count: u64 = 0;
        loop {
            interval.tick().await;
            audio_source_for_task.capture_frame(&silence_frame);
            frame_count += 1;
            if frame_count % 500 == 0 {
                info!(frame_count, "audio frames injected (silence, 10s)");
            }
        }
    });

    // ================================================================
    //  3. 시그널링 연결
    // ================================================================
    let mut signal = SignalClient::new(SignalConfig {
        server_url: server_url.clone(),
        token: "bench-token".to_string(),
        user_id: Some(user_id.clone()),
    });

    let mut signal_rx = signal.connect().await?;
    info!("✓ WebSocket connected");

    // 상태 추적
    let mut joined_room_id: Option<String> = None;
    let mut is_ptt_mode = false;
    let mut floor_ping_handle: Option<tokio::task::JoinHandle<()>> = None;

    // ================================================================
    //  4. 이벤트 루프
    // ================================================================
    loop {
        tokio::select! {
            event = signal_rx.recv() => {
                let event = match event {
                    Some(e) => e,
                    None => {
                        warn!("signal channel closed");
                        break;
                    }
                };

                match event {
                    SignalEvent::Connected { heartbeat_interval } => {
                        info!(heartbeat_interval, "✓ HELLO received, IDENTIFY sent");
                    }

                    SignalEvent::Identified => {
                        match bench_mode {
                            BenchMode::Conference | BenchMode::Ptt => {
                                let mode_str = if bench_mode == BenchMode::Ptt {
                                    "ptt"
                                } else {
                                    "conference"
                                };
                                info!("✓ Identified — ROOM_CREATE ({}) '{}'",
                                    mode_str, room_name);
                                signal
                                    .send_request(opcode::ROOM_CREATE, &RoomCreateRequest {
                                        name: room_name.clone(),
                                        capacity: Some(30),
                                        mode: Some(mode_str.to_string()),
                                    })
                                    .await?;
                            }
                            BenchMode::List => {
                                info!("✓ Identified — ROOM_LIST");
                                signal
                                    .send_request(opcode::ROOM_LIST, &serde_json::json!({}))
                                    .await?;
                            }
                        }
                    }

                    SignalEvent::RoomCreated { payload } => {
                        let rid = payload.get("room_id")
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        let rname = payload.get("name")
                            .and_then(|v| v.as_str())
                            .unwrap_or("?");
                        let rmode = payload.get("mode")
                            .and_then(|v| v.as_str())
                            .unwrap_or("?");
                        info!("✓ ROOM_CREATE ok: {} (id={}, mode={})", rname, rid, rmode);

                        // 바로 JOIN
                        signal
                            .send_request(opcode::ROOM_JOIN, &RoomJoinRequest {
                                room_id: rid.to_string(),
                            })
                            .await?;
                    }

                    SignalEvent::RoomList { payload } => {
                        let rooms = payload.get("rooms")
                            .and_then(|r| r.as_array())
                            .cloned()
                            .unwrap_or_default();

                        if rooms.is_empty() {
                            warn!("no rooms available on server");
                        } else {
                            for r in &rooms {
                                info!("  room: id={} name={} mode={}",
                                    r.get("room_id").and_then(|v| v.as_str()).unwrap_or("?"),
                                    r.get("name").and_then(|v| v.as_str()).unwrap_or("?"),
                                    r.get("mode").and_then(|v| v.as_str()).unwrap_or("?"),
                                );
                            }

                            let target = rooms.iter()
                                .find(|r| r.get("name").and_then(|v| v.as_str()) == Some(&room_name))
                                .or_else(|| rooms.first());

                            if let Some(target_room) = target {
                                let rid = target_room.get("room_id")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("");
                                let rname = target_room.get("name")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("?");
                                info!("✓ joining room: {} (id={})", rname, rid);
                                signal
                                    .send_request(opcode::ROOM_JOIN, &RoomJoinRequest {
                                        room_id: rid.to_string(),
                                    })
                                    .await?;
                            }
                        }
                    }

                    SignalEvent::RoomJoined { payload } => {
                        let (rid, ptt) = handle_room_joined(
                            payload, &mut media, &signal
                        ).await?;
                        joined_room_id = Some(rid.clone());
                        is_ptt_mode = ptt;

                        // PTT 모드면 2PC 셋업 완료 후 FLOOR_REQUEST
                        if is_ptt_mode {
                            info!("PTT mode — sending FLOOR_REQUEST");
                            signal
                                .send_request(opcode::FLOOR_REQUEST, &FloorRequestMsg {
                                    room_id: rid,
                                })
                                .await?;
                        }
                    }

                    SignalEvent::FloorResponse { granted, payload } => {
                        if granted {
                            let speaker = payload.get("speaker")
                                .and_then(|v| v.as_str())
                                .unwrap_or("?");
                            info!("✓ FLOOR_REQUEST granted — speaker={}", speaker);

                            // FLOOR_PING 태스크 시작 (2초 주기)
                            if let Some(ref rid) = joined_room_id {
                                let ping_signal = signal.clone_sender();
                                let ping_room_id = rid.clone();
                                if let Some(h) = floor_ping_handle.take() {
                                    h.abort();
                                }
                                floor_ping_handle = Some(tokio::spawn(async move {
                                    let mut interval = tokio::time::interval(
                                        Duration::from_millis(2000)
                                    );
                                    loop {
                                        interval.tick().await;
                                        if ping_signal
                                            .send_packet(opcode::FLOOR_PING, &FloorPingMsg {
                                                room_id: ping_room_id.clone(),
                                            })
                                            .await
                                            .is_err()
                                        {
                                            break;
                                        }
                                    }
                                }));
                                info!("FLOOR_PING task started (2s interval)");
                            }
                        } else {
                            warn!("FLOOR_REQUEST denied: {:?}", payload);
                        }
                    }

                    SignalEvent::FloorReleaseResponse => {
                        info!("✓ FLOOR_RELEASE ok");
                    }

                    SignalEvent::FloorTaken { room_id, user_id } => {
                        info!("floor taken: room={}, speaker={}", room_id, user_id);
                    }

                    SignalEvent::FloorIdle { room_id } => {
                        info!("floor idle: room={}", room_id);
                    }

                    SignalEvent::FloorRevoke { room_id } => {
                        warn!("floor revoked: room={}", room_id);
                        // PING 태스크 중단
                        if let Some(h) = floor_ping_handle.take() {
                            h.abort();
                            info!("FLOOR_PING task stopped (revoked)");
                        }
                    }

                    SignalEvent::TracksUpdate { action, tracks } => {
                        info!("tracks_update: action={}, count={}", action, tracks.len());
                    }

                    SignalEvent::RoomEvent(evt) => {
                        info!("room event: {:?}", evt);
                    }

                    SignalEvent::Error { code, msg } => {
                        error!("server error: code={}, msg={}", code, msg);
                    }

                    SignalEvent::Disconnected { reason } => {
                        warn!("disconnected: {}", reason);
                        break;
                    }

                    _ => {}
                }
            }

            _ = signal::ctrl_c() => {
                info!("Ctrl+C received, shutting down...");
                // FLOOR_PING 태스크 중단
                if let Some(h) = floor_ping_handle.take() {
                    h.abort();
                }
                // PTT 모드면 FLOOR_RELEASE 먼저
                if is_ptt_mode {
                    if let Some(ref rid) = joined_room_id {
                        info!("sending FLOOR_RELEASE before exit");
                        let _ = signal
                            .send_request(opcode::FLOOR_RELEASE, &FloorReleaseMsg {
                                room_id: rid.clone(),
                            })
                            .await;
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    }
                }
                break;
            }
        }
    }

    // 정리
    audio_task.abort();
    media.close();
    info!("=== bench finished ===");
    Ok(())
}

/// ROOM_JOIN 성공 처리 — 2PC 셋업
/// 반환: (room_id, is_ptt)
async fn handle_room_joined(
    payload: serde_json::Value,
    media: &mut MediaSession,
    signal: &SignalClient,
) -> anyhow::Result<(String, bool)> {
    let resp: RoomJoinResponse = serde_json::from_value(payload)?;
    let is_ptt = resp.mode == oxlens_core::sdp::types::RoomMode::Ptt;
    info!(
        room_id = %resp.room_id,
        mode = ?resp.mode,
        tracks = resp.tracks.len(),
        "✓ ROOM_JOIN response"
    );

    // Publish SDP 조립 → 협상
    let pub_sdp = sdp::build_publish_remote_sdp(&resp.server_config);
    let answer_sdp = media
        .setup_publish(&pub_sdp)
        .await
        .map_err(|e| anyhow::anyhow!("setup_publish failed: {:?}", e))?;
    info!("✓ Publish PC: offer→answer OK ({} bytes)", answer_sdp.len());

    // SSRC 추출 → PUBLISH_TRACKS
    let pub_tracks = extract_ssrc_from_sdp(&answer_sdp);
    if !pub_tracks.is_empty() {
        info!("SSRCs: {:?}", pub_tracks);
        signal
            .send_request(
                opcode::PUBLISH_TRACKS,
                &PublishTracksRequest { tracks: pub_tracks },
            )
            .await?;
        info!("✓ PUBLISH_TRACKS sent");
    } else {
        warn!("no SSRC found in answer SDP!");
    }

    // Subscribe PC
    if !resp.tracks.is_empty() {
        let mut next_mid: u32 = 0;
        let sub_tracks: Vec<TrackDesc> = resp
            .tracks
            .iter()
            .map(|t| {
                let mid = next_mid;
                next_mid += 1;
                TrackDesc {
                    user_id: t.user_id.clone(),
                    kind: t.kind,
                    ssrc: t.ssrc,
                    track_id: t.track_id.clone(),
                    rtx_ssrc: None,
                    mid: Some(mid.to_string()),
                    active: true,
                }
            })
            .collect();

        let sub_sdp = sdp::build_subscribe_remote_sdp(
            &resp.server_config,
            &sub_tracks,
            resp.mode,
            resp.ptt_virtual_ssrc.as_ref(),
        );

        media
            .setup_subscribe(&sub_sdp)
            .await
            .map_err(|e| anyhow::anyhow!("setup_subscribe failed: {:?}", e))?;
        info!("✓ Subscribe PC: {} tracks negotiated", sub_tracks.len());
    } else {
        info!("no existing tracks to subscribe");
    }

    info!("=== 2PC setup complete, media flowing ===");
    info!("press Ctrl+C to stop");
    Ok((resp.room_id, is_ptt))
}

/// answer SDP에서 각 m-line의 SSRC를 추출
fn extract_ssrc_from_sdp(answer_sdp: &str) -> Vec<PublishTrackItem> {
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
                        info!(kind, ssrc, "SSRC extracted");
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
    tracks
}

fn get_arg(args: &[String], key: &str) -> Option<String> {
    args.iter()
        .position(|a| a == key)
        .and_then(|i| args.get(i + 1))
        .cloned()
}
