// author: kodeholic (powered by Claude)
package com.oxlens.sdk

/**
 * OxLens SDK 이벤트 리스너 인터페이스.
 *
 * 콜백은 OkHttp 워커 스레드에서 호출되므로,
 * UI 업데이트 시 반드시 메인 스레드로 전환할 것.
 *
 * ## 주의
 * - 콜백 내에서 오래 걸리는 작업 금지 (이벤트 펌프 블로킹)
 */
interface OxLensEventListener {

    /** 서버 연결 완료 (HELLO 수신) */
    fun onConnected() {}

    /** IDENTIFY 성공 — 이 시점부터 명령 전송 가능 */
    fun onIdentified() {}

    /** 방 생성 완료 */
    fun onRoomCreated(roomId: String, name: String, mode: String) {}

    /** 방 목록 수신 (JSON 문자열) */
    fun onRoomList(roomsJson: String) {}

    /** 방 입장 완료 */
    fun onRoomJoined(roomId: String, mode: String) {}

    /** 방 퇴장 완료 */
    fun onRoomLeft(roomId: String) {}

    /** 다른 참가자 트랙 변경 */
    fun onTracksUpdated(action: String, count: Int) {}

    /** PTT 발화권 획득 (내가 요청한 것) */
    fun onFloorGranted(roomId: String) {}

    /** PTT 발화권 거부 */
    fun onFloorDenied(reason: String) {}

    /** 다른 사용자가 발화권 획득 */
    fun onFloorTaken(roomId: String, userId: String) {}

    /** 발화권 해제 (방 idle 상태) */
    fun onFloorIdle(roomId: String) {}

    /** 발화권 강제 회수 */
    fun onFloorRevoke(roomId: String) {}

    /** 발화권 해제 완료 (내 요청에 대한 응답) */
    fun onFloorReleased() {}

    /** Mute 상태 변경 (kind: "audio"|"video", muted: true/false, phase: "soft"|"hard"|"ptt") */
    fun onMuteChanged(kind: String, muted: Boolean, phase: String) {}

    /** 카메라 전환 완료 */
    fun onCameraSwitched(facingMode: String) {}

    /** 서버 에러 */
    fun onError(code: Int, message: String) {}

    /** 연결 해제 */
    fun onDisconnected(reason: String) {}
}
