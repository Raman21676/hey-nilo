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
        
        // TinyLlama chat template tokens
        private const val SYSTEM_START = "<|system|>\n"
        private const val SYSTEM_END = "</s>"
        private const val USER_START = "<|user|>\n"
        private const val USER_END = "</s>"
        private const val ASSISTANT_START = "<|assistant|>\n"
    }
    
    override val provider: ApiProvider = ApiProvider.OFFLINE
    override val displayName: String = "TinyLlama (Offline)"
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
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
        var hasError = false
        
        try {
            // Create a channel to collect tokens
            val channel = kotlinx.coroutines.channels.Channel<String>(Channel.UNLIMITED)
            
            // Start generation in background
            scope.launch(Dispatchers.IO) {
                try {
                    llamaBridge?.generate(prompt, object : StreamingCallback {
                        override fun onToken(token: String) {
                            channel.trySend(token)
                        }
                        
                        override fun onComplete(fullText: String) {
                            channel.close()
                        }
                        
                        override fun onError(errorMsg: String) {
                            hasError = true
                            channel.close()
                        }
                    })
                } catch (e: Exception) {
                    hasError = true
                    channel.close()
                }
            }
            
            // Emit tokens as they arrive
            for (token in channel) {
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
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(LLMResponse(
                error = e.message ?: "Streaming error",
                isComplete = true,
                provider = provider,
                latencyMs = System.currentTimeMillis() - startTime
            ))
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
     */
    private fun formatPrompt(request: CompletionRequest): String {
        val sb = StringBuilder()
        
        // System prompt
        val systemPrompt = buildString {
            // Base system prompt
            request.systemPrompt?.let { append(it) }
            
            // Add memory context if present
            request.memoryContext?.let { context ->
                if (isNotEmpty()) append("\n\n")
                append(context)
            }
        }
        
        if (systemPrompt.isNotBlank()) {
            sb.append(SYSTEM_START)
            sb.append(systemPrompt)
            sb.append(SYSTEM_END)
            sb.append("\n")
        }
        
        // Messages
        request.messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    sb.append(USER_START)
                    sb.append(message.content)
                    sb.append(USER_END)
                    sb.append("\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append(ASSISTANT_START)
                    sb.append(message.content)
                    sb.append(SYSTEM_END) // </s> for assistant too
                    sb.append("\n")
                }
                MessageRole.SYSTEM -> {
                    // Already handled above
                }
            }
        }
        
        // Start assistant response
        sb.append(ASSISTANT_START)
        
        return sb.toString()
    }
    
    /**
     * Estimate token count (rough approximation)
     */
    override fun estimateTokens(text: String): Int {
        // TinyLlama uses BPE tokenizer, roughly 4 chars per token
        return text.length / 4
    }
}


