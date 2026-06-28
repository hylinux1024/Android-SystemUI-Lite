package com.android.systemui.wallpapers

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

class ImageWallpaper : WallpaperService() {

    companion object {
        private const val TAG = "ImageWallpaper"
    }

    override fun onCreateEngine(): Engine = ImageWallpaperEngine()

    inner class ImageWallpaperEngine : Engine() {

        private var wallpaperManager: WallpaperManager? = null
        private var wallpaperBitmap: Bitmap? = null

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            Log.d(TAG, "onCreate")
            wallpaperManager = WallpaperManager.getInstance(this@ImageWallpaper)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.d(TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
            loadWallpaper()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.d(TAG, "onSurfaceDestroyed")
            recycleBitmap()
            try {
                wallpaperManager?.forgetLoadedWallpaper()
            } catch (e: Exception) {
                Log.w(TAG, "forgetLoadedWallpaper failed", e)
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            Log.d(TAG, "onSurfaceRedrawNeeded")
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.d(TAG, "onVisibilityChanged: $visible")
            if (visible) {
                drawFrame()
            }
        }

        private fun loadWallpaper() {
            val wm = wallpaperManager ?: return
            try {
                val drawable = wm.drawable
                if (drawable != null) {
                    val bmp = drawableToBitmap(drawable)
                    if (bmp != null && !bmp.isRecycled) {
                        recycleBitmap()
                        wallpaperBitmap = bmp
                        Log.d(TAG, "Wallpaper loaded: ${bmp.width}x${bmp.height}")
                        drawFrame()
                    } else {
                        Log.w(TAG, "Failed to convert wallpaper to bitmap")
                    }
                } else {
                    Log.w(TAG, "Wallpaper drawable is null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load wallpaper", e)
                try {
                    wm.clear()
                    val drawable = wm.drawable
                    if (drawable != null) {
                        val bmp = drawableToBitmap(drawable)
                        if (bmp != null && !bmp.isRecycled) {
                            recycleBitmap()
                            wallpaperBitmap = bmp
                            drawFrame()
                        }
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to load default wallpaper", e2)
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            val bmp = wallpaperBitmap
            if (bmp == null || bmp.isRecycled) {
                loadWallpaper()
                return
            }
            val surface = holder.surface
            if (!surface.isValid) return

            var canvas: Canvas? = null
            try {
                canvas = surface.lockHardwareCanvas()
            } catch (e: Exception) {
                try {
                    canvas = surface.lockCanvas(null)
                } catch (e2: Exception) {
                    Log.w(TAG, "Unable to lock canvas", e2)
                    return
                }
            }
            try {
                val dest = holder.surfaceFrame
                canvas.drawBitmap(bmp, null, dest, null)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "unlockCanvasAndPost failed", e)
                }
            }
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap? {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            try {
                val width = maxOf(drawable.intrinsicWidth, 1)
                val height = maxOf(drawable.intrinsicHeight, 1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                return bitmap
            } catch (e: Exception) {
                Log.w(TAG, "drawableToBitmap failed", e)
                return null
            }
        }

        private fun recycleBitmap() {
            wallpaperBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            wallpaperBitmap = null
        }
    }
}
