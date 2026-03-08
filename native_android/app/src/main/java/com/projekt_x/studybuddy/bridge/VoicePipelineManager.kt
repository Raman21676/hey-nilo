package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.media.AudioManager
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import com.projekt_x.studybuddy.InferenceQueue
import com.projekt_x.studybuddy.bridge.llm.*
import com.projekt_x.studybuddy.util.CopyAssetToExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

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
        
        // VAD Configuration
        private const val VAD_THRESHOLD = 0.6f       // INCREASED to 0.6 - less sensitive to background noise (was 0.5)
        private const val MIN_SPEECH_MS = 300L        // Minimum speech to process
        private const val MIN_SILENCE_MS = 800L       // REDUCED to 800ms - faster response after speech ends (was 1200)
        private const val PRE_SPEECH_BUFFER_MS = 800L // Pre-speech capture buffer
        private const val MAX_SPEECH_MS = 8000L       // Maximum 8 seconds for faster processing (was 15s)
        // INCREASED: Allows natural pauses between sentences
        // 25 frames * 32ms = ~800ms - won't cut off "Who are you? [pause] Can you..."
        private const val TRAILING_SILENCE_FRAMES = 25  // Was 8 (~256ms) - too short
        
        // NO SOFTWARE GAIN — hardware AGC via VOICE_RECOGNITION handles this
        // Previous AGC code removed per professor's guidance: gain causes clipping/distortion
        
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
    
    // Bridges
    private var vadBridge: VADBridge? = null
    private var vadProcessor: VADProcessor? = null
    private var sttBridge: RealSTTBridge? = null  // Whisper STT - using TINY model for speed!
    private var kokoroTTS: KokoroTTSBridge? = null  // Kokoro TTS via Sherpa-ONNX AAR
    private var fullResponseText = StringBuilder()  // Accumulate LLM response for TTS
    
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
    
    // Current state
    private var currentState = PipelineState.IDLE
        set(value) {
            field = value
            Log.i(TAG, "State changed: $value")
            onStateChange?.invoke(value)
        }
    
    /**
     * Initialize the entire pipeline
     */
    fun initialize(mode: BridgeMode = BridgeMode.AUTO) {
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
                    onInitialized?.invoke(false)
                    onError?.invoke("Microphone permission required")
                    return@launch
                }
                Log.i(TAG, "✓ Audio recorder created ($SAMPLE_RATE Hz)")
                
                // Step 4: Initialize Kokoro TTS
                Log.i(TAG, "\n[Step 4/5] Initializing Kokoro TTS...")
                try {
                    kokoroTTS = KokoroTTSBridge(context)
                    // Wait for TTS initialization with timeout
                    val ttsOk = withTimeoutOrNull(10000) {
                        kokoroTTS!!.initialize()
                    } ?: false
                    
                    if (ttsOk && kokoroTTS!!.isReady) {
                        Log.i(TAG, "✓ Kokoro TTS initialized")
                    } else {
                        Log.w(TAG, "✗ Kokoro TTS failed")
                        kokoroTTS = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Kokoro TTS exception: ${e.message}")
                    kokoroTTS = null
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
                
                Log.i(TAG, "\n" + "=".repeat(60))
                Log.i(TAG, "PIPELINE STATUS:")
                Log.i(TAG, "  VAD: ${if (vadBridge?.isAvailable() == true) "✓" else "✗"} ${vadBridge?.getName() ?: "None"}")
                Log.i(TAG, "  STT: ${if (sttBridge?.isAvailable() == true) "✓" else "✗"} ${sttBridge?.getName() ?: "None"}")
                Log.i(TAG, "  TTS: ${if (kokoroTTS?.isReady == true) "✓" else "✗"} Kokoro TTS")
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
        currentState = PipelineState.LISTENING
        
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
        }
        
        // Ensure we're in the LISTENING state
        if (currentState != PipelineState.LISTENING) {
            Log.d(TAG, "Setting state to LISTENING before starting recording")
            currentState = PipelineState.LISTENING
        }
        
        pipelineJob = scope.launch {
            Log.i(TAG, "Voice pipeline started - listening for speech...")
            
            // Start audio recording with VAD processing
            val recordingStarted = audioRecorder?.startRecording(object : SimpleAudioRecorder.AudioCallback {
                override fun onAudioData(audioData: ShortArray) {
                    if (!isRunning.get()) return
                    
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
                Log.e(TAG, "Failed to start recording")
                onError?.invoke("Failed to start recording")
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
        // Calculate audio level for visualization (always, even during non-listening states)
        val audioLevel = calculateAudioLevel(audioData)
        
        // DEBUG: Log audio level periodically
        if (audioLevel > 0.001f) {
            Log.d(TAG, "Audio level: ${"%.3f".format(audioLevel)}, state: $currentState")
        }
        
        onAudioLevel?.invoke(audioLevel)
        
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
        
        // Add to pre-speech buffer (circular buffer)
        preSpeechBuffer.addAll(audioData.toList())
        val preSpeechMaxSize = (SAMPLE_RATE / 1000 * PRE_SPEECH_BUFFER_MS).toInt()
        while (preSpeechBuffer.size > preSpeechMaxSize) {
            preSpeechBuffer.removeFirst()
        }
        
        // Run VAD processor
        val vadState = vadProcessor?.process(audioData) ?: VADState.SILENCE
        
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
                    
                    // Move pre-speech buffer to speech buffer
                    speechBuffer.clear()
                    speechBuffer.addAll(preSpeechBuffer)
                    
                    // Include the current frame
                    speechBuffer.addAll(audioData.toList())
                    
                    currentState = PipelineState.SPEECH_DETECTED
                    Log.i(TAG, "Started collecting speech (pre-speech: ${preSpeechBuffer.size} + frame: ${audioData.size} samples)")
                } else {
                    // Subsequent SPEECH_START frames during confirmation period
                    consecutiveSilenceFrames = 0  // Reset silence counter
                    speechBuffer.addAll(audioData.toList())
                }
            }
            
            VADState.SPEECH -> {
                if (isCollectingSpeech) {
                    // Add to speech buffer
                    speechBuffer.addAll(audioData.toList())
                    
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
                
                if (isCollectingSpeech) {
                    // Include the final frame
                    speechBuffer.addAll(audioData.toList())
                    
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
                    
                    currentState = PipelineState.LISTENING
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
            currentState = PipelineState.LISTENING
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
        currentState = PipelineState.TRANSCRIBING
        
        // Process with STT
        scope.launch {
            try {
                val sttProcessingStart = System.currentTimeMillis()
                val transcription = sttBridge?.transcribe(speechArray) ?: ""
                val sttProcessingEnd = System.currentTimeMillis()
                Log.i(TAG, "STT processing took ${sttProcessingEnd - sttProcessingStart}ms")
                
                if (transcription.isNotBlank()) {
                    Log.i(TAG, "STT Result: \"$transcription\"")
                    
                    withContext(Dispatchers.Main) {
                        onTranscriptionUpdate?.invoke(transcription, true)
                    }
                    
                    // Process with LLM if available
                    if (llmBridge != null && queue != null) {
                        currentState = PipelineState.THINKING
                        processWithLLM(transcription)
                    }
                } else {
                    Log.w(TAG, "STT returned empty transcription (${durationMs}ms audio was likely noise)")
                    currentState = PipelineState.LISTENING
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT processing failed", e)
                currentState = PipelineState.ERROR
                withContext(Dispatchers.Main) {
                    onError?.invoke("STT failed: ${e.message}")
                }
            } finally {
                isProcessingSTT = false
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
     * Stop voice conversation
     */
    fun stopConversation() {
        if (!isRunning.get()) return
        
        Log.i(TAG, "Stopping voice conversation...")
        isRunning.set(false)
        currentState = PipelineState.IDLE
        
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
        isLLMResponseComplete = false  // Reset for next conversation
        
        pipelineJob?.cancel()
        pipelineJob = null
        
        Log.i(TAG, "Voice conversation stopped")
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
        if (llmProvider?.isAvailable() == true) {
            processWithLLMProvider(transcription)
        } else {
            processWithLegacyQueue(transcription)
        }
    }
    
    /**
     * Process with new LLMProvider (streaming, memory context)
     */
    private fun processWithLLMProvider(transcription: String) {
        scope.launch {
            try {
                // Clear previous response text when NEW user query starts
                fullResponseText.clear()
                
                // Build memory context
                val memoryContext = memoryManager?.buildContextBlock(maxTokens = 300) ?: ""
                
                Log.i(TAG, "Sending to LLM Provider: '$transcription'")
                Log.i(TAG, "Memory context: ${if (memoryContext.isNotBlank()) "present" else "none"}")
                
                // Prepare streaming TTS
                ttsTextBuffer.clear()
                lastSpokenPosition = 0
                ttsStartTime = System.currentTimeMillis()
                isStreamingTTSActive = false
                
                // Build completion request
                val request = CompletionRequest(
                    messages = listOf(ChatMessage.user(transcription)),
                    systemPrompt = LlamaBridge.DEFAULT_SYSTEM_PROMPT,
                    memoryContext = memoryContext.ifBlank { null },
                    maxTokens = 256,
                    stream = true
                )
                
                // Stream response
                var tokenCount = 0
                llmProvider?.stream(request)?.collect { response ->
                    when {
                        response.isError -> {
                            currentState = PipelineState.ERROR
                            withContext(Dispatchers.Main) {
                                onError?.invoke("LLM Error: ${response.error}")
                            }
                        }
                        response.isComplete -> {
                            Log.i(TAG, "LLM response complete, tokens: $tokenCount")
                            isLLMResponseComplete = true
                            
                            val finalResponse = fullResponseText.toString()
                            
                            // Start TTS if not already started
                            if (!isStreamingTTSActive && kokoroTTS?.isReady == true && ttsTextBuffer.isNotBlank()) {
                                Log.i(TAG, "LLM complete, starting TTS for short response")
                                startStreamingTTS()
                            }
                            
                            // Send final response to UI
                            withContext(Dispatchers.Main) {
                                onResponseUpdate?.invoke(finalResponse, true)
                            }
                            
                            // Save conversation to memory
                            scope.launch(Dispatchers.IO) {
                                try {
                                    memoryManager?.saveConversationExchange(
                                        userMessage = transcription,
                                        assistantMessage = finalResponse
                                    )
                                    Log.d(TAG, "Conversation exchange saved to memory")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to save conversation: ${e.message}")
                                }
                            }
                        }
                        response.isStreaming -> {
                            tokenCount++
                            currentState = PipelineState.SPEAKING
                            
                            // Append token to response
                            val newText = response.text
                            val token = newText.removePrefix(fullResponseText.toString())
                            fullResponseText.append(token)
                            
                            // Add to TTS buffer
                            if (token.isNotBlank()) {
                                ttsTextBuffer.append(token)
                            }
                            
                            // Start TTS when we have a complete sentence
                            if (!isStreamingTTSActive && hasCompleteSentence(ttsTextBuffer.toString())) {
                                Log.i(TAG, "First sentence ready (${ttsTextBuffer.length} chars), starting streaming TTS")
                                startStreamingTTS()
                            }
                            
                            // Update UI
                            withContext(Dispatchers.Main) {
                                onResponseUpdate?.invoke(fullResponseText.toString(), false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM Provider processing failed", e)
                currentState = PipelineState.ERROR
                withContext(Dispatchers.Main) {
                    onError?.invoke("Processing failed: ${e.message}")
                }
                delay(1000)
                restartListening()
            }
        }
    }
    
    /**
     * Process with legacy InferenceQueue (backward compatibility)
     */
    private fun processWithLegacyQueue(transcription: String) {
        scope.launch {
            try {
                // Clear previous response text when NEW user query starts
                fullResponseText.clear()
                
                // Build memory-enhanced prompt with context block
                val memoryContext = memoryManager?.buildContextBlock(maxTokens = 300) ?: ""
                val enhancedPrompt = if (memoryContext.isNotBlank()) {
                    // Inject memory context before the user message
                    // Format: [MEMORY]...[/MEMORY]\n\nUser: {transcription}
                    "$memoryContext\n\nUser: $transcription"
                } else {
                    transcription
                }
                
                Log.i(TAG, "Sending to LLM (legacy queue) with memory context: ${memoryContext.isNotBlank()}")
                if (memoryContext.isNotBlank()) {
                    Log.d(TAG, "Memory context: ${memoryContext.take(100)}...")
                }
                
                val requestId = System.currentTimeMillis().toString()
                val request = InferenceQueue.Request(
                    id = requestId,
                    prompt = enhancedPrompt,
                    maxTokens = 256,
                    priority = InferenceQueue.Priority.HIGH
                )
                
                Log.i(TAG, "Sending to LLM: '$transcription' (requestId: $requestId)")
                queue?.enqueue(request)
                
                // Collect response - filter by request ID and terminate when complete
                var responseComplete = false
                var tokenCount = 0
                // fullResponseText was already cleared at function start
                ttsTextBuffer.clear()  // Reset streaming TTS buffer
                lastSpokenPosition = 0
                ttsStartTime = System.currentTimeMillis()  // Start timing for streaming TTS
                isStreamingTTSActive = false
                
                // Collect response with proper termination using takeWhile
                queue?.responses
                    ?.takeWhile { !responseComplete }  // Terminate when complete
                    ?.collect { response ->
                        // Only process responses for our request
                        if (response.requestId != requestId) return@collect
                        
                        when {
                            response.error != null -> {
                                currentState = PipelineState.ERROR
                                responseComplete = true
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("LLM Error: ${response.error}")
                                }
                            }
                            response.isComplete -> {
                                Log.i(TAG, "LLM response complete, tokens: $tokenCount")
                                responseComplete = true
                                isLLMResponseComplete = true  // Signal TTS loop to speak remaining text
                                
                                val finalResponse = fullResponseText.toString()
                                
                                // If TTS hasn't started yet (short response), start it now
                                if (!isStreamingTTSActive && kokoroTTS?.isReady == true && ttsTextBuffer.isNotBlank()) {
                                    Log.i(TAG, "LLM complete, starting TTS for short response")
                                    startStreamingTTS()
                                }
                                // If TTS is already running, it will pick up remaining text via isLLMResponseComplete flag
                                
                                // Send FULL accumulated text on complete
                                withContext(Dispatchers.Main) {
                                    onResponseUpdate?.invoke(finalResponse, true)
                                }
                                
                                // MEMORY INTEGRATION: Save conversation to memory
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        memoryManager?.saveConversationExchange(
                                            userMessage = transcription,
                                            assistantMessage = finalResponse
                                        )
                                        Log.d(TAG, "Conversation exchange saved to memory")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to save conversation: ${e.message}")
                                    }
                                }
                                
                                // NOTE: Don't clear fullResponseText here - it's cleared when NEW user speech starts
                            }
                            response.token != null && !response.isComplete -> {
                                // STREAMING TTS: Start speaking as soon as first sentence is ready
                                tokenCount++
                                currentState = PipelineState.SPEAKING
                                fullResponseText.append(response.token)
                                
                                // Accumulate text for TTS
                                val token = response.token
                                if (token.isNotBlank()) {
                                    ttsTextBuffer.append(token)
                                }
                                
                                // STREAMING: Start TTS immediately when we have a complete sentence
                                // Don't wait for full response - user gets bored waiting!
                                if (!isStreamingTTSActive && hasCompleteSentence(ttsTextBuffer.toString())) {
                                    Log.i(TAG, "First sentence ready (${ttsTextBuffer.length} chars), starting streaming TTS")
                                    startStreamingTTS()
                                }
                                
                                withContext(Dispatchers.Main) {
                                    onResponseUpdate?.invoke(fullResponseText.toString(), false)
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "LLM processing failed", e)
                currentState = PipelineState.ERROR
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
     * Restart listening after error or when TTS is not available
     * This is a fallback function for error recovery.
     * For normal TTS flow, we use seamless transition (see finally block in startStreamingTTS).
     */
    private suspend fun restartListening() {
        if (!isRunning.get()) {
            Log.w(TAG, "Cannot restart: pipeline not running")
            return
        }
        
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
        
        // Reset VAD processor for clean state
        vadProcessor?.reset()
        
        // Clear LLM context for next utterance
        llmBridge?.clearContext()
        
        // Only restart recording if it's not already running
        if (audioRecorder?.isRecording() != true) {
            Log.w(TAG, "Recording was stopped - restarting it")
            audioRecorder?.stopRecording()
            pipelineJob?.cancel()
            pipelineJob = null
            delay(200)
            startRecording()
        }
        
        currentState = PipelineState.LISTENING
        Log.i(TAG, "✓ Listening restarted - ready for next utterance")
    }
    
    /**
     * Start TTS - FIXED for smooth continuous speech using QUEUE_ADD
     * 
     * CLAUDE FIX: Use QUEUE_ADD for seamless continuation instead of serial speakAndWait().
     * Wait for first sentence, speak it with QUEUE_FLUSH, then queue rest with QUEUE_ADD.
     * Exit loop when isLLMResponseComplete is true and no text remains.
     */
    private fun startStreamingTTS() {
        if (isStreamingTTSActive || kokoroTTS?.isReady != true) return
        
        isStreamingTTSActive = true
        Log.i(TAG, "Starting TTS (QUEUE_ADD mode)")
        
        // Don't stop recording! Just change state to SPEAKING to pause VAD processing
        currentState = PipelineState.SPEAKING
        
        // FLUSH any previous TTS
        kokoroTTS?.stop()
        
        streamingTTSJob = scope.launch {
            var isFirstChunk = true
            var ttsCompleted = false
            val ttsStartTime = System.currentTimeMillis()
            val MAX_TTS_WAIT_MS = 15000L  // Maximum 15 seconds for TTS loop
            
            try {
                while (isActive) {
                    val currentLength = ttsTextBuffer.length
                    val availableLength = currentLength - lastSpokenPosition
                    
                    val availableText = if (availableLength > 0) 
                        ttsTextBuffer.substring(lastSpokenPosition, currentLength) 
                    else ""
                    
                    // Find a complete sentence to speak
                    val (speakableText, breakPoint) = findSpeakableChunk(availableText)
                    
                    if (speakableText != null && speakableText.isNotBlank()) {
                        // CLAUDE FIX: Use QUEUE_FLUSH for first chunk, QUEUE_ADD for rest
                        val queueMode = if (isFirstChunk) {
                            TextToSpeech.QUEUE_FLUSH
                        } else {
                            TextToSpeech.QUEUE_ADD  // Seamless continuation!
                        }
                        
                        Log.d(TAG, "TTS queueing: '$speakableText' (mode: ${if (isFirstChunk) "FLUSH" else "ADD"})")
                        
                        // Fire-and-forget (don't wait) - let Android TTS handle the queuing
                        kokoroTTS?.speakQueued(text = speakableText, queueMode = queueMode)
                        
                        lastSpokenPosition += breakPoint
                        lastWordSentTime = System.currentTimeMillis()
                        isFirstChunk = false
                        
                        // Small delay to let TTS engine process
                        delay(50)
                    } else if (isLLMResponseComplete && availableLength == 0) {
                        // All text spoken and LLM is complete - we're done!
                        Log.i(TAG, "TTS: All text spoken, waiting for completion...")
                        
                        // Wait a bit for last TTS to start, then track completion
                        delay(200)
                        
                        // Wait for TTS to actually finish speaking
                        kokoroTTS?.waitForCompletion()
                        
                        Log.i(TAG, "TTS: Finished speaking all text")
                        break  // Exit loop → triggers finally → sets LISTENING
                    } else if (isLLMResponseComplete && availableLength > 0) {
                        // LLM done but we have remaining text that didn't end with punctuation
                        val remaining = availableText.trim()
                        if (remaining.isNotBlank()) {
                            Log.d(TAG, "TTS speaking final remainder: '$remaining'")
                            kokoroTTS?.speakQueued(
                                text = remaining,
                                queueMode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                            )
                            lastSpokenPosition = ttsTextBuffer.length
                            
                            // Wait for completion
                            delay(200)
                            kokoroTTS?.waitForCompletion()
                        } else {
                            Log.d(TAG, "TTS: remaining text is blank, skipping")
                        }
                        break
                    } else {
                        // No text ready yet, wait
                        delay(20)
                        
                        // SAFETY: Break if TTS has been running too long (prevents infinite loop)
                        val ttsElapsed = System.currentTimeMillis() - ttsStartTime
                        if (ttsElapsed > MAX_TTS_WAIT_MS) {
                            Log.w(TAG, "TTS loop timeout after ${ttsElapsed}ms, forcing exit")
                            break
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}")
            } finally {
                isStreamingTTSActive = false
                lastSpokenPosition = 0
                isLLMResponseComplete = false  // Reset for next utterance
                
                Log.i(TAG, "TTS finished - preparing for next utterance")
                
                // Reset all speech-related state for next utterance
                speechBuffer.clear()
                preSpeechBuffer.clear()
                isCollectingSpeech = false
                isProcessingSTT = false
                consecutiveSilenceFrames = 0
                ttsTextBuffer.clear()
                // NOTE: Don't clear fullResponseText here - it's cleared when NEW user speech starts
                // This prevents text from disappearing after TTS finishes
                
                // Reset VAD processor for clean state - CRITICAL for auto-listening
                vadProcessor?.reset()
                
                // Clear LLM context for next utterance
                llmBridge?.clearContext()
                
                // Resume listening by changing state back to LISTENING
                currentState = PipelineState.LISTENING
                
                // Ensure audio recorder is still running
                if (audioRecorder?.isRecording() != true) {
                    Log.w(TAG, "Recorder not running, restarting...")
                    startRecording()
                }
                
                Log.i(TAG, "✓ Ready for next utterance - auto-listening active")
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
     */
    private fun findSpeakableChunk(availableText: String): Pair<String?, Int> {
        if (availableText.length < 10) return Pair(null, 0)  // Need some content
        
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
                    val speakableText = availableText.substring(0, breakPoint).trim()
                    return Pair(speakableText, breakPoint)
                }
            }
        }
        
        // If buffer is getting large (>200 chars), force speak at comma
        if (availableText.length > 200) {
            for (i in availableText.length - 1 downTo maxOf(availableText.length - 50, 0)) {
                if (availableText[i] == ',' || availableText[i] == ';') {
                    val breakPoint = i + 1
                    val speakableText = availableText.substring(0, breakPoint).trim()
                    return Pair(speakableText, breakPoint)
                }
            }
        }
        
        return Pair(null, 0)
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
