package com.example.remoteacess.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.remoteacess.net.FrameServer
import java.io.ByteArrayOutputStream

/**
 * Foreground service that owns the MediaProjection session. It mirrors the
 * screen into an ImageReader, compresses each frame to JPEG and pushes it to
 * the [FrameServer] for any connected viewer.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val server = FrameServer(PORT)

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Missing projection permission result")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startCapture(resultCode, data)
        isRunning = true
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Could not obtain MediaProjection")
            stopSelf()
            return
        }
        projection = mp

        captureThread = HandlerThread("ScreenCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // Required on Android 14+: register a callback before using the projection.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Projection stopped by system/user")
                stopSelf()
            }
        }, captureHandler)

        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / SCALE).coerceAtLeast(1)
        val height = (metrics.heightPixels / SCALE).coerceAtLeast(1)
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                    lastFrameAt = now
                    server.sendFrame(imageToJpeg(image, width, height))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped: ${e.message}")
            } finally {
                image.close()
            }
        }, captureHandler)

        server.start()

        virtualDisplay = mp.createVirtualDisplay(
            "RemoteAccess",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler,
        )
        Log.i(TAG, "Capturing at ${width}x$height")
    }

    /** RGBA_8888 Image -> cropped Bitmap -> JPEG bytes. */
    private fun imageToJpeg(image: Image, width: Int, height: Int): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val frame = if (paddedWidth != width) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

        if (frame !== bitmap) frame.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen Sharing",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen is being shared")
            .setContentText("Your screen is visible to a connected viewer")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
            server.stop()
            captureThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIF_ID = 1

        /** Downscale factor applied to the capture to cut bandwidth. */
        private const val SCALE = 2
        private const val JPEG_QUALITY = 50
        private const val FRAME_INTERVAL_MS = 100L // ~10 fps

        const val PORT = 8888
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.remoteacess.STOP"

        @Volatile
        var isRunning = false
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
