// author: kodeholic (powered by Claude)
package com.oxlens.sdk.signaling

import org.json.JSONArray
import org.json.JSONObject

/**
 * 시그널링 패킷 — 서버와 동일한 JSON 구조.
 *
 * 요청/이벤트: { "op": N, "pid": N, "d": { ... } }
 * 응답:       { "op": N, "pid": N, "ok": true/false, "d": { ... } }
 */
data class Packet(
    val op: Int,
    val pid: Long,
    val ok: Boolean? = null,
    val d: JSONObject = JSONObject(),
) {
    val isResponse: Boolean get() = ok != null
    val isOk: Boolean get() = ok == true
    val isErr: Boolean get() = ok == false

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("op", op)
        obj.put("pid", pid)
        if (ok != null) obj.put("ok", ok)
        obj.put("d", d)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): Packet {
            val obj = JSONObject(json)
            return Packet(
                op = obj.getInt("op"),
                pid = obj.getLong("pid"),
                ok = if (obj.has("ok")) obj.getBoolean("ok") else null,
                d = obj.optJSONObject("d") ?: JSONObject(),
            )
        }

        /** 요청 패킷 생성 */
        fun request(op: Int, pid: Long, d: JSONObject = JSONObject()): Packet {
            return Packet(op = op, pid = pid, d = d)
        }
    }
}

// ================================================================
//  Request payloads (Client → Server)
// ================================================================

/** IDENTIFY 요청 */
fun buildIdentify(token: String, userId: String? = null): JSONObject {
    return JSONObject().apply {
        put("token", token)
        if (userId != null) put("user_id", userId)
    }
}

/** ROOM_CREATE 요청 */
fun buildRoomCreate(name: String, capacity: Int = 30, mode: String = "conference"): JSONObject {
    return JSONObject().apply {
        put("name", name)
        put("capacity", capacity)
        put("mode", mode)
    }
}

/** ROOM_JOIN 요청 */
fun buildRoomJoin(roomId: String): JSONObject {
    return JSONObject().apply {
        put("room_id", roomId)
    }
}

/** ROOM_LEAVE 요청 */
fun buildRoomLeave(roomId: String): JSONObject {
    return JSONObject().apply {
        put("room_id", roomId)
    }
}

/** PUBLISH_TRACKS 요청 */
fun buildPublishTracks(tracks: List<TrackItem>): JSONObject {
    val arr = JSONArray()
    for (t in tracks) {
        arr.put(JSONObject().apply {
            put("kind", t.kind)
            put("ssrc", t.ssrc)
        })
    }
    return JSONObject().apply {
        put("tracks", arr)
    }
}

/** MUTE_UPDATE 요청 */
fun buildMuteUpdate(ssrc: Long, muted: Boolean): JSONObject {
    return JSONObject().apply {
        put("ssrc", ssrc)
        put("muted", muted)
    }
}

/** CAMERA_READY 요청 */
fun buildCameraReady(roomId: String): JSONObject {
    return JSONObject().apply {
        put("room_id", roomId)
    }
}

/** TRACKS_ACK 요청 — 클라이언트가 인식한 subscribe SSRC 목록 */
fun buildTracksAck(ssrcs: List<Long>): JSONObject {
    val arr = JSONArray()
    for (ssrc in ssrcs) arr.put(ssrc)
    return JSONObject().apply {
        put("ssrcs", arr)
    }
}

/** FLOOR_REQUEST / FLOOR_RELEASE / FLOOR_PING 요청 */
fun buildFloorMsg(roomId: String): JSONObject {
    return JSONObject().apply {
        put("room_id", roomId)
    }
}

// ================================================================
//  Data classes
// ================================================================

/** PUBLISH_TRACKS 트랙 항목 */
data class TrackItem(
    val kind: String,  // "audio" | "video"
    val ssrc: Long,
)

/** TRACKS_UPDATE 이벤트의 트랙 정보 */
data class TrackInfo(
    val userId: String,
    val trackId: String,
    val kind: String,
    val ssrc: Long,
    val rtxSsrc: Long? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): TrackInfo {
            return TrackInfo(
                userId = obj.getString("user_id"),
                trackId = obj.getString("track_id"),
                kind = obj.getString("kind"),
                ssrc = obj.getLong("ssrc"),
                rtxSsrc = if (obj.has("rtx_ssrc") && !obj.isNull("rtx_ssrc")) obj.getLong("rtx_ssrc") else null,
            )
        }

        fun listFromJsonArray(arr: JSONArray): List<TrackInfo> {
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}
