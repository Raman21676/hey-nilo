package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MemoryInitialization - Handles memory system startup
 * 
 * Call this from Application.onCreate() or MainActivity.onCreate()
 * to ensure memory system is ready before use.
 */
object MemoryInitialization {

    private const val TAG = "MemoryInitialization"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var fileSystemManager: FileSystemManager? = null

    /**
     * Initialize memory system on app startup
     * 
     * @param context Application context
     * @param onComplete Callback with success/failure result
     */
    fun initialize(context: Context, onComplete: (Boolean) -> Unit = {}) {
        if (isInitialized) {
            Log.d(TAG, "Memory system already initialized")
            onComplete(true)
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "Starting memory system initialization...")
                
                // Create file system manager
                val fsManager = FileSystemManager(context)
                fileSystemManager = fsManager
                
                // Initialize file system (create directories and default files)
                val success = fsManager.initialize()
                
                if (success) {
                    isInitialized = true
                    Log.i(TAG, "✅ Memory system initialized successfully")
                    
                    // Log storage info
                    val storageUsed = fsManager.calculateStorageUsed()
                    Log.i(TAG, "Storage used: $storageUsed bytes")
                    
                    onComplete(true)
                } else {
                    Log.e(TAG, "❌ Failed to initialize memory system")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing memory system", e)
                onComplete(false)
            }
        }
    }

    /**
     * Check if memory system is initialized
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Get FileSystemManager instance
     * Throws if not initialized
     */
    fun getFileSystemManager(): FileSystemManager {
        return fileSystemManager ?: throw IllegalStateException(
            "Memory system not initialized. Call initialize() first."
        )
    }

    /**
     * Reset initialization state (for testing)
     */
    fun reset() {
        isInitialized = false
        fileSystemManager = null
    }
}
