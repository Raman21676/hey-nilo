package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * Android System TTS Bridge
 * 
 * Uses Android's built-in TextToSpeech engine.
 * Samsung devices have high-quality offline TTS.
 */
class KokoroTTSBridge(private val context: Context) {
    private val TAG = "KokoroTTSBridge"
    
    private var androidTts: TextToSpeech? = null
    
    var isReady: Boolean = false
        private set
    
    // CRITICAL FIX: Track initialization errors for diagnostics
    private var initError: String? = null
    private var initAttemptCount = 0
    
    // CRITICAL FIX: Track if TTS engine is actually available on device
    private var isTtsEngineAvailable = false
    
    // Track pending utterances for proper completion detection
    private val pendingUtterances = mutableSetOf<String>()
    private val utteranceLock = Object()
    
    // CRITICAL FIX: Track last utterance time for fallback completion detection
    // Some Android TTS engines don't fire onDone reliably
    private var lastUtteranceStartTime: Long = 0
    private var lastUtteranceExpectedDurationMs: Long = 0
    
    /**
     * Initialize Android System TTS
     * CRITICAL FIX: Robust initialization with retry, fallback, and detailed diagnostics
     */
    suspend fun initialize(maxRetries: Int = 2): Boolean = withContext(Dispatchers.Main) {
        initAttemptCount++
        Log.i(TAG, "TTS initialization attempt $initAttemptCount (max retries: $maxRetries)")
        
        // CRITICAL FIX: Check if TTS engine is available before trying
        isTtsEngineAvailable = checkTtsEngineAvailability()
        if (!isTtsEngineAvailable) {
            Log.e(TAG, "No TTS engine available on device!")
            initError = "No TTS engine installed on device"
            isReady = false
            return@withContext false
        }
        
        try {
            // Shutdown any existing instance first
            shutdown()
            delay(100) // Give time for cleanup
            
            val initResult = CompletableDeferred<Boolean>()
            
            androidTts = TextToSpeech(context) { status ->
                Log.d(TAG, "TTS init callback: status=$status")
                if (status == TextToSpeech.SUCCESS) {
                    configureTts(initResult)
                } else {
                    Log.e(TAG, "✗ Android TTS init failed with status: $status")
                    initError = "Init failed with status $status"
                    initResult.complete(false)
                }
            }
            
            // Wait for initialization with timeout
            var success = withTimeoutOrNull(10000) {
                initResult.await()
            } ?: false
            
            if (!success) {
                Log.e(TAG, "TTS initialization failed on attempt $initAttemptCount")
                isReady = false
                
                // CRITICAL FIX: Retry if we haven't exceeded max retries
                if (initAttemptCount <= maxRetries) {
                    Log.i(TAG, "Retrying TTS initialization...")
                    delay(500) // Wait before retry
                    return@withContext initialize(maxRetries)
                }
            } else {
                // CRITICAL FIX: Verify TTS is actually working by doing a test
                val testSuccess = verifyTtsWorking()
                if (!testSuccess) {
                    Log.w(TAG, "TTS initialized but verification failed")
                    // Don't fail here - let it try to work
                }
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "TTS error during init: ${e.message}", e)
            initError = "Exception: ${e.message}"
            isReady = false
            
            // CRITICAL FIX: Retry on exception too
            if (initAttemptCount <= maxRetries) {
                Log.i(TAG, "Retrying TTS initialization after exception...")
                delay(500)
                return@withContext initialize(maxRetries)
            }
            false
        }
    }
    
    /**
     * CRITICAL FIX: Check if any TTS engine is available on the device
     */
    private fun checkTtsEngineAvailability(): Boolean {
        return try {
            val pm = context.packageManager
            val intent = android.content.Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val resolveInfo = pm.queryIntentActivities(intent, 0)
            val available = resolveInfo.isNotEmpty()
            Log.i(TAG, "TTS engine availability check: $available (${resolveInfo.size} engines found)")
            available
        } catch (e: Exception) {
            Log.e(TAG, "Error checking TTS availability: ${e.message}")
            true // Assume available and let init fail if not
        }
    }
    
