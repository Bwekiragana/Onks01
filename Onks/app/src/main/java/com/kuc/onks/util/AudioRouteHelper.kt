package com.kuc.onks.util

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Forces audio output to wired 3.5 mm or USB-C adapter for the Host phone.
 */
object AudioRouteHelper {

    private const val TAG = "AudioRouteHelper"

    fun forceWiredOutput(audioManager: AudioManager): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wiredTypes = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_AUX_LINE
            )
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val found = devices.firstOrNull { it.type in wiredTypes }
            return if (found != null) {
                Log.i(TAG, "Wired output: ${deviceName(found.type)}")
                true
            } else {
                Log.w(TAG, "No wired output found — audio may play through speaker")
                false
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isWiredHeadsetOn
    }

    fun resetToNormal(audioManager: AudioManager) {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun deviceName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "3.5 mm headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET    -> "3.5 mm TRRS headset"
        AudioDeviceInfo.TYPE_USB_HEADSET      -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE       -> "USB audio device"
        AudioDeviceInfo.TYPE_AUX_LINE         -> "AUX line"
        else                                  -> "type($type)"
    }
}
