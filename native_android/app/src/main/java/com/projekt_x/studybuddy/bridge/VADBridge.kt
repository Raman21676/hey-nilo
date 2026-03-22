package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.projekt_x.studybuddy.model.ModelInfo
import java.io.File

/**
 * Bridge for Voice Activity Detection (VAD)
 * 
 * Uses Silero VAD to detect speech vs silence in audio streams.
 * This is critical for battery life - only process audio when speech detected.
 * 
 * Features:
 * - Real-time voice detection
 * - Configurable threshold
 * - Low CPU usage (~1%)
 * - <10ms latency
 * 
 * Usage:
 * ```
 * val vad = VADBridge(context)
 * vad.init()
 * vad.loadModel("path/to/silero_vad.onnx")
 * 
 * // In audio loop
 * if (vad.detectVoice(audioChunk)) {
 *     // Process speech
 * }
 * ```
 */
interface VADBridgeInterface {
    fun isAvailable(): Boolean
    fun getName(): String
    fun processAudioChunk(audioData: ShortArray): Boolean
}

class VADBridge(private val context: Context) : BaseBridge(), VADBridgeInterface {

    companion object {
        private const val TAG = "VADBridge"
        private const val DEFAULT_THRESHOLD = 0.25f  // FIXED: Lowered for Samsung Tab A7 Lite
        private const val SAMPLE_RATE = 16000 // 16kHz
    }

    // Default VAD model info
    val modelInfo = ModelInfo.Voice(
        name = "Silero VAD",
        url = "https://github.com/snakers4/silero-vad/raw/master/files/silero_vad.onnx",
        filename = "silero_vad.onnx",
        size = 1_000_000L,
        ramRequiredMB = 50,
        type = ModelInfo.VoiceModelType.VAD,
        description = "Voice Activity Detection (1MB)"
    )

    private var modelPath: String? = null
    private var threshold: Float = DEFAULT_THRESHOLD
    private var isModelLoaded: Boolean = false

    // Native state
    private var nativeHandle: Long = 0

    // ===================================================================
    // BaseBridge Implementation
    // ===================================================================

    override fun getBridgeName(): String = "VADBridge"

    override fun init(): Boolean {
        if (currentState == State.READY) {
            Log.w(TAG, "Already initialized")
            return true
        }

        updateState(State.INITIALIZING)

        return try {
            System.loadLibrary("ai-chat")
            nativeHandle = nativeInit()
            
            if (nativeHandle == 0L) {
                setError("Failed to initialize native VAD")
                return false
            }

            Log.i(TAG, "VAD initialized successfully")
            updateState(State.READY)
            true
        } catch (e: Exception) {
            setError("Failed to initialize: ${e.message}")
            false
        }
    }

    override fun release(): Boolean {
        return safeRelease {
            try {
                if (nativeHandle != 0L) {
                    nativeRelease(nativeHandle)
                    nativeHandle = 0
                }
                modelPath = null
                isModelLoaded = false
                Log.i(TAG, "VAD released")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing VAD: ${e.message}")
                false
            }
        }
    }

    override fun isLoaded(): Boolean = isModelLoaded && nativeHandle != 0L
    
    // VADBridgeInterface implementation
    override fun isAvailable(): Boolean = isLoaded()
    override fun getName(): String = "Silero VAD (ONNX)"
    override fun processAudioChunk(audioData: ShortArray): Boolean = detectVoice(audioData)
    fun destroy() = release()

    // ===================================================================
    // VAD-Specific Methods
    // ===================================================================

    /**
     * Initialize with external directory (finds model automatically)
     */
    fun initialize(externalDir: File): Boolean {
        if (!init()) return false
        
        val modelFile = File(externalDir, "models/vad/silero_vad.onnx")
        return if (modelFile.exists()) {
            loadModel(modelFile.absolutePath)
        } else {
            Log.w(TAG, "VAD model not found at ${modelFile.absolutePath}")
            false
        }
    }
    
    /**
     * Load Silero VAD model
     * 
     * @param path Path to silero_vad.onnx file
     * @return true if loaded successfully
     */
    fun loadModel(path: String): Boolean {
        // Don't use assertReady() here because isLoaded() returns false before model is loaded
        if (currentState != State.READY) {
            setError("Cannot load model: bridge not ready (state: $currentState)")
            return false
        }

        updateState(State.LOADING)

        return try {
            val file = File(path)
            if (!file.exists()) {
                setError("Model file not found: $path")
                return false
            }

            val success = nativeLoadModel(nativeHandle, path)
            if (success) {
                modelPath = path
                isModelLoaded = true
                updateState(State.READY)
                Log.i(TAG, "VAD model loaded: $path")
            } else {
                setError("Native loadModel returned false")
            }
            success
        } catch (e: Exception) {
            setError("Exception loading model: ${e.message}")
            false
        }
    }

