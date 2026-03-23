package com.antieyes.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.equationl.paddleocr4android.CpuPowerMode
import com.equationl.paddleocr4android.OCR
import com.equationl.paddleocr4android.OcrConfig
import com.equationl.paddleocr4android.bean.OcrResult

private const val TAG = "PADDLE_OCR"

/**
 * Wraps the paddleocr4android library for on-device text recognition.
 *
 * Uses PP-OCRv4 models via Paddle-Lite for fast, accurate OCR.
 * Must call [init] before [recognize], and [close] when done.
 */
class PaddleOcrEngine(private val context: Context) {

    private val ocr = OCR(context)

    @Volatile
    var isReady = false
        private set

    /**
     * Initialize models synchronously. Call from a background thread.
     */
    fun init(): Boolean {
        Log.i(TAG, "Initializing PaddleOCR engine...")
        return try {
            val config = OcrConfig().apply {
                modelPath = "models/ocr"
                clsModelFilename = "cls.nb"
                detModelFilename = "det.nb"
                recModelFilename = "rec.nb"
                isRunDet = true
                isRunCls = true
                isRunRec = true
                cpuPowerMode = CpuPowerMode.LITE_POWER_FULL
                isDrwwTextPositionBox = false
            }

            val result = ocr.initModelSync(config)
            result.fold(
                onSuccess = {
                    isReady = true
                    Log.i(TAG, "PaddleOCR initialized successfully")
                    true
                },
                onFailure = { e ->
                    Log.e(TAG, "PaddleOCR init failed: ${e.message}", e)
                    false
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "PaddleOCR init exception: ${e.message}", e)
            false
        }
    }

    /**
     * Run OCR on a bitmap and return the recognized text.
     * Returns null if OCR fails or engine not ready.
     */
    fun recognize(bitmap: Bitmap): String? {
        if (!isReady) {
            Log.w(TAG, "PaddleOCR not initialized, skipping recognition")
            return null
        }

        return try {
            Log.d(TAG, "Running OCR on bitmap ${bitmap.width}x${bitmap.height}...")
            val resultWrapper = ocr.runSync(bitmap)
            resultWrapper.fold(
                onSuccess = { ocrResult ->
                    val text = ocrResult.simpleText?.trim()
                    Log.i(TAG, "OCR SUCCESS: \"$text\" (inference: ${ocrResult.inferenceTime}ms, isEmpty=${text.isNullOrEmpty()})")
                    if (text.isNullOrEmpty()) {
                        Log.w(TAG, "OCR returned empty text")
                        null
                    } else {
                        text
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR recognition failed: ${e.message}", e)
                    null
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition exception: ${e.message}", e)
            null
        }
    }

    fun close() {
        Log.i(TAG, "Closing PaddleOCR engine")
        isReady = false
    }
}
