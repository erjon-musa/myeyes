package com.antieyes.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ESP32_STREAM"

/**
 * Reads an MJPEG stream from an ESP32 camera over HTTP.
 * Parses multipart boundaries, decodes JPEG frames, and stores the
 * latest frame in an AtomicReference (latest-frame-wins, drops old frames).
 */
class MjpegStreamReader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Latest decoded frame (rotated). Consumers read this; old frames are dropped. */
    val latestFrame = AtomicReference<Bitmap?>(null)

    private var job: Job? = null
    private var frameCount = 0L
    private var startTimeMs = 0L

    @Volatile
    var isConnected = false
        private set

    /**
     * Start reading the MJPEG stream from [url] on a background coroutine.
     */
    fun connect(url: String, scope: CoroutineScope) {
        disconnect()
        frameCount = 0
        startTimeMs = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "┌─────────────────────────────────────────────")
            Log.i(TAG, "│ CONNECTING to MJPEG stream: $url")
            Log.i(TAG, "└─────────────────────────────────────────────")

            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error: ${response.code} ${response.message}")
                    return@launch
                }

                val contentType = response.header("Content-Type", "") ?: ""
                Log.i(TAG, "Response Content-Type: $contentType")

                val boundary = extractBoundary(contentType)
                Log.i(TAG, "Extracted boundary: '$boundary'")

                isConnected = true
                val body = response.body ?: run {
                    Log.e(TAG, "Response body is null")
                    return@launch
                }

                val inputStream = BufferedInputStream(body.byteStream(), 64 * 1024)
                var frameData = ByteArray(102400) // Pre-allocate 100KB

                Log.i(TAG, "Starting MJPEG frame read loop...")

                while (isActive) {
                    // Read lines until we find Content-Length
                    var contentLength = -1
                    while (isActive) {
                        val line = readLine(inputStream)?.trim() ?: break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substring(15).trim().toIntOrNull() ?: -1
                        }
                        if (line.isEmpty() && contentLength > 0) {
                            break // Empty line after headers = body starts
                        }
                    }

                    if (contentLength <= 0 || !isActive) break

                    // Grow buffer if needed
                    if (contentLength > frameData.size) {
                        frameData = ByteArray(contentLength + 1024)
                    }

                    // Read exactly contentLength bytes
                    var totalRead = 0
                    while (totalRead < contentLength && isActive) {
                        val read = inputStream.read(frameData, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }

                    if (totalRead == contentLength) {
                        // We have to copy just the valid bytes since decodeAndStore expects exact length array
                        val jpegBytes = frameData.copyOf(contentLength)
                        decodeAndStore(jpegBytes)
                    }
                }

                inputStream.close()
                response.close()

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.i(TAG, "Stream reading cancelled (disconnect)")
                } else {
                    Log.e(TAG, "Stream error: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            } finally {
                isConnected = false
                Log.i(TAG, "Stream reader stopped. Total frames decoded: $frameCount")
            }
        }
    }

    private fun decodeAndStore(jpegBytes: ByteArray) {
        frameCount++

        // Decode to a mutable bitmap so we own the pixel data
        val opts = BitmapFactory.Options().apply {
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)

        if (bitmap == null) {
            Log.w(TAG, "  Frame #$frameCount: Failed to decode JPEG (${jpegBytes.size} bytes)")
            return
        }

        // Rotate the bitmap by 90 degrees clockwise (no stretch, no crop)
        val rotatedBitmap = rotateBitmap(bitmap, 90f)
        
        // Recycle the original bitmap since we created a rotated copy
        bitmap.recycle()

        // Swap in the new frame; recycle any unconsumed old frame
        val oldFrame = latestFrame.getAndSet(rotatedBitmap)
        try { oldFrame?.recycle() } catch (_: Exception) {}

        if (frameCount <= 3 || frameCount % 100 == 0L) {
            val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
            val fps = if (elapsedSec > 0) frameCount / elapsedSec else 0.0
            Log.i(TAG, "  Frame #$frameCount: ${rotatedBitmap.width}x${rotatedBitmap.height} (rotated 90°), " +
                    "JPEG=${jpegBytes.size} bytes, streamFPS=%.1f".format(fps))
        }
    }

    /**
     * Rotate a bitmap by the specified degrees without stretching or cropping.
     * Returns a new bitmap with dimensions swapped for 90/270 degree rotations.
     */
    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Read a line from the stream (up to \n).
     */
    private fun readLine(stream: java.io.InputStream): String? {
        val sb = StringBuilder(80)
        var c: Int
        while (stream.read().also { c = it } != -1) {
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return null // EOF
    }

    private fun extractBoundary(contentType: String): String {
        val parts = contentType.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("boundary=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim()
            }
        }
        return "frame"
    }

    /**
     * Disconnect from the stream, cancel coroutine, clean up.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting stream reader...")
        isConnected = false
        job?.cancel()
        job = null
        val remaining = latestFrame.getAndSet(null)
        try { remaining?.recycle() } catch (_: Exception) {}
    }
}
