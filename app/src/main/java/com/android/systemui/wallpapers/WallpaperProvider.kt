package com.android.systemui.wallpapers

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts WallpaperColors (primary / secondary / tertiary + dark/light) from the
 * current system wallpaper and listens for changes. This is scaffolding for future
 * status-bar / monet theming — it exposes the colors via a simple listener bridge
 * and a synchronous [current] getter; consumers are added in a later milestone.
 *
 * Ported from SystemUI-Lite2's com.android.systemui.lite.data.WallpaperProvider,
 * which depended on Compose (androidx.compose.ui.graphics.Color) and kotlinx.coroutines
 * (StateFlow, CoroutineScope). v0.2 has neither, so we replace:
 *   - Compose Color  → android.graphics.Color (framework, ARGB-int based)
 *   - coroutines     → a hand-rolled OnWallpaperColorsChangedListener + a main Handler
 *
 * Color math (faithful to AOSP WallpaperColors.fromBitmap + Tonal luminance):
 *   isDark = (0.299*R + 0.587*G + 0.114*B) / 255 < 0.5
 */
@Singleton
class WallpaperProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "WallpaperProvider"

        // Default dark-teal palette (used when wallpaper colors are unavailable).
        @JvmField
        val DEFAULT_COLORS = WallpaperColorsData(
            primary = 0xFF00ADB5.toInt(),
            secondary = 0xFF393E46.toInt(),
            tertiary = 0xFF222831.toInt(),
            isDark = true
        )
    }

    /** ARGB-int color holder (replaces the Compose-Color based class in Lite2). */
    data class WallpaperColorsData(
        val primary: Int = DEFAULT_COLORS.primary,
        val secondary: Int = DEFAULT_COLORS.secondary,
        val tertiary: Int = DEFAULT_COLORS.tertiary,
        val isDark: Boolean = DEFAULT_COLORS.isDark
    )

    /** Reactive replacement for StateFlow — consumers register/unregister freely. */
    fun interface OnWallpaperColorsChangedListener {
        fun onColorsChanged(data: WallpaperColorsData)
    }

    private val wallpaperManager: WallpaperManager? by lazy {
        try {
            WallpaperManager.getInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WallpaperManager: ${e.message}", e)
            null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // WallpaperManager.OnColorsChangedListener must be registered/unregistered on the
    // main thread (AOSP contract). All callbacks therefore land on mainHandler.
    private val onColorsChangedListener: WallpaperManager.OnColorsChangedListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager.OnColorsChangedListener { _, _ ->
                refreshWallpaperColors()
            }
        } else {
            null
        }

    private val listeners = CopyOnWriteArrayList<OnWallpaperColorsChangedListener>()

    /** Last-known colors. Volatile so reads from any thread see the latest value. */
    @Volatile
    var current: WallpaperColorsData = DEFAULT_COLORS
        private set

    override fun start() {
        Log.d(TAG, "start")
        // Refresh synchronously so a consumer reading `current` immediately sees colors.
        refreshWallpaperColors()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                wallpaperManager?.addOnColorsChangedListener(onColorsChangedListener!!, mainHandler)
                Log.d(TAG, "Wallpaper colors listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register wallpaper listener: ${e.message}", e)
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "stop")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                wallpaperManager?.removeOnColorsChangedListener(onColorsChangedListener!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister wallpaper listener: ${e.message}", e)
            }
        }
        listeners.clear()
    }

    // CoreStartable extras (no-op for now).
    override fun onBootCompleted() {}
    override fun onUserSwitch(newUserId: Int) {}
    override fun onUserSwitchComplete(userId: Int) {}

    /** Register a listener for future color changes. */
    fun addListener(listener: OnWallpaperColorsChangedListener) {
        listeners.add(listener)
    }

    /** Unregister a previously registered listener. */
    fun removeListener(listener: OnWallpaperColorsChangedListener) {
        listeners.remove(listener)
    }

    private fun refreshWallpaperColors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            Log.d(TAG, "WallpaperColors not supported on API < 27")
            return
        }
        try {
            val wm = wallpaperManager ?: return
            val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return

            // getPrimaryColor() returns an android.graphics.Color; .toArgb() → ARGB int.
            val primary = colors.primaryColor.toArgb() or 0xFF000000.toInt()
            val secondary = (colors.secondaryColor?.toArgb() ?: colors.primaryColor.toArgb()) or 0xFF000000.toInt()
            val tertiary = (colors.tertiaryColor?.toArgb() ?: colors.primaryColor.toArgb()) or 0xFF000000.toInt()

            val isDark = calculateIsDark(primary)
            current = WallpaperColorsData(primary, secondary, tertiary, isDark)
            Log.d(TAG, "Wallpaper colors updated: primary=${Integer.toHexString(primary)}, isDark=$isDark")

            // Notify registered listeners (copy-on-write: safe to mutate during iteration).
            listeners.forEach { it.onColorsChanged(current) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallpaper colors: ${e.message}", e)
        }
    }

    private fun calculateIsDark(color: Int): Boolean {
        // Framework Color.red/green/blue extract components as 0..255 floats.
        val luminance = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
        return luminance / 255f < 0.5f
    }
}
