// author: kodeholic (powered by Claude)
package com.oxlens.sdk

import android.content.Context
import android.util.Log
import com.oxlens.sdk.device.AudioDeviceManager
import com.oxlens.sdk.media.*
import com.oxlens.sdk.ptt.FloorFsm
import com.oxlens.sdk.ptt.FloorFsmListener
import com.oxlens.sdk.signaling.*
import org.json.JSONObject
import org.webrtc.*

/**
 * OxLens SDK 메인 클라이언트 — 순수 Kotlin (JNI 없음).
 *
 * 시그널링 + 미디어(org.webrtc.*) + PTT를 오케스트레이션.
 *
 * ## 사용법
 * ```kotlin
 * val client = OxLensClient(
 *     serverUrl = "ws://192.168.0.29:1974/ws",
 *     token = "my-token",
 *     userId = "user-1",
 *     listener = object : OxLensEventListener {
 *         override fun onIdentified() { client.createRoom("test-room") }
 *         override fun onRoomCreated(roomId: String, name: String, mode: String) {
 *             client.joinRoom(roomId)
 *         }
 *         override fun onRoomJoined(roomId: String, mode: String) {
 *             Log.d("OxLens", "joined: $roomId ($mode)")
 *         }
 *     }
 * )
 * client.connect()
 *
 * // 종료 시
 * client.destroy()
 * ```
 *
 * ## 스레드 안전성
 * - 모든 public 메서드는 어떤 스레드에서든 호출 가능
 * - 콜백은 OkHttp 워커 스레드에서 호출됨 → UI 갱신 시 메인 스레드 전환 필요
 */
