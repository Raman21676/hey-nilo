package com.projekt_x.studybuddy.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Utility for copying asset files to external storage.
 * Used for ML models that need to be accessed as regular files.
 */
class CopyAssetToExternal(private val context: Context) {
    
    companion object {
        private const val TAG = "CopyAssetToExternal"
        private const val BUFFER_SIZE = 8192
    }
    
    /**
     * Get the external files directory for storing models
     */
    fun getExternalFilesDir(): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
    
    /**
     * Copy an asset file to external storage if it doesn't exist or has changed.
     * @param assetPath Path to the asset (e.g., "models/vad/silero_vad.onnx")
     * @param destFile Destination file
     * @return true if successful
     */
    fun copyAssetToExternal(assetPath: String, destFile: File): Boolean {
        try {
            // Check if asset exists
            val assetManager = context.assets
            val assetList = assetManager.list(assetPath.substringBeforeLast("/")) ?: emptyArray()
            val assetName = assetPath.substringAfterLast("/")
            
            if (assetName !in assetList) {
                Log.d(TAG, "Asset not found: $assetPath")
                return false
            }
            
            // Create parent directories
            destFile.parentFile?.mkdirs()
            
            // Check if file already exists with same size
            if (destFile.exists()) {
                val assetSize = assetManager.open(assetPath).use { it.available() }
                if (destFile.length() == assetSize.toLong()) {
                    Log.d(TAG, "Asset already exists with same size: $assetPath")
                    return true
                }
                Log.d(TAG, "Asset exists but size differs, re-copying: $assetPath")
            }
            
            // Copy file
            assetManager.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            
            Log.i(TAG, "Copied asset to: ${destFile.absolutePath}")
            return true
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset $assetPath: ${e.message}")
            return false
        }
    }
    
    /**
     * Copy all assets from a directory to external storage
     */
    fun copyAssetsDirectory(assetDir: String, destDir: File): Boolean {
        return try {
            val assetManager = context.assets
            val files = assetManager.list(assetDir) ?: return false
            
            destDir.mkdirs()
            
            for (file in files) {
                val assetPath = "$assetDir/$file"
                val destFile = File(destDir, file)
                copyAssetToExternal(assetPath, destFile)
            }
            
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets from $assetDir: ${e.message}")
            false
        }
    }
    
    /**
     * Calculate SHA-256 hash of a file
     */
    fun calculateFileHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate hash: ${e.message}")
            null
        }
    }
}
