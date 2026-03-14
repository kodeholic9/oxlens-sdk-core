// author: kodeholic (powered by Claude)
package com.oxlens.sdk.ptt

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Floor FSM — PTT 발화권 상태 머신.
 *
 * ## 상태 전이
 * ```
 * IDLE ──requestFloor()──→ REQUESTING
 *   ↑                          │
 *   │                   onGranted() / onDenied()
 *   │                          │         │
 *   │                     GRANTED    ──→ IDLE
 *   │                          │
 *   │                   releaseFloor()
 *   │                          │
 *   │                     RELEASING
 *   │                          │
 *   │                   onReleased()
 *   └──────────────────────────┘
 * ```
 *
 * ## Floor Ping
 * GRANTED 상태에서 FLOOR_PING_INTERVAL_MS 마다 자동 전송.
 * 서버가 FLOOR_PING_TIMEOUT_MS 내에 ping을 못 받으면 Revoke.
 *
 * ## 스레드 안전성
 * 모든 상태 전이는 synchronized. ping 타이머는 메인 Handler 사용.
 */
class FloorFsm(private val listener: FloorFsmListener) {

    companion object {
        private const val TAG = "FloorFsm"

        /** 서버 config 미러: Floor Ping 전송 주기 (ms) */
        const val FLOOR_PING_INTERVAL_MS = 2000L
    }

    enum class State {
        /** 발화자 없음 또는 내가 아님 */
        IDLE,
        /** 발화권 요청 중 (응답 대기) */
        REQUESTING,
        /** 발화권 획득 — 마이크 활성, ping 전송 중 (home: FLOOR.TALKING) */
        TALKING,
        /** 타인 발화 중 (home: FLOOR.LISTENING) */
        LISTENING,
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 현재 발화자 user_id (FLOOR_TAKEN으로 수신) */
    @Volatile
    var currentSpeaker: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null

    // ================================================================
    //  Actions (앱 → FSM)
    // ================================================================

    /** PTT 버튼 누름 — 발화권 요청 */
    @Synchronized
    fun requestFloor(): Boolean {
        if (state != State.IDLE) {
            Log.w(TAG, "requestFloor ignored: state=$state")
            return false
        }
        state = State.REQUESTING
        Log.i(TAG, "state → REQUESTING")
        listener.onSendFloorRequest()
        return true
    }

    /** PTT 버튼 뗌 — 발화권 해제 */
    @Synchronized
    fun releaseFloor(): Boolean {
        if (state != State.TALKING) {
            Log.w(TAG, "releaseFloor ignored: state=$state")
            return false
        }
        stopPingTimer()
        listener.onMicMute(true)
        listener.onSendFloorRelease()
        // WS 응답 즉시이므로 RELEASING 상태 생략 (home 관례)
        state = State.IDLE
        Log.i(TAG, "state → IDLE (release sent)")
        return true
    }

    // ================================================================
    //  Events (서버 → FSM)
    // ================================================================

    /** FLOOR_REQUEST 응답: 허가됨 */
    @Synchronized
    fun onGranted() {
        if (state != State.REQUESTING && state != State.TALKING) {
            Log.w(TAG, "onGranted ignored: state=$state")
            return
        }
        state = State.TALKING
        listener.onMicMute(false)
        startPingTimer()
        Log.i(TAG, "state → TALKING")
    }

    /** FLOOR_REQUEST 응답: 거부됨 */
    @Synchronized
    fun onDenied(reason: String) {
        if (state != State.REQUESTING) {
            Log.w(TAG, "onDenied ignored: state=$state")
            return
        }
        state = State.IDLE
        Log.i(TAG, "state → IDLE (denied: $reason)")
    }

    /** FLOOR_RELEASE 응답: 해제 완료 */
    @Synchronized
    fun onReleased() {
        // RELEASING 또는 GRANTED(서버가 먼저 idle 보낸 경우) 모두 수용
        stopPingTimer()
        state = State.IDLE
        currentSpeaker = null
        Log.i(TAG, "state → IDLE (released)")
    }

    /** FLOOR_TAKEN 이벤트: 누군가 발화권 획득 (브로드캐스트) */
    @Synchronized
    fun onFloorTaken(userId: String) {
        currentSpeaker = userId
        // 타인이 발화권을 잡은 경우 → LISTENING
        if (state == State.IDLE) {
            state = State.LISTENING
            Log.i(TAG, "state → LISTENING (speaker=$userId)")
        } else {
            Log.d(TAG, "floor taken by $userId (my state=$state)")
        }
    }

    /** FLOOR_IDLE 이벤트: 발화권 해제됨 (브로드캐스트) */
    @Synchronized
    fun onFloorIdle() {
        currentSpeaker = null
        if (state == State.TALKING) {
            stopPingTimer()
            listener.onMicMute(true)
            state = State.IDLE
            Log.i(TAG, "state → IDLE (floor idle)")
        } else if (state == State.LISTENING) {
            state = State.IDLE
            Log.i(TAG, "state → IDLE (from listening)")
        }
    }

    /** FLOOR_REVOKE 이벤트: 서버가 강제 회수 */
    @Synchronized
    fun onRevoked() {
        stopPingTimer()
        listener.onMicMute(true)
        state = State.IDLE
        currentSpeaker = null
        Log.i(TAG, "state → IDLE (revoked)")
    }

    /** 연결 해제 시 리셋 */
    @Synchronized
    fun reset() {
        stopPingTimer()
        state = State.IDLE
        currentSpeaker = null
    }

    // ================================================================
    //  Ping Timer
    // ================================================================

    private fun startPingTimer() {
        stopPingTimer()
        val runnable = object : Runnable {
            override fun run() {
                if (state == State.TALKING) {
                    listener.onSendFloorPing()
                    handler.postDelayed(this, FLOOR_PING_INTERVAL_MS)
                }
            }
        }
        pingRunnable = runnable
        handler.postDelayed(runnable, FLOOR_PING_INTERVAL_MS)
    }

    private fun stopPingTimer() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = null
    }
}

// ================================================================
//  Listener
// ================================================================

/**
 * FloorFsm이 OxLensClient에게 요청하는 콜백.
 */
interface FloorFsmListener {
    /** 시그널링: FLOOR_REQUEST 전송 */
    fun onSendFloorRequest()
    /** 시그널링: FLOOR_RELEASE 전송 */
    fun onSendFloorRelease()
    /** 시그널링: FLOOR_PING 전송 */
    fun onSendFloorPing()
    /** 마이크 mute/unmute */
    fun onMicMute(muted: Boolean)
}