class OxLensClient(
    private val context: Context,
    private val serverUrl: String,
    private val token: String,
    private val userId: String? = null,
    private val listener: OxLensEventListener,
) {
    companion object {
        private const val TAG = "OxLensClient"
    }

    private var signalClient: SignalClient? = null
    private var mediaSession: MediaSession? = null

    /** PTT Floor 상태 머신 */
    val floorFsm: FloorFsm by lazy { FloorFsm(floorFsmListenerImpl) }

    /** 오디오 장치 관리 (home: device: DeviceManager 역할) */
    val device = AudioDeviceManager(context)

    /** 현재 입장한 방 ID */
    var currentRoomId: String? = null
        private set

    /** 현재 방의 mode */
    var currentRoomMode: RoomMode? = null
        private set

    /** ROOM_JOIN에서 파싱된 서버 설정 (2PC 미디어 셋업에 사용) */
    private var serverConfig: ServerConfig? = null

    /** ROOM_JOIN에서 받은 기존 참가자 트랙 목록 */
    private var remoteTracks: MutableList<TrackDesc> = mutableListOf()

    /** 미디어 자동 연결 활성화 (false면 시그널링만) */
    var mediaEnabled: Boolean = true

    /** 현재 Floor 상태 문자열 (home: floorState getter 미러) */
    val floorState: String
        get() = when (floorFsm.state) {
            FloorFsm.State.IDLE -> Constants.FLOOR.IDLE
            FloorFsm.State.REQUESTING -> Constants.FLOOR.REQUESTING
            FloorFsm.State.TALKING -> Constants.FLOOR.TALKING
            FloorFsm.State.LISTENING -> Constants.FLOOR.LISTENING
        }

    /** 현재 발화자 user_id (home: speaker getter 미러) */
    val speaker: String?
        get() = floorFsm.currentSpeaker

    // ================================================================
    //  연결 / 해제
    // ================================================================

    /** 서버에 연결 (HELLO → IDENTIFY → HEARTBEAT 자동) */
    fun connect() {
        Log.i(TAG, "connect: $serverUrl")

        val config = SignalConfig(
            serverUrl = serverUrl,
            token = token,
            userId = userId,
        )

        signalClient = SignalClient(config, signalListener)
        signalClient!!.connect()
    }

    /** 연결 종료 및 리소스 해제 (home: disconnect) */
    fun disconnect() {
        Log.i(TAG, "disconnect")
        floorFsm.reset()
        device.deactivate()
        device.stop()
        mediaSession?.dispose()
        mediaSession = null
        signalClient?.disconnect()
        signalClient = null
        currentRoomId = null
        currentRoomMode = null
        serverConfig = null
        remoteTracks.clear()
    }

    // ================================================================
    //  방 관리
    // ================================================================

    /** 방 목록 요청 */
    fun listRooms() {
        signalClient?.sendRequest(Opcode.ROOM_LIST)
    }

    /** 방 생성 */
    fun createRoom(name: String, capacity: Int = 30, mode: String = "conference") {
        signalClient?.sendRequest(Opcode.ROOM_CREATE, buildRoomCreate(name, capacity, mode))
    }

    /** 방 입장 */
    fun joinRoom(roomId: String) {
        signalClient?.sendRequest(Opcode.ROOM_JOIN, buildRoomJoin(roomId))
    }

    /** 방 퇴장 */
    fun leaveRoom() {
        val roomId = currentRoomId ?: return
        signalClient?.sendRequest(Opcode.ROOM_LEAVE, buildRoomLeave(roomId))
    }

    // ================================================================
    //  PTT Floor Control (FloorFsm 경유)
    // ================================================================

    /** PTT 버튼 누름 — 발화권 요청 (home: floorRequest) */
    fun floorRequest() {
        floorFsm.requestFloor()
    }

    /** PTT 버튼 뗌 — 발화권 해제 (home: floorRelease) */
    fun floorRelease() {
        floorFsm.releaseFloor()
    }

    /** FloorFsm 콜백 구현 */
    private val floorFsmListenerImpl = object : FloorFsmListener {
        override fun onSendFloorRequest() {
            val roomId = currentRoomId ?: return
            signalClient?.sendRequest(Opcode.FLOOR_REQUEST, buildFloorMsg(roomId))
        }
        override fun onSendFloorRelease() {
            val roomId = currentRoomId ?: return
            signalClient?.sendRequest(Opcode.FLOOR_RELEASE, buildFloorMsg(roomId))
        }
        override fun onSendFloorPing() {
            val roomId = currentRoomId ?: return
            signalClient?.sendRequest(Opcode.FLOOR_PING, buildFloorMsg(roomId))
        }
        override fun onMicMute(muted: Boolean) {
            mediaSession?.setAudioMuted(muted)
        }
    }

    // ================================================================
    //  시그널링 이벤트 핸들러 (내부)
    // ================================================================

    private val signalListener = object : SignalListener {

        override fun onConnected(heartbeatInterval: Long) {
            Log.i(TAG, "connected (heartbeat=${heartbeatInterval}ms)")
            listener.onConnected()
        }

        override fun onIdentified() {
            Log.i(TAG, "identified")
            listener.onIdentified()
        }

        override fun onRoomList(payload: JSONObject) {
            listener.onRoomList(payload.toString())
        }

        override fun onRoomCreated(payload: JSONObject) {
            val roomId = payload.optString("room_id", "")
            val name = payload.optString("name", "")
            val mode = payload.optString("mode", "conference")
            Log.i(TAG, "room created: $roomId ($name, $mode)")
            listener.onRoomCreated(roomId, name, mode)
        }

        override fun onRoomJoined(payload: JSONObject) {
            try {
                val joinResp = RoomJoinResponse.fromJson(payload)
                currentRoomId = joinResp.roomId
                currentRoomMode = joinResp.mode
                serverConfig = joinResp.serverConfig
                remoteTracks.clear()
                remoteTracks.addAll(joinResp.tracks)

                Log.i(TAG, "room joined: ${joinResp.roomId} (${joinResp.mode.value})")
                Log.i(TAG, "  server: ${joinResp.serverConfig.ice.ip}:${joinResp.serverConfig.ice.port}")
                Log.i(TAG, "  codecs: ${joinResp.serverConfig.codecs.map { it.name }}")
                Log.i(TAG, "  tracks: ${joinResp.tracks.size}")

                listener.onRoomJoined(joinResp.roomId, joinResp.mode.value)

                // 미디어 셋업 (ROOM_JOIN 성공 후)
                if (mediaEnabled) {
                    setupMedia(joinResp.serverConfig)

                    // PTT 모드: 초기 마이크 muted (발화권 획득 전까지)
                    if (joinResp.mode == RoomMode.Ptt) {
                        mediaSession?.setAudioMuted(true)
                        Log.i(TAG, "PTT mode: mic muted (waiting for floor)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to parse ROOM_JOIN response", e)
                listener.onError(0, "ROOM_JOIN parse error: ${e.message}")
            }
        }

        override fun onRoomLeft(roomId: String) {
            Log.i(TAG, "room left: $roomId")
            floorFsm.reset()
            device.deactivate()
            device.stop()
            mediaSession?.dispose()
            mediaSession = null
            currentRoomId = null
            currentRoomMode = null
            serverConfig = null
            remoteTracks.clear()
            listener.onRoomLeft(roomId)
        }

        override fun onTracksUpdate(action: String, tracks: List<TrackInfo>) {
            Log.i(TAG, "tracks update: action=$action count=${tracks.size}")
            listener.onTracksUpdated(action, tracks.size)

            // Subscribe PC에 트랙 반영
            if (mediaEnabled && tracks.isNotEmpty()) {
                val config = serverConfig ?: return
                val trackDescs = tracks.map { t ->
                    TrackDesc(
                        userId = t.userId,
                        kind = MediaKind.from(t.kind),
                        ssrc = t.ssrc,
                        trackId = t.trackId,
                        rtxSsrc = t.rtxSsrc,
                    )
                }

                // remoteTracks 갱신
                if (action == "add") {
                    for (td in trackDescs) {
                        if (remoteTracks.none { it.ssrc == td.ssrc }) {
                            remoteTracks.add(td)
                        }
                    }
                } else if (action == "remove") {
                    for (td in trackDescs) {
                        val idx = remoteTracks.indexOfFirst { it.ssrc == td.ssrc }
                        if (idx >= 0) remoteTracks[idx] = remoteTracks[idx].copy(active = false)
                    }
                }

                val session = mediaSession
                if (session != null) {
                    session.updateSubscribeTracks(action, trackDescs)
                } else {
                    // Subscribe PC가 아직 없으면 새로 생성
                    setupSubscribe(config)
                }
            }
        }

        override fun onRoomEvent(payload: JSONObject) {
            val type = payload.optString("type", "")
            val userId = payload.optString("user_id", "")
            Log.d(TAG, "room event: type=$type user=$userId")
            // 향후 세분화
        }

        override fun onFloorTaken(roomId: String, userId: String) {
            floorFsm.onFloorTaken(userId)
            listener.onFloorTaken(roomId, userId)
        }

        override fun onFloorIdle(roomId: String) {
            floorFsm.onFloorIdle()
            listener.onFloorIdle(roomId)
        }

        override fun onFloorRevoke(roomId: String) {
            floorFsm.onRevoked()
            listener.onFloorRevoke(roomId)
        }

        override fun onFloorResponse(granted: Boolean, payload: JSONObject) {
            val roomId = currentRoomId ?: ""
            if (granted) {
                floorFsm.onGranted()
                listener.onFloorGranted(roomId)
            } else {
                val reason = payload.optString("reason", "denied")
                floorFsm.onDenied(reason)
                listener.onFloorDenied(reason)
            }
        }

        override fun onFloorReleaseResponse() {
            floorFsm.onReleased()
            listener.onFloorReleased()
        }

        override fun onPublishTracksAck(payload: JSONObject) {
            Log.d(TAG, "publish tracks ack")
            // TODO: Phase 2
        }

        override fun onError(code: Int, message: String) {
            Log.e(TAG, "error: code=$code msg=$message")
            listener.onError(code, message)
        }

        override fun onDisconnected(reason: String) {
            Log.i(TAG, "disconnected: $reason")
            floorFsm.reset()
            device.deactivate()
            device.stop()
            mediaSession?.dispose()
            mediaSession = null
            currentRoomId = null
            currentRoomMode = null
            serverConfig = null
            remoteTracks.clear()
            listener.onDisconnected(reason)
        }
    }

    // ================================================================
    //  미디어 셋업 (내부)
    // ================================================================

    private fun setupMedia(config: ServerConfig) {
        try {
            Log.i(TAG, "setting up media session...")
            val session = MediaSession(context, mediaSessionListener)
            session.initialize()
            mediaSession = session

            // 오디오 장치 관리 시작 + AudioFocus 획득
            val isPtt = currentRoomMode == RoomMode.Ptt
            device.start(preferSpeaker = isPtt)
            device.activate()

            // Publish PC 셋업 (SDP 교환 → ICE → DTLS → SRTP)
            session.setupPublishPc(config)

            // 기존 참가자 트랙이 있으면 Subscribe PC도 셋업
            if (remoteTracks.isNotEmpty()) {
                setupSubscribe(config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "media setup failed", e)
            listener.onError(0, "media setup failed: ${e.message}")
        }
    }

    private fun setupSubscribe(config: ServerConfig) {
        try {
            val session = mediaSession ?: return
            val joinResp = serverConfig ?: return
            val mode = currentRoomMode ?: RoomMode.Conference
            Log.i(TAG, "setting up subscribe PC (${remoteTracks.size} tracks, mode=${mode.value})")
            session.setupSubscribePc(config, remoteTracks.toList(), mode)
        } catch (e: Exception) {
            Log.e(TAG, "subscribe setup failed", e)
            listener.onError(0, "subscribe setup failed: ${e.message}")
        }
    }

    private val mediaSessionListener = object : MediaSessionListener {
        override fun onPublishPcReady(tracks: List<PublishedTrack>) {
            Log.i(TAG, "publish PC ready — ${tracks.size} tracks")
            if (tracks.isNotEmpty()) {
                val trackItems = tracks.map {
                    com.oxlens.sdk.signaling.TrackItem(kind = it.kind, ssrc = it.ssrc)
                }
                signalClient?.sendRequest(Opcode.PUBLISH_TRACKS, buildPublishTracks(trackItems))
                Log.i(TAG, "PUBLISH_TRACKS sent: ${trackItems.map { "${it.kind}=${it.ssrc}" }}")
            }
        }

        override fun onPublishIceStateChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    Log.i(TAG, "✓ publish ICE CONNECTED")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "✗ publish ICE FAILED")
                    listener.onError(0, "publish ICE failed")
                }
                else -> {}
            }
        }

        override fun onSubscribeIceStateChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    Log.i(TAG, "✓ subscribe ICE CONNECTED")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "✗ subscribe ICE FAILED")
                    listener.onError(0, "subscribe ICE failed")
                }
                else -> {}
            }
        }

        override fun onRemoteTrackAdded(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track()
            val kind = track?.kind() ?: "unknown"
            Log.i(TAG, "remote track added: kind=$kind id=${track?.id()}")

            // 오디오 트랙이면 자동 재생 (libwebrtc가 AudioTrack을 스피커로 출력)
            if (track is AudioTrack) {
                track.setEnabled(true)
                Log.i(TAG, "remote audio track enabled — should hear audio")
            }
        }

        override fun onError(message: String) {
            Log.e(TAG, "media error: $message")
            listener.onError(0, message)
        }
    }
}
