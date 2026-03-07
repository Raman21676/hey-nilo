package com.projekt_x.studybuddy.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

/**
 * Simple Audio Recorder using Android's built-in AudioRecord
 * 
 * CRITICAL FIX: Robust polling loop with larger buffer to handle
 * partial reads and device-specific audio issues.
 */
class SimpleAudioRecorder(
    private val context: Context,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
) {
    
    companion object {
        private const val TAG = "SimpleAudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 512  // 32ms at 16kHz
    }
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRecording = false
    
    interface AudioCallback {
        fun onAudioData(audioData: ShortArray)
        fun onError(error: String)
    }
    
    /**
     * Check if we have recording permission
     */
    fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == 
               PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start recording audio - FIXED VERSION with robust polling
     */
    fun startRecording(callback: AudioCallback): Boolean {
        if (!hasPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted!")
            callback.onError("No microphone permission")
            return false
        }
        
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }
        
        // FIX: Increase buffer size to *4 (from *2) to prevent underruns
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = minBufferSize * 4
        
        Log.d(TAG, "Min buffer size: $minBufferSize, Using: $bufferSize")
        
        // Create AudioRecord instance
        // FIX: Use VOICE_RECOGNITION instead of MIC for better speech detection on Samsung devices
        try {
            val sourceName = when (audioSource) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
                MediaRecorder.AudioSource.MIC -> "MIC"
                else -> "UNKNOWN($audioSource)"
            }
            Log.i(TAG, "Creating AudioRecord with source: $sourceName, rate: $SAMPLE_RATE, buffer: $bufferSize")
            
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}")
            callback.onError("Failed to create audio recorder: ${e.message}")
            return false
        }
        
        // Check state
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state: ${audioRecord?.state})")
            callback.onError("Audio recorder not initialized")
            audioRecord?.release()
            audioRecord = null
            return false
        }
        
        Log.i(TAG, "AudioRecord initialized successfully")
        
        // Start recording
        try {
            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            callback.onError("Failed to start recording: ${e.message}")
            audioRecord?.release()
            audioRecord = null
            return false
        }
        
        // FIX: Use larger buffer for reading, then split into frames
        recordingJob = scope.launch {
            // Use a larger buffer to read more data at once
            val largeBuffer = ShortArray(bufferSize / 2)
            
            try {
                while (isActive && isRecording) {
                    val readResult = audioRecord?.read(largeBuffer, 0, largeBuffer.size) ?: 0
                    
                    // DEBUG LOG - Critical for debugging
                    if (readResult > 0) {
                        Log.d(TAG, "Read $readResult samples from mic")
                        
                        // Split into 512-sample frames
                        var offset = 0
                        while (offset + FRAME_SIZE <= readResult) {
                            val frame = largeBuffer.copyOfRange(offset, offset + FRAME_SIZE)
                            
                            // Only process full frames
                            if (frame.size == FRAME_SIZE) {
                                // Pass raw audio - VOICE_RECOGNITION provides hardware AGC
                                withContext(Dispatchers.Main) {
                                    callback.onAudioData(frame)
                                }
                            }
                            offset += FRAME_SIZE
                        }
                    } else if (readResult < 0) {
                        // Error codes
                        val errorMsg = when (readResult) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
                            AudioRecord.ERROR_BAD_VALUE -> "Bad value"
                            AudioRecord.ERROR_DEAD_OBJECT -> "Dead object"
                            AudioRecord.ERROR -> "Unknown error"
                            else -> "Error code: $readResult"
                        }
                        Log.e(TAG, "AudioRecord error: $errorMsg")
                        withContext(Dispatchers.Main) {
                            callback.onError("Recording error: $errorMsg")
                        }
                        break
                    } else {
                        // readResult == 0, no data available
                        Log.d(TAG, "Read 0 samples (no data)")
                    }
                    
                    // Small delay to prevent tight loop but keep responsive
                    delay(1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Recording error: ${e.message}")
                }
            }
        }
        
        return true
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        Log.i(TAG, "Stopping recording...")
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
            Log.i(TAG, "AudioRecord stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording: ${e.message}")
        }
        
        audioRecord?.release()
        audioRecord = null
        
        Log.i(TAG, "Recording stopped")
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Release all resources
     */
    fun release() {
        Log.i(TAG, "Releasing SimpleAudioRecorder...")
        stopRecording()
        scope.cancel()
        Log.i(TAG, "SimpleAudioRecorder released")
    }
}
