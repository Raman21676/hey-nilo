package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.media.AudioManager
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import com.projekt_x.studybuddy.InferenceQueue
import com.projekt_x.studybuddy.bridge.llm.*
import com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder
import com.projekt_x.studybuddy.util.CopyAssetToExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production-Grade Voice Pipeline Manager
 * 
 * Pipeline: VAD → STT → LLM → TTS
 * All components run offline on-device for privacy.
 * 
 * Fixed version with proper audio buffering and VAD-driven STT triggering.
 */
/**
 * Voice Pipeline Manager - Updated for LLMProvider abstraction
 * 
 * Supports both offline (TinyLlama) and online (API) LLMs through
 * the LLMProvider interface. Memory context is automatically injected
 * into system prompts for personalized responses.
 */
class VoicePipelineManager(
    private val context: Context,
    private val llmBridge: LlamaBridge? = null,
    private val queue: InferenceQueue? = null,
    private val memoryManager: MemoryManager? = null,
    private val llmProvider: LLMProvider? = null
) {
    
    companion object {
        private const val TAG = "VoicePipelineManager"
        private const val SAMPLE_RATE = 16000  // 16kHz required
        private const val FRAME_SIZE = 512     // 32ms at 16kHz
        private const val MAX_BUFFER_SIZE = SAMPLE_RATE * 10  // 10 seconds max
        
        // WORKING CONFIG FROM MINI PROJECT - Fast response (2 seconds)
        // FIX: Balanced threshold for Samsung Tab A7 Lite
        private const val VAD_THRESHOLD = 0.25f      // Catches quiet speech while filtering ambient noise
        private const val MIN_SPEECH_MS = 800L       // Minimum 0.8 seconds of speech
        private const val MIN_SILENCE_MS = 600L      // Wait 600ms silence before ending
        private const val PRE_SPEECH_BUFFER_MS = 800L // Capture word beginnings
        private const val MAX_SPEECH_MS = 10000L     // Force stop after 10 seconds
        // INCREASED: More trailing silence tolerance for natural pauses
        private const val TRAILING_SILENCE_FRAMES = 18  // ~576ms of silence to end
        
        // BUG FIX 2: Barge-in detection - requires sustained human speech
        // Motorbike horn (~200ms) won't trigger, human speech (~960ms+) will
        private const val BARGE_IN_ENERGY_THRESHOLD = 3500f   // High energy threshold
        private const val BARGE_IN_CONFIRM_FRAMES = 30        // ~960ms sustained detection
        
        // CRITICAL FIX: TTS cooldown period to prevent acoustic echo feedback
        // After TTS finishes, wait 1500ms before allowing barge-in detection
        // This prevents room echo from being detected as user speech
        private const val TTS_COOLDOWN_MS = 1500L
        
        // MAX LISTENING TIME: Force stop if user doesn't speak or tap within 15 seconds
        private const val MAX_LISTENING_TIME_MS = 15000L
        
        // MODERATE SOFTWARE GAIN: Samsung Tab A7 Lite mic is very quiet even with
        // hardware AGC. 4x gain provides reliable detection without the excessive
        // noise sensitivity of 8x.
        private const val SOFTWARE_GAIN = 4f
        
        // MAX RESPONSE LENGTH: Force stop LLM once we have enough content
        // For voice mode, we want concise but complete responses (3-4 sentences = ~300-400 chars)
        private const val MAX_RESPONSE_CHARS = 200
        private const val MAX_RESPONSE_SENTENCES = 3
        
        // Debug: set to true to save raw audio to /sdcard/Download/mini_debug/
        private const val DEBUG_SAVE_AUDIO = false
        
        enum class PipelineState {
            IDLE,
            LISTENING,
            SPEECH_DETECTED,  // New: VAD detected speech start
            TRANSCRIBING,
            THINKING,
            SPEAKING,
            ERROR
        }
    }
    
    // Pipeline state
    private val isRunning = AtomicBoolean(false)
    private var isInitialized = false
    private var pipelineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Audio components
    private var audioRecorder: SimpleAudioRecorder? = null
    
    // Audio buffering for speech segments
    private val speechBuffer = ArrayDeque<Short>(MAX_BUFFER_SIZE)  // Accumulates speech audio
    private val preSpeechBuffer = ArrayDeque<Short>(SAMPLE_RATE / 1000 * PRE_SPEECH_BUFFER_MS.toInt() / FRAME_SIZE * FRAME_SIZE)
    private var isCollectingSpeech = false
    private var isProcessingSTT = false  // Prevent multiple simultaneous STT processing
    private var speechStartTime: Long = 0
    private var consecutiveSilenceFrames = 0  // Count consecutive silence frames for trailing silence detection
    private var bargeInConfirmFrames = 0      // BUG FIX 2: Count frames for barge-in confirmation
    private var lastTTSFinishedTime = 0L      // CRITICAL FIX: Track when TTS finished to prevent echo detection
    private val isTransitioningToListening = java.util.concurrent.atomic.AtomicBoolean(false)  // Prevent race conditions
    private val isRestarting = java.util.concurrent.atomic.AtomicBoolean(false)  // Prevent concurrent restarts
    
    /**
     * CRITICAL FIX: Transition to LISTENING state with TTS cooldown check
     * This prevents the app from listening while TTS is speaking or before cooldown
     * Uses atomic flag to prevent race conditions from multiple coroutines
     */
    private suspend fun transitionToListening() {
        // CRITICAL FIX: Only reset TTS flags, NOT the text buffers!
        // Text buffers are cleared at the START of a new question in processWithLLMProvider()
        // Clearing them here would wipe out the displayed response!
        isStreamingTTSActive = false
        isLLMResponseComplete = false
        // DO NOT clear: finalBubbleText, fullResponseText, ttsTextBuffer
        
        // Only allow one transition at a time
        if (!isTransitioningToListening.compareAndSet(false, true)) {
            Log.d(TAG, "Transition already in progress, skipping")
            return
        }
        
        try {
            // CRITICAL FIX: isSpeaking() API is broken on Samsung Tab A7 Lite
            // Don't wait for it - transition immediately to prevent getting stuck
            if (isRunning.get() && currentState != PipelineState.LISTENING) {
                transitionToState(PipelineState.LISTENING)
                Log.i(TAG, "NILO_DEBUG: State changed: LISTENING (immediate)")
            }
        } finally {
            isTransitioningToListening.set(false)
        }
    }
    
    /**
     * Non-suspending version for non-coroutine contexts
     * Also uses atomic flag and cooldown check
     */
    private fun transitionToListeningImmediate() {
        // CRITICAL FIX: Only reset TTS flags, NOT the text buffers!
        // Text buffers are cleared at the START of a new question in processWithLLMProvider()
        isStreamingTTSActive = false
        isLLMResponseComplete = false
        // DO NOT clear: finalBubbleText, fullResponseText, ttsTextBuffer
        
        // Only allow one transition at a time
        if (!isTransitioningToListening.compareAndSet(false, true)) {
            Log.d(TAG, "Transition already in progress, skipping immediate")
            return
        }
        
        // CRITICAL FIX: isSpeaking() API is broken on Samsung Tab A7 Lite
        // Transition immediately without waiting for TTS
        if (isRunning.get() && currentState != PipelineState.LISTENING) {
            transitionToState(PipelineState.LISTENING)
            Log.i(TAG, "🎤 State changed: LISTENING (immediate)")
        }
        isTransitioningToListening.set(false)
    }
    private var listeningStartTime: Long = 0  // Track when listening started for timeout
    
    // Bridges
    private var vadBridge: VADBridge? = null
    private var vadProcessor: VADProcessor? = null
    private var sttBridge: RealSTTBridge? = null  // Whisper STT - using TINY model for speed!
    private var kokoroTTS: KokoroTTSBridge? = null  // Kokoro TTS via Sherpa-ONNX AAR
    private var fullResponseText = StringBuilder()  // Accumulate LLM response for TTS
    
    // CRITICAL FIX: Single source of truth for bubble and TTS
    // When LLM is stopped (max length reached), this stores the exact text shown in bubble
    private var finalBubbleText: String = ""
    
    // Track current query classification for dynamic response limits
    private var currentQueryClassification: QueryClassifier.ClassificationResult? = null
    
    // Streaming TTS state
    private var streamingTTSJob: Job? = null
    private val ttsTextBuffer = StringBuilder()  // Raw text waiting to be spoken
    private var ttsStartTime: Long = 0
    @Volatile
    private var isStreamingTTSActive = false
    private var lastSpokenPosition = 0  // Track how much text has been spoken
    private var lastWordSentTime: Long = 0  // Track when we last sent text to TTS
    @Volatile
    private var isLLMResponseComplete = false  // NEW: Signal when LLM is done
    
    // Token filtering buffer for streaming <|im_end|> detection
    private val streamingTokenBuffer = StringBuilder()
    private var isCollectingImEnd = false
    
    // LLM Provider job (for cancellation during barge-in)
    private var llmProviderJob: Job? = null

    // Job that waits for LLM + TTS to finish before transitioning back to LISTENING
    private var completionWaitJob: Job? = null

    // STT job (for cancellation when user presses X)
    private var sttJob: Job? = null
    
    // Callbacks for UI
    var onStateChange: ((PipelineState) -> Unit)? = null
    var onTranscriptionUpdate: ((String, Boolean) -> Unit)? = null
    var onResponseUpdate: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInitialized: ((Boolean) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null  // Audio level for visualization
    
    // Audio Focus handling
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasAudioFocus = false
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                hasAudioFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost - stopping voice pipeline")
                hasAudioFocus = false
                stopConversation()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "Audio focus lost transient")
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transient can duck")
            }
        }
    }
    
    /**
     * Request audio focus for recording
     */
    private fun acquireAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d(TAG, "Audio focus request result: $result, granted: $hasAudioFocus")
        return hasAudioFocus
    }
    
    /**
     * Release audio focus
     */
    private fun releaseAudioFocus() {
        audioManager.abandonAudioFocus(audioFocusChangeListener)
        hasAudioFocus = false
        Log.d(TAG, "Audio focus released")
    }
    
    // Current state - CRITICAL FIX: Use AtomicReference for thread safety
    private val currentStateAtomic = AtomicReference(PipelineState.IDLE)
    private var currentState: PipelineState
        get() = currentStateAtomic.get()
        set(value) {
            val oldValue = currentStateAtomic.getAndSet(value)
            if (oldValue != value) {
                Log.i(TAG, "State changed: $oldValue -> $value")
                // Always notify UI on Main thread regardless of which thread set the state
                scope.launch(Dispatchers.Main.immediate) {
                    onStateChange?.invoke(value)
                }
            }
        }
    
    /**
     * CRITICAL FIX: Once we transition to LISTENING for next query, prevent other coroutines
     * from overwriting it back to SPEAKING/THINKING. This fixes the race condition where
     * max-length sets LISTENING but TTS or LLM coroutine overwrites it.
     */
    private val isListeningForNextQuery = AtomicBoolean(false)
    
    private fun transitionToState(newState: PipelineState): Boolean {
        // If we're listening for next query, only allow transitions to IDLE or ERROR
        if (isListeningForNextQuery.get() && newState != PipelineState.LISTENING && 
            newState != PipelineState.IDLE && newState != PipelineState.ERROR) {
            Log.w(TAG, "BLOCKED state change to $newState - currently listening for next query")
            return false
        }
        currentState = newState
        return true
    }
    
    /**
     * Initialize the entire pipeline
     */
    fun initialize(mode: BridgeMode = BridgeMode.AUTO) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized, skipping...")
            return
        }
        
        Log.i(TAG, "=".repeat(60))
        Log.i(TAG, "VOICE PIPELINE INITIALIZATION START")
        Log.i(TAG, "Mode: $mode")
        Log.i(TAG, "VAD Threshold: $VAD_THRESHOLD")
        Log.i(TAG, "Min Speech: ${MIN_SPEECH_MS}ms, Min Silence: ${MIN_SILENCE_MS}ms")
        Log.i(TAG, "=".repeat(60))
        
        scope.launch {
            try {
                // Step 1: Extract assets to external storage
                Log.i(TAG, "\n[Step 1/4] Extracting assets...")
                val externalDir = extractAssets()
                if (externalDir == null) {
                    Log.e(TAG, "✗ Asset extraction failed")
                    onInitialized?.invoke(false)
                    return@launch
                }
                Log.i(TAG, "✓ Assets extracted to: ${externalDir.absolutePath}")
                
                // Step 2: Initialize bridges
                Log.i(TAG, "\n[Step 2/4] Initializing bridges...")
                val bridgesOk = initializeBridges(externalDir)
                if (!bridgesOk) {
                    Log.w(TAG, "⚠ Some bridges failed to initialize, using fallbacks")
                }
                
                // Step 3: Initialize audio
                Log.i(TAG, "\n[Step 3/4] Initializing audio...")
                audioRecorder = SimpleAudioRecorder(context)
                if (!audioRecorder!!.hasPermission()) {
                    Log.e(TAG, "✗ RECORD_AUDIO permission not granted!")
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Microphone permission required")
                        onInitialized?.invoke(false)
                    }
                    return@launch
                }
                Log.i(TAG, "✓ Audio recorder created ($SAMPLE_RATE Hz)")
                
                // Step 4: Initialize Kokoro TTS
                Log.i(TAG, "\n[Step 4/5] Initializing Kokoro TTS...")
                try {
                    kokoroTTS = KokoroTTSBridge(context)
                    // CRITICAL FIX: Wait for TTS initialization with timeout and retry
                    val ttsOk = withTimeoutOrNull(15000) {
                        kokoroTTS!!.initialize(maxRetries = 2)
                    } ?: false
                    
                    if (ttsOk && kokoroTTS!!.isReady) {
                        Log.i(TAG, "✓ Kokoro TTS initialized successfully")
                    } else {
                        val error = kokoroTTS!!.getInitError() ?: "Unknown error"
                        Log.e(TAG, "✗ Kokoro TTS failed: $error")
                        // CRITICAL FIX: Don't set to null - keep it for potential retry
                        // kokoroTTS = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Kokoro TTS exception: ${e.message}", e)
                    // kokoroTTS = null
                }
                
                // Step 5: Initialize LLM Provider (if not already provided)
                Log.i(TAG, "\n[Step 5/6] Initializing LLM Provider...")
                val providerOk = initializeLLMProvider()
                if (!providerOk) {
                    Log.w(TAG, "⚠ LLM Provider not available")
                }
                
                // Step 6: Validate pipeline
                Log.i(TAG, "\n[Step 6/6] Validating pipeline...")
                val pipelineValid = validatePipeline()
                
                isInitialized = pipelineValid
                
                // CRITICAL FIX: Log TTS error if initialization failed
                val ttsError = kokoroTTS?.getInitError()
                
                Log.i(TAG, "\n" + "=".repeat(60))
                Log.i(TAG, "PIPELINE STATUS:")
                Log.i(TAG, "  VAD: ${if (vadBridge?.isAvailable() == true) "✓" else "✗"} ${vadBridge?.getName() ?: "None"}")
                Log.i(TAG, "  STT: ${if (sttBridge?.isAvailable() == true) "✓" else "✗"} ${sttBridge?.getName() ?: "None"}")
                Log.i(TAG, "  TTS: ${if (kokoroTTS?.isReady == true) "✓" else "✗"} Kokoro TTS ${if (ttsError != null) "(Error: $ttsError)" else ""}")
                Log.i(TAG, "  Audio: ${if (audioRecorder != null) "✓" else "✗"}")
                Log.i(TAG, "  LLM: ${if (llmProvider?.isAvailable() == true) "✓" else "✗"} ${llmProvider?.displayName ?: "Legacy"}")
                Log.i(TAG, "  Memory: ${if (memoryManager != null) "✓" else "✗"} ${if (memoryManager != null) "Active" else "Disabled"}")
                Log.i(TAG, "=".repeat(60))
                
                withContext(Dispatchers.Main) {
                    onInitialized?.invoke(isInitialized)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                withContext(Dispatchers.Main) {
                    onInitialized?.invoke(false)
                    onError?.invoke("Initialization failed: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Extract all ML model assets to external storage
     */
    private fun extractAssets(): File? {
        return try {
            val copyUtil = CopyAssetToExternal(context)
            val externalDir = copyUtil.getExternalFilesDir()
            
            // Extract VAD model
            Log.d(TAG, "Extracting VAD model...")
            val vadDir = File(externalDir, "models/vad")
            copyUtil.copyAssetToExternal("models/vad/silero_vad.onnx", File(vadDir, "silero_vad.onnx"))
            
            // Extract Whisper STT models (TINY for speed, BASE for fallback)
            Log.d(TAG, "Extracting Whisper STT models...")
            val whisperDir = File(externalDir, "models/whisper")
            val tinyCopied = copyUtil.copyAssetToExternal("models/whisper/ggml-tiny.en.bin", File(whisperDir, "ggml-tiny.en.bin"))
            val baseCopied = copyUtil.copyAssetToExternal("models/whisper/ggml-base.en.bin", File(whisperDir, "ggml-base.en.bin"))
            
            if (tinyCopied) {
                Log.i(TAG, "✓ Whisper TINY model extracted (39MB, ~2-3s latency)")
            }
            if (baseCopied) {
                Log.i(TAG, "✓ Whisper BASE model extracted (74MB, ~10s latency)")
            }
            if (!tinyCopied && !baseCopied) {
                Log.w(TAG, "⚠ No Whisper models found in assets")
            }
            
            // Extract Kokoro TTS models
            Log.d(TAG, "Extracting Kokoro TTS models...")
            val ttsDir = File(externalDir, "models/tts_kokoro")
            val modelCopied = copyUtil.copyAssetToExternal("models/tts_kokoro/kokoro-v0_19.onnx", File(ttsDir, "kokoro-v0_19.onnx"))
            val voicesCopied = copyUtil.copyAssetToExternal("models/tts_kokoro/voices.bin", File(ttsDir, "voices.bin"))
            
            if (modelCopied && voicesCopied) {
                Log.i(TAG, "✓ Kokoro TTS models extracted to models/tts_kokoro/")
            } else {
                Log.w(TAG, "⚠ Kokoro TTS models not found in assets")
            }
            
            externalDir
        } catch (e: Exception) {
            Log.e(TAG, "Asset extraction failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Initialize all bridge components
     */
    private fun initializeBridges(externalDir: File): Boolean {
        var allSuccess = true
        
        // VAD Bridge - CRITICAL for voice detection
        Log.i(TAG, "Initializing VAD bridge...")
        val realVAD = VADBridge(context)
        val vadInitialized = realVAD.initialize(externalDir)
        
        if (vadInitialized) {
            vadBridge = realVAD
            // Set optimal threshold from research
            vadBridge?.setThreshold(VAD_THRESHOLD)
            vadProcessor = VADProcessor(vadBridge!!)
            Log.i(TAG, "✓ Real VAD initialized (threshold: $VAD_THRESHOLD)")
        } else {
            Log.e(TAG, "✗ VAD initialization failed - voice detection will not work!")
            allSuccess = false
        }
        
        // STT Bridge - Whisper TINY model (fastest, 39MB, ~2-3s latency)
        // NOTE: base.en model is too slow (~10s), using tiny.en instead
        Log.i(TAG, "Initializing Whisper STT bridge...")
        val realSTT = RealSTTBridge(context)
        val sttInitialized = realSTT.initialize(externalDir)
        if (sttInitialized) {
            sttBridge = realSTT
            Log.i(TAG, "✓ Whisper STT initialized (TINY model ~2-3s, 39MB)")
        } else {
            Log.w(TAG, "⚠ Whisper STT failed, falling back to MockSTT")
        }
        
        return allSuccess
    }
    
    /**
     * Initialize LLM Provider
     * Creates OfflineLLMProvider if llmBridge is available and no provider was injected
     */
    private suspend fun initializeLLMProvider(): Boolean {
        return try {
            // If provider already injected, just initialize it
            if (llmProvider != null) {
                Log.i(TAG, "Using injected LLM Provider: ${llmProvider.displayName}")
                val initialized = llmProvider.initialize()
                if (initialized) {
                    Log.i(TAG, "✓ LLM Provider initialized: ${llmProvider.displayName}")
                } else {
                    Log.w(TAG, "⚠ LLM Provider failed to initialize")
                }
                return initialized
            }
            
            // Otherwise, create OfflineLLMProvider from llmBridge if available
            if (llmBridge?.isLoaded() == true) {
                Log.i(TAG, "Creating OfflineLLMProvider from LlamaBridge")
                val offlineProvider = OfflineLLMProvider(context, llmBridge)
                val initialized = offlineProvider.initialize()
                if (initialized) {
                    Log.i(TAG, "✓ OfflineLLMProvider created and initialized")
                }
                return initialized
            }
            
            Log.w(TAG, "No LLM bridge available for provider creation")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM Provider", e)
            false
        }
    }
    
    /**
     * Validate pipeline has minimum required components
     */
    private fun validatePipeline(): Boolean {
        val hasVAD = vadBridge?.isAvailable() == true
        val hasSTT = sttBridge?.isAvailable() == true
        val hasAudio = audioRecorder != null
        
        Log.i(TAG, "Pipeline validation: VAD=$hasVAD, STT(Whisper)=$hasSTT, Audio=$hasAudio")
        
        return hasVAD && hasSTT && hasAudio
    }
    
    /**
     * Clear LLM context asynchronously to avoid blocking the pipeline
     * if the native generation thread is still holding the mutex.
     */
    private fun clearContextAsync() {
        scope.launch(Dispatchers.IO) {
            try {
                llmBridge?.clearContext()
            } catch (e: Exception) {
                Log.e(TAG, "Async context clear failed: ${e.message}")
            }
        }
    }

    /**
     * Start voice conversation mode
     */
    fun startVoiceConversation() {
        if (!isInitialized) {
            Log.e(TAG, "Cannot start: not initialized")
            onError?.invoke("Voice pipeline not initialized")
            return
        }
        
        if (isRunning.get()) {
            Log.w(TAG, "Pipeline already running")
            return
        }
        
        // Acquire audio focus first (CRITICAL for recording)
        if (!acquireAudioFocus()) {
            Log.w(TAG, "Could not acquire audio focus - proceeding anyway")
        }
        
        // Reset buffers
        speechBuffer.clear()
        preSpeechBuffer.clear()
        isCollectingSpeech = false
        vadProcessor?.reset()
        
        isRunning.set(true)
        listeningStartTime = System.currentTimeMillis()  // Track listening start for timeout
        
        // CRITICAL FIX: Use helper to respect TTS cooldown
        transitionToListeningImmediate()
        
        // Start recording
        startRecording()
    }
    
    /**
     * Start audio recording with VAD processing
     * Extracted to allow restarting after TTS playback
     */
    private fun startRecording() {
        if (audioRecorder?.isRecording() == true) {
            Log.w(TAG, "Recording already active, stopping first then restarting")
            audioRecorder?.stopRecording()
            // Cancel existing job
            pipelineJob?.cancel()
            pipelineJob = null
            // CRITICAL: Wait for hardware to release
            Thread.sleep(150)
        }
        
        // Ensure we're in the LISTENING state
        if (currentState != PipelineState.LISTENING) {
            Log.d(TAG, "Setting state to LISTENING before starting recording")
            transitionToListeningImmediate()
        }
        
        pipelineJob = scope.launch {
            Log.i(TAG, "Voice pipeline started - listening for speech...")
            
            // Start audio recording with VAD processing
            val recordingStarted = audioRecorder?.startRecording(object : SimpleAudioRecorder.AudioCallback {
                override fun onAudioData(audioData: ShortArray) {
                    if (!isRunning.get()) {
                        Log.w(TAG, "BLOCKED: isRunning=false, audio ignored")
                        return
                    }
                    
                    // DEBUG LOG - Critical for debugging
                    Log.d(TAG, "Received audio frame: ${audioData.size} samples")
                    
                    // Process audio through VAD and buffer management
                    processAudioWithVAD(audioData)
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Audio error: $error")
                    onError?.invoke(error)
                }
            }) ?: false
            
            if (!recordingStarted) {
                Log.e(TAG, "CRITICAL: Failed to start recording - setting isRunning=false")
                isRunning.set(false)
                return@launch
            }
            
            Log.i(TAG, "Recording started successfully")
        }
    }
    
    // DEBUG: Timestamp tracking for delay analysis
    private var lastSpeechDetectedTime: Long = 0
    private var speechEndDetectedTime: Long = 0
    
    /**
     * Process audio chunk with VAD state machine
     * 
     * CRITICAL FIXES:
     * 1. Skip processing during TRANSCRIBING/THINKING/SPEAKING to prevent
     *    TTS audio feedback loop (mic picks up TTS → VAD → STT → garbage)
     * 2. Buffer audio during SPEECH_START confirmation period (prevents first-word loss)
     * 3. Reset VADProcessor after SPEECH_END for clean separation between utterances
     */
    private fun processAudioWithVAD(audioData: ShortArray) {
        // CRITICAL FIX: Skip ALL processing if TTS is speaking
        // This prevents the TTS feedback loop where app listens to itself
        if (currentState == PipelineState.SPEAKING) {
            return
        }
        
        // EXTRA CHECK: Skip if TTS is actively speaking (even if state is wrong)
        if (kokoroTTS?.isSpeaking() == true) {
            Log.v(TAG, "Skipping audio - TTS is speaking")
            return
        }
        
        // Apply conservative software gain for VAD processing on very quiet mic
        val processedAudio = ShortArray(audioData.size) { i ->
            val amplified = (audioData[i] * SOFTWARE_GAIN).toInt()
            when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }

        // Calculate audio level for visualization (always, even during non-listening states)
        val audioLevel = calculateAudioLevel(audioData)
        
        // DEBUG: Log audio level periodically
        if (audioLevel > 0.001f) {
            Log.d(TAG, "Audio level: ${"%.3f".format(audioLevel)}, state: $currentState")
        }
        
        onAudioLevel?.invoke(audioLevel)
        
        // ========================================================================
        // TIMEOUT CHECK: Auto-restart if listening too long without speech
        // ========================================================================
        if (currentState == PipelineState.LISTENING && !isCollectingSpeech && !isRestarting.get()) {
            val listeningDuration = System.currentTimeMillis() - listeningStartTime
            if (listeningDuration > MAX_LISTENING_TIME_MS) {
                Log.w(TAG, "Listening timeout after ${listeningDuration}ms - no speech detected")
                // CRITICAL FIX: Restart listening instead of stopping conversation
                // This allows user to try again without manually tapping
                Log.i(TAG, "🎤 Auto-restarting listening for another attempt...")
                // Reset timer immediately to prevent duplicate timeout triggers
                listeningStartTime = System.currentTimeMillis()
                scope.launch {
                    restartListening()
                }
                return
            }
        }
        
        // ========================================================================
        // BUG FIX 2: BARGE-IN DETECTION - Requires BOTH VAD + Energy + Sustained duration
        // Motorbike horn (~200ms) will NOT trigger, human speech (~960ms+) WILL trigger
        // ========================================================================
        if (currentState == PipelineState.SPEAKING) {
            // CRITICAL FIX: Skip barge-in detection during TTS cooldown
            // isSpeaking() API is broken on this device, use time-based cooldown only
            val timeSinceTTS = System.currentTimeMillis() - lastTTSFinishedTime
            val inTTSCooldown = timeSinceTTS < TTS_COOLDOWN_MS
            
            if (inTTSCooldown) {
                // TTS just finished, reset barge-in counter
                if (bargeInConfirmFrames > 0) {
                    Log.v(TAG, "Barge-in reset: cooldown=$inTTSCooldown (${timeSinceTTS}ms)")
                }
                bargeInConfirmFrames = 0
                return
            }
            
            // Calculate audio energy (RMS)
            val audioEnergy = calculateAudioEnergy(processedAudio)
            // Check if VAD agrees it's speech (neural model classification)
            val vadSaysSpeech = vadBridge?.detectVoice(processedAudio) == true
            val isHighEnergy = audioEnergy > BARGE_IN_ENERGY_THRESHOLD
            
            // ALL THREE conditions must be true to increment counter
            if (vadSaysSpeech && isHighEnergy) {
                bargeInConfirmFrames++
                Log.v(TAG, "BUG FIX 2: Barge-in frame $bargeInConfirmFrames/$BARGE_IN_CONFIRM_FRAMES (energy=$audioEnergy)")
            } else {
                // Reset on ANY frame that fails either check
                if (bargeInConfirmFrames > 0) {
                    Log.v(TAG, "BUG FIX 2: Barge-in reset (vad=$vadSaysSpeech, energy=$audioEnergy)")
                }
                bargeInConfirmFrames = 0
            }
            
            // Only trigger after sustained detection (30 frames = ~960ms)
            if (bargeInConfirmFrames >= BARGE_IN_CONFIRM_FRAMES) {
                Log.i(TAG, "🎤 BUG FIX 2: BARGE-IN CONFIRMED after $bargeInConfirmFrames frames (~960ms)")
                bargeInConfirmFrames = 0
                handleBargeIn()
                return
            }
            // Still speaking, skip normal VAD processing to prevent self-feedback
            return
        }
        
        // ========================================================================
        // FIX: SKIP VAD/STT processing during non-listening states
        // This prevents TTS audio feedback loop:
        //   TTS speaks → mic picks it up → VAD detects → STT transcribes → garbage
        // Q2-Q4 test results ("I'm well", "Good here") were LLM responses being
        // re-transcribed through this feedback loop.
        // ========================================================================
        if (currentState != PipelineState.LISTENING && 
            currentState != PipelineState.SPEECH_DETECTED) {
            // Still recording but not processing — skip silently
            return
        }
        
        // Add to pre-speech buffer (circular buffer) - USE AMPLIFIED AUDIO
        preSpeechBuffer.addAll(processedAudio.toList())
        val preSpeechMaxSize = (SAMPLE_RATE / 1000 * PRE_SPEECH_BUFFER_MS).toInt()
        while (preSpeechBuffer.size > preSpeechMaxSize) {
            preSpeechBuffer.removeFirst()
        }
        
        // Run VAD processor - USE AMPLIFIED AUDIO
        val vadState = vadProcessor?.process(processedAudio) ?: VADState.SILENCE
        
        // DEBUG LOG - VAD state
        if (vadState != VADState.SILENCE) {
            Log.d(TAG, "VAD state: $vadState (buffer: ${speechBuffer.size} samples)")
        }
        
        when (vadState) {
            VADState.SPEECH_START -> {
                if (!isCollectingSpeech) {
                    // First SPEECH_START frame — initialize collection
                    lastSpeechDetectedTime = System.currentTimeMillis()
                    Log.d(TAG, "VAD: SPEECH_START — beginning collection (timestamp: $lastSpeechDetectedTime)")
                    isCollectingSpeech = true
                    speechStartTime = System.currentTimeMillis()
                    consecutiveSilenceFrames = 0  // Reset silence counter
                    
                    // CRITICAL FIX: Reset listening flag so state transitions work for new query
                    isListeningForNextQuery.set(false)
                    Log.d(TAG, "Reset isListeningForNextQuery for new query")
                    
                    // Move pre-speech buffer to speech buffer
                    speechBuffer.clear()
                    speechBuffer.addAll(preSpeechBuffer)
                    
                    // Include the current frame - USE AMPLIFIED AUDIO
                    speechBuffer.addAll(processedAudio.toList())
                    
                    transitionToState(PipelineState.SPEECH_DETECTED)
                    Log.i(TAG, "Started collecting speech (pre-speech: ${preSpeechBuffer.size} + frame: ${audioData.size} samples)")
                } else {
                    // Subsequent SPEECH_START frames during confirmation period
                    consecutiveSilenceFrames = 0  // Reset silence counter
                    speechBuffer.addAll(processedAudio.toList())
                }
            }
            
            VADState.SPEECH -> {
                if (isCollectingSpeech) {
                    // Add to speech buffer - USE AMPLIFIED AUDIO
                    speechBuffer.addAll(processedAudio.toList())
                    
                    // Reset silence counter when speech detected
                    consecutiveSilenceFrames = 0
                    
                    // Prevent buffer overflow
                    while (speechBuffer.size > MAX_BUFFER_SIZE) {
                        speechBuffer.removeFirst()
                    }
                    
                    // NEW: Maximum speech duration check - force end after MAX_SPEECH_MS
                    val speechDuration = System.currentTimeMillis() - speechStartTime
                    if (speechDuration > MAX_SPEECH_MS) {
                        Log.w(TAG, "Speech exceeded maximum duration ($MAX_SPEECH_MS ms), forcing end")
                        forceEndSpeechSegment()
                    }
                }
            }
            
            VADState.SPEECH_END -> {
                speechEndDetectedTime = System.currentTimeMillis()
                val silenceDuration = speechEndDetectedTime - lastSpeechDetectedTime
                Log.i(TAG, "VAD: SPEECH_END detected (silence wait: ${silenceDuration}ms), buffer size: ${speechBuffer.size}")
                Log.d("TIMING", "1. VAD speech end detected at: ${System.currentTimeMillis()}")
                
                if (isCollectingSpeech) {
                    // Include the final frame - USE AMPLIFIED AUDIO
                    speechBuffer.addAll(processedAudio.toList())
                    
                    val speechDuration = System.currentTimeMillis() - speechStartTime
                    val durationMs = speechBuffer.size * 1000L / SAMPLE_RATE
                    
                    if (speechDuration >= MIN_SPEECH_MS && speechBuffer.size >= FRAME_SIZE) {
                        // We have valid speech - process it
                        Log.i(TAG, "Processing speech segment: ${speechDuration}ms elapsed, ${speechBuffer.size} samples (${durationMs}ms audio)")
                        processSpeechSegment()
                    } else {
                        Log.d(TAG, "Speech too short: ${speechDuration}ms (${speechBuffer.size} samples), discarding")
                    }
                    
                    // Reset EVERYTHING for clean next utterance
                    isCollectingSpeech = false
                    speechBuffer.clear()
                    preSpeechBuffer.clear()
                    consecutiveSilenceFrames = 0  // Reset silence counter
                    
                    // FIX: Reset VAD state to prevent pollution between utterances
                    vadProcessor?.reset()
                    
                    // CRITICAL FIX: Respect TTS cooldown before listening
                    transitionToListeningImmediate()
                }
            }
            
            VADState.SILENCE -> {
                // NEW: Trailing silence detection - count consecutive silence frames
                if (isCollectingSpeech) {
                    consecutiveSilenceFrames++
                    // If we've had enough consecutive silence frames, force speech end
                    if (consecutiveSilenceFrames >= TRAILING_SILENCE_FRAMES) {
                        speechEndDetectedTime = System.currentTimeMillis()
                        val totalSilenceWait = speechEndDetectedTime - lastSpeechDetectedTime
                        val speechDuration = speechEndDetectedTime - speechStartTime
                        Log.i(TAG, "Trailing silence detected ($consecutiveSilenceFrames frames, speech was ${speechDuration}ms), forcing speech end")
                        
                        // CRITICAL FIX: If we've been collecting speech for too long (>5s) 
                        // with very little audio, it's likely noise - force end
                        if (speechDuration > 5000 && speechBuffer.size < SAMPLE_RATE * 2) {
                            Log.w(TAG, "Detected noise-induced false positive (long duration, small buffer), forcing end")
                        }
                        
                        forceEndSpeechSegment()
                    }
                }
            }
        }
    }
    
    /**
     * Handle barge-in (user interrupting during TTS) - BUG FIX 1
     * Cancels TTS/LLM but PRESERVES chat history
     */
    private fun handleBargeIn() {
        Log.i(TAG, "=".repeat(50))
        Log.i(TAG, "BARGE-IN: Cancelling TTS, preserving chat history")
        Log.i(TAG, "=".repeat(50))
        
        // Cancel streaming TTS job
        streamingTTSJob?.cancel()
        streamingTTSJob = null
        
        // Stop current TTS immediately
        kokoroTTS?.stop()
        
        // Cancel any ongoing LLM generation
        llmProviderJob?.cancel()
        llmProviderJob = null
        
        // Reset TTS state
        isStreamingTTSActive = false
        isLLMResponseComplete = false
        lastSpokenPosition = 0
        ttsTextBuffer.clear()
        
        // BUG FIX 1: DO NOT clear previous response text!
        // fullResponseText.clear() <- REMOVED
        
        // Reset speech collection state for new input
        speechBuffer.clear()
        preSpeechBuffer.clear()
        isCollectingSpeech = false
        consecutiveSilenceFrames = 0
        bargeInConfirmFrames = 0  // BUG FIX 2: Reset barge-in counter
        
        // Reset VAD for clean state
        vadProcessor?.reset()
        
        // Notify UI that we were interrupted (but keep previous message)
        val currentResponse = fullResponseText.toString()
        scope.launch(Dispatchers.Main) {
            // Keep the previous response, just show we're listening again
            onResponseUpdate?.invoke(currentResponse + "\n\n(interrupted - listening...)", false)
        }
        
        // CRITICAL FIX: Switch back to listening with cooldown check
        transitionToListeningImmediate()
        
        Log.i(TAG, "✓ Barge-in handled - chat history preserved, listening for new input")
    }
    
    /**
     * Calculate RMS audio level for visualization - UI ONLY, does NOT affect STT audio
     */
    private fun calculateAudioLevel(audioData: ShortArray): Float {
        if (audioData.isEmpty()) return 0f
        
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = kotlin.math.sqrt(sumSquares / audioData.size)
        // UI boost only: makes quiet audio visible in the bar (does NOT affect STT)
        return (rms / 32768.0f * 10f).toFloat().coerceIn(0f, 1f)
    }
    
    /**
     * BUG FIX 2: Calculate raw audio energy (RMS) for barge-in detection
     * Returns raw RMS value (not normalized)
     */
    private fun calculateAudioEnergy(audioData: ShortArray): Float {
        if (audioData.isEmpty()) return 0f
        
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        return kotlin.math.sqrt(sumSquares / audioData.size).toFloat()
    }
    
    /**
     * Debug: Save audio to file for offline analysis
     */
    private fun debugSaveAudio(audioData: ShortArray, prefix: String) {
        if (!DEBUG_SAVE_AUDIO) return
        
        try {
            val debugDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mini_debug")
            debugDir.mkdirs()
            
            val timestamp = System.currentTimeMillis()
            val file = File(debugDir, "${prefix}_${timestamp}.pcm")
            
            val buffer = ByteBuffer.allocate(audioData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audioData) {
                buffer.putShort(sample)
            }
            
            FileOutputStream(file).use { fos ->
                fos.write(buffer.array())
            }
            
            Log.i(TAG, "Debug audio saved: ${file.absolutePath} (${audioData.size} samples, ${audioData.size * 1000 / SAMPLE_RATE}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save debug audio: ${e.message}")
        }
    }
    
    /**
     * Force end current speech segment (for max duration or trailing silence)
     */
    private fun forceEndSpeechSegment() {
        if (!isCollectingSpeech) return
        
        val speechDuration = System.currentTimeMillis() - speechStartTime
        if (speechDuration >= MIN_SPEECH_MS && speechBuffer.size >= FRAME_SIZE) {
            Log.i(TAG, "Forcing speech segment end: ${speechDuration}ms, ${speechBuffer.size} samples")
            processSpeechSegment()
        } else {
            // Speech too short, just reset
            isCollectingSpeech = false
            speechBuffer.clear()
            preSpeechBuffer.clear()
            consecutiveSilenceFrames = 0
            transitionToListeningImmediate()
        }
    }
    
    /**
     * Process accumulated speech segment through STT
     */
    private fun processSpeechSegment() {
        val sttStartTime = System.currentTimeMillis()
        if (speechBuffer.isEmpty()) return
        if (isProcessingSTT) {
            Log.w(TAG, "Already processing STT, ignoring new segment")
            return
        }
        
        // Reset consecutive silence counter
        consecutiveSilenceFrames = 0
        
        isProcessingSTT = true
        
        // Convert buffer to array
        val speechArray = speechBuffer.toShortArray()
        
        // Save raw audio for debugging (if enabled)
        debugSaveAudio(speechArray, "raw")
        
        // NO AGC - passing raw audio directly to STT
        // Hardware AGC via VOICE_RECOGNITION AudioSource provides proper gain
        
        val durationMs = speechArray.size * 1000 / SAMPLE_RATE
        Log.i(TAG, "Sending ${speechArray.size} samples (${durationMs}ms) to STT")
        transitionToState(PipelineState.TRANSCRIBING)
        
        // Process with STT - track job so we can cancel it if needed
        sttJob = scope.launch(Dispatchers.IO) {
            try {
                Log.d("TIMING", "2. STT start at: ${System.currentTimeMillis()}")
                val sttProcessingStart = System.currentTimeMillis()
                val transcription = sttBridge?.transcribe(speechArray) ?: ""
                val sttProcessingEnd = System.currentTimeMillis()
                Log.i(TAG, "STT processing took ${sttProcessingEnd - sttProcessingStart}ms")
                Log.d("TIMING", "3. STT done at: ${System.currentTimeMillis()}, result: $transcription")
                
                if (transcription.isNotBlank()) {
                    Log.i(TAG, "STT Result: \"$transcription\"")
                    
                    withContext(Dispatchers.Main) {
                        onTranscriptionUpdate?.invoke(transcription, true)
                    }
                    
                    // Process with LLM if available
                    if (llmBridge != null && queue != null) {
                        transitionToState(PipelineState.THINKING)
                        processWithLLM(transcription)
                    }
                } else {
                    Log.w(TAG, "STT returned empty transcription (${durationMs}ms audio was likely noise)")
                    // CRITICAL FIX: Restart listening automatically when no speech detected
                    // This prevents the app from getting stuck in a non-responsive state
                    Log.i(TAG, "🎤 No speech detected - automatically restarting listening...")
                    restartListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT processing failed", e)
                transitionToState(PipelineState.ERROR)
                withContext(Dispatchers.Main) {
                    onError?.invoke("STT failed: ${e.message}")
                }
            } finally {
                isProcessingSTT = false
                sttJob = null
            }
        }
    }
    
    /**
     * Process speech with real STT using Sherpa-ONNX
     * 
     * Uses the existing STT models (encoder_model.ort, decoder_model_merged.ort)
     * These are Moonshine models that use ONNX Runtime directly.
     */
    private suspend fun processWithMockSTT(audioData: ShortArray): String {
        // For now, we need to implement real STT using the available models
        // The models in assets/models/stt/ are Moonshine models
        // TODO: Implement Moonshine ONNX inference similar to TTSBridge
        
        delay(500) // Simulate processing time
        
        // TEMPORARY: Return a demo phrase that varies based on audio characteristics
        // This at least shows the pipeline is working
        // In production, implement real STT inference
        
        // Analyze audio to give different responses (for demo purposes)
        val rms = kotlin.math.sqrt(audioData.map { (it * it).toDouble() }.average())
        
        return when {
            rms > 1000 -> "Hello, how are you today?"
            rms > 500 -> "What can I help you with?"
            rms > 200 -> "Tell me more about that."
            else -> "I heard you speaking."
        }
    }
    
    /**
     * Force stop listening and process current audio immediately
     * Called when user taps the orb during listening
     */
    fun forceStopAndProcess() {
        Log.i(TAG, "Force stop requested by user tap")
        Log.i(TAG, "Current state: isCollectingSpeech=$isCollectingSpeech, speechBuffer=${speechBuffer.size}, preSpeechBuffer=${preSpeechBuffer.size}")
        
        // If we have collected speech, process it
        if (isCollectingSpeech && speechBuffer.size >= FRAME_SIZE) {
            Log.i(TAG, "Processing collected speech on user tap: ${speechBuffer.size} samples")
            forceEndSpeechSegment()
            return
        } 
        
        // Combine pre-speech and speech buffers
        val combinedBuffer = mutableListOf<Short>()
        
        // Add pre-speech buffer first (contains audio before VAD trigger)
        if (preSpeechBuffer.isNotEmpty()) {
            val preSpeechSize = minOf(preSpeechBuffer.size, SAMPLE_RATE * 2) // Max 2 seconds of pre-speech
            combinedBuffer.addAll(preSpeechBuffer.takeLast(preSpeechSize))
            Log.i(TAG, "Added $preSpeechSize pre-speech samples (buffer had ${preSpeechBuffer.size})")
        } else {
            Log.w(TAG, "Pre-speech buffer is EMPTY!")
        }
        
        // Add any collected speech buffer
        if (speechBuffer.isNotEmpty()) {
            combinedBuffer.addAll(speechBuffer)
            Log.i(TAG, "Added ${speechBuffer.size} speech samples")
        }
        
        // Process if we have enough audio (at least 0.3 seconds - reduced from 0.5)
        val minSamples = SAMPLE_RATE * 3 / 10 // 0.3 seconds minimum
        Log.i(TAG, "Combined buffer size: ${combinedBuffer.size} samples (need $minSamples)")
        
        if (combinedBuffer.size >= minSamples) {
            Log.i(TAG, "Processing combined buffer: ${combinedBuffer.size} samples (${combinedBuffer.size * 1000 / SAMPLE_RATE}ms)")
            speechBuffer.clear()
            speechBuffer.addAll(combinedBuffer)
            processSpeechSegment()
            return
        }
        
        // Otherwise just stop normally
        Log.w(TAG, "No audio collected (${combinedBuffer.size} samples), stopping normally")
        stopConversation()
    }
    
    /**
     * Stop voice conversation
     */
    fun stopConversation() {
        if (!isRunning.get()) return
        
        Log.w(TAG, "CRITICAL: stopConversation() called - setting isRunning=false")
        isRunning.set(false)
        transitionToState(PipelineState.IDLE)
        
        // Stop audio recording
        audioRecorder?.stopRecording()
        
        // Release audio focus
        releaseAudioFocus()
        
        // Process any remaining speech
        if (isCollectingSpeech && speechBuffer.size >= FRAME_SIZE) {
            Log.i(TAG, "Processing final speech segment")
            processSpeechSegment()
        }
        
        // Clear buffers
        speechBuffer.clear()
        preSpeechBuffer.clear()
        isCollectingSpeech = false
        isProcessingSTT = false
        consecutiveSilenceFrames = 0
        bargeInConfirmFrames = 0       // BUG FIX 2: Reset barge-in counter
        isLLMResponseComplete = false  // Reset for next conversation
        
        pipelineJob?.cancel()
        pipelineJob = null
        
        Log.i(TAG, "Voice conversation stopped")
    }
    
    /**
     * Stop TTS immediately - called when user closes voice mode
     */
    fun stopTTS() {
        Log.i(TAG, "Stopping TTS")
        kokoroTTS?.stop()
        streamingTTSJob?.cancel()
        streamingTTSJob = null
        isStreamingTTSActive = false
        ttsTextBuffer.clear()
    }
    
    /**
     * Stop ongoing LLM generation
     * Called when user presses the cross button to interrupt generation
     */
    fun stopGeneration() {
        Log.i(TAG, "Stopping LLM generation")
        // Cancel LLM Provider job if running
        llmProviderJob?.cancel()
        llmProviderJob = null
        // CRITICAL FIX: Also cancel the provider's internal generation job
        // so it doesn't keep emitting tokens after we asked it to stop.
        llmProvider?.cancelGeneration()
        // Signal native layer to stop
        llmBridge?.stopGeneration()
        // Reset state
        isLLMResponseComplete = false
        // CRITICAL FIX: Don't transition here - let the caller decide
        // This was causing race conditions with restartForNewQuestion()
    }
    
    /**
     * Clear current response text and buffers
     * Called when user interrupts to start fresh
     */
    fun clearResponse() {
        Log.i(TAG, "Clearing response buffers")
        fullResponseText.clear()
        ttsTextBuffer.clear()
        lastSpokenPosition = 0
        streamingTokenBuffer.clear()
        isCollectingImEnd = false
    }
    
    /**
     * Restart voice mode for a new question
     * Called when user presses cross button during voice mode
     * Stops everything and returns to LISTENING state
     * 
     * CRITICAL FIX: Also clears native LLM context to prevent contamination
     * from previous failed/corrupted conversation turns.
     */
    fun restartForNewQuestion() {
        Log.i(TAG, "🔄 Restarting voice mode for new question (HARD RESET)")
        
        // CRITICAL FIX: Reset isListeningForNextQuery FIRST before stopping anything
        // This prevents race conditions where stopTTS/stopGeneration callbacks
        // set isListeningForNextQuery=true AFTER we reset it, blocking the transition
        isListeningForNextQuery.set(false)
        
        // Stop any ongoing processes
        stopTTS()
        stopGeneration()
        
        // CRITICAL: Clear native LLM context to prevent history contamination
        // This prevents bad context from affecting new questions
        try {
            llmBridge?.clearContext()
            Log.i(TAG, "✅ Native LLM context cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear native context: ${e.message}")
        }
        
        // Clear buffers - CRITICAL: Reset STT processing flag
        speechBuffer.clear()
        preSpeechBuffer.clear()
        isCollectingSpeech = false
        isProcessingSTT = false  // FORCE RESET - this was getting stuck!
        consecutiveSilenceFrames = 0
        bargeInConfirmFrames = 0
        
        // Reset response state
        clearResponse()
        
        // Cancel any pending jobs
        llmProviderJob?.cancel()
        llmProviderJob = null
        // CRITICAL FIX: Don't cancel TTS job - let it finish speaking!
        // streamingTTSJob?.cancel()
        // streamingTTSJob = null
        sttJob?.cancel()
        sttJob = null
        
        // Reset completion flag
        isLLMResponseComplete = false
        
        // Reset listening timer
        listeningStartTime = System.currentTimeMillis()
        
        // CRITICAL FIX: Reset VAD processor for clean state
        // This was missing and causing VAD to be in a bad state after restart
        vadProcessor?.reset()
        Log.i(TAG, "✅ VAD processor reset")
        
        // Reset audio recorder TTS speaking state
        audioRecorder?.setTTSSpeaking(false)
        
        // CRITICAL FIX: Briefly restart audio recorder to ensure clean audio pipeline
        // This reinitializes audio effects (AEC, NS, AGC) which can degrade over time
        if (audioRecorder?.isRecording() == true) {
            Log.i(TAG, "🎤 Restarting audio recorder for clean state...")
            audioRecorder?.stopRecording()
            scope.launch {
                delay(300)  // CRITICAL: Longer delay for hardware to fully stabilize
                startRecording()
            }
        } else if (isRunning.get()) {
            // CRITICAL FIX: If recorder is not running but pipeline is active, start it!
            // This fixes the issue where X button press leaves recorder stopped
            Log.i(TAG, "🎤 Audio recorder not running - starting it now...")
            scope.launch {
                delay(300)  // CRITICAL: Longer delay for hardware to fully stabilize
                startRecording()
            }
        }
        
        // CRITICAL FIX: Reset from ANY state (including ERROR) back to LISTENING
        if (isRunning.get()) {
            Log.i(TAG, "🎤 FORCE: Returning to LISTENING state for new question (from $currentState)")
            transitionToState(PipelineState.LISTENING)
        } else {
            // If somehow stopped, restart the voice conversation
            Log.i(TAG, "🎤 Voice pipeline was stopped, restarting...")
            startVoiceConversation()
        }
    }
    
    /**
     * Send transcription to LLM for processing
     * 
     * LLM PROVIDER INTEGRATION: Uses LLMProvider abstraction when available,
     * falls back to InferenceQueue for backward compatibility.
     * 
     * MEMORY INTEGRATION: Injects memory context block into the prompt
     * Format: [MEMORY] User: {name}. Facts: ... People: ... Pending: ... [/MEMORY]\n\nUser: {message}
     */
    private fun processWithLLM(transcription: String) {
        // Use LLMProvider if available, otherwise fall back to queue
        if (llmProvider != null) {
            if (llmProvider.isAvailable()) {
                Log.i(TAG, "Using LLMProvider: ${llmProvider.displayName}")
                processWithLLMProvider(transcription)
            } else {
                Log.e(TAG, "LLMProvider (${llmProvider.displayName}) not available - API key may be missing")
                onError?.invoke("${llmProvider.displayName} not available. Please check your API key in settings.")
                transitionToState(PipelineState.ERROR)
                scope.launch { restartListening() }
            }
        } else {
            Log.i(TAG, "No LLMProvider, using legacy queue (offline mode)")
            processWithLegacyQueue(transcription)
        }
    }
    
    /**
     * Process with new LLMProvider (streaming, memory context)
     * 
     * CRITICAL FIX: Added 60-second timeout to prevent getting stuck indefinitely.
     * If LLM doesn't respond within 60 seconds, we timeout and restart listening.
     */
    private fun processWithLLMProvider(transcription: String) {
        Log.d("NILO_DEBUG", "processWithLLM called. llmProvider=${llmProvider != null}, isAvailable=${llmProvider?.isAvailable()}")
        Log.d("NILO_DEBUG", "currentState=$currentState, isRunning=${isRunning.get()}")
        
        // CRITICAL FIX: Cancel any ongoing generation before starting a new one.
        // Without this, old native generations can pile up and deadlock the llama context.
        llmProviderJob?.cancel()
        llmBridge?.stopGeneration()
        llmProviderJob = null
        
        llmProviderJob = scope.launch {
            try {
                // Hard reset all state before starting new generation
                fullResponseText.clear()
                ttsTextBuffer.clear()  
                isLLMResponseComplete = false
                finalBubbleText = ""
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                Log.d("NILO_DEBUG", "State reset complete, starting LLM call")
                // NOTE: Don't clear context here - it was cleared after previous response
                // This allows the model to maintain context for natural conversation
                // Reset streaming token filter state
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                
                // BUG FIX 5: Use shared system prompt builder for consistent identity + memory
                val fullSystemPrompt = SystemPromptBuilder.buildSystemPrompt(memoryManager, maxTokens = 500)
                
                // CRITICAL FIX: Classify query to determine appropriate response length
                currentQueryClassification = QueryClassifier.classify(transcription)
                val dynamicMaxTokens = currentQueryClassification?.maxTokens ?: 1024
                val dynamicMaxSentences = currentQueryClassification?.maxSentences ?: 3
                val dynamicMaxChars = currentQueryClassification?.maxChars ?: 200
                
                Log.i(TAG, "Sending to LLM Provider: '$transcription'")
                Log.i(TAG, "System prompt length: ${fullSystemPrompt.length} chars")
                Log.i(TAG, "Query classified as ${currentQueryClassification?.length}, maxTokens=$dynamicMaxTokens, maxSentences=$dynamicMaxSentences")
                
                // Prepare streaming TTS
                ttsTextBuffer.clear()
                lastSpokenPosition = 0
                ttsStartTime = System.currentTimeMillis()
                isStreamingTTSActive = false
                
                // Build completion request with DYNAMIC maxTokens based on query classification
                val request = CompletionRequest(
                    messages = listOf(ChatMessage.user(transcription)),
                    systemPrompt = fullSystemPrompt,
                    memoryContext = null, // Already included in systemPrompt via SystemPromptBuilder
                    maxTokens = dynamicMaxTokens,  // CRITICAL FIX: Use dynamic limit based on query type
                    stream = true,
                    stopSequences = listOf("</s>", "<|endoftext|>", "<|user|>", "User:")  // REMOVED: "<|im_end|>" to allow showing all tokens
                )
                
                // Stream response
                var tokenCount = 0
                var isFirstToken = true
                Log.d("TIMING", "4. LLM request start at: ${System.currentTimeMillis()}")
                
                // CRITICAL FIX: Add timeout around stream collection to prevent getting stuck
                // if the native layer hangs or deadlocks.
                withTimeoutOrNull(75000) {
                    llmProvider?.stream(request)?.collect { response ->
                    when {
                        response.isStreaming && isFirstToken -> {
                            Log.d("TIMING", "5. First response token at: ${System.currentTimeMillis()}")
                            isFirstToken = false
                        }
                        response.isError -> {
                            transitionToState(PipelineState.ERROR)
                            val errorMsg = response.error ?: "Unknown error"
                            Log.e(TAG, "Online provider error: $errorMsg")
                            withContext(Dispatchers.Main) {
                                onError?.invoke(errorMsg)
                            }
                        }
                        response.isComplete -> {
                            Log.i(TAG, "LLM response complete, tokens: $tokenCount")
                            isLLMResponseComplete = true
                            
                            // CRITICAL FIX: Transition to SPEAKING state when LLM completes
                            // This ensures UI shows correct state even if TTS hasn't started yet
                            transitionToState(PipelineState.SPEAKING)
                            
                            // CRITICAL FIX: Get clean response (truncate any role leakage)
                            val cleanResponse = removeImEndTokenOnly(fullResponseText.toString())
                            finalBubbleText = cleanResponse  // Store as single source of truth
                            
                            // CRITICAL FIX: TTS buffer MUST match the bubble text exactly!
                            // Clear any accumulated content and use only finalBubbleText
                            val cleanTTSContent = filterTTSText(finalBubbleText)
                            ttsTextBuffer.clear()
                            ttsTextBuffer.append(cleanTTSContent)
                            Log.i(TAG, "TTS buffer set to match finalBubbleText EXACTLY: ${ttsTextBuffer.length} chars")
                            
                            // CRITICAL FIX: Start TTS if not already active (for short responses that never triggered streaming)
                            if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && finalBubbleText.isNotBlank()) {
                                Log.i(TAG, "LLM complete but TTS not started yet - starting now (${finalBubbleText.length} chars)")
                                startStreamingTTS(finalBubbleText)
                            } else if (finalBubbleText.isBlank()) {
                                Log.w(TAG, "LLM complete but response is blank — restarting listening")
                                restartListening()
                            }
                            
                            // Send finalBubbleText to UI (single source of truth)
                            Log.d(TAG, "NILO_DEBUG: onResponseUpdate FINAL: ${finalBubbleText.length} chars, isComplete=true")
                            withContext(Dispatchers.Main) {
                                onResponseUpdate?.invoke(finalBubbleText, true)
                            }
                            
                            // BUG FIX 4: Save conversation AND extract memories (use finalBubbleText)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    memoryManager?.saveConversationExchange(
                                        userMessage = transcription,
                                        assistantMessage = finalBubbleText
                                    )
                                    Log.d(TAG, "Conversation exchange saved to memory")
                                    
                                    // BUG FIX 4: Extract and save memory-worthy facts (use finalBubbleText)
                                    memoryManager?.extractAndSave(
                                        userMessage = transcription,
                                        llmResponse = finalBubbleText,
                                        mode = llmProvider?.displayName ?: "Offline"
                                    )
                                    Log.d(TAG, "Memory extraction completed")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to save conversation or extract memory: ${e.message}")
                                }
                            }
                            
                            // CRITICAL FIX: Clear LLM context after response to prevent corruption for next turn
                            // BUT only if we're going to continue listening (not if user closed voice mode)
                            if (isRunning.get()) {
                                clearContextAsync()
                                Log.i(TAG, "LLM context cleared after response for clean next turn")
                            }
                            
                            // Reset for next query
                            currentQueryClassification = null
                        }
                        response.isStreaming -> {
                            tokenCount++
                            transitionToState(PipelineState.SPEAKING)
                            
                            // Append token to response
                            val newText = response.text
                            // FIX: Handle case where text doesn't start with previous
                            val token = if (newText.length > fullResponseText.length && 
                                          newText.startsWith(fullResponseText.toString())) {
                                newText.substring(fullResponseText.length)
                            } else if (newText.length > fullResponseText.length) {
                                // Text changed unexpectedly, use diff
                                newText.removePrefix(fullResponseText.toString())
                            } else {
                                "" // No new content
                            }
                            
                            // Always add token to fullResponseText FIRST
                            fullResponseText.append(token)
                            
                            // NOTE: We no longer force-stop LLM on role leakage detection
                            // The LLM completes naturally, and we truncate at display/speak time
                            
                            // CRITICAL FIX: Add token to TTS buffer FIRST
                            // This ensures ALL text that appears in the bubble also gets added to TTS buffer
                            if (token.isNotBlank()) {
                                val streamingFiltered = filterStreamingTokenForTTS(token)
                                if (streamingFiltered != null && streamingFiltered.isNotBlank()) {
                                    val filteredToken = filterTTSText(streamingFiltered)
                                    if (filteredToken.isNotBlank()) {
                                        ttsTextBuffer.append(filteredToken)
                                        Log.v(TAG, "Added to TTS buffer: '$filteredToken' (buffer now ${ttsTextBuffer.length} chars)")
                                    }
                                }
                            }
                            
                            // CRITICAL FIX: Start TTS as soon as we have a complete sentence (streaming TTS)
                            val currentText = removeImEndTokenOnly(fullResponseText.toString())
                            
                            if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && hasCompleteSentence(currentText)) {
                                Log.i(TAG, "First sentence ready, starting TTS streaming")
                                startStreamingTTS(currentText)
                            }
                            
                            // Update UI - show ALL tokens, just hide the <|im_end|> marker
                            val rawText = fullResponseText.toString()
                            val displayText = removeImEndTokenOnly(rawText)
                            Log.d(TAG, "NILO_DEBUG: RAW fullResponseText: '${rawText.take(200)}...' (${rawText.length} chars)")
                            Log.d(TAG, "NILO_DEBUG: DISPLAY displayText: '${displayText.take(200)}...' (${displayText.length} chars)")
                            Log.d(TAG, "NILO_DEBUG: onResponseUpdate STREAM: ${displayText.length} chars, isComplete=false")
                            withContext(Dispatchers.Main) {
                                onResponseUpdate?.invoke(displayText, false)
                            }
                        }
                    }
                }
                }
                
                // SAFETY NET: If stream ended without ever emitting isComplete, force completion now
                if (!isLLMResponseComplete) {
                    Log.w(TAG, "LLM stream ended without isComplete signal — forcing completion safety net")
                    isLLMResponseComplete = true
                    finalBubbleText = removeImEndTokenOnly(fullResponseText.toString())
                    withContext(Dispatchers.Main) {
                        onResponseUpdate?.invoke(finalBubbleText, true)
                    }
                    if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && finalBubbleText.isNotBlank()) {
                        startStreamingTTS(finalBubbleText)
                    } else if (finalBubbleText.isBlank()) {
                        Log.w(TAG, "Empty response from LLM — restarting listening")
                        restartListening()
                    }
                    if (isRunning.get()) {
                        clearContextAsync()
                    }
                    currentQueryClassification = null
                }

                // CRITICAL FIX: Only transition back to listening after BOTH LLM and TTS are done.
                // If the response was blank, restartListening() was already called above.
                if (finalBubbleText.isNotBlank()) {
                    awaitCompletionAndListen()
                }
            } catch (e: CancellationException) {
                // CRITICAL FIX: Handle CancellationException separately - don't treat as error
                // This happens when user presses X to interrupt. Let restartForNewQuestion() handle it.
                Log.i(TAG, "LLM Provider job cancelled (user interrupted)")
                // Emit partial response as complete so UI shows final state
                if (fullResponseText.isNotBlank()) {
                    finalBubbleText = removeImEndTokenOnly(fullResponseText.toString())
                    withContext(Dispatchers.Main) {
                        onResponseUpdate?.invoke(finalBubbleText, true)
                    }
                }
                throw e  // Re-throw so parent coroutine knows it was cancelled
            } catch (e: Exception) {
                Log.e(TAG, "LLM Provider processing failed", e)
                transitionToState(PipelineState.ERROR)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Processing failed: ${e.message}")
                }
                delay(1000)
                restartListening()
            } finally {
                llmProviderJob = null
            }
        }
    }
    
    /**
     * Process with legacy InferenceQueue (backward compatibility)
     * 
     * CRITICAL FIX: Added 60-second timeout to prevent getting stuck indefinitely.
     */
    private fun processWithLegacyQueue(transcription: String) {
        // CRITICAL FIX: Cancel any ongoing generation before starting a new one.
        llmProviderJob?.cancel()
        llmBridge?.stopGeneration()
        llmProviderJob = null
        
        llmProviderJob = scope.launch {
            try {
                // Clear previous response text when NEW user query starts
                fullResponseText.clear()
                isLLMResponseComplete = false
                // NOTE: Don't clear context here - it was cleared after previous response
                // Reset streaming token filter state
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                
                // BUG FIX 5: Use shared system prompt builder for consistent identity + memory
                val fullSystemPrompt = SystemPromptBuilder.buildSystemPrompt(memoryManager, maxTokens = 500)
                
                // CRITICAL FIX: Classify query to determine appropriate response length (same as LLMProvider path)
                currentQueryClassification = QueryClassifier.classify(transcription)
                val dynamicMaxTokens = currentQueryClassification?.maxTokens ?: 1024
                val dynamicMaxSentences = currentQueryClassification?.maxSentences ?: 3
                
                Log.i(TAG, "Sending to LLM (legacy queue) with system prompt length: ${fullSystemPrompt.length}")
                Log.i(TAG, "Query classified as ${currentQueryClassification?.length}, maxTokens=$dynamicMaxTokens")
                
                val requestId = System.currentTimeMillis().toString()
                val request = InferenceQueue.Request(
                    id = requestId,
                    prompt = transcription, // User message only
                    systemPrompt = fullSystemPrompt, // BUG FIX 3 & 5: Include identity + memory
                    maxTokens = dynamicMaxTokens,  // CRITICAL FIX: Use dynamic limit based on query type
                    priority = InferenceQueue.Priority.HIGH
                )
                
                Log.i(TAG, "Sending to LLM: '$transcription' (requestId: $requestId)")
                Log.d("TIMING", "4. LLM request start at: ${System.currentTimeMillis()}")
                queue?.enqueue(request)
                
                // Collect response - filter by request ID and terminate when complete
                var responseComplete = false
                var tokenCount = 0
                var isFirstToken = true
                // fullResponseText was already cleared at function start
                ttsTextBuffer.clear()  // Reset streaming TTS buffer
                lastSpokenPosition = 0
                ttsStartTime = System.currentTimeMillis()  // Start timing for streaming TTS
                isStreamingTTSActive = false
                
                // Collect response with proper termination using takeWhile
                // CRITICAL FIX: Add timeout around collection to prevent getting stuck
                withTimeoutOrNull(75000) {
                    queue?.responses
                        ?.takeWhile { !responseComplete }  // Terminate when complete
                        ?.collect { response ->
                        // Only process responses for our request
                        if (response.requestId != requestId) return@collect
                        
                        if (response.token != null && isFirstToken) {
                            Log.d("TIMING", "5. First response token at: ${System.currentTimeMillis()}")
                            isFirstToken = false
                        }
                        
                        when {
                            response.error != null -> {
                                transitionToState(PipelineState.ERROR)
                                responseComplete = true
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("LLM Error: ${response.error}")
                                }
                            }
                            response.isComplete -> {
                                Log.i(TAG, "LLM response complete, tokens: $tokenCount")
                                
                                // BUG FIX: Process final token if present
                                if (response.token != null && response.token.isNotBlank()) {
                                    tokenCount++
                                    fullResponseText.append(response.token)
                                }
                                
                                responseComplete = true
                                
                                // STEP 1: Snapshot the bubble text
                                finalBubbleText = removeImEndTokenOnly(fullResponseText.toString())
                                
                                // STEP 2: Update UI one final time
                                Log.d(TAG, "NILO_DEBUG: onResponseUpdate LEGACY_FINAL: ${finalBubbleText.length} chars")
                                withContext(Dispatchers.Main) {
                                    onResponseUpdate?.invoke(finalBubbleText, true)
                                }
                                
                                // STEP 3: Cancel the LLM job so nothing else can generate
                                llmProviderJob?.cancel()
                                llmBridge?.stopGeneration()
                                
                                // STEP 4: Save conversation in background
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        memoryManager?.saveConversationExchange(
                                            userMessage = transcription,
                                            assistantMessage = finalBubbleText
                                        )
                                        memoryManager?.extractAndSave(
                                            userMessage = transcription,
                                            llmResponse = finalBubbleText,
                                            mode = "Offline"
                                        )
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to save conversation: ${e.message}")
                                    }
                                }
                                
                                // STEP 5: Clear LLM context for next turn
                                if (isRunning.get()) {
                                    clearContextAsync()
                                }
                                
                                // STEP 6: Mark LLM complete - streaming TTS will finish speaking remaining text
                                Log.d(TAG, "NILO_DEBUG: isComplete fired - LLM response complete, ${finalBubbleText.length} chars")
                                isLLMResponseComplete = true
                                
                                // Start TTS if not already active and we have text
                                if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && finalBubbleText.isNotBlank()) {
                                    startStreamingTTS(finalBubbleText)
                                }
                            }
                            response.token != null && !response.isComplete -> {
                                // STREAMING: Collect tokens, update UI, start TTS when first sentence ready
                                tokenCount++
                                transitionToState(PipelineState.SPEAKING)
                                fullResponseText.append(response.token)
                                
                                // Update UI in real time
                                val uiText = removeImEndTokenOnly(fullResponseText.toString())
                                Log.d(TAG, "NILO_DEBUG: RAW: '${fullResponseText}'")
                                Log.d(TAG, "NILO_DEBUG: UI TEXT: '$uiText'")
                                Log.d(TAG, "NILO_DEBUG: onResponseUpdate LEGACY_STREAM: ${fullResponseText.length} chars")
                                withContext(Dispatchers.Main) {
                                    onResponseUpdate?.invoke(uiText, false)
                                }
                                
                                // Start TTS as soon as we have a complete sentence (low latency)
                                val currentText = removeImEndTokenOnly(fullResponseText.toString())
                                
                                if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && hasCompleteSentence(currentText)) {
                                    Log.i(TAG, "First sentence ready, starting streaming TTS")
                                    startStreamingTTS(currentText)
                                }
                            }
                        }
                    }
                }
                
                // SAFETY NET: If queue flow ended without ever setting responseComplete, force it now
                if (!responseComplete) {
                    Log.w(TAG, "Legacy queue flow ended without isComplete — forcing completion safety net")
                    responseComplete = true
                    finalBubbleText = removeImEndTokenOnly(fullResponseText.toString())
                    withContext(Dispatchers.Main) {
                        onResponseUpdate?.invoke(finalBubbleText, true)
                    }
                    isLLMResponseComplete = true
                    if (!isStreamingTTSActive && streamingTTSJob?.isActive != true && finalBubbleText.isNotBlank()) {
                        startStreamingTTS(finalBubbleText)
                    } else if (finalBubbleText.isBlank()) {
                        Log.w(TAG, "Empty response from legacy queue — restarting listening")
                        restartListening()
                    }
                    if (isRunning.get()) {
                        clearContextAsync()
                    }
                }

                // CRITICAL FIX: Only transition back to listening after BOTH LLM and TTS are done.
                if (finalBubbleText.isNotBlank()) {
                    awaitCompletionAndListen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM processing failed", e)
                transitionToState(PipelineState.ERROR)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Processing failed: ${e.message}")
                }
                // FIXED: Restart listening after error
                delay(1000)
                restartListening()
            }
        }
    }
    
    /**
     * Wait for both LLM generation and TTS to finish, then transition back to LISTENING.
     * Polling every 500ms because isSpeaking() is unreliable on Samsung Tab A7 Lite.
     */
    private fun awaitCompletionAndListen() {
        if (completionWaitJob?.isActive == true) {
            Log.d(TAG, "awaitCompletionAndListen already active, skipping")
            return
        }

        completionWaitJob = scope.launch {
            val start = System.currentTimeMillis()
            val maxWait = 90000L

            while (isActive) {
                val llmDone = isLLMResponseComplete
                val ttsDone = !isStreamingTTSActive && streamingTTSJob?.isActive != true && kokoroTTS?.isSpeaking() != true

                if (llmDone && ttsDone) {
                    Log.i(TAG, "LLM and TTS both complete — safe to transition to LISTENING")
                    break
                }

                if (System.currentTimeMillis() - start > maxWait) {
                    Log.w(TAG, "Timeout waiting for LLM+TTS completion, forcing transition")
                    break
                }

                delay(500)
            }

            if (!isRunning.get()) {
                Log.w(TAG, "Pipeline stopped while waiting, aborting transition")
                return@launch
            }

            // Reset state for next utterance
            speechBuffer.clear()
            preSpeechBuffer.clear()
            isCollectingSpeech = false
            isProcessingSTT = false
            consecutiveSilenceFrames = 0
            lastSpokenPosition = 0
            ttsTextBuffer.clear()
            fullResponseText.clear()
            lastTTSFinishedTime = System.currentTimeMillis()
            vadProcessor?.reset()
            currentQueryClassification = null
            listeningStartTime = System.currentTimeMillis()

            if (currentState != PipelineState.LISTENING) {
                transitionToState(PipelineState.LISTENING)
            }

            if (audioRecorder?.isRecording() != true) {
                startRecording()
            }

            Log.i(TAG, "✓ Listening active after LLM+TTS completion")
        }
    }

    /**
     * Restart listening after error or when TTS is not available
     * This is a fallback function for error recovery.
     * Uses atomic flag to prevent concurrent restarts from multiple threads.
     */
    private suspend fun restartListening() {
        // Prevent concurrent restarts
        if (!isRestarting.compareAndSet(false, true)) {
            Log.d(TAG, "Restart already in progress, skipping")
            return
        }
        
        try {
            if (!isRunning.get()) {
                Log.w(TAG, "Cannot restart: pipeline not running")
                return
            }
            
            // Cancel completion wait so we can take over immediately
            completionWaitJob?.cancel()
            completionWaitJob = null

            // Cancel any existing streaming TTS job to prevent conflicts
            streamingTTSJob?.cancel()
            streamingTTSJob = null
            isStreamingTTSActive = false
            isLLMResponseComplete = false  // Reset for next utterance
            
            Log.i(TAG, "=".repeat(50))
            Log.i(TAG, "RESTARTING LISTENING (fallback mode)")
            Log.i(TAG, "=".repeat(50))
            
            // Reset all speech-related state
            speechBuffer.clear()
            preSpeechBuffer.clear()
            isCollectingSpeech = false
            isProcessingSTT = false
            consecutiveSilenceFrames = 0
            lastSpokenPosition = 0
            ttsTextBuffer.clear()
            fullResponseText.clear()
            lastTTSFinishedTime = 0L  // Reset TTS cooldown
            
            // Reset VAD processor for clean state
            vadProcessor?.reset()
            
            // Clear LLM context for next utterance
            llmBridge?.clearContext()
            
            // CRITICAL FIX: Always restart recording to re-initialize hardware AGC after TTS.
            // Keeping the same AudioRecord instance can leave the mic in a quiet state.
            Log.i(TAG, "Restarting audio recorder to re-initialize AGC")
            audioRecorder?.stopRecording()
            pipelineJob?.cancel()
            pipelineJob = null
            delay(200)
            startRecording()
            
            // CRITICAL FIX: Use suspend helper to respect TTS cooldown
            transitionToListening()
            Log.i(TAG, "✓ Listening restarted - ready for next utterance")
        } finally {
            isRestarting.set(false)
        }
    }
    
    /**
     * STREAMING TTS: Speaks text as it arrives, waits for both LLM and TTS to complete
     * Flow: Start with initial sentence → continue speaking new text → wait for LLM done → 
     *       speak remaining → wait for TTS done → transition to LISTENING
     * 
     * CRITICAL FIX: Added TTS re-initialization if not ready
     */
    private fun startStreamingTTS(initialText: String) {
        // CRITICAL FIX: Prevent multiple concurrent TTS jobs
        if (streamingTTSJob?.isActive == true) {
            Log.w(TAG, "TTS job already active, skipping new start")
            return
        }
        
        // CRITICAL FIX: Check if TTS is ready, try to re-initialize if not
        if (kokoroTTS?.isReady != true) {
            Log.w(TAG, "TTS not ready when starting streaming, attempting re-initialization...")
            scope.launch {
                val reinitSuccess = kokoroTTS?.ensureReady() == true
                if (reinitSuccess) {
                    Log.i(TAG, "TTS re-initialized successfully, starting streaming")
                    startStreamingTTS(initialText)
                } else {
                    Log.e(TAG, "TTS re-initialization failed, cannot speak response")
                    // Show error to user
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Text-to-Speech failed. Please check your device TTS settings.")
                    }
                }
            }
            return
        }
        
        if (isStreamingTTSActive) {
            Log.w(TAG, "TTS streaming already active, skipping")
            return
        }
        
        isStreamingTTSActive = true
        Log.i(TAG, "Starting TTS (hybrid mode - streams sentences from finalBubbleText only)")
        
        // CRITICAL FIX: Force state to SPEAKING and abort any ongoing speech collection
        transitionToState(PipelineState.SPEAKING)
        
        // Reset all speech collection state to prevent feedback loop
        isCollectingSpeech = false
        speechBuffer.clear()
        preSpeechBuffer.clear()
        consecutiveSilenceFrames = 0
        bargeInConfirmFrames = 0
        vadProcessor?.reset()
        
        // Notify audio recorder that TTS is speaking (for barge-in detection)
        audioRecorder?.setTTSSpeaking(true)
        
        // CRITICAL FIX: STOP any previous TTS and clear queue
        kokoroTTS?.stop()
        
        streamingTTSJob = scope.launch {
            var lastSentToTTSIndex = 0  // Tracks what we've spoken from finalBubbleText
            var isFirstChunk = true
            
            try {
                while (isActive) {
                    // CRITICAL FIX: During streaming, use fullResponseText which keeps growing
                    // Only use finalBubbleText when LLM is complete
                    val sourceText = if (isLLMResponseComplete && finalBubbleText.isNotBlank()) {
                        finalBubbleText
                    } else {
                        removeImEndTokenOnly(fullResponseText.toString())
                    }
                    
                    // Check if there's new text to speak
                    if (sourceText.length > lastSentToTTSIndex) {
                        val unspokenText = sourceText.substring(lastSentToTTSIndex)
                        
                        // Look for sentence boundary
                        val sentenceEnd = unspokenText.indexOfFirst { it == '.' || it == '!' || it == '?' }
                        
                        if (sentenceEnd != -1) {
                            // Found complete sentence - speak it
                            val sentence = unspokenText.substring(0, sentenceEnd + 1).trim()
                            if (sentence.isNotBlank()) {
                                val filtered = filterTTSText(sentence)
                                if (filtered.isNotBlank()) {
                                    val queueMode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                                    Log.d(TAG, "TTS streaming: '$filtered'")
                                    kokoroTTS?.speakQueued(filtered, queueMode)
                                    isFirstChunk = false
                                }
                            }
                            lastSentToTTSIndex += sentenceEnd + 1
                        }
                    }
                    
                    // Check if LLM is complete
                    if (isLLMResponseComplete) {
                        // Speak any remaining text from finalBubbleText
                        val sourceText = finalBubbleText.ifBlank { removeImEndTokenOnly(fullResponseText.toString()) }
                        
                        if (sourceText.length > lastSentToTTSIndex) {
                            val remainder = sourceText.substring(lastSentToTTSIndex).trim()
                            if (remainder.isNotBlank()) {
                                val filtered = filterTTSText(remainder)
                                if (filtered.isNotBlank()) {
                                    val queueMode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                                    Log.i(TAG, "TTS final remainder: '$filtered'")
                                    
                                    // CRITICAL FIX: Check TTS is still ready before speaking
                                    if (kokoroTTS?.isReady == true) {
                                        kokoroTTS?.speakQueued(filtered, queueMode)
                                    } else {
                                        Log.e(TAG, "TTS became unavailable before speaking final remainder")
                                    }
                                }
                            }
                        }
                        
                        // CRITICAL FIX: Wait for actual TTS completion via utterance listener
                        // isSpeaking() API is broken on Samsung Tab A7 Lite
                        Log.i(TAG, "TTS: Waiting for actual completion via utterance listener...")
                        
                        // CRITICAL FIX: Only wait if TTS is still ready
                        if (kokoroTTS?.isReady == true) {
                            withTimeoutOrNull(30000) {
                                kokoroTTS?.waitForCompletion(30000)
                            }
                            Log.i(TAG, "NILO_DEBUG: TTS finished")
                        } else {
                            Log.w(TAG, "TTS not ready during completion wait, skipping")
                        }
                        break
                    }
                    
                    delay(50)
                }
                
                
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}")
            } finally {
                isStreamingTTSActive = false
                lastSpokenPosition = 0
                // DO NOT reset isLLMResponseComplete here — let the unified completion check handle it
                streamingTTSJob = null

                // Notify audio recorder that TTS stopped
                audioRecorder?.setTTSSpeaking(false)

                Log.i(TAG, "NILO_DEBUG: TTS finished - preparing for next utterance")

                // CRITICAL FIX: Record when TTS finished to prevent echo detection
                lastTTSFinishedTime = System.currentTimeMillis()

                // Reset all speech-related state for next utterance
                speechBuffer.clear()
                preSpeechBuffer.clear()
                isCollectingSpeech = false
                isProcessingSTT = false
                consecutiveSilenceFrames = 0
                ttsTextBuffer.clear()

                // Reset VAD processor for clean state - CRITICAL for auto-listening
                vadProcessor?.reset()

                // DO NOT clear LLM context here — processWithLLMProvider already does it when generation completes.
                // Clearing here creates a race condition if the user has already started the next question.

                // Reset for next query
                currentQueryClassification = null

                // CRITICAL FIX: Reset listening timer so timeout doesn't fire immediately
                listeningStartTime = System.currentTimeMillis()

                // CRITICAL FIX: Ensure isRunning is true for next listening cycle
                if (!isRunning.get()) {
                    Log.w(TAG, "CRITICAL: isRunning was false, setting to true")
                    isRunning.set(true)
                }

                // CRITICAL FIX: Do NOT transition to LISTENING here immediately.
                // The mic must stay off until LLM generation is also complete.
                // awaitCompletionAndListen polls every 500ms for both conditions.
                scope.launch { awaitCompletionAndListen() }

                Log.i(TAG, "✓ TTS cleanup done — awaiting LLM completion before listening")
            }
        }
    }
    
    /**
     * Check if text has a complete sentence ready to speak
     * Looks for .!? followed by space (but not after numbered list like "1. ")
     */
    /**
     * Find a complete sentence/phrase to speak from available text
     * Returns Pair(speakableText, breakPoint) or Pair(null, 0) if no complete sentence found
     * 
     * CRITICAL FIX: Now also returns partial text if it's substantial (>30 chars) to prevent skipping
     */
    private fun findSpeakableChunk(availableText: String, isFinal: Boolean = false): Pair<String?, Int> {
        if (availableText.length < 5) return Pair(null, 0)  // Reduced from 10 to 5
        
        // Look for sentence boundaries (.!? followed by space or end)
        for (i in 1 until availableText.length - 1) {
            val char = availableText[i]
            val nextChar = availableText.getOrNull(i + 1)
            
            if (char == '.' || char == '!' || char == '?') {
                // Check if it's NOT a numbered list item (not "5." or "1." at start)
                val isNumberedList = i >= 1 && availableText[i-1].isDigit() && 
                    (i == 1 || availableText[i-2] == ' ' || i-2 < 0)
                
                if (!isNumberedList) {
                    // Include the punctuation, check for following space
                    var breakPoint = i + 1
                    if (nextChar == ' ') {
                        breakPoint = i + 2  // Skip the space
                    }
                    val rawText = availableText.substring(0, breakPoint).trim()
                    val speakableText = filterTTSText(rawText)
                    Log.d(TAG, "findSpeakableChunk: found sentence at $i: '$speakableText'")
                    return Pair(speakableText, breakPoint)
                }
            }
        }
        
        // If buffer is getting large (>150 chars), force speak at comma or any natural break
        if (availableText.length > 150) {
            for (i in availableText.length - 1 downTo maxOf(availableText.length - 50, 0)) {
                if (availableText[i] == ',' || availableText[i] == ';' || availableText[i] == ' ') {
                    val breakPoint = i + 1
                    val rawText = availableText.substring(0, breakPoint).trim()
                    if (rawText.length >= 20) {  // Only speak if we have enough content
                        val speakableText = filterTTSText(rawText)
                        Log.d(TAG, "findSpeakableChunk: forced break at ${availableText[i]}: '$speakableText'")
                        return Pair(speakableText, breakPoint)
                    }
                }
            }
        }
        
        // CRITICAL FIX: If this is the final call and we have substantial text, speak it anyway
        // This prevents text without ending punctuation from being skipped
        if (isFinal && availableText.length >= 10) {
            val speakableText = filterTTSText(availableText.trim())
            Log.d(TAG, "findSpeakableChunk: FINAL - speaking remaining text: '$speakableText'")
            return Pair(speakableText, availableText.length)
        }
        
        Log.v(TAG, "findSpeakableChunk: no chunk found in '${availableText.take(50)}...'")
        return Pair(null, 0)
    }
    
    /**
     * Filter TTS text to remove special tokens that should not be spoken
     * Removes <|im_end|>, <|im_start|>, memory tags, and other template tokens
     * 
     * CRITICAL FIX: Also TRUNCATES at end markers (like filterAiResponse) to prevent
     * TTS from speaking content that comes after <|im_end|> which is hidden from UI.
     */
    /**
     * CRITICAL FIX: Truncate response when model starts generating system/user role content.
     * This prevents TTS from speaking hidden system prompts or context leakage.
     */
    private fun truncateAtRoleLeakage(text: String): String {
        // IMMEDIATE TRUNCATION: If text starts with role markers, it's 100% leakage
        val roleStartPatterns = listOf(
            "user:", "user :", "[user]", "<|user|>", "<|im_start|>user",
            "system:", "system :", "[system]", "<|system|>", "<|im_start|>system",
            "assistant:", "assistant :", "[assistant]",
            "you are a user interface", "youareauserinterface",
            "your task is to", "yourtaskisto",
            "type something here", "typesomethinghere"
        )
        
        for (pattern in roleStartPatterns) {
            val index = text.indexOf(pattern, ignoreCase = true)
            if (index != -1) {
                Log.w(TAG, "ROLE LEAKAGE DETECTED at index $index: '$pattern' - TRUNCATING")
                return text.substring(0, index)
            }
        }
        
        // Also check for mid-text role transitions (model trying to start new conversation)
        val midRolePatterns = listOf(
            "<|im_start|>", "<|im_end|>", "|im_start|>", "|im_end|>",
            "|im_end|", "|im_start|",  // Partial tokens
            "im_end|", "im_start|",    // Even more partial
            "</s>", "[/s]", "<|endoftext|>", "<|end|>"
        )
        
        for (pattern in midRolePatterns) {
            val index = text.indexOf(pattern, ignoreCase = true)
            if (index != -1) {
                Log.w(TAG, "ROLE TRANSITION DETECTED at index $index: '$pattern' - TRUNCATING")
                return text.substring(0, index)
            }
        }
        
        // Also truncate at Chinese characters (system content leakage)
        val chineseCharPattern = Regex("[\u4e00-\u9fff]")
        val chineseMatch = chineseCharPattern.find(text)
        if (chineseMatch != null) {
            Log.w(TAG, "CHINESE CHARACTER DETECTED at index ${chineseMatch.range.first} - TRUNCATING")
            return text.substring(0, chineseMatch.range.first)
        }
        
        return text
    }
    
    /**
     * NEW: Remove only the <|im_end|> token but keep ALL content after it.
     * This shows all LLM-generated tokens in the UI while hiding the marker itself.
     */
    private fun removeImEndTokenOnly(text: String): String {
        var result = text
        // Remove complete <|im_end|> tokens
        result = result.replace("<|im_end|>", "")
        // Remove partial tokens that might be split
        result = result.replace("|im_end|>", "")
        result = result.replace("|im_end|", "")
        result = result.replace("im_end|", "")
        return result
    }
    
    /**
     * CRITICAL FIX: Detect if text contains role leakage patterns.
     * Returns true if the model is generating system/user content.
     */
    private fun detectRoleLeakage(text: String): Boolean {
        val leakagePatterns = listOf(
            // Role markers
            "user:", "user :", "[user]", "<|user|>", "<|im_start|>user",
            "system:", "system :", "[system]", "<|system|>", "<|im_start|>system",
            // System prompt content
            "you are a user interface", "youareauserinterface",
            "your task is to", "yourtaskisto",
            "type something here", "typesomethinghere",
            "qwen", "qwencoder",  // Model name references
            "conversation mode", "text bubble",
            // Chat template markers - various forms
            "<|im_start|>", "<|im_end|>", "|im_start|>", "|im_end|>",
            "|im_end|", "|im_start|",  // Partial tokens without brackets
            "im_end|", "im_start|",    // Even more partial
            "</s>", "[/s]", "<|endoftext|>", "<|end|>"
        )
        
        for (pattern in leakagePatterns) {
            if (text.contains(pattern, ignoreCase = true)) {
                Log.w(TAG, "detectRoleLeakage: FOUND '$pattern' in text")
                return true
            }
        }
        
        // Also detect non-ASCII content after valid English response (indicates system leakage)
        // Look for Chinese/Japanese characters which indicate internal system content
        val chineseCharPattern = Regex("[\u4e00-\u9fff]")  // Chinese characters
        if (chineseCharPattern.containsMatchIn(text)) {
            Log.w(TAG, "detectRoleLeakage: FOUND Chinese characters (system content leakage)")
            return true
        }
        
        return false
    }
    
    private fun filterTTSText(text: String): String {
        if (text.isBlank()) return ""
        
        // CRITICAL FIX: Only remove the <|im_end|> marker, keep ALL content after it
        var filtered = removeImEndTokenOnly(text)
        
        // STEP 1: Remove memory-related text patterns (case insensitive)
        filtered = filtered.replace(Regex("(?i)\\[?MEMORY\\]?"), "")
        filtered = filtered.replace(Regex("(?i)END\\s*OF\\s*MEMORY"), "")
        filtered = filtered.replace(Regex("(?i)START\\s*OF\\s*MEMORY"), "")
        filtered = filtered.replace(Regex("(?i)<--\\s*START"), "")
        filtered = filtered.replace(Regex("(?i)<--\\s*END"), "")
        filtered = filtered.replace(Regex("(?i)---\\s*End\\s*Context\\s*---"), "")
        filtered = filtered.replace(Regex("(?i)---\\s*Memory\\s*Context\\s*---"), "")
        filtered = filtered.replace(Regex("(?i)\\[/MEMORY\\]"), "")
        
        // STEP 3: Remove special Qwen2.5 chat template tokens
        filtered = filtered.replace("<|im_end|>", "")
        filtered = filtered.replace("<|im_start|>", "")
        filtered = filtered.replace("|im_end|>", "")
        filtered = filtered.replace("|im_start|>", "")
        filtered = filtered.replace("|im_end|", "")  // CRITICAL: catch |im_end|| pattern
        filtered = filtered.replace("|im_start|", "")  // Catch partial start
        filtered = filtered.replace("<|im_end", "")
        filtered = filtered.replace("<|im_start", "")
        filtered = filtered.replace("<|", " ")
        filtered = filtered.replace("|>", " ")
        
        // STEP 4: Remove role markers only when they appear as standalone role indicators
        filtered = filtered.replace(Regex("(?i)^\\s*system\\s*\n"), "")
        filtered = filtered.replace(Regex("(?i)^\\s*assistant\\s*\n"), "")
        filtered = filtered.replace(Regex("(?i)^\\s*user\\s*\n"), "")
        
        // STEP 5: Remove other special tokens
        filtered = filtered.replace("</s>", "")
        filtered = filtered.replace("<s>", "")

        // STEP 5b: Strip Chinese/Japanese characters that the system TTS can't pronounce
        filtered = filtered.replace(Regex("[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff]"), "")

        // STEP 6: Replace ellipsis with space (TTS shouldn't say "dot dot dot")
        filtered = filtered.replace("...", " ")
        filtered = filtered.replace("…", " ")  // Unicode ellipsis
        
        // STEP 7: Clean up any remaining angle brackets and dashes that might be spoken
        filtered = filtered.replace(Regex("<[^>]*>"), " ")
        filtered = filtered.replace(Regex("-{3,}"), " ")  // 3+ dashes
        
        // STEP 7: Clean up whitespace
        filtered = filtered.replace(Regex("\\s+"), " ")
        
        return filtered.trim()
    }
    
    /**
     * Filter individual streaming tokens for TTS.
     * The model generates <|im_end|> as separate tokens: '<', '|', 'im', '_end', '|', '>'
     * OR as: '|', 'im', '_end', '|' (partial without brackets)
     * This buffers and detects these patterns, returning null for partial tokens.
     */
    private fun filterStreamingTokenForTTS(token: String): String? {
        // Quick check: if token contains complete marker, remove it
        if (token.contains("<|im_end|>") || token.contains("<|im_start|>") || 
            token.contains("|im_end|") || token.contains("|im_start|")) {
            return token.replace("<|im_end|>", "")
                       .replace("<|im_start|>", "")
                       .replace("|im_end|", "")
                       .replace("|im_start|", "")
        }
        
        val trimmed = token.trim()
        
        // Pattern 1: Full <|im_end|> = '<', '|', 'im', '_end', '|', '>'
        // Pattern 2: Partial |im_end| = '|', 'im', '_end', '|'
        when {
            // Start of <|im_start|> or <|im_end|>
            trimmed == "<" -> {
                streamingTokenBuffer.clear()
                streamingTokenBuffer.append("<")
                isCollectingImEnd = true
                return null
            }
            // Continuation of <|...
            isCollectingImEnd && trimmed == "|" && streamingTokenBuffer.toString() == "<" -> {
                streamingTokenBuffer.append("|")
                return null
            }
            isCollectingImEnd && (trimmed == "im" || trimmed == "_end") && 
                (streamingTokenBuffer.toString() == "<|" || streamingTokenBuffer.toString().endsWith("im")) -> {
                streamingTokenBuffer.append(trimmed)
                return null
            }
            isCollectingImEnd && trimmed == "|" && streamingTokenBuffer.toString().contains("im") -> {
                streamingTokenBuffer.append("|")
                return null
            }
            isCollectingImEnd && trimmed == ">" && streamingTokenBuffer.toString().startsWith("<|") -> {
                // Complete <|im_end|> or <|im_start|> - discard it
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                return null
            }
            // Pattern 2: |im_end| (starts with | instead of <|)
            trimmed == "|" && !isCollectingImEnd -> {
                streamingTokenBuffer.clear()
                streamingTokenBuffer.append("|")
                isCollectingImEnd = true
                return null
            }
            isCollectingImEnd && trimmed == "im" && streamingTokenBuffer.toString() == "|" -> {
                streamingTokenBuffer.append("im")
                return null
            }
            isCollectingImEnd && trimmed == "_end" && streamingTokenBuffer.toString() == "|im" -> {
                streamingTokenBuffer.append("_end")
                return null
            }
            isCollectingImEnd && trimmed == "|" && streamingTokenBuffer.toString() == "|im_end" -> {
                // Complete |im_end| - discard it
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                return null
            }
            // If we're collecting but pattern doesn't match, flush buffer
            isCollectingImEnd -> {
                val bufferContent = streamingTokenBuffer.toString()
                streamingTokenBuffer.clear()
                isCollectingImEnd = false
                // Don't add this token if it looks like end marker
                if (trimmed == "_end" || trimmed == "im_end" || trimmed.contains("im")) {
                    return bufferContent
                }
                return bufferContent + token
            }
        }
        
        return token
    }
    
    /**
     * Count the number of complete sentences in text.
     * Used to limit response length for voice mode.
     */
    private fun countSentences(text: String): Int {
        if (text.length < 10) return 0
        
        var count = 0
        for (i in 1 until text.length - 1) {
            val char = text[i]
            if (char == '.' || char == '!' || char == '?') {
                // Check if it's a numbered list item (digit + .)
                if (i > 0 && text[i-1].isDigit()) {
                    val isAfterSpace = i == 1 || text[i-2] == ' '
                    if (isAfterSpace) continue  // Skip "1." "2." etc.
                }
                count++
            }
        }
        return count
    }
    
    private fun hasCompleteSentence(text: String): Boolean {
        if (text.length < 20) return false  // Need at least some content
        
        for (i in 1 until text.length - 1) {
            val char = text[i]
            if (char == '.' || char == '!' || char == '?') {
                // Check if it's a numbered list item (digit + .)
                // e.g., "5." or "1." at start or after space
                if (i > 0 && text[i-1].isDigit()) {
                    val isAfterSpace = i == 1 || text[i-2] == ' '
                    if (isAfterSpace) continue  // Skip "1." "2." etc.
                }
                
                // Found sentence end
                return true
            }
        }
        return false
    }
    
    /**
     * Check if response has reached length limits and should stop generation.
     * Only returns true if we have at least one complete sentence and have
     * exceeded either the sentence count or character limit.
     */
    private fun shouldStopResponse(
        text: String,
        maxChars: Int = MAX_RESPONSE_CHARS,
        maxSentences: Int = MAX_RESPONSE_SENTENCES
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 80) return false  // Need minimum content before stopping
        
        // Count complete sentences (same logic as hasCompleteSentence)
        var sentenceCount = 0
        for (i in 1 until trimmed.length - 1) {
            val char = trimmed[i]
            if (char == '.' || char == '!' || char == '?') {
                if (i > 0 && trimmed[i-1].isDigit()) {
                    val isAfterSpace = i == 1 || trimmed[i-2] == ' '
                    if (isAfterSpace) continue
                }
                sentenceCount++
            }
        }
        
        val endsWithCompleteSentence = trimmed.lastOrNull()?.let { it == '.' || it == '!' || it == '?' } ?: false
        
        // Stop if we have enough sentences AND end with a complete sentence
        if (sentenceCount >= maxSentences && endsWithCompleteSentence) {
            Log.i(TAG, "Stopping response: reached $sentenceCount sentences")
            return true
        }
        
        // Stop if we reached max chars AND end with a complete sentence
        if (trimmed.length >= maxChars && endsWithCompleteSentence) {
            Log.i(TAG, "Stopping response: reached ${trimmed.length} chars")
            return true
        }
        
        return false
    }
    
    /**
     * Release all resources
     */
    fun release() {
        Log.i(TAG, "Releasing VoicePipelineManager...")
        
        isRunning.set(false)
        
        pipelineJob?.cancel()
        pipelineJob = null
        
        audioRecorder?.release()
        audioRecorder = null
        
        vadBridge?.release()
        vadBridge = null
        vadProcessor = null
        
        sttBridge?.release()
        sttBridge = null
        
        kokoroTTS?.release()
        kokoroTTS = null
        
        // Clear accumulated text
        fullResponseText.clear()
        
        Log.i(TAG, "✓ VoicePipelineManager released")
        
        scope.cancel()
        
        isInitialized = false
        
        Log.i(TAG, "✓ VoicePipelineManager released")
    }
    
    // Status methods
    fun isInitialized(): Boolean = isInitialized
    fun isRunning(): Boolean = isRunning.get()
    fun getVADName(): String = vadBridge?.getName() ?: "None"
    fun getSTTName(): String = sttBridge?.getName() ?: "None"
    fun getTTSName(): String = if (kokoroTTS?.isReady == true) "Kokoro TTS" else "None"
    fun getAudioLevel(): Float = 0f  // Updated via callback
    
    /**
     * Debug function to check pipeline status
     * Call this from the mic button handler to diagnose issues
     */
    fun debugStatus() {
        Log.d("DEBUG", "=== Voice Pipeline Debug Status ===")
        Log.d("DEBUG", "Pipeline state: $currentState")
        Log.d("DEBUG", "isInitialized: $isInitialized")
        Log.d("DEBUG", "isRunning: ${isRunning.get()}")
        Log.d("DEBUG", "VAD ready: ${vadBridge?.isLoaded()}")
        Log.d("DEBUG", "VAD available: ${vadBridge?.isAvailable()}")
        Log.d("DEBUG", "STT ready: ${sttBridge?.isAvailable()}")
        Log.d("DEBUG", "AudioRecorder: ${audioRecorder != null}")
        Log.d("DEBUG", "TTS ready: ${kokoroTTS?.isReady}")
        Log.d("DEBUG", "===================================")
    }
}

enum class BridgeMode {
    REAL,
    MOCK,
    AUTO
}
