// author: kodeholic (powered by Claude)
package com.oxlens.sdk.media

import org.json.JSONArray
import org.json.JSONObject

/**
 * SDP 관련 타입 정의 — ROOM_JOIN 응답의 server_config JSON 매핑.
 *
 * Rust oxlens-core/src/sdp/types.rs 포팅.
 * 참조: doc/SERVER_CONFIG_SCHEMA.md
 */

// ── server_config ────────────────────────────────────────────

/** ROOM_JOIN 응답 → d.server_config */
data class ServerConfig(
    val ice: IceConfig,
    val dtls: DtlsConfig,
    val codecs: List<CodecConfig>,
    val extmap: List<ExtmapEntry> = emptyList(),
    val maxBitrateBps: Int? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): ServerConfig {
            val codecsArr = obj.getJSONArray("codecs")
            val codecs = (0 until codecsArr.length()).map { CodecConfig.fromJson(codecsArr.getJSONObject(it)) }

            val extmapArr = obj.optJSONArray("extmap") ?: JSONArray()
            val extmap = (0 until extmapArr.length()).map { ExtmapEntry.fromJson(extmapArr.getJSONObject(it)) }

            return ServerConfig(
                ice = IceConfig.fromJson(obj.getJSONObject("ice")),
                dtls = DtlsConfig.fromJson(obj.getJSONObject("dtls")),
                codecs = codecs,
                extmap = extmap,
                maxBitrateBps = if (obj.has("max_bitrate_bps")) obj.getInt("max_bitrate_bps") else null,
            )
        }
    }
}

/** ICE credentials + 서버 endpoint */
data class IceConfig(
    val publishUfrag: String,
    val publishPwd: String,
    val subscribeUfrag: String,
    val subscribePwd: String,
    val ip: String,
    val port: Int,
) {
    companion object {
        fun fromJson(obj: JSONObject): IceConfig = IceConfig(
            publishUfrag = obj.getString("publish_ufrag"),
            publishPwd = obj.getString("publish_pwd"),
            subscribeUfrag = obj.getString("subscribe_ufrag"),
            subscribePwd = obj.getString("subscribe_pwd"),
            ip = obj.getString("ip"),
            port = obj.getInt("port"),
        )
    }
}

/** DTLS fingerprint + setup role */
data class DtlsConfig(
    val fingerprint: String,
    /** 항상 "passive" (서버 = ICE-Lite) */
    val setup: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): DtlsConfig = DtlsConfig(
            fingerprint = obj.getString("fingerprint"),
            setup = obj.getString("setup"),
        )
    }
}

/** 미디어 종류 */
enum class MediaKind(val value: String) {
    Audio("audio"),
    Video("video");

    companion object {
        fun from(s: String): MediaKind = when (s.lowercase()) {
            "audio" -> Audio
            "video" -> Video
            else -> throw IllegalArgumentException("Unknown media kind: $s")
        }
    }
}

/** 코덱 정의 */
data class CodecConfig(
    val kind: MediaKind,
    val name: String,
    val pt: Int,
    val clockrate: Int,
    val channels: Int? = null,
    val rtxPt: Int? = null,
    val rtcpFb: List<String> = emptyList(),
    val fmtp: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): CodecConfig {
            val fbArr = obj.optJSONArray("rtcp_fb") ?: JSONArray()
            val fb = (0 until fbArr.length()).map { fbArr.getString(it) }

            return CodecConfig(
                kind = MediaKind.from(obj.getString("kind")),
                name = obj.getString("name"),
                pt = obj.getInt("pt"),
                clockrate = obj.getInt("clockrate"),
                channels = if (obj.has("channels") && !obj.isNull("channels")) obj.getInt("channels") else null,
                rtxPt = if (obj.has("rtx_pt") && !obj.isNull("rtx_pt")) obj.getInt("rtx_pt") else null,
                rtcpFb = fb,
                fmtp = obj.optString("fmtp", null),
            )
        }
    }
}

