package com.antieyes.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

private const val TAG_PERF = "PERF"
private const val TAG_MAIN = "MAIN_ACTIVITY"
private const val CAMERA_PERMISSION_CODE = 1001

private const val PREFS_NAME = "MyEyesPrefs"
private const val PREF_URL_KEY = "last_stream_url"

class MainActivity : AppCompatActivity() {

    private lateinit var etStreamUrl: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnCamera: Button
    private lateinit var btnDisconnect: Button
    private lateinit var ivVideoFrame: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView

    private var streamReader: MjpegStreamReader? = null
    private var cameraSource: CameraFrameSource? = null
    private var detector: YoloTfliteDetector? = null
    private lateinit var announcer: DetectionAnnouncer

    /** Points to whichever source is active (MJPEG or Camera) */
    private var activeFrameSource: AtomicReference<Bitmap?>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var displayJob: Job? = null
    private var inferenceJob: Job? = null

    private var frameCount = 0L
    private var inferenceCount = 0L
    private var fpsStartTime = 0L

    /** Latest detections from the inference thread — read by the display thread */
    @Volatile private var latestDetections: List<Detection> = emptyList()
    @Volatile private var latestDetFrameW = 0
    @Volatile private var latestDetFrameH = 0
    @Volatile private var latestInferenceMs = 0L

    /** Holds the latest frame copy for inference (so display and inference don't share bitmaps) */
    private val inferenceFrame = AtomicReference<Bitmap?>(null)

    /** Track which mode is active */
    private enum class SourceMode { NONE, ESP32, CAMERA }
    private var currentMode = SourceMode.NONE


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.i(TAG_MAIN, "╔═══════════════════════════════════════════════════════════════")
        Log.i(TAG_MAIN, "║ AntiEyes starting up...")
        Log.i(TAG_MAIN, "╚═══════════════════════════════════════════════════════════════")

