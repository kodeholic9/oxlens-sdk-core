// author: kodeholic (powered by Claude)
#![allow(unused_mut)]
//! JNI 바인딩 — OxLensClient의 모든 공개 API를 JNI 함수로 노출
//!
//! ## 핸들 패턴
//! - connect() → Box::into_raw() → Long (Kotlin)
//! - 이후 API 호출 시 Long → *const ClientHandle → &ClientHandle
//! - destroy() → Box::from_raw() → drop
//!
//! ## 네이밍 규칙
//! Java_com_oxlens_sdk_OxLensClient_<methodName>
//!
//! ## 에러 처리
//! - JNI 함수는 panic하면 안 됨 → catch_unwind 또는 Result 변환
//! - 에러 시 Kotlin에 0L 반환 또는 JNI exception throw

use std::ptr;

use jni::JNIEnv;
use jni::objects::{GlobalRef, JClass, JObject, JString};
use jni::sys::jlong;
use log::{error, info};

use oxlens_core::{ClientConfig, OxLensClient};

use crate::runtime;
use crate::callback::event_pump;

// ================================================================
//  ClientHandle — Kotlin Long으로 전달되는 불투명 포인터
// ================================================================

/// JNI를 통해 Kotlin에 전달되는 핸들 구조체.
///
/// Box::into_raw()로 힙에 고정 → Long으로 전달 → 역참조하여 사용.
/// destroy() 시 Box::from_raw()로 회수하여 drop.
pub(crate) struct ClientHandle {
    pub client: OxLensClient,
    // event_pump 태스크가 GlobalRef를 소유하므로 여기엔 안 둠
}

// ================================================================
//  헬퍼 — Long → &ClientHandle 역참조
// ================================================================

/// Long 핸들을 ClientHandle 참조로 변환 (unsafe)
///
/// # Safety
/// - handle이 유효한 ClientHandle 포인터여야 함
/// - destroy() 호출 후에는 사용 불가
unsafe fn handle_ref(handle: jlong) -> &'static ClientHandle {
    &*(handle as *const ClientHandle)
}

/// JString → Rust String 변환 헬퍼
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String, jni::errors::Error> {
    Ok(env.get_string(s)?.into())
}

/// nullable JString → Option<String> 변환 헬퍼
fn jstring_to_option(env: &mut JNIEnv, s: &JString) -> Result<Option<String>, jni::errors::Error> {
    if s.is_null() {
        Ok(None)
    } else {
        Ok(Some(env.get_string(s)?.into()))
    }
}

// ================================================================
//  JNI 함수: connect
// ================================================================

/// Kotlin: `external fun nativeConnect(url: String, token: String, userId: String?, listener: OxLensEventListener): Long`
///
/// 서버에 연결하고 이벤트 루프를 시작.
/// 성공 시 ClientHandle 포인터(Long), 실패 시 0L 반환.
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    token: JString,
    user_id: JString,
    listener: JObject,
) -> jlong {
    // 1. 파라미터 변환
    let server_url = match jstring_to_string(&mut env, &url) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to read url: {:?}", e);
            return 0;
        }
    };
    let token_str = match jstring_to_string(&mut env, &token) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to read token: {:?}", e);
            return 0;
        }
    };
    let user_id_opt = match jstring_to_option(&mut env, &user_id) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to read user_id: {:?}", e);
            return 0;
        }
    };

    // 2. Kotlin listener → GlobalRef (GC 방지, event_pump 수명 동안 유지)
    let listener_ref: GlobalRef = match env.new_global_ref(listener) {
        Ok(r) => r,
        Err(e) => {
            error!("failed to create GlobalRef for listener: {:?}", e);
            return 0;
        }
    };

    info!("[JNI] step 2 OK: GlobalRef created");

    // 3. OxLensClient::connect() (async → block_on)
    let config = ClientConfig {
        server_url: server_url.clone(),
        token: token_str,
        user_id: user_id_opt,
    };

    info!("[JNI] step 3: calling OxLensClient::connect to {}", server_url);

    let (client, event_rx) = match runtime().block_on(OxLensClient::connect(config)) {
        Ok(pair) => {
            info!("[JNI] step 3 OK: connect returned");
            pair
        }
        Err(e) => {
            error!("OxLensClient::connect failed: {:?}", e);
            return 0;
        }
    };

    // 4. 이벤트 펌프 spawn (Rust → Kotlin 콜백)
    runtime().spawn(event_pump(event_rx, listener_ref));

    // 5. 핸들 생성 → Long 반환
    let handle = Box::new(ClientHandle { client });
    let ptr = Box::into_raw(handle);

    info!("[JNI] step 5 OK: client connected, handle created (ptr={:?})", ptr);
    ptr as jlong
}

