package com.moodify.app.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class PlaybackOutputRoute {
    DEVICE,
    WIRED,
    BLUETOOTH,
    USB,
    HDMI,
    OTHER,
}

/** Observes the physical output route without claiming codec or bit-perfect status. */
internal class PlaybackRouteMonitor(
    context: Context,
    private val onChanged: (PlaybackOutputRoute) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var started = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publish()
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(callback, null)
        publish()
    }

    fun stop() {
        if (!started) return
        audioManager.unregisterAudioDeviceCallback(callback)
        started = false
    }

    private fun publish() {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(mutableSetOf()) { it.type }
        onChanged(classifyPlaybackRoute(types))
    }
}

internal fun classifyPlaybackRoute(types: Set<Int>): PlaybackOutputRoute = when {
    types.any {
        it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            it == AudioDeviceInfo.TYPE_HEARING_AID
    } -> PlaybackOutputRoute.BLUETOOTH
    types.any {
        it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it == AudioDeviceInfo.TYPE_LINE_ANALOG
    } -> PlaybackOutputRoute.WIRED
    types.any { it == AudioDeviceInfo.TYPE_USB_DEVICE || it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_ACCESSORY } -> PlaybackOutputRoute.USB
    types.any { it == AudioDeviceInfo.TYPE_HDMI || it == AudioDeviceInfo.TYPE_HDMI_ARC || it == AudioDeviceInfo.TYPE_HDMI_EARC } -> PlaybackOutputRoute.HDMI
    types.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } -> PlaybackOutputRoute.DEVICE
    types.isNotEmpty() -> PlaybackOutputRoute.OTHER
    else -> PlaybackOutputRoute.DEVICE
}
