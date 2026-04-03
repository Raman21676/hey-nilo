package com.projekt_x.studybuddy.bridge.llm

/**
 * Data models for LLM abstraction layer
 * 
 * These models are provider-agnostic and work with both
 * offline (TinyLlama) and online (OpenAI, Claude, etc.) LLMs.
 */

/**
 * Role of a message in the conversation
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}

/**
 * Single chat message
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun system(content: String) = ChatMessage(MessageRole.SYSTEM, content)
        fun user(content: String) = ChatMessage(MessageRole.USER, content)
        fun assistant(content: String) = ChatMessage(MessageRole.ASSISTANT, content)
    }
}

/**
 * LLM response with streaming support
 */
data class LLMResponse(
    val text: String = "",
    val isComplete: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val tokensUsed: Int = 0,
    val provider: ApiProvider = ApiProvider.OFFLINE,
    val modelName: String = "",
    val latencyMs: Long = 0
) {
    val isSuccess: Boolean get() = error == null && isComplete
    val isError: Boolean get() = error != null
}

/**
 * Request configuration for LLM completion
 */
data class CompletionRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val stopSequences: List<String> = emptyList(),
    val stream: Boolean = true,
    val systemPrompt: String? = null,
    val memoryContext: String? = null
) {
    /**
     * Build the full prompt with memory context
     */
    fun buildPrompt(): String {
        val sb = StringBuilder()
        
        // Add system prompt if provided
        systemPrompt?.let {
            sb.appendLine(it)
            sb.appendLine()
        }
        
        // Add memory context if provided
        memoryContext?.let {
            sb.appendLine(it)
            sb.appendLine()
        }
        
        // Add conversation messages
        messages.forEach { msg ->
            when (msg.role) {
                MessageRole.SYSTEM -> sb.appendLine("System: ${msg.content}")
                MessageRole.USER -> sb.appendLine("User: ${msg.content}")
                MessageRole.ASSISTANT -> sb.appendLine("Assistant: ${msg.content}")
            }
        }
        
        // Add assistant prefix for completion
        sb.append("Assistant: ")
        
        return sb.toString()
    }
    
    /**
     * Get only the last user message
     */
    fun getLastUserMessage(): String? {
        return messages.lastOrNull { it.role == MessageRole.USER }?.content
    }
}

/**
 * API format types
 */
enum class ApiFormat {
    OPENAI,     // OpenAI-compatible format
    ANTHROPIC   // Anthropic Claude format
}

/**
 * Supported API providers
 */
enum class ApiProvider {
    OFFLINE,        // TinyLlama via LlamaBridge
    OPENAI,         // OpenAI GPT-4/GPT-3.5
    CLAUDE,         // Anthropic Claude
    DEEPSEEK,       // DeepSeek
    KIMI,           // Moonshot AI Kimi (uses Anthropic format)
    OPENROUTER      // OpenRouter (multi-provider, OpenAI-compatible)
}

/**
 * App mode - offline or online
 */
sealed class AppMode {
    abstract val name: String
    abstract val displayName: String
    
    data object Offline : AppMode() {
        override val name = "offline"
        override val displayName = "Offline (TinyLlama)"
    }
    
    data object HuggingFace : AppMode() {
        override val name = "huggingface"
        override val displayName = "Hugging Face"
    }
    
    data class Online(val provider: ApiProvider) : AppMode() {
        override val name = "online_${provider.name.lowercase()}"
        override val displayName = "Online (${provider.name})"
    }
    
    companion object {
        fun fromString(mode: String): AppMode {
            return when {
                mode == "offline" -> Offline
                mode == "huggingface" -> HuggingFace
                mode.startsWith("online_") -> {
                    val providerName = mode.removePrefix("online_").uppercase()
                    val provider = ApiProvider.valueOf(providerName)
                    Online(provider)
                }
                else -> Offline
            }
        }
    }
}

/**
 * Provider configuration for API keys and settings
 */
data class ProviderConfig(
    val provider: ApiProvider,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val enabled: Boolean = false,
    val apiFormat: ApiFormat = ApiFormat.OPENAI
) {
    companion object {
        // Default model names for each provider
        fun defaultModel(provider: ApiProvider): String {
            return when (provider) {
                ApiProvider.OFFLINE -> "tinyllama-1.1b-chat"
                ApiProvider.OPENAI -> "gpt-4o-mini"
                ApiProvider.CLAUDE -> "claude-3-haiku-20240307"
                ApiProvider.DEEPSEEK -> "deepseek-chat"
                ApiProvider.KIMI -> "moonshot-v1-8k"
                ApiProvider.OPENROUTER -> "nvidia/llama-3.1-nemotron-70b-instruct"
            }
        }
        
        // Default base URLs
        fun defaultBaseUrl(provider: ApiProvider): String {
            return when (provider) {
                ApiProvider.OFFLINE -> "" // Not used
                ApiProvider.OPENAI -> "https://api.openai.com/v1"
                ApiProvider.CLAUDE -> "https://api.anthropic.com/v1"
                ApiProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
                ApiProvider.KIMI -> "https://api.moonshot.cn/anthropic/v1"
                ApiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
            }
        }
    }
}

/**
 * Connection status for online providers
 */
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
    ERROR
}

/**
 * Provider status with availability info
 */
data class ProviderStatus(
    val provider: ApiProvider,
    val isAvailable: Boolean,
    val status: ConnectionStatus,
    val latencyMs: Long = 0,
    val errorMessage: String? = null
)

/**
 * Token usage tracking
 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
) {
    operator fun plus(other: TokenUsage): TokenUsage {
        return TokenUsage(
            promptTokens = promptTokens + other.promptTokens,
            completionTokens = completionTokens + other.completionTokens,
            totalTokens = totalTokens + other.totalTokens
        )
    }
}
