package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Sherpa-ONNX STT Bridge - Placeholder for future implementation
 * 
 * For now, this returns false from initialize() so VoicePipelineManager
 * falls back to MockSTTBridge which provides simulated transcription.
 * 
 * TODO: Implement proper Sherpa-ONNX JNI bindings
 */
class SherpaSTTBridge(private val context: Context) : STTBridgeInterface {
    
    companion object {
        private const val TAG = "SherpaSTTBridge"
        private const val SAMPLE_RATE = 16000
    }
    
    interface TranscriptionCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }
    
    var callback: TranscriptionCallback? = null
    
    /**
     * Initialize - currently returns false to trigger fallback to MockSTTBridge
     */
    fun initialize(externalDir: File): Boolean {
        Log.i(TAG, "Sherpa-ONNX STT not yet implemented, using MockSTTBridge fallback")
        // TODO: Implement Sherpa-ONNX JNI bindings
        // For now, return false so VoicePipelineManager uses MockSTTBridge
        return false
    }
    
    fun startRecognition() {
        Log.w(TAG, "Not implemented")
    }
    
    fun processAudioChunk(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        // No-op
    }
    
    fun stopRecognition(): String {
        return ""
    }
    
    fun transcribeAudio(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String? {
        return null
    }
    
    fun isRecognizing(): Boolean = false
    
    fun release() {
        // No-op
    }
    
    // Interface implementations
    override fun isAvailable(): Boolean = false
    override fun getName(): String = "Sherpa-ONNX (Not Implemented)"
    fun getBridgeName(): String = getName()
}
