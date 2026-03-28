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
    
    // Track pending utterances for proper completion detection
    private val pendingUtterances = mutableSetOf<String>()
    private val utteranceLock = Object()
    
    /**
     * Initialize Android System TTS
     * FIXED: Properly waits for async callback completion
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        try {
            val initResult = CompletableDeferred<Boolean>()
            
            androidTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = androidTts?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || 
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "US English not supported, trying default")
                        // Try default locale as fallback
                        val defaultResult = androidTts?.setLanguage(Locale.getDefault())
                        if (defaultResult == TextToSpeech.LANG_MISSING_DATA ||
                            defaultResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.e(TAG, "Default language also not supported")
                            initResult.complete(false)
                        } else {
                            // VOICE CUSTOMIZATION: More natural, less robotic
                            androidTts?.setSpeechRate(0.95f)  // Slightly faster (was 0.9)
                            androidTts?.setPitch(1.1f)        // Higher pitch for warmer tone (was 1.0)
                            setupUtteranceListener()  // CRITICAL: Set up listener once
                            isReady = true
                            Log.i(TAG, "✓ Android TTS initialized with default locale (pitch=1.1, rate=0.95)")
                            initResult.complete(true)
                        }
                    } else {
                        // VOICE CUSTOMIZATION: More natural, less robotic
                        androidTts?.setSpeechRate(0.95f)  // Slightly faster (was 0.9)
                        androidTts?.setPitch(1.1f)        // Higher pitch for warmer tone (was 1.0)
                        setupUtteranceListener()  // CRITICAL: Set up listener once
                        isReady = true
                        Log.i(TAG, "✓ Android TTS initialized with US English (pitch=1.1, rate=0.95)")
                        initResult.complete(true)
                    }
                } else {
                    Log.e(TAG, "✗ Android TTS init failed: $status")
                    initResult.complete(false)
                }
            }
            
            // Wait for initialization with timeout
            val success = withTimeoutOrNull(10000) {
                initResult.await()
            } ?: false
            
            if (!success) {
                Log.e(TAG, "TTS initialization timed out or failed")
                isReady = false
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
            isReady = false
            false
        }
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
     */
    suspend fun speak(
        text: String,
        speaker: Int = 0,
        speed: Float = 0.95f,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.Main) {
        if (!isReady || androidTts == null) {
            Log.w(TAG, "TTS not ready, cannot speak")
            return@withContext false
        }
        
        if (text.isBlank()) return@withContext false
        
        val cleanText = preprocessText(text)
        if (cleanText.isBlank()) return@withContext false
        
        try {
            val utteranceId = UUID.randomUUID().toString()
            
            // Track this utterance
            synchronized(utteranceLock) {
                pendingUtterances.add(utteranceId)
            }
            
            // Set pitch for more natural voice
            androidTts?.setPitch(1.1f)
            androidTts?.setSpeechRate(speed)
            
            val result = androidTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak() returned ERROR")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
                false
            } else {
                Log.d(TAG, "TTS speaking: '${cleanText.take(50)}...'")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}")
            false
        }
    }
    
    /**
     * Speak text with queue mode
     * Use QUEUE_FLUSH for first utterance, QUEUE_ADD for seamless continuation
     * FIXED: Properly track all utterances with a single listener
     */
    fun speakQueued(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isReady || androidTts == null) {
            Log.w(TAG, "TTS not ready, cannot speak")
            return
        }
        
        if (text.isBlank()) return
        
        val cleanText = preprocessText(text)
        if (cleanText.isBlank()) return
        
        try {
            val utteranceId = UUID.randomUUID().toString()
            
            // Track this utterance
            synchronized(utteranceLock) {
                pendingUtterances.add(utteranceId)
            }
            
            // CRITICAL FIX: Listener is set up ONCE in initialize(), not here
            // Setting it here would replace the listener and break tracking for queued utterances
            
            val result = androidTts?.speak(cleanText, queueMode, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak() returned ERROR")
                synchronized(utteranceLock) {
                    pendingUtterances.remove(utteranceId)
                }
            } else {
                Log.d(TAG, "TTS queued: '${cleanText.take(50)}...' (mode: ${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"}, id: ${utteranceId.take(8)})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}")
        }
    }
    
    /**
     * Wait for all TTS to complete (check pending utterances)
     * FIXED: Also check isSpeaking as fallback
     */
    suspend fun waitForCompletion(timeoutMs: Long = 30000) {
        withTimeoutOrNull(timeoutMs) {
            var emptyCount = 0
            while (true) {
                val hasPending = synchronized(utteranceLock) { pendingUtterances.isNotEmpty() }
                val isSpeaking = androidTts?.isSpeaking == true
                
                Log.d(TAG, "waitForCompletion: pending=${pendingUtterances.size}, isSpeaking=$isSpeaking")
                
                if (!hasPending && !isSpeaking) {
                    emptyCount++
                    // Wait a bit more to make sure nothing new started
                    if (emptyCount >= 2) {  // Reduced from 3 to 2 for faster response
                        Log.d(TAG, "TTS complete - no pending utterances")
                        break
                    }
                } else {
                    emptyCount = 0
                }
                delay(100)  // Reduced from 150ms to 100ms for faster polling
            }
        }
        // Clear any stale pending utterances
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
     * Used to prevent barge-in from triggering on TTS feedback loop
     */
    fun isSpeaking(): Boolean {
        return androidTts?.isSpeaking == true || synchronized(utteranceLock) { pendingUtterances.isNotEmpty() }
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
}
