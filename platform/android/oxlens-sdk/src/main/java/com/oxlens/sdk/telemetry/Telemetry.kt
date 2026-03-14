// author: kodeholic (powered by Claude)
package com.oxlens.sdk.telemetry

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oxlens.sdk.signaling.Opcode
import com.oxlens.sdk.signaling.Packet
import com.oxlens.sdk.signaling.SignalClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * Media Telemetry — Home telemetry.js 1:1 Kotlin 포팅.
 *
 * 책임:
 *   - 구간 S-1: SDP 상태 1회 보고
 *   - 구간 S-2: encoder/decoder 코덱 상태 (outbound/inbound-rtp에 포함)
 *   - 구간 A: publish outbound-rtp + candidate-pair (3초 주기)
 *   - 구간 C: subscribe inbound-rtp (3초 주기)
 *   - delta bitrate / jitterBuffer delta 계산
 *   - 이벤트 타임라인: 상태 전이 감지 (10종) + 링버퍼 기록
 *   - PTT 진단 (track/sender/PC 건강성)
 *
 * ## 스레드 모델
 * - 3초 타이머: Handler(mainLooper) 기반
 * - getStats(): libwebrtc 내부 스레드에서 콜백 → synchronized로 합류
 * - WS 전송: signalClient.send() (OkHttp 내부에서 thread-safe)
 */
