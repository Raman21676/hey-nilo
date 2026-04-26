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
    val downloadUrl: String,
    val category: ModelCategory = ModelCategory.GENERAL // NEW: Category for filtering
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
 * Model categories for filtering/sorting
 */
enum class ModelCategory(val displayName: String, val icon: String) {
    GENERAL("General", "💬"),
    CODING("Coding", "💻"),
    CREATIVE("Creative Writing", "✍️"),
    MULTILINGUAL("Multilingual", "🌍"),
    REASONING("Reasoning/Math", "🧮"),
    ULTRA_LIGHT("Ultra-Light", "🪶")
}

/**
 * ALL 27 Models organized by their ACTUAL minimum RAM requirements
 * Each tier contains models that need that specific amount of RAM
 * 
 * EXPANDED: Added 18 new models based on popular demand (Pocket Pal style)
 * Categories: General, Coding, Creative, Multilingual, Reasoning
 */
val ALL_OFFLINE_MODELS: List<OfflineModelConfig> = listOf(
    // ========================================
    // TIER 1: 2-3GB RAM (Ultra-light models)
    // ========================================
    OfflineModelConfig(
        id = "qwen2.5-0.5b",
        displayName = "Qwen2.5 0.5B",
        fileName = "qwen2.5-0.5b-instruct-q4_0.gguf",
        sizeGB = 0.4f,
        minRamGB = 2,
        isRecommended = false,
        description = "Ultra-light · Basic chat for very old devices",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
        category = ModelCategory.ULTRA_LIGHT
    ),
    OfflineModelConfig(
        id = "llama3.2-1b",
        displayName = "Llama 3.2 1B",
        fileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
        sizeGB = 0.7f,
        minRamGB = 2,
        isRecommended = true,
        description = "Meta's best 1B model · Fast & capable",
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "tinyllama",
        displayName = "TinyLlama 1.1B",
        fileName = "tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
        sizeGB = 0.6f,
        minRamGB = 2,
        isRecommended = false,
        description = "Fast & lightweight · 3T tokens trained",
        downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "qwen2-0.5b",
        displayName = "Qwen2 0.5B",
        fileName = "qwen2-0_5b-instruct-q4_0.gguf",
        sizeGB = 0.4f,
        minRamGB = 2,
        isRecommended = false,
        description = "Ultra-light · Alibaba's smallest model",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_0.gguf",
        category = ModelCategory.ULTRA_LIGHT
    ),
    // ========================================
    // TIER 2: 4GB RAM (Lightweight quality models)
    // ========================================
    OfflineModelConfig(
        id = "qwen2.5-1.5b",
        displayName = "Qwen2.5 1.5B",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeGB = 1.1f,
        minRamGB = 4,
        isRecommended = true,
        description = "Best 4GB model · Great reasoning",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "gemma-2-2b",
        displayName = "Gemma 2 2B IT",
        fileName = "gemma-2-2b-it-Q4_K_M.gguf",
        sizeGB = 1.6f,
        minRamGB = 4,
        isRecommended = false,
        description = "Gemma 2 · Better quality & safety",
        downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B IT",
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        sizeGB = 2.9f,
        minRamGB = 4,
        isRecommended = false,
        description = "Gemma 4 Edge · Latest small model",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "qwen2-1.5b",
        displayName = "Qwen2 1.5B",
        fileName = "qwen2-1_5b-instruct-q4_0.gguf",
        sizeGB = 0.9f,
        minRamGB = 3,
        isRecommended = false,
        description = "Fast with good quality · Balanced",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_0.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "smollm2",
        displayName = "SmolLM2 1.7B",
        fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        sizeGB = 1.0f,
        minRamGB = 4,
        isRecommended = false,
        description = "Microsoft's best · Very fast & efficient",
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "phi-2",
        displayName = "Phi-2 2.7B",
        fileName = "phi-2-Q4_K_M.gguf",
        sizeGB = 1.6f,
        minRamGB = 4,
        isRecommended = false,
        description = "Microsoft reasoning · Logic & math",
        downloadUrl = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
        category = ModelCategory.REASONING
    ),

    // ========================================
    // TIER 3: 5-6GB RAM (Medium performance models)
    // ========================================
    OfflineModelConfig(
        id = "phi3-mini",
        displayName = "Phi-3 Mini 3.8B",
        fileName = "phi-3-mini-4k-instruct-q4.gguf",
        sizeGB = 2.2f,
        minRamGB = 5,
        isRecommended = true,
        description = "Microsoft Phi-3 · Excellent reasoning",
        downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        category = ModelCategory.REASONING
    ),
    OfflineModelConfig(
        id = "llama3.2-3b",
        displayName = "Llama 3.2 3B",
        fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
        sizeGB = 1.9f,
        minRamGB = 6,
        isRecommended = false,
        description = "Meta's latest · Best overall quality",
        downloadUrl = "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "gemma-3-4b",
        displayName = "Gemma 3 4B IT",
        fileName = "gemma-3-4b-it-Q4_K_M.gguf",
        sizeGB = 2.5f,
        minRamGB = 6,
        isRecommended = false,
        description = "Gemma 3 4B · Vision + text capable",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B IT",
        fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
        sizeGB = 4.6f,
        minRamGB = 6,
        isRecommended = false,
        description = "Gemma 4 Edge 4B · Latest medium model",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "qwen2.5-3b",
        displayName = "Qwen2.5 3B",
        fileName = "qwen2.5-3b-instruct-q4_0.gguf",
        sizeGB = 1.8f,
        minRamGB = 6,
        isRecommended = false,
        description = "Alibaba's best · Great for coding",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_0.gguf",
        category = ModelCategory.CODING
    ),
    OfflineModelConfig(
        id = "deepseek-coder-1.3b",
        displayName = "DeepSeek Coder 1.3B",
        fileName = "deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
        sizeGB = 0.8f,
        minRamGB = 5,
        isRecommended = false,
        description = "Code specialist · Completion & debug",
        downloadUrl = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
        category = ModelCategory.CODING
    ),
    OfflineModelConfig(
        id = "exaone-3.5-2.4b",
        displayName = "EXAONE 3.5 2.4B",
        fileName = "EXAONE-3.5-2.4B-Instruct-Q4_K_M.gguf",
        sizeGB = 1.5f,
        minRamGB = 5,
        isRecommended = false,
        description = "LG AI · Bilingual EN/KO support",
        downloadUrl = "https://huggingface.co/bartowski/EXAONE-3.5-2.4B-Instruct-GGUF/resolve/main/EXAONE-3.5-2.4B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.MULTILINGUAL
    ),
    OfflineModelConfig(
        id = "qwen2.5-coder-1.5b",
        displayName = "Qwen2.5 Coder 1.5B",
        fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        sizeGB = 1.1f,
        minRamGB = 5,
        isRecommended = false,
        description = "Coding specialist · Python & JS",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        category = ModelCategory.CODING
    ),

    // ========================================
    // TIER 4: 8GB RAM (Large capable models)
    // ========================================
    OfflineModelConfig(
        id = "llama3-8b",
        displayName = "Llama 3 8B",
        fileName = "Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
        sizeGB = 4.6f,
        minRamGB = 8,
        isRecommended = true,
        description = "Meta's full model · Excellent quality",
        downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "qwen2.5-7b",
        displayName = "Qwen2.5 7B",
        fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        sizeGB = 4.4f,
        minRamGB = 8,
        isRecommended = false,
        description = "Alibaba's large model · Top tier",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.MULTILINGUAL
    ),
    OfflineModelConfig(
        id = "mistral-7b",
        displayName = "Mistral 7B Instruct",
        fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        sizeGB = 4.1f,
        minRamGB = 8,
        isRecommended = false,
        description = "Creative writing · Story & dialogue",
        downloadUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        category = ModelCategory.CREATIVE
    ),
    OfflineModelConfig(
        id = "deepseek-coder-6.7b",
        displayName = "DeepSeek Coder 6.7B",
        fileName = "deepseek-coder-6.7b-instruct.Q4_K_M.gguf",
        sizeGB = 3.8f,
        minRamGB = 8,
        isRecommended = false,
        description = "Best coding model · 82% HumanEval",
        downloadUrl = "https://huggingface.co/TheBloke/deepseek-coder-6.7b-instruct-GGUF/resolve/main/deepseek-coder-6.7b-instruct.Q4_K_M.gguf",
        category = ModelCategory.CODING
    ),
    OfflineModelConfig(
        id = "codellama-7b",
        displayName = "CodeLlama 7B",
        fileName = "codellama-7b-instruct.Q4_K_M.gguf",
        sizeGB = 3.8f,
        minRamGB = 8,
        isRecommended = false,
        description = "Meta's code model · Fill-in-middle",
        downloadUrl = "https://huggingface.co/TheBloke/CodeLlama-7B-Instruct-GGUF/resolve/main/codellama-7b-instruct.Q4_K_M.gguf",
        category = ModelCategory.CODING
    ),
    OfflineModelConfig(
        id = "qwen2.5-coder-7b",
        displayName = "Qwen2.5 Coder 7B",
        fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        sizeGB = 4.4f,
        minRamGB = 8,
        isRecommended = false,
        description = "Advanced coding · Multi-language",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        category = ModelCategory.CODING
    ),

    // ========================================
    // TIER 5: 10GB+ RAM (Full-size models)
    // ========================================
    OfflineModelConfig(
        id = "llama3.1-8b",
        displayName = "Llama 3.1 8B",
        fileName = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        sizeGB = 4.6f,
        minRamGB = 10,
        isRecommended = true,
        description = "128K context · Best responses",
        downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "gemma-2-9b",
        displayName = "Gemma 2 9B",
        fileName = "gemma-2-9b-it-Q4_K_M.gguf",
        sizeGB = 5.5f,
        minRamGB = 10,
        isRecommended = false,
        description = "Google's best · Knowledge & reasoning",
        downloadUrl = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
        category = ModelCategory.GENERAL
    ),
    OfflineModelConfig(
        id = "mistral-nemo-12b",
        displayName = "Mistral Nemo 12B",
        fileName = "Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
        sizeGB = 7.0f,
        minRamGB = 12,
        isRecommended = false,
        description = "Best creative writing · Stories",
        downloadUrl = "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
        category = ModelCategory.CREATIVE
    ),
    OfflineModelConfig(
        id = "qwen2.5-14b",
        displayName = "Qwen2.5 14B",
        fileName = "Qwen2.5-14B-Instruct-Q4_K_M.gguf",
        sizeGB = 8.4f,
        minRamGB = 12,
        isRecommended = false,
        description = "Power users · Advanced reasoning",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
        category = ModelCategory.REASONING
    )
    // Note: Llama 3.1 70B removed as it requires 40GB - not practical for mobile
)

