package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.getkeepsafe.relinker.ReLinker
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Moonshine STT Bridge using Sherpa-ONNX
 * 
 * Moonshine Tiny: ~45MB, ~1-2s latency, optimized for edge devices
 * Much faster than Whisper base (74MB, ~10s) with similar accuracy
 */
class MoonshineSTTBridge(private val context: Context) : BaseBridge(), STTBridgeInterface {
    
    companion object {
        private const val TAG = "MoonshineSTTBridge"
        private const val SAMPLE_RATE = 16000
        
        // Moonshine Tiny INT8 model files (faster, smaller)
        // Downloaded from: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
        const val PREPROCESSOR = "moonshine-tiny-preprocessor.onnx"
        const val ENCODER = "moonshine-tiny-encoder.onnx"  // Note: actual file is encode.int8.onnx
        const val UNCACHED_DECODER = "moonshine-tiny-uncached-decoder.onnx"  // uncached_decode.int8.onnx
        const val CACHED_DECODER = "moonshine-tiny-cached-decoder.onnx"  // cached_decode.int8.onnx
        const val TOKENS = "tokens.txt"
    }
    
    private var recognizer: OfflineRecognizer? = null
    private var modelDir: File? = null
    
    override fun getBridgeName(): String = "MoonshineSTTBridge"
    
    override fun init(): Boolean {
        updateState(State.INITIALIZING)
        return try {
            // Load native libraries using ReLinker for proper dependency resolution
            // This ensures RTLD_GLOBAL is used so symbols are visible to dependent libraries
            try {
                ReLinker.loadLibrary(context, "onnxruntime")
                Log.i(TAG, "ONNX Runtime loaded via ReLinker")
            } catch (e: Exception) {
                Log.w(TAG, "ONNX Runtime load attempt: ${e.message}")
            }
            
            try {
                ReLinker.loadLibrary(context, "sherpa-onnx-c-api")
                Log.i(TAG, "Sherpa-ONNX C API loaded via ReLinker")
            } catch (e: Exception) {
                Log.w(TAG, "Sherpa C API load attempt: ${e.message}")
            }
            
            ReLinker.loadLibrary(context, "sherpa-onnx-jni")
            Log.i(TAG, "Sherpa-ONNX JNI loaded via ReLinker")
            updateState(State.READY)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            setError("Failed to initialize: ${e.message}")
            false
        }
    }
    
    /**
     * Initialize with Moonshine Tiny models from external storage
     */
    fun initialize(externalDir: File): Boolean {
        if (!init()) return false
        
        modelDir = File(externalDir, "models/stt_moonshine")
        
        // Check if all model files exist
        val preprocessorFile = File(modelDir, PREPROCESSOR)
        val encoderFile = File(modelDir, ENCODER)
        val uncachedDecoderFile = File(modelDir, UNCACHED_DECODER)
        val cachedDecoderFile = File(modelDir, CACHED_DECODER)
        
        if (!preprocessorFile.exists() || !encoderFile.exists() || 
            !uncachedDecoderFile.exists() || !cachedDecoderFile.exists()) {
            Log.e(TAG, "Moonshine models not found in ${modelDir?.absolutePath}")
            Log.e(TAG, "Files: ${modelDir?.listFiles()?.map { it.name }}")
            return false
        }
        
        return try {
            updateState(State.LOADING)
            
            val modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = preprocessorFile.absolutePath,
                    encoder = encoderFile.absolutePath,
                    uncachedDecoder = uncachedDecoderFile.absolutePath,
                    cachedDecoder = cachedDecoderFile.absolutePath
                ),
                numThreads = 4,  // Use 4 threads for good performance on Tab A7 Lite
                debug = false,
                provider = "cpu"
            )
            
            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                featConfig = FeatureExtractorConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )
            
            recognizer = OfflineRecognizer.create(config)
            
            if (recognizer != null) {
                updateState(State.READY)
                Log.i(TAG, "✓ Moonshine STT initialized successfully")
                true
            } else {
                setError("Failed to create recognizer")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Moonshine model: ${e.message}", e)
            setError("Model loading failed: ${e.message}")
            false
        }
    }
    
    override fun release(): Boolean {
        return safeRelease {
            try {
                recognizer?.release()
                recognizer = null
                Log.i(TAG, "Moonshine STT released")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing: ${e.message}")
                false
            }
        }
    }
    
    override fun isLoaded(): Boolean = recognizer != null
    override fun isAvailable(): Boolean = isLoaded()
    override fun getName(): String = "Moonshine Tiny (Sherpa-ONNX)"
    
    /**
     * Transcribe audio samples using Moonshine
     * 
     * @param audioData PCM audio data (16-bit, 16kHz, mono)
     * @return Transcribed text
     */
    fun transcribe(audioData: ShortArray): String {
        if (recognizer == null) {
            Log.w(TAG, "Recognizer not initialized")
            return ""
        }
        
        if (audioData.isEmpty()) {
            Log.w(TAG, "Empty audio data")
            return ""
        }
        
        val startTime = System.currentTimeMillis()
        
        return try {
            // Convert ShortArray to FloatArray (normalize to [-1, 1])
            val floatSamples = FloatArray(audioData.size) { i ->
                audioData[i] / 32768.0f
            }
            
            Log.d(TAG, "Transcribing ${floatSamples.size} samples...")
            
            // Create stream and process
            val stream = recognizer!!.createStream()
            stream.acceptWaveform(SAMPLE_RATE, floatSamples)
            
            // Decode
            recognizer!!.decode(stream)
            
            // Get result
            val result = recognizer!!.getResult(stream)
            val text = result.text.trim()
            
            // Cleanup
            stream.release()
            
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Transcription completed in ${duration}ms: \"$text\"")
            
            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            ""
        }
    }
    
    /**
     * Check if all Moonshine model files exist
     */
    fun areModelsAvailable(externalDir: File): Boolean {
        val dir = File(externalDir, "models/stt_moonshine")
        return File(dir, PREPROCESSOR).exists() &&
               File(dir, ENCODER).exists() &&
               File(dir, UNCACHED_DECODER).exists() &&
               File(dir, CACHED_DECODER).exists()
    }
    
    /**
     * Get model download information
     */
    fun getModelInfo(): Map<String, String> {
        return mapOf(
            "name" to "Moonshine Tiny",
            "size" to "~45MB",
            "latency" to "~1-2s",
            "source" to "https://github.com/k2-fsa/sherpa-onnx/releases",
            "files" to "$PREPROCESSOR, $ENCODER, $UNCACHED_DECODER, $CACHED_DECODER"
        )
    }
}