    /**
     * Detect voice in audio chunk
     * 
     * @param audioData PCM audio data (16-bit, 16kHz, mono)
     * @return Probability of speech (0.0 - 1.0), or -1.0 on error
     */
    fun detectSpeechProbability(audioData: ShortArray): Float {
        if (!isLoaded()) {
            Log.w(TAG, "VAD not loaded, cannot detect speech")
            return -1.0f
        }
        
        // RELAXED: Accept non-512 frames (pad or truncate)
        val processedFrame = if (audioData.size == 512) {
            audioData
        } else {
            // Pad with zeros or truncate to 512 samples
            ShortArray(512) { i ->
                if (i < audioData.size) audioData[i] else 0
            }
        }

        return try {
            val prob = nativeDetectSpeech(nativeHandle, processedFrame)
            
            // DEBUG LOG - Always log for now to see what's happening
            Log.v(TAG, "VAD prob: ${"%.3f".format(prob)} for ${audioData.size} samples")
            
            prob
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting speech: ${e.message}")
            -1.0f
        }
    }
    
    /**
     * Calculate RMS of audio for debugging
     */
    private fun calculateRMS(audioData: ShortArray): Float {
        var sum = 0.0
        for (sample in audioData) {
            sum += (sample * sample)
        }
        return kotlin.math.sqrt(sum / audioData.size).toFloat()
    }

    /**
     * Check if audio chunk contains speech
     * 
     * @param audioData PCM audio data (16-bit, 16kHz, mono)
     * @return true if speech detected (above threshold)
     */
    fun detectVoice(audioData: ShortArray): Boolean {
        val probability = detectSpeechProbability(audioData)
        val isSpeech = probability >= threshold
        
        // Debug: Log when speech is detected
        if (isSpeech && probability > 0) {
            Log.d(TAG, "Speech detected! prob=${"%.3f".format(probability)} >= threshold=$threshold")
        }
        
        return isSpeech
    }

    /**
     * Check if audio chunk contains voice (ByteArray version)
     * 
     * @param audioData PCM audio data as bytes (16-bit little-endian)
     * @return true if speech detected
     */
    fun detectVoice(audioData: ByteArray): Boolean {
        // Convert ByteArray to ShortArray (16-bit PCM)
        val shortArray = ShortArray(audioData.size / 2)
        for (i in shortArray.indices) {
            shortArray[i] = ((audioData[i * 2 + 1].toInt() shl 8) or 
                            (audioData[i * 2].toInt() and 0xFF)).toShort()
        }
        return detectVoice(shortArray)
    }

    /**
     * Set detection threshold
     * 
     * @param threshold Value between 0.0 and 1.0 (default: 0.5)
     *                  Lower = more sensitive, Higher = less sensitive
     */
    fun setThreshold(threshold: Float) {
        this.threshold = threshold.coerceIn(0.0f, 1.0f)
        Log.i(TAG, "VAD threshold set to: ${this.threshold}")
    }

    /**
     * Get current threshold
     */
    fun getThreshold(): Float = threshold

    /**
     * Reset VAD state (call when starting new audio stream)
     */
    fun reset() {
        if (isLoaded()) {
            try {
                nativeReset(nativeHandle)
                Log.d(TAG, "VAD state reset")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting VAD: ${e.message}")
            }
        }
    }

    /**
     * Get model download path
     */
    fun getModelPath(): String {
        return File(context.getExternalFilesDir(null), "models/${modelInfo.filename}").absolutePath
    }

    /**
     * Check if model is already downloaded
     */
    fun isModelDownloaded(): Boolean {
        return File(getModelPath()).exists()
    }

    // ===================================================================
    // Native Methods
    // ===================================================================

    private external fun nativeInit(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeLoadModel(handle: Long, path: String): Boolean
    private external fun nativeDetectSpeech(handle: Long, audioData: ShortArray): Float
    private external fun nativeReset(handle: Long)
}

/**
 * Configuration for VAD processing
 */
data class VADConfig(
    // FIX: Balanced threshold for Samsung Tab A7 Lite
    val threshold: Float = 0.30f,
    val sampleRate: Int = 16000,
    val frameDurationMs: Int = 30,  // 30ms frames
    val minSpeechDurationMs: Int = 200,  // Minimum speech to confirm (ms)
    // INCREASED: Wait longer for user to finish speaking (prevents cutting off mid-sentence)
    val minSilenceDurationMs: Int = 800
)

/**
 * VAD processing state
 */
enum class VADState {
    SILENCE,      // No speech detected
    SPEECH_START, // Speech just started
    SPEECH,       // Speech ongoing
    SPEECH_END    // Speech just ended
}

/**
 * VAD processor with state management
 */
class VADProcessor(private val vadBridge: VADBridge) {
    
