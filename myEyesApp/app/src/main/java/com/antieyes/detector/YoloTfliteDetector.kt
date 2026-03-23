package com.antieyes.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

private const val TAG_TF = "TFLITE"
private const val TAG_DECODE = "YOLO_DECODE"
private const val TAG_PERF = "PERF"

/**
 * YOLOv8 TFLite detector with exhaustive debug logging.
 *
 * Handles:
 *  - Model loading and tensor shape introspection
 *  - Letterbox preprocessing (640x640, no stretch)
 *  - Float32 RGB normalization [0..1]
 *  - Auto-detect output layout [1,C,N] vs [1,N,C]
 *  - Auto-detect objectness channel
 *  - Sigmoid auto-application
 *  - Confidence filtering + per-class NMS
 *  - Coordinate mapping back to original frame space
 */
class YoloTfliteDetector(context: Context) {

    // ── Model params ──────────────────────────────────────────────────
    private val interpreter: Interpreter
    private val labels: List<String>
    val numClasses: Int
    val inputSize: Int  // e.g. 640

    // ── Tensor metadata (logged on init) ──────────────────────────────
    val inputShape: IntArray
    val inputDtype: String
    val outputShape: IntArray
    val outputDtype: String

    // ── Output layout (auto-detected) ─────────────────────────────────
    /** True if output is [1, C, N], requiring transpose to [1, N, C] */
    var transposed = false
        private set
    var numDetections = 0       // N
        private set
    var numAttributes = 0       // C (4 box + optional obj + numClasses)
        private set
    var hasObjectness = false
        private set
    var classStartIdx = 4       // index where class scores begin
        private set

    // ── Reusable buffers ─────────────────────────────────────────────
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val outputArray: Array<Array<FloatArray>>  // [1][dim1][dim2]

    // ── Debug state ──────────────────────────────────────────────────
    var isFirstFrame = true
        private set
    var lastInferenceMs = 0L
        private set

    data class DebugInfo(
        val inputShape: String,
        val inputDtype: String,
        val outputShape: String,
        val outputDtype: String,
        val outputMin: Float,
        val outputMax: Float,
        val outputMean: Float,
        val numDetections: Int,
        val numAttributes: Int,
        val numClasses: Int,
        val hasObjectness: Boolean,
        val transposed: Boolean,
        val candidatesBeforeThreshold: Int,
        val candidatesAfterThreshold: Int,
        val candidatesAfterNms: Int,
        val inferenceMs: Long,
        val first20Values: String
    )

    var lastDebugInfo: DebugInfo? = null
        private set

