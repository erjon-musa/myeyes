package com.antieyes.detector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ESP32_AUDIO"
private const val AUDIO_PORT = 81

/**
 * Handles sending WAV audio files to the ESP32 speakers over HTTP.
 */
class Esp32AudioSender {

    private val client = OkHttpClient.Builder()
        // Longer timeouts for sending large audio files
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val wavMediaType = "audio/wav".toMediaType()

    /**
     * Suspend function to read a WAV file and POST it to the ESP32.
     * Must be called from a Coroutine (automatically switches to IO dispatcher).
     *
     * @param baseUrl The base URL of the ESP32 (e.g., "http://192.168.4.1")
     * @param wavFile The WAV file to send
     * @return true if successful, false otherwise
     */
    suspend fun sendWavFile(baseUrl: String, wavFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!wavFile.exists() || !wavFile.isFile) {
            Log.e(TAG, "WAV file does not exist: ${wavFile.absolutePath}")
            return@withContext false
        }

        if (wavFile.length() == 0L) {
            Log.w(TAG, "WAV file is empty: ${wavFile.absolutePath}")
            return@withContext false
        }

        // Parse the URL to properly extract the host and protocol, ignoring any paths like /stream
        val urlObj = try {
            java.net.URL(baseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid base URL: $baseUrl")
            return@withContext false
        }
        val audioUrl = "${urlObj.protocol}://${urlObj.host}:$AUDIO_PORT/audio"

        Log.d(TAG, "Preparing to send audio to $audioUrl (${wavFile.length()} bytes)")

        try {
            val requestBody = wavFile.asRequestBody(wavMediaType)
            val request = Request.Builder()
                .url(audioUrl)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error sending audio: ${response.code} ${response.message}")
                    return@withContext false
                }
                
                Log.i(TAG, "Successfully sent audio to ESP32: ${wavFile.length()} bytes")
                return@withContext true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send audio to ESP32: ${e.message}", e)
            return@withContext false
        }
    }
}
