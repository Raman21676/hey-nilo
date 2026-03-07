package com.projekt_x.studybuddy.bridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Real TTS Bridge using Piper VITS ONNX model.
 * 
 * Piper uses a VITS-based architecture:
 *   Input:  phoneme/character IDs as int64 tensor + lengths
 *   Output: raw audio waveform as float32 tensor
 * 
 * Piper model (opset 15): ~60MB, 16kHz output, mono
 * 
 * Pipeline:
 *   text → character IDs (ASCII + special tokens) → ONNX inference → float PCM → int16 PCM → AudioTrack
 */
class TTSBridge(context: Context) : TTSBridgeInterface {
    
    companion object {
        private const val TAG = "TTSBridge"
        private const val CHANNEL_COUNT = 1
        
        // Piper VITS special token IDs
        private const val PAD_ID = 0L
        private const val BOS_ID = 1L    // Beginning of sequence
        private const val EOS_ID = 2L    // End of sequence
        
        // Piper uses phoneme-id mapping where printable ASCII starts at offset
        // For character-based Piper models: id = char_code - 31 + offset
        private const val CHAR_OFFSET = 3L  // After PAD, BOS, EOS
        
        // Chunk size for streaming playback
        private const val PLAYBACK_CHUNK_SIZE = 4096
        
        // Track if native libraries are loaded
        private var nativeLibsLoaded = false
        
        /**
         * Load ONNX Runtime native libraries
         * Must be called before any OrtEnvironment creation
         */
        fun loadNativeLibraries() {
            if (nativeLibsLoaded) return
            
            try {
                // Load ONNX Runtime native library first
                System.loadLibrary("onnxruntime")
                Log.d(TAG, "Loaded onnxruntime")
                
                nativeLibsLoaded = true
                Log.i(TAG, "ONNX Runtime native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load ONNX Runtime native libraries: ${e.message}")
                throw e
            }
        }
    }
    
    // Instance-level sample rate (Piper uses 16000Hz or 22050Hz depending on model)
    private var sampleRate = 16000
    
    interface TTSCallback {
        fun onStart()
        fun onChunk(audioData: ShortArray)
        fun onComplete()
        fun onError(error: String)
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    @Volatile
    private var isSpeaking = false
    private var modelType = "Unknown"
    
    private var audioTrack: AudioTrack? = null
    private val appContext = context.applicationContext
    
    var callback: TTSCallback? = null
    
    /**
     * Initialize the TTS bridge with model from external storage
     * 
     * IMPORTANT: Must be called from background thread to avoid ANR
     */
    fun initialize(externalDir: File): Boolean {
        if (isInitialized) {
            Log.d(TAG, "TTS already initialized")
            return true
        }
        
        return try {
            // Check if model exists before attempting to load
            // Try tts_piper directory first (where VoicePipelineManager extracts)
            var piperFile = File(externalDir, "models/tts_piper/en_US-lessac-medium.onnx")
            if (!piperFile.exists()) {
                // Fallback to old path
                piperFile = File(externalDir, "models/tts/piper_model.onnx")
            }
            
            if (!piperFile.exists()) {
                Log.e(TAG, "Piper model not found at ${piperFile.absolutePath}")
                return false
            }
            
            Log.i(TAG, "Found Piper model: ${piperFile.absolutePath} (${piperFile.length() / 1024 / 1024}MB)")
            
            // Try to load the model
            val success = loadModel(piperFile, "Piper", 16000)
            
            if (success) {
                Log.i(TAG, "✓ TTS initialized successfully")
            } else {
                Log.e(TAG, "✗ TTS model loading returned false")
            }
            
            success
            
        } catch (e: ai.onnxruntime.OrtException) {
            Log.e(TAG, "ONNX Runtime error initializing TTS: ${e.message}", e)
            if (e.message?.contains("opset") == true) {
                Log.e(TAG, "Model opset version not supported. Need opset 15+")
            }
            false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory loading TTS model: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}", e)
            false
        }
    }
    