    init {
        Log.i(TAG_TF, "╔═══════════════════════════════════════════════════════════════")
        Log.i(TAG_TF, "║ INITIALIZING YoloTfliteDetector")
        Log.i(TAG_TF, "╚═══════════════════════════════════════════════════════════════")

        // ── Load labels ──────────────────────────────────────────────
        labels = context.assets.open("labels.txt").bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        numClasses = labels.size
        Log.i(TAG_TF, "Labels loaded: $numClasses classes")
        labels.forEachIndexed { idx, name ->
            Log.i(TAG_TF, "  label[$idx] = '$name'")
        }

        // ── Load model ───────────────────────────────────────────────
        val modelBuffer = loadModelFile(context, "newmodel.tflite")
        Log.i(TAG_TF, "Model file loaded, size = ${modelBuffer.capacity()} bytes")

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
        Log.i(TAG_TF, "Interpreter created with 4 CPU threads")

        // ── Inspect input tensor ─────────────────────────────────────
        val inputTensor = interpreter.getInputTensor(0)
        inputShape = inputTensor.shape()
        inputDtype = inputTensor.dataType().name
        Log.i(TAG_TF, "┌─ INPUT TENSOR ─────────────────────────────")
        Log.i(TAG_TF, "│  shape  = ${inputShape.contentToString()}")
        Log.i(TAG_TF, "│  dtype  = $inputDtype")
        Log.i(TAG_TF, "│  bytes  = ${inputTensor.numBytes()}")
        Log.i(TAG_TF, "└────────────────────────────────────────────")

        // Determine input size from shape
        // Typical: [1, 640, 640, 3] (NHWC) or [1, 3, 640, 640] (NCHW)
        inputSize = when {
            inputShape.size == 4 && inputShape[1] == inputShape[2] -> {
                Log.i(TAG_TF, "Input format: NHWC [1, H, W, C] — H=W=${inputShape[1]}")
                inputShape[1]
            }
            inputShape.size == 4 && inputShape[2] == inputShape[3] -> {
                Log.i(TAG_TF, "Input format: NCHW [1, C, H, W] — H=W=${inputShape[2]}")
                inputShape[2]
            }
            else -> {
                Log.w(TAG_TF, "Unexpected input shape, defaulting inputSize to 640")
                640
            }
        }

        // ── Inspect output tensor ────────────────────────────────────
        val outputTensor = interpreter.getOutputTensor(0)
        outputShape = outputTensor.shape()
        outputDtype = outputTensor.dataType().name
        Log.i(TAG_TF, "┌─ OUTPUT TENSOR ────────────────────────────")
        Log.i(TAG_TF, "│  shape  = ${outputShape.contentToString()}")
        Log.i(TAG_TF, "│  dtype  = $outputDtype")
        Log.i(TAG_TF, "│  bytes  = ${outputTensor.numBytes()}")
        Log.i(TAG_TF, "└────────────────────────────────────────────")

        // ── Auto-detect output layout ────────────────────────────────
        // outputShape is [1, dim1, dim2]
        val dim1 = outputShape[1]
        val dim2 = outputShape[2]
        Log.i(TAG_DECODE, "┌─ AUTO-DETECTING OUTPUT LAYOUT ─────────────")
        Log.i(TAG_DECODE, "│  dim1=$dim1, dim2=$dim2, numClasses=$numClasses")

        // Heuristic: the larger dimension is likely numDetections (e.g. 8400)
        // The smaller dimension is numAttributes (4 + maybe_obj + numClasses)
        val expectedAttrsWithObj = 5 + numClasses    // cx,cy,w,h,obj + classes
        val expectedAttrsNoObj   = 4 + numClasses    // cx,cy,w,h + classes

        when {
            dim2 == expectedAttrsWithObj || dim2 == expectedAttrsNoObj -> {
                // [1, N, C] layout — no transpose needed
                transposed = false
                numDetections = dim1
                numAttributes = dim2
                Log.i(TAG_DECODE, "│  Layout: [1, N=$numDetections, C=$numAttributes] (no transpose)")
            }
            dim1 == expectedAttrsWithObj || dim1 == expectedAttrsNoObj -> {
                // [1, C, N] layout — needs transpose
                transposed = true
                numDetections = dim2
                numAttributes = dim1
                Log.i(TAG_DECODE, "│  Layout: [1, C=$numAttributes, N=$numDetections] (TRANSPOSED)")
            }
            else -> {
                // Neither matches exactly - use heuristic: smaller dim = attributes
                if (dim1 < dim2) {
                    transposed = true
                    numDetections = dim2
                    numAttributes = dim1
                    Log.w(TAG_DECODE, "│  ⚠ HEURISTIC: dim1($dim1) < dim2($dim2)")
                    Log.w(TAG_DECODE, "│    Guessing [1, C=$numAttributes, N=$numDetections] (transposed)")
                } else {
                    transposed = false
                    numDetections = dim1
                    numAttributes = dim2
                    Log.w(TAG_DECODE, "│  ⚠ HEURISTIC: dim1($dim1) >= dim2($dim2)")
                    Log.w(TAG_DECODE, "│    Guessing [1, N=$numDetections, C=$numAttributes] (not transposed)")
                }
                Log.w(TAG_DECODE, "│  Expected C = $expectedAttrsNoObj (no obj) or $expectedAttrsWithObj (with obj)")
                Log.w(TAG_DECODE, "│  Actual C = $numAttributes — MISMATCH, results may be wrong!")
            }
        }

        // Determine objectness
        when (numAttributes) {
            5 + numClasses -> {
                hasObjectness = true
                classStartIdx = 5
                Log.i(TAG_DECODE, "│  Objectness: YES (channel 4)")
                Log.i(TAG_DECODE, "│  Class scores start at index: 5")
            }
            4 + numClasses -> {
                hasObjectness = false
                classStartIdx = 4
                Log.i(TAG_DECODE, "│  Objectness: NO")
                Log.i(TAG_DECODE, "│  Class scores start at index: 4")
            }
            else -> {
                // Best-effort: assume no objectness
                hasObjectness = false
                classStartIdx = 4
                Log.w(TAG_DECODE, "│  ⚠ Cannot determine objectness from C=$numAttributes vs numClasses=$numClasses")
                Log.w(TAG_DECODE, "│    Defaulting: no objectness, classStart=4")
            }
        }

        Log.i(TAG_DECODE, "│  Summary: ${numDetections} detections × ${numAttributes} attributes")
        Log.i(TAG_DECODE, "│           ${numClasses} classes, objectness=${hasObjectness}")
        Log.i(TAG_DECODE, "└────────────────────────────────────────────")

        // ── Allocate reusable buffers ────────────────────────────────
        val inputBytes = 1 * inputSize * inputSize * 3 * 4  // float32 = 4 bytes
        inputBuffer = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())
        Log.i(TAG_TF, "Input buffer allocated: $inputBytes bytes (direct, nativeOrder)")

