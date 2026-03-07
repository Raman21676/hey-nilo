package com.projekt_x.studybuddy.bridge

import android.util.Log
import kotlinx.coroutines.*

/**
 * Audio Bridge - JNI wrapper for Oboe-based audio recording and playback
 * 
 * This provides low-latency audio I/O using the Oboe library via JNI.
 */
class AudioBridge {
    
    companion object {
        private const val TAG = "AudioBridge"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1  // Mono
        
        init {
            try {
                System.loadLibrary("native_audio")
                Log.i(TAG, "Native audio library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native audio library: ${e.message}")
            }
        }
    }
    
    private var isRecording = false
    private var isPlaying = false
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Start audio recording
     */
    fun startRecording(): Boolean {
        val result = nativeStartRecording(SAMPLE_RATE, CHANNEL_COUNT)
        if (result) {
            isRecording = true
            Log.i(TAG, "Recording started")
        } else {
            Log.e(TAG, "Failed to start recording")
        }
        return result
    }
    
    /**
     * Stop audio recording
     */
    fun stopRecording() {
        nativeStopRecording()
        isRecording = false
        readJob?.cancel()
        Log.i(TAG, "Recording stopped")
    }
    
    /**
     * Read available audio data from buffer
     */
    fun readAudioData(maxSamples: Int = SAMPLE_RATE): ByteArray? {
        return nativeReadAudioData(maxSamples)
    }
    
    /**
     * Get number of samples available in buffer
     */
    fun getAvailableSamples(): Int {
        return nativeGetAvailableSamples()
    }
    
    /**
     * Clear recording buffer
     */
    fun clearRecordingBuffer() {
        nativeClearRecordingBuffer()
    }
    
    /**
     * Start audio playback
     */
    fun startPlayback(): Boolean {
        val result = nativeStartPlayback(SAMPLE_RATE, CHANNEL_COUNT)
        if (result) {
            isPlaying = true
            Log.i(TAG, "Playback started")
        }
        return result
    }
    
    /**
     * Stop audio playback
     */
    fun stopPlayback() {
        nativeStopPlayback()
        isPlaying = false
        Log.i(TAG, "Playback stopped")
    }
    
    /**
     * Queue audio data for playback
     */
    fun queueAudioData(audioData: ByteArray) {
        nativeQueueAudioData(audioData)
    }
    
    /**
     * Clear playback buffer
     */
    fun clearPlaybackBuffer() {
        nativeClearPlaybackBuffer()
    }
    
    /**
     * Release resources
     */
    fun release() {
        stopRecording()
        stopPlayback()
        nativeCleanup()
        scope.cancel()
    }
    
    // Native methods
    private external fun nativeStartRecording(sampleRate: Int, channelCount: Int): Boolean
    private external fun nativeStopRecording()
    private external fun nativeReadAudioData(maxSamples: Int): ByteArray?
    private external fun nativeGetAvailableSamples(): Int
    private external fun nativeClearRecordingBuffer()
    
    private external fun nativeStartPlayback(sampleRate: Int, channelCount: Int): Boolean
    private external fun nativeStopPlayback()
    private external fun nativeQueueAudioData(audioData: ByteArray)
    private external fun nativeClearPlaybackBuffer()
    
    private external fun nativeCleanup()
}

/**
 * Kotlin wrapper for audio recording with callback-based API
 */
class AudioRecorder(private val sampleRate: Int = 16000) {
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val FRAME_SIZE = 512  // 32ms at 16kHz
    }
    
    private val audioBridge = AudioBridge()
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    interface AudioCallback {
        fun onAudioData(audioData: ByteArray)
        fun onError(error: String)
    }
    
    /**
     * Start recording with callback for audio data
     */
    fun startRecording(callback: AudioCallback) {
        if (!audioBridge.startRecording()) {
            callback.onError("Failed to start recording")
            return
        }
        
        recordingJob = scope.launch {
            try {
                while (isActive) {
                    val available = audioBridge.getAvailableSamples()
                    if (available >= FRAME_SIZE) {
                        val data = audioBridge.readAudioData(FRAME_SIZE)
                        if (data != null && data.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                callback.onAudioData(data)
                            }
                        }
                    }
                    delay(10)  // 10ms poll interval
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onError("Recording error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        recordingJob?.cancel()
        audioBridge.stopRecording()
    }
    
    /**
     * Release resources
     */
    fun release() {
        stopRecording()
        audioBridge.release()
    }
}