/** RTP 헤더 확장 */
data class ExtmapEntry(
    val id: Int,
    val uri: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): ExtmapEntry = ExtmapEntry(
            id = obj.getInt("id"),
            uri = obj.getString("uri"),
        )
    }
}

// ── tracks ──────────────────────────────────────────────────

/** ROOM_JOIN 응답 → d.tracks[] */
data class TrackDesc(
    val userId: String,
    val kind: MediaKind,
    val ssrc: Long,
    val trackId: String,
    val rtxSsrc: Long? = null,
    val mid: String? = null,
    val active: Boolean = true,
) {
    companion object {
        fun fromJson(obj: JSONObject): TrackDesc = TrackDesc(
            userId = obj.getString("user_id"),
            kind = MediaKind.from(obj.getString("kind")),
            ssrc = obj.getLong("ssrc"),
            trackId = obj.getString("track_id"),
            rtxSsrc = if (obj.has("rtx_ssrc") && !obj.isNull("rtx_ssrc")) obj.getLong("rtx_ssrc") else null,
            mid = obj.optString("mid", null),
            active = obj.optBoolean("active", true),
        )

        fun listFromJsonArray(arr: JSONArray): List<TrackDesc> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── PTT ─────────────────────────────────────────────────────

/** PTT 가상 SSRC (PTT 모드일 때만) */
data class PttVirtualSsrc(
    val audio: Long,
    val video: Long? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): PttVirtualSsrc = PttVirtualSsrc(
            audio = obj.getLong("audio"),
            video = if (obj.has("video") && !obj.isNull("video")) obj.getLong("video") else null,
        )
    }
}

// ── enums ───────────────────────────────────────────────────

/** 방 모드 */
enum class RoomMode(val value: String) {
    Conference("conference"),
    Ptt("ptt");

    companion object {
        fun from(s: String): RoomMode = when (s.lowercase()) {
            "ptt" -> Ptt
            else -> Conference
        }
    }
}

// ── ROOM_JOIN 응답 전체 ─────────────────────────────────────

/** ROOM_JOIN 응답 payload (d 필드) */
data class RoomJoinResponse(
    val roomId: String,
    val mode: RoomMode = RoomMode.Conference,
    val participants: List<String> = emptyList(),
    val serverConfig: ServerConfig,
    val tracks: List<TrackDesc> = emptyList(),
    val pttVirtualSsrc: PttVirtualSsrc? = null,
    /** PTT: 입장 시점의 현재 발화자 (null = idle) */
    val floorSpeaker: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): RoomJoinResponse {
            val participantsArr = obj.optJSONArray("participants") ?: JSONArray()
            val participants = (0 until participantsArr.length()).map { participantsArr.getString(it) }

            val tracksArr = obj.optJSONArray("tracks") ?: JSONArray()
            val tracks = TrackDesc.listFromJsonArray(tracksArr)

            val pttVssrc = if (obj.has("ptt_virtual_ssrc") && !obj.isNull("ptt_virtual_ssrc")) {
                PttVirtualSsrc.fromJson(obj.getJSONObject("ptt_virtual_ssrc"))
            } else null

            val floorSpeaker = if (obj.has("floor_speaker") && !obj.isNull("floor_speaker")) {
                obj.getString("floor_speaker")
            } else null

            return RoomJoinResponse(
                roomId = obj.getString("room_id"),
                mode = RoomMode.from(obj.optString("mode", "conference")),
                participants = participants,
                serverConfig = ServerConfig.fromJson(obj.getJSONObject("server_config")),
                tracks = tracks,
                pttVirtualSsrc = pttVssrc,
                floorSpeaker = floorSpeaker,
            )
        }
    }
}

// ── 상수 ────────────────────────────────────────────────────

/** sdes:mid extmap URI — subscribe SDP에서 제거 대상 */
const val EXTMAP_SDES_MID = "urn:ietf:params:rtp-hdrext:sdes:mid"

/** audio 전용 extmap URI — video m-line에 넣으면 Android libwebrtc 크래시 */
val AUDIO_ONLY_EXTMAP_URIS = setOf(
    "urn:ietf:params:rtp-hdrext:ssrc-audio-level",
)
