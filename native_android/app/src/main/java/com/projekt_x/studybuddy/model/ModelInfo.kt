package com.projekt_x.studybuddy.model

/**
 * Information about available AI models
 */
sealed class ModelInfo(
    open val name: String,
    open val url: String,
    open val filename: String,
    open val size: Long,
    open val ramRequiredMB: Int,
    open val sha256: String? = null,
    open val description: String = ""
) {
    /**
     * LLM Models
     */
    data class LLM(
        override val name: String,
        override val url: String,
        override val filename: String,
        override val size: Long,
        override val ramRequiredMB: Int,
        override val sha256: String? = null,
        override val description: String = "",
        val contextSize: Int = 2048,
        val quantization: String = "Q4_0"
    ) : ModelInfo(name, url, filename, size, ramRequiredMB, sha256, description)

    /**
     * Voice Models (VAD, STT, TTS)
     */
    data class Voice(
        override val name: String,
        override val url: String,
        override val filename: String,
        override val size: Long,
        override val ramRequiredMB: Int,
        override val sha256: String? = null,
        override val description: String = "",
        val type: VoiceModelType
    ) : ModelInfo(name, url, filename, size, ramRequiredMB, sha256, description)

    enum class VoiceModelType {
        VAD, STT, TTS
    }

    companion object {
        /**
         * Available LLM models
         */
        val LLM_MODELS = listOf(
            LLM(
                name = "TinyLlama-1.1B-Chat",
                url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
                filename = "tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
                size = 637_000_000L,
                ramRequiredMB = 1500,
                sha256 = null, // TODO: Add actual SHA256
                description = "Lightweight model for 2-3GB RAM devices",
                contextSize = 2048,
                quantization = "Q4_0"
            ),
            LLM(
                name = "Qwen2.5-1.5B-Instruct",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf",
                filename = "qwen2.5-1.5b-instruct-q4_0.gguf",
                size = 900_000_000L,
                ramRequiredMB = 2200,
                sha256 = null,
                description = "Better quality for 3-4GB RAM devices (Recommended)",
                contextSize = 32768,
                quantization = "Q4_0"
            ),
            LLM(
                name = "Phi-3-mini-4k-Instruct",
                url = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
                filename = "Phi-3-mini-4k-instruct-q4.gguf",
                size = 2_300_000_000L,
                ramRequiredMB = 3500,
                sha256 = null,
                description = "Best quality for 4GB+ RAM devices",
                contextSize = 4096,
                quantization = "Q4"
            )
        )

        /**
         * Voice models (for Phase 2)
         */
        val VOICE_MODELS = listOf(
            Voice(
                name = "Silero VAD",
                url = "https://github.com/snakers4/silero-vad/raw/master/files/silero_vad.onnx",
                filename = "silero_vad.onnx",
                size = 1_000_000L,
                ramRequiredMB = 50,
                type = VoiceModelType.VAD,
                description = "Voice Activity Detection"
            ),
            Voice(
                name = "Moonshine Tiny",
                url = "https://github.com/usefulsensors/moonshine/raw/master/models/moonshine-tiny.onnx",
                filename = "moonshine-tiny.onnx",
                size = 26_000_000L,
                ramRequiredMB = 200,
                type = VoiceModelType.STT,
                description = "Speech-to-Text (26MB)"
            ),
            Voice(
                name = "Kokoro-82M INT8",
                url = "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v0_19.onnx",
                filename = "kokoro-v0_19.onnx",
                size = 80_000_000L,
                ramRequiredMB = 300,
                type = VoiceModelType.TTS,
                description = "Text-to-Speech (80MB INT8)"
            )
        )

        /**
         * Get recommended model based on device RAM
         */
        fun getRecommendedModel(availableRamMB: Int): LLM {
            return when {
                availableRamMB < 2500 -> LLM_MODELS[0] // TinyLlama
                availableRamMB < 4000 -> LLM_MODELS[1] // Qwen2.5
                else -> LLM_MODELS[2] // Phi-3
            }
        }

        /**
         * Get all models suitable for given RAM
         */
        fun getSuitableModels(availableRamMB: Int): List<LLM> {
            return LLM_MODELS.filter { it.ramRequiredMB <= availableRamMB * 0.8 }
        }
    }
}

/**
 * Device capability detection
 */
data class DeviceCapabilities(
    val totalRamMB: Long,
    val availableRamMB: Long,
    val cpuCores: Int,
    val hasGpu: Boolean
) {
    fun canRun(modelInfo: ModelInfo): Boolean {
        return availableRamMB >= modelInfo.ramRequiredMB
    }

    fun getRecommendedModels(): List<ModelInfo.LLM> {
        return ModelInfo.getSuitableModels(availableRamMB.toInt())
    }
}
