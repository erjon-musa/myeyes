package com.antieyes.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CAMERA_SOURCE"

/**
 * Uses CameraX to capture frames from the phone's rear camera
 * and feeds them into the same AtomicReference<Bitmap> pattern
 * as MjpegStreamReader, so the inference loop works identically.
 */
class CameraFrameSource {

    val latestFrame = AtomicReference<Bitmap?>(null)

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    @Volatile
    var isRunning = false
        private set

    private var frameCount = 0L
    private var startTimeMs = 0L

    /**
     * Start camera preview and frame analysis.
     * [previewView] - optional PreviewView to show camera preview (can be null for headless)
     * [lifecycleOwner] - the activity/fragment lifecycle
     */
    fun start(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        if (isRunning) {
            Log.w(TAG, "Camera already running, ignoring start()")
            return
        }

        frameCount = 0
        startTimeMs = System.currentTimeMillis()

        Log.i(TAG, "┌─────────────────────────────────────────────")
        Log.i(TAG, "│ STARTING CameraX frame source")
        Log.i(TAG, "└─────────────────────────────────────────────")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Preview use case (optional, for on-screen display)
                val preview = Preview.Builder()
                    .build()
                    .also { prev ->
                        previewView?.let { prev.setSurfaceProvider(it.surfaceProvider) }
                    }

                // ImageAnalysis use case — this is where we capture frames
                analysisExecutor = Executors.newSingleThreadExecutor()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor!!) { imageProxy ->
                    processFrame(imageProxy)
                }

                // Select rear camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind any previous use cases and bind new ones
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                isRunning = true
                Log.i(TAG, "CameraX bound successfully — rear camera, 640x480 target, KEEP_ONLY_LATEST")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                latestFrame.set(bitmap)
                frameCount++

                if (frameCount <= 3 || frameCount % 100 == 0L) {
                    val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
                    val fps = if (elapsedSec > 0) frameCount / elapsedSec else 0.0
                    Log.i(TAG, "  Frame #$frameCount: ${bitmap.width}x${bitmap.height}, " +
                            "rotation=${imageProxy.imageInfo.rotationDegrees}°, " +
                            "cameraFPS=${"%.1f".format(fps)}")
                }
            } else {
                Log.w(TAG, "  Frame #$frameCount: Failed to convert ImageProxy to Bitmap")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera frame: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert an ImageProxy (YUV_420_888) to a Bitmap.
     * Also applies rotation correction based on the image's rotation degrees.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U for NV21 format
        val vData = ByteArray(vSize)
        val uData = ByteArray(uSize)
        vBuffer.get(vData)
        uBuffer.get(uData)

        // For NV21, after Y data comes V,U interleaved
        // But when pixelStride > 1, data is already interleaved
        val pixelStride = imageProxy.planes[1].pixelStride
        if (pixelStride == 2) {
            // Already interleaved (common on most devices)
            // V plane already contains V,U interleaved in NV21-like format
            // Actually planes[1] is U, planes[2] is V for YUV_420_888
            // For NV21 we need V first then U
            vBuffer.rewind()
            vBuffer.get(nv21, ySize, vSize)
        } else {
            // Planar format, need to interleave manually
            var offset = ySize
            for (i in 0 until uSize) {
                nv21[offset++] = vData[i]
                nv21[offset++] = uData[i]
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val outStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, outStream)
        val jpegBytes = outStream.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

        // Apply rotation if needed
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            bitmap = rotated
        }

        if (frameCount <= 1) {
            Log.d(TAG, "  imageProxyToBitmap: YUV ${imageProxy.width}x${imageProxy.height} " +
                    "pixelStride=$pixelStride rotation=${rotation}° → " +
                    "Bitmap ${bitmap.width}x${bitmap.height}")
        }

        return bitmap
    }

    fun stop() {
        Log.i(TAG, "Stopping CameraX frame source...")
        isRunning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        latestFrame.set(null)
        Log.i(TAG, "CameraX stopped. Total frames: $frameCount")
    }
}
