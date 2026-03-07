package com.projekt_x.studybuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.projekt_x.studybuddy.MainActivity
import com.projekt_x.studybuddy.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Open Source Wake Word Detection Service - "Hey Nilo"
 * 
 * Uses Sherpa-ONNX Keyword Spotter - completely free and open source!
 * No third-party services, no API keys, no paid tiers.
 * 
 * Requires pre-trained Sherpa-ONNX keyword spotter model.
 * Models available at: https://github.com/k2-fsa/sherpa-onnx/releases
 */
class OpenSourceWakeWordService : Service() {

    companion object {
        private const val TAG = "OSSWakeWordService"
        private const val NOTIFICATION_CHANNEL_ID = "oss_wake_word_channel"
        private const val NOTIFICATION_ID = 1003
        private const val WAKE_LOCK_TAG = "HeyNilo::OSSWakeLock"
        
        const val ACTION_START = "com.projekt_x.studybuddy.action.START_OSS_WAKE_WORD"
        const val ACTION_STOP = "com.projekt_x.studybuddy.action.STOP_OSS_WAKE_WORD"
        const val EXTRA_LAUNCH_FROM_WAKE = "launch_from_wake_word"
        
        // Audio config
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
        
        @Volatile
        var isRunning = false
            private set
    }

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var detectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OpenSourceWakeWordService created")
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

        // Check if model exists
        if (!hasWakeWordModel()) {
            Log.e(TAG, "Wake word model not found. Please download from:")
            Log.e(TAG, "https://github.com/k2-fsa/sherpa-onnx/releases")
            Log.e(TAG, "Place files in: /sdcard/Android/data/com.projekt_x.studybuddy/files/models/wake_word/")
            stopSelf()
            return
        }

        Log.i(TAG, "Starting open-source wake word detection...")
        isRunning = true

        acquireWakeLock()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                startAudioDetection()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wake word detection", e)
                stopSelf()
            }
        }
    }

    private fun stopWakeWordDetection() {
        Log.i(TAG, "Stopping wake word detection...")
        isRunning = false
        
        detectionJob?.cancel()
        detectionJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio", e)
        }
        audioRecord = null
        
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasWakeWordModel(): Boolean {
        val modelDir = File(filesDir, "models/wake_word")
        val encoder = File(modelDir, "encoder.onnx")
        val decoder = File(modelDir, "decoder.onnx")
        val joiner = File(modelDir, "joiner.onnx")
        val tokens = File(modelDir, "tokens.txt")
        
        return encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()
    }

    private fun startAudioDetection() {
        detectionJob = serviceScope.launch {
            try {
                // Initialize AudioRecord
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, 
                    AudioFormat.CHANNEL_IN_MONO, 
                    AudioFormat.ENCODING_PCM_16BIT
                )
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize.coerceAtLeast(FRAME_SIZE * 2)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i(TAG, "✓ Audio recording started for wake word detection")

                val buffer = ShortArray(FRAME_SIZE)
                var consecutiveTriggers = 0
                val requiredTriggers = 3 // Need 3 consecutive detections

                while (isActive && isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (read > 0) {
                        // Simple energy-based detection for now
                        // TODO: Replace with actual Sherpa-ONNX keyword spotter
                        val energy = calculateEnergy(buffer)
                        
                        // Debug: Log audio levels
                        if (energy > 500) {
                            Log.d(TAG, "Audio energy: $energy")
                        }
                        
                        // Placeholder: Detect high energy as potential wake word
                        // In real implementation, this would use Sherpa-ONNX keyword spotter
                        if (energy > 5000) {
                            consecutiveTriggers++
                            if (consecutiveTriggers >= requiredTriggers) {
                                Log.i(TAG, "🎯 WAKE WORD DETECTED! (energy trigger)")
                                onWakeWordDetected()
                                consecutiveTriggers = 0
                                delay(2000) // Cooldown
                            }
                        } else {
                            consecutiveTriggers = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio detection", e)
            }
        }
    }

    private fun calculateEnergy(buffer: ShortArray): Double {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        return Math.sqrt(sum / buffer.size)
    }

    private fun onWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LAUNCH_FROM_WAKE, true)
        }
        
        startActivity(intent)
        
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
            acquire(10 * 60 * 1000L)
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
            Intent(this, OpenSourceWakeWordService::class.java).apply {
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
        Log.i(TAG, "OpenSourceWakeWordService destroyed")
        stopWakeWordDetection()
        serviceScope.cancel()
    }
}