    companion object {
        private const val TAG = "VADProcessor"
    }
    
    private var currentState = VADState.SILENCE
    private var speechStartTime: Long = 0
    private var silenceStartTime: Long = 0
    private var lastSpeechFrameTime: Long = 0  // DEBUG: Track when we last saw speech
    
    private val config = VADConfig()
    
    /**
     * Process audio chunk and return VAD state
     * 
     * FIXED: The old state machine had a fatal bug where SPEECH → !hasSpeech
     * immediately transitioned to SPEECH_END, which VoicePipelineManager treated
     * as "speech is done, send to STT". Natural word gaps (200-400ms) would
     * truncate audio mid-sentence, causing Whisper to hallucinate garbage.
     * 
     * NEW BEHAVIOR:
     * - SPEECH stays in SPEECH while silence accumulates (keeps buffering audio)
     * - Only emits SPEECH_END after minSilenceDurationMs of continuous silence
     * - If speech resumes during the silence window, silenceStartTime resets
     * 
     * @param audioData PCM audio data
     * @return Current VAD state
     */
    fun process(audioData: ShortArray): VADState {
        val hasSpeech = vadBridge.detectVoice(audioData)
        val currentTime = System.currentTimeMillis()
        
        return when (currentState) {
            VADState.SILENCE -> {
                if (hasSpeech) {
                    speechStartTime = currentTime
                    silenceStartTime = 0
                    currentState = VADState.SPEECH_START
                    Log.d(TAG, "SILENCE → SPEECH_START")
                    VADState.SPEECH_START
                } else {
                    VADState.SILENCE
                }
            }
            
            VADState.SPEECH_START -> {
                if (hasSpeech) {
                    // Keep accumulating — require minSpeechDurationMs before confirming speech
                    if (currentTime - speechStartTime >= config.minSpeechDurationMs) {
                        currentState = VADState.SPEECH
                        silenceStartTime = 0
                        Log.d(TAG, "SPEECH_START → SPEECH (confirmed after ${currentTime - speechStartTime}ms)")
                        VADState.SPEECH
                    } else {
                        VADState.SPEECH_START
                    }
                } else {
                    // Brief noise, not real speech
                    currentState = VADState.SILENCE
                    Log.d(TAG, "SPEECH_START → SILENCE (false trigger)")
                    VADState.SILENCE
                }
            }
            
            VADState.SPEECH -> {
                if (hasSpeech) {
                    // Active speech — reset any silence accumulation
                    silenceStartTime = 0
                    lastSpeechFrameTime = currentTime  // DEBUG
                    VADState.SPEECH
                } else {
                    // No speech in this frame — start or continue silence accumulation
                    if (silenceStartTime == 0L) {
                        silenceStartTime = currentTime
                        Log.d(TAG, "Starting silence accumulation at ${currentTime} (config: ${config.minSilenceDurationMs}ms)")
                    }
                    
                    val silenceDuration = currentTime - silenceStartTime
                    
                    // DEBUG: Log every 100ms of silence to track accumulation
                    if (silenceDuration % 100 < 32) {
                        Log.d(TAG, "Accumulating silence: ${silenceDuration}ms / ${config.minSilenceDurationMs}ms")
                    }
                    
                    if (silenceDuration >= config.minSilenceDurationMs) {
                        // Enough sustained silence — NOW we end the speech segment
                        currentState = VADState.SPEECH_END
                        Log.i(TAG, "SPEECH → SPEECH_END (after ${silenceDuration}ms silence, threshold was ${config.minSilenceDurationMs}ms)")
                        VADState.SPEECH_END
                    } else {
                        // Stay in SPEECH — this is likely a natural word gap
                        // VoicePipelineManager keeps buffering audio in this state
                        VADState.SPEECH
                    }
                }
            }
            
            VADState.SPEECH_END -> {
                // SPEECH_END is now a terminal transition state
                // Immediately go to SILENCE for the next cycle
                currentState = VADState.SILENCE
                silenceStartTime = 0
                Log.d(TAG, "SPEECH_END → SILENCE (ready for next utterance)")
                VADState.SILENCE
            }
        }
    }
    
    /**
     * Check if currently in speech state
     */
    fun isInSpeech(): Boolean {
        return currentState == VADState.SPEECH || currentState == VADState.SPEECH_START
    }
    
    /**
     * Reset processor state
     */
    fun reset() {
        currentState = VADState.SILENCE
        silenceStartTime = 0
        speechStartTime = 0
        vadBridge.reset()
    }
}
