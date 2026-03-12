// author: kodeholic (powered by Claude)
//! bench — OxLensClient SDK 통합 테스트
//!
//! OxLensClient 오케스트레이터를 사용하여 서버 연동을 검증한다.
//! 이전 low-level 버전과 달리, SDK 사용자가 실제로 쓸 패턴과 동일.
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

use oxlens_core::{ClientConfig, ClientEvent, OxLensClient};
use oxlens_core::sdp::types::RoomMode;
use oxlens_webrtc::{AudioFrame, AudioSourceOptions};

use tokio::signal;
use tracing::{error, info, warn};

/// 오디오 프레임 상수
/// libwebrtc 내부 처리 단위가 10ms/480samples — 맞춰주면 리샘플링 제거
const SAMPLE_RATE: u32 = 48_000;
const NUM_CHANNELS: u32 = 1;
const SAMPLES_PER_CHANNEL: u32 = 480; // 10ms @ 48kHz (libwebrtc 내부 단위)
const FRAME_INTERVAL_MS: u64 = 10;

/// bench 실행 모드
#[derive(Debug, Clone, PartialEq)]
enum BenchMode {
    Conference,
    Ptt,
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
        server_url, room_name, user_id, mode = ?bench_mode,
        "=== oxlens-sdk-core bench start ==="
    );

    // ================================================================
    //  1. OxLensClient 연결
    // ================================================================
    let (client, mut events) = OxLensClient::connect(ClientConfig {
        server_url,
        token: "bench-token".to_string(),
        user_id: Some(user_id),
    })
    .await?;

    info!("✓ OxLensClient connected");

    // ================================================================
    //  2. 오디오 트랙 추가
    // ================================================================
    let audio_source = client.add_audio_source(AudioSourceOptions {
        echo_cancellation: false,
        noise_suppression: false,
        auto_gain_control: false,
    }).await?;

    info!("✓ audio track added to publish PC");

    // ================================================================
    //  3. 더미 오디오 프레임 주입 태스크 (20ms 주기 무음)
    // ================================================================
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
            audio_source.capture_frame(&silence_frame);
            frame_count += 1;
            if frame_count % 1000 == 0 {
                info!(frame_count, "audio frames injected (silence, 10s)");
            }
        }
    });

    // ================================================================
    //  4. 상태 추적
    // ================================================================
    let mut joined_room_id: Option<String> = None;
    let mut is_ptt_mode = false;
    let mut floor_ping_handle: Option<tokio::task::JoinHandle<()>> = None;

    // ================================================================
    //  5. 이벤트 루프
    // ================================================================
    loop {
        tokio::select! {
            event = events.recv() => {
                let event = match event {
                    Some(e) => e,
                    None => {
                        warn!("event channel closed");
                        break;
                    }
                };

                match event {
                    ClientEvent::Connected => {
                        info!("✓ HELLO received");
                    }

                    ClientEvent::Identified => {
                        info!("✓ Identified");
                        match bench_mode {
                            BenchMode::Conference | BenchMode::Ptt => {
                                let mode_str = if bench_mode == BenchMode::Ptt {
                                    "ptt"
                                } else {
                                    "conference"
                                };
                                info!("→ ROOM_CREATE ({}) '{}'", mode_str, room_name);
                                client
                                    .create_room(&room_name, 30, mode_str)
                                    .await?;
                            }
                            BenchMode::List => {
                                info!("→ ROOM_LIST");
                                client.list_rooms().await?;
                            }
                        }
                    }

                    ClientEvent::RoomCreated { room_id, name, mode } => {
                        info!("✓ ROOM_CREATE ok: {} (id={}, mode={})", name, room_id, mode);
                        client.join_room(&room_id).await?;
                    }

                    ClientEvent::RoomList { rooms } => {
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
                                .find(|r| {
                                    r.get("name").and_then(|v| v.as_str()) == Some(&room_name)
                                })
                                .or_else(|| rooms.first());

                            if let Some(target_room) = target {
                                let rid = target_room
                                    .get("room_id")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("");
                                let rname = target_room
                                    .get("name")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("?");
                                info!("✓ joining room: {} (id={})", rname, rid);
                                client.join_room(rid).await?;
                            }
                        }
                    }

                    ClientEvent::RoomJoined { room_id, mode } => {
                        info!(
                            "✓ ROOM_JOIN + 2PC complete: {} ({:?})",
                            room_id, mode
                        );
                        is_ptt_mode = mode == RoomMode::Ptt;
                        joined_room_id = Some(room_id.clone());

                        if is_ptt_mode {
                            info!("PTT mode — sending FLOOR_REQUEST");
                            client.request_floor(&room_id).await?;
                        } else {
                            info!("=== media flowing, press Ctrl+C to stop ===");
                        }
                    }

                    ClientEvent::FloorGranted { room_id, speaker } => {
                        info!("✓ FLOOR granted — speaker={}", speaker);

                        // FLOOR_PING 주기 태스크 — client.clone()으로 전달!
                        let ping_client = client.clone();
                        let ping_room_id = room_id.clone();
                        if let Some(h) = floor_ping_handle.take() {
                            h.abort();
                        }
                        floor_ping_handle = Some(tokio::spawn(async move {
                            let mut interval =
                                tokio::time::interval(Duration::from_millis(2000));
                            loop {
                                interval.tick().await;
                                if ping_client
                                    .floor_ping(&ping_room_id)
                                    .await
                                    .is_err()
                                {
                                    break;
                                }
                            }
                        }));
                        info!("FLOOR_PING task started (2s interval)");
                        info!("=== media flowing, press Ctrl+C to stop ===");
                    }

                    ClientEvent::FloorDenied { reason } => {
                        warn!("FLOOR_REQUEST denied: {}", reason);
                    }

                    ClientEvent::FloorTaken { room_id, user_id } => {
                        info!("floor taken: room={}, speaker={}", room_id, user_id);
                    }

                    ClientEvent::FloorIdle { room_id } => {
                        info!("floor idle: room={}", room_id);
                    }

                    ClientEvent::FloorRevoke { room_id } => {
                        warn!("floor revoked: room={}", room_id);
                        if let Some(h) = floor_ping_handle.take() {
                            h.abort();
                            info!("FLOOR_PING task stopped (revoked)");
                        }
                    }

                    ClientEvent::FloorReleased => {
                        info!("✓ FLOOR_RELEASE ok");
                    }

                    ClientEvent::TracksUpdated { action, count } => {
                        info!("tracks_update: action={}, count={}", action, count);
                    }

                    ClientEvent::AudioFrameReceived { sample_rate, num_channels, samples_per_channel } => {
                        info!(
                            "✓ FIRST rx audio frame: rate={}, ch={}, samples={}",
                            sample_rate, num_channels, samples_per_channel
                        );
                    }

                    ClientEvent::RoomLeft { room_id } => {
                        info!("room left: {}", room_id);
                        joined_room_id = None;
                        is_ptt_mode = false;
                        if let Some(h) = floor_ping_handle.take() {
                            h.abort();
                        }
                    }

                    ClientEvent::Error { code, msg } => {
                        error!("server error: code={}, msg={}", code, msg);
                    }

                    ClientEvent::Disconnected { reason } => {
                        warn!("disconnected: {}", reason);
                        break;
                    }
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
                        let _ = client.release_floor(rid).await;
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    }
                }
                break;
            }
        }
    }

    // 정리
    audio_task.abort();
    info!("=== bench finished ===");
    Ok(())
}

fn get_arg(args: &[String], key: &str) -> Option<String> {
    args.iter()
        .position(|a| a == key)
        .and_then(|i| args.get(i + 1))
        .cloned()
}