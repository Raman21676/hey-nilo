package com.projekt_x.studybuddy.model

/**
 * RAM requirements for each model tier
 * Models are organized by minimum RAM needed to run comfortably
 */
enum class RAMTier(val minRamGB: Int, val maxRamGB: Int, val label: String, val description: String) {
    RAM_3GB(2, 3, "3GB RAM", "Ultra-light models for basic devices"),
    RAM_4GB(4, 4, "4GB RAM", "Lightweight models with good quality"),
    RAM_6GB(5, 6, "6GB RAM", "Medium models with better performance"),
    RAM_8GB(7, 8, "8GB RAM", "Larger models for capable devices"),
    RAM_12GB_PLUS(9, Int.MAX_VALUE, "12GB+ RAM", "Full-size models for high-end devices");

    companion object {
        fun forDeviceRam(totalRamGB: Int): RAMTier {
            return when {
                totalRamGB <= 3 -> RAM_3GB
                totalRamGB == 4 -> RAM_4GB
                totalRamGB <= 6 -> RAM_6GB
                totalRamGB <= 8 -> RAM_8GB
                else -> RAM_12GB_PLUS
            }
        }
    }
}

data class OfflineModelConfig(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeGB: Float,
    val minRamGB: Int,      // Minimum RAM required to run this model
    val isRecommended: Boolean = false,
    val description: String,
    val downloadUrl: String
) {
    fun isCompatibleWith(deviceRamGB: Int): Boolean {
        return deviceRamGB >= minRamGB
    }
    
    fun getTier(): RAMTier {
        return when {
            minRamGB <= 3 -> RAMTier.RAM_3GB
            minRamGB == 4 -> RAMTier.RAM_4GB
            minRamGB <= 6 -> RAMTier.RAM_6GB
            minRamGB <= 8 -> RAMTier.RAM_8GB
            else -> RAMTier.RAM_12GB_PLUS
        }
    }
}

/**
 * ALL 10 Models organized by their ACTUAL minimum RAM requirements
 * Each tier contains models that need that specific amount of RAM
 */
