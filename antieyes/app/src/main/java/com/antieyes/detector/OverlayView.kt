package com.antieyes.detector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay view that draws bounding boxes and labels.
 * Coordinates are in original frame space and get scaled to view size via fitCenter.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var frameWidth: Int = 1
    private var frameHeight: Int = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
    }

    // Colors for different classes
    private val classColors = intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, Color.rgb(255, 128, 0),
        Color.rgb(128, 0, 255), Color.rgb(0, 200, 100),
        Color.rgb(200, 100, 0)
    )

    fun setDetections(dets: List<Detection>, fWidth: Int, fHeight: Int) {
        detections = dets
        frameWidth = fWidth
        frameHeight = fHeight
        postInvalidate()
    }

    fun clear() {
        detections = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return

        // Compute fitCenter scaling from frame coords to view coords
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = minOf(viewW / frameWidth, viewH / frameHeight)
        val offsetX = (viewW - frameWidth * scale) / 2f
        val offsetY = (viewH - frameHeight * scale) / 2f

        for (det in detections) {
            val color = classColors[det.classId % classColors.size]
            boxPaint.color = color
            textBgPaint.color = color

            val rect = RectF(
                det.x1 * scale + offsetX,
                det.y1 * scale + offsetY,
                det.x2 * scale + offsetX,
                det.y2 * scale + offsetY
            )

            canvas.drawRect(rect, boxPaint)

            val label = "${det.className} ${"%.0f".format(det.confidence * 100)}%"
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize

            canvas.drawRect(rect.left, rect.top - textHeight - 4, rect.left + textWidth + 8, rect.top, textBgPaint)
            canvas.drawText(label, rect.left + 4, rect.top - 4, textPaint)
        }
    }
}