class Telemetry(
    private val signalClient: SignalClient,
    private val pcProvider: PeerConnectionProvider,
) {
    companion object {
        private const val TAG = "Telemetry"
        private const val STATS_INTERVAL_MS = 3000L
        private const val EVENT_LOG_MAX = 50
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var tick = 0

    // 이전 tick stats (delta 계산용)
    private val prevPub = mutableMapOf<String, Long>()
    private val prevSub = mutableMapOf<String, Long>()
    private val prevJb = mutableMapOf<String, JbPrev>()
    private val prevQld = mutableMapOf<String, Map<String, Double>>()

    // 이벤트 타임라인
    private val eventLog = mutableListOf<JSONObject>()
    private val watchState = mutableMapOf<String, JSONObject>()
    private var pendingEvents = mutableListOf<JSONObject>()

    // ================================================================
    //  Start / Stop
    // ================================================================

    fun start() {
        stop()
        tick = 0
        prevPub.clear(); prevSub.clear(); prevJb.clear(); prevQld.clear()
        eventLog.clear(); watchState.clear(); pendingEvents.clear()

        statsRunnable = object : Runnable {
            override fun run() {
                collectAndSend()
                handler.postDelayed(this, STATS_INTERVAL_MS)
            }
        }
        handler.postDelayed(statsRunnable!!, STATS_INTERVAL_MS)
        Log.i(TAG, "telemetry started (interval=${STATS_INTERVAL_MS}ms)")
    }

    fun stop() {
        statsRunnable?.let { handler.removeCallbacks(it) }
        statsRunnable = null
    }

    // ================================================================
    //  구간 S-1: SDP 상태 1회 보고
    // ================================================================

    fun sendSdpTelemetry() {
        val data = JSONObject().apply { put("section", "sdp") }

        pcProvider.getPublishPc()?.let { pc ->
            data.put("pub_local_sdp", pc.localDescription?.description ?: JSONObject.NULL)
            data.put("pub_remote_sdp", pc.remoteDescription?.description ?: JSONObject.NULL)
            data.put("pub_mline_summary", parseMlineSummary(pc.localDescription?.description))
        }

        pcProvider.getSubscribePc()?.let { pc ->
            data.put("sub_local_sdp", pc.localDescription?.description ?: JSONObject.NULL)
            data.put("sub_remote_sdp", pc.remoteDescription?.description ?: JSONObject.NULL)
            data.put("sub_mline_summary", parseMlineSummary(pc.localDescription?.description))
        }

        send(data)
        Log.i(TAG, "SDP telemetry sent")
    }

    private fun parseMlineSummary(sdp: String?): JSONArray {
        val result = JSONArray()
        if (sdp == null) return result

        val sections = sdp.split(Regex("(?=^m=)", RegexOption.MULTILINE))
            .filter { it.startsWith("m=") }

        for (sec in sections) {
            val firstLine = sec.split("\r\n", "\n")[0]
            val parts = firstLine.split(" ")
            val kind = parts[0].removePrefix("m=")
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val midMatch = Regex("""a=mid:(\S+)""").find(sec)
            val dirMatch = Regex("""a=(sendonly|recvonly|sendrecv|inactive)""").find(sec)
            val ssrcMatch = Regex("""a=ssrc:(\d+)""").find(sec)
            val codecMatch = Regex("""a=rtpmap:(\d+)\s+(\w+)/(\d+)""").find(sec)

            result.put(JSONObject().apply {
                put("mid", midMatch?.groupValues?.get(1) ?: JSONObject.NULL)
                put("kind", kind)
                put("direction", dirMatch?.groupValues?.get(1) ?: if (port == 0) "inactive" else "unknown")
                put("codec", codecMatch?.let { "${it.groupValues[2]}/${it.groupValues[3]}" } ?: JSONObject.NULL)
                put("pt", codecMatch?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                put("ssrc", ssrcMatch?.groupValues?.get(1)?.toLongOrNull() ?: JSONObject.NULL)
                put("port", port)
            })
        }
        return result
    }

    // ================================================================
    //  3초 주기 수집 + 전송
    // ================================================================

    private fun collectAndSend() {
        val telemetry = JSONObject().apply {
            put("section", "stats")
            put("tick", tick)
        }
        val ts = System.currentTimeMillis()
        pendingEvents = mutableListOf()

        val pubPc = pcProvider.getPublishPc()
        val subPc = pcProvider.getSubscribePc()

        // PC 없으면 PTT 진단만 보고
        if (pubPc == null && subPc == null) {
            telemetry.put("ptt", pcProvider.collectPttDiagnostics())
            attachEventsAndSend(telemetry)
            tick++
            return
        }

        // getStats()는 콜백 기반 — publish + subscribe 순차 수집 후 전송
        var remaining = 0
        if (pubPc != null) remaining++
        if (subPc != null) remaining++

        val lock = Object()
        var publishResult: JSONObject? = null
        var subscribeResult: JSONObject? = null
        var rawPubStats: Map<String, org.webrtc.RTCStats>? = null
        var rawSubStats: Map<String, org.webrtc.RTCStats>? = null

        pubPc?.getStats { report ->
            val stats = report.statsMap
            rawPubStats = stats
            detectPublishEvents(stats, ts)
            publishResult = collectPublishStats(stats)
            synchronized(lock) {
                remaining--
                if (remaining == 0) {
                    finalizeAndSend(telemetry, publishResult, subscribeResult, rawPubStats, rawSubStats)
                }
            }
        }

        subPc?.getStats { report ->
            val stats = report.statsMap
            rawSubStats = stats
            detectSubscribeEvents(stats, ts)
            subscribeResult = collectSubscribeStats(stats)
            synchronized(lock) {
                remaining--
                if (remaining == 0) {
                    finalizeAndSend(telemetry, publishResult, subscribeResult, rawPubStats, rawSubStats)
                }
            }
        }
    }

    private fun finalizeAndSend(
        telemetry: JSONObject,
        publishStats: JSONObject?,
        subscribeStats: JSONObject?,
        rawPubStats: Map<String, org.webrtc.RTCStats>?,
        rawSubStats: Map<String, org.webrtc.RTCStats>?,
    ) {
        publishStats?.let { telemetry.put("publish", it) }
        subscribeStats?.let { telemetry.put("subscribe", it) }

        // 구간 S-2: 코덱 상태 (Home _collectCodecStats 미러)
        telemetry.put("codecs", collectCodecStatsFromReport(rawPubStats, rawSubStats))

        telemetry.put("ptt", pcProvider.collectPttDiagnostics())

        pcProvider.getSubscribeTrackCounts()?.let { telemetry.put("subTracks", it) }

        attachEventsAndSend(telemetry)
        tick++
    }

    private fun attachEventsAndSend(telemetry: JSONObject) {
        if (pendingEvents.isNotEmpty()) {
            val evArr = JSONArray()
            for (ev in pendingEvents) evArr.put(ev)
            telemetry.put("events", evArr)
        }
        send(telemetry)
    }

    // ================================================================
    //  구간 A: publish outbound-rtp + candidate-pair
    // ================================================================

    private fun collectPublishStats(stats: Map<String, org.webrtc.RTCStats>): JSONObject {
        val result = JSONObject()
        val outbound = JSONArray()
        var network: JSONObject? = null

        for ((_, r) in stats) {
            if (r.type == "outbound-rtp") {
                val ssrc = (r.members["ssrc"] as? Number)?.toLong() ?: continue
                val kind = r.members["kind"] as? String ?: continue

                // delta bytesSent → bitrate
                val bytesSent = (r.members["bytesSent"] as? Number)?.toLong() ?: 0L
                val prevKey = "pub_$ssrc"
                val prevBytes = prevPub[prevKey] ?: 0L
                val deltaBytes = maxOf(0L, bytesSent - prevBytes)
                val bitrate = (deltaBytes * 8 / 3).toInt()
                prevPub[prevKey] = bytesSent

                // delta packetsSent
                val packetsSent = (r.members["packetsSent"] as? Number)?.toLong() ?: 0L
                val prevPkts = prevPub["pkt_$ssrc"] ?: 0L
                val deltaPkts = maxOf(0L, packetsSent - prevPkts)
                prevPub["pkt_$ssrc"] = packetsSent

                // delta retransmittedPacketsSent
                val rtxSent = (r.members["retransmittedPacketsSent"] as? Number)?.toLong() ?: 0L
                val prevRtx = prevPub["rtx_$ssrc"] ?: 0L
                val deltaRtx = maxOf(0L, rtxSent - prevRtx)
                prevPub["rtx_$ssrc"] = rtxSent

                // delta nackCount
                val nackCount = (r.members["nackCount"] as? Number)?.toLong() ?: 0L
                val prevNack = prevPub["nack_$ssrc"] ?: 0L
                val deltaNack = maxOf(0L, nackCount - prevNack)
                prevPub["nack_$ssrc"] = nackCount

                // qualityLimitationDurations delta
                @Suppress("UNCHECKED_CAST")
                val curQld = r.members["qualityLimitationDurations"] as? Map<String, Number>
                var qldDelta: JSONObject? = null
                if (curQld != null) {
                    val qldKey = "qld_$ssrc"
                    val prevQldMap = prevQld[qldKey]
                    val curQldDouble = curQld.mapValues { it.value.toDouble() }
                    if (prevQldMap != null) {
                        qldDelta = JSONObject()
                        for (reason in listOf("none", "bandwidth", "cpu", "other")) {
                            val cur = curQldDouble[reason] ?: 0.0
                            val prev = prevQldMap[reason] ?: 0.0
                            qldDelta.put(reason, Math.round((cur - prev) * 1000) / 1000.0)
                        }
                    }
                    prevQld[qldKey] = curQldDouble
                }

                outbound.put(JSONObject().apply {
                    put("kind", kind)
                    put("ssrc", ssrc)
                    put("packetsSent", packetsSent)
                    put("packetsSentDelta", deltaPkts)
                    put("bytesSent", bytesSent)
                    put("bitrate", bitrate)
                    put("nackCount", nackCount)
                    put("nackCountDelta", deltaNack)
                    put("pliCount", (r.members["pliCount"] as? Number)?.toLong() ?: 0L)
                    put("targetBitrate", numOrNull(r.members["targetBitrate"]))
                    put("retransmittedPacketsSent", rtxSent)
                    put("retransmittedPacketsSentDelta", deltaRtx)
                    put("framesEncoded", numOrNull(r.members["framesEncoded"]))
                    put("framesSent", numOrNull(r.members["framesSent"]))
                    put("hugeFramesSent", numOrNull(r.members["hugeFramesSent"]))
                    put("keyFramesEncoded", numOrNull(r.members["keyFramesEncoded"]))
                    put("framesPerSecond", numOrNull(r.members["framesPerSecond"]))
                    put("totalEncodeTime", numOrNull(r.members["totalEncodeTime"]))
                    put("qualityLimitationReason", r.members["qualityLimitationReason"] ?: JSONObject.NULL)
                    put("qualityLimitationDurations", qldDelta ?: JSONObject.NULL)
                    put("encoderImplementation", r.members["encoderImplementation"] ?: JSONObject.NULL)
                    put("powerEfficientEncoder", r.members["powerEfficientEncoder"] ?: JSONObject.NULL)
                })
            }

            if (r.type == "candidate-pair") {
                val state = r.members["state"] as? String
                if (state == "succeeded") {
                    val rtt = (r.members["currentRoundTripTime"] as? Number)?.toDouble()
                    network = JSONObject().apply {
                        put("rtt", if (rtt != null) Math.round(rtt * 1000) else JSONObject.NULL)
                        put("availableBitrate", numOrNull(r.members["availableOutgoingBitrate"]))
                    }
                }
            }
        }

        result.put("outbound", outbound)
        result.put("network", network ?: JSONObject.NULL)
        return result
    }

    // ================================================================
    //  구간 C: subscribe inbound-rtp
    // ================================================================

    private fun collectSubscribeStats(stats: Map<String, org.webrtc.RTCStats>): JSONObject {
        val result = JSONObject()
        val inbound = JSONArray()
        var network: JSONObject? = null

        for ((_, r) in stats) {
            if (r.type == "inbound-rtp") {
                val ssrc = (r.members["ssrc"] as? Number)?.toLong() ?: continue
                val kind = r.members["kind"] as? String ?: continue
                val sourceUser = pcProvider.resolveSourceUser(ssrc)

                // delta bytesReceived → bitrate
                val bytesReceived = (r.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                val prevKey = "sub_$ssrc"
                val prevBytes = prevSub[prevKey] ?: 0L
                val deltaBytes = maxOf(0L, bytesReceived - prevBytes)
                val bitrate = (deltaBytes * 8 / 3).toInt()
                prevSub[prevKey] = bytesReceived

                // delta packetsReceived
                val packetsReceived = (r.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                val prevRecv = prevSub["recv_$ssrc"] ?: 0L
                val deltaRecv = maxOf(0L, packetsReceived - prevRecv)
                prevSub["recv_$ssrc"] = packetsReceived

                // delta packetsLost
                val packetsLost = (r.members["packetsLost"] as? Number)?.toLong() ?: 0L
                val prevLost = prevSub["lost_$ssrc"] ?: 0L
                val deltaLost = maxOf(0L, packetsLost - prevLost)
                prevSub["lost_$ssrc"] = packetsLost

                // delta nackCount
                val nackCount = (r.members["nackCount"] as? Number)?.toLong() ?: 0L
                val prevNack = prevSub["nack_$ssrc"] ?: 0L
                val deltaNack = maxOf(0L, nackCount - prevNack)
                prevSub["nack_$ssrc"] = nackCount

                // delta 손실률
                val deltaTotal = deltaRecv + deltaLost
                val deltaLossRate = if (deltaTotal > 0)
                    Math.round(deltaLost.toDouble() / deltaTotal * 1000) / 10.0
                else 0.0

                // jitterBuffer delta
                val jbDelay = (r.members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                val jbEmitted = (r.members["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0L
                val jbKey = "jb_$ssrc"
                val prevJbEntry = prevJb[jbKey]
                var jbDelayMs: Any = JSONObject.NULL
                if (prevJbEntry != null && jbEmitted > prevJbEntry.emitted) {
                    val dd = jbDelay - prevJbEntry.delay
                    val de = jbEmitted - prevJbEntry.emitted
                    jbDelayMs = Math.round(dd / de * 1000)
                }
                prevJb[jbKey] = JbPrev(jbDelay, jbEmitted)

                inbound.put(JSONObject().apply {
                    put("kind", kind)
                    put("ssrc", ssrc)
                    put("sourceUser", sourceUser ?: JSONObject.NULL)
                    put("packetsReceived", packetsReceived)
                    put("packetsReceivedDelta", deltaRecv)
                    put("packetsLost", packetsLost)
                    put("packetsLostDelta", deltaLost)
                    put("lossRateDelta", deltaLossRate)
                    put("bytesReceived", bytesReceived)
                    put("bitrate", bitrate)
                    put("jitter", numOrNull(r.members["jitter"]))
                    put("nackCount", nackCount)
                    put("nackCountDelta", deltaNack)
                    put("jitterBufferDelay", jbDelayMs)
                    put("jitterBufferEmittedCount", jbEmitted)
                    put("framesDecoded", numOrNull(r.members["framesDecoded"]))
                    put("keyFramesDecoded", numOrNull(r.members["keyFramesDecoded"]))
                    put("framesDropped", numOrNull(r.members["framesDropped"]))
                    put("framesPerSecond", numOrNull(r.members["framesPerSecond"]))
                    put("freezeCount", (r.members["freezeCount"] as? Number)?.toLong() ?: 0L)
                    put("totalFreezesDuration", (r.members["totalFreezesDuration"] as? Number)?.toDouble() ?: 0.0)
                    put("concealedSamples", (r.members["concealedSamples"] as? Number)?.toLong() ?: 0L)
                    put("decoderImplementation", r.members["decoderImplementation"] ?: JSONObject.NULL)
                })
            }

            if (r.type == "candidate-pair") {
                val state = r.members["state"] as? String
                if (state == "succeeded") {
                    val rtt = (r.members["currentRoundTripTime"] as? Number)?.toDouble()
                    network = JSONObject().apply {
                        put("rtt", if (rtt != null) Math.round(rtt * 1000) else JSONObject.NULL)
                    }
                }
            }
        }

        result.put("inbound", inbound)
        result.put("network", network ?: JSONObject.NULL)
        return result
    }

    // ================================================================
    //  구간 S-2: 코덱 상태 (Home _collectCodecStats 미러)
    // ================================================================

    /**
     * 3초 주기 getStats() 콜백에서 수집된 raw stats를 재활용하여 코덱 정보 추출.
     * Home의 codecs[] 배열과 동일한 스키마로 어드민에 전송.
     */
    private fun collectCodecStatsFromReport(
        pubStats: Map<String, org.webrtc.RTCStats>?,
        subStats: Map<String, org.webrtc.RTCStats>?,
    ): JSONArray {
        val codecs = JSONArray()

        pubStats?.values?.forEach { r ->
            if (r.type == "outbound-rtp") {
                codecs.put(JSONObject().apply {
                    put("pc", "pub")
                    put("kind", r.members["kind"] as? String ?: "unknown")
                    put("encoderImpl", r.members["encoderImplementation"] ?: JSONObject.NULL)
                    put("powerEfficient", r.members["powerEfficientEncoder"] ?: JSONObject.NULL)
                    put("qualityLimitReason", r.members["qualityLimitationReason"] ?: JSONObject.NULL)
                    put("fps", numOrNull(r.members["framesPerSecond"]))
                    put("framesEncoded", numOrNull(r.members["framesEncoded"]))
                    put("keyFramesEncoded", numOrNull(r.members["keyFramesEncoded"]))
                })
            }
        }

        subStats?.values?.forEach { r ->
            if (r.type == "inbound-rtp") {
                codecs.put(JSONObject().apply {
                    put("pc", "sub")
                    put("kind", r.members["kind"] as? String ?: "unknown")
                    put("ssrc", (r.members["ssrc"] as? Number)?.toLong() ?: JSONObject.NULL)
                    put("decoderImpl", r.members["decoderImplementation"] ?: JSONObject.NULL)
                    put("fps", numOrNull(r.members["framesPerSecond"]))
                    put("framesDecoded", numOrNull(r.members["framesDecoded"]))
                    put("keyFramesDecoded", numOrNull(r.members["keyFramesDecoded"]))
                })
            }
        }

        return codecs
    }

    // ================================================================
    //  이벤트 타임라인 — 상태 전이 감지
    // ================================================================

    private fun pushEvent(event: JSONObject, ts: Long) {
        event.put("ts", ts)
        eventLog.add(event)
        while (eventLog.size > EVENT_LOG_MAX) eventLog.removeAt(0)
        pendingEvents.add(event)
    }

    /** publish outbound-rtp 상태 전이 감지 (6종) */
    private fun detectPublishEvents(stats: Map<String, org.webrtc.RTCStats>, ts: Long) {
        for ((_, r) in stats) {
            if (r.type != "outbound-rtp") continue
            val ssrc = (r.members["ssrc"] as? Number)?.toLong() ?: continue
            val kind = r.members["kind"] as? String ?: continue
            val key = "pub_$ssrc"
            val prev = watchState[key]

            // 1) qualityLimitationReason 변화
            val curReason = r.members["qualityLimitationReason"] as? String ?: "none"
            val prevReason = prev?.optString("qualityLimitReason", "")
            if (!prevReason.isNullOrEmpty() && curReason != prevReason) {
                pushEvent(JSONObject().apply {
                    put("type", "quality_limit_change")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("from", prevReason); put("to", curReason)
                }, ts)
            }

            // 2) encoderImplementation 변화 (HW↔SW fallback)
            val curImpl = r.members["encoderImplementation"] as? String
            val prevImpl = prev?.optString("encoderImpl", "")
            if (!prevImpl.isNullOrEmpty() && curImpl != null && curImpl != prevImpl) {
                pushEvent(JSONObject().apply {
                    put("type", "encoder_impl_change")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("from", prevImpl); put("to", curImpl)
                }, ts)
            }

            // 3) PLI 급증 (3초에 3개 이상)
            val curPli = (r.members["pliCount"] as? Number)?.toLong() ?: 0L
            val prevPli = prev?.optLong("pliCount", 0L) ?: 0L
            val deltaPli = curPli - prevPli
            if (deltaPli >= 3) {
                pushEvent(JSONObject().apply {
                    put("type", "pli_burst")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("count", deltaPli)
                }, ts)
            }

            // 4) NACK 급증 (3초에 10개 이상)
            val curNack = (r.members["nackCount"] as? Number)?.toLong() ?: 0L
            val prevNack = prev?.optLong("nackCount", 0L) ?: 0L
            val deltaNack = curNack - prevNack
            if (deltaNack >= 10) {
                pushEvent(JSONObject().apply {
                    put("type", "nack_burst")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("count", deltaNack)
                }, ts)
            }

            // 5) targetBitrate 급락 (이전 대비 50% 이하)
            val curTarget = (r.members["targetBitrate"] as? Number)?.toLong() ?: 0L
            val prevTarget = prev?.optLong("targetBitrate", 0L) ?: 0L
            if (prevTarget > 0 && curTarget > 0 && curTarget < prevTarget / 2) {
                pushEvent(JSONObject().apply {
                    put("type", "bitrate_drop")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("from", prevTarget); put("to", curTarget)
                }, ts)
            }

            // 6) FPS 양수→0 (인코딩/전송 중단)
            val curFps = (r.members["framesPerSecond"] as? Number)?.toInt() ?: 0
            val prevFps = prev?.optInt("fps", 0) ?: 0
            if (kind == "video" && prevFps > 0 && curFps == 0) {
                pushEvent(JSONObject().apply {
                    put("type", "fps_zero")
                    put("pc", "pub"); put("kind", kind); put("ssrc", ssrc)
                    put("prevFps", prevFps)
                }, ts)
            }

            watchState[key] = JSONObject().apply {
                put("qualityLimitReason", curReason)
                put("encoderImpl", curImpl ?: "")
                put("pliCount", curPli)
                put("nackCount", curNack)
                put("targetBitrate", curTarget)
                put("fps", curFps)
            }
        }
    }

    /** subscribe inbound-rtp 상태 전이 감지 (5종) */
    private fun detectSubscribeEvents(stats: Map<String, org.webrtc.RTCStats>, ts: Long) {
        for ((_, r) in stats) {
            if (r.type != "inbound-rtp") continue
            val ssrc = (r.members["ssrc"] as? Number)?.toLong() ?: continue
            val kind = r.members["kind"] as? String ?: continue
            val key = "sub_$ssrc"
            val prev = watchState[key]

            // 1) freeze 발생 (누적 증가)
            val curFreeze = (r.members["freezeCount"] as? Number)?.toLong() ?: 0L
            val prevFreeze = prev?.optLong("freezeCount", 0L) ?: 0L
            if (curFreeze > prevFreeze) {
                pushEvent(JSONObject().apply {
                    put("type", "video_freeze")
                    put("pc", "sub"); put("kind", kind); put("ssrc", ssrc)
                    put("count", curFreeze - prevFreeze)
                    put("totalDuration", (r.members["totalFreezesDuration"] as? Number)?.toDouble() ?: 0.0)
                }, ts)
            }

            // 2) 손실 급증 (3초에 20패킷 이상)
            val curLost = (r.members["packetsLost"] as? Number)?.toLong() ?: 0L
            val prevLost = prev?.optLong("packetsLost", 0L) ?: 0L
            val deltaLost = curLost - prevLost
            if (deltaLost >= 20) {
                pushEvent(JSONObject().apply {
                    put("type", "loss_burst")
                    put("pc", "sub"); put("kind", kind); put("ssrc", ssrc)
                    put("count", deltaLost)
                }, ts)
            }

            // 3) framesDropped 급증 (3초에 5 이상)
            val curDropped = (r.members["framesDropped"] as? Number)?.toLong() ?: 0L
            val prevDropped = prev?.optLong("framesDropped", 0L) ?: 0L
            val deltaDropped = curDropped - prevDropped
            if (deltaDropped >= 5) {
                pushEvent(JSONObject().apply {
                    put("type", "frames_dropped_burst")
                    put("pc", "sub"); put("kind", kind); put("ssrc", ssrc)
                    put("count", deltaDropped)
                }, ts)
            }

            // 4) decoderImplementation 변화
            val curDecImpl = r.members["decoderImplementation"] as? String
            val prevDecImpl = prev?.optString("decoderImpl", "")
            if (!prevDecImpl.isNullOrEmpty() && curDecImpl != null && curDecImpl != prevDecImpl) {
                pushEvent(JSONObject().apply {
                    put("type", "decoder_impl_change")
                    put("pc", "sub"); put("kind", kind); put("ssrc", ssrc)
                    put("from", prevDecImpl); put("to", curDecImpl)
                }, ts)
            }

            // 5) FPS 양수→0 (수신 중단)
            val curFps = (r.members["framesPerSecond"] as? Number)?.toInt() ?: 0
            val prevFps = prev?.optInt("fps", 0) ?: 0
            if (kind == "video" && prevFps > 0 && curFps == 0) {
                pushEvent(JSONObject().apply {
                    put("type", "fps_zero")
                    put("pc", "sub"); put("kind", kind); put("ssrc", ssrc)
                    put("prevFps", prevFps)
                }, ts)
            }

            watchState[key] = JSONObject().apply {
                put("freezeCount", curFreeze)
                put("packetsLost", curLost)
                put("framesDropped", curDropped)
                put("decoderImpl", curDecImpl ?: "")
                put("fps", curFps)
            }
        }
    }

    // ================================================================
    //  전송 헬퍼
    // ================================================================

    private fun send(data: JSONObject) {
        val pid = signalClient.nextPid()
        signalClient.send(Packet.request(Opcode.TELEMETRY, pid, data))
    }

    /** 전체 이벤트 로그 반환 (디버그/스냅샷용) */
    fun getEventLog(): List<JSONObject> = eventLog.toList()

    // ================================================================
    //  유틸 — RTCStats.members 값 안전 변환
    // ================================================================

    /**
     * RTCStats.members 값을 JSONObject에 넣을 수 있는 Number 또는 NULL로 변환.
     * Android libwebrtc는 필드를 Integer/Long/Double/BigInteger 등 다양한 타입으로
     * 내려주므로, as? Long / as? Double 직접 캐스팅은 타입 불일치로 null이 됨.
     */
    private fun numOrNull(v: Any?): Any {
        return when (v) {
            is Number -> v
            else -> JSONObject.NULL
        }
    }
}

// ================================================================
//  내부 데이터 타입
// ================================================================

private data class JbPrev(val delay: Double, val emitted: Long)

// ================================================================
//  PeerConnectionProvider — OxLensClient에서 구현
// ================================================================

/**
 * Telemetry가 PeerConnection과 SDK 상태에 접근하기 위한 인터페이스.
 * OxLensClient가 구현하여 Telemetry 생성자에 전달.
 */
interface PeerConnectionProvider {
    fun getPublishPc(): PeerConnection?
    fun getSubscribePc(): PeerConnection?

    /** subscribe SSRC → 원본 publisher userId 매핑 */
    fun resolveSourceUser(ssrc: Long): String?

    /** PTT 진단 정보 수집 */
    fun collectPttDiagnostics(): JSONObject

    /** subscribe 트랙 카운트 (total, active, inactive) */
    fun getSubscribeTrackCounts(): JSONObject?
}
