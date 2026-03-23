package com.antieyes.detector

/**
 * Represents a single object detection result.
 */
data class Detection(
    val x1: Float,      // left   (in original frame coords)
    val y1: Float,      // top    (in original frame coords)
    val x2: Float,      // right  (in original frame coords)
    val y2: Float,      // bottom (in original frame coords)
    val confidence: Float,
    val classId: Int,
    val className: String
)
