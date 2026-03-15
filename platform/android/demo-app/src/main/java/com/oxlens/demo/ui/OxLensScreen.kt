// author: kodeholic (powered by Claude)
// OxLens 데모 — Compose 메인 화면
// Home UI + Gemini 디자인 요소 통합:
//   - ConnectionPanel: 서버 주소 셀렉트 + 방 목록 셀렉트 + 입장/퇴장 버튼 (Home 미러)
//   - MediaControls: Lock + 긴급발언(PTT Lock) + 스피커 토글 + AnimatedVisibility
//   - ControlButton: 통일된 원형 버튼 스타일
//   - PttView: awaitEachGesture 패턴 (press/release 추적)
package com.oxlens.demo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oxlens.demo.*
import com.oxlens.demo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import java.util.UUID

// ================================================================
//  Toast 데이터
// ================================================================

data class ToastData(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "info", "ok", "warn", "err", "media", "signal", "ice"
    val text: String,
)

// ================================================================
//  메인 화면
// ================================================================

@Composable
fun OxLensApp(
    state: DemoUiState,
    eglContext: EglBase.Context,
    onUpdateUserId: (String) -> Unit = {},
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectMode: (RoomMode) -> Unit,
    onToggleMic: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleControlLock: () -> Unit,
    onTogglePttLock: () -> Unit,
    onFloorRequest: () -> Unit,
    onFloorRelease: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onSelectRoom: (String) -> Unit,
    onRoomJoin: () -> Unit,
    onRoomLeave: () -> Unit,
    onSelectMediaPreset: (MediaPreset) -> Unit = {},
    onSelectAudioDevice: (String) -> Unit = {},
    toastEvent: SharedFlow<Pair<String, String>>? = null,
) {
    // Toast 상태
    val toastList = remember { mutableStateListOf<ToastData>() }
    val coroutineScope = rememberCoroutineScope()
    val showToast: (String, String) -> Unit = { type, text ->
        val toast = ToastData(type = type, text = text)
        toastList.add(0, toast)
        coroutineScope.launch {
            delay(3000)
            toastList.remove(toast)
        }
    }

    // ViewModel toast 이벤트 수집
    LaunchedEffect(toastEvent) {
        toastEvent?.collect { (type, text) -> showToast(type, text) }
    }

    // Settings 시트 상태
    var showSettingsSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Header
        Header(
            wsState = state.wsState,
            roomMode = state.roomMode,
            onSettingsClick = { showSettingsSheet = true },
        )

        // 2. 연결 패널
        ConnectionPanel(
            selectedServerIndex = state.selectedServerIndex,
            wsState = state.wsState,
            isInRoom = state.isInRoom,
            roomList = state.roomList,
            selectedRoomId = state.selectedRoomId,
            userId = state.userId,
            onUpdateUserId = onUpdateUserId,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSelectServer = onSelectServer,
            onSelectRoom = onSelectRoom,
            onRoomJoin = onRoomJoin,
            onRoomLeave = onRoomLeave,
        )

        // 3. 미디어 컨트롤 바
        MediaControls(
            enabled = state.isInRoom,
            isPttMode = state.roomMode == RoomMode.PTT,
            isLocked = state.isControlLocked,
            isVideoMuted = state.isVideoMuted,
            isMicMuted = state.isMicMuted,
            isSpeakerOn = state.isSpeakerOn,
            pttLocked = state.isPttLocked,
            onLockToggle = onToggleControlLock,
            onPttLockToggle = onTogglePttLock,
            onVideoToggle = onToggleVideo,
            onMicToggle = onToggleMic,
            onSpeakerToggle = onToggleSpeaker,
            onCameraSwitch = onSwitchCamera,
        )

        // 4. 메인 컨텐츠 영역
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BrandSurface)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
        ) {
            when {
                !state.isInRoom -> WaitingScreen(statusText = state.statusText)

                state.roomMode == RoomMode.CONFERENCE -> {
                    ConferenceGrid(
                        participants = state.participants,
                        eglContext = eglContext,
                    )
                }

                state.roomMode == RoomMode.PTT -> {
                    PttView(
                        pttState = state.pttState,
                        speaker = state.pttSpeaker,
                        speakerTrack = state.pttSpeakerTrack,
                        eglContext = eglContext,
                        isLocalSpeaker = state.pttSpeaker == state.localUserId,
                        isLocked = state.isControlLocked,
                        pttLocked = state.isPttLocked,
                        onFloorRequest = onFloorRequest,
                        onFloorRelease = onFloorRelease,
                    )
                }
            }

            // 로컬 PIP (Conference + 방 안)
            if (state.isInRoom && state.roomMode == RoomMode.CONFERENCE && state.localVideoTrack != null) {
                // key로 안정화 — 참가자 변경 시 리컴포즈로 AndroidView 재생성 방지
                key("local-pip") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .width(120.dp)
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, BrandRust, RoundedCornerShape(12.dp))
                    ) {
                        WebRtcSurface(
                            eglContext = eglContext,
                            videoTrack = state.localVideoTrack,
                            mirror = state.cameraFacing == "front",
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = "나",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(OverlayDark, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    } // Column 끝

        // Toast 오버레이 (우상단)
        ToastStack(
            toasts = toastList,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 16.dp)
        )

        // 설정 바텀 시트
        if (showSettingsSheet) {
            SettingsBottomSheet(
                currentPreset = state.mediaPreset,
                onSelectPreset = onSelectMediaPreset,
                audioDeviceNames = state.audioDeviceNames,
                selectedAudioDevice = state.selectedAudioDevice,
                onSelectAudioDevice = onSelectAudioDevice,
                cameraFacing = state.cameraFacing,
                onSwitchCamera = onSwitchCamera,
                onDismiss = { showSettingsSheet = false },
            )
        }
    } // Box 끝
}

