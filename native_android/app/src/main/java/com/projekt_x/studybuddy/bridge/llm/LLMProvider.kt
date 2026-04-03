package com.projekt_x.studybuddy.bridge.llm

import kotlinx.coroutines.flow.Flow

/**
 * Unified LLM Provider Interface
 * 
 * Abstracts the differences between offline (TinyLlama) and online (API) LLMs.
 * All providers implement this interface for seamless switching.
 * 
 * Usage:
 * ```
 * // Initialize provider
 * val provider: LLMProvider = OfflineLLMProvider(context, llamaBridge)
 * 
 * // Check availability
 * if (provider.isAvailable()) {
 *     // Stream response
 *     provider.stream(request).collect { response ->
 *         println(response.text)
 *     }
 * }
 * ```
 */
interface LLMProvider {
    
    /**
     * Provider identifier
     */
    val provider: ApiProvider
    
    /**
     * Display name for UI
     */
    val displayName: String
    
    /**
     * Check if this provider is available
     * - Offline: Model loaded
     * - Online: API key configured and connection OK
     */
    fun isAvailable(): Boolean
    
    /**
     * Get current provider status
     */
    suspend fun getStatus(): ProviderStatus
    
    /**
     * Complete a request (blocking, non-streaming)
     * 
     * @param request The completion request
     * @return Full LLM response
     */
    suspend fun complete(request: CompletionRequest): LLMResponse
    
    /**
     * Stream a request (returns flow of partial responses)
     * 
     * @param request The completion request
     * @return Flow of responses (partial + final)
     */
    fun stream(request: CompletionRequest): Flow<LLMResponse>
    
    /**
     * Quick completion with simple prompt
     * Convenience method for simple use cases
     * 
     * @param prompt User prompt
     * @param systemPrompt Optional system prompt
     * @return Full response text or null if error
     */
    suspend fun quickComplete(
        prompt: String,
        systemPrompt: String? = null,
        maxTokens: Int = 256
    ): String? {
        val request = CompletionRequest(
            messages = listOf(ChatMessage.user(prompt)),
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            stream = false
        )
        return try {
            complete(request).text
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Initialize the provider
     * - Offline: Load model if not loaded
     * - Online: Validate API key
     */
    suspend fun initialize(): Boolean
    
    /**
     * Cancel any ongoing generation immediately
     */
    fun cancelGeneration()
    
    /**
     * Release resources
     */
    fun release()
    
    /**
     * Get estimated token count for a message
     * Rough approximation (4 chars ≈ 1 token)
     */
    fun estimateTokens(text: String): Int {
        return text.length / 4
    }
    
    /**
     * Get maximum context size for this provider
     */
    fun getMaxContextSize(): Int
    
    /**
     * Check if streaming is supported
     */
    fun supportsStreaming(): Boolean = true
}

/**
 * Provider factory for creating LLM providers
 */
object LLMProviderFactory {
    
    /**
     * Create a provider based on app mode
     */
    fun createProvider(
        mode: AppMode,
        offlineProvider: LLMProvider? = null,
        config: ProviderConfig? = null
    ): LLMProvider? {
        return when (mode) {
            is AppMode.Offline -> offlineProvider
            is AppMode.HuggingFace -> offlineProvider
            is AppMode.Online -> {
                when (mode.provider) {
                    ApiProvider.OFFLINE -> offlineProvider
                    // Online providers will be implemented in Job 2.6+
                    else -> null
                }
            }
        }
    }
    
    /**
     * Get all available providers
     */
    fun getAllProviders(): List<ApiProvider> {
        return ApiProvider.entries.toList()
    }
    
    /**
     * Get default offline provider configuration
     */
    fun getOfflineConfig(): ProviderConfig {
        return ProviderConfig(
            provider = ApiProvider.OFFLINE,
            modelName = ProviderConfig.defaultModel(ApiProvider.OFFLINE),
            enabled = true
        )
    }
}

/**
 * Manager for switching between providers
 */
class LLMProviderManager {
    
    private val providers = mutableMapOf<ApiProvider, LLMProvider>()
    private var currentProvider: LLMProvider? = null
    private var currentMode: AppMode = AppMode.Offline
    
    /**
     * Register a provider
     */
    fun registerProvider(provider: LLMProvider) {
        providers[provider.provider] = provider
    }
    
    /**
     * Get registered provider
     */
    fun getProvider(provider: ApiProvider): LLMProvider? {
        return providers[provider]
    }
    
    /**
     * Set current active mode
     */
    fun setMode(mode: AppMode): Boolean {
        val provider = when (mode) {
            is AppMode.Offline -> providers[ApiProvider.OFFLINE]
            is AppMode.HuggingFace -> providers[ApiProvider.OFFLINE]
            is AppMode.Online -> providers[mode.provider]
        }
        
        return if (provider != null && provider.isAvailable()) {
            currentProvider = provider
            currentMode = mode
            true
        } else {
            false
        }
    }
    
    /**
     * Get current active provider
     */
    fun getCurrentProvider(): LLMProvider? = currentProvider
    
    /**
     * Get current mode
     */
    fun getCurrentMode(): AppMode = currentMode
    
    /**
     * Check if we can switch to a mode
     */
    fun canSwitchTo(mode: AppMode): Boolean {
        return when (mode) {
            is AppMode.Offline -> providers[ApiProvider.OFFLINE]?.isAvailable() == true
            is AppMode.HuggingFace -> providers[ApiProvider.OFFLINE]?.isAvailable() == true
            is AppMode.Online -> providers[mode.provider]?.isAvailable() == true
        }
    }
    
    /**
     * Get all available providers
     */
    fun getAvailableProviders(): List<LLMProvider> {
        return providers.values.filter { it.isAvailable() }
    }
    
    /**
     * Release all providers
     */
    fun releaseAll() {
        providers.values.forEach { it.release() }
        providers.clear()
        currentProvider = null
    }
}

/**
 * Exception for LLM provider errors
 */
class LLMProviderException(
    message: String,
    val provider: ApiProvider,
    val isRetryable: Boolean = false
) : Exception(message)

/**
 * Extension functions for easier usage
 */
suspend fun LLMProvider.completeSimple(
    userMessage: String,
    systemPrompt: String = "You are a helpful assistant.",
    memoryContext: String? = null
): String? {
    val request = CompletionRequest(
        messages = listOf(ChatMessage.user(userMessage)),
        systemPrompt = systemPrompt,
        memoryContext = memoryContext,
        maxTokens = 256,
        stream = false
    )
    
    return try {
        val response = complete(request)
        if (response.isSuccess) response.text else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Stream extension with simplified callback
 */
fun LLMProvider.streamSimple(
    userMessage: String,
    systemPrompt: String = "You are a helpful assistant.",
    memoryContext: String? = null,
    onToken: (String) -> Unit,
    onComplete: (String) -> Unit = {},
    onError: (String) -> Unit = {}
): Flow<LLMResponse> {
    val request = CompletionRequest(
        messages = listOf(ChatMessage.user(userMessage)),
        systemPrompt = systemPrompt,
        memoryContext = memoryContext,
        maxTokens = 256,
        stream = true
    )
    
    return stream(request)
}
