package com.projekt_x.studybuddy.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Simple manager for custom user-provided GGUF models
 * Allows users to load their own models from any file path
 */
object CustomModelManager {
    private const val TAG = "CustomModelManager"
    private const val PREFS_NAME = "custom_models"
    private const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
    private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
    
    data class CustomModel(
        val path: String,
        val name: String
    ) {
        fun exists(): Boolean = File(path).exists()
        fun sizeGB(): Float = try {
            File(path).length() / (1024f * 1024f * 1024f)
        } catch (e: Exception) {
            0f
        }
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveCustomModel(context: Context, path: String, name: String) {
        getPrefs(context).edit().apply {
            putString(KEY_CUSTOM_MODEL_PATH, path)
            putString(KEY_CUSTOM_MODEL_NAME, name)
            apply()
        }
        Log.i(TAG, "Saved custom model: $name at $path")
    }
    
    fun getCustomModel(context: Context): CustomModel? {
        val prefs = getPrefs(context)
        val path = prefs.getString(KEY_CUSTOM_MODEL_PATH, null) ?: return null
        val name = prefs.getString(KEY_CUSTOM_MODEL_NAME, null) ?: File(path).name
        return CustomModel(path, name)
    }
    
    fun clearCustomModel(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    fun isValidModelFile(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.canRead() && 
               path.endsWith(".gguf", ignoreCase = true)
    }
}
