package com.projekt_x.studybuddy.bridge.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Online LLM Provider - wraps OpenAI-compatible APIs
 * 
 * Implements the LLMProvider interface for cloud-based inference.
 * Supports OpenAI, Claude, DeepSeek, and other OpenAI-compatible APIs.
 */
class OnlineLLMProvider(
    private val context: Context,
    override val provider: ApiProvider,
    private val config: ProviderConfig
) : LLMProvider {
    
    companion object {
        private const val TAG = "OnlineLLMProvider"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
    }
    
    override val displayName: String = when (provider) {
        ApiProvider.OPENAI -> "OpenAI GPT"
        ApiProvider.CLAUDE -> "Anthropic Claude"
        ApiProvider.DEEPSEEK -> "DeepSeek"
        ApiProvider.KIMI -> "Moonshot Kimi"
        ApiProvider.OPENROUTER -> "OpenRouter"
        ApiProvider.OFFLINE -> "Offline"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentGenerationJob: Job? = null
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private var isInitialized = false
    
    /**
     * Check if API key is configured
     */
    override fun isAvailable(): Boolean {
        return config.apiKey.isNotBlank() && isInitialized
    }
    
    /**
     * Get provider status
     */
    override suspend fun getStatus(): ProviderStatus {
        val hasApiKey = config.apiKey.isNotBlank()
        return ProviderStatus(
            provider = provider,
            isAvailable = hasApiKey && isInitialized,
            status = if (hasApiKey) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
            latencyMs = 0
        )
    }
    
    /**
     * Initialize the provider - validate API key format
     */
    override suspend fun initialize(): Boolean {
        isInitialized = config.apiKey.isNotBlank()
        if (isInitialized) {
            Log.i(TAG, "✓ $displayName initialized")
        } else {
            Log.w(TAG, "✗ $displayName not initialized - no API key")
        }
        return isInitialized
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
     */
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        if (!isAvailable()) {
            return LLMResponse(
                error = "API key not configured for $provider",
                isComplete = true,
                provider = provider
            )
        }
        
        val startTime = System.currentTimeMillis()
        
        return try {
            val response = makeApiRequest(request, stream = false)
            val latency = System.currentTimeMillis() - startTime
            
            response ?: LLMResponse(
                error = "Empty response from API",
                isComplete = true,
                provider = provider,
                latencyMs = latency
            )
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            LLMResponse(
                error = e.message ?: "API request failed",
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
            emit(LLMResponse(
                error = "API key not configured for $provider. Please add your API key in settings.",
                isComplete = true,
                provider = provider
            ))
            return@flow
        }
        
        val startTime = System.currentTimeMillis()
        val fullResponse = StringBuilder()
        
        try {
            when (provider) {
                ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.OPENROUTER -> {
                    emitOpenAICompatibleStream(request, startTime, fullResponse)
                }
                ApiProvider.CLAUDE, ApiProvider.KIMI -> {
                    emitClaudeStream(request, startTime, fullResponse)
                }
                ApiProvider.OFFLINE -> {
                    emit(LLMResponse(
                        error = "Offline provider should not use OnlineLLMProvider",
                        isComplete = true,
                        provider = provider
                    ))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed: ${e.javaClass.simpleName}: ${e.message}", e)
            e.printStackTrace()
            emit(LLMResponse(
                error = e.message ?: "Streaming error",
                isComplete = true,
                provider = provider,
                latencyMs = System.currentTimeMillis() - startTime
            ))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Emit OpenAI-compatible streaming response
     */
    private suspend fun FlowCollector<LLMResponse>.emitOpenAICompatibleStream(
        request: CompletionRequest,
        startTime: Long,
        fullResponse: StringBuilder
    ) {
        val url = if (config.baseUrl.isNotBlank()) {
            "${config.baseUrl}/chat/completions"
        } else {
            ProviderConfig.defaultBaseUrl(provider) + "/chat/completions"
        }
        
        val jsonBody = buildOpenAIBody(request, stream = true)
        
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val call = okHttpClient.newCall(httpRequest)
        
        val maskedKey = config.apiKey.take(15) + "..." + config.apiKey.takeLast(4)
        Log.d(TAG, "Making API request to: $url")
        Log.d(TAG, "API Key (masked): $maskedKey")
        Log.d(TAG, "Model: ${config.modelName}")
        Log.d(TAG, "Request body preview: ${jsonBody.take(200)}...")
        
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "API Error ${response.code}: $errorBody")
                
                // Provide user-friendly error messages for common errors
                val userFriendlyError = when (response.code) {
                    401 -> "Invalid API key. Please check your ${provider.name} API key in settings."
                    402 -> when (provider) {
                        ApiProvider.DEEPSEEK -> "Insufficient balance. Please top up your DeepSeek account."
                        ApiProvider.OPENROUTER -> "Model requires credits. Try a free model or add credits at openrouter.ai"
                        else -> "Insufficient balance. Please check your account."
                    }
                    404 -> when (provider) {
                        ApiProvider.OPENROUTER -> "Model not found. The model may be unavailable or renamed. Check openrouter.ai/models"
                        else -> "API endpoint not found."
                    }
                    429 -> "Rate limit exceeded. Please wait a moment and try again."
                    500, 502, 503, 504 -> "${provider.name} server error. Please try again later."
                    else -> "API Error ${response.code}: $errorBody"
                }
                
                emit(LLMResponse(
                    error = userFriendlyError,
                    isComplete = true,
                    provider = provider,
                    latencyMs = System.currentTimeMillis() - startTime
                ))
                return
            }
            
            response.body?.source()?.use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        
                        if (data == "[DONE]") {
                            val latency = System.currentTimeMillis() - startTime
                            emit(LLMResponse(
                                text = fullResponse.toString(),
                                isStreaming = false,
                                isComplete = true,
                                provider = provider,
                                modelName = config.modelName,
                                latencyMs = latency,
                                tokensUsed = estimateTokens(fullResponse.toString())
                            ))
                            break
                        }
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                
                                if (content.isNotEmpty()) {
                                    fullResponse.append(content)
                                    emit(LLMResponse(
                                        text = fullResponse.toString(),
                                        isStreaming = true,
                                        isComplete = false,
                                        provider = provider,
                                        modelName = config.modelName
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse SSE data: $data")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Emit Claude streaming response
     */
    private suspend fun FlowCollector<LLMResponse>.emitClaudeStream(
        request: CompletionRequest,
        startTime: Long,
        fullResponse: StringBuilder
    ) {
        val url = if (config.baseUrl.isNotBlank()) {
            "${config.baseUrl}/messages"
        } else {
            "https://api.anthropic.com/v1/messages"
        }
        
        val jsonBody = buildClaudeBody(request, stream = true)
        
        val httpRequestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        
        // Different auth headers for Claude vs Kimi (both use Anthropic format)
        if (provider == ApiProvider.CLAUDE) {
            httpRequestBuilder.addHeader("x-api-key", config.apiKey.trim())
            httpRequestBuilder.addHeader("anthropic-version", "2023-06-01")
        } else {
            // Kimi and others using Anthropic format
            httpRequestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        
        val httpRequest = httpRequestBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val call = okHttpClient.newCall(httpRequest)
        
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(LLMResponse(
                    error = "API Error ${response.code}: $errorBody",
                    isComplete = true,
                    provider = provider,
                    latencyMs = System.currentTimeMillis() - startTime
                ))
                return
            }
            
            response.body?.source()?.use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        
                        try {
                            val json = JSONObject(data)
                            val type = json.optString("type")
                            
                            when (type) {
                                "content_block_delta" -> {
                                    val delta = json.getJSONObject("delta")
                                    val text = delta.optString("text", "")
                                    
                                    if (text.isNotEmpty()) {
                                        fullResponse.append(text)
                                        emit(LLMResponse(
                                            text = fullResponse.toString(),
                                            isStreaming = true,
                                            isComplete = false,
                                            provider = provider,
                                            modelName = config.modelName
                                        ))
                                    }
                                }
                                "message_stop" -> {
                                    val latency = System.currentTimeMillis() - startTime
                                    emit(LLMResponse(
                                        text = fullResponse.toString(),
                                        isStreaming = false,
                                        isComplete = true,
                                        provider = provider,
                                        modelName = config.modelName,
                                        latencyMs = latency,
                                        tokensUsed = estimateTokens(fullResponse.toString())
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse SSE data: $data")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Make non-streaming API request
     */
    private suspend fun makeApiRequest(request: CompletionRequest, stream: Boolean): LLMResponse? {
        return when (provider) {
            ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.OPENROUTER -> {
                makeOpenAICompatibleRequest(request)
            }
            ApiProvider.CLAUDE, ApiProvider.KIMI -> {
                makeClaudeRequest(request)
            }
            ApiProvider.OFFLINE -> null
        }
    }
    
    /**
     * Make OpenAI-compatible API request
     */
    private suspend fun makeOpenAICompatibleRequest(request: CompletionRequest): LLMResponse? {
        val url = if (config.baseUrl.isNotBlank()) {
            "${config.baseUrl}/chat/completions"
        } else {
            ProviderConfig.defaultBaseUrl(provider) + "/chat/completions"
        }
        
        val jsonBody = buildOpenAIBody(request, stream = false)
        
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = okHttpClient.newCall(httpRequest).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            return LLMResponse(
                error = "API Error ${response.code}: $errorBody",
                isComplete = true,
                provider = provider
            )
        }
        
        val responseBody = response.body?.string() ?: return null
        val json = JSONObject(responseBody)
        
        val choices = json.getJSONArray("choices")
        val message = choices.getJSONObject(0).getJSONObject("message")
        val content = message.getString("content")
        val model = json.optString("model", config.modelName)
        
        val usage = json.optJSONObject("usage")
        val tokensUsed = usage?.optInt("total_tokens", 0) ?: 0
        
        return LLMResponse(
            text = content,
            isComplete = true,
            provider = provider,
            modelName = model,
            tokensUsed = tokensUsed
        )
    }
    
    /**
     * Make Claude API request
     */
    private suspend fun makeClaudeRequest(request: CompletionRequest): LLMResponse? {
        val url = if (config.baseUrl.isNotBlank()) {
            "${config.baseUrl}/messages"
        } else {
            "https://api.anthropic.com/v1/messages"
        }
        
        val jsonBody = buildClaudeBody(request, stream = false)
        
        val httpRequestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        
        // Different auth headers for Claude vs Kimi (both use Anthropic format)
        if (provider == ApiProvider.CLAUDE) {
            httpRequestBuilder.addHeader("x-api-key", config.apiKey.trim())
            httpRequestBuilder.addHeader("anthropic-version", "2023-06-01")
        } else {
            // Kimi and others using Anthropic format
            httpRequestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        
        val httpRequest = httpRequestBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = okHttpClient.newCall(httpRequest).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            return LLMResponse(
                error = "API Error ${response.code}: $errorBody",
                isComplete = true,
                provider = provider
            )
        }
        
        val responseBody = response.body?.string() ?: return null
        val json = JSONObject(responseBody)
        
        val content = json.getJSONArray("content")
        val text = content.getJSONObject(0).getString("text")
        val model = json.optString("model", config.modelName)
        
        val usage = json.optJSONObject("usage")
        val tokensUsed = usage?.optInt("input_tokens", 0)?.plus(
            usage.optInt("output_tokens", 0)
        ) ?: 0
        
        return LLMResponse(
            text = text,
            isComplete = true,
            provider = provider,
            modelName = model,
            tokensUsed = tokensUsed
        )
    }
    
    /**
     * Build OpenAI-compatible request body
     */
    private fun buildOpenAIBody(request: CompletionRequest, stream: Boolean): String {
        val messages = JSONArray()
        
        // Add system message with memory context
        val systemContent = buildString {
            request.systemPrompt?.let { append(it) }
            request.memoryContext?.let { context ->
                if (isNotEmpty()) append("\n\n")
                append(context)
            }
        }
        
        if (systemContent.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemContent)
            })
        }
        
        // Add conversation messages
        request.messages.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                })
                put("content", msg.content)
            })
        }
        
        return JSONObject().apply {
            put("model", config.modelName.ifBlank { ProviderConfig.defaultModel(provider) })
            put("messages", messages)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("top_p", request.topP)
            put("stream", stream)
        }.toString()
    }
    
    /**
     * Build Claude request body
     */
    private fun buildClaudeBody(request: CompletionRequest, stream: Boolean): String {
        val systemContent = buildString {
            request.systemPrompt?.let { append(it) }
            request.memoryContext?.let { context ->
                if (isNotEmpty()) append("\n\n")
                append(context)
            }
        }
        
        val messages = JSONArray()
        request.messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", msg.content)
                    })
                }
                MessageRole.ASSISTANT -> {
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", msg.content)
                    })
                }
                MessageRole.SYSTEM -> {} // Claude uses top-level system parameter
            }
        }
        
        return JSONObject().apply {
            put("model", config.modelName.ifBlank { ProviderConfig.defaultModel(provider) })
            if (systemContent.isNotBlank()) {
                put("system", systemContent)
            }
            put("messages", messages)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature.toDouble())
            put("top_p", request.topP.toDouble())
            put("stream", stream)
        }.toString()
    }
    
    /**
     * Get maximum context size
     */
    override fun getMaxContextSize(): Int {
        return when (provider) {
            ApiProvider.OPENAI -> 128000  // GPT-4o
            ApiProvider.CLAUDE -> 200000  // Claude 3
            ApiProvider.DEEPSEEK -> 64000
            ApiProvider.KIMI -> 8192
            ApiProvider.OPENROUTER -> 128000  // Varies by model
            ApiProvider.OFFLINE -> 2048
        }
    }
    
    /**
     * Estimate token count
     */
    override fun estimateTokens(text: String): Int {
        // Rough approximation: 4 chars per token for English
        return text.length / 4
    }
}