    /**
     * Load ONNX model with specified sample rate
     * 
     * WARNING: This performs heavy I/O and should be called from background thread
     */
    private fun loadModel(modelFile: File, type: String, sampleRateHz: Int): Boolean {
        Log.i(TAG, "Loading $type TTS model (${modelFile.length() / 1024 / 1024}MB)")
        
        // Check file is readable
        if (!modelFile.canRead()) {
            Log.e(TAG, "Cannot read model file: ${modelFile.absolutePath}")
            return false
        }
        
        sampleRate = sampleRateHz
        modelType = type
        
        return try {
            // Load native libraries first (critical!)
            loadNativeLibraries()
            
            // Create ONNX environment
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // Configure session options for mobile
            val sessionOptions = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)  // Limit threads for battery
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                // Disable memory pattern optimizations to save RAM
                setMemoryPatternOptimization(false)
            }
            
            Log.d(TAG, "Creating ONNX session...")
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
            
            Log.d(TAG, "Initializing AudioTrack...")
            initAudioTrack()
            
            isInitialized = true
            
            // Log model I/O for debugging
            ortSession?.let { session ->
                Log.i(TAG, "Model loaded successfully")
                Log.i(TAG, "  Inputs: ${session.inputNames}")
                Log.i(TAG, "  Outputs: ${session.outputNames}")
            }
            