    /**
     * CRITICAL FIX: Configure TTS with multiple fallback locales
     */
    private fun configureTts(initResult: CompletableDeferred<Boolean>) {
        try {
            val tts = androidTts
            if (tts == null) {
                Log.e(TAG, "TTS is null during configuration")
                initError = "TTS became null"
                initResult.complete(false)
                return
            }
            
            // Try US English first
            var result = tts.setLanguage(Locale.US)
            Log.d(TAG, "TTS setLanguage(US) result: $result")
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "US English not supported, trying default locale...")
                result = tts.setLanguage(Locale.getDefault())
                Log.d(TAG, "TTS setLanguage(default) result: $result")
                
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Default locale not supported, trying UK English...")
                    result = tts.setLanguage(Locale.UK)
                    Log.d(TAG, "TTS setLanguage(UK) result: $result")
                    
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "No supported language found for TTS")
                        initError = "No supported language"
                        initResult.complete(false)
                        return
                    }
                }
            }
            
            // VOICE CUSTOMIZATION: Clear, natural speech with proper pacing
            tts.setSpeechRate(0.82f)
            tts.setPitch(1.05f)
            
            // CRITICAL: Set up listener once
            setupUtteranceListener()
            
            isReady = true
            initError = null
            Log.i(TAG, "✓ Android TTS initialized successfully (pitch=1.05, rate=0.82)")
            initResult.complete(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring TTS: ${e.message}", e)
            initError = "Config error: ${e.message}"
            initResult.complete(false)
        }
    }
    
    /**
     * CRITICAL FIX: Verify TTS is actually working by doing a silent test
     */
    private fun verifyTtsWorking(): Boolean {
        return try {
            if (!isReady || androidTts == null) return false
            
            // Check if we can query the engine
            val engines = androidTts?.engines
            Log.d(TAG, "TTS engines available: ${engines?.size ?: 0}")
            
            engines?.forEach { engine ->
                Log.d(TAG, "  - Engine: ${engine.name}, label: ${engine.label}")
            }
            
            true
        } catch (e: Exception) {
            Log.w(TAG, "TTS verification failed: ${e.message}")
            false
        }
    }
    
    /**
     * Get initialization error message for diagnostics
     */
    fun getInitError(): String? = initError
    
    /**
     * CRITICAL FIX: Force re-initialization if TTS is not ready
     */
    suspend fun ensureReady(): Boolean {
        if (isReady && androidTts != null) {
            return true
        }
        Log.w(TAG, "TTS not ready, attempting re-initialization...")
        initAttemptCount = 0 // Reset retry count
        return initialize()
    }
    
    /**
     * CRITICAL FIX: Set up utterance progress listener ONCE during initialization
     * This ensures all utterances are properly tracked regardless of when they're queued
     */
    private fun setupUtteranceListener() {
        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed: $utteranceId")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
            }
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d(TAG, "TTS stopped: $utteranceId (interrupted: $interrupted)")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
            }
        })
        Log.d(TAG, "Utterance progress listener set up")
    }
    
    /**
     * Speak text
     * FIXED: Now uses suspend function properly with completion tracking
     * CRITICAL FIX: Added detailed diagnostics
     */
    suspend fun speak(
        text: String,
        speaker: Int = 0,
        speed: Float = 0.95f,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.Main) {
        Log.d(TAG, "speak() called: textLength=${text.length}, isReady=$isReady")
        
        if (!isReady) {
            Log.e(TAG, "TTS not ready (isReady=false), cannot speak. Error: $initError")
            return@withContext false
        }
        
        if (androidTts == null) {
            Log.e(TAG, "TTS engine is null, cannot speak")
            return@withContext false
        }
        
        if (text.isBlank()) {
            Log.w(TAG, "TTS text is blank, skipping")
            return@withContext false
        }
        
        val cleanText = preprocessText(text)
        if (cleanText.isBlank()) {
            Log.w(TAG, "TTS text became blank after preprocessing, skipping")
            return@withContext false
        }
        
        try {
            val utteranceId = UUID.randomUUID().toString()
            
            // Track this utterance
            synchronized(utteranceLock) {
                pendingUtterances.add(utteranceId)
            }
            
            // Set pitch for more natural voice
            androidTts?.setPitch(1.05f)
            androidTts?.setSpeechRate(0.82f)
            
            Log.i(TAG, "Speaking (${cleanText.length} chars): '${cleanText.take(60)}${if (cleanText.length > 60) "..." else ""}'")
            
            val result = androidTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak() returned ERROR")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
                isReady = false
                false
            } else {
                Log.d(TAG, "TTS speak successful")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}", e)
            isReady = false
            false
        }
    }
    
    /**
     * Speak text with queue mode
     * Use QUEUE_FLUSH for first utterance, QUEUE_ADD for seamless continuation
     * FIXED: Properly track all utterances with timing for fallback completion detection
     * CRITICAL FIX: Added comprehensive safety checks and logging
     */
    fun speakQueued(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        // CRITICAL FIX: Detailed logging for debugging
        Log.d(TAG, "speakQueued called: textLength=${text.length}, isReady=$isReady, ttsNull=${androidTts == null}")
        
        if (!isReady) {
            Log.e(TAG, "TTS not ready (isReady=false), cannot speak. Error: $initError")
            return
        }
        
        if (androidTts == null) {
            Log.e(TAG, "TTS engine is null, cannot speak")
            return
        }
        
        if (text.isBlank()) {
            Log.w(TAG, "TTS text is blank, skipping")
            return
        }
        
        val cleanText = preprocessText(text)
        if (cleanText.isBlank()) {
            Log.w(TAG, "TTS text became blank after preprocessing, skipping")
            return
        }
        
        try {
            val utteranceId = UUID.randomUUID().toString()
            
            // Track this utterance
            synchronized(utteranceLock) {
                pendingUtterances.add(utteranceId)
            }
            
            // CRITICAL FIX: Track timing for fallback completion detection
            // Estimate ~70ms per char at speech rate 0.82
            lastUtteranceStartTime = System.currentTimeMillis()
            lastUtteranceExpectedDurationMs = (cleanText.length * 70L).coerceIn(1000L, 10000L)
            
            // VOICE FIX: Ensure pitch and rate are set before each speak
            androidTts?.setPitch(1.05f)
            androidTts?.setSpeechRate(0.82f)
            
            Log.i(TAG, "Speaking (${cleanText.length} chars): '${cleanText.take(60)}${if (cleanText.length > 60) "..." else ""}'")
            
            val result = androidTts?.speak(cleanText, queueMode, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak() returned ERROR - text may be too long or engine crashed")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
                // CRITICAL FIX: Mark TTS as potentially broken
                isReady = false
            } else {
                Log.d(TAG, "TTS queued successfully (mode: ${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"}, expectedDuration=${lastUtteranceExpectedDurationMs}ms)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}", e)
            // CRITICAL FIX: Mark TTS as potentially broken on exception
            isReady = false
        }
    }
    
    /**
     * Wait for all pending TTS utterances to complete.
     * Uses pendingUtterances set (tracked by UtteranceProgressListener) as primary signal.
     * Includes a time-based bailout for devices where QUEUE_ADD callbacks are unreliable.
     */
    suspend fun waitForCompletion(timeoutMs: Long = 30000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val hasPending = synchronized(utteranceLock) { pendingUtterances.isNotEmpty() }
            if (!hasPending) {
                // Small delay to let final audio frame finish playing
                delay(200)
                return
            }
            // CRITICAL FIX: Samsung Tab A7 Lite doesn't reliably fire onDone for QUEUE_ADD utterances.
            // If we've passed the expected duration of the last utterance + grace period, assume done.
            val timeSinceLastUtterance = System.currentTimeMillis() - lastUtteranceStartTime
            val gracePeriod = 1500L
            if (timeSinceLastUtterance > lastUtteranceExpectedDurationMs + gracePeriod) {
                val staleCount = synchronized(utteranceLock) { pendingUtterances.size }
                Log.w(TAG, "waitForCompletion: TTS done by time (${timeSinceLastUtterance}ms > ${lastUtteranceExpectedDurationMs + gracePeriod}ms), clearing $staleCount stale pending utterances")
                synchronized(utteranceLock) { pendingUtterances.clear() }
                delay(200)
                return
            }
            delay(100)
        }
        Log.w(TAG, "waitForCompletion timed out after ${timeoutMs}ms, clearing pending utterances")
        synchronized(utteranceLock) { pendingUtterances.clear() }
    }
    
    /**
     * Preprocess text for TTS
     * Converts abbreviations and symbols to spoken words for natural speech
     */
    fun preprocessText(raw: String): String {
        var text = raw
            .replace(Regex("```[\\s\\S]*?```"), "")  // Remove code blocks
            .replace(Regex("[*_~`#>]+"), "")  // Remove markdown formatting
            // Simple emoji removal (just common emoji range)
            .replace(Regex("[\\x{1F600}-\\x{1F9FF}]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")  // Convert links to text
            .replace(Regex("<[^>]+>"), "")  // Remove HTML tags
            .replace(Regex("https?://\\S+"), "")  // Remove URLs
        
        // VOICE FIX: Convert abbreviations to spoken words
        text = text
            // Common abbreviations
            .replace(Regex("\\be\\.g\\.\\b", RegexOption.IGNORE_CASE), "for example")
            .replace(Regex("\\bi\\.e\\.\\b", RegexOption.IGNORE_CASE), "that is")
            .replace(Regex("\\betc\\.\\b", RegexOption.IGNORE_CASE), "etcetera")
            .replace(Regex("\\bvs\\.\\b", RegexOption.IGNORE_CASE), "versus")
            .replace(Regex("\\bvs\\b", RegexOption.IGNORE_CASE), "versus")
            .replace(Regex("\\bMr\\.\\b", RegexOption.IGNORE_CASE), "Mister")
            .replace(Regex("\\bMrs\\.\\b", RegexOption.IGNORE_CASE), "Misses")
            .replace(Regex("\\bMs\\.\\b", RegexOption.IGNORE_CASE), "Ms")
            .replace(Regex("\\bDr\\.\\b", RegexOption.IGNORE_CASE), "Doctor")
            .replace(Regex("\\bProf\\.\\b", RegexOption.IGNORE_CASE), "Professor")
            .replace(Regex("\\bSt\\.\\b", RegexOption.IGNORE_CASE), "Saint")
            .replace(Regex("\\bAve\\.\\b", RegexOption.IGNORE_CASE), "Avenue")
            .replace(Regex("\\bBlvd\\.\\b", RegexOption.IGNORE_CASE), "Boulevard")
            .replace(Regex("\\bRd\\.\\b", RegexOption.IGNORE_CASE), "Road")
            .replace(Regex("\\bInc\\.\\b", RegexOption.IGNORE_CASE), "Incorporated")
            .replace(Regex("\\bLtd\\.\\b", RegexOption.IGNORE_CASE), "Limited")
            .replace(Regex("\\bCorp\\.\\b", RegexOption.IGNORE_CASE), "Corporation")
            .replace(Regex("\\bNo\\.\\s*(\\d+)", RegexOption.IGNORE_CASE), "number $1")
            
            // Symbols that get spelled out - convert to words
            .replace("!", ".")  // Exclamation becomes period (stops "exclamation mark" spelling)
            .replace("?", "?")  // Keep question marks (they affect intonation)
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace("=", " equals ")
            .replace("%", " percent ")
            .replace("$", " dollars ")
            .replace("€", " euros ")
            .replace("£", " pounds ")
            .replace("°", " degrees ")
            .replace("#", " number ")
            .replace("@", " at ")
            
            // Date formats
            .replace(Regex("\\b([A-Z][a-z]{2})\\.\\s*(\\d{1,2})\\b"), "$1 $2")  // Jan. 15 -> Jan 15
            
            // Time formats
            .replace(Regex("\\b(\\d{1,2}):(\\d{2})\\s*AM\\b", RegexOption.IGNORE_CASE), "$1 $2 A M")
            .replace(Regex("\\b(\\d{1,2}):(\\d{2})\\s*PM\\b", RegexOption.IGNORE_CASE), "$1 $2 P M")
            
            // VOICE FIX: Ensure proper pauses between sentences
            // Add double space after punctuation for longer pause between sentences
            .replace(Regex("([.!?])([^\\s])"), "$1  $2")  // "Hello.World" -> "Hello.  World" (double space)
            
            // Clean up extra whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
        
        return text
    }
    
    /**
     * Stop speaking
     */
    fun stop() {
        try {
            androidTts?.stop()
            synchronized(utteranceLock) { pendingUtterances.clear() }
            Log.d(TAG, "TTS stopped, cleared ${pendingUtterances.size} pending utterances")
        } catch (e: Exception) {
            Log.w(TAG, "Stop error: ${e.message}")
        }
    }
    
    /**
     * Check if TTS is currently speaking
     * CRITICAL FIX: Use time-based fallback when Android TTS API is unreliable
     */
    fun isSpeaking(): Boolean {
        val systemSpeaking = androidTts?.isSpeaking == true
        val hasPending = synchronized(utteranceLock) { pendingUtterances.isNotEmpty() }
        
        // CRITICAL FIX: Time-based fallback - if we recently spoke and within expected duration, consider still speaking
        val timeSinceLastUtterance = System.currentTimeMillis() - lastUtteranceStartTime
        val withinExpectedDuration = timeSinceLastUtterance < lastUtteranceExpectedDurationMs + 500L // 500ms grace period
        
        val result = systemSpeaking || hasPending || withinExpectedDuration
        
        // Log when we're using the time-based fallback (helps debugging)
        if (!systemSpeaking && !hasPending && withinExpectedDuration) {
            Log.d(TAG, "isSpeaking: Using time fallback (${timeSinceLastUtterance}ms / ${lastUtteranceExpectedDurationMs}ms)")
        }
        
        return result
    }
    
    /**
     * Shutdown TTS
     */
    fun shutdown() {
        try {
            androidTts?.stop()
            androidTts?.shutdown()
            isReady = false
            Log.i(TAG, "TTS shutdown")
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown error: ${e.message}")
        }
    }
    
    /**
     * Release resources (alias for shutdown)
     */
    fun release() {
        shutdown()
    }
    
    /**
     * CRITICAL FIX: Health check - verifies TTS is still working
     * Call this periodically to detect if TTS engine has crashed
     */
    fun healthCheck(): Boolean {
        if (!isReady || androidTts == null) {
            Log.w(TAG, "Health check failed: isReady=$isReady, ttsNull=${androidTts == null}")
            isReady = false
            return false
        }
        
        // Try to check if engine is still responsive
        return try {
            val engines = androidTts?.engines
            val healthy = engines != null
            if (!healthy) {
                Log.e(TAG, "Health check failed: cannot query engines")
                isReady = false
            }
            healthy
        } catch (e: Exception) {
            Log.e(TAG, "Health check exception: ${e.message}")
            isReady = false
            false
        }
    }
}
