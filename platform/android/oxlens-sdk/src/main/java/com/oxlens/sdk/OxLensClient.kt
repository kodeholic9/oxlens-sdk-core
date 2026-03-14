// author: kodeholic (powered by Claude)
package com.oxlens.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oxlens.sdk.device.AudioDeviceListener
import com.oxlens.sdk.device.AudioDeviceManager
import com.twilio.audioswitch.AudioDevice
import com.oxlens.sdk.media.*
import com.oxlens.sdk.ptt.FloorFsm
import com.oxlens.sdk.ptt.FloorFsmListener
import com.oxlens.sdk.signaling.*
import com.oxlens.sdk.telemetry.PeerConnectionProvider
import com.oxlens.sdk.telemetry.Telemetry
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
    private var telemetry: Telemetry? = null

    /** PTT Floor 상태 머신 */
    val floorFsm: FloorFsm by lazy { FloorFsm(floorFsmListenerImpl) }

    /** 오디오 장치 관리 (home: device: DeviceManager 역할) */
    val device: AudioDeviceManager by lazy {
        AudioDeviceManager(context, audioDeviceListenerImpl)
    }

    /** 현재 입장한 방 ID */
    var currentRoomId: String? = null
        private set

    /** 현재 방의 mode */
    var currentRoomMode: RoomMode? = null
        private set

    // ================================================================
    //  화질 프리셋 (joinRoom 전에 세팅 → 다음 입장 시 적용)
    // ================================================================

    /** 카메라 캡처 해상도 — 너비 */
    var captureWidth: Int = 1280
    /** 카메라 캡처 해상도 — 높이 */
    var captureHeight: Int = 720
    /** 카메라 캡처 프레임레이트 */
    var captureFps: Int = 24
    /** 비디오 최대 비트레이트 (bps). publish ICE CONNECTED 후 RtpSender에 적용 */
    var maxBitrateBps: Int = 1_500_000

    /** EGL 컨텍스트 — 데모앵에서 ViewModel.eglBase.eglBaseContext 주입 */
    var eglContext: org.webrtc.EglBase.Context? = null

    /** ROOM_JOIN에서 파싱된 서버 설정 (2PC 미디어 셋업에 사용) */
    private var serverConfig: ServerConfig? = null

    /** PTT 모드: 서버가 할당한 가상 SSRC (리라이트 대상) */
    private var pttVirtualSsrc: PttVirtualSsrc? = null

    /** ROOM_JOIN에서 받은 기존 참가자 트랙 목록 */
    private var remoteTracks: MutableList<TrackDesc> = mutableListOf()

    /** 미디어 자동 연결 활성화 (false면 시그널링만) */
    var mediaEnabled: Boolean = true

    // ================================================================
    //  Mute 3-state 상태 (home: _muteState 미러)
    // ================================================================

    private val muteHandler = Handler(Looper.getMainLooper())

    /** Conference 모드: kind별 mute 상태 */
    private val _muteState = mutableMapOf(
        "audio" to Constants.MUTE.UNMUTED,
        "video" to Constants.MUTE.UNMUTED,
    )

    /** Conference 모드: soft→hard 에스컬레이션 타이머 */
    private val _muteTimers = mutableMapOf<String, Runnable?>("audio" to null, "video" to null)

    /** PTT 모드: 사용자 비디오 off 토글 (비디오 추가 시 활성화) */
    private var _userVideoOff = false

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
        telemetry?.stop()
        telemetry = null
        floorFsm.reset()
        resetMute()
        device.deactivate()
        device.stop()
        mediaSession?.dispose()
        mediaSession = null
        signalClient?.disconnect()
        signalClient = null
        currentRoomId = null
        currentRoomMode = null
        serverConfig = null
        pttVirtualSsrc = null
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
    //  비디오 제어
    // ================================================================

    /**
     * 카메라 시작 + publish PC에 비디오 트랙 추가.
     * joinRoom 이후, publish ICE CONNECTED 상태에서 호출.
     *
     * @param sink 로컬 프리뷰용 VideoSink (null이면 프리뷰 없음)
     */
    fun startCamera(sink: org.webrtc.VideoSink? = null) {
        mediaSession?.startCamera(sink, captureWidth, captureHeight, captureFps)
        // 카메라 시작 후 video SSRC가 추가되므로 PUBLISH_TRACKS 재전송
        resendPublishTracks()
    }

    /** 카메라 정지 + 리소스 해제 */
    fun stopCamera() {
        mediaSession?.stopCamera()
    }

    /** 전면/후면 카메라 전환 (home: switchCamera 미러) */
    fun switchCamera() {
        mediaSession?.switchCamera()
    }

    /** 현재 카메라 방향 */
    val facingMode: String
        get() = mediaSession?.facingMode ?: "front"

    // ================================================================
    //  오디오 장치 제어
    // ================================================================

    /**
     * 이름으로 오디오 장치 선택.
     * SettingsBottomSheet 드롭다운에서 선택한 name을 매칭.
     */
    fun selectAudioDevice(name: String) {
        val target = device.availableDevices.firstOrNull { it.name == name }
        if (target != null) {
            device.selectDevice(target)
        } else {
            Log.w(TAG, "selectAudioDevice: '$name' not found in available devices")
        }
    }

    /** 스피커폰으로 전환 */
    fun selectSpeaker(): Boolean = device.selectSpeaker()

    /** 이어피스로 전환 */
    fun selectEarpiece(): Boolean = device.selectEarpiece()

    /** AudioDeviceListener 구현 — 핵플러그 시 UI 갱신용 */
    private val audioDeviceListenerImpl = object : AudioDeviceListener {
        override fun onAudioDevicesChanged(devices: List<AudioDevice>, selected: AudioDevice?) {
            val names = devices.map { it.name }
            val selectedName = selected?.name ?: ""
            listener.onAudioDevicesChanged(names, selectedName)
        }
    }

    /** 로컬 비디오 트랙 (데모앱에서 SurfaceViewRenderer 연결용) */
    fun getLocalVideoTrack(): org.webrtc.VideoTrack? = mediaSession?.getLocalVideoTrack()

    /**
     * publish PC SSRC 재추출 + PUBLISH_TRACKS 재전송.
     * 카메라 시작 후 video SSRC가 추가되면 서버에 알려줘야 함.
     */
    private fun resendPublishTracks() {
        muteHandler.postDelayed({
            val session = mediaSession ?: return@postDelayed
            val audioSsrc = session.getPublishSsrc("audio")
            val videoSsrc = session.getPublishSsrc("video")
            val trackItems = mutableListOf<com.oxlens.sdk.signaling.TrackItem>()
            audioSsrc?.let { trackItems.add(com.oxlens.sdk.signaling.TrackItem("audio", it)) }
            videoSsrc?.let { trackItems.add(com.oxlens.sdk.signaling.TrackItem("video", it)) }
            if (trackItems.isNotEmpty()) {
                signalClient?.sendRequest(Opcode.PUBLISH_TRACKS, buildPublishTracks(trackItems))
                Log.i(TAG, "PUBLISH_TRACKS re-sent: ${trackItems.map { "${it.kind}=${it.ssrc}" }}")
            }
        }, 200)
    }

    // ================================================================
    //  Mute 3-state API (home: toggleMute/isMuted 미러)
    // ================================================================

    /**
     * Mute 토글 — Conference: 3-state 순환, PTT: audio 차단 / video 토글.
     *
     * Conference 흐름:
     *   UNMUTED → toggleMute → SOFT_MUTED (track.enabled=false)
     *   SOFT_MUTED → 5초 타이머 → HARD_MUTED (TODO: 비디오 추가 시)
     *   SOFT_MUTED → toggleMute → UNMUTED
     *   HARD_MUTED → toggleMute → UNMUTED (TODO: 비디오 추가 시)
     *
     * PTT 흐름:
     *   audio: floor가 소유, 사용자 toggle 불가
     *   video: _userVideoOff 반전 → 서버 통보 (비디오 추가 시 활성화)
     */
    fun toggleMute(kind: String) {
        // -- PTT 모드 분기 --
        if (currentRoomMode == RoomMode.Ptt) {
            if (kind == "audio") {
                Log.i(TAG, "toggleMute(audio) blocked — PTT floor controls audio")
                return
            }
            // video: _userVideoOff 반전 (비디오 추가 시 실제 트랙 제어 연결)
            _userVideoOff = !_userVideoOff
            notifyMuteServer("video", _userVideoOff)
            listener.onMuteChanged("video", _userVideoOff, "ptt")
            Log.i(TAG, "PTT video toggle: videoOff=$_userVideoOff")
            return
        }

        // -- Conference 모드: 3-state --
        val state = _muteState[kind] ?: Constants.MUTE.UNMUTED

        if (state == Constants.MUTE.UNMUTED) {
            // UNMUTED → SOFT_MUTED
            cancelMuteTimer(kind)
            applySoftMute(kind, true)
            _muteState[kind] = Constants.MUTE.SOFT_MUTED
            notifyMuteServer(kind, true)
            listener.onMuteChanged(kind, true, "soft")

            // 5초 후 hard escalation 타이머
            val escalateRunnable = Runnable {
                if (_muteState[kind] != Constants.MUTE.SOFT_MUTED) return@Runnable
                doHardMute(kind)
                Log.i(TAG, "escalated to hard mute: $kind")
            }
            _muteTimers[kind] = escalateRunnable
            muteHandler.postDelayed(escalateRunnable, Constants.MUTE_ESCALATION_MS)

        } else {
            // SOFT_MUTED or HARD_MUTED → UNMUTED
            cancelMuteTimer(kind)

            if (state == Constants.MUTE.SOFT_MUTED) {
                applySoftMute(kind, false)
                _muteState[kind] = Constants.MUTE.UNMUTED
                notifyMuteServer(kind, false)
                listener.onMuteChanged(kind, false, "soft")
            } else {
                // HARD_MUTED → UNMUTED
                doHardUnmute(kind)
            }
        }
    }

    /** Mute 여부 조회 (home: isMuted 미러) */
    fun isMuted(kind: String): Boolean {
        if (currentRoomMode == RoomMode.Ptt && kind == "video") return _userVideoOff
        return _muteState[kind] != Constants.MUTE.UNMUTED
    }

    /** Mute phase 조회 (home: getMutePhase 미러) */
    fun getMutePhase(kind: String): String {
        return _muteState[kind] ?: Constants.MUTE.UNMUTED
    }

    // ── Mute 내부 ──

    private fun applySoftMute(kind: String, muted: Boolean) {
        if (kind == "audio") {
            mediaSession?.setAudioMuted(muted)
        } else {
            mediaSession?.setVideoMuted(muted)
        }
        Log.i(TAG, "applySoftMute kind=$kind muted=$muted")
    }

    private fun doHardMute(kind: String) {
        // 1차: soft mute로 대체 (hard mute는 비디오 추가 시 구현)
        // TODO: audio → AudioRecord 정지, video → camera release + dummy track
        _muteState[kind] = Constants.MUTE.HARD_MUTED
        listener.onMuteChanged(kind, true, "hard")
        Log.i(TAG, "doHardMute kind=$kind (stub — using soft mute)")
    }

    private fun doHardUnmute(kind: String) {
        // 1차: soft unmute로 대체 (hard unmute는 비디오 추가 시 구현)
        // TODO: audio → AudioRecord 재시작, video → camera restart + replaceTrack
        applySoftMute(kind, false)
        _muteState[kind] = Constants.MUTE.UNMUTED
        notifyMuteServer(kind, false)
        listener.onMuteChanged(kind, false, "hard")
        Log.i(TAG, "doHardUnmute kind=$kind (stub — using soft unmute)")
    }

    private fun notifyMuteServer(kind: String, muted: Boolean) {
        val ssrc = mediaSession?.getPublishSsrc(kind) ?: return
        signalClient?.sendRequest(Opcode.MUTE_UPDATE, buildMuteUpdate(ssrc, muted))
        Log.d(TAG, "MUTE_UPDATE sent: kind=$kind ssrc=$ssrc muted=$muted")
    }

    private fun cancelMuteTimer(kind: String) {
        _muteTimers[kind]?.let { muteHandler.removeCallbacks(it) }
        _muteTimers[kind] = null
    }

    private fun resetMute() {
        cancelMuteTimer("audio")
        cancelMuteTimer("video")
        _muteState["audio"] = Constants.MUTE.UNMUTED
        _muteState["video"] = Constants.MUTE.UNMUTED
        _userVideoOff = false
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
                pttVirtualSsrc = joinResp.pttVirtualSsrc
                remoteTracks.clear()
                remoteTracks.addAll(joinResp.tracks)

                Log.i(TAG, "room joined: ${joinResp.roomId} (${joinResp.mode.value})")
                Log.i(TAG, "  server: ${joinResp.serverConfig.ice.ip}:${joinResp.serverConfig.ice.port}")
                Log.i(TAG, "  codecs: ${joinResp.serverConfig.codecs.map { it.name }}")
                Log.i(TAG, "  tracks: ${joinResp.tracks.size}")
                Log.i(TAG, "  pttVirtualSsrc: ${joinResp.pttVirtualSsrc}")

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
            telemetry?.stop()
            telemetry = null
            floorFsm.reset()
            resetMute()
            device.deactivate()
            device.stop()
            mediaSession?.dispose()
            mediaSession = null
            currentRoomId = null
            currentRoomMode = null
            serverConfig = null
            pttVirtualSsrc = null
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

            when (type) {
                "participant_joined" -> {
                    if (userId.isNotEmpty()) listener.onParticipantJoined(userId)
                }
                "participant_left" -> {
                    if (userId.isNotEmpty()) {
                        listener.onRemoteVideoTrackRemoved(userId)
                        listener.onParticipantLeft(userId)
                    }
                }
            }
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
            telemetry?.stop()
            telemetry = null
            floorFsm.reset()
            resetMute()
            device.deactivate()
            device.stop()
            mediaSession?.dispose()
            mediaSession = null
            currentRoomId = null
            currentRoomMode = null
            serverConfig = null
            pttVirtualSsrc = null
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
            val session = MediaSession(context, mediaSessionListener, eglContext)
            session.initialize()
            mediaSession = session

            // 오디오 장치 관리 시작 + AudioFocus 획득
            val isPtt = currentRoomMode == RoomMode.Ptt
            device.start(preferSpeaker = isPtt)
            device.activate()

            // Publish PC 셋업 (SDP 교환 → ICE → DTLS → SRTP)
            // server_config에 video 코덱이 있으면 MediaSession이 카메라 자동 초기화
            session.setupPublishPc(
                config = config,
                videoSink = null,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                captureFps = captureFps,
                maxBitrateBps = maxBitrateBps,
            )

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
            Log.i(TAG, "setting up subscribe PC (${remoteTracks.size} tracks, mode=${mode.value}, vssrc=${pttVirtualSsrc})")
            session.setupSubscribePc(config, remoteTracks.toList(), mode, pttVirtualSsrc)
        } catch (e: Exception) {
            Log.e(TAG, "subscribe setup failed", e)
            listener.onError(0, "subscribe setup failed: ${e.message}")
        }
    }

    // ================================================================
    //  Telemetry 연동
    // ================================================================

    /** Telemetry 시작 — publish ICE CONNECTED 시 호출 */
    private fun startTelemetry() {
        val sig = signalClient ?: return
        if (telemetry != null) return  // 이미 시작됨

        val tel = Telemetry(sig, pcProviderImpl)
        telemetry = tel

        // 구간 S-1: SDP 1회 보고
        tel.sendSdpTelemetry()
        // 3초 주기 stats 모니터 시작
        tel.start()
        Log.i(TAG, "telemetry started")
    }

    /** PeerConnectionProvider 구현 — Telemetry에서 PC와 SDK 상태 접근 */
    private val pcProviderImpl = object : PeerConnectionProvider {
        override fun getPublishPc(): PeerConnection? = mediaSession?.getPublishPc()
        override fun getSubscribePc(): PeerConnection? = mediaSession?.getSubscribePc()

        override fun resolveSourceUser(ssrc: Long): String? {
            return remoteTracks.firstOrNull { it.ssrc == ssrc }?.userId
        }

        override fun collectPttDiagnostics(): JSONObject {
            val session = mediaSession
            return JSONObject().apply {
                // ── SDK 상태 (Home 동일) ──
                put("roomMode", currentRoomMode?.value ?: "none")
                put("floorState", floorState)
                put("speaker", speaker ?: JSONObject.NULL)
                put("userVideoOff", _userVideoOff)

                // ── 트랙 건강성 (Home: stream.getTracks() 미러) ──
                val tracks = org.json.JSONArray()
                session?.getPublishSenders()?.forEach { sender ->
                    val track = sender.track()
                    if (track != null) {
                        tracks.put(JSONObject().apply {
                            put("kind", track.kind())
                            put("enabled", track.enabled())
                            put("readyState", track.state()?.name ?: "unknown")
                            put("label", track.id() ?: "")
                        })
                    }
                }
                put("tracks", tracks)

                // ── Sender 상태 (Home: pubPc.getSenders() 미러) ──
                val senders = org.json.JSONArray()
                session?.getPublishSenders()?.forEach { sender ->
                    val track = sender.track()
                    val params = try { sender.parameters } catch (_: Exception) { null }
                    val enc0 = params?.encodings?.firstOrNull()
                    senders.put(JSONObject().apply {
                        put("kind", track?.kind() ?: "unknown")
                        put("hasTrack", track != null)
                        put("trackLabel", track?.id() ?: "(none)")
                        put("readyState", track?.state()?.name ?: "(no track)")
                        put("active", enc0?.active ?: JSONObject.NULL)
                        put("maxBitrate", enc0?.maxBitrateBps ?: JSONObject.NULL)
                    })
                }
                put("senders", senders)

                // ── PC 연결 상태 ──
                put("pubPc", session?.let {
                    JSONObject().apply {
                        put("iceState", it.publishIceState.name)
                    }
                } ?: JSONObject.NULL)
                put("subPc", session?.let {
                    JSONObject().apply {
                        put("iceState", it.subscribeIceState.name)
                    }
                } ?: JSONObject.NULL)
            }
        }

        override fun getSubscribeTrackCounts(): JSONObject? {
            val tracks = mediaSession?.getSubscribeTracks() ?: return null
            return JSONObject().apply {
                put("total", tracks.size)
                put("active", tracks.count { it.active != false })
                put("inactive", tracks.count { it.active == false })
            }
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
                    startTelemetry()
                    listener.onPublishReady()
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
            // 비디오 트랙: 리스너로 전달하여 데모앱에서 SurfaceViewRenderer 연결
            if (track is VideoTrack) {
                track.setEnabled(true)
                // trackId 포맷: "{userId}_{mid}" — userId 추출
                val trackId = track.id() ?: ""
                val userId = trackId.substringBefore("_").ifEmpty { "unknown" }
                Log.i(TAG, "remote video track enabled: $trackId (user=$userId)")
                listener.onRemoteVideoTrack(userId, track)
            }
        }

        override fun onCameraSwitched(facingMode: String) {
            Log.i(TAG, "camera switched: facing=$facingMode")
            listener.onCameraSwitched(facingMode)
        }

        override fun onError(message: String) {
            Log.e(TAG, "media error: $message")
            listener.onError(0, message)
        }
    }
}
