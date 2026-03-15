// author: kodeholic (powered by Claude)
package com.oxlens.sdk.media

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * MediaSession — 2PC PeerConnection 관리.
 *
 * Publish PC: 로컬 오디오/비디오 → 서버 (sendonly)
 * Subscribe PC: 서버 → 원격 트랙 수신 (recvonly)
 *
 * ## 2PC 프로토콜 (SDP-free)
 * 1. ROOM_JOIN → server_config 수신
 * 2. SdpBuilder로 fake remote SDP(offer) 생성
 * 3. setRemoteDescription(offer) → createAnswer → setLocalDescription(answer)
 * 4. ICE → STUN → DTLS → SRTP 확립
 * 5. PUBLISH_TRACKS(ssrc, kind) → 서버 등록
 *
 * ## Subscribe PC re-nego
 * - TRACKS_UPDATE(add/remove) → allTracks 갱신 → SDP 재조립 → re-nego
 * - m-line은 삭제 불가 → 제거된 트랙은 inactive(port=0)로 변경
 *
 * ## 스레드 모델
 * - PeerConnection 콜백은 libwebrtc 시그널링 스레드에서 호출
 * - public 메서드는 어떤 스레드에서든 호출 가능
 */
class MediaSession(
    private val context: Context,
    private val listener: MediaSessionListener,
    private val eglContext: EglBase.Context? = null,
) {
    companion object {
        private const val TAG = "MediaSession"
        private var factoryInitialized = false
    }

    private var factory: PeerConnectionFactory? = null
    private var publishPc: PeerConnection? = null
    private var subscribePc: PeerConnection? = null

    /** 현재 ServerConfig (re-nego에 사용) */
    private var serverConfig: ServerConfig? = null

    /** 현재 방 모드 */
    private var roomMode: RoomMode = RoomMode.Conference

    /** PTT 가상 SSRC */
    private var pttVirtualSsrc: PttVirtualSsrc? = null

    // Publish 트랙 정보 (PUBLISH_TRACKS 전송용)
    private val publishedTracks = mutableListOf<PublishedTrack>()

    // addTrack 시 캐시된 RtpSender (applyMaxBitrate에서 사용 — getSenders() 회피)
    private var videoSender: RtpSender? = null

    // Subscribe 트랙 목록 (re-nego용 — mid 포함)
    private val subscribeTracks = mutableListOf<TrackDesc>()

    /** mid 카운터 — re-nego 시 새 트랙에 순차 할당 */
    private var nextMid = 0

    // ================================================================
    //  비디오 캡쳐 (Camera2)
    // ================================================================

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSink: VideoSink? = null

    /** 현재 카메라 방향 ("front" | "back") */
    @Volatile
    var facingMode: String = "front"
        private set

    /** 비디오 활성화 여부 */
    @Volatile
    var videoEnabled: Boolean = false
        private set

    /** Publish PC의 ICE 연결 상태 */
    @Volatile
    var publishIceState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        private set

    /** Subscribe PC의 ICE 연결 상태 */
    @Volatile
    var subscribeIceState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        private set

    // ================================================================
    //  PeerConnection 접근자 (Telemetry용)
    // ================================================================

    /** Telemetry에서 getStats() 호출용 — publish PC 참조 */
    fun getPublishPc(): PeerConnection? = publishPc

    /** Telemetry에서 getStats() 호출용 — subscribe PC 참조 */
    fun getSubscribePc(): PeerConnection? = subscribePc

    /** Subscribe 트랙 목록 (Telemetry subTracks 카운트용) */
    fun getSubscribeTracks(): List<TrackDesc> = subscribeTracks.toList()

    /** Publish PC의 RtpSender 목록 (Telemetry PTT 진단용) */
    fun getPublishSenders(): List<RtpSender> {
        return try { publishPc?.senders?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    // ================================================================
    //  초기화 / 해제
    // ================================================================

    fun initialize() {
        if (!factoryInitialized) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            factoryInitialized = true
            Log.i(TAG, "PeerConnectionFactory.initialize() OK")
        }

        // Video codec factory — EGL 컨텍스트 주입
        // SW-only factory는 SurfaceViewRenderer와 EGL 컨텍스트 불일치로 크래시 발생.
        // DefaultVideoEncoder/DecoderFactory를 사용하여 HW 코덱 + EGL 컨텍스트 공유.
        val encoderFactory = DefaultVideoEncoderFactory(
            eglContext,
            true,   // enableIntelVp8Encoder
            true,   // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                // ICE-Lite + 단일포트 구조로 NetworkMonitor 불필요.
                // LiveKit #415에서 NetworkMonitor 관련 크래시 사례 있음.
                disableNetworkMonitor = true
            })
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        Log.i(TAG, "PeerConnectionFactory created (HW codecs, eglContext=${eglContext != null})")
    }

    fun dispose() {
        Log.i(TAG, "dispose")
        stopCamera()
        videoSender = null
        subscribePc?.dispose()
        subscribePc = null
        publishPc?.dispose()
        publishPc = null
        factory?.dispose()
        factory = null
        publishedTracks.clear()
        subscribeTracks.clear()
        serverConfig = null
        videoEnabled = false
    }

    // ================================================================
    //  마이크 Mute/Unmute
    // ================================================================

    /** publish PC의 audio sender mute/unmute */
    fun setAudioMuted(muted: Boolean) {
        val pc = publishPc ?: return
        for (sender in pc.senders) {
            val track = sender.track() ?: continue
            if (track.kind() == "audio") {
                track.setEnabled(!muted)
                Log.i(TAG, "audio track ${if (muted) "muted" else "unmuted"}")
            }
        }
    }

    /** publish PC의 video sender mute/unmute (비디오 추가 시 활성화) */
    fun setVideoMuted(muted: Boolean) {
        val pc = publishPc ?: return
        for (sender in pc.senders) {
            val track = sender.track() ?: continue
            if (track.kind() == "video") {
                track.setEnabled(!muted)
                Log.i(TAG, "video track ${if (muted) "muted" else "unmuted"}")
            }
        }
    }

    /** subscribe PC의 원격 오디오 트랙 on/off (스피커 음소거용) */
    fun setRemoteAudioEnabled(enabled: Boolean) {
        val pc = subscribePc ?: run {
            Log.w(TAG, "setRemoteAudioEnabled: subscribe PC not ready")
            return
        }
        for (receiver in pc.receivers) {
            val track = receiver.track() ?: continue
            if (track.kind() == "audio") {
                track.setEnabled(enabled)
                Log.i(TAG, "remote audio track ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    /**
     * publish PC에서 해당 kind의 SSRC 조회.
     * MUTE_UPDATE 서버 통보에 사용.
     */
    fun getPublishSsrc(kind: String): Long? {
        return publishedTracks.firstOrNull { it.kind == kind }?.ssrc
    }

    // ================================================================
    //  비디오 캡쳐 API
    // ================================================================

    /**
     * 카메라 캡쳐 시작 + publish PC에 비디오 트랙 추가.
     *
     * Camera2Enumerator로 전면 카메라 우선 선택.
     * publish PC가 이미 셋업된 상태에서 호출 가능 (re-nego 필요 없음 —
     * SDP에 이미 video m-line이 recvonly로 포함되어 있고,
     * addTrack으로 sendonly로 전환).
     *
     * @param sink 로컬 프리뷰용 VideoSink (SurfaceViewRenderer 등). null이면 프리뷰 없음.
     * @param width 캡쳐 해상도 너비 (default: 1280)
     * @param height 캡쳐 해상도 높이 (default: 720)
     * @param fps 캡쳐 프레임레이트 (default: 24)
     */
    /**
     * 카메라 시작 (이미 publish PC가 셋업된 후 호출용).
     *
     * setupPublishPc(enableVideo=true)로 시작한 경우 이미 카메라가 동작 중.
     * 이 메서드는 stopCamera() 후 재시작하거나, 나중에 카메라를 추가하는 경우용.
     * 주의: addTrack 후 re-nego 없이는 SSRC가 localDescription에 반영 안 됨.
     *        초기 비디오는 setupPublishPc(enableVideo=true)로 시작할 것.
     */
    fun startCamera(
        sink: VideoSink? = null,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 24,
    ) {
        if (videoCapturer != null) {
            Log.w(TAG, "startCamera: already running")
            return
        }
        val f = factory ?: run {
            Log.e(TAG, "startCamera: factory not initialized")
            return
        }
        initCamera(f, sink, width, height, fps)
    }

    /**
     * 카메라 초기화 내부 구현.
     * setupPublishPc와 startCamera 양쪽에서 호출.
     */
    private fun initCamera(
        f: PeerConnectionFactory,
        sink: VideoSink? = null,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 24,
    ) {
        val pc = publishPc ?: run {
            Log.e(TAG, "initCamera: publish PC not ready")
            return
        }

        // Camera2Enumerator로 카메라 선택 (전면 우선)
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backCamera = deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val cameraName = frontCamera ?: backCamera

        if (cameraName == null) {
            Log.e(TAG, "initCamera: no camera found")
            listener.onError("No camera available")
            return
        }

        facingMode = if (cameraName == frontCamera) "front" else "back"

        val capturer = enumerator.createCapturer(cameraName, null)
            ?: run {
                Log.e(TAG, "initCamera: failed to create capturer for $cameraName")
                listener.onError("Failed to create camera capturer")
                return
            }

        // VideoSource + VideoTrack 생성
        // EGL 컨텍스트: 외부 주입 우선, 없으면 새로 생성
        val eglCtx = eglContext ?: EglBase.create().eglBaseContext
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)

        val source = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, source.capturerObserver)
        capturer.startCapture(width, height, fps)

        val track = f.createVideoTrack("video0", source)
        track.setEnabled(true)

        // 로컬 프리뷰 sink 연결
        if (sink != null) {
            track.addSink(sink)
            localVideoSink = sink
        }

        // publish PC에 트랙 추가 + sender 캐시 (applyMaxBitrate용)
        videoSender = pc.addTrack(track, listOf("stream0"))
        Log.i(TAG, "video track added to publish PC (sender cached)")

        videoCapturer = capturer
        videoSource = source
        localVideoTrack = track
        videoEnabled = true

        Log.i(TAG, "camera started: $cameraName (${width}x${height}@${fps}fps, facing=$facingMode)")
    }

    /**
     * 카메라 캡쳐 정지 + 리소스 해제.
     * hard mute(video) 또는 disconnect 시 호출.
     */
    fun stopCamera() {
        // RtpSender에서 트랙 분리 (dispose 전에 반드시 먼저 — CAUTIONS 1-3)
        try {
            videoSender?.setTrack(null, false)
        } catch (e: Exception) {
            Log.w(TAG, "setTrack(null) failed: ${e.message}")
        }

        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.w(TAG, "stopCapture interrupted", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoSink?.let { localVideoTrack?.removeSink(it) }
        localVideoSink = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null

        videoEnabled = false
        Log.i(TAG, "camera stopped (sender retained for replaceTrack)")
    }

    /**
     * 전면/후면 카메라 전환.
     * CameraVideoCapturer.switchCamera() 사용 — 실시간 전환, re-nego 불필요.
     */
    fun switchCamera() {
        val capturer = videoCapturer ?: run {
            Log.w(TAG, "switchCamera: camera not running")
            return
        }
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                facingMode = if (isFront) "front" else "back"
                Log.i(TAG, "camera switched: facing=$facingMode")
                listener.onCameraSwitched(facingMode)
            }
            override fun onCameraSwitchError(error: String) {
                Log.e(TAG, "camera switch failed: $error")
                listener.onError("Camera switch failed: $error")
            }
        })
    }

    /**
     * Hard unmute 후 카메라 재시작.
     *
     * 기존 videoSender에 setTrack(newTrack)으로 새 트랙 연결 (replaceTrack).
     * addTrack 재호출 없음 — SSRC 변경/SDP re-nego 불필요.
     * CameraEventsHandler.onFirstFrameAvailable() 콜백으로 카메라 웜업 완료 감지.
     *
     * @param sink 로컬 프리뷰용 VideoSink
     * @return true: 카메라 시작 성공 (firstFrame 콜백 대기), false: 실패
     */
    fun restartCamera(
        sink: VideoSink? = null,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 24,
    ): Boolean {
        if (videoCapturer != null) {
            Log.w(TAG, "restartCamera: already running")
            return false
        }
        val f = factory ?: run {
            Log.e(TAG, "restartCamera: factory not initialized")
            return false
        }
        val sender = videoSender ?: run {
            Log.e(TAG, "restartCamera: no video sender (was camera ever started?)")
            return false
        }

        // Camera2Enumerator로 카메라 선택 (이전 facingMode 유지)
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backCamera = deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val cameraName = if (facingMode == "front") (frontCamera ?: backCamera) else (backCamera ?: frontCamera)

        if (cameraName == null) {
            Log.e(TAG, "restartCamera: no camera found")
            listener.onError("No camera available")
            return false
        }

        // CameraEventsHandler — onFirstFrameAvailable에서 CAMERA_READY 트리거
        val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
            @Volatile private var firstFrameFired = false
            override fun onFirstFrameAvailable() {
                if (firstFrameFired) return
                firstFrameFired = true
                Log.i(TAG, "\u2713 camera first frame available (restart)")
                listener.onCameraFirstFrame()
            }
            override fun onCameraOpening(cameraName: String) {
                Log.d(TAG, "camera opening: $cameraName")
            }
            override fun onCameraFreezed(error: String) {
                Log.w(TAG, "camera freezed: $error")
            }
            override fun onCameraClosed() {
                Log.d(TAG, "camera closed")
            }
            override fun onCameraError(error: String) {
                Log.e(TAG, "camera error: $error")
                listener.onError("Camera error: $error")
            }
            override fun onCameraDisconnected() {
                Log.w(TAG, "camera disconnected")
            }
        }

        val capturer = enumerator.createCapturer(cameraName, cameraEvents)
            ?: run {
                Log.e(TAG, "restartCamera: failed to create capturer")
                listener.onError("Failed to create camera capturer")
                return false
            }

        val eglCtx = eglContext ?: EglBase.create().eglBaseContext
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)

        val source = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, source.capturerObserver)
        capturer.startCapture(width, height, fps)

        val track = f.createVideoTrack("video0", source)
        track.setEnabled(true)

        // 로컬 프리뷰 sink
        if (sink != null) {
            track.addSink(sink)
            localVideoSink = sink
        }

        // replaceTrack — 기존 sender에 새 트랙 연결 (addTrack 재호출 X, SSRC 변경 X)
        try {
            sender.setTrack(track, false)
            Log.i(TAG, "replaceTrack OK (sender=${sender.id()})")
        } catch (e: Exception) {
            Log.e(TAG, "replaceTrack failed: ${e.message}")
            listener.onError("replaceTrack failed: ${e.message}")
            return false
        }

        videoCapturer = capturer
        videoSource = source
        localVideoTrack = track
        videoEnabled = true

        Log.i(TAG, "camera restarted: $cameraName (${width}x${height}@${fps}fps, facing=$facingMode)")
        return true
    }

    /**
     * 로컬 비디오 트랙 반환 (데모앱에서 SurfaceViewRenderer 연결용).
     */
    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    // ================================================================
    //  Publish PC 셋업
    // ================================================================

    /**
     * Publish PC 셋업.
     *
     * server_config에 video 코덱이 있으면 자동으로 카메라 초기화 + video track 추가.
     * 2PC 구조에서는 SDP 교환 전에 track이 있어야 localDescription에 SSRC가 포함된다.
     *
     * @param videoSink 로컬 프리뷰용 VideoSink (null이면 프리뷰 없음)
     */
    /** publish ICE CONNECTED 후 적용할 maxBitrate (bps) */
    private var pendingMaxBitrateBps: Int = 0

    fun setupPublishPc(
        config: ServerConfig,
        videoSink: VideoSink? = null,
        captureWidth: Int = 1280,
        captureHeight: Int = 720,
        captureFps: Int = 24,
        maxBitrateBps: Int = 1_500_000,
    ) {
        this.serverConfig = config
        this.pendingMaxBitrateBps = maxBitrateBps

        val f = factory ?: throw IllegalStateException("MediaSession not initialized")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 0
        }

        publishPc = f.createPeerConnection(rtcConfig, publishPcObserver)
            ?: throw IllegalStateException("Failed to create publish PeerConnection")
        Log.i(TAG, "publish PC created")

        addDummyAudioTrack(f)

        // 비디오: server_config에 video 코덱이 있으면 카메라 자동 초기화
        val hasVideoCodec = config.codecs.any { it.kind == MediaKind.Video }
        if (hasVideoCodec) {
            initCamera(f, videoSink, captureWidth, captureHeight, captureFps)
        } else {
            Log.i(TAG, "server has no video codec — audio only")
        }

        val offerSdp = SdpBuilder.buildPublishRemoteSdp(config)
        Log.d(TAG, "publish offer SDP (${offerSdp.length} bytes)")

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        publishPc!!.setRemoteDescription(object : SdpObserverAdapter("pub-setRemote") {
            override fun onSetSuccess() {
                super.onSetSuccess()
                createPublishAnswer()
            }
        }, remoteDesc)
    }

    private fun createPublishAnswer() {
        val pc = publishPc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createAnswer(object : SdpObserverAdapter("pub-createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                Log.d(TAG, "publish answer SDP (${sdp.description.length} bytes)")
                pc.setLocalDescription(object : SdpObserverAdapter("pub-setLocal") {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        Log.i(TAG, "publish PC SDP exchange complete — waiting for ICE")
                        // sdp.description을 직접 전달 — setLocalDescription 콜백 안에서
                        // getLocalDescription() JNI 재호출 시 cross-thread invoke
                        // re-entrancy로 DCHECK 실패 (rtc_base/thread.cc:785)
                        extractPublishedSsrcs(sdp.description)
                        listener.onPublishPcReady(publishedTracks)
                    }
                }, sdp)
            }
        }, constraints)
    }

    private fun addDummyAudioTrack(f: PeerConnectionFactory) {
        val audioConstraints = MediaConstraints()
        val audioSource = f.createAudioSource(audioConstraints)
        val audioTrack = f.createAudioTrack("audio0", audioSource)
        audioTrack.setEnabled(true)
        val pc = publishPc ?: return
        val sender = pc.addTrack(audioTrack, listOf("stream0"))
        Log.i(TAG, "audio track added to publish PC (sender=${sender.id()})")
    }

    /**
     * SDP 문자열에서 publish 트랙의 primary SSRC를 추출.
     *
     * RTX SSRC 필터링: a=ssrc-group:FID <primary> <rtx> 라인을 먼저 파싱하여
     * RTX SSRC set을 만들고, a=ssrc: 추출 시 RTX set에 포함된 SSRC는 skip.
     *
     * @param sdpText setLocalDescription에 전달한 SDP 문자열 (콜백 내에서 JNI 재호출 회피)
     */
    private fun extractPublishedSsrcs(sdpText: String) {
        val localSdp = sdpText
        publishedTracks.clear()

        val lines = localSdp.split("\r\n", "\n")

        // 1단계: ssrc-group:FID에서 RTX SSRC 수집
        val rtxSsrcs = mutableSetOf<Long>()
        val fidRegex = Regex("""a=ssrc-group:FID\s+(\d+)\s+(\d+)""")
        for (line in lines) {
            fidRegex.find(line)?.let { match ->
                val rtxSsrc = match.groupValues[2].toLongOrNull()
                if (rtxSsrc != null) {
                    rtxSsrcs.add(rtxSsrc)
                    Log.d(TAG, "RTX SSRC filtered: ${match.groupValues[1]} → rtx=$rtxSsrc")
                }
            }
        }

        // 2단계: m-line별 primary SSRC 추출 (RTX 제외)
        val ssrcRegex = Regex("""a=ssrc:(\d+)\s+cname:""")
        val mLineRegex = Regex("""m=(audio|video)\s+""")
        var currentKind = "audio"
        val ssrcSet = mutableSetOf<Long>()

        for (line in lines) {
            mLineRegex.find(line)?.let { currentKind = it.groupValues[1]; return@let }
            ssrcRegex.find(line)?.let { match ->
                val ssrc = match.groupValues[1].toLongOrNull() ?: return@let
                if (ssrc !in ssrcSet && ssrc !in rtxSsrcs) {
                    ssrcSet.add(ssrc)
                    publishedTracks.add(PublishedTrack(kind = currentKind, ssrc = ssrc))
                    Log.i(TAG, "extracted SSRC: $currentKind=$ssrc")
                }
            }
        }

        if (rtxSsrcs.isNotEmpty()) {
            Log.i(TAG, "filtered ${rtxSsrcs.size} RTX SSRC(s) from PUBLISH_TRACKS")
        }
    }

    // ================================================================
    //  Subscribe PC 셋업
    // ================================================================

    /**
     * Subscribe PC 초기 셋업.
     *
     * ROOM_JOIN 시점에 기존 참가자 트랙이 있으면 호출.
     * 트랙이 없으면 첫 TRACKS_UPDATE(add) 때 호출됨.
     */
    fun setupSubscribePc(
        config: ServerConfig,
        initialTracks: List<TrackDesc>,
        mode: RoomMode = RoomMode.Conference,
        pttVssrc: PttVirtualSsrc? = null,
    ) {
        this.serverConfig = config
        this.roomMode = mode
        this.pttVirtualSsrc = pttVssrc

        val f = factory ?: throw IllegalStateException("MediaSession not initialized")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 0
        }

        subscribePc = f.createPeerConnection(rtcConfig, subscribePcObserver)
            ?: throw IllegalStateException("Failed to create subscribe PeerConnection")
        Log.i(TAG, "subscribe PC created")

        // 트랙에 mid 할당
        subscribeTracks.clear()
        nextMid = 0
        for (track in initialTracks) {
            val t = if (track.mid == null) {
                track.copy(mid = nextMid.toString()).also { nextMid++ }
            } else {
                nextMid = maxOf(nextMid, (track.mid.toIntOrNull() ?: 0) + 1)
                track
            }
            subscribeTracks.add(t)
        }

        applySubscribeSdp()
    }

    /**
     * Subscribe PC re-negotiation.
     *
     * TRACKS_UPDATE(add) → 새 트랙 추가 후 호출
     * TRACKS_UPDATE(remove) → 해당 트랙 active=false 후 호출
     */
    fun updateSubscribeTracks(action: String, updatedTracks: List<TrackDesc>) {
        if (action == "add") {
            for (track in updatedTracks) {
                // 이미 존재하는 SSRC면 active=true로 복원
                val existing = subscribeTracks.indexOfFirst { it.ssrc == track.ssrc }
                if (existing >= 0) {
                    subscribeTracks[existing] = subscribeTracks[existing].copy(active = true)
                } else {
                    val mid = nextMid.toString()
                    nextMid++
                    subscribeTracks.add(track.copy(mid = mid, active = true))
                }
            }
        } else if (action == "remove") {
            for (track in updatedTracks) {
                val idx = subscribeTracks.indexOfFirst { it.ssrc == track.ssrc }
                if (idx >= 0) {
                    subscribeTracks[idx] = subscribeTracks[idx].copy(active = false)
                }
            }
        }

        // Subscribe PC가 없으면 새로 생성
        if (subscribePc == null) {
            val config = serverConfig ?: return
            setupSubscribePc(config, subscribeTracks.toList(), roomMode, pttVirtualSsrc)
        } else {
            applySubscribeSdp()
        }
    }

    /** Subscribe SDP 적용 (초기 또는 re-nego) */
    private fun applySubscribeSdp() {
        val pc = subscribePc ?: return
        val config = serverConfig ?: return

        if (subscribeTracks.isEmpty()) {
            Log.d(TAG, "subscribe: no tracks, skipping SDP")
            return
        }

        val offerSdp = SdpBuilder.buildSubscribeRemoteSdp(
            config, subscribeTracks, roomMode, pttVirtualSsrc
        )
        Log.d(TAG, "subscribe offer SDP (${offerSdp.length} bytes, ${subscribeTracks.size} tracks)")
        Log.d(TAG, "subscribe SDP:\n$offerSdp")

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        pc.setRemoteDescription(object : SdpObserverAdapter("sub-setRemote") {
            override fun onSetSuccess() {
                super.onSetSuccess()
                createSubscribeAnswer()
            }
        }, remoteDesc)
    }

    private fun createSubscribeAnswer() {
        val pc = subscribePc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createAnswer(object : SdpObserverAdapter("sub-createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                Log.d(TAG, "subscribe answer SDP (${sdp.description.length} bytes)")
                pc.setLocalDescription(object : SdpObserverAdapter("sub-setLocal") {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        Log.i(TAG, "subscribe PC SDP exchange complete — waiting for ICE")
                    }
                }, sdp)
            }
        }, constraints)
    }

    // ================================================================
    //  AudioInterceptor 제어 (PTT silence injection)
    // ================================================================
    //
    //  커스텀 libwebrtc AAR에 추가된 PeerConnection Java API:
    //    enableAudioInterceptor(boolean)
    //    setAudioInterceptorSilence(boolean, long)
    //    resetAudioInterceptorOffset()
    //    setAudioInterceptorOpusPt(int)
    //
    //  표준 AAR에는 이 메서드가 없으므로 Reflection으로 호출.
    //  커스텀 AAR이면 정상 동작, 표준 AAR이면 graceful skip.
    // ================================================================

    /** interceptor 사용 가능 여부 (첫 호출 시 lazy 판정) */
    @Volatile
    private var interceptorAvailable: Boolean? = null

    /**
     * Reflection으로 PeerConnection 메서드 호출.
     * 첫 호출 시 메서드 존재 여부를 판정하여 캐싱.
     * 메서드가 없으면 로그 1회 출력 후 이후 호출은 즉시 리턴.
     */
    private fun callInterceptor(methodName: String, vararg args: Any) {
        val pc = subscribePc ?: run {
            Log.w(TAG, "$methodName: subscribe PC not ready")
            return
        }

        // 이미 불가 판정 → 즉시 리턴
        if (interceptorAvailable == false) return

        try {
            val paramTypes = args.map { arg ->
                when (arg) {
                    is Boolean -> Boolean::class.javaPrimitiveType!!
                    is Long -> Long::class.javaPrimitiveType!!
                    is Int -> Int::class.javaPrimitiveType!!
                    else -> arg::class.java
                }
            }.toTypedArray()

            val method = pc.javaClass.getMethod(methodName, *paramTypes)
            method.invoke(pc, *args)
            interceptorAvailable = true
        } catch (e: NoSuchMethodException) {
            if (interceptorAvailable == null) {
                Log.w(TAG, "AudioInterceptor not available in this AAR (missing $methodName) — PTT silence injection disabled")
                interceptorAvailable = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "$methodName reflection failed: ${e.message}")
        }
    }

    /**
     * Subscribe PC의 AudioInterceptor 활성화/비활성화.
     * PTT 방 입장 시 enable, 퇴장 시 disable.
     */
    fun enableAudioInterceptor(enable: Boolean) {
        callInterceptor("enableAudioInterceptor", enable)
        if (interceptorAvailable == true) {
            Log.i(TAG, "audioInterceptor enabled=$enable")
        }
    }

    /**
     * Silence injection 시작/중지.
     * @param inject true: silence frame 주입 시작, false: 중지 (실제 오디오 통과)
     * @param ssrc audio virtual SSRC (inject=true일 때만 의미 있음)
     */
    fun setAudioInterceptorSilence(inject: Boolean, ssrc: Long) {
        callInterceptor("setAudioInterceptorSilence", inject, ssrc)
        if (interceptorAvailable == true) {
            Log.i(TAG, "audioInterceptor silence=$inject ssrc=0x${ssrc.toString(16)}")
        }
    }

    /**
     * Offset 리셋 — 방 퇴장 시 호출.
     * 다음 방 입장 시 seq/ts offset이 0부터 다시 시작.
     */
    fun resetAudioInterceptorOffset() {
        callInterceptor("resetAudioInterceptorOffset")
        if (interceptorAvailable == true) {
            Log.i(TAG, "audioInterceptor offset reset")
        }
    }

    /**
     * Opus payload type 설정.
     * 서버 코덱 정책에서 Opus PT를 읽어 전달 (기본 111).
     */
    fun setAudioInterceptorOpusPt(pt: Int) {
        callInterceptor("setAudioInterceptorOpusPt", pt)
        if (interceptorAvailable == true) {
            Log.i(TAG, "audioInterceptor opusPt=$pt")
        }
    }

    // ================================================================
    //  Bitrate 제어
    // ================================================================

    /**
     * publish PC의 video RtpSender에 maxBitrate 적용.
     * ICE CONNECTED 후 호출해야 sender.getParameters()가 유효.
     */
    fun applyMaxBitrate(maxBps: Int) {
        val sender = videoSender ?: run {
            Log.w(TAG, "applyMaxBitrate: no video sender cached")
            return
        }
        try {
            val params = sender.parameters
            if (params.encodings.isEmpty()) {
                Log.w(TAG, "applyMaxBitrate: no encodings")
                return
            }
            for (encoding in params.encodings) {
                encoding.maxBitrateBps = maxBps
            }
            sender.parameters = params
            Log.i(TAG, "maxBitrate applied: ${maxBps / 1000}kbps")
        } catch (e: Exception) {
            Log.w(TAG, "applyMaxBitrate failed: ${e.message}")
        }
    }

    // ================================================================
    //  PeerConnection Observers
    // ================================================================

    private val publishPcObserver = object : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            publishIceState = state
            Log.i(TAG, "publish ICE: $state")
            // ICE CONNECTED → maxBitrate 적용
            if (state == PeerConnection.IceConnectionState.CONNECTED && pendingMaxBitrateBps > 0) {
                applyMaxBitrate(pendingMaxBitrateBps)
            }
            listener.onPublishIceStateChange(state)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "publish PC state: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "publish ICE candidate (ignored): ${candidate.sdp}")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "publish signaling: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "publish ICE gathering: $state")
        }
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "publish renegotiation needed (ignored)")
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private val subscribePcObserver = object : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            subscribeIceState = state
            Log.i(TAG, "subscribe ICE: $state")
            listener.onSubscribeIceStateChange(state)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "subscribe PC state: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "subscribe ICE candidate (ignored): ${candidate.sdp}")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "subscribe signaling: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "subscribe ICE gathering: $state")
        }
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "subscribe renegotiation needed (ignored)")
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val kind = receiver.track()?.kind() ?: "unknown"
            Log.i(TAG, "subscribe onAddTrack: kind=$kind id=${receiver.track()?.id()}")
            listener.onRemoteTrackAdded(receiver, streams)
        }
    }

    // ================================================================
    //  SDP Observer Adapter
    // ================================================================

    private open inner class SdpObserverAdapter(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "[$tag] createSuccess")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "[$tag] setSuccess")
        }
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "[$tag] createFailure: $error")
            listener.onError("$tag createFailure: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "[$tag] setFailure: $error")
            listener.onError("$tag setFailure: $error")
        }
    }
}

// ================================================================
//  Data types
// ================================================================

data class PublishedTrack(
    val kind: String,
    val ssrc: Long,
)

// ================================================================
//  Listener
// ================================================================

interface MediaSessionListener {
    fun onPublishPcReady(tracks: List<PublishedTrack>)
    fun onPublishIceStateChange(state: PeerConnection.IceConnectionState)
    fun onSubscribeIceStateChange(state: PeerConnection.IceConnectionState)
    /** 원격 트랙 수신 — UI에서 AudioTrack 재생 등 */
    fun onRemoteTrackAdded(receiver: RtpReceiver, streams: Array<out MediaStream>)
    /** 카메라 전환 완료 */
    fun onCameraSwitched(facingMode: String) {}
    /** 카메라 첫 프레임 수신 (hard unmute 후 웜업 완료 → CAMERA_READY 시그널 트리거) */
    fun onCameraFirstFrame() {}
    fun onError(message: String)
}
