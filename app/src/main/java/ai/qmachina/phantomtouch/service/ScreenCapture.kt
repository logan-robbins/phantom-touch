package ai.qmachina.phantomtouch.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Screenshot capture via MediaProjection.
 * 
 * Lifecycle:
 * 1. MainActivity requests MediaProjection permission → gets resultCode + data Intent
 * 2. CaptureService starts as foreground service, creates MediaProjection
 * 3. On screenshot request: create ImageReader → VirtualDisplay → grab frame → tear down
 * 
 * The scale parameter controls output resolution (0.5 = half resolution).
 * Lower scale = smaller base64 payload = faster LLM ingestion.
 * At 0.5 scale on a 1080p device, screenshots are ~100-200KB JPEG.
 */
class ScreenCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    fun initialize(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    fun getScreenInfo(): Triple<Int, Int, Int> = Triple(screenWidth, screenHeight, screenDensity)

    /**
     * Capture a screenshot and return as base64-encoded JPEG.
     * 
     * @param scale Resolution multiplier (0.0 - 1.0). Default 0.5.
     *              Use lower values for faster transfer to remote LLM.
     *              Use 1.0 when you need pixel-precise coordinate mapping.
     * @param quality JPEG quality (0-100). Default 80.
     */
    fun captureBase64(scale: Float = 0.5f, quality: Int = 80): String? {
        val projection = mediaProjection ?: return null

        val targetW = (screenWidth * scale).toInt()
        val targetH = (screenHeight * scale).toInt()

        val imageReader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)

        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "PhantomTouch-Screenshot",
                targetW, targetH, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null, null
            )

            // Give the system time to render a frame, retry if null
            var image: android.media.Image? = null
            for (attempt in 1..3) {
                Thread.sleep(150)
                image = imageReader.acquireLatestImage()
                if (image != null) break
            }
            if (image == null) return null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * targetW

                val bitmap = Bitmap.createBitmap(
                    targetW + rowPadding / pixelStride,
                    targetH,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop off any padding
                val cropped = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, targetW, targetH).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                // Encode to JPEG base64
                val stream = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                cropped.recycle()

                return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } finally {
                image.close()
            }
        } finally {
            virtualDisplay?.release()
            imageReader.close()
        }
    }

    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
    }
}
