package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Sherpa-ONNX TTS Bridge using official Java bindings
 * 
 * Uses libsherpa-onnx-jni.so with Piper VITS models
 * 
 * Required files (from assets/models/tts_piper/):
 * - en_US-lessac-medium.onnx
 * - tokens.txt
 * - espeak-ng-data/ (directory with phoneme data)
 */
class SherpaTTSBridge(private val context: Context) : TTSBridgeInterface {
    
    companion object {
        private const val TAG = "SherpaTTSBridge"
        private const val DEFAULT_SAMPLE_RATE = 22050
        private const val PLAYBACK_CHUNK_SIZE = 4096
        
        init {
            try {
                System.loadLibrary("sherpa-onnx-jni")
                Log.d(TAG, "Loaded sherpa-onnx-jni")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load sherpa-onnx-jni: ${e.message}")
            }
        }
    }
    
    private var tts: OfflineTts? = null
    private var isInitialized = false
    private var isSpeaking = false
    private var sampleRate = DEFAULT_SAMPLE_RATE
    
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    interface TTSCallback {
        fun onStart()
        fun onAudioData(audioData: ShortArray)
        fun onComplete()
        fun onError(error: String)
    }
    
    var callback: TTSCallback? = null
    
    /**
     * Initialize TTS with model from external storage
     */
    fun initialize(externalDir: File): Boolean {
        if (isInitialized) return true
        
        return try {
            val modelDir = File(externalDir, "models/tts_piper")
            
            // Look for model file
            val modelFile = File(modelDir, "en_US-lessac-medium.onnx")
            val tokensFile = File(modelDir, "tokens.txt")
            val dataDir = File(modelDir, "espeak-ng-data")
            
            if (!modelFile.exists()) {
                Log.e(TAG, "TTS model not found: ${modelFile.absolutePath}")
                return false
            }
            
            if (!tokensFile.exists()) {
                Log.e(TAG, "TTS tokens not found: ${tokensFile.absolutePath}")
                return false
            }
            
            Log.i(TAG, "Found TTS model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
            Log.i(TAG, "Found tokens: ${tokensFile.name} (${tokensFile.length() / 1024}KB)")
            Log.i(TAG, "Data dir exists: ${dataDir.exists()}")
            
            // Build VITS model config using official Java API
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false
            )
            
            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                maxNumSentences = 1
            )
            
            // Create TTS using official Java bindings
            tts = OfflineTts.create(ttsConfig)
            
            // Get sample rate
            sampleRate = tts!!.sampleRate
            Log.i(TAG, "TTS sample rate: $sampleRate Hz")
            
            // Initialize AudioTrack for playback
            initAudioTrack()
            
            isInitialized = true
            Log.i(TAG, "✓ Sherpa TTS initialized with official Java bindings")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}", e)
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
        ) * 4
        
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
            
            Log.d(TAG, "AudioTrack initialized: ${sampleRate}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
            throw e
        }
    }
    
    /**
     * Speak text using TTS
     */
    override fun speak(text: String, voiceId: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized")
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
                
                Log.i(TAG, "Generating speech for: $text")
                
                // Generate speech using official Java API
                val audio = tts!!.generate(text, sid = 0, speed = 1.0f)
                
                if (audio != null && audio.samples.isNotEmpty()) {
                    Log.i(TAG, "Generated ${audio.samples.size} samples, playing...")
                    
                    // Convert float samples to short
                    val pcmData = ShortArray(audio.samples.size) { i ->
                        val sample = audio.samples[i].coerceIn(-1.0f, 1.0f)
                        (sample * 32767).toInt().toShort()
                    }
                    
                    playAudio(pcmData)
                } else {
                    Log.e(TAG, "Failed to generate speech (null or empty)")
                    withContext(Dispatchers.Main) {
                        callback?.onError("Failed to generate speech")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    callback?.onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("TTS failed: ${e.message}")
                }
            } finally {
                isSpeaking = false
            }
        }
    }
    
    /**
     * Play PCM audio data
     */
    private suspend fun playAudio(pcmData: ShortArray) {
        val track = audioTrack ?: return
        
        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack: ${e.message}")
            return
        }
        
        // Stream in chunks
        var offset = 0
        while (offset < pcmData.size && isSpeaking) {
            val chunkSize = minOf(PLAYBACK_CHUNK_SIZE, pcmData.size - offset)
            val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
            
            // Convert ShortArray to ByteArray (16-bit PCM to bytes)
            val bytes = ByteArray(chunk.size * 2)
            for (i in chunk.indices) {
                bytes[i * 2] = (chunk[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (chunk[i].toInt() shr 8 and 0xFF).toByte()
            }
            
            track.write(bytes, 0, bytes.size)
            callback?.onAudioData(chunk)
            
            offset += chunkSize
            
            if (offset < pcmData.size) {
                delay(10)
            }
        }
        
        // Wait for playback to complete
        if (isSpeaking) {
            val durationMs = (pcmData.size * 1000L) / sampleRate
            delay(durationMs)
        }
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
    
    /**
     * Release resources
     */
    override fun destroy() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
        
        tts?.release()
        tts = null
        
        isInitialized = false
        Log.i(TAG, "Sherpa TTS released")
    }
    
    override fun isAvailable(): Boolean = isInitialized
    override fun getName(): String = "Sherpa-ONNX Piper TTS (Java Bindings)"
    override fun isSpeaking(): Boolean = isSpeaking
}
