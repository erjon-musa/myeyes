package com.antieyes.detector

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TTS_ANNOUNCER"

/**
 * Wraps Android TextToSpeech to announce detections out loud.
 *
 * - Simple classes get a fixed spoken prompt with a per-class cooldown.
 * - "Important Text" and Canadian dollar classes trigger on-device PaddleOCR
 *   on the cropped detection region, then speak the recognised text / estimated total.
 */
class DetectionAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    /** PaddleOCR engine for on-device text recognition */
    private val paddleOcr = PaddleOcrEngine(context)

    /** Single-thread executor for running PaddleOCR off the main thread */
    private val ocrExecutor = Executors.newSingleThreadExecutor()

    /** Dictionary-based word filter — only real words get spoken */
    private val wordFilter = WordFilter(context, enableFuzzy = false)

    /** ESP32 audio routing */
    var esp32Url: String? = null
        set(value) {
            field = value
            if (value != null) Log.i(TAG, "Audio routed to ESP32: $value")
            else Log.i(TAG, "Audio routed to phone speaker")
        }
    
    private val audioSender = Esp32AudioSender()
    private val appContext = context.applicationContext

    /** Per-class last-spoken timestamp (ms) */
    private val lastSpokenAt = HashMap<String, Long>()

    /** Minimum gap between repeats of the same class (ms) */
    var cooldownMs: Long = 3_000L

    /** Longer cooldown for OCR classes so we don't spam while processing */
    var ocrCooldownMs: Long = 6_000L

    /** True while TTS is actively speaking an OCR utterance — blocks new OCR triggers */
    private val isSpeakingOcr = AtomicBoolean(false)

    // ── Fixed prompt mapping (non-OCR classes) ───────────────────────

    private val promptMap = mapOf(
        "Crosswalks"             to "Crosswalk ahead",
        "car"                    to "Careful, car ahead",
        "green pedestrian light" to "Green pedestrian light ahead",
        "red pedestrian light"   to "Red pedestrian light ahead",
        "stop"                   to "Stop sign ahead",
        "5CanadianDollar"        to "5 dollars",
        "10CanadianDollar"       to "10 dollars",
        "20CanadianDollar"       to "20 dollars",
        "50CanadianDollar"       to "50 dollars",
        "100CanadianDollar"      to "100 dollars"
    )

    /** Special classes that need contextual logic */
    private val crosswalkClasses = setOf("Crosswalks")
    private val trafficLightClasses = setOf("green pedestrian light", "red pedestrian light")

    /** Classes that need OCR before speaking */
    private val ocrClasses = setOf(
        "Important Text"
    )

    companion object {
        /** Utterance ID prefix used to identify OCR speech for completion tracking */
        private const val OCR_UTTERANCE_PREFIX = "ocr_"
    }

    // ── TTS init callback ────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                      result != TextToSpeech.LANG_NOT_SUPPORTED
            if (isReady) {
                Log.i(TAG, "TTS engine ready (Locale.US)")
                setupAudioRouting()
            } else {
                Log.w(TAG, "TTS language not supported, status=$result")
            }
        } else {
            Log.e(TAG, "TTS init failed, status=$status")
        }
    }

    private fun setupAudioRouting() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(OCR_UTTERANCE_PREFIX) == true) {
                    isSpeakingOcr.set(false)
                    Log.d(TAG, "OCR utterance error, unlocking OCR gate")
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(OCR_UTTERANCE_PREFIX) == true) {
                    isSpeakingOcr.set(false)
                    Log.d(TAG, "OCR utterance finished, unlocking OCR gate")
                }

                if (utteranceId == null || esp32Url == null) return
                
                val wavFile = File(appContext.cacheDir, "tts_$utteranceId.wav")
                if (wavFile.exists() && wavFile.length() > 0) {
                    val urlToSendTo = esp32Url!!
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val success = audioSender.sendWavFile(urlToSendTo, wavFile)
                            if (!success) {
                                Log.w(TAG, "Failed to send audio to ESP32: $utteranceId")
                            }
                        } finally {
                            wavFile.delete()
                        }
                    }
                }
            }
        })
    }

    /**
     * Initialize PaddleOCR models. Call from a background thread (e.g. in a coroutine).
     */
    fun initOcr() {
        ocrExecutor.execute {
            paddleOcr.init()
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Call after every inference pass.
     *
     * @param detections  list of detections from this frame
     * @param frameBitmap the frame bitmap (used to crop regions for OCR).
     *                    Caller must NOT recycle it until this method returns.
     */
    fun announce(detections: List<Detection>, frameBitmap: Bitmap?) {
        if (!isReady || detections.isEmpty()) return

        val now = System.currentTimeMillis()

        val crosswalkDetection = detections.find { it.className in crosswalkClasses }
        val hasCrosswalk = crosswalkDetection != null
        val greenLight = detections.find { it.className == "green pedestrian light" }
        val redLight = detections.find { it.className == "red pedestrian light" }
        val cars = detections.filter { it.className == "car" }

        val carInCrosswalk = if (hasCrosswalk && cars.isNotEmpty()) {
            cars.any { car -> boundingBoxesOverlap(crosswalkDetection!!, car) }
        } else {
            false
        }

        var greenLightHandled = false

        // Green pedestrian light takes priority — works with or without crosswalk
        if (greenLight != null) {
            val greenKey = "green_light_safety"
            val last = lastSpokenAt[greenKey] ?: 0L

            if (now - last >= cooldownMs) {
                if (cars.isNotEmpty()) {
                    speak("Green pedestrian light, car crossing, not safe to cross", "green_car_warning")
                    lastSpokenAt[greenKey] = now
                    lastSpokenAt["green pedestrian light"] = now
                    lastSpokenAt["car"] = now
                    if (hasCrosswalk) {
                        lastSpokenAt["crosswalk_safety"] = now
                        lastSpokenAt["Crosswalks"] = now
                    }
                    Log.d(TAG, "Speaking: Green light + car - NOT SAFE TO CROSS")
                } else {
                    speak("Green pedestrian light, safe to cross", "green_safe")
                    lastSpokenAt[greenKey] = now
                    lastSpokenAt["green pedestrian light"] = now
                    if (hasCrosswalk) {
                        lastSpokenAt["crosswalk_safety"] = now
                        lastSpokenAt["Crosswalks"] = now
                    }
                    Log.d(TAG, "Speaking: Green light, no car - SAFE TO CROSS")
                }
                greenLightHandled = true
            }
        }

        // Handle remaining crosswalk announcements (only if green light wasn't just spoken)
        if (hasCrosswalk && !greenLightHandled) {
            val crosswalkKey = "crosswalk_safety"
            val last = lastSpokenAt[crosswalkKey] ?: 0L

            if (now - last >= cooldownMs) {
                when {
                    carInCrosswalk -> {
                        speak("Car in crosswalk, careful", "crosswalk_car_warning")
                        lastSpokenAt[crosswalkKey] = now
                        lastSpokenAt["Crosswalks"] = now
                        lastSpokenAt["car"] = now
                        Log.d(TAG, "Speaking: CAR IN CROSSWALK - WARNING")
                    }
                    redLight != null -> {
                        speak("Red pedestrian light ahead, NOT safe to cross. Wait...", "crosswalk_unsafe")
                        lastSpokenAt[crosswalkKey] = now
                        lastSpokenAt["red pedestrian light"] = now
                        lastSpokenAt["Crosswalks"] = now
                        Log.d(TAG, "Speaking: Crosswalk with red light - NOT SAFE TO CROSS")
                    }
                    else -> {
                        val crosswalkLast = lastSpokenAt["Crosswalks"] ?: 0L
                        if (now - crosswalkLast >= cooldownMs) {
                            speak("Crosswalk ahead", "det_Crosswalks")
                            lastSpokenAt["Crosswalks"] = now
                            Log.d(TAG, "Speaking: Crosswalk detected (no traffic light visible)")
                        }
                    }
                }
            }
        }

        // Handle all other detections individually
        for (det in detections) {
            val cls = det.className

            if (greenLightHandled && (cls == "green pedestrian light" || cls == "car")) continue
            if (greenLightHandled && hasCrosswalk && cls in crosswalkClasses) continue
            if (!greenLightHandled && hasCrosswalk && (cls in crosswalkClasses || cls in trafficLightClasses)) continue
            if (!greenLightHandled && carInCrosswalk && cls == "car") continue

            if (cls in ocrClasses) {
                // OCR-based announcement — skip if TTS is still reading previous OCR
                if (isSpeakingOcr.get()) continue
                val last = lastSpokenAt[cls] ?: 0L
                if (now - last >= ocrCooldownMs) {
                    lastSpokenAt[cls] = now
                    announceWithOcr(det, frameBitmap)
                }
            } else {
                // Fixed-prompt announcement
                val last = lastSpokenAt[cls] ?: 0L
                if (now - last >= cooldownMs) {
                    val prompt = promptMap[cls] ?: "$cls detected"
                    speak(prompt, "det_$cls")
                    lastSpokenAt[cls] = now
                    Log.d(TAG, "Speaking: \"$prompt\" (class=$cls, conf=${"%.2f".format(det.confidence)})")
                }
            }
        }
    }

    /**
     * Check if two bounding boxes overlap (intersect).
     * Returns true if the boxes share any area.
     */
    private fun boundingBoxesOverlap(det1: Detection, det2: Detection): Boolean {
        // Check if one box is completely to the left/right/above/below the other
        // If any of these is true, they don't overlap
        if (det1.x2 < det2.x1 || det2.x2 < det1.x1) return false  // one is to the left of the other
        if (det1.y2 < det2.y1 || det2.y2 < det1.y1) return false  // one is above the other
        
        // If we get here, the boxes must overlap
        return true
    }

    // ── OCR pipeline (PaddleOCR) ────────────────────────────────────

    private fun announceWithOcr(det: Detection, frameBitmap: Bitmap?) {
        val isMoney = det.className.endsWith("CanadianDollar")
        if (frameBitmap == null || frameBitmap.isRecycled) {
            val fallback = if (isMoney) "Money detected" else "Text detected"
            speak(fallback, "det_${det.className}")
            return
        }

        val crop = cropDetection(det, frameBitmap) ?: run {
            val fallback = if (isMoney) "Money detected" else "Text detected"
            speak(fallback, "det_${det.className}")
            return
        }

        if (isMoney) {
            speak("Money detected...", "det_intro_${det.className}")
        }

        ocrExecutor.execute {
            try {
                Log.d(TAG, "Starting OCR for ${det.className}, crop size: ${crop.width}x${crop.height}")
                val fullText = paddleOcr.recognize(crop)
                Log.i(TAG, "PaddleOCR raw result for ${det.className}: \"$fullText\"")

                if (!fullText.isNullOrEmpty()) {
                    if (isMoney) {
                        val estimate = estimateMoney(fullText, det.className)
                        Log.i(TAG, "Money estimate: \"$estimate\"")
                        speakOcr(estimate, "${OCR_UTTERANCE_PREFIX}money_${System.nanoTime()}")
                    } else {
                        val cleanedText = fullText.replace(Regex("[^a-zA-Z0-9.,\\s]"), " ").trim()
                        if (cleanedText.isNotBlank()) {
                            speakOcr("It says: $cleanedText", "${OCR_UTTERANCE_PREFIX}text_${System.nanoTime()}")
                            Log.i(TAG, "SPEAKING raw text: \"$cleanedText\"")
                        } else {
                            Log.w(TAG, "OCR text was blank after cleaning: \"$fullText\"")
                        }
                    }
                } else {
                    Log.w(TAG, "PaddleOCR returned null or empty text for ${det.className}")
                    if (!isMoney) {
                        speakOcr("Text detected, but could not read it clearly", "${OCR_UTTERANCE_PREFIX}fail_${System.nanoTime()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PaddleOCR failed for ${det.className}: ${e.message}", e)
            } finally {
                try { crop.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Crop the detection bounding box from the frame bitmap.
     * Returns null if the box is invalid or too small.
     */
    private fun cropDetection(det: Detection, frame: Bitmap): Bitmap? {
        val fw = frame.width
        val fh = frame.height

        // Clamp to frame bounds
        val left   = det.x1.toInt().coerceIn(0, fw - 1)
        val top    = det.y1.toInt().coerceIn(0, fh - 1)
        val right  = det.x2.toInt().coerceIn(left + 1, fw)
        val bottom = det.y2.toInt().coerceIn(top + 1, fh)

        val w = right - left
        val h = bottom - top
        if (w < 10 || h < 10) return null  // too small to OCR

        return try {
            Bitmap.createBitmap(frame, left, top, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to crop detection: ${e.message}")
            null
        }
    }

    /**
     * Parse OCR text to double check the amount detected by the model.
     * Looks for keywords matching the expected denomination.
     * Returns a spoken string like "Verified 20 Canadian dollars".
     */
    private fun estimateMoney(ocrText: String, className: String): String {
        val text = ocrText.lowercase()
        
        // Extract expected amount from class name (e.g., "20CanadianDollar" -> 20)
        val expectedAmountStr = className.replace("CanadianDollar", "")
        val expectedAmount = expectedAmountStr.toIntOrNull() ?: 0

        // Common representations for the expected amount
        val keywords = when (expectedAmount) {
            100 -> listOf("100", "hundred")
            50 -> listOf("50", "fifty")
            20 -> listOf("20", "twenty")
            10 -> listOf("10", "ten")
            5 -> listOf("5", "five")
            else -> emptyList()
        }

        var verified = false

        for (word in keywords) {
            // Match word boundaries to prevent matching "one" inside "money", etc.
            // Also check for literal "$20" type formats.
            if (Regex("\\b$word\\b").containsMatchIn(text) || text.contains("\$$expectedAmount")) {
                verified = true
                break
            }
        }

        return if (verified && expectedAmount > 0) {
            "Verified $expectedAmount Canadian dollars"
        } else if (expectedAmount > 0) {
            // Couldn't find the specific denomination keyword, fall back to what the model predicted
            "Detected $expectedAmount Canadian dollars"
        } else {
            "Money detected, but couldn't verify the amount"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Speak OCR text and lock the gate so no new OCR triggers until this finishes.
     * The gate is released in the UtteranceProgressListener onDone/onError callbacks.
     */
    private fun speakOcr(text: String, utteranceId: String) {
        isSpeakingOcr.set(true)
        Log.d(TAG, "OCR gate locked, speaking: \"$text\"")
        speak(text, utteranceId)
    }

    private fun speak(text: String, utteranceId: String) {
        val url = esp32Url
        if (url != null) {
            // Synthesize to temp WAV and send to ESP32
            val tempWav = File(appContext.cacheDir, "tts_$utteranceId.wav")
            tts?.synthesizeToFile(text, null, tempWav, utteranceId)
        } else {
            // Play directly on phone speaker
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /** Clean up the TTS engine and OCR. Call from Activity.onDestroy(). */
    fun shutdown() {
        Log.i(TAG, "Shutting down TTS engine and PaddleOCR")
        isSpeakingOcr.set(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        paddleOcr.close()
        ocrExecutor.shutdown()
    }
}