            Log.i(TAG, "✓ $type TTS initialized successfully at ${sampleRateHz}Hz")
            true
            
        } catch (e: ai.onnxruntime.OrtException) {
            Log.e(TAG, "ONNX error loading $type: ${e.message}")
            if (e.message?.contains("opset") == true) {
                Log.e(TAG, "Model opset not supported. Piper models need ONNX Runtime 1.15+")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading TTS model: ${e.message}", e)
            false
        }
    }
    
    /**
     * Initialize AudioTrack for playback
     */
    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4  // Extra buffer for smooth playback
        
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            Log.d(TAG, "AudioTrack initialized: ${sampleRate}Hz, buffer: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
            throw e
        }
    }
    
    /**
     * Synthesize and speak text using real ONNX inference.
     */
    override fun speak(text: String, voiceId: String) {
        if (!isInitialized) {
            scope.launch(Dispatchers.Main) {
                callback?.onError("TTS not initialized")
            }
            return
        }
        
        if (isSpeaking) {
            stop()
        }
        
        isSpeaking = true
        
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    callback?.onStart()
                }
                synthesizeWithONNX(text)
                if (isSpeaking) {
                    withContext(Dispatchers.Main) {
                        callback?.onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("Speech synthesis failed: ${e.message}")
                }
            } finally {
                isSpeaking = false
            }
        }
    }
    
    /**
     * Real ONNX synthesis using Piper VITS model.
     * 
     * Piper VITS models expect:
     *   - input: int64 tensor of phoneme/character IDs [1, seq_len]
     *   - input_lengths: int64 tensor [1] with the sequence length
     *   - scales: float tensor [3] with noise_scale, length_scale, noise_w
     *   
     * Output:
     *   - audio: float tensor with raw waveform
     */
    private suspend fun synthesizeWithONNX(text: String) {
        val session = ortSession ?: return
        val env = ortEnvironment ?: return
        
        // Break text into sentences for more natural speech and streaming
        val sentences = splitIntoSentences(text)
        Log.i(TAG, "Synthesizing ${sentences.size} sentence(s)")
        
        for (sentence in sentences) {
            if (!isSpeaking) break
            if (sentence.isBlank()) continue
            
            try {
                synthesizeSentence(session, env, sentence)
            } catch (e: Exception) {
                Log.e(TAG, "Error synthesizing sentence: ${e.message}", e)
                // Continue with next sentence
            }
        }
    }
    
    /**
     * Synthesize a single sentence through the ONNX model.
     */
    private suspend fun synthesizeSentence(
        session: OrtSession,
        env: OrtEnvironment,
        text: String
    ) {
        // Convert text to phoneme/character IDs
        val phonemeIds = textToPhonemeIds(text)
        if (phonemeIds.isEmpty()) return
        
        Log.d(TAG, "Text: \"${text.take(50)}\" → ${phonemeIds.size} IDs")
        
        // Build input tensors based on model input names
        val inputNames = session.inputNames.toList()
        val inputs = mutableMapOf<String, OnnxTensor>()
        
        // Primary input: phoneme/character IDs [1, seq_len]
        val idsShape = longArrayOf(1, phonemeIds.size.toLong())
        val idsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(phonemeIds), idsShape
        )
        
        // Input lengths [1]
        val lengthsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(phonemeIds.size.toLong())), longArrayOf(1)
        )
        
        // Scales: [noise_scale, length_scale, noise_w] — controls speech variation and speed
        val scales = floatArrayOf(0.667f, 1.0f, 0.8f)
        val scalesTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(scales), longArrayOf(3)
        )
        
        try {
            // Map inputs to model input names
            for (name in inputNames) {
                val lower = name.lowercase()
                when {
                    lower.contains("input") && !lower.contains("length") -> inputs[name] = idsTensor
                    lower.contains("length") || lower.contains("len") -> inputs[name] = lengthsTensor
                    lower.contains("scale") || lower.contains("noise") -> inputs[name] = scalesTensor
                    lower.contains("sid") || lower.contains("speaker") -> {
                        // Speaker ID tensor (use 0 for default speaker)
                        val spkTensor = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1)
                        )
                        inputs[name] = spkTensor
                    }
                }
            }
            
            // Positional fallback if name matching didn't cover all inputs
            if (inputs.size < inputNames.size) {
                inputs.clear()
                when (inputNames.size) {
                    1 -> {
                        inputs[inputNames[0]] = idsTensor
                    }
                    2 -> {
                        inputs[inputNames[0]] = idsTensor
                        inputs[inputNames[1]] = lengthsTensor
                    }
                    3 -> {
                        inputs[inputNames[0]] = idsTensor
                        inputs[inputNames[1]] = lengthsTensor
                        inputs[inputNames[2]] = scalesTensor
                    }
                    else -> {
                        inputs[inputNames[0]] = idsTensor
                        inputs[inputNames[1]] = lengthsTensor
                        inputs[inputNames[2]] = scalesTensor
                        // Additional inputs get default values
                        for (i in 3 until inputNames.size) {
                            val defTensor = OnnxTensor.createTensor(
                                env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1)
                            )
                            inputs[inputNames[i]] = defTensor
                        }
                    }
                }
            }
            
            Log.d(TAG, "Running TTS inference with ${inputs.size} inputs")
            
            // Run ONNX inference
            val results = session.run(inputs)
            
            // Extract audio waveform from output
            val outputTensor = results[0] as OnnxTensor
            val audioFloat = outputTensor.floatBuffer
            val numSamples = audioFloat.remaining()
            val floatArray = FloatArray(numSamples)
            audioFloat.get(floatArray)
            
            Log.i(TAG, "Synthesized $numSamples samples (${numSamples / sampleRate.toFloat()}s)")
            
            results.close()
            
            // Convert float waveform to int16 PCM and play
            val pcmData = floatToPCM16(floatArray)
            playAudioStreaming(pcmData)
            
        } finally {
            idsTensor.close()
            lengthsTensor.close()
            scalesTensor.close()
            // Close any additional tensors in inputs
            for ((name, tensor) in inputs) {
                if (tensor !== idsTensor && tensor !== lengthsTensor && tensor !== scalesTensor) {
                    try { tensor.close() } catch (e: Exception) { /* ignore */ }
                }
            }
        }
    }
    
    /**
     * Convert text to phoneme/character IDs for Piper VITS.
     * 
     * Piper character-based models use:
     *   0 = PAD, 1 = BOS, 2 = EOS
     *   3+ = character codes (typically ASCII mapped with interspersed blanks)
     *   
     * Piper inserts a blank (PAD) between each character for VITS alignment.
     */
    private fun textToPhonemeIds(text: String): LongArray {
        val cleanText = text.trim().lowercase()
        if (cleanText.isEmpty()) return LongArray(0)
        
        val ids = mutableListOf<Long>()
        ids.add(BOS_ID)
        ids.add(PAD_ID)  // Blank after BOS
        
        for (char in cleanText) {
            val charId = charToId(char)
            if (charId >= 0) {
                ids.add(charId)
                ids.add(PAD_ID)  // Interspersed blank for VITS alignment
            }
        }
        
        ids.add(EOS_ID)
        return ids.toLongArray()
    }
    
    /**
     * Map a character to its Piper phoneme ID.
     * Piper's default character set follows espeak-ng IPA mapping,
     * but for basic English characters we use ASCII offset mapping.
     */
    private fun charToId(char: Char): Long {
        return when (char) {
            ' ' -> CHAR_OFFSET          // Space
            in 'a'..'z' -> CHAR_OFFSET + 1 + (char - 'a')   // Letters: a=4, b=5, ..., z=29
            in '0'..'9' -> CHAR_OFFSET + 27 + (char - '0')   // Digits: 0=30, ..., 9=39
            '.' -> CHAR_OFFSET + 37
            ',' -> CHAR_OFFSET + 38
            '!' -> CHAR_OFFSET + 39
            '?' -> CHAR_OFFSET + 40
            '\'' -> CHAR_OFFSET + 41
            '-' -> CHAR_OFFSET + 42
            ':' -> CHAR_OFFSET + 43
            ';' -> CHAR_OFFSET + 44
            '"' -> CHAR_OFFSET + 45
            '(' -> CHAR_OFFSET + 46
            ')' -> CHAR_OFFSET + 47
            else -> -1  // Skip unsupported characters
        }
    }
    
    /**
     * Convert float32 audio waveform [-1, 1] to int16 PCM.
     */
    private fun floatToPCM16(floatAudio: FloatArray): ShortArray {
        val pcm = ShortArray(floatAudio.size)
        for (i in floatAudio.indices) {
            // Clamp to [-1, 1] range and scale to int16
            val clamped = floatAudio[i].coerceIn(-1.0f, 1.0f)
            pcm[i] = (clamped * 32767).toInt().toShort()
        }
        return pcm
    }
    
    /**
     * Play PCM audio through AudioTrack in streaming chunks.
     * Also sends chunks through the callback for VoicePipelineManager.
     */
    private suspend fun playAudioStreaming(pcmData: ShortArray) {
        val track = audioTrack ?: return
        
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack: ${e.message}")
            // Reinitialize AudioTrack if it was in bad state
            initAudioTrack()
            audioTrack?.play()
        }
        
        // Stream in chunks for responsive interruption
        var offset = 0
        while (offset < pcmData.size && isSpeaking) {
            val chunkSize = minOf(PLAYBACK_CHUNK_SIZE, pcmData.size - offset)
            val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
            
            // Write to AudioTrack
            val bytes = shortArrayToByteArray(chunk)
            audioTrack?.write(bytes, 0, bytes.size)
            
            // Notify callback
            callback?.onChunk(chunk)
            
            offset += chunkSize
            
            // Small delay to prevent buffer overflow and allow interruption
            if (offset < pcmData.size) {
                delay(10)
            }
        }
    }
    
    /**
     * Convert ShortArray to ByteArray (16-bit little-endian PCM).
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
    
    /**
     * Split text into sentences for more natural TTS and streaming.
     */
    private fun splitIntoSentences(text: String): List<String> {
        if (text.length < 100) return listOf(text)
        
        val sentences = mutableListOf<String>()
        val delimiters = setOf('.', '!', '?', '\n')
        
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char in delimiters && current.length > 10) {
                sentences.add(current.toString().trim())
                current.clear()
            }
        }
        
        if (current.isNotBlank()) {
            sentences.add(current.toString().trim())
        }
        
        return sentences.ifEmpty { listOf(text) }
    }
    
    /**
     * Stop speaking
     */
    fun stop() {
        isSpeaking = false
        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
    }
    
    override fun destroy() {
        scope.cancel()
        stop()
        audioTrack?.release()
        audioTrack = null
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying TTS: ${e.message}")
        }
        isInitialized = false
    }
    
    override fun isAvailable(): Boolean = isInitialized
    override fun getName(): String = "$modelType TTS (ONNX)"
    override fun isSpeaking(): Boolean = isSpeaking
}

/**
 * Interface for dependency injection and testing
 */
interface TTSBridgeInterface {
    fun isAvailable(): Boolean
    fun getName(): String
    fun isSpeaking(): Boolean
    fun speak(text: String, voiceId: String = "default")
    fun destroy()
}
