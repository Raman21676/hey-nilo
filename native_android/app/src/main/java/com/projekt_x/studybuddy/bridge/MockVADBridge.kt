package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Mock VAD Bridge for testing voice pipeline
 * 
 * Simulates Voice Activity Detection without requiring the ONNX model.
 * Useful for testing the voice pipeline architecture before integrating
 * the real Silero VAD model.
 * 
 * To switch to real VAD: Replace MockVADBridge with VADBridge in MainActivity
 */
class MockVADBridge(private val context: Context) : BaseBridge(), VADBridgeInterface {

    companion object {
        private const val TAG = "MockVADBridge"
        private const val SIMULATION_MODE = true // Set false to use real detection logic
    }

    private var isModelLoaded: Boolean = false
    private var simulationJob: Job? = null
    private var mockSpeechProbability: Float = 0f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Simulation state
    private var isSimulatingSpeech: Boolean = false

    override fun getBridgeName(): String = "MockVADBridge"

    override fun init(): Boolean {
        updateState(State.INITIALIZING)
        
        return try {
            Log.i(TAG, "Mock VAD initialized (simulation mode)")
            updateState(State.READY)
            true
        } catch (e: Exception) {
            setError("Failed to initialize: ${e.message}")
            false
        }
    }

    override fun release(): Boolean {
        return safeRelease {
            simulationJob?.cancel()
            scope.cancel()
            isModelLoaded = false
            Log.i(TAG, "Mock VAD released")
            true
        }
    }

    override fun isLoaded(): Boolean = isModelLoaded
    
    // VADBridgeInterface implementation
    override fun isAvailable(): Boolean = isLoaded()
    override fun getName(): String = "Mock VAD (Simulation)"
    override fun processAudioChunk(audioData: ShortArray): Boolean = detectVoice(audioData)

    /**
     * "Load" the mock model - just marks as loaded
     */
    fun loadModel(path: String): Boolean {
        if (currentState != State.READY) {
            setError("Cannot load model: bridge not ready")
            return false
        }

        updateState(State.LOADING)
        
        // Simulate loading delay
        Thread.sleep(100)
        
        isModelLoaded = true
        updateState(State.READY)
        Log.i(TAG, "Mock VAD model 'loaded' from: $path")
        
        // Start simulation coroutine
        startSimulation()
        
        return true
    }

    /**
     * Detect speech probability
     * In simulation mode: Returns mock values that simulate speech detection
     * In real mode: Would process actual audio
     */
    fun detectSpeechProbability(audioData: ShortArray): Float {
        if (!isLoaded()) {
            Log.w(TAG, "VAD not loaded, cannot detect speech")
            return -1.0f
        }

        return if (SIMULATION_MODE) {
            // Return the current simulated probability
            mockSpeechProbability
        } else {
            // Real implementation would process audio here
            // For now, just return a simple energy-based detection
            calculateEnergyBasedDetection(audioData)
        }
    }

    /**
     * Simple energy-based detection (fallback when not simulating)
     */
    private fun calculateEnergyBasedDetection(audioData: ShortArray): Float {
        if (audioData.isEmpty()) return 0f
        
        // Calculate RMS energy
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / audioData.size)
        
        // Normalize to 0-1 range (rough approximation)
        // Speech typically has RMS > 500, silence < 100
        val normalized = (rms / 1000.0).coerceIn(0.0, 1.0)
        
