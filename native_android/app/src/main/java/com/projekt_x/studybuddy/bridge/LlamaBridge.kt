package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader

/**
 * JNI-compatible callback interface for token streaming.
 * This interface is used by native code to stream tokens back to Kotlin.
 * 
 * IMPORTANT: This interface must match the JNI expectations in ai_chat_jni.cpp:
 * - onToken(String): Called for each generated token
 * - onComplete(): Called when generation is complete
 * - onError(String): Called when an error occurs
 */
interface GenerationCallback {
    fun onToken(token: String)
    fun onComplete()
    fun onError(error: String)
}

/**
 * Bridge for LLM (Large Language Model) operations
 * 
 * Extends BaseBridge to provide LLM-specific functionality:
 * - Model loading/unloading
 * - Text generation with streaming
 * - Context management
 * - Device capability detection
 * 
 * Usage:
 * ```
 * val llmBridge = LlamaBridge(context)
 * if (llmBridge.init()) {
 *     llmBridge.loadModel("/path/to/model.gguf", config)
 *     llmBridge.generate("Hello", callback)
 * }
 * ```
 */
class LlamaBridge(private val context: Context) : BaseBridge() {

    companion object {
        private const val TAG = "LlamaBridge"
        private const val MODEL_SIZE_MB = 650L // TinyLlama size
        
        // Default system prompt (fallback)
        // BUG FIX 3: Identity prompt - Nilo knows its name
        const val DEFAULT_SYSTEM_PROMPT = """You are Nilo, a friendly personal AI assistant built into the Hey-Nilo app.
Your name is Nilo. Always refer to yourself as Nilo.
You are helpful, concise, and conversational.
Keep responses short and natural — they will be spoken aloud via text-to-speech."""
        
        // TinyLlama chat template tokens
        const val SYSTEM_START = "<|system|>\n"
        const val SYSTEM_END = "</s>"
        const val USER_START = "<|user|>\n"
        const val USER_END = "</s>"
        const val ASSISTANT_START = "<|assistant|>\n"
        
        // Native library loading
        init {
            try {
                System.loadLibrary("ai-chat")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Current generation job (for cancellation)
    private var currentGenerationJob: Job? = null

    // Model configuration
    private var currentConfig: BridgeConfig? = null
    private var modelPath: String? = null
    private var currentSystemPrompt: String = DEFAULT_SYSTEM_PROMPT

    // ========================================================================
    // BaseBridge Implementation
    // ========================================================================

    override fun getBridgeName(): String = "LlamaBridge"

    override fun init(): Boolean {
        if (currentState == State.READY) {
            Log.w(TAG, "Already initialized")
            return true
        }

        updateState(State.INITIALIZING)
        
        return try {
            // Initialize native backend
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            nativeInit(nativeLibDir)
            
            Log.i(TAG, "Native backend initialized")
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
                // Cancel any ongoing generation
                currentGenerationJob?.cancel()
                currentGenerationJob = null
                
                // Release native resources
                nativeRelease()
                
                modelPath = null
                currentConfig = null
                
                Log.i(TAG, "Resources released")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during release: ${e.message}")
                false
            }
        }
    }

    override fun isLoaded(): Boolean {
        return modelPath != null && nativeIsModelLoaded()
    }

    // ========================================================================
    // LLM-Specific Methods
    // ========================================================================

    /**
     * Load a GGUF model file
     * 
     * @param path Path to .gguf model file
     * @param config Bridge configuration
     * @return true if loaded successfully
     */
    fun loadModel(path: String, config: BridgeConfig = detectDeviceConfig()): Boolean {
        // Only check state, not isLoaded() - we need to load the model!
        if (currentState != State.READY) {
            val msg = "Cannot load model: bridge not ready (state: $currentState)"
            setError(msg)
            return false
        }
        
        updateState(State.LOADING)
        
        return try {
            Log.i(TAG, "Loading model from: $path")
            Log.i(TAG, "Config: threads=${config.threads}, context=${config.contextSize}, " +
                    "mmap=${config.useMmap}")
            
            val success = nativeLoadModel(
                path,
                config.threads,
                config.contextSize,
                config.batchSize,
                config.useMmap,
                config.memoryPressure
            )
            
            if (success) {
                modelPath = path
                currentConfig = config
                updateState(State.READY)
                Log.i(TAG, "Model loaded successfully")
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
     * Unload the current model
     */
    fun unloadModel(): Boolean {
        return try {
            nativeUnloadModel()
            modelPath = null
            currentConfig = null
            Log.i(TAG, "Model unloaded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model: ${e.message}")
            false
        }
    }

    /**
     * Generate text with streaming callback
     * 
     * @param prompt Input prompt
     * @param callback Streaming callback for tokens
     */
    fun generate(prompt: String, callback: StreamingCallback) {
        if (!assertReady("generate")) {
            callback.onError("Bridge not ready: ${lastError ?: "unknown error"}")
            return
        }

        updateState(State.BUSY)
        
        currentGenerationJob = scope.launch {
            try {
                val fullResponse = StringBuilder()
                var errorOccurred = false
                
                // Create a native callback that implements the JNI interface
                val nativeCallback = object : GenerationCallback {
                    override fun onToken(token: String) {
                        if (isActive) {
                            fullResponse.append(token)
                            callback.onToken(token)
                        }
                    }
                    
                    override fun onComplete() {
                        // Called when generation is complete
                    }
                    
                    override fun onError(error: String) {
                        errorOccurred = true
                        callback.onError(error)
                    }
                }
                
                nativeGenerate(prompt, nativeCallback)
                
                if (isActive && !errorOccurred) {
                    callback.onComplete(fullResponse.toString())
                }
            } catch (e: Exception) {
                if (isActive) {
                    callback.onError("Generation error: ${e.message}")
                }
            } finally {
                updateState(State.READY)
            }
        }
    }

    /**
     * Stop current generation
     */
    fun stopGeneration() {
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        nativeStopGeneration()
        updateState(State.READY)
        Log.i(TAG, "Generation stopped")
    }

    /**
     * Set system prompt/persona
     * 
     * @param prompt The system prompt text
     * @param includeMemory Whether to include memory context (if available)
     * @return true if successful
     */
    @JvmOverloads
    fun setSystemPrompt(prompt: String, includeMemory: Boolean = false): Boolean {
        return try {
            currentSystemPrompt = prompt
            nativeSetSystemPrompt(prompt)
            Log.i(TAG, "System prompt set (includeMemory=$includeMemory)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting system prompt: ${e.message}")
            false
        }
    }
    
    /**
     * Get current system prompt
     */
    fun getSystemPrompt(): String = currentSystemPrompt
    
    /**
     * Generate text with system prompt prepended
     * 
     * This method formats the prompt using TinyLlama chat template:
     * <|system|>{systemPrompt}</s>
     * <|user|>{userMessage}</s>
     * <|assistant|>
     * 
     * @param userMessage The user's message
     * @param systemPrompt Optional system prompt (uses current if null)
     * @param memoryContext Optional memory context to inject
     * @param callback Streaming callback for tokens
     */
    fun generateWithSystemPrompt(
        userMessage: String,
        systemPrompt: String? = null,
        memoryContext: String? = null,
        callback: StreamingCallback
    ) {
        val fullPrompt = buildTinyLlamaPrompt(
            userMessage = userMessage,
            systemPrompt = systemPrompt ?: currentSystemPrompt,
            memoryContext = memoryContext
        )
        generate(fullPrompt, callback)
    }
    
    /**
     * Build TinyLlama chat template formatted prompt
     * 
     * Format:
     * <|system|>
     * {systemPrompt}
     * [Memory Context]
     * </s>
     * <|user|>
     * {userMessage}</s>
     * <|assistant|>
     */
    fun buildTinyLlamaPrompt(
        userMessage: String,
        systemPrompt: String = currentSystemPrompt,
        memoryContext: String? = null
    ): String {
        val sb = StringBuilder()
        
        // System section
        sb.append(SYSTEM_START)
        sb.append(systemPrompt)
        
        // Add memory context if provided
        memoryContext?.let {
            sb.append("\n\n")
            sb.append(it)
        }
        
        sb.append(SYSTEM_END)
        sb.append("\n")
        
        // User section
        sb.append(USER_START)
        sb.append(userMessage)
        sb.append(USER_END)
        sb.append("\n")
        
        // Assistant prefix (model will complete this)
        sb.append(ASSISTANT_START)
        
        return sb.toString()
    }

    /**
     * Clear conversation context
     */
    fun clearContext(): Boolean {
        return try {
            nativeClearContext()
            Log.i(TAG, "Context cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing context: ${e.message}")
            false
        }
    }

    /**
     * Get current context size (number of tokens)
     */
    fun getContextSize(): Int {
        return try {
            nativeGetContextSize()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting context size: ${e.message}")
            0
        }
    }

    // ========================================================================
    // Device Configuration
    // ========================================================================

    /**
     * Detect device capabilities and return optimal config
     */
    fun detectDeviceConfig(): BridgeConfig {
        val batteryInfo = getBatteryInfo()
        val isCharging = batteryInfo.isCharging
        val availableRamMB = getSystemAvailableRamMB()
        val totalRamMB = getSystemTotalRamMB()
        val cpuTemp = getCurrentTemperature()
        
        // Calculate memory pressure
        val memoryPressure = calculateMemoryPressure(availableRamMB, totalRamMB)
        
        // Get optimal thread count from native layer
        val optimalThreads = getOptimalThreadCount()
        
        // Apply battery/temperature adjustments
        val finalThreads = when {
            memoryPressure > 0.8f -> (optimalThreads * 0.7).toInt().coerceAtLeast(2)
            batteryInfo.level < 15 && !isCharging -> (optimalThreads * 0.7).toInt().coerceAtLeast(2)
            cpuTemp > 60f -> (optimalThreads * 0.8).toInt().coerceAtLeast(2)
            isCharging -> optimalThreads
            else -> optimalThreads
        }
        
        // Determine context size based on RAM
        val safeContextSize = when {
            availableRamMB < 2048 -> 512
            availableRamMB < 3584 -> 1024
            else -> 2048
        }
        
        // Batch size based on memory pressure
        val batchSize = when {
            memoryPressure > 0.7f -> 256
            else -> 512
        }
        
        // Use mmap for low RAM devices
        val useMmap = availableRamMB < 2500
        
        Log.i(TAG, "Detected config: threads=$finalThreads, context=$safeContextSize, " +
                "mmap=$useMmap, pressure=${(memoryPressure * 100).toInt()}%")
        
        return BridgeConfig(
            threads = finalThreads,
            useMmap = useMmap,
            contextSize = safeContextSize,
            batchSize = batchSize,
            memoryPressure = memoryPressure
        )
    }

    /**
     * Get current CPU temperature
     */
    fun getCurrentTemperature(): Float {
        return try {
            nativeGetCurrentTemperature()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get temperature: ${e.message}")
            0f
        }
    }

    /**
     * Get optimal thread count from native layer
     */
    fun getOptimalThreadCount(): Int {
        return try {
            nativeGetOptimalThreadCount()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get optimal threads: ${e.message}")
            4 // Default
        }
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    override fun getMemoryUsage(): Long {
        return try {
            nativeGetMemoryUsage()
        } catch (e: Exception) {
            -1
        }
    }

    override fun getStatus(): BridgeStatus {
        val baseStatus = super.getStatus()
        return baseStatus.copy(
            memoryUsage = getMemoryUsage(),
            isLoaded = isLoaded()
        )
    }

    /**
     * Optimize memory (clear caches, request GC)
     */
    fun optimizeMemory(): Long {
        return try {
            val freed = nativeOptimizeMemory()
            System.gc()
            Log.i(TAG, "Memory optimized, freed: ${freed / 1024}KB")
            freed
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing memory: ${e.message}")
            0
        }
    }

    // ========================================================================
    // Backward Compatibility Methods (for migration from old architecture)
    // ========================================================================

    /**
     * Stop generation (alias for stopGeneration)
     * Backward compatible with old InferenceQueue
     */
    fun stop() {
        stopGeneration()
    }

    /**
     * Get current memory usage in MB
     * Backward compatible with old code
     */
    fun getCurrentMemoryUsageMB(): Int {
        return (getMemoryUsage() / (1024 * 1024)).toInt()
    }

    /**
     * Add to history (stub for backward compatibility)
     * TODO: Implement proper conversation history
     */
    fun addToHistory(role: String, content: String) {
        Log.d(TAG, "Adding to history - role: $role, content: ${content.take(50)}...")
        // TODO: Implement conversation history management
    }

    /**
     * Generate with Flow-based API (backward compatible)
     * Wraps callback-based generate() in a Flow
     */
    fun generateStream(prompt: String, maxTokens: Int = 256): kotlinx.coroutines.flow.Flow<String> = 
        kotlinx.coroutines.flow.flow {
            val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            
            // Launch generation in a separate coroutine so it doesn't block the flow
            val generationJob = scope.launch {
                generate(prompt, object : StreamingCallback {
                    override fun onToken(token: String) {
                        channel.trySend(token)
                    }
                    override fun onComplete(fullText: String) {
                        channel.close()
                    }
                    override fun onError(error: String) {
                        channel.close()
                    }
                })
            }
            
            // Collect tokens as they arrive
            try {
                for (token in channel) {
                    emit(token)
                }
            } finally {
                generationJob.cancel()
            }
        }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private data class BatteryInfo(val level: Int, val isCharging: Boolean)

    private fun getBatteryInfo(): BatteryInfo {
        return try {
            val intent = context.registerReceiver(null, 
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo((level * 100 / scale), isCharging)
        } catch (e: Exception) {
            BatteryInfo(100, true)
        }
    }

    private fun getSystemAvailableRamMB(): Long {
        return try {
            val reader = BufferedReader(FileReader("/proc/meminfo"))
            var line: String?
            var memAvailableKB = 0L
            while (reader.readLine().also { line = it } != null) {
                if (line?.startsWith("MemAvailable:") == true) {
                    memAvailableKB = line?.replace(Regex("[^0-9]"), "")?.toLong() ?: 0
                    break
                }
            }
            reader.close()
            memAvailableKB / 1024
        } catch (e: Exception) {
            Log.w(TAG, "Could not read available RAM")
            2000 // Conservative default
        }
    }

    private fun getSystemTotalRamMB(): Long {
        return try {
            val reader = BufferedReader(FileReader("/proc/meminfo"))
            val line = reader.readLine()
            reader.close()
            val kb = line?.replace(Regex("[^0-9]"), "")?.toLong() ?: 0
            kb / 1024
        } catch (e: Exception) {
            Log.w(TAG, "Could not read total RAM")
            3000 // Conservative default
        }
    }

    private fun calculateMemoryPressure(availableMB: Long, totalMB: Long): Float {
        if (totalMB <= 0) return 0.0f
        val effectiveAvailable = availableMB - MODEL_SIZE_MB
        val effectiveTotal = totalMB - MODEL_SIZE_MB
        if (effectiveAvailable <= 0 || effectiveTotal <= 0) return 1.0f
        return 1.0f - (effectiveAvailable.toFloat() / effectiveTotal.toFloat())
    }

    // ========================================================================
    // Native Methods
    // ========================================================================

    private external fun nativeInit(nativeLibDir: String)
    private external fun nativeRelease()
    private external fun nativeIsModelLoaded(): Boolean
    private external fun nativeLoadModel(
        path: String,
        threads: Int,
        contextSize: Int,
        batchSize: Int,
        useMmap: Boolean,
        memoryPressure: Float
    ): Boolean
    private external fun nativeUnloadModel()
    private external fun nativeGenerate(prompt: String, callback: GenerationCallback)
    private external fun nativeStopGeneration()
    private external fun nativeSetSystemPrompt(prompt: String)
    private external fun nativeClearContext()
    private external fun nativeGetContextSize(): Int
    private external fun nativeGetOptimalThreadCount(): Int
    private external fun nativeGetCurrentTemperature(): Float
    private external fun nativeGetMemoryUsage(): Long
    private external fun nativeOptimizeMemory(): Long
}
