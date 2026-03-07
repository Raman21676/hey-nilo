package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Real STT Bridge using Whisper.cpp
 * 
 * Uses whisper.cpp for local speech recognition.
 * Models: ggml-tiny.en.bin (39MB) or ggml-base.en.bin (74MB)
 */
class RealSTTBridge(private val context: Context) : BaseBridge(), STTBridgeInterface {
    
    companion object {
        private const val TAG = "RealSTTBridge"
        private const val SAMPLE_RATE = 16000
        
        // Known Whisper hallucinations on silence/noise — reject these
        private val HALLUCINATIONS = setOf(
            "bye bye", "bye-bye", "thank you", "thanks for watching",
            "you", ".", " ", "the", "and", "um", "uh", "a", "an",
            "sorry", "sorry jen", "excuse me", "jen", "i'm sorry"
        )
        
        init {
            try {
                System.loadLibrary("ai-chat")
                Log.d(TAG, "Loaded ai-chat JNI library with Whisper")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load ai-chat: ${e.message}")
            }
        }
        
        /**
         * Check if transcription is valid (not a hallucination)
         */
        fun isValidTranscription(text: String): Boolean {
            val cleaned = text.trim().lowercase()
            if (cleaned.length < 4) return false          // too short
            if (cleaned in HALLUCINATIONS) return false   // known hallucination
            if (cleaned.all { !it.isLetter() }) return false  // no real words
            return true
        }
    }
    
    private var nativeHandle: Long = 0
    private var isModelLoaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    interface TranscriptionCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }
    
    var callback: TranscriptionCallback? = null
    
    override fun getBridgeName(): String = "WhisperSTTBridge"
    
    override fun init(): Boolean {
        updateState(State.INITIALIZING)
        return try {
            nativeHandle = nativeInit()
            if (nativeHandle == 0L) {
                setError("Failed to initialize native context")
                return false
            }
            Log.i(TAG, "Whisper STT initialized, handle: $nativeHandle")
            updateState(State.READY)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            setError("Failed to initialize: ${e.message}")
            false
        }
    }
    
    /**
     * Initialize with Whisper model from external storage
     */
    fun initialize(externalDir: File): Boolean {
        if (!init()) return false
        
        return try {
            val modelDir = File(externalDir, "models/whisper")
            
            // Use TINY model for SPEED (~2-3s latency vs ~10s for base)
            // Trade-off: slightly less accuracy but much faster response
            val tinyModelFile = File(modelDir, "ggml-tiny.en.bin")
            val baseModelFile = File(modelDir, "ggml-base.en.bin")
            
            val modelFile = if (tinyModelFile.exists()) {
                Log.i(TAG, "Using TINY model for speed (39MB, ~2-3s latency)")
                tinyModelFile
            } else if (baseModelFile.exists()) {
                Log.w(TAG, "Tiny model not found, falling back to BASE (74MB, ~10s latency)")
                baseModelFile
            } else {
                Log.e(TAG, "No Whisper models found!")
                Log.e(TAG, "Looking for: ${tinyModelFile.absolutePath} or ${baseModelFile.absolutePath}")
                return false
            }
            
            Log.i(TAG, "Loading model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
            
            val success = nativeLoadModel(nativeHandle, modelFile.absolutePath)
            if (!success) {
                Log.e(TAG, "Failed to load model via JNI")
                return false
            }
            
            isModelLoaded = true
            updateState(State.READY)
            Log.i(TAG, "✓ Whisper STT initialized (${modelFile.name})")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize STT: ${e.message}", e)
            setError("Model initialization failed: ${e.message}")
            false
        }
    }
    
    override fun release(): Boolean {
        return safeRelease {
            scope.cancel()
            if (nativeHandle != 0L) {
                nativeRelease(nativeHandle)
                nativeHandle = 0
            }
            isModelLoaded = false
            Log.i(TAG, "Whisper STT released")
            true
        }
    }
    
    override fun isLoaded(): Boolean = isModelLoaded && nativeHandle != 0L
    override fun isAvailable(): Boolean = isLoaded()
    override fun getName(): String = "Whisper.cpp STT"
    
    /**
     * Transcribe audio samples
     * 
     * @param audioData PCM audio data (16-bit, 16kHz, mono)
     * @return Transcribed text
     */
    fun transcribe(audioData: ShortArray): String {
        if (!isModelLoaded || nativeHandle == 0L) {
            Log.w(TAG, "STT not loaded, cannot transcribe")
            return ""
        }
        
        if (audioData.isEmpty()) {
            Log.w(TAG, "Empty audio data")
            return ""
        }
        
        return try {
            Log.i(TAG, "Transcribing ${audioData.size} samples...")
            
            val result = nativeTranscribe(nativeHandle, audioData, SAMPLE_RATE)
            
            // Filter out hallucinations
            return if (result != null && isValidTranscription(result)) {
                Log.i(TAG, "STT Result: $result")
                result
            } else {
                if (result != null) {
                    Log.w(TAG, "Filtered hallucination: '$result'")
                }
                ""
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            ""
        }
    }
    
    // Native methods
    private external fun nativeInit(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeLoadModel(handle: Long, modelPath: String): Boolean
    private external fun nativeTranscribe(handle: Long, audioData: ShortArray, sampleRate: Int): String
}
