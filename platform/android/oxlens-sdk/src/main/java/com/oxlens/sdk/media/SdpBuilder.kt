// author: kodeholic (powered by Claude)
package com.oxlens.sdk.media

/**
 * SDP 빌더 — server_config JSON → fake remote SDP 조립.
 *
 * 서버는 SDP를 모른다. 클라이언트가 이 모듈로 SDP를 만들어
 * setRemoteDescription → createAnswer → setLocalDescription 한다.
 *
 * Rust oxlens-core/src/sdp/builder.rs 포팅.
 */
object SdpBuilder {

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * Publish PC용 remote SDP 생성 (서버 = recvonly offer).
     *
     * 브라우저/libwebrtc는 이 offer에 대해 sendonly answer를 만든다.
     * m-line: audio 1개 + video 1개 (고정)
     */
    fun buildPublishRemoteSdp(config: ServerConfig): String {
        val audioCodecs = config.codecs.filter { it.kind == MediaKind.Audio }
        val videoCodecs = config.codecs.filter { it.kind == MediaKind.Video }

        val iceCred = IceCred(config.ice.publishUfrag, config.ice.publishPwd)

        val sections = mutableListOf<MediaSectionResult>()
        var midCounter = 0

        // audio m-line
        if (audioCodecs.isNotEmpty()) {
            val mid = midCounter.toString()
            midCounter++
            sections.add(buildMediaSection(MediaSectionOpts(
                mid = mid,
                kind = MediaKind.Audio,
                codecs = audioCodecs,
                extmap = config.extmap,
                direction = Direction.RecvOnly,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
            )))
        }

        // video m-line
        if (videoCodecs.isNotEmpty()) {
            val mid = midCounter.toString()
            sections.add(buildMediaSection(MediaSectionOpts(
                mid = mid,
                kind = MediaKind.Video,
                codecs = videoCodecs,
                extmap = config.extmap,
                direction = Direction.RecvOnly,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
            )))
        }

        val bundleMids = collectBundleMids(sections)
        val sb = StringBuilder()
        sb.append(buildSessionHeader(bundleMids))
        for (s in sections) sb.append(s.sdp)
        return sb.toString()
    }

    /**
     * Subscribe PC용 remote SDP 생성 (서버 = sendonly offer × N).
     *
     * Conference 모드: 트랙별 m-line.
     * PTT 모드: 가상 SSRC 1쌍으로 2 m-line.
     */
    fun buildSubscribeRemoteSdp(
        config: ServerConfig,
        tracks: List<TrackDesc>,
        mode: RoomMode = RoomMode.Conference,
        pttVirtualSsrc: PttVirtualSsrc? = null,
    ): String {
        // PTT 모드
        if (mode == RoomMode.Ptt && pttVirtualSsrc != null) {
            return buildPttSubscribeSdp(config, pttVirtualSsrc)
        }

        val iceCred = IceCred(config.ice.subscribeUfrag, config.ice.subscribePwd)

        // Conference 모드: 트랙 없으면 최소 SDP (inactive audio 1개)
        if (tracks.isEmpty()) {
            val audioCodecs = config.codecs.filter { it.kind == MediaKind.Audio }
            val section = buildMediaSection(MediaSectionOpts(
                mid = "0",
                kind = MediaKind.Audio,
                codecs = audioCodecs,
                extmap = config.extmap,
                direction = Direction.Inactive,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
            ))
            val sb = StringBuilder()
            sb.append(buildSessionHeader(listOf("0")))
            sb.append(section.sdp)
            return sb.toString()
        }

        // subscribe SDP에서 sdes:mid extmap 제거 (SSRC 기반 demux)
        val subExtmap = config.extmap.filter { it.uri != EXTMAP_SDES_MID }

        val sections = mutableListOf<MediaSectionResult>()

        for ((idx, track) in tracks.withIndex()) {
            val trackCodecs = config.codecs.filter { it.kind == track.kind }
            if (trackCodecs.isEmpty()) continue

            val mid = track.mid ?: idx.toString()

            val direction = if (track.active) Direction.SendOnly else Direction.Inactive

            val msid = if (track.active) {
                "light-${track.userId} ${track.trackId}"
            } else null

            val rtxSsrc = if (track.active && track.kind == MediaKind.Video) {
                track.rtxSsrc
            } else null

            // Android libwebrtc: RTX PT가 m-line에 선언되면 ssrc-group:FID 필수.
            // rtxSsrc가 없으면 코덱에서 RTX PT 제거 (Chrome은 관대하지만 Android AAR은 크래시)
            val effectiveCodecs = if (rtxSsrc == null && track.kind == MediaKind.Video) {
                trackCodecs.map { it.copy(rtxPt = null) }
            } else {
                trackCodecs
            }

            sections.add(buildMediaSection(MediaSectionOpts(
                mid = mid,
                kind = track.kind,
                codecs = effectiveCodecs,
                extmap = subExtmap,
                direction = direction,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
                ssrc = if (track.active) track.ssrc else null,
                rtxSsrc = rtxSsrc,
                msid = msid,
            )))
        }

        val bundleMids = collectBundleMids(sections)
        val sb = StringBuilder()
        sb.append(buildSessionHeader(bundleMids))
        for (s in sections) sb.append(s.sdp)
        return sb.toString()
    }

