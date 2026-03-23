package com.antieyes.detector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log

private const val TAG = "LETTERBOX"

object LetterboxUtil {

    /**
     * Letterbox-resize [src] to [targetSize] x [targetSize].
     * Preserves aspect ratio, pads remaining area with black.
     * Returns the letterboxed Bitmap and the LetterboxInfo needed to reverse-map coords.
     */
    fun letterbox(src: Bitmap, targetSize: Int): Pair<Bitmap, LetterboxInfo> {
        val srcW = src.width
        val srcH = src.height
        val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        Log.d(TAG, "letterbox: src=${srcW}x${srcH}, scale=$scale, newSize=${newW}x${newH}, pad=($padX,$padY), target=$targetSize")

        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK) // fill with black padding

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(padX, padY)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)

        val info = LetterboxInfo(scale, padX, padY, srcW, srcH)
        return Pair(result, info)
    }

    /**
     * Map a bounding box from 640-space back to original frame coordinates.
     * Input: (x1, y1, x2, y2) in model input space (0..640).
     * Output: (x1, y1, x2, y2) in original frame pixel space.
     */
    fun mapToOriginal(x1: Float, y1: Float, x2: Float, y2: Float, info: LetterboxInfo): FloatArray {
        val ox1 = ((x1 - info.padX) / info.scale).coerceIn(0f, info.originalWidth.toFloat())
        val oy1 = ((y1 - info.padY) / info.scale).coerceIn(0f, info.originalHeight.toFloat())
        val ox2 = ((x2 - info.padX) / info.scale).coerceIn(0f, info.originalWidth.toFloat())
        val oy2 = ((y2 - info.padY) / info.scale).coerceIn(0f, info.originalHeight.toFloat())
        return floatArrayOf(ox1, oy1, ox2, oy2)
    }
}
