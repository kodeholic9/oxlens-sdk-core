// author: kodeholic (powered by Claude)
// OxLens 데모 ViewModel — SDK 이벤트 → Compose StateFlow 바인딩
// Vue의 Vuex store + computed처럼, SDK 콜백이 state를 바꾸면 UI가 자동 반응
package com.oxlens.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.oxlens.sdk.OxLensClient
import com.oxlens.sdk.OxLensEventListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack

// ================================================================
//  UI 상태 모델
// ================================================================

/** WS 연결 상태 (뱃지용) */
enum class WsState { OFF, CONNECTING, READY, ROOM }

/** 방 모드 */
enum class RoomMode { CONFERENCE, PTT }

/** PTT 발화 상태 */
enum class PttState { IDLE, REQUESTING, TALKING }

/** 방 목록 항목 */
data class RoomInfo(
    val roomId: String,
    val name: String,
    val mode: String,
    val participants: Int,
    val capacity: Int,
)

/** 참가자 정보 */
data class Participant(
    val userId: String,
    val videoTrack: VideoTrack? = null,
)

/** 화질 프리셋 (Home MEDIA_PRESETS 미러) */
enum class MediaPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxBitrateBps: Int,
) {
    ECO   ("절약 (480p/15fps)",     640,  480, 15,   300_000),
    NORMAL("일반 (480p/24fps)",     640,  480, 24,   500_000),
    HD    ("HD (720p/24fps)",        1280, 720, 24, 1_500_000),
    HD_PLUS("HD+ (720p/30fps)",      1280, 720, 30, 2_000_000),
    FHD   ("FHD (1080p/30fps)",      1920, 1080, 30, 2_500_000),
}

/** 서버 주소 프리셋 (Home 클라이언트와 동기화) */
data class ServerPreset(val url: String, val label: String)

val SERVER_PRESETS = listOf(
    ServerPreset("ws://127.0.0.1:1974/ws", "localhost (ws)"),
    ServerPreset("ws://192.168.0.18:1974/ws", "개발PC (ws)"),
    ServerPreset("ws://192.168.0.29:1974/ws", "RPi local (ws)"),
    ServerPreset("wss://www.oxlens.com/ws", "oxlens.com (wss)"),
)

/** 전체 UI 상태 — Compose가 관찰하는 단일 진실 소스 */
data class DemoUiState(
    val wsState: WsState = WsState.OFF,
    val statusText: String = "서버에 연결하려면 ▶ 버튼을 누르세요",
    val userId: String = "",   // Uxxx — 연결 시 IDENTIFY에 사용
    val localUserId: String = "",
    val selectedServerIndex: Int = 3,  // 기본: oxlens.com
    val isInRoom: Boolean = false,
    val roomMode: RoomMode = RoomMode.CONFERENCE,
    val isMicMuted: Boolean = false,
    val isVideoMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isControlLocked: Boolean = false,
    val isPttLocked: Boolean = false,   // 긴급발언 토글 (PTT Lock)
    val roomList: List<RoomInfo> = emptyList(),
    val selectedRoomId: String = "",
    val participants: List<Participant> = emptyList(),
    val localVideoTrack: VideoTrack? = null,
    val pttState: PttState = PttState.IDLE,
    val pttSpeaker: String = "",
    val pttSpeakerTrack: VideoTrack? = null,
    val cameraFacing: String = "front",
    // 설정
    val mediaPreset: MediaPreset = MediaPreset.HD,
    val audioDeviceNames: List<String> = listOf("기본 장치"),
    val selectedAudioDevice: String = "기본 장치",
)

// ================================================================
//  ViewModel
// ================================================================

class DemoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OxLensDemo"
        private const val TOKEN = "demo-token"

        /** Home 미러: Uxxx (숫자 3자리 랜덤) */
        private fun generateUserId(): String {
            val num = (100..999).random()
            return "U$num"
        }
    }

    // 공유 EGL 컨텍스트 — 모든 SurfaceViewRenderer가 사용
    val eglBase: EglBase = EglBase.create()

    private val _ui = MutableStateFlow(DemoUiState(userId = generateUserId()))
    val ui: StateFlow<DemoUiState> = _ui.asStateFlow()

    // Toast 이벤트 (fire-and-forget, UI에서 LaunchedEffect로 수집)
    private val _toastEvent = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 10)
    val toastEvent: SharedFlow<Pair<String, String>> = _toastEvent.asSharedFlow()

    private fun toast(type: String, text: String) {
        _toastEvent.tryEmit(type to text)
    }

    private var client: OxLensClient? = null

    // 리모트 비디오 트랙 (userId → VideoTrack)
    private val remoteVideoTracks = mutableMapOf<String, VideoTrack>()

    // ================================================================
    //  공개 액션 (UI → ViewModel)
    // ================================================================

    fun selectServer(index: Int) {
        if (index in SERVER_PRESETS.indices) {
            update { copy(selectedServerIndex = index) }
        }
    }

    /** 사용자 ID 변경 (ConnectionPanel TextField) */
    fun updateUserId(id: String) {
        update { copy(userId = id) }
    }

    fun connect() {
        if (client != null) return
        val uid = _ui.value.userId.ifBlank { generateUserId().also { update { copy(userId = it) } } }
        val url = SERVER_PRESETS.getOrNull(_ui.value.selectedServerIndex)?.url
            ?: SERVER_PRESETS[1].url
        update { copy(wsState = WsState.CONNECTING, statusText = "서버 연결 중...", localUserId = uid) }

        client = OxLensClient(
            context = getApplication(),
            serverUrl = url,
            token = TOKEN,
            userId = uid,
            listener = sdkListener,
        ).also {
            it.eglContext = eglBase.eglBaseContext
        }
        client!!.connect()
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        remoteVideoTracks.clear()
        val keepUserId = _ui.value.userId
        val keepPreset = _ui.value.mediaPreset
        update {
            DemoUiState(userId = keepUserId, mediaPreset = keepPreset)
        }
    }

    fun selectMode(mode: RoomMode) {
        update { copy(roomMode = mode) }
    }

    fun selectRoom(roomId: String) {
        update { copy(selectedRoomId = roomId) }
    }

    /** 선택된 방에 입장 */
    fun joinSelectedRoom() {
        val roomId = _ui.value.selectedRoomId
        if (roomId.isBlank()) return
        // 화질 프리셋 → client 프로퍼티 세팅 (다음 입장 시 적용)
        val preset = _ui.value.mediaPreset
        client?.apply {
            captureWidth = preset.width
            captureHeight = preset.height
            captureFps = preset.fps
            maxBitrateBps = preset.maxBitrateBps
        }
        // Home 미러: join:phase → media → signaling 순서
        toast("media", "카메라/마이크 준비 중…")
        update { copy(statusText = "입장 중...") }
        client?.joinRoom(roomId)
    }

    /** 방 퇴장 */
    fun leaveRoom() {
        client?.leaveRoom()
    }

    fun toggleMic() {
        if (_ui.value.isControlLocked) return
        client?.toggleMute("audio")
    }
    fun toggleVideo() {
        if (_ui.value.isControlLocked) return
        client?.toggleMute("video")
    }
    fun switchCamera() {
        if (_ui.value.isControlLocked) return
        client?.switchCamera()
    }
    fun toggleSpeaker() {
        if (_ui.value.isControlLocked) return
        val newState = !_ui.value.isSpeakerOn
        update { copy(isSpeakerOn = newState) }
        if (newState) client?.selectSpeaker() else client?.selectEarpiece()
    }

    /** 오디오 장치 선택 (설정 시트에서 호출, 즉시 적용) */
    fun selectAudioDevice(name: String) {
        client?.selectAudioDevice(name)
    }
    /** 화질 프리셋 변경 (다음 입장 시 적용) */
    fun selectMediaPreset(preset: MediaPreset) {
        update { copy(mediaPreset = preset) }
        Log.i(TAG, "화질 변경: ${preset.label} (${preset.width}x${preset.height}@${preset.fps}, ${preset.maxBitrateBps / 1000}kbps)")
    }

    fun toggleControlLock() {
        update { copy(isControlLocked = !isControlLocked) }
    }
    fun togglePttLock() {
        if (_ui.value.isControlLocked) return
        val newLocked = !_ui.value.isPttLocked
        update { copy(isPttLocked = newLocked) }
        // 긴급발언 ON → 즐시 발화권 요청, OFF → 해제
        if (newLocked) floorRequest() else floorRelease()
    }


    fun floorRequest() {
        Log.i(TAG, "PTT: floor request")
        update { copy(pttState = PttState.REQUESTING) }
        client?.floorRequest()
    }

    fun floorRelease() {
        Log.i(TAG, "PTT: floor release")
        client?.floorRelease()
    }

    fun getLocalVideoTrack(): VideoTrack? = client?.getLocalVideoTrack()

    // ================================================================
    //  정리
    // ================================================================

    override fun onCleared() {
        super.onCleared()
        client?.disconnect()
        client = null
        eglBase.release()
    }

    // ================================================================
    //  내부 헬퍼
    // ================================================================

    private inline fun update(crossinline block: DemoUiState.() -> DemoUiState) {
        _ui.value = _ui.value.block()
    }

    private fun addParticipant(userId: String) {
        val current = _ui.value.participants
        if (current.any { it.userId == userId }) return
        val track = remoteVideoTracks[userId]
        update { copy(participants = current + Participant(userId, track)) }
    }

    private fun removeParticipant(userId: String) {
        remoteVideoTracks.remove(userId)
        update { copy(participants = participants.filter { it.userId != userId }) }
    }

    private fun updateParticipantTrack(userId: String, track: VideoTrack?) {
        update {
            copy(participants = participants.map {
                if (it.userId == userId) it.copy(videoTrack = track) else it
            })
        }
    }

    // ================================================================
    //  SDK 이벤트 리스너
    // ================================================================

    private val sdkListener = object : OxLensEventListener {

        override fun onConnected() {
            Log.i(TAG, "✓ onConnected")
        }

        override fun onIdentified() {
            Log.i(TAG, "✓ onIdentified — ready")
            update {
                copy(wsState = WsState.READY, statusText = "연결 완료 — 방을 선택하세요")
            }
            // 인증 완료 → 즉시 방 목록 조회 (Home 동작 미러)
            client?.listRooms()
        }

        override fun onRoomList(roomsJson: String) {
            try {
                // 서버 응답: { "rooms": [...] } — JSONObject → rooms 배열 추출
                val root = org.json.JSONObject(roomsJson)
                val arr = root.optJSONArray("rooms") ?: org.json.JSONArray()
                val rooms = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RoomInfo(
                        roomId = obj.optString("room_id", ""),
                        name = obj.optString("name", ""),
                        mode = obj.optString("mode", "conference"),
                        participants = obj.optInt("participants", 0),
                        capacity = obj.optInt("capacity", 30),
                    )
                }
                Log.i(TAG, "방 목록 수신: ${rooms.size}개")
                update {
                    val prevSelected = selectedRoomId
                    val newSelected = if (rooms.any { it.roomId == prevSelected }) prevSelected
                        else rooms.firstOrNull()?.roomId ?: ""
                    copy(roomList = rooms, selectedRoomId = newSelected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "room list parse error", e)
            }
        }

        override fun onRoomCreated(roomId: String, name: String, mode: String) {
            Log.i(TAG, "✓ onRoomCreated: $roomId ($name, $mode)")
            update { copy(statusText = "입장 중...") }
            client?.joinRoom(roomId)
        }

        override fun onRoomJoined(roomId: String, mode: String) {
            Log.i(TAG, "✓ onRoomJoined: $roomId ($mode)")
            // Home 미러: room:joined → "미디어 연결 중…"
            toast("ice", "미디어 연결 중…")
            val rm = if (mode == "ptt") RoomMode.PTT else RoomMode.CONFERENCE
            val status = if (rm == RoomMode.CONFERENCE) "Conference — 음성+영상 활성"
                else "PTT — 화면을 길게 눌러 말하기"
            update {
                copy(
                    isInRoom = true,
                    roomMode = rm,
                    wsState = WsState.ROOM,
                    statusText = status,
                    isMicMuted = false,
                    isVideoMuted = false,
                    pttState = PttState.IDLE,
                )
            }
        }

        override fun onRoomLeft(roomId: String) {
            Log.i(TAG, "onRoomLeft: $roomId")
            remoteVideoTracks.clear()
            update {
                copy(
                    isInRoom = false,
                    wsState = WsState.READY,
                    statusText = "방을 나갔습니다",
                    participants = emptyList(),
                    localVideoTrack = null,
                    pttState = PttState.IDLE,
                    pttSpeakerTrack = null,
                )
            }
        }

        override fun onParticipantJoined(userId: String) {
            Log.i(TAG, "참가자 입장: $userId")
            addParticipant(userId)
        }

        override fun onParticipantLeft(userId: String) {
            Log.i(TAG, "참가자 퇴장: $userId")
            removeParticipant(userId)
        }

        override fun onPublishReady() {
            Log.i(TAG, "✓ publish ready")
            // Home 미러: ICE connected → "미디어 연결 완료"
            toast("ok", "미디어 연결 완료")
            val track = client?.getLocalVideoTrack()
            update { copy(localVideoTrack = track) }
        }

        override fun onTracksUpdated(action: String, count: Int) {
            Log.i(TAG, "onTracksUpdated: $action ($count tracks)")
        }

        override fun onRemoteVideoTrack(userId: String, track: VideoTrack) {
            Log.i(TAG, "리모트 비디오 수신: $userId")
            remoteVideoTracks[userId] = track

            // 참가자 목록에 없으면 먼저 추가
            addParticipant(userId)
            updateParticipantTrack(userId, track)

            // PTT 모드: 현재 발화자의 트랙이면 pttSpeakerTrack 갱신
            if (_ui.value.roomMode == RoomMode.PTT && _ui.value.pttState == PttState.TALKING) {
                val currentSpeaker = _ui.value.pttSpeaker
                if (userId == currentSpeaker || remoteVideoTracks.size == 1) {
                    update { copy(pttSpeakerTrack = track) }
                }
            }
        }

        override fun onRemoteVideoTrackRemoved(userId: String) {
            Log.i(TAG, "리모트 비디오 제거: $userId")
            remoteVideoTracks.remove(userId)
            updateParticipantTrack(userId, null)

            if (_ui.value.roomMode == RoomMode.PTT) {
                update { copy(pttSpeakerTrack = null) }
            }
        }

        override fun onFloorGranted(roomId: String) {
            Log.i(TAG, "✓ onFloorGranted")
            // 내가 화자 → 내 로컬 비디오 트랙을 pttSpeakerTrack에 연결
            val localTrack = client?.getLocalVideoTrack()
            val myId = _ui.value.userId
            update { copy(pttState = PttState.TALKING, pttSpeaker = myId, pttSpeakerTrack = localTrack) }
        }

        override fun onFloorDenied(reason: String) {
            Log.w(TAG, "onFloorDenied: $reason")
            update { copy(pttState = PttState.IDLE) }
        }

        override fun onFloorTaken(roomId: String, userId: String) {
            Log.i(TAG, "onFloorTaken: user=$userId remoteKeys=${remoteVideoTracks.keys}")
            val speakerName = userId.ifEmpty { "someone" }

            // 내가 화자이면 로컬 비디오
            if (userId == _ui.value.userId) {
                val localTrack = client?.getLocalVideoTrack()
                update { copy(pttState = PttState.TALKING, pttSpeaker = speakerName, pttSpeakerTrack = localTrack) }
                return
            }

            // 상대가 화자 → 리모트 비디오 트랙 매칭 시도
            var track = remoteVideoTracks[userId]
            if (track == null && remoteVideoTracks.isNotEmpty()) {
                // userId 불일치 시 첫 번째 리모트 트랙 fallback
                val fallbackKey = remoteVideoTracks.keys.first()
                track = remoteVideoTracks[fallbackKey]
                Log.w(TAG, "onFloorTaken: userId=$userId not in remoteVideoTracks, fallback to $fallbackKey")
            }
            update {
                copy(pttState = PttState.TALKING, pttSpeaker = speakerName, pttSpeakerTrack = track)
            }
        }

        override fun onFloorIdle(roomId: String) {
            Log.i(TAG, "onFloorIdle")
            update { copy(pttState = PttState.IDLE, pttSpeaker = "", pttSpeakerTrack = null) }
        }

        override fun onFloorRevoke(roomId: String) {
            Log.w(TAG, "onFloorRevoke")
            update { copy(pttState = PttState.IDLE, pttSpeaker = "", pttSpeakerTrack = null) }
        }

        override fun onFloorReleased() {
            Log.i(TAG, "onFloorReleased")
            update { copy(pttState = PttState.IDLE) }
        }

        override fun onMuteChanged(kind: String, muted: Boolean, phase: String) {
            Log.i(TAG, "onMuteChanged: kind=$kind muted=$muted phase=$phase")
            update {
                when (kind) {
                    "audio" -> copy(isMicMuted = muted)
                    "video" -> copy(isVideoMuted = muted)
                    else -> this
                }
            }
        }

        override fun onCameraSwitched(facingMode: String) {
            Log.i(TAG, "카메라 전환: $facingMode")
            update { copy(cameraFacing = facingMode) }
        }

        override fun onAudioDevicesChanged(deviceNames: List<String>, selectedDevice: String) {
            Log.i(TAG, "오디오 장치 변경: $deviceNames selected=$selectedDevice")
            update { copy(audioDeviceNames = deviceNames, selectedAudioDevice = selectedDevice) }
        }

        override fun onError(code: Int, message: String) {
            Log.e(TAG, "✗ onError: code=$code msg=$message")
            update { copy(statusText = "에러: $message") }
        }

        override fun onDisconnected(reason: String) {
            Log.w(TAG, "onDisconnected: $reason")
            client = null
            remoteVideoTracks.clear()
            val keepUserId = _ui.value.userId
            val keepPreset = _ui.value.mediaPreset
            update {
                DemoUiState(userId = keepUserId, mediaPreset = keepPreset, statusText = "연결 해제: $reason")
            }
        }
    }
}
