package com.projekt_x.studybuddy.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility to extract models from app assets to external storage
 * where the native bridges can access them.
 */
object ModelExtractor {
    private const val TAG = "ModelExtractor"
    
    // Model paths in assets and their destination paths
    private val MODELS = mapOf(
        // VAD (Silero VAD)
        "models/vad/silero_vad.onnx" to "models/vad/silero_vad.onnx",
        
        // STT (Sherpa-ONNX - replaces Moonshine for reliability)
        // Whisper Tiny model files
        "models/sherpa/encoder.onnx" to "models/sherpa/encoder.onnx",
        "models/sherpa/decoder.onnx" to "models/sherpa/decoder.onnx",
        "models/sherpa/tokens.txt" to "models/sherpa/tokens.txt",
        
        // Alternative: Paraformer model (smaller, faster)
        // "models/sherpa/model.onnx" to "models/sherpa/model.onnx",
        // "models/sherpa/tokens.txt" to "models/sherpa/tokens.txt",
        
        // TTS (Android built-in - no models needed)
    )
    
    /**
     * Extract all voice models from assets to external storage.
     * Only extracts if the model doesn't already exist or force is true.
     * 
     * @param context Application context
     * @param force If true, overwrite existing models
     * @return Number of models extracted
     */
    fun extractAllModels(context: Context, force: Boolean = false): Int {
        val externalDir = context.getExternalFilesDir(null) ?: run {
            Log.e(TAG, "Cannot get external files directory")
            return 0
        }
        
        var extractedCount = 0
        
        MODELS.forEach { (assetPath, destPath) ->
            val destFile = File(externalDir, destPath)
            
            if (extractAsset(context, assetPath, destFile, force)) {
                extractedCount++
            }
        }
        
        Log.i(TAG, "Extracted $extractedCount models")
        return extractedCount
    }
    
    /**
     * Extract a single asset to a destination file.
     * 
     * @param context Application context
     * @param assetPath Path in assets
     * @param destFile Destination file
     * @param force If true, overwrite existing file
     * @return true if extraction succeeded or file already exists
     */
    private fun extractAsset(
        context: Context, 
        assetPath: String, 
        destFile: File, 
        force: Boolean
    ): Boolean {
        // Check if already exists and is valid (not corrupted HTML)
        if (destFile.exists() && !force) {
            // Check if file is corrupted (HTML instead of binary ONNX)
            if (isFileCorrupted(destFile)) {
                Log.w(TAG, "Model file is corrupted (HTML), re-extracting: ${destFile.name}")
                destFile.delete()
            } else {
                Log.d(TAG, "Model already exists: ${destFile.name}")
                return true
            }
        }
        
        // Check if asset exists
        if (!assetExists(context, assetPath)) {
            Log.w(TAG, "Asset not found: $assetPath")
            return false
        }
        
        return try {
            // Create parent directories
            destFile.parentFile?.mkdirs()
            
            // If it's a directory, extract recursively
            if (isAssetDirectory(context, assetPath)) {
                extractAssetDirectory(context, assetPath, destFile, force)
            } else {
                // Extract single file
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Extracted: $assetPath -> ${destFile.absolutePath}")
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract $assetPath: ${e.message}")
            false
        }
    }
    
    /**
     * Check if a file is corrupted (e.g., HTML instead of binary ONNX).
     * Reads first few bytes to detect HTML tags.
     */
    private fun isFileCorrupted(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(20)
                val read = input.read(header)
                if (read < 4) return true // Too small
                
                val headerStr = String(header, 0, read, Charsets.UTF_8).trim().lowercase()
                // Check for HTML indicators
                headerStr.startsWith("<html") || 
                headerStr.startsWith("<!doctype") ||
                headerStr.startsWith("<!") ||
                headerStr.contains("<!doctype html>")
            }
        } catch (e: Exception) {
            true // If we can't read it, consider it corrupted
        }
    }
    
    /**
     * Check if an asset exists.
     */
    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.list(path)?.isNotEmpty() == true || 
            context.assets.open(path).use { true }
        } catch (e: IOException) {
            false
        }
    }
    
    /**
     * Check if an asset path is a directory.
     */
    private fun isAssetDirectory(context: Context, path: String): Boolean {
        return try {
            context.assets.list(path)?.isNotEmpty() == true
        } catch (e: IOException) {
            false
        }
    }
    
    /**
     * Extract an asset directory recursively.
     */
    private fun extractAssetDirectory(
        context: Context,
        assetPath: String,
        destDir: File,
        force: Boolean
    ) {
        destDir.mkdirs()
        
        val children = context.assets.list(assetPath) ?: return
        
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDestFile = File(destDir, child)
            
            extractAsset(context, childAssetPath, childDestFile, force)
        }
    }
    
    /**
     * Check which models are available in assets.
     */
    fun checkAvailableAssets(context: Context): Map<String, Boolean> {
        return MODELS.keys.associateWith { assetExists(context, it) }
    }
    
    /**
     * Get total size of all models in assets.
     */
    fun getTotalAssetSize(context: Context): Long {
        return MODELS.keys.sumOf { getAssetSize(context, it) }
    }
    
    /**
     * Get size of a single asset.
     */
    private fun getAssetSize(context: Context, assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            0L
        }
    }
}
