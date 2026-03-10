package com.projekt_x.studybuddy.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
    
    // Audio effects for echo cancellation and noise suppression
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    // Barge-in detection - MUCH LESS AGGRESSIVE
    // Only triggers on CLEAR, SUSTAINED human speech - ignores typing, horns, rain, animals
    private var isTTSSpeaking = false
    // INCREASED: 2500 energy threshold - ignores typing (short spikes) and light rain
    private var ttsEnergyThreshold = 2500
    private var consecutiveHighEnergyFrames = 0
    private var consecutiveLowEnergyFrames = 0
    // INCREASED: 20 frames = ~640ms of sustained high energy required
    // Human speech is sustained; horns/typing are brief spikes
    private val HIGH_ENERGY_THRESHOLD = 20
    private val LOW_ENERGY_THRESHOLD = 5  // Frames to confirm TTS stopped
    
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
        
        Log.i(TAG, "AudioRecord initialized successfully (sessionId: ${audioRecord?.audioSessionId})")
        
        // Initialize audio effects for echo cancellation
        audioRecord?.audioSessionId?.let { sessionId ->
            initAudioEffects(sessionId)
        }
        
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
        
        // Release audio effects
        releaseAudioEffects()
        
        Log.i(TAG, "Recording stopped")
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Set TTS speaking state for barge-in detection
     */
    fun setTTSSpeaking(speaking: Boolean) {
        isTTSSpeaking = speaking
        if (speaking) {
            consecutiveHighEnergyFrames = 0
            consecutiveLowEnergyFrames = 0
        }
    }
    
    /**
     * Check if user is trying to interrupt (barge-in detection)
     * Returns true if user speech detected during TTS
     */
    fun detectBargeIn(audioData: ShortArray): Boolean {
        if (!isTTSSpeaking) return false
        
        // Calculate RMS energy
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / audioData.size)
        
        // During TTS, if we detect high energy, it's likely user interrupting
        // (AEC should filter out most of TTS, but residual + user speech = higher energy)
        if (rms > ttsEnergyThreshold) {
            consecutiveHighEnergyFrames++
            consecutiveLowEnergyFrames = 0
            if (consecutiveHighEnergyFrames >= HIGH_ENERGY_THRESHOLD) {
                Log.d(TAG, "Barge-in detected! RMS: $rms")
                return true
            }
        } else {
            consecutiveLowEnergyFrames++
            if (consecutiveLowEnergyFrames >= LOW_ENERGY_THRESHOLD) {
                consecutiveHighEnergyFrames = 0
            }
        }
        return false
    }
    
    /**
     * Check if audio effects are available on this device
     */
    fun areAudioEffectsAvailable(): Boolean {
        return AcousticEchoCanceler.isAvailable() || 
               AutomaticGainControl.isAvailable() || 
               NoiseSuppressor.isAvailable()
    }
    
    /**
     * Initialize audio effects (AEC, AGC, NS)
     */
    private fun initAudioEffects(audioSessionId: Int) {
        try {
            // Acoustic Echo Canceler - removes speaker output from mic input
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                acousticEchoCanceler?.enabled = true
                Log.i(TAG, "✓ AcousticEchoCanceler enabled")
            } else {
                Log.w(TAG, "AcousticEchoCanceler not available on this device")
            }
            
            // Automatic Gain Control
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)
                automaticGainControl?.enabled = true
                Log.i(TAG, "✓ AutomaticGainControl enabled")
            }
            
            // Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.i(TAG, "✓ NoiseSuppressor enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing audio effects: ${e.message}")
        }
    }
    
    /**
     * Release audio effects
     */
    private fun releaseAudioEffects() {
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
            automaticGainControl?.release()
            automaticGainControl = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
    }
    
    /**
     * Release all resources
     */
    fun release() {
        Log.i(TAG, "Releasing SimpleAudioRecorder...")
        stopRecording()
        releaseAudioEffects()
        scope.cancel()
        Log.i(TAG, "SimpleAudioRecorder released")
    }
}