        val outDim1 = outputShape[1]
        val outDim2 = outputShape[2]
        outputArray = Array(1) { Array(outDim1) { FloatArray(outDim2) } }
        val outputBytes = 1 * outDim1 * outDim2 * 4
        outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        Log.i(TAG_TF, "Output array allocated: [1][$outDim1][$outDim2] = ${outDim1 * outDim2} floats")

        Log.i(TAG_TF, "╔═══════════════════════════════════════════════════════════════")
        Log.i(TAG_TF, "║ YoloTfliteDetector READY")
        Log.i(TAG_TF, "╚═══════════════════════════════════════════════════════════════")
    }

    /**
     * Run detection on a single frame.
     * Returns list of Detection objects in original frame coordinates.
     */
    fun detect(
        frame: Bitmap,
        confidenceThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        val totalStart = System.nanoTime()

        // ── 1. PREPROCESS ────────────────────────────────────────────
        val prepStart = System.nanoTime()

        val (letterboxed, lbInfo) = LetterboxUtil.letterbox(frame, inputSize)

        Log.d(TAG_TF, "┌─ PREPROCESS ───────────────────────────────")
        Log.d(TAG_TF, "│  Original frame: ${frame.width}x${frame.height}")
        Log.d(TAG_TF, "│  Letterboxed to: ${letterboxed.width}x${letterboxed.height}")
        Log.d(TAG_TF, "│  Scale=${lbInfo.scale}, padX=${lbInfo.padX}, padY=${lbInfo.padY}")

        // Fill input buffer with RGB float32 [0..1]
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Log some pixel samples before conversion
        if (isFirstFrame) {
            Log.d(TAG_TF, "│  ── First frame pixel samples (ARGB hex) ──")
            val samplePositions = intArrayOf(0, 1, inputSize, inputSize * inputSize / 2, pixels.size - 1)
            for (pos in samplePositions) {
                if (pos < pixels.size) {
                    val p = pixels[pos]
                    val a = (p shr 24) and 0xFF
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    Log.d(TAG_TF, "│    pixel[$pos] = ARGB($a,$r,$g,$b) = 0x${Integer.toHexString(p)}")
                }
            }
        }

        // Determine input format
        val isNchw = inputShape.size == 4 && inputShape[1] == 3

        if (isNchw) {
            Log.d(TAG_TF, "│  Writing pixels in NCHW order (channel-first)")
            // NCHW: write all R values, then all G values, then all B values
            for (c in 0 until 3) {
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val value = when (c) {
                        0 -> ((p shr 16) and 0xFF) / 255.0f  // R
                        1 -> ((p shr 8) and 0xFF) / 255.0f   // G
                        2 -> (p and 0xFF) / 255.0f            // B
                        else -> 0f
                    }
                    inputBuffer.putFloat(value)
                }
            }
        } else {
            Log.d(TAG_TF, "│  Writing pixels in NHWC order (channel-last)")
            // NHWC: for each pixel, write R, G, B
            for (p in pixels) {
                val r = ((p shr 16) and 0xFF) / 255.0f
                val g = ((p shr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }

        // Log buffer stats
        if (isFirstFrame) {
            inputBuffer.rewind()
            var minVal = Float.MAX_VALUE
            var maxVal = Float.MIN_VALUE
            var sum = 0.0
            val count = inputSize * inputSize * 3
            val first20 = FloatArray(minOf(20, count))
            for (i in 0 until count) {
                val v = inputBuffer.getFloat()
                if (i < 20) first20[i] = v
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
                sum += v
            }
            Log.d(TAG_TF, "│  ── Input buffer stats ──")
            Log.d(TAG_TF, "│    min=${minVal}, max=${maxVal}, mean=${"%.4f".format(sum / count)}")
            Log.d(TAG_TF, "│    first 20 values: ${first20.joinToString { "%.4f".format(it) }}")
            inputBuffer.rewind()
        }

        val prepMs = (System.nanoTime() - prepStart) / 1_000_000.0
        Log.d(TAG_TF, "│  Preprocessing took: ${"%.1f".format(prepMs)} ms")
        Log.d(TAG_TF, "└────────────────────────────────────────────")

        // Recycle letterboxed bitmap since we extracted pixels already
        letterboxed.recycle()

        // ── 2. INFERENCE ─────────────────────────────────────────────
        val infStart = System.nanoTime()
        Log.d(TAG_TF, "Running inference...")

        inputBuffer.rewind()
        // Use the array-based output
        interpreter.run(inputBuffer, outputArray)

        val infMs = (System.nanoTime() - infStart) / 1_000_000
        lastInferenceMs = infMs
        Log.d(TAG_PERF, "Inference completed in ${infMs} ms")

        // ── 3. DECODE OUTPUT ─────────────────────────────────────────
        val decStart = System.nanoTime()
        val dim1 = outputShape[1]
        val dim2 = outputShape[2]

        Log.d(TAG_DECODE, "┌─ DECODING OUTPUT ──────────────────────────")
        Log.d(TAG_DECODE, "│  Raw output shape: [1, $dim1, $dim2]")
        Log.d(TAG_DECODE, "│  Transposed=$transposed, N=$numDetections, C=$numAttributes")

        // Compute raw output stats
        var rawMin = Float.MAX_VALUE
        var rawMax = Float.MIN_VALUE
        var rawSum = 0.0
        var rawCount = 0
        val first20Raw = mutableListOf<Float>()

        for (i in 0 until dim1) {
            for (j in 0 until dim2) {
                val v = outputArray[0][i][j]
                if (v < rawMin) rawMin = v
                if (v > rawMax) rawMax = v
                rawSum += v
                rawCount++
                if (first20Raw.size < 20) first20Raw.add(v)
            }
        }
        val rawMean = rawSum / rawCount

        Log.d(TAG_DECODE, "│  ── Raw output stats ──")
        Log.d(TAG_DECODE, "│    min=${"%.6f".format(rawMin)}")
        Log.d(TAG_DECODE, "│    max=${"%.6f".format(rawMax)}")
        Log.d(TAG_DECODE, "│    mean=${"%.6f".format(rawMean)}")
        Log.d(TAG_DECODE, "│    first 20 raw values: ${first20Raw.joinToString { "%.4f".format(it) }}")

        if (isFirstFrame) {
            // Log the first 5 full detection vectors
            Log.d(TAG_DECODE, "│  ── First 5 detection vectors (raw) ──")
            for (det in 0 until minOf(5, numDetections)) {
                val vec = StringBuilder("│    det[$det]: ")
                for (attr in 0 until minOf(numAttributes, 20)) {
                    val v = if (transposed) outputArray[0][attr][det] else outputArray[0][det][attr]
                    vec.append("%.4f".format(v))
                    if (attr < numAttributes - 1) vec.append(", ")
                }
                if (numAttributes > 20) vec.append(", ... (${numAttributes - 20} more)")
                Log.d(TAG_DECODE, vec.toString())
            }
        }

        // ── Decode each detection ────────────────────────────────────
        val candidates = mutableListOf<Detection>()
        var rawCandidateCount = 0

        for (i in 0 until numDetections) {
            // Read attributes — handle transposed or not
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float

            if (transposed) {
                cx = outputArray[0][0][i]
                cy = outputArray[0][1][i]
                w  = outputArray[0][2][i]
                h  = outputArray[0][3][i]
            } else {
                cx = outputArray[0][i][0]
                cy = outputArray[0][i][1]
                w  = outputArray[0][i][2]
                h  = outputArray[0][i][3]
            }

            // ── Auto-detect normalized vs pixel coords ───────────────
            // YOLOv8 typically outputs pixel coords in model input space (0..640)
            // But if values are in 0..~1.5, they might be normalized
            val finalCx: Float
            val finalCy: Float
            val finalW: Float
            val finalH: Float

            if (cx in 0f..1.5f && cy in 0f..1.5f && w in 0f..1.5f && h in 0f..1.5f) {
                // Looks normalized — scale to inputSize
                finalCx = cx * inputSize
                finalCy = cy * inputSize
                finalW = w * inputSize
                finalH = h * inputSize
                if (isFirstFrame && i == 0) {
                    Log.d(TAG_DECODE, "│  Box coords appear NORMALIZED (0..1), scaling by $inputSize")
                }
            } else {
                // Already in pixel space
                finalCx = cx
                finalCy = cy
                finalW = w
                finalH = h
                if (isFirstFrame && i == 0) {
                    Log.d(TAG_DECODE, "│  Box coords appear in PIXEL space (0..$inputSize)")
                }
            }

            // ── Read scores ──────────────────────────────────────────
            val objScore: Float
            if (hasObjectness) {
                val rawObj = if (transposed) outputArray[0][4][i] else outputArray[0][i][4]
                objScore = maybeApplySigmoid(rawObj)
            } else {
                objScore = 1.0f
            }

            // Find best class
            var bestClassScore = -Float.MAX_VALUE
            var bestClassId = 0

            for (c in 0 until numClasses) {
                val rawScore = if (transposed) {
                    outputArray[0][classStartIdx + c][i]
                } else {
                    outputArray[0][i][classStartIdx + c]
                }
                val score = maybeApplySigmoid(rawScore)
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClassId = c
                }
            }

            val confidence = objScore * bestClassScore
            rawCandidateCount++

            // Log first few detections in detail on first frame
            if (isFirstFrame && i < 10) {
                Log.v(TAG_DECODE, "│    det[$i]: cx=${"%.1f".format(finalCx)} cy=${"%.1f".format(finalCy)} " +
                        "w=${"%.1f".format(finalW)} h=${"%.1f".format(finalH)} " +
                        "obj=${"%.4f".format(objScore)} bestClass=$bestClassId " +
                        "classScore=${"%.4f".format(bestClassScore)} conf=${"%.4f".format(confidence)} " +
                        "label='${labels.getOrElse(bestClassId) { "?" }}'")
            }

            // Apply per-class confidence thresholds
            val className = labels.getOrElse(bestClassId) { "" }
            val classThreshold = when (className) {
                "car" -> 0.4f
                "Crosswalks" -> 0.6f
                "10CanadianDollar" -> 0.9f
                "50CanadianDollar" -> 0.9f
                "100CanadianDollar" -> 0.5f
                "20CanadianDollar" -> 0.5f
                "5CanadianDollar" -> 0.5f
                "red pedestrian light" -> 0.5f
                else -> confidenceThreshold
            }
            
            if (confidence < classThreshold) continue

            // ── Convert cx,cy,w,h → x1,y1,x2,y2 in 640-space ───────
            val x1_640 = finalCx - finalW / 2f
            val y1_640 = finalCy - finalH / 2f
            val x2_640 = finalCx + finalW / 2f
            val y2_640 = finalCy + finalH / 2f

            // ── Map back to original frame coordinates ───────────────
            val mapped = LetterboxUtil.mapToOriginal(x1_640, y1_640, x2_640, y2_640, lbInfo)

            val det = Detection(
                x1 = mapped[0], y1 = mapped[1],
                x2 = mapped[2], y2 = mapped[3],
                confidence = confidence,
                classId = bestClassId,
                className = labels.getOrElse(bestClassId) { "class_$bestClassId" }
            )
            candidates.add(det)

            Log.d(TAG_DECODE, "│  ✓ CANDIDATE: ${det.className} conf=${"%.3f".format(det.confidence)} " +
                    "box640=(${"%.0f".format(x1_640)},${"%.0f".format(y1_640)},${"%.0f".format(x2_640)},${"%.0f".format(y2_640)}) " +
                    "boxOrig=(${"%.0f".format(det.x1)},${"%.0f".format(det.y1)},${"%.0f".format(det.x2)},${"%.0f".format(det.y2)})")
        }

        Log.d(TAG_DECODE, "│  Candidates: $rawCandidateCount total → ${candidates.size} above threshold ($confidenceThreshold)")

        // ── 4. NMS ───────────────────────────────────────────────────
        val results = NmsUtil.nms(candidates, iouThreshold)

        val decMs = (System.nanoTime() - decStart) / 1_000_000.0
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000.0

        Log.d(TAG_DECODE, "│  After NMS: ${results.size} final detections")
        Log.d(TAG_DECODE, "│  Decoding took: ${"%.1f".format(decMs)} ms, total pipeline: ${"%.1f".format(totalMs)} ms")
        Log.d(TAG_DECODE, "└────────────────────────────────────────────")

        for (det in results) {
            Log.i(TAG_DECODE, "  ★ DETECTION: ${det.className} (${"%.1f".format(det.confidence * 100)}%) " +
                    "at (${"%.0f".format(det.x1)}, ${"%.0f".format(det.y1)}) - (${"%.0f".format(det.x2)}, ${"%.0f".format(det.y2)})")
        }

        Log.d(TAG_PERF, "Pipeline: prep=${"%.0f".format(prepMs)}ms + infer=${infMs}ms + decode=${"%.0f".format(decMs)}ms = ${"%.0f".format(totalMs)}ms total")

        // ── Save debug info ──────────────────────────────────────────
        lastDebugInfo = DebugInfo(
            inputShape = inputShape.contentToString(),
            inputDtype = inputDtype,
            outputShape = outputShape.contentToString(),
            outputDtype = outputDtype,
            outputMin = rawMin,
            outputMax = rawMax,
            outputMean = rawMean.toFloat(),
            numDetections = numDetections,
            numAttributes = numAttributes,
            numClasses = numClasses,
            hasObjectness = hasObjectness,
            transposed = transposed,
            candidatesBeforeThreshold = rawCandidateCount,
            candidatesAfterThreshold = candidates.size,
            candidatesAfterNms = results.size,
            inferenceMs = infMs,
            first20Values = first20Raw.joinToString { "%.4f".format(it) }
        )

        if (isFirstFrame) {
            isFirstFrame = false
            Log.i(TAG_TF, "═══ First frame processing complete ═══")
        }

        return results
    }

    /**
     * Apply sigmoid only if the value is outside [0, 1] range
     * (i.e. raw logits vs already-activated scores).
     */
    private fun maybeApplySigmoid(x: Float): Float {
        return if (x < 0f || x > 1f) {
            val result = (1.0f / (1.0f + exp(-x)))
            result
        } else {
            x
        }
    }

    /** Print a full debug snapshot to Logcat */
    fun logDebugSnapshot() {
        val info = lastDebugInfo ?: run {
            Log.i(TAG_TF, "No debug info yet — run at least one frame first")
            return
        }
        Log.i(TAG_TF, "╔═══════════════════════════════════════════════════════════════")
        Log.i(TAG_TF, "║ DEBUG SNAPSHOT")
        Log.i(TAG_TF, "╠═══════════════════════════════════════════════════════════════")
        Log.i(TAG_TF, "║ Input shape:  ${info.inputShape}")
        Log.i(TAG_TF, "║ Input dtype:  ${info.inputDtype}")
        Log.i(TAG_TF, "║ Output shape: ${info.outputShape}")
        Log.i(TAG_TF, "║ Output dtype: ${info.outputDtype}")
        Log.i(TAG_TF, "╠───────────────────────────────────────────────────────────────")
        Log.i(TAG_TF, "║ Output min:   ${info.outputMin}")
        Log.i(TAG_TF, "║ Output max:   ${info.outputMax}")
        Log.i(TAG_TF, "║ Output mean:  ${info.outputMean}")
        Log.i(TAG_TF, "╠───────────────────────────────────────────────────────────────")
        Log.i(TAG_TF, "║ Num detections:  ${info.numDetections}")
        Log.i(TAG_TF, "║ Num attributes:  ${info.numAttributes}")
        Log.i(TAG_TF, "║ Num classes:     ${info.numClasses}")
        Log.i(TAG_TF, "║ Has objectness:  ${info.hasObjectness}")
        Log.i(TAG_TF, "║ Transposed:      ${info.transposed}")
        Log.i(TAG_TF, "╠───────────────────────────────────────────────────────────────")
        Log.i(TAG_TF, "║ Before threshold: ${info.candidatesBeforeThreshold}")
        Log.i(TAG_TF, "║ After threshold:  ${info.candidatesAfterThreshold}")
        Log.i(TAG_TF, "║ After NMS:        ${info.candidatesAfterNms}")
        Log.i(TAG_TF, "║ Inference ms:     ${info.inferenceMs}")
        Log.i(TAG_TF, "╠───────────────────────────────────────────────────────────────")
        Log.i(TAG_TF, "║ First 20 values:  ${info.first20Values}")
        Log.i(TAG_TF, "╚═══════════════════════════════════════════════════════════════")
    }

    fun close() {
        interpreter.close()
        Log.i(TAG_TF, "Interpreter closed")
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
}