/**
 * Group models by their ACTUAL minimum RAM requirement
 */
val MODELS_BY_RAM_TIER: Map<RAMTier, List<OfflineModelConfig>> by lazy {
    ALL_OFFLINE_MODELS.groupBy { it.getTier() }
}

/**
 * Group models by category for filtering
 */
val MODELS_BY_CATEGORY: Map<ModelCategory, List<OfflineModelConfig>> by lazy {
    ALL_OFFLINE_MODELS.groupBy { it.category }
}

/**
 * Get all models (for showing all options)
 */
fun getAllModels(): List<OfflineModelConfig> = ALL_OFFLINE_MODELS

/**
 * Get models filtered by category
 */
fun getModelsByCategory(category: ModelCategory): List<OfflineModelConfig> {
    return MODELS_BY_CATEGORY[category] ?: emptyList()
}

/**
 * Get the recommended model for a device
 */
fun getRecommendedModel(deviceRamGB: Int): OfflineModelConfig? {
    return ALL_OFFLINE_MODELS
        .filter { it.isCompatibleWith(deviceRamGB) && it.isRecommended }
        .maxByOrNull { it.minRamGB }
}

/**
 * Get best model for specific use case
 */
fun getBestModelForCategory(deviceRamGB: Int, category: ModelCategory): OfflineModelConfig? {
    return ALL_OFFLINE_MODELS
        .filter { it.isCompatibleWith(deviceRamGB) && it.category == category }
        .maxByOrNull { it.minRamGB }
}

/**
 * Legacy list for backward compatibility
 */
@Deprecated("Use getAllModels() instead")
val AVAILABLE_OFFLINE_MODELS: List<OfflineModelConfig> = ALL_OFFLINE_MODELS
