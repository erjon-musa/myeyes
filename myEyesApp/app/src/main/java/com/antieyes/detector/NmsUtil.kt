package com.antieyes.detector

import android.util.Log

private const val TAG = "NMS"

object NmsUtil {

    /**
     * Per-class greedy NMS.
     * Sorts detections by confidence descending, suppresses overlapping boxes
     * of the same class with IoU > [iouThreshold].
     */
    fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Group by class
        val byClass = detections.groupBy { it.classId }
        val result = mutableListOf<Detection>()

        for ((classId, dets) in byClass) {
            val sorted = dets.sortedByDescending { it.confidence }
            val keep = mutableListOf<Detection>()

            for (det in sorted) {
                var dominated = false
                for (kept in keep) {
                    if (iou(det, kept) > iouThreshold) {
                        dominated = true
                        break
                    }
                }
                if (!dominated) {
                    keep.add(det)
                }
            }

            Log.d(TAG, "  class=$classId: ${sorted.size} candidates -> ${keep.size} after NMS (iou=$iouThreshold)")
            result.addAll(keep)
        }

        Log.d(TAG, "NMS total: ${detections.size} in -> ${result.size} out")
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)
        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val aArea = (a.x2 - a.x1) * (a.y2 - a.y1)
        val bArea = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = aArea + bArea - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
