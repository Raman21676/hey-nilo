package com.projekt_x.studybuddy.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Wake Word Manager - Helper class to manage wake word detection
 * 
 * Uses open-source Sherpa-ONNX - completely free, no third-party services!
 */
class WakeWordManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WakeWordManager"
        const val PREFS_NAME = "HeyNiloPrefs"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
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
     * Start the wake word service
     */
    fun startWakeWordService() {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot start wake word service - missing permissions")
            return
        }
        
        val intent = Intent(context, OpenSourceWakeWordService::class.java).apply {
            action = OpenSourceWakeWordService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "✓ Open source wake word service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word service", e)
        }
    }
    
    /**
     * Stop the wake word service
     */
    fun stopWakeWordService() {
        val intent = Intent(context, OpenSourceWakeWordService::class.java).apply {
            action = OpenSourceWakeWordService.ACTION_STOP
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
        return OpenSourceWakeWordService.isRunning
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        val recordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        return recordAudio
    }
}