    /**
     * Subscribe PC re-negotiation용 SDP 재조립.
     * 전체 트랙 목록을 받아 SDP를 처음부터 조립.
     */
    fun updateSubscribeRemoteSdp(
        config: ServerConfig,
        allTracks: List<TrackDesc>,
        mode: RoomMode = RoomMode.Conference,
        pttVirtualSsrc: PttVirtualSsrc? = null,
    ): String = buildSubscribeRemoteSdp(config, allTracks, mode, pttVirtualSsrc)

    // ================================================================
    //  PTT Subscribe SDP
    // ================================================================

    /**
     * PTT 모드 subscribe SDP — 가상 SSRC 1쌍(audio+video)으로 2개 m-line.
     *
     * 서버가 화자 교대 시 SSRC/seq/ts를 리라이팅하므로
     * libwebrtc는 하나의 연속 스트림으로 인식.
     */
    private fun buildPttSubscribeSdp(config: ServerConfig, vssrc: PttVirtualSsrc): String {
        val audioCodecs = config.codecs.filter { it.kind == MediaKind.Audio }
        val videoCodecs = config.codecs.filter { it.kind == MediaKind.Video }

        val subExtmap = config.extmap.filter { it.uri != EXTMAP_SDES_MID }
        val iceCred = IceCred(config.ice.subscribeUfrag, config.ice.subscribePwd)

        val sections = mutableListOf<MediaSectionResult>()

        // PTT audio m-line (mid=0)
        if (audioCodecs.isNotEmpty()) {
            sections.add(buildMediaSection(MediaSectionOpts(
                mid = "0",
                kind = MediaKind.Audio,
                codecs = audioCodecs,
                extmap = subExtmap,
                direction = Direction.SendOnly,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
                ssrc = vssrc.audio,
                msid = "light-ptt ptt-audio",
            )))
        }

        // PTT video m-line (mid=1)
        val videoSsrc = vssrc.video
        if (videoSsrc != null && videoCodecs.isNotEmpty()) {
            sections.add(buildMediaSection(MediaSectionOpts(
                mid = "1",
                kind = MediaKind.Video,
                codecs = videoCodecs,
                extmap = subExtmap,
                direction = Direction.SendOnly,
                ice = iceCred,
                dtls = config.dtls,
                ip = config.ice.ip,
                port = config.ice.port,
                ssrc = videoSsrc,
                msid = "light-ptt ptt-video",
            )))
        }

        val bundleMids = collectBundleMids(sections)
        val sb = StringBuilder()
        sb.append(buildSessionHeader(bundleMids))
        for (s in sections) sb.append(s.sdp)
        return sb.toString()
    }

    // ================================================================
    //  Internal: Session Header
    // ================================================================

    private fun buildSessionHeader(mids: List<String>): String {
        val sessionId = System.currentTimeMillis()
        return "v=0\r\n" +
            "o=oxlens-sfu $sessionId $sessionId IN IP4 0.0.0.0\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=group:BUNDLE ${mids.joinToString(" ")}\r\n" +
            "a=ice-lite\r\n"
    }

    // ================================================================
    //  Internal: Media Section Builder
    // ================================================================

    private enum class Direction(val value: String) {
        SendOnly("sendonly"),
        RecvOnly("recvonly"),
        Inactive("inactive");

        val isInactive: Boolean get() = this == Inactive
    }

    private data class IceCred(val ufrag: String, val pwd: String)

    private data class MediaSectionOpts(
        val mid: String,
        val kind: MediaKind,
        val codecs: List<CodecConfig>,
        val extmap: List<ExtmapEntry>,
        val direction: Direction,
        val ice: IceCred,
        val dtls: DtlsConfig,
        val ip: String,
        val port: Int,
        val ssrc: Long? = null,
        val rtxSsrc: Long? = null,
        val msid: String? = null,
    )

    private data class MediaSectionResult(
        val mid: String,
        val sdp: String,
        val active: Boolean,
    )

