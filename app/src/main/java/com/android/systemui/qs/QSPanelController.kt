package com.android.systemui.qs

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.SeekBar
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.qs.tiles.AirplaneTile
import com.android.systemui.qs.tiles.AutoRotateTile
import com.android.systemui.qs.tiles.BluetoothTile
import com.android.systemui.qs.tiles.DndTile
import com.android.systemui.qs.tiles.FlashlightTile
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.qs.tiles.LocationTile
import com.android.systemui.qs.tiles.WifiTile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QSPanelController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "QSPanelController"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tiles = mutableListOf<QSTileBase>()
    private var brightnessSlider: SeekBar? = null

    override fun start() {
        Log.i(TAG, "Starting QS panel controller")
        initTiles()
    }

    override fun stop() {
        Log.i(TAG, "Stopping QS panel controller")
        tiles.forEach { it.destroy() }
        tiles.clear()
    }

    private fun initTiles() {
        tiles.add(WifiTile(context))
        tiles.add(BluetoothTile(context))
        tiles.add(FlashlightTile(context))
        tiles.add(AirplaneTile(context))
        tiles.add(AutoRotateTile(context))
        tiles.add(DndTile(context))
        tiles.add(LocationTile(context))
        tiles.add(HotspotTile(context))
    }

    fun setupTiles(gridLayout: GridLayout) {
        gridLayout.removeAllViews()
        for (tile in tiles) {
            val tileView = LayoutInflater.from(context).inflate(R.layout.qs_tile, gridLayout, false)
            tile.bindView(tileView)
            gridLayout.addView(tileView)
        }
    }

    fun setupBrightnessSlider(slider: SeekBar) {
        brightnessSlider = slider

        val currentBrightness = try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
        slider.max = 255
        slider.progress = currentBrightness

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setBrightness(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setBrightness(level: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level.coerceIn(0, 255)
            )
            val intent = Intent("com.android.systemui.action.SET_BRIGHTNESS")
            intent.putExtra("brightness", level)
            context.sendBroadcast(intent)
            Log.d(TAG, "Brightness set to $level")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
    }
}