// ================================================================
//  JNI 함수: createRoom
// ================================================================

/// Kotlin: `external fun nativeCreateRoom(handle: Long, name: String, capacity: Int, mode: String)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeCreateRoom(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    name: JString,
    capacity: jni::sys::jint,
    mode: JString,
) {
    let h = unsafe { handle_ref(handle) };
    let name_str = match jstring_to_string(&mut env, &name) {
        Ok(s) => s,
        Err(_) => return,
    };
    let mode_str = match jstring_to_string(&mut env, &mode) {
        Ok(s) => s,
        Err(_) => return,
    };

    if let Err(e) = runtime().block_on(h.client.create_room(&name_str, capacity as u32, &mode_str))
    {
        error!("createRoom failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: listRooms
// ================================================================

/// Kotlin: `external fun nativeListRooms(handle: Long)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeListRooms(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let h = unsafe { handle_ref(handle) };
    if let Err(e) = runtime().block_on(h.client.list_rooms()) {
        error!("listRooms failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: joinRoom
// ================================================================

/// Kotlin: `external fun nativeJoinRoom(handle: Long, roomId: String)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeJoinRoom(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    room_id: JString,
) {
    let h = unsafe { handle_ref(handle) };
    let id = match jstring_to_string(&mut env, &room_id) {
        Ok(s) => s,
        Err(_) => return,
    };

    if let Err(e) = runtime().block_on(h.client.join_room(&id)) {
        error!("joinRoom failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: leaveRoom
// ================================================================

/// Kotlin: `external fun nativeLeaveRoom(handle: Long)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeLeaveRoom(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let h = unsafe { handle_ref(handle) };
    if let Err(e) = runtime().block_on(h.client.leave_room()) {
        error!("leaveRoom failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: requestFloor
// ================================================================

/// Kotlin: `external fun nativeRequestFloor(handle: Long, roomId: String)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeRequestFloor(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    room_id: JString,
) {
    let h = unsafe { handle_ref(handle) };
    let id = match jstring_to_string(&mut env, &room_id) {
        Ok(s) => s,
        Err(_) => return,
    };

    if let Err(e) = runtime().block_on(h.client.request_floor(&id)) {
        error!("requestFloor failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: releaseFloor
// ================================================================

/// Kotlin: `external fun nativeReleaseFloor(handle: Long, roomId: String)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeReleaseFloor(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    room_id: JString,
) {
    let h = unsafe { handle_ref(handle) };
    let id = match jstring_to_string(&mut env, &room_id) {
        Ok(s) => s,
        Err(_) => return,
    };

    if let Err(e) = runtime().block_on(h.client.release_floor(&id)) {
        error!("releaseFloor failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: floorPing
// ================================================================

/// Kotlin: `external fun nativeFloorPing(handle: Long, roomId: String)`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeFloorPing(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    room_id: JString,
) {
    let h = unsafe { handle_ref(handle) };
    let id = match jstring_to_string(&mut env, &room_id) {
        Ok(s) => s,
        Err(_) => return,
    };

    if let Err(e) = runtime().block_on(h.client.floor_ping(&id)) {
        error!("floorPing failed: {:?}", e);
    }
}

// ================================================================
//  JNI 함수: roomId (상태 조회)
// ================================================================

/// Kotlin: `external fun nativeRoomId(handle: Long): String?`
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeRoomId(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jni::sys::jstring {
    let h = unsafe { handle_ref(handle) };
    let room_id = runtime().block_on(h.client.room_id());

    match room_id {
        Some(id) => match env.new_string(&id) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        },
        None => ptr::null_mut(),
    }
}

// ================================================================
//  JNI 함수: destroy
// ================================================================

/// Kotlin: `external fun nativeDestroy(handle: Long)`
///
/// ClientHandle을 drop — 내부 리소스 해제.
/// 호출 후 handle은 무효 — 이후 사용 시 UB.
#[no_mangle]
pub extern "system" fn Java_com_oxlens_sdk_OxLensClient_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    info!("destroying client handle (handle={})", handle);
    let _ = unsafe { Box::from_raw(handle as *mut ClientHandle) };
    // Box drop → ClientHandle drop → OxLensClient drop (Arc ref count -1)
}