    /** 단일 m= 섹션 생성 */
    private fun buildMediaSection(opts: MediaSectionOpts): MediaSectionResult {
        // PT 목록 수집 (rtx_pt 포함)
        val pts = mutableListOf<Int>()
        for (c in opts.codecs) {
            pts.add(c.pt)
            c.rtxPt?.let { pts.add(it) }
        }

        // inactive이면 port=0
        val mPort = if (opts.direction.isInactive) 0 else opts.port
        val ptList = pts.joinToString(" ")

        val sb = StringBuilder(1024)

        // m= line
        sb.append("m=${opts.kind.value} $mPort UDP/TLS/RTP/SAVPF $ptList\r\n")

        // connection
        sb.append("c=IN IP4 ${opts.ip}\r\n")

        // ICE
        sb.append("a=ice-ufrag:${opts.ice.ufrag}\r\n")
        sb.append("a=ice-pwd:${opts.ice.pwd}\r\n")

        // DTLS
        sb.append("a=fingerprint:${opts.dtls.fingerprint}\r\n")
        sb.append("a=setup:${opts.dtls.setup}\r\n")

        // mid
        sb.append("a=mid:${opts.mid}\r\n")

        // rtcp-mux (BUNDLE 필수)
        sb.append("a=rtcp-mux\r\n")

        // rtcp-rsize (video에만)
        if (opts.kind == MediaKind.Video) {
            sb.append("a=rtcp-rsize\r\n")
        }

        // direction
        sb.append("a=${opts.direction.value}\r\n")

        // codecs: rtpmap + fmtp + rtcp-fb
        for (c in opts.codecs) {
            // rtpmap — audio에서 channels > 1이면 채널수 포함
            if (opts.kind == MediaKind.Audio && c.channels != null && c.channels > 1) {
                sb.append("a=rtpmap:${c.pt} ${c.name}/${c.clockrate}/${c.channels}\r\n")
            } else {
                sb.append("a=rtpmap:${c.pt} ${c.name}/${c.clockrate}\r\n")
            }

            // fmtp
            c.fmtp?.let { sb.append("a=fmtp:${c.pt} $it\r\n") }

            // rtcp-fb
            for (fb in c.rtcpFb) {
                sb.append("a=rtcp-fb:${c.pt} $fb\r\n")
            }

            // RTX codec
            c.rtxPt?.let { rtxPt ->
                sb.append("a=rtpmap:$rtxPt rtx/${c.clockrate}\r\n")
                sb.append("a=fmtp:$rtxPt apt=${c.pt}\r\n")
            }
        }

        // extmap (kind별 필터링: audio 전용 extmap을 video m-line에 넣으면 Android libwebrtc 크래시)
        for (ext in opts.extmap) {
            if (opts.kind == MediaKind.Video && ext.uri in AUDIO_ONLY_EXTMAP_URIS) continue
            sb.append("a=extmap:${ext.id} ${ext.uri}\r\n")
        }

        // SSRC + msid (sendonly일 때만)
        opts.ssrc?.let { ssrc ->
            sb.append("a=ssrc:$ssrc cname:oxlens-sfu\r\n")
            opts.msid?.let { msid ->
                sb.append("a=ssrc:$ssrc msid:$msid\r\n")
            }

            // RTX SSRC (video only, RFC 4588)
            if (opts.kind == MediaKind.Video) {
                opts.rtxSsrc?.let { rtxSsrc ->
                    sb.append("a=ssrc:$rtxSsrc cname:oxlens-sfu\r\n")
                    opts.msid?.let { msid ->
                        sb.append("a=ssrc:$rtxSsrc msid:$msid\r\n")
                    }
                    sb.append("a=ssrc-group:FID $ssrc $rtxSsrc\r\n")
                }
            }
        }

        // ICE candidate (inactive가 아닐 때만)
        if (!opts.direction.isInactive) {
            sb.append("a=candidate:1 1 udp 2113937151 ${opts.ip} ${opts.port} typ host generation 0\r\n")
            sb.append("a=end-of-candidates\r\n")
        }

        return MediaSectionResult(
            mid = opts.mid,
            sdp = sb.toString(),
            active = !opts.direction.isInactive,
        )
    }

    // ================================================================
    //  Internal: Helpers
    // ================================================================

    /**
     * BUNDLE에 포함할 active m-line mids 수집.
     *
     * inactive(port=0)는 BUNDLE에서 제외해야 Chrome re-nego 통과.
     * BUNDLE이 비어있으면 첫 번째 mid라도 넣어야 SDP 유효.
     */
    private fun collectBundleMids(sections: List<MediaSectionResult>): List<String> {
        val active = sections.filter { it.active }.map { it.mid }
        return active.ifEmpty {
            listOf(sections.firstOrNull()?.mid ?: "0")
        }
    }
}
