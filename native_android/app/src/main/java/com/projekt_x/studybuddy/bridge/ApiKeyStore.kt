package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.projekt_x.studybuddy.bridge.llm.ApiProvider

/**
 * Secure API Key Storage using EncryptedSharedPreferences
 */
class ApiKeyStore(private val context: Context) {
    
    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PREFS_FILE = "hey_nilo_api_keys"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
    }
    
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: EncryptedSharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }
    
    fun saveApiKey(provider: ApiProvider, apiKey: String, modelName: String = ""): Boolean {
        return try {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isBlank()) {
                Log.w(TAG, "Cannot save blank API key")
                return false
            }
            
            encryptedPrefs.edit()
                .putString(KEY_PROVIDER, provider.name)
                .putString(KEY_API_KEY, trimmedKey)
                .putString(KEY_MODEL_NAME, modelName)
                .apply()
            
            Log.i(TAG, "API key saved for ${provider.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key", e)
            false
        }
    }
    
    fun getApiKey(): String? {
        return try {
            encryptedPrefs.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve API key", e)
            null
        }
    }
    
    fun getSavedProvider(): ApiProvider? {
        return try {
            val providerName = encryptedPrefs.getString(KEY_PROVIDER, null)
            if (providerName != null) {
                ApiProvider.valueOf(providerName)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve provider", e)
            null
        }
    }
    
    fun getSavedModelName(): String {
        return encryptedPrefs.getString(KEY_MODEL_NAME, "") ?: ""
    }
    
    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }
    
    fun deleteApiKey(): Boolean {
        return try {
            encryptedPrefs.edit()
                .remove(KEY_PROVIDER)
                .remove(KEY_API_KEY)
                .remove(KEY_MODEL_NAME)
                .apply()
            
            Log.i(TAG, "API key deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API key", e)
            false
        }
    }
    
    fun getCredentialsSummary(): String? {
        val provider = getSavedProvider()
        val key = getApiKey()
        
        return if (provider != null && key != null) {
            val maskedKey = key.take(8) + "..." + key.takeLast(4)
            "${provider.name}: $maskedKey"
        } else {
            null
        }
    }
}
