// author: kodeholic (powered by Claude)
//! 이벤트 펌프 — ClientEvent를 Kotlin OxLensEventListener 콜백으로 전달
//!
//! Rust tokio 태스크에서 실행되므로 JNI AttachCurrentThread가 필요.
//! GlobalRef로 리스너를 잡고, 이벤트마다 JNI 메서드 호출.

use jni::objects::GlobalRef;
use jni::objects::JValue;
use tokio::sync::mpsc;
use log::{error, info};

use oxlens_core::ClientEvent;

use crate::jvm;

/// Kotlin OxLensEventListener의 메서드 시그니처 상수
mod sig {
    pub const VOID: &str = "()V";
    pub const STRING_VOID: &str = "(Ljava/lang/String;)V";
    pub const STRING_STRING_VOID: &str = "(Ljava/lang/String;Ljava/lang/String;)V";
    pub const STRING_STRING_STRING_VOID: &str =
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
    pub const INT_STRING_VOID: &str = "(ILjava/lang/String;)V";
    pub const STRING_INT_VOID: &str = "(Ljava/lang/String;I)V";
}

/// 이벤트 펌프 — tokio::spawn으로 실행
///
/// event_rx에서 ClientEvent를 수신하고,
/// JNI를 통해 Kotlin OxLensEventListener의 해당 메서드를 호출.
/// Disconnected 이벤트 수신 시 루프 종료.
pub(crate) async fn event_pump(
    mut event_rx: mpsc::Receiver<ClientEvent>,
    listener: GlobalRef,
) {
    info!("event_pump started");

    while let Some(event) = event_rx.recv().await {
        // 매 이벤트마다 현재 스레드를 JVM에 attach
        // (tokio 워커 스레드는 JVM에 알려지지 않은 네이티브 스레드)
        let mut env = match jvm().attach_current_thread() {
            Ok(env) => env,
            Err(e) => {
                error!("JNI AttachCurrentThread failed: {:?}", e);
                continue;
            }
        };

        let should_break = matches!(event, ClientEvent::Disconnected { .. });

        if let Err(e) = dispatch_event(&mut env, &listener, event) {
            error!("event dispatch failed: {:?}", e);
            // JNI 예외 체크 + 클리어
            if env.exception_check().unwrap_or(false) {
                env.exception_describe().ok();
                env.exception_clear().ok();
            }
        }

        if should_break {
            info!("event_pump: disconnected, exiting loop");
            break;
        }
    }

    info!("event_pump exited");
}

/// 개별 이벤트 디스패치 — JNI 메서드 호출
fn dispatch_event(
    env: &mut jni::JNIEnv,
    listener: &GlobalRef,
    event: ClientEvent,
) -> Result<(), jni::errors::Error> {
    match event {
        ClientEvent::Connected => {
            env.call_method(listener, "onConnected", sig::VOID, &[])?;
        }

        ClientEvent::Identified => {
            env.call_method(listener, "onIdentified", sig::VOID, &[])?;
        }

        ClientEvent::RoomCreated {
            room_id,
            name,
            mode,
        } => {
            let j_room_id = env.new_string(&room_id)?;
            let j_name = env.new_string(&name)?;
            let j_mode = env.new_string(&mode)?;
            env.call_method(
                listener,
                "onRoomCreated",
                sig::STRING_STRING_STRING_VOID,
                &[
                    JValue::Object(&j_room_id),
                    JValue::Object(&j_name),
                    JValue::Object(&j_mode),
                ],
            )?;
        }

        ClientEvent::RoomList { rooms } => {
            let json = serde_json::to_string(&rooms).unwrap_or_default();
            let j_json = env.new_string(&json)?;
            env.call_method(
                listener,
                "onRoomList",
                sig::STRING_VOID,
                &[JValue::Object(&j_json)],
            )?;
        }

        ClientEvent::RoomJoined { room_id, mode } => {
            let j_room_id = env.new_string(&room_id)?;
            let j_mode = env.new_string(format!("{:?}", mode))?;
            env.call_method(
                listener,
                "onRoomJoined",
                sig::STRING_STRING_VOID,
                &[JValue::Object(&j_room_id), JValue::Object(&j_mode)],
            )?;
        }

        ClientEvent::RoomLeft { room_id } => {
            let j_room_id = env.new_string(&room_id)?;
            env.call_method(
                listener,
                "onRoomLeft",
                sig::STRING_VOID,
                &[JValue::Object(&j_room_id)],
            )?;
        }

        ClientEvent::TracksUpdated { action, count } => {
            let j_action = env.new_string(&action)?;
            env.call_method(
                listener,
                "onTracksUpdated",
                sig::STRING_INT_VOID,
                &[JValue::Object(&j_action), JValue::Int(count as i32)],
            )?;
        }

        ClientEvent::AudioFrameReceived { .. } => {
            // Android에서는 무시 (벤치 전용 이벤트)
        }

        ClientEvent::FloorGranted { room_id, speaker } => {
            let j_room_id = env.new_string(&room_id)?;
            let j_speaker = env.new_string(&speaker)?;
            env.call_method(
                listener,
                "onFloorGranted",
                sig::STRING_STRING_VOID,
                &[JValue::Object(&j_room_id), JValue::Object(&j_speaker)],
            )?;
        }

        ClientEvent::FloorDenied { reason } => {
            let j_reason = env.new_string(&reason)?;
            env.call_method(
                listener,
                "onFloorDenied",
                sig::STRING_VOID,
                &[JValue::Object(&j_reason)],
            )?;
        }

        ClientEvent::FloorTaken { room_id, user_id } => {
            let j_room_id = env.new_string(&room_id)?;
            let j_user_id = env.new_string(&user_id)?;
            env.call_method(
                listener,
                "onFloorTaken",
                sig::STRING_STRING_VOID,
                &[JValue::Object(&j_room_id), JValue::Object(&j_user_id)],
            )?;
        }

        ClientEvent::FloorIdle { room_id } => {
            let j_room_id = env.new_string(&room_id)?;
            env.call_method(
                listener,
                "onFloorIdle",
                sig::STRING_VOID,
                &[JValue::Object(&j_room_id)],
            )?;
        }

        ClientEvent::FloorRevoke { room_id } => {
            let j_room_id = env.new_string(&room_id)?;
            env.call_method(
                listener,
                "onFloorRevoke",
                sig::STRING_VOID,
                &[JValue::Object(&j_room_id)],
            )?;
        }

        ClientEvent::FloorReleased => {
            env.call_method(listener, "onFloorReleased", sig::VOID, &[])?;
        }

        ClientEvent::Error { code, msg } => {
            let j_msg = env.new_string(&msg)?;
            env.call_method(
                listener,
                "onError",
                sig::INT_STRING_VOID,
                &[JValue::Int(code as i32), JValue::Object(&j_msg)],
            )?;
        }

        ClientEvent::Disconnected { reason } => {
            let j_reason = env.new_string(&reason)?;
            env.call_method(
                listener,
                "onDisconnected",
                sig::STRING_VOID,
                &[JValue::Object(&j_reason)],
            )?;
        }
    }

    Ok(())
}
