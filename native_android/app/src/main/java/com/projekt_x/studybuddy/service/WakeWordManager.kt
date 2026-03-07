package com.projekt_x.studybuddy.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Wake Word Manager - Helper class to manage wake word detection
 * 
 * Handles starting/stopping the wake word service and checking permissions
 */
class WakeWordManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WakeWordManager"
        const val PREFS_NAME = "HeyNiloPrefs"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_PICOVOICE_ACCESS_KEY = "picovoice_access_key"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Check if wake word is enabled
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
    }
    
    /**
     * Enable/disable wake word detection
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        
        if (enabled) {
            startWakeWordService()
        } else {
            stopWakeWordService()
        }
    }
    
    /**
     * Check if battery optimization is disabled (required for background service)
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    /**
     * Get intent to request battery optimization exemption
     */
    fun getBatteryOptimizationIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
    }
    
    /**
     * Start the wake word service
     */
    fun startWakeWordService() {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot start wake word service - missing permissions")
            return
        }
        
        val intent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "✓ Wake word service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word service", e)
        }
    }
    
    /**
     * Stop the wake word service
     */
    fun stopWakeWordService() {
        val intent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        }
        
        try {
            context.startService(intent)
            Log.i(TAG, "✓ Wake word service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop wake word service", e)
        }
    }
    
    /**
     * Check if wake word service is currently running
     */
    fun isServiceRunning(): Boolean {
        return WakeWordService.isRunning
    }
    
    /**
     * Get/set Picovoice access key
     */
    fun getAccessKey(): String {
        return prefs.getString(KEY_PICOVOICE_ACCESS_KEY, "") ?: ""
    }
    
    fun setAccessKey(key: String) {
        prefs.edit().putString(KEY_PICOVOICE_ACCESS_KEY, key).apply()
    }
    
    fun hasAccessKey(): Boolean {
        return getAccessKey().isNotBlank()
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        return recordAudio
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.RECORD_AUDIO)
        }
        
        return missing
    }
}
