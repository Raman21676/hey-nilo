package com.projekt_x.studybuddy.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.projekt_x.studybuddy.bridge.llm.AppMode
import com.projekt_x.studybuddy.bridge.llm.ApiProvider
import com.projekt_x.studybuddy.bridge.llm.ProviderConfig
import com.projekt_x.studybuddy.model.OfflineModelConfig
import com.projekt_x.studybuddy.model.ModelCategory

private const val TAG = "LastModelPreference"
private const val PREFS_NAME = "last_model_prefs"

/**
 * Saves and restores the last used model configuration
 * Allows users to quickly resume with their previous selection
 */
class LastModelPreference(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    data class SavedConfig(
        val mode: AppMode,
        val offlineModel: OfflineModelConfig? = null,
        val onlineConfig: ProviderConfig? = null,
        val isCustomModel: Boolean = false,
        val customModelPath: String? = null
    )
    
    /**
     * Save the last used offline model configuration
     */
    fun saveOfflineModel(model: OfflineModelConfig, isCustom: Boolean = false, customPath: String? = null) {
        val isHf = model.id.startsWith("hf_")
        prefs.edit().apply {
            putString("mode", if (isHf) "HuggingFace" else "Offline")
            putString("model_id", model.id)
            putString("model_fileName", model.fileName)
            putString("model_displayName", model.displayName)
            putFloat("model_sizeGB", model.sizeGB)
            putInt("model_minRamGB", model.minRamGB)
            putBoolean("model_isRecommended", model.isRecommended)
            putString("model_description", model.description)
            putString("model_downloadUrl", model.downloadUrl)
            putString("model_category", model.category.name)
            putBoolean("is_custom_model", isCustom)
            putString("custom_model_path", customPath)
            // Clear online config
            remove("online_provider")
            remove("online_apiKey")
            remove("online_modelName")
            apply()
        }
        Log.i(TAG, "Saved offline model: ${model.displayName} (custom=$isCustom, hf=$isHf)")
    }
    
    /**
     * Save the last used online configuration
     */
    fun saveOnlineConfig(config: ProviderConfig) {
        prefs.edit().apply {
            putString("mode", "Online")
            putString("online_provider", config.provider.name)
            putString("online_apiKey", config.apiKey)
            putString("online_modelName", config.modelName)
            // Clear offline config
            remove("model_id")
            remove("model_fileName")
            remove("model_displayName")
            remove("is_custom_model")
            remove("custom_model_path")
            apply()
        }
        Log.i(TAG, "Saved online config: ${config.provider.name}")
    }
    
    /**
     * Check if there's a saved configuration
     */
    fun hasSavedConfig(): Boolean {
        return prefs.contains("mode")
    }
    
    /**
     * Get the saved configuration
     */
    fun getSavedConfig(context: Context): SavedConfig? {
        val modeName = prefs.getString("mode", null) ?: return null
        
        return when (modeName) {
            "Offline", "HuggingFace" -> {
                val modelId = prefs.getString("model_id", null) ?: return null
                val fileName = prefs.getString("model_fileName", "") ?: ""
                val displayName = prefs.getString("model_displayName", "") ?: ""
                val sizeGB = prefs.getFloat("model_sizeGB", 0f)
                val minRamGB = prefs.getInt("model_minRamGB", 4)
                val isRecommended = prefs.getBoolean("model_isRecommended", false)
                val description = prefs.getString("model_description", "") ?: ""
                val downloadUrl = prefs.getString("model_downloadUrl", "") ?: ""
                val categoryName = prefs.getString("model_category", "GENERAL") ?: "GENERAL"
                val isCustom = prefs.getBoolean("is_custom_model", false)
                val customPath = prefs.getString("custom_model_path", null)
                
                val category = try {
                    ModelCategory.valueOf(categoryName)
                } catch (e: Exception) {
                    ModelCategory.GENERAL
                }
                
                val model = OfflineModelConfig(
                    id = modelId,
                    displayName = displayName,
                    fileName = fileName,
                    sizeGB = sizeGB,
                    minRamGB = minRamGB,
                    isRecommended = isRecommended,
                    description = description,
                    downloadUrl = downloadUrl,
                    category = category
                )
                
                val isHf = modelId.startsWith("hf_")
                val modelPath = when {
                    isCustom && customPath != null -> customPath
                    isHf -> java.io.File(context.getExternalFilesDir(null), "models/hf_downloads/${model.fileName}").absolutePath
                    else -> java.io.File(context.getExternalFilesDir(null), "models/${model.fileName}").absolutePath
                }
                
                // Verify the file still exists
                val file = java.io.File(modelPath)
                if (!file.exists()) {
                    Log.w(TAG, "Model file no longer exists: $modelPath")
                    // Clear saved config since model is gone
                    clear()
                    return null
                }
                
                SavedConfig(
                    mode = if (isHf) AppMode.HuggingFace else AppMode.Offline,
                    offlineModel = model,
                    isCustomModel = isCustom,
                    customModelPath = customPath
                )
            }
            "Online" -> {
                val providerName = prefs.getString("online_provider", null) ?: return null
                val apiKey = prefs.getString("online_apiKey", null) ?: return null
                val modelName = prefs.getString("online_modelName", null) ?: return null
                
                val provider = try {
                    ApiProvider.valueOf(providerName)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid provider: $providerName")
                    return null
                }
                
                SavedConfig(
                    mode = AppMode.Online(provider),
                    onlineConfig = ProviderConfig(
                        provider = provider,
                        apiKey = apiKey,
                        modelName = modelName,
                        enabled = true
                    )
                )
            }
            else -> null
        }
    }
    
    /**
     * Get display name for the saved config (for dialog)
     */
    fun getSavedConfigDisplayName(context: Context): String? {
        val config = getSavedConfig(context) ?: return null
        return when (config.mode) {
            is AppMode.Offline -> {
                val modelName = config.offlineModel?.displayName ?: "Unknown Model"
                if (config.isCustomModel) "Custom: $modelName" else modelName
            }
            is AppMode.HuggingFace -> {
                val modelName = config.offlineModel?.displayName ?: "Unknown Model"
                "Hugging Face: $modelName"
            }
            is AppMode.Online -> {
                val provider = config.onlineConfig?.provider?.name ?: "Unknown"
                "Online: $provider"
            }
        }
    }
    
    /**
     * Clear saved configuration
     */
    fun clear() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Cleared saved config")
    }
}
