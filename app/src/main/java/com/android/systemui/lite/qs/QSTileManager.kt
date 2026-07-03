package com.android.systemui.lite.qs

import android.content.Context
import com.android.systemui.lite.data.SystemStateProvider
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin compatibility shim — all Quick Settings state and toggle logic now lives in
 * com.android.systemui.lite.data.SystemStateProvider. This class exposes the same
 * surface so existing Koin wiring and call sites keep compiling without duplication.
 */
class QSTileManager(context: Context) {

    private val sp: SystemStateProvider =
        org.koin.core.context.GlobalContext.get().get()

    fun start() = sp.start()
    fun stop() = sp.stop()

    val wifiEnabled: StateFlow<Boolean> get() = sp.wifiEnabled
    val bluetoothEnabled: StateFlow<Boolean> get() = sp.bluetoothEnabled
    val bluetoothTransitioning: StateFlow<Boolean> get() = sp.bluetoothTransitioning
    val dndEnabled: StateFlow<Boolean> get() = sp.dndEnabled
    val flashlightEnabled: StateFlow<Boolean> get() = sp.flashlightEnabled
    val flashlightAvailable: StateFlow<Boolean?> get() = sp.flashlightAvailable
    val airplaneModeEnabled: StateFlow<Boolean> get() = sp.airplaneModeEnabled
    val autoRotateEnabled: StateFlow<Boolean> get() = sp.autoRotateEnabled
    val batterySaverEnabled: StateFlow<Boolean> get() = sp.batterySaverEnabled
    val screenRecording: StateFlow<Boolean> get() = sp.screenRecording
    val brightness: StateFlow<Int> get() = sp.brightness
    val mediaVolume: StateFlow<Int> get() = sp.mediaVolume
    val ringVolume: StateFlow<Int> get() = sp.ringVolume
    val alarmVolume: StateFlow<Int> get() = sp.alarmVolume

    /** Return the current NotificationPolicy (as Any — the concrete type is @SystemAPI), or null if unavailable. */
    fun getNotificationPolicy(): Any? = sp.getNotificationPolicy()

    fun toggleWifi() = sp.toggleWifi()
    fun toggleBluetooth() = sp.toggleBluetooth()
    fun toggleDnd() = sp.toggleDnd()
    fun toggleFlashlight() = sp.toggleFlashlight()
    fun toggleAirplaneMode() = sp.toggleAirplaneMode()
    fun toggleAutoRotate() = sp.toggleAutoRotate()
    fun toggleBatterySaver() = sp.toggleBatterySaver()
    fun toggleScreenRecording() = sp.toggleScreenRecording()

    fun setBrightness(value: Int) = sp.setBrightness(value)
    fun setMediaVolume(percent: Int) = sp.setMediaVolume(percent)
    fun setRingVolume(percent: Int) = sp.setRingVolume(percent)
    fun setAlarmVolume(percent: Int) = sp.setAlarmVolume(percent)
}
