// author: kodeholic (powered by Claude)
package com.oxlens.sdk

/**
 * OxLens SDK 공용 상수 — home core/constants.js 미러.
 *
 * 여러 모듈(signaling, media, ptt)이 공유하는 상수.
 * 앱에서도 직접 참조 가능.
 */
object Constants {

    const val SDK_VERSION = "0.1.0"

    // ============================================================
    //  Floor Participant 상태 (MCPTT/MBCP §6.2.4 기반)
    // ============================================================
    object FLOOR {
        const val IDLE = "idle"              // 아무도 안 말함, PTT 가능
        const val REQUESTING = "requesting"  // PTT 눌림, 서버 응답 대기
        const val TALKING = "talking"        // 내가 발화 중
        const val LISTENING = "listening"    // 타인 발화 중
    }

    // ============================================================
    //  Mute 3-state
    // ============================================================
    object MUTE {
        const val UNMUTED = "unmuted"
        const val SOFT_MUTED = "soft_muted"
        const val HARD_MUTED = "hard_muted"
    }

    // ============================================================
    //  Connection 상태
    // ============================================================
    object CONN {
        const val DISCONNECTED = "disconnected"
        const val CONNECTING = "connecting"
        const val CONNECTED = "connected"
        const val IDENTIFIED = "identified"
    }

    // ============================================================
    //  Timing
    // ============================================================
    const val FLOOR_PING_MS = 2000L
    const val MUTE_ESCALATION_MS = 5000L
}
