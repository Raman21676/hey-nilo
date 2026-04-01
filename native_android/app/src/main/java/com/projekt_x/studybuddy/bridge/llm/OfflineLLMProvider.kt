package com.projekt_x.studybuddy.bridge.llm

import android.content.Context
import android.util.Log
import com.projekt_x.studybuddy.bridge.LlamaBridge
import com.projekt_x.studybuddy.bridge.StreamingCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Offline LLM Provider - wraps TinyLlama via LlamaBridge
 * 
 * Implements the LLMProvider interface for local on-device inference.
 * Uses TinyLlama-1.1B-Chat Q4_0 quantized model (~608MB).
 * 
 * Chat Template:
 * ```
 * <|system|>
 * {systemPrompt}</s>
 * <|user|>
 * {userMessage}</s>
 * <|assistant|>
 * ```
 */
class OfflineLLMProvider(
    private val context: Context,
    private val llamaBridge: LlamaBridge? = null
) : LLMProvider {
    
    companion object {
        private const val TAG = "OfflineLLMProvider"
    }
    
    override val provider: ApiProvider = ApiProvider.OFFLINE
    override val displayName: String = "TinyLlama (Offline)"
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentGenerationJob: Job? = null
    
    /**
     * Check if model is loaded and ready
     */
    override fun isAvailable(): Boolean {
        return llamaBridge?.isLoaded() == true
    }
    
    /**
     * Get provider status
     */
    override suspend fun getStatus(): ProviderStatus {
        val isLoaded = llamaBridge?.isLoaded() == true
        return ProviderStatus(
            provider = provider,
            isAvailable = isLoaded,
            status = if (isLoaded) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
            latencyMs = 0
        )
    }
    
    /**
     * Initialize the provider
     * Note: Model loading is handled separately via LlamaBridge.loadModel()
     */
    override suspend fun initialize(): Boolean {
        return isAvailable()
    }
    
    /**
     * Release resources
     */
    override fun release() {
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        scope.cancel()
    }
    
    /**
     * Complete request (non-streaming)
     * Collects streaming tokens and returns full response
     */
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        if (!isAvailable()) {
            return LLMResponse(
                error = "Model not loaded",
                isComplete = true,
                provider = provider
            )
        }
        
        val startTime = System.currentTimeMillis()
        val prompt = formatPrompt(request)
        
        return try {
            val fullResponse = StringBuilder()
            var error: String? = null
            
            // Use callback-based generation
            val deferred = CompletableDeferred<String>()
            
            withContext(Dispatchers.IO) {
                llamaBridge?.generate(prompt, object : StreamingCallback {
                    override fun onToken(token: String) {
                        fullResponse.append(token)
                    }
                    
                    override fun onComplete(fullText: String) {
                        deferred.complete(fullText)
                    }
                    
                    override fun onError(errorMsg: String) {
                        error = errorMsg
                        deferred.complete("")
                    }
                })
            }
            
            // Wait for completion with timeout
            withTimeoutOrNull(60000) {
                deferred.await()
            }
            
            val latency = System.currentTimeMillis() - startTime
            
            if (error != null) {
                LLMResponse(
                    error = error,
                    isComplete = true,
                    provider = provider,
                    latencyMs = latency
                )
            } else {
                LLMResponse(
                    text = fullResponse.toString(),
                    isComplete = true,
                    provider = provider,
                    modelName = "tinyllama-1.1b-chat",
                    latencyMs = latency,
                    tokensUsed = estimateTokens(prompt) + estimateTokens(fullResponse.toString())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Completion failed", e)
            LLMResponse(
                error = e.message ?: "Unknown error",
                isComplete = true,
                provider = provider,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Stream request (returns flow of partial responses)
     */
    override fun stream(request: CompletionRequest): Flow<LLMResponse> = flow {
        if (!isAvailable()) {
            emit(LLMResponse(error = "Model not loaded", isComplete = true, provider = provider))
            return@flow
        }
        
        val startTime = System.currentTimeMillis()
        val prompt = formatPrompt(request)
        val fullResponse = StringBuilder()
        
        // Create channels for token and error communication
        val tokenChannel = Channel<String>(Channel.UNLIMITED)
        val errorChannel = Channel<String?>(1)
        
        try {
            // CRITICAL FIX: Cap maxTokens to ensure generation completes within C++ timeout on slow devices.
            // Samsung Tab A7 Lite generates ~2 tokens/sec, so 150 tokens = ~75s max. C++ timeout is 60s.
            val requestedMaxTokens = request.maxTokens ?: 256
            val maxTokens = requestedMaxTokens.coerceAtMost(120)
            val userQuery = request.messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
            
            // DIAGNOSTIC LOG - Verify maxTokens is reaching this point
            Log.d("NILO_DEBUG", "Query: $userQuery")
            Log.d("NILO_DEBUG", "MaxTokens being passed to LLM: $maxTokens (was $requestedMaxTokens)")
            Log.d(TAG, "Starting generation with maxTokens=$maxTokens (capped from $requestedMaxTokens)")
            
            // CRITICAL FIX: Ensure previous generation is fully stopped before starting a new one.
            // On slow devices, the old generation may still be holding the llama context lock.
            currentGenerationJob?.cancel()
            llamaBridge?.stopGeneration()
            withTimeoutOrNull(2000) { currentGenerationJob?.join() }
            
            // Start generation in background
            currentGenerationJob = scope.launch(Dispatchers.IO) {
                try {
                    // CRITICAL FIX: Use generateWithMaxTokens to respect the request's maxTokens
                    llamaBridge?.generateWithMaxTokens(prompt, maxTokens, object : StreamingCallback {
                        override fun onToken(token: String) {
                            tokenChannel.trySend(token)
                        }
                        
                        override fun onComplete(fullText: String) {
                            errorChannel.trySend(null)  // No error
                            tokenChannel.close()
                            errorChannel.close()
                        }
                        
                        override fun onError(errorMsg: String) {
                            errorChannel.trySend(errorMsg)
                            tokenChannel.close()
                            errorChannel.close()
                        }
                    })
                } catch (e: Exception) {
                    errorChannel.trySend(e.message ?: "Generation error")
                    tokenChannel.close()
                    errorChannel.close()
                }
            }
            
            // CRITICAL FIX: Add timeout around the entire streaming collection.
            // If native generation hangs or deadlocks, this prevents the coroutine from waiting forever.
            val streamSuccess = withTimeoutOrNull(75000) {
                // Wait for error or completion signal
                val error = errorChannel.receiveCatching().getOrNull()
                if (error != null) {
                    emit(LLMResponse(
                        error = error,
                        isComplete = true,
                        provider = provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    ))
                    return@withTimeoutOrNull true
                }
                
                // Emit tokens as they arrive
                for (token in tokenChannel) {
                    fullResponse.append(token)
                    emit(LLMResponse(
                        text = fullResponse.toString(),
                        isStreaming = true,
                        isComplete = false,
                        provider = provider,
                        modelName = "tinyllama-1.1b-chat"
                    ))
                }
                
                // Emit final response
                val latency = System.currentTimeMillis() - startTime
                emit(LLMResponse(
                    text = fullResponse.toString(),
                    isStreaming = false,
                    isComplete = true,
                    provider = provider,
                    modelName = "tinyllama-1.1b-chat",
                    latencyMs = latency,
                    tokensUsed = estimateTokens(prompt) + estimateTokens(fullResponse.toString())
                ))
                true
            }
            
            if (streamSuccess != true) {
                Log.w(TAG, "Stream timed out — native generation likely hung")
                currentGenerationJob?.cancel()
                tokenChannel.close()
                errorChannel.close()
                emit(LLMResponse(
                    text = fullResponse.toString(),
                    isStreaming = false,
                    isComplete = true,
                    provider = provider,
                    modelName = "tinyllama-1.1b-chat",
                    latencyMs = System.currentTimeMillis() - startTime,
                    error = "Generation timed out"
                ))
            }
            
        } catch (e: CancellationException) {
            // Normal cancellation, don't emit error
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(LLMResponse(
                error = e.message ?: "Streaming error",
                isComplete = true,
                provider = provider,
                latencyMs = System.currentTimeMillis() - startTime
            ))
        } finally {
            currentGenerationJob = null
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Get maximum context size for TinyLlama
     */
    override fun getMaxContextSize(): Int {
        // TinyLlama supports 2048 tokens, but we use 1024 for safety
        return 2048
    }
    
    /**
     * Format prompt using TinyLlama chat template
     * Delegates to LlamaBridge.buildTinyLlamaPrompt for consistency
     */
    private fun formatPrompt(request: CompletionRequest): String {
        // Build combined system prompt with memory context
        val systemPrompt = buildString {
            request.systemPrompt?.let { append(it) }
            request.memoryContext?.let { context ->
                if (isNotEmpty()) append("\n\n")
                append(context)
            }
        }
        
        // Get the last user message
        val lastUserMessage = request.messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content ?: ""
        
        // Use LlamaBridge's prompt builder if available, otherwise build manually
        return llamaBridge?.buildTinyLlamaPrompt(
            userMessage = lastUserMessage,
            systemPrompt = systemPrompt.ifBlank { LlamaBridge.DEFAULT_SYSTEM_PROMPT },
            memoryContext = null // Already included in systemPrompt
        ) ?: buildManualPrompt(systemPrompt, lastUserMessage)
    }
    
    /**
     * Manual prompt builder (fallback if LlamaBridge not available)
     */
    private fun buildManualPrompt(systemPrompt: String, userMessage: String): String {
        return buildString {
            append("<|system|>\n")
            append(systemPrompt.ifBlank { LlamaBridge.DEFAULT_SYSTEM_PROMPT })
            append("</s>\n")
            append("<|user|>\n")
            append(userMessage)
            append("</s>\n")
            append("<|assistant|>\n")
        }
    }
    
    /**
     * Estimate token count (rough approximation)
     */
    override fun estimateTokens(text: String): Int {
        // TinyLlama uses BPE tokenizer, roughly 4 chars per token
        return text.length / 4
    }
}


