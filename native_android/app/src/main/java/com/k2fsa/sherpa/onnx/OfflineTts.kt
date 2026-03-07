package com.k2fsa.sherpa.onnx

/**
 * Sherpa-ONNX OfflineTts JNI Bindings
 */

// Configuration classes
data class OfflineTtsConfig(
    val model: OfflineTtsModelConfig,
    val maxNumSentences: Int = 1
)

data class OfflineTtsModelConfig(
    val vits: OfflineTtsVitsModelConfig? = null,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu"
)

data class OfflineTtsVitsModelConfig(
    val model: String,
    val tokens: String,
    val dataDir: String = "",
    val noiseScale: Float = 0.667f,
    val noiseScaleW: Float = 0.8f,
    val lengthScale: Float = 1.0f
)

/**
 * Generated audio result
 */
data class GeneratedAudioData(
    val samples: FloatArray,
    val sampleRate: Int
)

/**
 * Offline TTS using JNI
 */
class OfflineTts(
    var ptr: Long
) {
    val sampleRate: Int
    
    init {
        if (ptr == 0L) throw RuntimeException("Failed to create OfflineTts")
        sampleRate = nativeGetSampleRate(ptr)
    }
    
    companion object {
        init {
            try { System.loadLibrary("onnxruntime") } catch (e: UnsatisfiedLinkError) {}
            try { System.loadLibrary("sherpa-onnx-c-api") } catch (e: UnsatisfiedLinkError) {}
            System.loadLibrary("sherpa-onnx-jni")
        }
        
        fun create(config: OfflineTtsConfig): OfflineTts? {
            val ptr = nativeCreate(config)
            return if (ptr != 0L) OfflineTts(ptr) else null
        }
        
        private external fun nativeCreate(config: OfflineTtsConfig): Long
        private external fun nativeGetSampleRate(ptr: Long): Int
    }
    
    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudioData? {
        return nativeGenerate(ptr, text, sid, speed)
    }
    
    fun release() {
        if (ptr != 0L) {
            nativeRelease(ptr)
            ptr = 0
        }
    }
    
    protected fun finalize() {
        release()
    }
    
    private external fun nativeGenerate(ptr: Long, text: String, sid: Int, speed: Float): GeneratedAudioData?
    private external fun nativeRelease(ptr: Long)
}
