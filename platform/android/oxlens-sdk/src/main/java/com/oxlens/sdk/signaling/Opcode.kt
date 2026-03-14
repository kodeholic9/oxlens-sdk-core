// author: kodeholic (powered by Claude)
package com.oxlens.sdk.signaling

/**
 * 시그널링 Opcode — oxlens-sfu-server opcode 미러.
 *
 * 패킷 형식: { "op": N, "pid": N, "d": { ... } }
 * 응답 형식: { "op": N, "pid": N, "ok": true/false, "d": { ... } }
 */
object Opcode {
    // --- Client → Server (Request) ---
    const val HEARTBEAT: Int = 1
    const val IDENTIFY: Int = 3
    const val ROOM_LIST: Int = 9
    const val ROOM_CREATE: Int = 10
    const val ROOM_JOIN: Int = 11
    const val ROOM_LEAVE: Int = 12
    const val PUBLISH_TRACKS: Int = 15
    const val MUTE_UPDATE: Int = 17
    const val MESSAGE: Int = 20
    const val TELEMETRY: Int = 30

    // --- Floor Control (MCPTT/MBCP) ---
    const val FLOOR_REQUEST: Int = 40
    const val FLOOR_RELEASE: Int = 41
    const val FLOOR_PING: Int = 42

    // --- Server → Client (Event) ---
    const val HELLO: Int = 0
    const val ROOM_EVENT: Int = 100
    const val TRACKS_UPDATE: Int = 101
    const val TRACK_STATE: Int = 102
    const val MESSAGE_EVENT: Int = 103
    const val ADMIN_TELEMETRY: Int = 110

    // --- Floor Control Events ---
    const val FLOOR_TAKEN: Int = 141
    const val FLOOR_IDLE: Int = 142
    const val FLOOR_REVOKE: Int = 143

    /** opcode → 디버그 이름 */
    fun name(op: Int): String = when (op) {
        HEARTBEAT -> "HEARTBEAT"
        IDENTIFY -> "IDENTIFY"
        ROOM_LIST -> "ROOM_LIST"
        ROOM_CREATE -> "ROOM_CREATE"
        ROOM_JOIN -> "ROOM_JOIN"
        ROOM_LEAVE -> "ROOM_LEAVE"
        PUBLISH_TRACKS -> "PUBLISH_TRACKS"
        MUTE_UPDATE -> "MUTE_UPDATE"
        MESSAGE -> "MESSAGE"
        TELEMETRY -> "TELEMETRY"
        FLOOR_REQUEST -> "FLOOR_REQUEST"
        FLOOR_RELEASE -> "FLOOR_RELEASE"
        FLOOR_PING -> "FLOOR_PING"
        HELLO -> "HELLO"
        ROOM_EVENT -> "ROOM_EVENT"
        TRACKS_UPDATE -> "TRACKS_UPDATE"
        TRACK_STATE -> "TRACK_STATE"
        MESSAGE_EVENT -> "MESSAGE_EVENT"
        ADMIN_TELEMETRY -> "ADMIN_TELEMETRY"
        FLOOR_TAKEN -> "FLOOR_TAKEN"
        FLOOR_IDLE -> "FLOOR_IDLE"
        FLOOR_REVOKE -> "FLOOR_REVOKE"
        else -> "UNKNOWN($op)"
    }
}
