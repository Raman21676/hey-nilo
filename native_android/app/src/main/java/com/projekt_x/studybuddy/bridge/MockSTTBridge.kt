package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Mock STT Bridge for testing voice pipeline
 * 
 * SIMULATION MODE: This mock does NOT actually listen to your voice.
 * Instead, it demonstrates the STT flow with simulated transcription.
 * 
 * For testing, you can:
 * 1. Let it auto-generate random sentences (current behavior)
 * 2. Use manual input mode (tap screen to enter text)
 * 
 * To switch to real STT: Replace MockSTTBridge with STTBridge
 */
class MockSTTBridge(private val context: Context) : BaseBridge(), STTBridgeInterface {

    companion object {
        private const val TAG = "MockSTTBridge"
        
        // Demo sentences for simulation
        private val DEMO_SENTENCES = listOf(
            "Hello, how can I help you today?",
            "What is the weather like?",
            "Tell me a joke please.",
            "What time is it?",
            "How are you doing?",
            "Can you help me with something?",
            "What's new?",
            "Nice to meet you."
        )
    }

    private var isModelLoaded: Boolean = false
    private var currentLanguage: String = "en"
    
    // Callback for streaming transcription
    private var transcriptionCallback: TranscriptionCallback? = null
    
    // Simulation
    private var simulationJob: Job? = null
    private var isActiveFlag = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // For manual input mode
    private var manualInputText: String? = null
    private var useManualInput = false

    interface TranscriptionCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    override fun getBridgeName(): String = "MockSTTBridge"

    override fun init(): Boolean {
        updateState(State.INITIALIZING)
        
        return try {
            Log.i(TAG, "Mock STT initialized")
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
            Log.i(TAG, "Mock STT released")
            true
        }
    }

    override fun isLoaded(): Boolean = isModelLoaded
    
    // STTBridgeInterface implementation
    override fun isAvailable(): Boolean = isLoaded()
    override fun getName(): String = "Mock STT (Simulation)"

    /**
     * "Load" the mock model
     */
    fun loadModel(modelPath: String, language: String = "en"): Boolean {
        if (currentState != State.READY) {
            setError("Cannot load model: bridge not ready")
            return false
        }

        updateState(State.LOADING)
        
        // Simulate loading delay
        Thread.sleep(150)
        
        currentLanguage = language
        isModelLoaded = true
        updateState(State.READY)
        Log.i(TAG, "Mock STT model 'loaded' - language: $language")
        
        return true
    }

    /**
     * Set transcription callback for streaming results
     */
    fun setTranscriptionCallback(callback: TranscriptionCallback) {
        transcriptionCallback = callback
    }

    /**
     * Start transcribing audio stream
     * In mock mode: Auto-simulates transcription after delay
     */
    fun startTranscription() {
        if (!isLoaded()) {
            Log.w(TAG, "STT not loaded, cannot transcribe")
            return
        }

        stopTranscription()
        
        // Only process if manual input is set
        if (useManualInput && manualInputText != null) {
            Log.i(TAG, "Using manual input: $manualInputText")
            isActiveFlag = true
            simulateManualTranscription(manualInputText!!)
            manualInputText = null // Clear after use
            useManualInput = false
            return
        }
        
        // AUTO-SIMULATION: Simulate hearing "Hello, how are you?" after 3 seconds
        Log.i(TAG, "Listening... (will auto-detect speech in 3 seconds)")
        isActiveFlag = true
        
        simulationJob = scope.launch {
            delay(3000) // Wait 3 seconds like real STT
            if (isActiveFlag) {
                simulateAutoTranscription()
            }
        }
    }
    


    /**
     * Stop transcription
     */
    fun stopTranscription() {
        isActiveFlag = false
        simulationJob?.cancel()
        simulationJob = null
        Log.i(TAG, "Transcription stopped")
    }

    /**
     * Transcribe audio file (non-streaming)
     * Returns empty - NO RANDOM generation
     */
    fun transcribeFile(audioPath: String): String {
        if (!isLoaded()) {
            return "Error: STT not loaded"
        }
        // NO RANDOM OUTPUT - return empty
        return ""
    }

    /**
     * Process audio chunk (for real-time streaming)
     * In mock mode: Doesn't actually process audio, just continues simulation
     */
    fun processAudioChunk(audioData: ShortArray) {
        // In real implementation, this would feed audio to the STT engine
        // For mock, the simulation runs independently
    }

