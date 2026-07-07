package com.android.systemui.statusbar

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks cellular service state / signal level and reports a 0..4 level to a
 * listener (status bar signal_icon ImageView).
 *
 * The icon set is chosen in the listener; this controller stays a pure data source,
 * mirroring AOSP's `StatusBarSignalPolicy` data pipeline (just without its Binder wiring).
 *
 * Uses the framework `stat_sys_signal_0..4` + `stat_sys_signal_null` (flight/no-sim)
 * drawables, which devices ship for signal slot rendering.
 */
@Singleton
class SignalController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "SignalController"
        private const val LEVELS = 5
        private const val FLIGHT_MODE_OFF = 0
    }

    /** signal level is -1 when the icon should be the null/no-service icon. */
    fun interface SignalStateListener {
        fun onSignalStateChanged(level: Int, inService: Boolean, airplaneMode: Boolean)
    }

    private val telephonyManager: TelephonyManager? by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }

    private var listener: SignalStateListener? = null
    @Volatile private var level = -1
    @Volatile private var inService = false
    @Volatile private var registered = false

    @Suppress("DEPRECATION")
    private val phoneStateListener: PhoneStateListener =
        object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                val rawLevel = signalStrength?.level ?: 0
                level = rawLevel.coerceIn(0, LEVELS - 1)
                dispatch()
            }

            @Deprecated("Deprecated in Java")
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                inService = serviceState?.state == ServiceState.STATE_IN_SERVICE
                dispatch()
            }
        }

    fun register(listener: SignalStateListener) {
        this.listener = listener
    }

    fun unregister() {
        this.listener = null
    }

    override fun start() {
        if (registered) return
        val tm = telephonyManager ?: return
        try {
            tm.listen(phoneStateListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)
            registered = true
            // Initial probe using cached values.
            level = tm.signalStrength?.level?.coerceIn(0, LEVELS - 1) ?: -1
            dispatch()
            Log.i(TAG, "SignalController started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing READ_PHONE_STATE", e)
        }
    }

    override fun stop() {
        if (!registered) return
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: SecurityException) { /* ignore */ }
        registered = false
    }

    private fun dispatch() {
        val airplane = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, FLIGHT_MODE_OFF
        ) == 1
        listener?.onSignalStateChanged(level, inService, airplane)
    }
}
