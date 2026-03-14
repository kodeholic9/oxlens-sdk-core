// author: kodeholic (powered by Claude)
// OxLens Demo — Compose 진입점
package com.oxlens.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.oxlens.demo.ui.OxLensApp
import com.oxlens.demo.ui.theme.OxLensTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_PERMISSIONS = 100
    }

    private val viewModel: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            OxLensTheme {
                val state by viewModel.ui.collectAsState()

                OxLensApp(
                    state = state,
                    eglContext = viewModel.eglBase.eglBaseContext,
                    onUpdateUserId = { viewModel.updateUserId(it) },
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() },
                    onSelectMode = { viewModel.selectMode(it) },
                    onToggleMic = { viewModel.toggleMic() },
                    onToggleVideo = { viewModel.toggleVideo() },
                    onToggleSpeaker = { viewModel.toggleSpeaker() },
                    onSwitchCamera = { viewModel.switchCamera() },
                    onToggleControlLock = { viewModel.toggleControlLock() },
                    onTogglePttLock = { viewModel.togglePttLock() },
                    onFloorRequest = { viewModel.floorRequest() },
                    onFloorRelease = { viewModel.floorRelease() },
                    onSelectServer = { viewModel.selectServer(it) },
                    onSelectRoom = { viewModel.selectRoom(it) },
                    onRoomJoin = { viewModel.joinSelectedRoom() },
                    onRoomLeave = { viewModel.leaveRoom() },
                    onSelectMediaPreset = { viewModel.selectMediaPreset(it) },
                    onSelectAudioDevice = { viewModel.selectAudioDevice(it) },
                    toastEvent = viewModel.toastEvent,
                )
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }
}
