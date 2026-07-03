package com.android.systemui.lite.data

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.content.ContentValues
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Koin singleton that owns the live screen-recording session.
 *
 * The token is not created here — [com.android.systemui.lite.ui.ScreenRecorderActivity]
 * drives the MediaProjection consent dialog and hands the resulting (resultCode, data)
 * back to [onConsentResult]. We then build the MediaRecorder + VirtualDisplay pipeline and
 * write to Movies/ScreenRecordings/ through MediaStore. Because this lives in the Koin
 * singleton scope, the session (and the flag) survives shade close/reopen and is torn down
 * only on explicit stop or process death.
 */
class ScreenRecorderController(private val context: Context) {

    companion object {
        private const val TAG = "ScreenRecorderController"
        private const val VIRTUAL_DISPLAY_NAME = "SystemUI-Lite-ScreenRecord"
        private const val RELATIVE_PATH = "Movies/ScreenRecordings"
        private const val MIME_TYPE = "video/mp4"
        private const val VIDEO_BIT_RATE = 6_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val MAX_RECORDING_MS = 60 * 60 * 1000 // 1 hour safety cap
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // These are mutated only from the main thread. @Volatile so a concurrent read on the
    // StateFlow consumers' threads sees a value consistent with what main last wrote.
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var outputUri: Uri? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var projectionCallback: MediaProjection.Callback? = null

    fun isCurrentlyRecording(): Boolean = _isRecording.value

    /**
     * Handle the result of the MediaProjection consent flow. On grant we synchronously
     * set up the recorder + virtual display and flip [isRecording]. On deny or cancel we
     * simply log and keep the flag inactive so the tile reflects reality (US-009 AC8).
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Screen recording consent denied (resultCode=$resultCode) — tile stays inactive")
            _isRecording.value = false
            return
        }
        try {
            start(resultCode, data)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to launch screen recording: ${e.message}", e)
            runCatching { stop() }
        }
    }

    /**
     * Begin recording. Must be called on the main thread (the caller, the consent
     * Activity, is main-bound). Configures a MediaRecorder writing to a MediaStore entry
     * with IS_PENDING=1, binds a VirtualDisplay backed by the recorder's surface, and
     * starts capture.
     */
    @Synchronized
    private fun start(resultCode: Int, data: Intent) {
        if (_isRecording.value) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val displayName = "screen_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.WIDTH, width)
            put(MediaStore.Video.Media.HEIGHT, height)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null for $RELATIVE_PATH/$displayName")
        outputUri = uri
        Log.d(TAG, "MediaStore entry created for $displayName")

        val parcelFileDescriptor = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("openFileDescriptor returned null for $uri")
        pfd = parcelFileDescriptor

        val rec = createMediaRecorder()
        try {
            configureRecorder(rec, parcelFileDescriptor, width, height)
            rec.prepare()
        } catch (e: Throwable) {
            Log.e(TAG, "MediaRecorder prepare failed: ${e.message}", e)
            runCatching { rec.release() }
            // No useful file to finalize; remove the pending entry.
            runCatching { resolver.delete(uri, null, null) }
            outputUri = null
            pfd = null
            throw e
        }

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IOException("getMediaProjection returned null for resultCode=$resultCode")
        projection = proj

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop — system revoked the capture")
                // System revoked the projection on us (policy / user); tear down cleanly.
                runCatching { stop() }
            }
        }.also { proj.registerCallback(it, mainHandler) }

        val recorderSurface: Surface = rec.surface
        val vDisplay = proj.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME, width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderSurface, null, mainHandler
        )
        if (vDisplay == null) {
            throw IOException("createVirtualDisplay returned null")
        }
        virtualDisplay = vDisplay
        recorder = rec

        // Drive prepare/start on the main looper so the VirtualDisplay callback is wired.
        try {
            rec.start()
            _isRecording.value = true
            Log.d(TAG, "Screen recording started → $displayName")
        } catch (e: Throwable) {
            Log.e(TAG, "MediaRecorder.start failed: ${e.message}", e)
            runCatching { rec.release() }
            releaseProjectionAndDisplay()
            finalizePending(uri, deleteInstead = true)
            outputUri = null
            pfd = null
            throw e
        }
    }

    /**
     * Stop capture, release the recorder/projection/virtual-display, and finalize the
     * MediaStore entry (IS_PENDING=0) so the user can see the file in gallery/files.
     * Safe to call repeatedly.
     */
    @Synchronized
    fun stop() {
        if (!_isRecording.value && recorder == null && projection == null) return
        try {
            recorder?.let { rec ->
                @Suppress("DEPRECATION")
                runCatching { rec.stop() }
                    .onFailure { Log.w(TAG, "MediaRecorder.stop raised (likely no frames captured): ${it.message}") }
                runCatching { rec.reset() }
                runCatching { rec.release() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Recorder teardown error: ${e.message}", e)
        }
        releaseProjectionAndDisplay()
        recorder = null

        val uri = outputUri
        if (uri != null) {
            finalizePending(uri, deleteInstead = false)
            runCatching { pfd?.close() }
        }
        pfd = null
        outputUri = null

        val wasRecording = _isRecording.value
        _isRecording.value = false
        Log.d(TAG, "Screen recording stopped${if (wasRecording) " and MediaStore entry finalized" else ""}")
    }

    // ---- internal helpers ----

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun configureRecorder(
        rec: MediaRecorder,
        fd: ParcelFileDescriptor,
        width: Int,
        height: Int
    ) {
        val micGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                // Encoder must match a source we actually attached.
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            } catch (e: Throwable) {
                Log.w(TAG, "Audio source unavailable (${e.message}) — recording video-only")
            }
        } else {
            Log.d(TAG, "RECORD_AUDIO not granted — recording video-only")
        }
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(VIDEO_FRAME_RATE)
        rec.setVideoEncodingBitRate(VIDEO_BIT_RATE)
        rec.setMaxDuration(MAX_RECORDING_MS)
        rec.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                Log.d(TAG, "Max duration reached — auto-stopping")
                runCatching { stop() }
            }
        }
        rec.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaRecorder onError: what=$what extra=$extra")
            runCatching { stop() }
        }
        rec.setOutputFile(fd.fileDescriptor)
    }

    private fun releaseProjectionAndDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projectionCallback?.let { cb ->
            runCatching { projection?.unregisterCallback(cb) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
    }

    /**
     * Finalize a MediaStore "pending" entry. On failure we leave the placeholder in place
     * so the file is still visible (a 0-byte entry is better than a missing one and it
     * costs nothing). On path error we delete the broken placeholder.
     */
    private fun finalizePending(uri: Uri, deleteInstead: Boolean) {
        val resolver = context.contentResolver
        try {
            if (deleteInstead) {
                resolver.delete(uri, null, null)
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DURATION, 0L)
            }
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            Log.e(TAG, "MediaStore finalize failed: ${e.message}", e)
        }
    }
}
