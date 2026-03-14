// author: kodeholic (powered by Claude)
package com.oxlens.sdk.media

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory

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

    // Subscribe 트랙 목록 (re-nego용 — mid 포함)
    private val subscribeTracks = mutableListOf<TrackDesc>()

    /** mid 카운터 — re-nego 시 새 트랙에 순차 할당 */
    private var nextMid = 0

    /** Publish PC의 ICE 연결 상태 */
    @Volatile
    var publishIceState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        private set

    /** Subscribe PC의 ICE 연결 상태 */
    @Volatile
    var subscribeIceState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        private set

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

        // Video codec factory 설정 필수 — 없으면 video m-line 처리 시
        // worker_thread에서 빈 코덱 벡터 접근으로 크래시 (front() on empty vector)
        val encoderFactory = SoftwareVideoEncoderFactory()
        val decoderFactory = SoftwareVideoDecoderFactory()

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        Log.i(TAG, "PeerConnectionFactory created (SW video codecs)")
    }

    fun dispose() {
        Log.i(TAG, "dispose")
        subscribePc?.dispose()
        subscribePc = null
        publishPc?.dispose()
        publishPc = null
        factory?.dispose()
        factory = null
        publishedTracks.clear()
        subscribeTracks.clear()
        serverConfig = null
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

    // ================================================================
    //  Publish PC 셋업
    // ================================================================

    fun setupPublishPc(config: ServerConfig) {
        this.serverConfig = config

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
                        extractPublishedSsrcs()
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

    private fun extractPublishedSsrcs() {
        val localSdp = publishPc?.localDescription?.description ?: return
        publishedTracks.clear()
        val ssrcRegex = Regex("""a=ssrc:(\d+)\s+cname:""")
        val mLineRegex = Regex("""m=(audio|video)\s+""")
        var currentKind = "audio"
        val ssrcSet = mutableSetOf<Long>()

        for (line in localSdp.split("\r\n", "\n")) {
            mLineRegex.find(line)?.let { currentKind = it.groupValues[1]; return@let }
            ssrcRegex.find(line)?.let { match ->
                val ssrc = match.groupValues[1].toLongOrNull() ?: return@let
                if (ssrc !in ssrcSet) {
                    ssrcSet.add(ssrc)
                    publishedTracks.add(PublishedTrack(kind = currentKind, ssrc = ssrc))
                    Log.i(TAG, "extracted SSRC: $currentKind=$ssrc")
                }
            }
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
    //  PeerConnection Observers
    // ================================================================

    private val publishPcObserver = object : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            publishIceState = state
            Log.i(TAG, "publish ICE: $state")
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
    fun onError(message: String)
}