        return normalized.toFloat()
    }

    /**
     * Check if audio chunk contains speech
     */
    fun detectVoice(audioData: ShortArray): Boolean {
        val probability = detectSpeechProbability(audioData)
        return probability >= 0.5f
    }

    /**
     * ByteArray version for convenience
     */
    fun detectVoice(audioData: ByteArray): Boolean {
        val shortArray = ShortArray(audioData.size / 2)
        for (i in shortArray.indices) {
            shortArray[i] = ((audioData[i * 2 + 1].toInt() shl 8) or 
                            (audioData[i * 2].toInt() and 0xFF)).toShort()
        }
        return detectVoice(shortArray)
    }

    /**
     * Reset VAD state
     */
    fun reset() {
        mockSpeechProbability = 0f
        isSimulatingSpeech = false
        Log.d(TAG, "Mock VAD state reset")
    }

    /**
     * Set threshold (mock - does nothing but maintains API compatibility)
     */
    fun setThreshold(threshold: Float) {
        Log.i(TAG, "Mock threshold set to: $threshold (no effect in simulation)")
    }

    /**
     * Get mock model path
     */
    fun getModelPath(): String {
        return "mock_vad_model.onnx"
    }

    /**
     * Check if "downloaded" (always true for mock)
     */
    fun isModelDownloaded(): Boolean = true

    /**
     * Start the simulation that generates mock speech detection
     */
    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            while (isActive) {
                // Simulate periodic speech detection
                // Pattern: silence (2-3s) → speech (2-4s) → silence...
                
                if (!isSimulatingSpeech) {
                    // Silence phase
                    mockSpeechProbability = (0.1f + Math.random() * 0.2f).toFloat()
                    delay(2000 + (Math.random() * 1000).toLong())
                    isSimulatingSpeech = true
                } else {
                    // Speech phase
                    mockSpeechProbability = (0.6f + Math.random() * 0.35f).toFloat()
                    delay(2000 + (Math.random() * 2000).toLong())
                    isSimulatingSpeech = false
                }
            }
        }
    }

    /**
     * Force a specific speech probability (for testing)
     */
    fun setMockProbability(probability: Float) {
        mockSpeechProbability = probability.coerceIn(0f, 1f)
    }

    /**
     * Get current mock probability
     */
    fun getMockProbability(): Float = mockSpeechProbability
}

/**
 * VAD Processor that works with MockVADBridge
 * Same interface as the real VADProcessor
 */
class MockVADProcessor(private val vadBridge: MockVADBridge) {
    
    private var currentState = VADState.SILENCE
    private var speechStartTime: Long = 0
    private var silenceStartTime: Long = 0
    
    private val config = VADConfig()
    
    /**
     * Process audio chunk and return VAD state
     */
    fun process(audioData: ShortArray): VADState {
        val hasSpeech = vadBridge.detectVoice(audioData)
        val currentTime = System.currentTimeMillis()
        
        return when (currentState) {
            VADState.SILENCE -> {
                if (hasSpeech) {
                    speechStartTime = currentTime
                    currentState = VADState.SPEECH_START
                    VADState.SPEECH_START
                } else {
                    VADState.SILENCE
                }
            }
            
            VADState.SPEECH_START -> {
                if (hasSpeech) {
                    if (currentTime - speechStartTime >= config.minSpeechDurationMs) {
                        currentState = VADState.SPEECH
                        VADState.SPEECH
                    } else {
                        VADState.SPEECH_START
                    }
                } else {
                    currentState = VADState.SILENCE
                    VADState.SILENCE
                }
            }
            
            VADState.SPEECH -> {
                if (hasSpeech) {
                    VADState.SPEECH
                } else {
                    silenceStartTime = currentTime
                    currentState = VADState.SPEECH_END
                    VADState.SPEECH_END
                }
            }
            
            VADState.SPEECH_END -> {
                if (hasSpeech) {
                    currentState = VADState.SPEECH
                    VADState.SPEECH
                } else {
                    if (currentTime - silenceStartTime >= config.minSilenceDurationMs) {
                        currentState = VADState.SILENCE
                        VADState.SILENCE
                    } else {
                        VADState.SPEECH_END
                    }
                }
            }
        }
    }
    
    fun isInSpeech(): Boolean {
        return currentState == VADState.SPEECH || currentState == VADState.SPEECH_START
    }
    
    fun reset() {
        currentState = VADState.SILENCE
        vadBridge.reset()
    }
}