    /**
     * Set manual input text (for testing specific phrases)
     */
    fun setManualInput(text: String) {
        manualInputText = text
        useManualInput = true
        Log.i(TAG, "Manual input set: $text")
    }
    
    /**
     * Check if manual input is pending
     */
    fun hasManualInput(): Boolean = useManualInput && manualInputText != null
    
    /**
     * Simulate transcription from manual input
     */
    private fun simulateManualTranscription(text: String) {
        simulationJob = scope.launch {
            val words = text.split(" ")
            var currentText = ""
            
            for (word in words) {
                if (!isActiveFlag) break
                delay(150) // Fast typing effect
                currentText = if (currentText.isEmpty()) word else "$currentText $word"
                
                withContext(Dispatchers.Main) {
                    transcriptionCallback?.onPartialResult(currentText)
                }
            }
            
            withContext(Dispatchers.Main) {
                transcriptionCallback?.onFinalResult(currentText)
            }
        }
    }
    
    /**
     * Auto-simulation (generates random demo sentence once)
     */
    private suspend fun simulateAutoTranscription() {
        val sentence = DEMO_SENTENCES.random()
        val words = sentence.split(" ")
        
        var currentText = ""
        
        Log.i(TAG, "Auto-generating demo transcription: $sentence")
        
        // Stream words progressively
        for (word in words) {
            if (!isActiveFlag) break
            
            delay(200 + (Math.random() * 200).toLong()) // 200-400ms per word
            
            currentText = if (currentText.isEmpty()) word else "$currentText $word"
            
            withContext(Dispatchers.Main) {
                transcriptionCallback?.onPartialResult(currentText)
            }
        }
        
        // Send final result
        withContext(Dispatchers.Main) {
            transcriptionCallback?.onFinalResult(currentText)
        }
        
        Log.i(TAG, "Demo transcription completed: $currentText")
    }

    /**
     * Set language
     */
    fun setLanguage(language: String) {
        currentLanguage = language
        Log.i(TAG, "Language set to: $language")
    }

    /**
     * Get current language
     */
    fun getLanguage(): String = currentLanguage

    /**
     * Get supported languages (mock)
     */
    fun getSupportedLanguages(): List<String> {
        return listOf("en", "es", "fr", "de", "zh", "ja")
    }

    /**
     * Check if model is downloaded (always true for mock)
     */
    fun isModelDownloaded(): Boolean = true

    /**
     * Get model path (mock)
     */
    fun getModelPath(): String = "mock_stt_model.onnx"
}

/**
 * Data class for transcription result
 */
data class TranscriptionResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float,
    val language: String = "en"
)

/**
 * STT Processor that works with MockSTTBridge
 * Manages the transcription state and callbacks
 */
class MockSTTProcessor(private val sttBridge: MockSTTBridge) {
    
    private var isProcessing: Boolean = false
    private var currentTranscript: String = ""
    
    // Callback for UI updates
    var onTranscriptionUpdate: ((TranscriptionResult) -> Unit)? = null
    
    /**
     * Initialize and start transcription
     */
    fun startListening() {
        if (isProcessing) return
        
        isProcessing = true
        currentTranscript = ""
        
        sttBridge.setTranscriptionCallback(object : MockSTTBridge.TranscriptionCallback {
            override fun onPartialResult(text: String) {
                currentTranscript = text
                onTranscriptionUpdate?.invoke(
                    TranscriptionResult(
                        text = text,
                        isFinal = false,
                        confidence = 0.8f + (Math.random() * 0.15f).toFloat()
                    )
                )
            }
            
            override fun onFinalResult(text: String) {
                currentTranscript = text
                onTranscriptionUpdate?.invoke(
                    TranscriptionResult(
                        text = text,
                        isFinal = true,
                        confidence = 0.9f + (Math.random() * 0.08f).toFloat()
                    )
                )
                isProcessing = false
            }
            
            override fun onError(error: String) {
                onTranscriptionUpdate?.invoke(
                    TranscriptionResult(
                        text = "Error: $error",
                        isFinal = true,
                        confidence = 0f
                    )
                )
                isProcessing = false
            }
        })
        
        sttBridge.startTranscription()
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        sttBridge.stopTranscription()
        isProcessing = false
    }
    
    /**
     * Check if currently processing
     */
    fun isListening(): Boolean = isProcessing
    
    /**
     * Get current transcript
     */
    fun getCurrentTranscript(): String = currentTranscript
    
    /**
     * Reset processor
     */
    fun reset() {
        stopListening()
        currentTranscript = ""
    }
}
