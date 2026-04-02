package com.projekt_x.studybuddy.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Manager for custom user-provided GGUF models
 * Allows users to load their own models from any file path
 */
object CustomModelManager {
    private const val TAG = "CustomModelManager"
    private const val PREFS_NAME = "custom_models"
    private const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
    private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
    private const val KEY_CUSTOM_MODEL_SIZE = "custom_model_size"
    
    /**
     * Data class representing a custom model
     */
    data class CustomModel(
        val path: String,
        val name: String,
        val sizeGB: Float
    ) {
        fun toOfflineModelConfig(): OfflineModelConfig {
            return OfflineModelConfig(
                id = "custom_${System.currentTimeMillis()}",
                displayName = name,
                fileName = File(path).name,
                sizeGB = sizeGB,
                minRamGB = 3, // Assume minimum 3GB for custom models
                isRecommended = false,
                description = "Custom user-provided model at: $path",
                downloadUrl = "", // No download URL for custom models
                category = ModelCategory.GENERAL
            )
        }
        
        fun exists(): Boolean {
            return File(path).exists()
        }
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Save a custom model path
     */
    fun saveCustomModel(context: Context, path: String, name: String, sizeGB: Float) {
        getPrefs(context).edit().apply {
            putString(KEY_CUSTOM_MODEL_PATH, path)
            putString(KEY_CUSTOM_MODEL_NAME, name)
            putFloat(KEY_CUSTOM_MODEL_SIZE, sizeGB)
            apply()
        }
        Log.i(TAG, "Saved custom model: $name at $path (${sizeGB}GB)")
    }
    
    /**
     * Get the saved custom model
     */
    fun getCustomModel(context: Context): CustomModel? {
        val prefs = getPrefs(context)
        val path = prefs.getString(KEY_CUSTOM_MODEL_PATH, null)
        val name = prefs.getString(KEY_CUSTOM_MODEL_NAME, null)
        val sizeGB = prefs.getFloat(KEY_CUSTOM_MODEL_SIZE, 0f)
        
        return if (path != null && name != null) {
            CustomModel(path, name, sizeGB)
        } else {
            null
        }
    }
    
    /**
     * Clear the saved custom model
     */
    fun clearCustomModel(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.i(TAG, "Cleared custom model")
    }
    
    /**
     * Check if a custom model is configured and exists
     */
    fun hasValidCustomModel(context: Context): Boolean {
        return getCustomModel(context)?.exists() ?: false
    }
    
    /**
     * Get file size in GB from path
     */
    fun getFileSizeGB(path: String): Float {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.length() / (1024f * 1024f * 1024f)
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size: ${e.message}")
            0f
        }
    }
    
    /**
     * Validate if a file path is a valid GGUF model
     */
    fun isValidModelFile(path: String): Boolean {
        val file = File(path)
        return file.exists() && 
               file.isFile && 
               file.canRead() &&
               path.endsWith(".gguf", ignoreCase = true)
    }
}