        // ── Bind views ───────────────────────────────────────────────
        etStreamUrl = findViewById(R.id.etStreamUrl)
        btnConnect = findViewById(R.id.btnConnect)
        btnCamera = findViewById(R.id.btnCamera)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        ivVideoFrame = findViewById(R.id.ivVideoFrame)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)

        // Load saved URL from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(PREF_URL_KEY, null)
        if (!savedUrl.isNullOrEmpty()) {
            etStreamUrl.setText(savedUrl)
        }

        // ── Initialize detector ──────────────────────────────────────
        Log.i(TAG_MAIN, "Initializing YoloTfliteDetector...")
        try {
            detector = YoloTfliteDetector(this)
            Log.i(TAG_MAIN, "Detector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG_MAIN, "Failed to initialize detector: ${e.message}", e)
        }

        // ── Initialize TTS announcer + PaddleOCR ─────────────────────
        announcer = DetectionAnnouncer(this)
        announcer.initOcr()
        Log.i(TAG_MAIN, "TTS announcer initialized, PaddleOCR loading in background")

        // ── Button handlers ──────────────────────────────────────────
        btnConnect.setOnClickListener {
            val url = etStreamUrl.text.toString().trim()
            if (url.isEmpty()) {
                Log.e(TAG_MAIN, "ERROR: URL is empty")
                return@setOnClickListener
            }
            
            // Save the URL to SharedPreferences
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_URL_KEY, url)
                .apply()
                
            connectEsp32(url)
        }

        btnCamera.setOnClickListener {
            startCamera()
        }

        btnDisconnect.setOnClickListener {
            disconnect()
        }
    }

    // ── ESP32 MJPEG stream ───────────────────────────────────────────

    private fun connectEsp32(url: String) {
        disconnect() // stop any existing source

        // Android 9+ drops traffic to WiFi networks without internet by default.
        // We must manually bind the process to the Wi-Fi network if we are connected to one.
        try {
            val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connManager != null) {
                val networks = connManager.allNetworks
                for (network in networks) {
                    val caps = connManager.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        connManager.bindProcessToNetwork(network)
                        Log.i(TAG_MAIN, "Bound process to Wi-Fi network to maintain connection with ESP32")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_MAIN, "Error binding to Wi-Fi network: ${e.message}")
        }

        Log.i(TAG_MAIN, "┌─ CONNECTING ESP32 ─────────────────────────")
        Log.i(TAG_MAIN, "│  URL: $url")
        Log.i(TAG_MAIN, "└────────────────────────────────────────────")

        currentMode = SourceMode.ESP32

        // Show ImageView, hide CameraX PreviewView
        ivVideoFrame.visibility = View.VISIBLE
        previewView.visibility = View.GONE

        btnConnect.isEnabled = false
        btnCamera.isEnabled = false
        btnDisconnect.isEnabled = true

        streamReader = MjpegStreamReader()
        streamReader!!.connect(url, scope)
        activeFrameSource = streamReader!!.latestFrame
        
        // Route TTS audio to ESP32 speakers
        announcer.esp32Url = url

        startDisplayLoop()
        startInferenceLoop()
    }

    // ── Live camera ──────────────────────────────────────────────────

    private fun startCamera() {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG_MAIN, "Requesting CAMERA permission...")
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }

        disconnect() // stop any existing source

        Log.i(TAG_MAIN, "┌─ STARTING LIVE CAMERA ─────────────────────")
        Log.i(TAG_MAIN, "│  Using CameraX rear camera")
        Log.i(TAG_MAIN, "└────────────────────────────────────────────")

        currentMode = SourceMode.CAMERA

        // Show CameraX PreviewView, hide ImageView
        ivVideoFrame.visibility = View.GONE
        previewView.visibility = View.VISIBLE

        btnConnect.isEnabled = false
        btnCamera.isEnabled = false
        btnDisconnect.isEnabled = true

        cameraSource = CameraFrameSource()
        cameraSource!!.start(this, this, previewView)
        activeFrameSource = cameraSource!!.latestFrame

        // Route TTS audio back to phone speaker
        announcer.esp32Url = null

        // Camera mode: PreviewView handles display, only need inference loop
        startInferenceLoop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG_MAIN, "CAMERA permission granted")
                    startCamera()
                } else {
                    Log.w(TAG_MAIN, "CAMERA permission denied")
                }
            }
        }
    }

    // ── Disconnect / cleanup ──────────────────────────────────────────

    private fun disconnect() {
        Log.i(TAG_MAIN, "Disconnecting (mode=$currentMode)...")

        displayJob?.cancel()
        displayJob = null
        inferenceJob?.cancel()
        inferenceJob = null

        streamReader?.disconnect()
        streamReader = null

        cameraSource?.stop()
        cameraSource = null

        activeFrameSource = null
        val leftoverInf = inferenceFrame.getAndSet(null)
        try { leftoverInf?.recycle() } catch (_: Exception) {}
        latestDetections = emptyList()
        currentMode = SourceMode.NONE
        
        // Route TTS audio back to phone speaker
        announcer.esp32Url = null

        btnConnect.isEnabled = true
        btnCamera.isEnabled = true
        btnDisconnect.isEnabled = false

        overlayView.clear()

        // Reset views
        ivVideoFrame.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        ivVideoFrame.setImageBitmap(null)
    }

    // ── Display loop: shows frames in real-time (ESP32 mode only) ────

    private fun startDisplayLoop() {
        frameCount = 0
        fpsStartTime = System.currentTimeMillis()

        displayJob = scope.launch {
            Log.i(TAG_MAIN, "Display loop started (ESP32 real-time frame display)")

            delay(300)

            var currentDisplayBitmap: Bitmap? = null
            var previousDisplayBitmap: Bitmap? = null

            try {
                while (isActive) {
                    val source = activeFrameSource
                    if (source == null) {
                        delay(30)
                        continue
                    }

                    // Consume the latest frame (take ownership from stream reader).
                    // Between new frames the stream returns null — we just keep showing the last one.
                    val newFrame = source.getAndSet(null)

                    if (newFrame != null) {
                        // One copy for inference (only when a genuinely new frame arrives)
                        try {
                            val infCopy = newFrame.copy(newFrame.config ?: Bitmap.Config.ARGB_8888, false)
                            val oldInf = inferenceFrame.getAndSet(infCopy)
                            try { oldInf?.recycle() } catch (_: Exception) {}
                        } catch (_: Exception) {}

                        // Display the original bitmap directly (no second copy needed)
                        ivVideoFrame.setImageBitmap(newFrame)

                        // Recycle the bitmap from 2 frames ago; the one from 1 frame ago
                        // may still be in the GPU render pipeline.
                        try { previousDisplayBitmap?.recycle() } catch (_: Exception) {}
                        previousDisplayBitmap = currentDisplayBitmap
                        currentDisplayBitmap = newFrame
                    }

                    // Always update the detection overlay (results arrive asynchronously)
                    val dets = latestDetections
                    if (latestDetFrameW > 0) {
                        overlayView.setDetections(dets, latestDetFrameW, latestDetFrameH)
                    }

                    frameCount++
                    val elapsed = (System.currentTimeMillis() - fpsStartTime) / 1000.0
                    val fps = if (elapsed > 0) frameCount / elapsed else 0.0
                    val infMs = latestInferenceMs

                    if (frameCount <= 3 || frameCount % 100 == 0L) {
                        Log.d(TAG_PERF, "Display #$frameCount [ESP32]: " +
                                "displayFPS=${"%.1f".format(fps)}, lastInfer=${infMs}ms, " +
                                "overlayDets=${dets.size}")
                    }

                    delay(33)
                }
            } finally {
                try { currentDisplayBitmap?.recycle() } catch (_: Exception) {}
                try { previousDisplayBitmap?.recycle() } catch (_: Exception) {}
                Log.i(TAG_MAIN, "Display loop ended")
            }
        }
    }

    // ── Inference loop: runs detection in the background ──────────────

    private fun startInferenceLoop() {
        inferenceCount = 0

        inferenceJob = scope.launch {
            Log.i(TAG_MAIN, "Inference loop started (source=$currentMode)")

            delay(500)

            while (isActive) {
                // For ESP32 mode, get frame from inferenceFrame (copy made by display loop)
                // For camera mode, get frame directly from source
                val frame: Bitmap? = if (currentMode == SourceMode.ESP32) {
                    inferenceFrame.getAndSet(null)
                } else {
                    val source = activeFrameSource
                    val raw = source?.getAndSet(null)
                    if (raw != null) {
                        try {
                            val copy = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, false)
                            raw.recycle()
                            copy
                        } catch (e: Exception) {
                            try { raw.recycle() } catch (_: Exception) {}
                            null
                        }
                    } else null
                }

                if (frame == null) {
                    delay(30)
                    continue
                }

                // Fixed thresholds
                // Base threshold is 30% for most classes, but cars require 70% (handled in detector)
                val confThreshold = 0.3f
                val iouThreshold = 0.45f

                val frameW = frame.width
                val frameH = frame.height

                // Run inference on Default dispatcher
                val detections = withContext(Dispatchers.Default) {
                    try {
                        detector?.detect(frame, confThreshold, iouThreshold) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG_MAIN, "Detection error: ${e.message}", e)
                        emptyList()
                    }
                }

                // Announce detections via TTS (before recycling — OCR needs the bitmap)
                if (detections.isNotEmpty()) {
                    announcer.announce(detections, frame)
                }

                // Recycle the inference frame copy
                try { frame.recycle() } catch (_: Exception) {}

                // Update shared detection state (read by display loop)
                latestDetections = detections
                latestDetFrameW = frameW
                latestDetFrameH = frameH
                latestInferenceMs = detector?.lastInferenceMs ?: 0

                inferenceCount++

                // For camera mode, also update overlay directly since there's no display loop
                if (currentMode == SourceMode.CAMERA) {
                    overlayView.setDetections(detections, frameW, frameH)
                }

                // Log periodically
                if (inferenceCount <= 3 || inferenceCount % 20 == 0L) {
                    val infMs = detector?.lastInferenceMs ?: 0
                    Log.d(TAG_PERF, "Inference #$inferenceCount [$currentMode]: " +
                            "infer=${infMs}ms, detections=${detections.size}, " +
                            "frame=${frameW}x${frameH}, conf=$confThreshold, iou=$iouThreshold")
                }

                yield()
            }

            Log.i(TAG_MAIN, "Inference loop ended")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        announcer.shutdown()
        detector?.close()
        scope.cancel()
        Log.i(TAG_MAIN, "Activity destroyed, resources cleaned up")
    }
}