// ================================================================
//  Header
// ================================================================

@Composable
private fun Header(wsState: WsState, roomMode: RoomMode, onSettingsClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("0x", color = BrandRust, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
            Text("LENS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("SDK v0.6.2", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WsBadge(wsState = wsState, roomMode = roomMode)
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, "Settings", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun WsBadge(wsState: WsState, roomMode: RoomMode) {
    val (text, textColor, bgColor) = when (wsState) {
        WsState.OFF -> Triple("OFF", TextMuted, BrandBorder)
        WsState.CONNECTING -> Triple("...", StatusYellow, BrandBorder)
        WsState.READY -> Triple("READY", StatusGreen, StatusGreenBg)
        WsState.ROOM -> Triple(
            if (roomMode == RoomMode.PTT) "PTT" else "CONF",
            BrandCyan,
            BrandCyan.copy(alpha = 0.15f),
        )
    }
    Text(
        text = text,
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

// ================================================================
//  연결 패널 (Gemini 스타일: 서버 URL 편집 + 방 입퇴장 분리)
// ================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPanel(
    selectedServerIndex: Int,
    wsState: WsState,
    isInRoom: Boolean,
    roomList: List<RoomInfo>,
    selectedRoomId: String,
    userId: String,
    onUpdateUserId: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onSelectRoom: (String) -> Unit,
    onRoomJoin: () -> Unit,
    onRoomLeave: () -> Unit,
) {
    val isConnected = wsState == WsState.READY || wsState == WsState.ROOM

    // 서버 드롭다운 상태
    var serverExpanded by remember { mutableStateOf(false) }
    // 방 드롭다운 상태
    var roomExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrandSurface)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1행: 서버 주소 셀렉트 + 전원 버튼
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 서버 주소 드롭다운 (Home의 <select id="srv-url"> 미러)
            ExposedDropdownMenuBox(
                expanded = serverExpanded,
                onExpandedChange = { if (!isConnected) serverExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = SERVER_PRESETS.getOrNull(selectedServerIndex)?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isConnected,
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace,
                    ),
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        disabledBorderColor = Color.White.copy(alpha = 0.05f),
                        disabledTextColor = TextMuted,
                    ),
                )
                ExposedDropdownMenu(
                    expanded = serverExpanded,
                    onDismissRequest = { serverExpanded = false },
                    modifier = Modifier.background(BrandDark),
                ) {
                    SERVER_PRESETS.forEachIndexed { index, preset ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    preset.label,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (index == selectedServerIndex) BrandCyan else Color.White,
                                )
                            },
                            onClick = {
                                onSelectServer(index)
                                serverExpanded = false
                            },
                        )
                    }
                }
            }

            // 사용자 ID (Home: 전원 버튼 좌측 TextField)
            OutlinedTextField(
                value = userId,
                onValueChange = { if (it.length <= 4) onUpdateUserId(it) },
                enabled = !isConnected,
                modifier = Modifier.width(72.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace,
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandCyan.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    disabledBorderColor = Color.White.copy(alpha = 0.05f),
                    disabledTextColor = TextMuted,
                ),
            )

            // 전원 버튼 (연결/해제)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) StatusGreen.copy(alpha = 0.15f) else BrandDark)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    .clickable { if (isConnected) onDisconnect() else onConnect() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    "Connect",
                    tint = if (isConnected) StatusGreen else TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 2행: 방 선택 셀렉트 + 입장/퇴장 버튼 (항상 노출, 연결 전에는 disabled)
        val roomEnabled = isConnected && !isInRoom
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 방 목록 드롭다운 (Home의 <select id="room-select"> 미러)
            ExposedDropdownMenuBox(
                expanded = roomExpanded,
                onExpandedChange = { if (roomEnabled) roomExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                val selectedRoom = roomList.find { it.roomId == selectedRoomId }
                val displayText = if (selectedRoom != null) {
                    val modeTag = if (selectedRoom.mode == "ptt") " [PTT]" else ""
                    "${selectedRoom.name}$modeTag (${selectedRoom.participants}/${selectedRoom.capacity})"
                } else {
                    "방 선택"
                }

                OutlinedTextField(
                    value = displayText,
                    onValueChange = {},
                    readOnly = true,
                    enabled = roomEnabled,
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace,
                    ),
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roomExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        disabledBorderColor = Color.White.copy(alpha = 0.05f),
                        disabledTextColor = TextMuted,
                    ),
                )
                ExposedDropdownMenu(
                    expanded = roomExpanded,
                    onDismissRequest = { roomExpanded = false },
                    modifier = Modifier.background(BrandDark),
                ) {
                    if (roomList.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("방 없음", fontSize = 12.sp, color = TextMuted) },
                            onClick = { roomExpanded = false },
                        )
                    } else {
                        roomList.forEach { room ->
                            val modeTag = if (room.mode == "ptt") " [PTT]" else ""
                            val label = "${room.name}$modeTag (${room.participants}/${room.capacity})"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (room.roomId == selectedRoomId) BrandCyan else Color.White,
                                    )
                                },
                                onClick = {
                                    onSelectRoom(room.roomId)
                                    roomExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // 입장/퇴장 버튼 (연결 전에는 비활성)
            val canEnter = isConnected && selectedRoomId.isNotBlank()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isInRoom -> StatusGreen.copy(alpha = 0.15f)
                            !isConnected -> BrandDark.copy(alpha = 0.5f)
                            else -> BrandDark
                        }
                    )
                    .border(1.dp, Color.White.copy(alpha = if (isConnected) 0.1f else 0.05f), CircleShape)
                    .then(
                        if (isConnected) Modifier.clickable {
                            if (isInRoom) onRoomLeave()
                            else if (canEnter) onRoomJoin()
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isInRoom) Icons.AutoMirrored.Filled.ExitToApp
                        else Icons.AutoMirrored.Filled.Login,
                    contentDescription = "Room",
                    tint = when {
                        isInRoom -> StatusGreen
                        !isConnected -> TextMuted.copy(alpha = 0.3f)
                        else -> TextMuted
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ================================================================
//  미디어 컨트롤 (Gemini 스타일: Lock + 긴급발언 + 스피커)
// ================================================================

@Composable
private fun MediaControls(
    enabled: Boolean,
    isPttMode: Boolean,
    isLocked: Boolean,
    isVideoMuted: Boolean,
    isMicMuted: Boolean,
    isSpeakerOn: Boolean,
    pttLocked: Boolean,
    onLockToggle: () -> Unit,
    onPttLockToggle: () -> Unit,
    onVideoToggle: () -> Unit,
    onMicToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.3f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrandSurface)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 좌측: 잠금 + 긴급발언(PTT만) + 카메라전환
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.alpha(alpha),
        ) {
            ControlButton(
                icon = if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                isActive = isLocked,
                activeColor = StatusYellow,
                onClick = { if (enabled) onLockToggle() },
            )

            AnimatedVisibility(visible = isPttMode) {
                ControlButton(
                    icon = Icons.Rounded.Campaign,
                    isActive = pttLocked,
                    activeColor = Color.White,
                    activeBgColor = StatusRed,
                    inactiveColor = StatusRed.copy(alpha = 0.7f),
                    onClick = { if (enabled && !isLocked) onPttLockToggle() },
                )
            }

            ControlButton(
                icon = Icons.Rounded.FlipCameraAndroid,
                onClick = { if (enabled && !isLocked) onCameraSwitch() },
            )
        }

        // 우측: 비디오 + 마이크(Conference만) + 스피커
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.alpha(alpha),
        ) {
            ControlButton(
                icon = if (isVideoMuted) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                isActive = !isVideoMuted,
                onClick = { if (enabled) onVideoToggle() },
            )

            AnimatedVisibility(visible = !isPttMode) {
                ControlButton(
                    icon = if (isMicMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    isActive = !isMicMuted,
                    onClick = { if (enabled) onMicToggle() },
                )
            }

            ControlButton(
                icon = if (isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                isActive = isSpeakerOn,
                onClick = { if (enabled) onSpeakerToggle() },
            )
        }
    }
}

