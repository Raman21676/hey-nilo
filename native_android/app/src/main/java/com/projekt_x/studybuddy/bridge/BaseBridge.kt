package com.projekt_x.studybuddy.bridge

import android.util.Log

/**
 * Abstract base class for all native bridges (LLM, Voice, Audio, etc.)
 * 
 * Provides common interface for:
 * - Lifecycle management (init, release)
 * - State tracking (isLoaded, state)
 * - Error handling
 * - Resource management
 * 
 * All bridges MUST extend this class and implement abstract methods.
 */
abstract class BaseBridge {

    companion object {
        private const val TAG = "BaseBridge"
    }

    /**
     * Current state of the bridge
     */
    enum class State {
        UNINITIALIZED,      // Bridge created but not initialized
        INITIALIZING,       // Initialization in progress
        READY,              // Ready to use
        LOADING,            // Loading model/resources
        BUSY,               // Currently processing (generating, etc.)
        ERROR,              // Error state
        RELEASED            // Resources released
    }

    /**
     * Current state of this bridge instance
     */
    protected var currentState: State = State.UNINITIALIZED
        private set

    /**
     * Last error message if state is ERROR
     * Access via property, no getter method needed
     */
    protected var lastError: String? = null
        private set

    /**
     * Timestamp of last state change
     */
    protected var lastStateChangeTime: Long = System.currentTimeMillis()

    // ========================================================================
    // ABSTRACT METHODS - Must be implemented by subclasses
    // ========================================================================

    /**
     * Initialize the bridge and load native libraries
     * 
     * @return true if initialization successful, false otherwise
     */
    abstract fun init(): Boolean

    /**
     * Release all resources and native memory
     * Must be called when done using the bridge
     * 
     * @return true if release successful
     */
    abstract fun release(): Boolean

    /**
     * Check if the bridge is fully loaded and ready to use
     * 
     * @return true if ready, false otherwise
     */
    abstract fun isLoaded(): Boolean

    /**
     * Get the name of this bridge (for logging/debugging)
     * 
     * @return bridge name (e.g., "LlamaBridge", "VoiceBridge")
     */
    abstract fun getBridgeName(): String

    // ========================================================================
    // COMMON METHODS - Can be overridden by subclasses if needed
    // ========================================================================

    /**
     * Get current state of the bridge
     */
    fun getState(): State = currentState

    /**
     * Check if bridge is in error state
     */
    fun isInError(): Boolean = currentState == State.ERROR

    /**
     * Get last error message (access as property: bridge.lastError)
     */
    fun getLastErrorMessage(): String? = lastError

    /**
     * Check if bridge can accept new requests
     * Override in subclasses for custom logic (e.g., check if busy)
     */
    open fun canAcceptRequest(): Boolean {
        return currentState == State.READY && isLoaded()
    }

    /**
     * Reset error state and attempt recovery
     * Override in subclasses for custom recovery logic
     * 
     * @return true if recovery successful
     */
    open fun recoverFromError(): Boolean {
        if (currentState != State.ERROR) {
            Log.w(TAG, "[${getBridgeName()}] Not in error state, nothing to recover")
            return true
        }
        
        Log.i(TAG, "[${getBridgeName()}] Attempting recovery from error: $lastError")
        lastError = null
        updateState(State.UNINITIALIZED)
        return init()
    }

    /**
     * Get memory usage in bytes
     * Override in subclasses to report actual memory usage
     * 
     * @return memory usage in bytes, or -1 if unknown
     */
    open fun getMemoryUsage(): Long = -1

    /**
     * Get detailed status information
     * Override in subclasses to provide more details
     */
    open fun getStatus(): BridgeStatus {
        return BridgeStatus(
            bridgeName = getBridgeName(),
            state = currentState,
            isLoaded = isLoaded(),
            memoryUsage = getMemoryUsage(),
            lastError = lastError,
            uptimeMs = System.currentTimeMillis() - lastStateChangeTime
        )
    }

    // ========================================================================
    // PROTECTED METHODS - For use by subclasses
    // ========================================================================

    /**
     * Update the bridge state
     * Automatically logs state transitions
     */
    protected fun updateState(newState: State) {
        if (currentState != newState) {
            Log.i(TAG, "[${getBridgeName()}] State: $currentState -> $newState")
            currentState = newState
            lastStateChangeTime = System.currentTimeMillis()
            
            // Clear error when transitioning out of error state
            if (newState != State.ERROR) {
                lastError = null
            }
        }
    }

    /**
     * Set error state with message
     */
    protected fun setError(errorMessage: String) {
        Log.e(TAG, "[${getBridgeName()}] Error: $errorMessage")
        lastError = errorMessage
        updateState(State.ERROR)
    }

    /**
     * Assert that bridge is in expected state, otherwise set error
     * 
     * @return true if in expected state
     */
    protected fun assertState(expectedState: State, operation: String): Boolean {
        if (currentState != expectedState) {
            val msg = "Cannot $operation: expected state $expectedState but was $currentState"
            setError(msg)
            return false
        }
        return true
    }

    /**
     * Assert that bridge is ready (in READY state and loaded)
     * 
     * @return true if ready
     */
    protected fun assertReady(operation: String): Boolean {
        if (currentState != State.READY) {
            val msg = "Cannot $operation: bridge not ready (state: $currentState)"
            setError(msg)
            return false
        }
        if (!isLoaded()) {
            val msg = "Cannot $operation: resources not loaded"
            setError(msg)
            return false
        }
        return true
    }

    /**
     * Safe release helper - releases even if in error state
     */
    protected fun safeRelease(releaseAction: () -> Boolean): Boolean {
        return try {
            val result = releaseAction()
            updateState(State.RELEASED)
            result
        } catch (e: Exception) {
            Log.e(TAG, "[${getBridgeName()}] Exception during release: ${e.message}")
            updateState(State.RELEASED)
            false
        }
    }
}

/**
 * Status information for a bridge
 */
data class BridgeStatus(
    val bridgeName: String,
    val state: BaseBridge.State,
    val isLoaded: Boolean,
    val memoryUsage: Long,
    val lastError: String?,
    val uptimeMs: Long
) {
    fun isReady(): Boolean = state == BaseBridge.State.READY && isLoaded
    fun hasError(): Boolean = state == BaseBridge.State.ERROR
}

/**
 * Interface for callbacks from native code
 * All callbacks should be called on a background thread
 */
interface NativeCallback {
    fun onSuccess(result: String)
    fun onError(error: String)
    fun onProgress(progress: Float)
}

/**
 * Interface for streaming callbacks (token-by-token)
 */
interface StreamingCallback {
    fun onToken(token: String)
    fun onComplete(fullText: String)
    fun onError(error: String)
}

/**
 * Resource configuration for bridges
 */
data class BridgeConfig(
    val threads: Int = 4,
    val useMmap: Boolean = true,
    val contextSize: Int = 2048,
    val batchSize: Int = 512,
    val memoryPressure: Float = 0f
)

/**
 * Exception for bridge-related errors
 */
class BridgeException(message: String, val bridgeName: String? = null) : Exception(message)
