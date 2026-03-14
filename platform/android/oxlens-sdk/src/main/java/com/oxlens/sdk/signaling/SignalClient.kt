// author: kodeholic (powered by Claude)
package com.oxlens.sdk.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp WebSocket 기반 시그널링 클라이언트.
 *
 * Rust SignalClient와 동일한 동작:
 * 1. 연결 → 서버가 HELLO 전송 (heartbeat_interval 포함)
 * 2. IDENTIFY 자동 전송 (token + user_id)
 * 3. HEARTBEAT 타이머 자동 시작
 * 4. 수신 패킷을 [SignalListener]로 디스패치
 *
 * ## 스레드 모델
 * - OkHttp는 내부 스레드에서 콜백 호출
 * - [SignalListener] 콜백은 OkHttp 스레드에서 호출됨
 * - UI 갱신 시 메인 스레드 전환 필요
 */
class SignalClient(
    private val config: SignalConfig,
    private val listener: SignalListener,
) {
    companion object {
        private const val TAG = "SignalClient"
        private const val NORMAL_CLOSE_CODE = 1000
    }

    private val pidCounter = AtomicLong(1)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null

    /** 연결 상태 */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** 다음 pid 생성 */
    fun nextPid(): Long = pidCounter.getAndIncrement()

    // ================================================================
    //  연결 / 해제
    // ================================================================

    /** 서버에 WebSocket 연결 */
    fun connect() {
        Log.i(TAG, "connecting to ${config.serverUrl}")

        httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket은 무한 대기
            .pingInterval(30, TimeUnit.SECONDS)     // OkHttp 레벨 ping
            .build()

        val request = Request.Builder()
            .url(config.serverUrl)
            .build()

        webSocket = httpClient!!.newWebSocket(request, WsListener())
    }

    /** 연결 종료 */
    fun disconnect() {
        Log.i(TAG, "disconnecting")
        stopHeartbeat()
        webSocket?.close(NORMAL_CLOSE_CODE, "client disconnect")
        webSocket = null
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
        isConnected = false
    }

    // ================================================================
    //  패킷 전송
    // ================================================================

    /** 패킷 전송 */
    fun send(packet: Packet) {
        val json = packet.toJson()
        Log.d(TAG, "→ ${Opcode.name(packet.op)} pid=${packet.pid}")
        webSocket?.send(json)
    }

    /** opcode + payload로 요청 전송, pid 반환 */
    fun sendRequest(op: Int, d: JSONObject = JSONObject()): Long {
        val pid = nextPid()
        send(Packet.request(op, pid, d))
        return pid
    }

    // ================================================================
    //  Heartbeat
    // ================================================================

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        // 별도 HandlerThread 대신 메인 Handler 사용 (heartbeat은 경량)
        heartbeatHandler = mainHandler
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    val pid = nextPid()
                    send(Packet.request(Opcode.HEARTBEAT, pid))
                    heartbeatHandler?.postDelayed(this, intervalMs)
                }
            }
        }
        heartbeatHandler?.postDelayed(heartbeatRunnable!!, intervalMs)
        Log.d(TAG, "heartbeat started (interval=${intervalMs}ms)")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatHandler = null
    }

    // ================================================================
    //  WebSocket 리스너 (내부)
    // ================================================================

    private inner class WsListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "websocket opened")
            isConnected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val packet: Packet
            try {
                packet = Packet.fromJson(text)
            } catch (e: Exception) {
                Log.w(TAG, "invalid packet json: $text", e)
                return
            }

            Log.d(TAG, "← ${Opcode.name(packet.op)} pid=${packet.pid} ok=${packet.ok}")
            dispatch(packet)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closing: code=$code reason=$reason")
            webSocket.close(NORMAL_CLOSE_CODE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed: code=$code reason=$reason")
            isConnected = false
            stopHeartbeat()
            listener.onDisconnected(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "ws failure: ${t.message}", t)
            isConnected = false
            stopHeartbeat()
            listener.onDisconnected(t.message ?: "unknown error")
        }
    }

    // ================================================================
    //  패킷 디스패치
    // ================================================================

    private fun dispatch(packet: Packet) {
        when (packet.op) {
            // --- HELLO: 서버 첫 메시지 → IDENTIFY 자동 전송 + HEARTBEAT 시작 ---
            Opcode.HELLO -> {
                val heartbeatInterval = packet.d.optLong("heartbeat_interval", 30000)
                Log.i(TAG, "HELLO received (heartbeat_interval=${heartbeatInterval}ms)")

                // IDENTIFY 자동 전송
                val identifyPayload = buildIdentify(config.token, config.userId)
                sendRequest(Opcode.IDENTIFY, identifyPayload)

                // HEARTBEAT 타이머 시작
                startHeartbeat(heartbeatInterval)

                listener.onConnected(heartbeatInterval)
            }

            // --- IDENTIFY 응답 ---
            Opcode.IDENTIFY -> {
                if (packet.isOk) {
                    Log.i(TAG, "identified successfully")
                    listener.onIdentified()
                } else {
                    val code = packet.d.optInt("code", 0)
                    val msg = packet.d.optString("msg", "unknown")
                    Log.e(TAG, "identify failed: code=$code msg=$msg")
                    listener.onError(code, msg)
                }
            }

            // --- HEARTBEAT ACK ---
            Opcode.HEARTBEAT -> {
                if (packet.isResponse) {
                    Log.d(TAG, "heartbeat ack")
                }
            }

            // --- ROOM_LIST 응답 ---
            Opcode.ROOM_LIST -> {
                if (packet.isOk) {
                    listener.onRoomList(packet.d)
                }
            }

            // --- ROOM_CREATE 응답 ---
            Opcode.ROOM_CREATE -> {
                if (packet.isOk) {
                    listener.onRoomCreated(packet.d)
                } else if (packet.isErr) {
                    val code = packet.d.optInt("code", 0)
                    val msg = packet.d.optString("msg", "unknown")
                    listener.onError(code, msg)
                }
            }

            // --- ROOM_JOIN 응답 ---
            Opcode.ROOM_JOIN -> {
                if (packet.isOk) {
                    val roomId = packet.d.optString("room_id", "")
                    Log.i(TAG, "room joined: $roomId")
                    listener.onRoomJoined(packet.d)
                } else if (packet.isErr) {
                    val code = packet.d.optInt("code", 0)
                    val msg = packet.d.optString("msg", "unknown")
                    listener.onError(code, msg)
                }
            }

            // --- ROOM_LEAVE 응답 ---
            Opcode.ROOM_LEAVE -> {
                if (packet.isOk) {
                    val roomId = packet.d.optString("room_id", "")
                    listener.onRoomLeft(roomId)
                }
            }

            // --- TRACKS_UPDATE 이벤트 ---
            Opcode.TRACKS_UPDATE -> {
                val action = packet.d.optString("action", "add")
                val tracksArr = packet.d.optJSONArray("tracks")
                val tracks = if (tracksArr != null) TrackInfo.listFromJsonArray(tracksArr) else emptyList()
                listener.onTracksUpdate(action, tracks)
            }

            // --- ROOM_EVENT ---
            Opcode.ROOM_EVENT -> {
                listener.onRoomEvent(packet.d)
            }

            // --- VIDEO_SUSPENDED / VIDEO_RESUMED 이벤트 ---
            Opcode.VIDEO_SUSPENDED -> {
                val userId = packet.d.optString("user_id", "")
                listener.onVideoSuspended(userId)
            }

            Opcode.VIDEO_RESUMED -> {
                val userId = packet.d.optString("user_id", "")
                listener.onVideoResumed(userId)
            }

            // --- Floor Control Events ---
            Opcode.FLOOR_TAKEN -> {
                val roomId = packet.d.optString("room_id", "")
                val userId = packet.d.optString("speaker", "")
                listener.onFloorTaken(roomId, userId)
            }

            Opcode.FLOOR_IDLE -> {
                val roomId = packet.d.optString("room_id", "")
                listener.onFloorIdle(roomId)
            }

            Opcode.FLOOR_REVOKE -> {
                val roomId = packet.d.optString("room_id", "")
                listener.onFloorRevoke(roomId)
            }

            // --- FLOOR_REQUEST 응답 ---
            Opcode.FLOOR_REQUEST -> {
                if (packet.isResponse) {
                    val granted = packet.isOk &&
                        packet.d.optBoolean("granted", false)
                    listener.onFloorResponse(granted, packet.d)
                }
            }

            // --- FLOOR_RELEASE 응답 ---
            Opcode.FLOOR_RELEASE -> {
                if (packet.isResponse) {
                    listener.onFloorReleaseResponse()
                }
            }

            // --- PUBLISH_TRACKS 응답 ---
            Opcode.PUBLISH_TRACKS -> {
                if (packet.isResponse) {
                    Log.d(TAG, "PUBLISH_TRACKS ack")
                    listener.onPublishTracksAck(packet.d)
                }
            }

            // --- FLOOR_PING 응답 ---
            Opcode.FLOOR_PING -> {
                if (packet.isResponse) {
                    Log.d(TAG, "FLOOR_PING ack")
                }
            }

            else -> {
                Log.d(TAG, "unhandled opcode: ${packet.op}")
            }
        }
    }
}

