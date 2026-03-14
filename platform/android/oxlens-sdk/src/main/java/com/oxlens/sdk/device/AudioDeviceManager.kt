// author: kodeholic (powered by Claude)
package com.oxlens.sdk.device

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch

/**
 * AudioDeviceManager — Twilio AudioSwitch 래퍼.
 *
 * home의 DeviceManager(브라우저 enumerateDevices/setSinkId 기반) 역할을
 * Android 네이티브(AudioSwitch)로 구현.
 *
 * ## 책임
 * - 오디오 장치 열거 (earpiece, speaker, bluetooth, wired headset)
 * - 장치 전환
 * - 핫플러그 감지 (bluetooth 연결/해제 등)
 * - AudioFocus 관리
 *
 * ## AudioSwitch 장치 우선순위 (기본)
 * BluetoothHeadset > WiredHeadset > Earpiece > Speakerphone
 *
 * PTT 무전 앱에서는 Speakerphone 우선이 더 자연스러울 수 있음 →
 * preferredDeviceList로 커스터마이징 가능.
 */
class AudioDeviceManager(
    private val context: Context,
    private val listener: AudioDeviceListener? = null,
) {
    companion object {
        private const val TAG = "AudioDeviceManager"
    }

    private var audioSwitch: AudioSwitch? = null
    private var started = false

    /** 현재 사용 가능한 장치 목록 */
    val availableDevices: List<AudioDevice>
        get() = audioSwitch?.availableAudioDevices ?: emptyList()

    /** 현재 선택된 장치 */
    val selectedDevice: AudioDevice?
        get() = audioSwitch?.selectedAudioDevice

    // ================================================================
    //  Lifecycle
    // ================================================================

    /**
     * 장치 감시 시작.
     *
     * @param preferSpeaker true면 Speakerphone을 최우선 (PTT 모드에 적합)
     */
    fun start(preferSpeaker: Boolean = false) {
        if (started) return

        val preferredList = if (preferSpeaker) {
            listOf(
                AudioDevice.Speakerphone::class.java,
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
            )
        } else {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java,
            )
        }

        audioSwitch = AudioSwitch(
            context = context,
            preferredDeviceList = preferredList,
        )

        audioSwitch?.start { devices, selected ->
            Log.i(TAG, "devices changed: ${devices.map { it.name }} selected=${selected?.name}")
            listener?.onAudioDevicesChanged(devices, selected)
        }

        started = true
        Log.i(TAG, "started (preferSpeaker=$preferSpeaker)")
    }

    /** AudioFocus 획득 + 오디오 라우팅 활성화 */
    fun activate() {
        audioSwitch?.activate()
        Log.i(TAG, "activated → ${selectedDevice?.name}")
    }

    /** AudioFocus 해제 */
    fun deactivate() {
        audioSwitch?.deactivate()
        Log.i(TAG, "deactivated")
    }

    /** 장치 감시 중단 + 정리 */
    fun stop() {
        if (!started) return
        audioSwitch?.stop()
        audioSwitch = null
        started = false
        Log.i(TAG, "stopped")
    }

    // ================================================================
    //  장치 선택
    // ================================================================

    /** 특정 장치 수동 선택 */
    fun selectDevice(device: AudioDevice) {
        audioSwitch?.selectDevice(device)
        Log.i(TAG, "selected: ${device.name}")
    }

    /** Speakerphone으로 전환 (PTT 앱에서 자주 사용) */
    fun selectSpeaker(): Boolean {
        val speaker = availableDevices.filterIsInstance<AudioDevice.Speakerphone>().firstOrNull()
        if (speaker != null) { selectDevice(speaker); return true }
        Log.w(TAG, "speakerphone not available")
        return false
    }

    /** Earpiece로 전환 */
    fun selectEarpiece(): Boolean {
        val earpiece = availableDevices.filterIsInstance<AudioDevice.Earpiece>().firstOrNull()
        if (earpiece != null) { selectDevice(earpiece); return true }
        Log.w(TAG, "earpiece not available")
        return false
    }
}

// ================================================================
//  Listener
// ================================================================

/** 오디오 장치 변경 콜백 */
interface AudioDeviceListener {
    fun onAudioDevicesChanged(devices: List<AudioDevice>, selected: AudioDevice?)
}
