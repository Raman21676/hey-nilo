package com.k2fsa.sherpa.onnx

/**
 * Sherpa-ONNX OfflineRecognizer JNI Bindings
 */

// Configuration classes
data class OfflineRecognizerConfig(
    val featConfig: FeatureExtractorConfig = FeatureExtractorConfig(),
    val modelConfig: OfflineModelConfig,
    val lmConfig: OfflineLMConfig? = null,
    val decodingMethod: String = "greedy_search",
    val maxActivePaths: Int = 4,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 1.5f,
    val ruleFsts: String = "",
    val ruleFars: String = ""
)

data class FeatureExtractorConfig(
    val sampleRate: Int = 16000,
    val featureDim: Int = 80
)

data class OfflineModelConfig(
    val whisper: WhisperConfig? = null,
    val paraformer: OfflineParaformerModelConfig? = null,
    val moonshine: OfflineMoonshineModelConfig? = null,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val modelType: String? = null,
    val tokens: String? = null
)

data class WhisperConfig(
    val encoder: String,
    val decoder: String,
    val tokens: String,
    val language: String = "en",
    val task: String = "transcribe",
    val tailPaddings: Int = 0
)

data class OfflineParaformerModelConfig(
    val model: String,
    val tokens: String
)

data class OfflineMoonshineModelConfig(
    val preprocessor: String,
    val encoder: String,
    val uncachedDecoder: String,
    val cachedDecoder: String
)

data class OfflineLMConfig(
    val model: String = "",
    val scale: Float = 0.5f
)

data class OfflineRecognizerResult(
    val text: String
)

/**
 * Offline recognizer using JNI
 */
class OfflineRecognizer(
    var ptr: Long
) {
    init {
        if (ptr == 0L) throw RuntimeException("Failed to create OfflineRecognizer")
    }
    
    companion object {
        init {
            try { System.loadLibrary("onnxruntime") } catch (e: UnsatisfiedLinkError) {}
            try { System.loadLibrary("sherpa-onnx-c-api") } catch (e: UnsatisfiedLinkError) {}
            System.loadLibrary("sherpa-onnx-jni")
        }
        
        fun create(config: OfflineRecognizerConfig): OfflineRecognizer? {
            val ptr = nativeCreate(config)
            return if (ptr != 0L) OfflineRecognizer(ptr) else null
        }
        
        private external fun nativeCreate(config: OfflineRecognizerConfig): Long
    }
    
    fun createStream(): OfflineStream {
        val streamPtr = nativeCreateStream(ptr)
        if (streamPtr == 0L) throw RuntimeException("Failed to create stream")
        return OfflineStream(streamPtr, this)
    }
    
    fun decode(stream: OfflineStream) {
        nativeDecode(ptr, stream.ptr)
    }
    
    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        val text = nativeGetResult(ptr, stream.ptr) ?: ""
        return OfflineRecognizerResult(text)
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
    
    private external fun nativeCreateStream(recognizerPtr: Long): Long
    private external fun nativeDecode(recognizerPtr: Long, streamPtr: Long)
    private external fun nativeGetResult(recognizerPtr: Long, streamPtr: Long): String?
    private external fun nativeRelease(ptr: Long)
}

/**
 * Offline recognition stream
 */
class OfflineStream(
    var ptr: Long,
    private val recognizer: OfflineRecognizer
) {
    fun acceptWaveform(sampleRate: Int, samples: FloatArray) {
        nativeAcceptWaveform(ptr, sampleRate, samples)
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
    
    private external fun nativeAcceptWaveform(streamPtr: Long, sampleRate: Int, samples: FloatArray)
    private external fun nativeRelease(ptr: Long)
}
