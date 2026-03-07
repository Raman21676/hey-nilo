package com.projekt_x.studybuddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot Receiver - Automatically starts wake word detection service on device boot
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "HeyNiloPrefs"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed received")
            
            // Check if wake word was enabled before reboot
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
            
            if (wakeWordEnabled) {
                Log.i(TAG, "Auto-starting wake word service...")
                startWakeWordService(context)
            } else {
                Log.i(TAG, "Wake word not enabled, skipping auto-start")
            }
        }
    }

    private fun startWakeWordService(context: Context) {
        val serviceIntent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "✓ Wake word service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word service", e)
        }
    }
}
