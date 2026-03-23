package com.antieyes.detector

/**
 * Stores the letterbox transformation parameters so we can map
 * detection coordinates from 640-space back to original frame coords.
 */
data class LetterboxInfo(
    val scale: Float,           // scale factor applied to original image
    val padX: Float,            // horizontal padding added (pixels in 640-space)
    val padY: Float,            // vertical padding added (pixels in 640-space)
    val originalWidth: Int,     // original frame width
    val originalHeight: Int     // original frame height
)