// ================================================================
//  통일된 원형 컨트롤 버튼 (Gemini 스타일)
// ================================================================

@Composable
private fun ControlButton(
    icon: ImageVector,
    isActive: Boolean = true,
    activeColor: Color = Color.White,
    inactiveColor: Color = TextMuted,
    activeBgColor: Color = BrandDark,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isActive && activeBgColor != BrandDark) activeBgColor else BrandDark)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) activeColor else inactiveColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ================================================================
//  대기 화면
// ================================================================

@Composable
private fun WaitingScreen(statusText: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text("0x", color = BrandRust, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("LENS", color = Color.White.copy(alpha = 0.2f), fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(12.dp))
            Text(statusText, color = TextMuted, fontSize = 13.sp)
        }
    }
}

// ================================================================
//  Conference 그리드
// ================================================================

@Composable
private fun ConferenceGrid(
    participants: List<Participant>,
    eglContext: EglBase.Context,
) {
    if (participants.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("참가자 대기 중...", color = TextMuted, fontSize = 13.sp)
        }
        return
    }

    val cols = when {
        participants.size <= 1 -> 1
        participants.size <= 4 -> 2
        participants.size <= 9 -> 3
        else -> 4
    }
    val rows = (participants.size + cols - 1) / cols

    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    if (idx < participants.size) {
                        // key로 안정화 — 참가자 추가/제거 시 WebRtcSurface 재생성 방지
                        key(participants[idx].userId) {
                            ParticipantTile(
                                participant = participants[idx],
                                eglContext = eglContext,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    participant: Participant,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BrandDark)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (participant.videoTrack != null) {
            WebRtcSurface(
                eglContext = eglContext,
                videoTrack = participant.videoTrack,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = participant.userId,
                fontSize = 11.sp,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(OverlayDark, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        participant.userId.take(2).uppercase(),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(participant.userId, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

// ================================================================
//  PTT 뷰 (Gemini awaitEachGesture + Lock/PttLock 통합)
// ================================================================

@Composable
private fun PttView(
    pttState: PttState,
    speaker: String,
    speakerTrack: org.webrtc.VideoTrack?,
    eglContext: EglBase.Context,
    isLocalSpeaker: Boolean,
    isLocked: Boolean,
    pttLocked: Boolean,
    onFloorRequest: () -> Unit,
    onFloorRelease: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isLocked, pttLocked) {
                // 잠금 상태이거나 긴급발언 중이면 터치 무시
                if (isLocked || pttLocked) return@pointerInput

                awaitEachGesture {
                    awaitFirstDown()
                    onFloorRequest()

                    // 손가락이 떨어질 때까지 대기
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })

                    onFloorRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 발화자 비디오 (전체화면) — 항상 유지, track 교체 후 딜레이 후 표시
        // [FIX] clearImage()로 잔상 제거 + 150ms 후 표시로 첫 프레임 도착 대기.
        // onFirstFrameRendered는 renderer lifetime에 1회만 호출되어 track 교체 시 부적합.
        val wantVideo = pttState == PttState.TALKING && speakerTrack != null
        var videoReady by remember { mutableStateOf(false) }
        LaunchedEffect(wantVideo, speakerTrack) {
            if (wantVideo) {
                videoReady = false
                kotlinx.coroutines.delay(150)
                // 딜레이 후에도 여전히 TALKING이면 표시
                if (pttState == PttState.TALKING) videoReady = true
            } else {
                videoReady = false
            }
        }
        val showVideo = wantVideo && videoReady
        key("ptt-speaker") {
            WebRtcSurface(
                eglContext = eglContext,
                videoTrack = if (wantVideo) speakerTrack else null,
                mirror = isLocalSpeaker,
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = if (showVideo) 0.dp else (-2000).dp),
            )
        }

        // 상태별 오버레이
        when (pttState) {
            PttState.IDLE -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.TouchApp,
                        "PTT",
                        tint = BrandCyan.copy(alpha = 0.7f),
                        modifier = Modifier.size(80.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "TAP",
                        color = BrandCyan.copy(alpha = 0.8f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("화면을 길게 눌러 말하기", color = TextMuted, fontSize = 12.sp)
                }
            }

            PttState.REQUESTING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.TouchApp,
                        "Requesting",
                        tint = StatusYellow,
                        modifier = Modifier.size(80.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "REQUESTING",
                        color = StatusYellow,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                }
            }

            PttState.TALKING -> {
                // 발화 뱃지 (우측 상단)
                AnimatedVisibility(
                    visible = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isLocalSpeaker) StatusRed else StatusGreen.copy(alpha = 0.8f)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isLocalSpeaker) "SPEAKING" else "Listening · $speaker",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ================================================================
//  Toast 스택 (Gemini 디자인 — 우상단 오버레이)
// ================================================================

@Composable
fun ToastStack(toasts: List<ToastData>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.widthIn(max = 280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        for (toast in toasts) {
            key(toast.id) {
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(250)),
                ) {
                    ToastItem(toast)
                }
            }
        }
    }
}

@Composable
private fun ToastItem(toast: ToastData) {
    val icon = when (toast.type) {
        "ok" -> Icons.Rounded.CheckCircle
        "err" -> Icons.Rounded.ErrorOutline
        "warn" -> Icons.Rounded.WarningAmber
        "media" -> Icons.Rounded.Mic
        "signal" -> Icons.Rounded.Wifi
        "ice" -> Icons.Rounded.SyncAlt
        else -> Icons.Rounded.Info
    }
    val iconColor = when (toast.type) {
        "err" -> StatusRed
        "warn" -> StatusYellow
        "ok" -> StatusGreen
        "signal", "ice", "media" -> BrandCyan
        else -> Color.LightGray
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BrandSurface.copy(alpha = 0.95f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
        Text(text = toast.text, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ================================================================
//  설정 바텀 시트 (Gemini 디자인)
// ================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    currentPreset: MediaPreset = MediaPreset.HD,
    onSelectPreset: (MediaPreset) -> Unit = {},
    audioDeviceNames: List<String> = listOf("기본 장치"),
    selectedAudioDevice: String = "기본 장치",
    onSelectAudioDevice: (String) -> Unit = {},
    cameraFacing: String = "front",
    onSwitchCamera: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandSurface,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

            // 화질 프리셋 — ViewModel 연동
            SettingDropdown(
                label = "화질",
                options = MediaPreset.entries.map { it.label },
                selected = currentPreset.label,
                onSelect = { label ->
                    MediaPreset.entries.firstOrNull { it.label == label }?.let(onSelectPreset)
                },
            )
            // 스피커 — AudioSwitch 연동 (즉시 적용)
            // Android는 마이크 별도 열거 불필요 — 스피커 선택 시 마이크도 자동 전환
            SettingDropdown(
                label = "스피커",
                options = audioDeviceNames,
                selected = selectedAudioDevice,
                onSelect = onSelectAudioDevice,
            )
            // 카메라 — 전면/후면 전환 (즉시 적용)
            val cameraOptions = listOf("전면", "후면")
            val currentCamera = if (cameraFacing == "front") "전면" else "후면"
            SettingDropdown(
                label = "카메라",
                options = cameraOptions,
                selected = currentCamera,
                onSelect = { selected ->
                    val targetFacing = if (selected == "전면") "front" else "back"
                    if (targetFacing != cameraFacing) onSwitchCamera()
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "※ 화질은 다음 입장 시, 장치 전환은 즉시 적용됩니다.",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val icon = when (label) {
                "마이크" -> Icons.Rounded.Mic
                "스피커" -> Icons.Rounded.VolumeUp
                "카메라" -> Icons.Rounded.Videocam
                else -> null
            }
            if (icon != null) {
                Icon(icon, null, tint = BrandCyan.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
            Text(label, color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().width(200.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BrandDark,
                    unfocusedContainerColor = BrandDark,
                    focusedBorderColor = BrandCyan.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(BrandDark),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
