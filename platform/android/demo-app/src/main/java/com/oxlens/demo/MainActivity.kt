// author: kodeholic (powered by Claude)
// OxLens Demo — Conference + PTT E2E 테스트
package com.oxlens.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.oxlens.sdk.OxLensClient
import com.oxlens.sdk.OxLensEventListener

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OxLensDemo"
        private const val REQ_AUDIO_PERMISSION = 100

        // ★ 서버 주소
        private const val SERVER_URL = "ws://192.168.0.29:1974/ws"
        private const val TOKEN = "demo-token"
        private const val USER_ID = "android-demo-1"
    }

    private var client: OxLensClient? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvFloor: TextView
    private lateinit var btnConference: Button
    private lateinit var btnPtt: Button
    private lateinit var btnTalk: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvFloor = findViewById(R.id.tvFloor)
        btnConference = findViewById(R.id.btnConference)
        btnPtt = findViewById(R.id.btnPtt)
        btnTalk = findViewById(R.id.btnTalk)

        btnConference.setOnClickListener { startRoom("conference") }
        btnPtt.setOnClickListener { startRoom("ptt") }
        setupTalkButton()

        // RECORD_AUDIO 런타임 퍼미션 요청 → 승인 후 connect
        // RECORD_AUDIO + BLUETOOTH_CONNECT 런타임 퍼미션
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            connectToServer()
        } else {
            Log.i(TAG, "Requesting permissions: $needed")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            for (i in permissions.indices) {
                val perm = permissions[i]
                val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "${if (granted) "✓" else "✗"} $perm ${if (granted) "granted" else "denied"}")
            }
            connectToServer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
        Log.i(TAG, "client disconnected")
    }

    // ================================================================
    //  PTT Talk 버튼 (길게 누르기)
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTalkButton() {
        btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.i(TAG, "PTT: button DOWN — requesting floor")
                    client?.floorRequest()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.i(TAG, "PTT: button UP — releasing floor")
                    client?.floorRelease()
                    true
                }
                else -> false
            }
        }
    }

    // ================================================================
    //  방 생성 + 입장
    // ================================================================

    private fun startRoom(mode: String) {
        btnConference.isEnabled = false
        btnPtt.isEnabled = false
        updateStatus("Creating $mode room...")
        client?.createRoom("demo-room", mode = mode)
    }

    // ================================================================
    //  서버 연결
    // ================================================================

    private fun connectToServer() {
        Log.i(TAG, "connecting to $SERVER_URL ...")
        updateStatus("Connecting...")

        client = OxLensClient(
            context = applicationContext,
            serverUrl = SERVER_URL,
            token = TOKEN,
            userId = USER_ID,
            listener = object : OxLensEventListener {

                override fun onConnected() {
                    Log.i(TAG, "✓ onConnected")
                }

                override fun onIdentified() {
                    Log.i(TAG, "✓ onIdentified — ready")
                    updateStatus("Connected. Choose mode:")
                    runOnUiThread {
                        btnConference.isEnabled = true
                        btnPtt.isEnabled = true
                    }
                }

                override fun onRoomCreated(roomId: String, name: String, mode: String) {
                    Log.i(TAG, "✓ onRoomCreated: $roomId ($name, $mode) — joining...")
                    updateStatus("Joining $mode room...")
                    client?.joinRoom(roomId)
                }

                override fun onRoomJoined(roomId: String, mode: String) {
                    Log.i(TAG, "✓ onRoomJoined: $roomId ($mode)")
                    if (mode == "ptt") {
                        updateStatus("PTT room joined — hold TALK to speak")
                        runOnUiThread {
                            btnTalk.visibility = View.VISIBLE
                            tvFloor.visibility = View.VISIBLE
                            tvFloor.text = "Floor: IDLE"
                        }
                    } else {
                        updateStatus("Conference room joined — mic live")
                    }
                }

                override fun onRoomLeft(roomId: String) {
                    Log.i(TAG, "onRoomLeft: $roomId")
                    updateStatus("Left room")
                }

                override fun onTracksUpdated(action: String, count: Int) {
                    Log.i(TAG, "onTracksUpdated: $action ($count tracks)")
                }

                override fun onFloorGranted(roomId: String) {
                    Log.i(TAG, "✓ onFloorGranted")
                    runOnUiThread {
                        tvFloor.text = "Floor: SPEAKING ★"
                        btnTalk.text = "🎙 SPEAKING"
                    }
                }

                override fun onFloorDenied(reason: String) {
                    Log.w(TAG, "onFloorDenied: $reason")
                    runOnUiThread {
                        tvFloor.text = "Floor: DENIED ($reason)"
                        btnTalk.text = "TALK"
                    }
                }

                override fun onFloorTaken(roomId: String, userId: String) {
                    Log.i(TAG, "onFloorTaken: user=$userId")
                    runOnUiThread { tvFloor.text = "Floor: $userId speaking" }
                }

                override fun onFloorIdle(roomId: String) {
                    Log.i(TAG, "onFloorIdle")
                    runOnUiThread {
                        tvFloor.text = "Floor: IDLE"
                        btnTalk.text = "TALK"
                    }
                }

                override fun onFloorRevoke(roomId: String) {
                    Log.w(TAG, "onFloorRevoke")
                    runOnUiThread {
                        tvFloor.text = "Floor: REVOKED"
                        btnTalk.text = "TALK"
                    }
                }

                override fun onFloorReleased() {
                    Log.i(TAG, "onFloorReleased")
                    runOnUiThread {
                        tvFloor.text = "Floor: IDLE"
                        btnTalk.text = "TALK"
                    }
                }

                override fun onError(code: Int, message: String) {
                    Log.e(TAG, "✗ onError: code=$code msg=$message")
                    updateStatus("Error: $message")
                }

                override fun onDisconnected(reason: String) {
                    Log.w(TAG, "onDisconnected: $reason")
                    updateStatus("Disconnected: $reason")
                }
            }
        )

        client!!.connect()
    }

    private fun updateStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }
}
