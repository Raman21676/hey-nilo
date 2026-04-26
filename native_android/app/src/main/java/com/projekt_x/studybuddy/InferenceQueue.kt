package com.projekt_x.studybuddy

import android.util.Log
import com.projekt_x.studybuddy.bridge.LlamaBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Request queue for inference with thermal management.
 * Prevents device overheating and manages concurrent requests.
 */
class InferenceQueue private constructor(
    private val bridge: LlamaBridge
) {
    companion object {
        private const val TAG = "InferenceQueue"
        
        @Volatile
        private var instance: InferenceQueue? = null
        
        fun getInstance(bridge: LlamaBridge): InferenceQueue {
            return instance ?: synchronized(this) {
                instance ?: InferenceQueue(bridge).also {
                    instance = it
                }
            }
        }
    }
    
    enum class Priority {
        HIGH,   // User waiting
        NORMAL, // Standard chat
        LOW     // Background tasks
    }
    
    data class Request(
        val id: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val maxTokens: Int = 2048,
        val priority: Priority = Priority.NORMAL
    )
    
    data class Response(
        val requestId: String,
        val token: String? = null,
        val isComplete: Boolean = false,
        val error: String? = null
    )
    
    // Queue for pending requests
    private val requestQueue = ConcurrentLinkedQueue<Request>()
    
    // Flow for responses
    private val _responses = MutableSharedFlow<Response>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val responses: SharedFlow<Response> = _responses.asSharedFlow()
    
    // State
    private val isProcessing = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var currentJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Track if current request was cancelled by user
    private val wasCancelled = AtomicBoolean(false)
    
    // Thermal management (reduced for better performance)
    private var consecutiveRequests = 0
    private var lastRequestTime = 0L
    private val cooldownPeriodMs = 500L // 0.5 seconds cooldown
    
    init {
        // Start queue processor
        scope.launch {
            processQueue()
        }
    }
    
    /**
     * Add request to queue
     */
    fun enqueue(request: Request): Boolean {
        if (requestQueue.size >= 10) {
            Log.w(TAG, "Queue full, dropping request: ${request.id}")
            return false
        }
        
        // High priority goes to front (synchronized for atomicity)
        if (request.priority == Priority.HIGH) {
            synchronized(requestQueue) {
                val tempList = requestQueue.toList()
                requestQueue.clear()
                requestQueue.add(request)
                requestQueue.addAll(tempList)
            }
        } else {
            requestQueue.add(request)
        }
        
        Log.i(TAG, "Enqueued request: ${request.id}, queue size: ${requestQueue.size}")
        return true
    }
    
    /**
     * Cancel request by ID
     */
    fun cancel(requestId: String) {
        requestQueue.removeIf { it.id == requestId }
        if (currentJob != null && isProcessing.get()) {
            wasCancelled.set(true)
            bridge.stop()
        }
    }
    
    /**
     * Pause processing (e.g., thermal throttling)
     */
    fun pause() {
        isPaused.set(true)
        bridge.stop()
        Log.i(TAG, "Queue paused")
    }
    
    /**
     * Resume processing
     */
    fun resume() {
        isPaused.set(false)
        Log.i(TAG, "Queue resumed")
    }
    
    /**
     * Clear all pending requests
     */
    fun clear() {
        requestQueue.clear()
        bridge.stop()
        Log.i(TAG, "Queue cleared")
    }
    
    /**
     * Main queue processor
     */
    private suspend fun processQueue() {
        while (scope.isActive) {
            // DEBUG
            Log.d(TAG, "Queue poll - isPaused=${isPaused.get()}, isProcessing=${isProcessing.get()}, queueSize=${requestQueue.size}, consecutive=$consecutiveRequests")
            
            // Check thermal state
            if (isPaused.get()) {
                Log.d(TAG, "Queue paused, waiting...")
                delay(1000)
                continue
            }
            
            // Cooldown between requests
            val timeSinceLast = System.currentTimeMillis() - lastRequestTime
            if (consecutiveRequests >= 3 && timeSinceLast < cooldownPeriodMs) {
                val waitTime = cooldownPeriodMs - timeSinceLast
                Log.i(TAG, "Thermal cooldown: waiting ${waitTime}ms")
                delay(waitTime)
            }
            
            // Get next request
            val request = requestQueue.poll()
            if (request == null) {
                delay(100)
                continue
            }
            
            Log.i(TAG, "Queue got request: ${request.id}, isProcessing=${isProcessing.get()}")
            
            // Process request
            isProcessing.set(true)
            wasCancelled.set(false)
            consecutiveRequests++
            
            try {
                processRequest(request)
            } catch (e: Exception) {
                // CRITICAL FIX: Check if this was a user cancellation vs a real error
                if (e is CancellationException ||
                    e.message?.contains("cancelled", ignoreCase = true) == true ||
                    wasCancelled.get()) {
                    Log.i(TAG, "Request ${request.id} was cancelled by user, preserving partial response")
                    // For user cancellation, emit completion without error to preserve partial response
                    _responses.tryEmit(Response(
                        requestId = request.id,
                        isComplete = true
                    ))
                } else {
                    Log.e(TAG, "Error processing request: ${request.id}", e)
                    _responses.tryEmit(Response(
                        requestId = request.id,
                        error = e.message ?: "Unknown error"
                    ))
                }
            } finally {
                isProcessing.set(false)
                lastRequestTime = System.currentTimeMillis()
            }
            
            // Reset counter after cooldown period
            if (timeSinceLast > cooldownPeriodMs * 2) {
                consecutiveRequests = 0
            }
        }
    }
    
    private suspend fun processRequest(request: Request) {
        Log.i(TAG, "Processing request: ${request.id}")
        
        // Set system prompt if provided
        request.systemPrompt?.let { systemPrompt ->
            bridge.setSystemPrompt(systemPrompt)
        }
        
        // CRITICAL FIX: Add user message to history BEFORE generation.
        // The C++ layer includes history when building the prompt via llama_chat_apply_template.
        // Without this, the model sees only the current message and forgets everything.
        val filteredUserMessage = request.prompt
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("</s>", "")
            .trim()
        if (filteredUserMessage.isNotBlank()) {
            bridge.addToHistory("user", filteredUserMessage)
            Log.i(TAG, "Added user message to history: ${filteredUserMessage.take(50)}...")
        }
        
        // Collect full response for history
        val responseBuilder = StringBuilder()
        
        // CRITICAL FIX: Removed Kotlin withTimeout around JNI call.
        // The C++ layer already has a 240-second hard timeout and maxTokens limit.
        // Kotlin withTimeout was dangerous because it would throw in the coroutine
        // while the native thread kept running, deadlocking the llama mutex.
        bridge.generateStream(request.prompt, request.maxTokens)
            .catch { e ->
                // Don't emit error for cancellation - just rethrow to be handled by outer catch
                if (e is CancellationException) {
                    Log.i(TAG, "Stream cancelled for ${request.id}")
                    throw e
                }
                Log.e(TAG, "Error in generateStream for ${request.id}", e)
                _responses.emit(Response(
                    requestId = request.id,
                    error = e.message ?: "Stream error"
                ))
            }
            .collect { token ->
                responseBuilder.append(token)
                Log.d(TAG, "Emitting token for ${request.id}: '${token.take(20)}...'")
                _responses.emit(Response(
                    requestId = request.id,
                    token = token
                ))
            }
        
        // Add assistant response to history
        val fullResponse = responseBuilder.toString()
        Log.i(TAG, "Full response collected: ${fullResponse.length} chars")
        
        // CRITICAL FIX: Filter special tokens before saving to history
        val filteredResponse = fullResponse
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("</s>", "")
            .trim()
        
        if (filteredResponse.isNotBlank()) {
            bridge.addToHistory("assistant", filteredResponse)
            Log.i(TAG, "Added assistant response to history: ${filteredResponse.take(50)}...")
        } else {
            Log.w(TAG, "Empty response for ${request.id} (original: '${fullResponse.take(30)}')")
        }
        
        // Mark complete
        _responses.emit(Response(
            requestId = request.id,
            isComplete = true
        ))
        
        Log.i(TAG, "Request complete: ${request.id}")
    }
    
    /**
     * Check if currently processing
     */
    fun isBusy(): Boolean = isProcessing.get() || requestQueue.isNotEmpty()
    
    /**
     * Get queue size
     */
    fun queueSize(): Int = requestQueue.size
    
    /**
     * Cleanup
     */
    fun destroy() {
        clear()
        scope.cancel()
        instance = null
    }
}