// ================================================================
//  설정 + 리스너 인터페이스
// ================================================================

/** 시그널링 클라이언트 설정 */
data class SignalConfig(
    val serverUrl: String,
    val token: String,
    val userId: String? = null,
)

/**
 * 시그널링 이벤트 리스너.
 *
 * OkHttp 워커 스레드에서 호출됨 — UI 갱신 시 메인 스레드 전환 필요.
 */
interface SignalListener {
    /** HELLO 수신 + IDENTIFY 자동 전송 완료 */
    fun onConnected(heartbeatInterval: Long)

    /** IDENTIFY 성공 */
    fun onIdentified()

    /** ROOM_LIST 응답 */
    fun onRoomList(payload: JSONObject)

    /** ROOM_CREATE 응답 */
    fun onRoomCreated(payload: JSONObject)

    /** ROOM_JOIN 응답 — 전체 payload (server_config 포함) */
    fun onRoomJoined(payload: JSONObject)

    /** ROOM_LEAVE 응답 */
    fun onRoomLeft(roomId: String)

    /** TRACKS_UPDATE 이벤트 */
    fun onTracksUpdate(action: String, tracks: List<TrackInfo>)

    /** ROOM_EVENT */
    fun onRoomEvent(payload: JSONObject)

    /** VIDEO_SUSPENDED — 상대방 카메라 off (UI avatar 전환) */
    fun onVideoSuspended(userId: String)

    /** VIDEO_RESUMED — 상대방 카메라 재개 (UI 복원) */
    fun onVideoResumed(userId: String)

    /** FLOOR_TAKEN 이벤트 */
    fun onFloorTaken(roomId: String, userId: String)

    /** FLOOR_IDLE 이벤트 */
    fun onFloorIdle(roomId: String)

    /** FLOOR_REVOKE 이벤트 */
    fun onFloorRevoke(roomId: String)

    /** FLOOR_REQUEST 응답 */
    fun onFloorResponse(granted: Boolean, payload: JSONObject)

    /** FLOOR_RELEASE 응답 */
    fun onFloorReleaseResponse()

    /** PUBLISH_TRACKS ACK */
    fun onPublishTracksAck(payload: JSONObject)

    /** 서버 에러 */
    fun onError(code: Int, message: String)

    /** 연결 해제 */
    fun onDisconnected(reason: String)
}
