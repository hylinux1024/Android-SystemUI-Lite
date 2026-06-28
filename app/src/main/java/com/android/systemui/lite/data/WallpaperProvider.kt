package com.android.systemui.lite.data

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WallpaperProvider(
    private val context: Context
) {
    companion object {
        private const val TAG = "WallpaperProvider"
    }

    data class WallpaperColorsData(
        val primary: Color = Color(0xFF00ADB5),
        val secondary: Color = Color(0xFF393E46),
        val tertiary: Color = Color(0xFF222831),
        val isDark: Boolean = true
    )

    private val wallpaperManager: WallpaperManager? by lazy {
        try {
            WallpaperManager.getInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WallpaperManager: ${e.message}", e)
            null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _wallpaperColors = MutableStateFlow(WallpaperColorsData())
    val wallpaperColors: StateFlow<WallpaperColorsData> = _wallpaperColors.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val onColorsChangedListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager.OnColorsChangedListener { _, _ ->
                scope.launch { refreshWallpaperColors() }
            }
        } else {
            null
        }

    fun start() {
        Log.d(TAG, "Starting WallpaperProvider...")
        scope.launch { refreshWallpaperColors() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                wallpaperManager?.addOnColorsChangedListener(
                    onColorsChangedListener!!,
                    mainHandler
                )
                _isListening.value = true
                Log.d(TAG, "Wallpaper colors listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register wallpaper listener: ${e.message}", e)
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping WallpaperProvider...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                wallpaperManager?.removeOnColorsChangedListener(
                    onColorsChangedListener!!
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister wallpaper listener: ${e.message}", e)
            }
        }
        _isListening.value = false
        scope.cancel()
    }

    private fun refreshWallpaperColors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            Log.d(TAG, "WallpaperColors not supported on API < 27")
            return
        }
        try {
            val wm = wallpaperManager ?: return
            val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                val primary = Color(colors.primaryColor.toArgb() or 0xFF000000.toInt())
                val secondary = Color(colors.secondaryColor?.toArgb() ?: colors.primaryColor.toArgb() or 0xFF000000.toInt())
                val tertiary = Color(colors.tertiaryColor?.toArgb() ?: colors.primaryColor.toArgb() or 0xFF000000.toInt())

                val isDark = calculateIsDark(primary)
                _wallpaperColors.value = WallpaperColorsData(
                    primary = primary,
                    secondary = secondary,
                    tertiary = tertiary,
                    isDark = isDark
                )
                Log.d(TAG, "Wallpaper colors updated: primary=$primary, isDark=$isDark")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallpaper colors: ${e.message}", e)
        }
    }

    private fun calculateIsDark(color: Color): Boolean {
        val red = color.red
        val green = color.green
        val blue = color.blue
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue)
        return luminance < 0.5f
    }
}