val ALL_OFFLINE_MODELS: List<OfflineModelConfig> = listOf(
    // ========================================
    // TIER 1: 2-3GB RAM (Ultra-light models)
    // ========================================
    OfflineModelConfig(
        id = "tinyllama",
        displayName = "TinyLlama 1.1B",
        fileName = "tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
        sizeGB = 0.6f,
        minRamGB = 2,
        isRecommended = true,
        description = "Fast & lightweight · Perfect for basic chat",
        downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_0.gguf"
    ),
    OfflineModelConfig(
        id = "qwen2-0.5b",
        displayName = "Qwen2 0.5B",
        fileName = "qwen2-0_5b-instruct-q4_0.gguf",
        sizeGB = 0.4f,
        minRamGB = 2,
        isRecommended = false,
        description = "Ultra-light · Alibaba's smallest model",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_0.gguf"
    ),

    // ========================================
    // TIER 2: 4GB RAM (Lightweight quality models)
    // ========================================
    OfflineModelConfig(
        id = "qwen2-1.5b",
        displayName = "Qwen2 1.5B",
        fileName = "qwen2-1_5b-instruct-q4_0.gguf",
        sizeGB = 0.9f,
        minRamGB = 3,
        isRecommended = false,  // Works on 3GB but better on 4GB
        description = "Best balance · Fast with good quality",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_0.gguf"
    ),
    OfflineModelConfig(
        id = "gemma-2b",
        displayName = "Gemma 2B IT",
        fileName = "gemma-2b-it-q4_0.gguf",
        sizeGB = 1.4f,
        minRamGB = 4,
        isRecommended = true,
        description = "Google's model · High quality",
        downloadUrl = "https://huggingface.co/lmstudio-community/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-Q4_K_M.gguf"
    ),
    OfflineModelConfig(
        id = "smollm2",
        displayName = "SmolLM2 1.7B",
        fileName = "smollm2-1.7b-instruct-q4_0.gguf",
        sizeGB = 1.0f,
        minRamGB = 4,
        isRecommended = false,
        description = "Microsoft's best · Very fast & efficient",
        downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_0.gguf"
    ),

    // ========================================
    // TIER 3: 5-6GB RAM (Medium performance models)
    // ========================================
    OfflineModelConfig(
        id = "phi3-mini",
        displayName = "Phi-3 Mini 3.8B",
        fileName = "phi-3-mini-4k-instruct-q4_0.gguf",
        sizeGB = 2.2f,
        minRamGB = 5,
        isRecommended = true,
        description = "Microsoft Phi-3 · Excellent reasoning",
        downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf"
    ),
    OfflineModelConfig(
        id = "llama3.2-3b",
        displayName = "Llama 3.2 3B",
        fileName = "llama-3.2-3b-instruct-q4_0.gguf",
        sizeGB = 1.9f,
        minRamGB = 6,
        isRecommended = false,
        description = "Meta's latest · Best overall quality",
        downloadUrl = "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf"
    ),
    OfflineModelConfig(
        id = "qwen2.5-3b",
        displayName = "Qwen2.5 3B",
        fileName = "qwen2.5-3b-instruct-q4_0.gguf",
        sizeGB = 1.8f,
        minRamGB = 6,
        isRecommended = false,
        description = "Alibaba's best · Great for coding",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_0.gguf"
    ),

    // ========================================
    // TIER 4: 8GB RAM (Large capable models)
    // ========================================
    OfflineModelConfig(
        id = "llama3-8b",
        displayName = "Llama 3 8B",
        fileName = "llama-3-8b-instruct-q4_0.gguf",
        sizeGB = 4.5f,
        minRamGB = 8,
        isRecommended = true,
        description = "Meta's full model · Excellent quality",
        downloadUrl = "https://huggingface.co/hugging-quants/Llama-3-8B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3-8b-instruct-q4_k_m.gguf"
    ),
    OfflineModelConfig(
        id = "qwen2.5-7b",
        displayName = "Qwen2.5 7B",
        fileName = "qwen2.5-7b-instruct-q4_0.gguf",
        sizeGB = 4.4f,
        minRamGB = 8,
        isRecommended = false,
        description = "Alibaba's large model · Top tier",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_0.gguf"
    ),

    // ========================================
    // TIER 5: 10GB+ RAM (Full-size models)
    // ========================================
    OfflineModelConfig(
        id = "llama3.1-8b",
        displayName = "Llama 3.1 8B",
        fileName = "llama-3.1-8b-instruct-q4_0.gguf",
        sizeGB = 4.7f,
        minRamGB = 10,
        isRecommended = true,
        description = "Full capability · Best responses",
        downloadUrl = "https://huggingface.co/hugging-quants/Llama-3.1-8B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.1-8b-instruct-q4_k_m.gguf"
    )
)

/**
 * Group models by their ACTUAL minimum RAM requirement
 */
val MODELS_BY_RAM_TIER: Map<RAMTier, List<OfflineModelConfig>> by lazy {
    ALL_OFFLINE_MODELS.groupBy { it.getTier() }
}

/**
 * Get all models (for showing all options)
 */
fun getAllModels(): List<OfflineModelConfig> = ALL_OFFLINE_MODELS

/**
 * Get the recommended model for a device
 */
fun getRecommendedModel(deviceRamGB: Int): OfflineModelConfig? {
    return ALL_OFFLINE_MODELS
        .filter { it.isCompatibleWith(deviceRamGB) }
        .maxByOrNull { it.minRamGB }  // Get the best model that works on this device
}

/**
 * Legacy list for backward compatibility
 */
@Deprecated("Use getAllModels() instead")
val AVAILABLE_OFFLINE_MODELS: List<OfflineModelConfig> = ALL_OFFLINE_MODELS
