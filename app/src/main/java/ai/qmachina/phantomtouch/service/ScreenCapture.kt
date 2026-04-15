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
 * Uses a persistent VirtualDisplay + ImageReader at native resolution.
 * On Android 14+, releasing and recreating a VirtualDisplay invalidates
 * the MediaProjection, so we keep it alive and grab frames on demand.
 *
 * The scale parameter controls output JPEG resolution (downscaled from
 * the native capture). Lower scale = smaller base64 payload.
 */
class ScreenCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    fun initialize(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data).also { projection ->
            // Android 14+ requires registering a callback before capture
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        android.util.Log.i("PhantomTouch", "MediaProjection stopped")
                        tearDownDisplay()
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Create persistent VirtualDisplay at native resolution
        setupDisplay()
    }

    private fun setupDisplay() {
        val projection = mediaProjection ?: return

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "PhantomTouch-Capture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        android.util.Log.i("PhantomTouch", "VirtualDisplay created: ${screenWidth}x${screenHeight}")
    }

    private fun tearDownDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun getScreenInfo(): Triple<Int, Int, Int> = Triple(screenWidth, screenHeight, screenDensity)

    /**
     * Capture a screenshot and return as base64-encoded JPEG.
     *
     * Grabs the latest frame from the persistent VirtualDisplay,
     * optionally downscales, and encodes as JPEG.
     *
     * @param scale Resolution multiplier (0.0 - 1.0). Default 0.5.
     * @param quality JPEG quality (0-100). Default 80.
     */
    fun captureBase64(scale: Float = 0.5f, quality: Int = 80): String? {
        val reader = imageReader ?: return null

        // Give the display time to render a fresh frame
        var image: android.media.Image? = null
        for (attempt in 1..3) {
            Thread.sleep(150)
            image = reader.acquireLatestImage()
            if (image != null) break
        }
        if (image == null) return null

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop off row padding if present
            val cropped = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            // Downscale if requested
            val targetW = (screenWidth * scale).toInt()
            val targetH = (screenHeight * scale).toInt()
            val output = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(cropped, targetW, targetH, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            } else {
                cropped
            }

            // Encode to JPEG base64
            val stream = ByteArrayOutputStream()
            output.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            output.recycle()

            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } finally {
            image.close()
        }
    }

    fun release() {
        tearDownDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
