package com.projekt_x.studybuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import com.projekt_x.studybuddy.MainActivity
import com.projekt_x.studybuddy.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Wake Word Detection Service - "Hey Nilo"
 * 
 * This service runs in the foreground and continuously listens for the wake word.
 * When detected, it launches the app in voice mode.
 * 
 * Uses Porcupine for efficient on-device wake word detection.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "HeyNilo::WakeLock"
        
        const val ACTION_START = "com.projekt_x.studybuddy.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.projekt_x.studybuddy.action.STOP_WAKE_WORD"
        const val EXTRA_LAUNCH_FROM_WAKE = "launch_from_wake_word"
        
        @Volatile
        var isRunning = false
            private set
    }

    private var porcupineManager: PorcupineManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WakeWordService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWakeWordDetection()
            ACTION_STOP -> stopWakeWordDetection()
            else -> startWakeWordDetection()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWakeWordDetection() {
        if (isRunning) {
            Log.w(TAG, "Wake word detection already running")
            return
        }

        Log.i(TAG, "Starting wake word detection...")
        isRunning = true

        // Acquire wake lock to keep CPU running
        acquireWakeLock()

        // Start as foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize Porcupine in background
        serviceScope.launch {
            try {
                initializePorcupine()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wake word detection", e)
                stopSelf()
            }
        }
    }

    private fun stopWakeWordDetection() {
        Log.i(TAG, "Stopping wake word detection...")
        isRunning = false
        
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Porcupine", e)
        }
        porcupineManager = null
        
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun initializePorcupine() {
        try {
            // Get access key from preferences
            val prefs = getSharedPreferences(WakeWordManager.PREFS_NAME, Context.MODE_PRIVATE)
            val accessKey = prefs.getString(WakeWordManager.KEY_PICOVOICE_ACCESS_KEY, "") ?: ""
            
            if (accessKey.isBlank()) {
                Log.e(TAG, "No Picovoice access key configured")
                throw IllegalStateException("Access key required")
            }

            // Extract or get the wake word model path
            val keywordPath = extractWakeWordModel()
            
            // Create the wake word callback
            val wakeWordCallback = PorcupineManagerCallback {
                Log.i(TAG, "🎯 WAKE WORD DETECTED! Launching app...")
                onWakeWordDetected()
            }

            // Build Porcupine manager with wake word
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordPath)
                .setSensitivity(0.7f)
                .build(applicationContext, wakeWordCallback)
            
            // Start listening
            porcupineManager?.start()
            
            Log.i(TAG, "✓ Porcupine wake word initialized and listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Porcupine", e)
            throw e
        }
    }

    private fun extractWakeWordModel(): String {
        val modelFile = File(filesDir, "hey-nilo-android.ppn")
        
        // If already extracted, return path
        if (modelFile.exists()) {
            return modelFile.absolutePath
        }

        // Copy from assets
        try {
            assets.open("models/wake_word/hey-nilo-android.ppn").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "✓ Wake word model extracted to ${modelFile.absolutePath}")
            return modelFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract custom wake word model from assets", e)
            // If custom model not found, the Porcupine builder will fail
            // This is expected until user trains their wake word
            throw IllegalStateException("Wake word model not found. Please train 'Hey Nilo' at picovoice.ai", e)
        }
    }

    private fun onWakeWordDetected() {
        // Launch MainActivity in voice mode
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LAUNCH_FROM_WAKE, true)
        }
        
        startActivity(intent)
        
        // Vibrate to give feedback
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(200, 100))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(200)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes, will refresh
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for 'Hey Nilo' wake word"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Hey Nilo")
            .setContentText("Listening for wake word...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WakeWordService destroyed")
        stopWakeWordDetection()
        serviceScope.cancel()
    }
}
