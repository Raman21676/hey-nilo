package com.projekt_x.studybuddy.bridge

import android.util.Log

/**
 * Manager for all bridges in the application
 * 
 * Coordinates initialization, resource management, and lifecycle
 * of multiple bridges (LLM, Voice, Audio, etc.)
 * 
 * Usage:
 * ```
 * val manager = BridgeManager.getInstance()
 * manager.registerBridge("llm", llamaBridge)
 * manager.initializeAll()
 * 
 * // Get bridge
 * val llm = manager.getBridge<LlamaBridge>("llm")
 * 
 * // Cleanup
 * manager.releaseAll()
 * ```
 */
class BridgeManager private constructor() {

    companion object {
        private const val TAG = "BridgeManager"
        
        @Volatile
        private var instance: BridgeManager? = null

        fun getInstance(): BridgeManager {
            return instance ?: synchronized(this) {
                instance ?: BridgeManager().also { instance = it }
            }
        }

        fun destroyInstance() {
            instance?.releaseAll()
            instance = null
        }
    }

    private val bridges = mutableMapOf<String, BaseBridge>()
    private val initializationOrder = mutableListOf<String>()

    /**
     * Register a bridge with the manager
     * 
     * @param name Unique name for the bridge (e.g., "llm", "voice", "audio")
     * @param bridge The bridge instance
     */
    fun registerBridge(name: String, bridge: BaseBridge) {
        if (bridges.containsKey(name)) {
            Log.w(TAG, "Bridge '$name' already registered, replacing")
        }
        bridges[name] = bridge
        if (!initializationOrder.contains(name)) {
            initializationOrder.add(name)
        }
        Log.i(TAG, "Registered bridge: $name (${bridge.getBridgeName()})")
    }

    /**
     * Unregister a bridge
     * 
     * @param name Bridge name
     * @param release If true, release the bridge before unregistering
     */
    fun unregisterBridge(name: String, release: Boolean = true) {
        val bridge = bridges[name]
        if (bridge != null) {
            if (release) {
                bridge.release()
            }
            bridges.remove(name)
            initializationOrder.remove(name)
            Log.i(TAG, "Unregistered bridge: $name")
        }
    }

    /**
     * Get a bridge by name
     * 
     * @param name Bridge name
     * @return The bridge instance, or null if not found
     */
    fun getBridge(name: String): BaseBridge? {
        return bridges[name]
    }

    /**
     * Get a bridge by name with type safety
     * 
     * @param name Bridge name
     * @return The typed bridge instance, or null if not found/wrong type
     */
    fun <T : BaseBridge> getBridgeTyped(name: String, clazz: Class<T>): T? {
        val bridge = bridges[name]
        return if (clazz.isInstance(bridge)) clazz.cast(bridge) else null
    }

    /**
     * Initialize all registered bridges in registration order
     * 
     * @return Map of bridge name to initialization success
     */
    fun initializeAll(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        Log.i(TAG, "Initializing ${bridges.size} bridges...")
        
        for (name in initializationOrder) {
            val bridge = bridges[name]
            if (bridge != null) {
                Log.i(TAG, "Initializing bridge: $name")
                val success = bridge.init()
                results[name] = success
                
                if (!success) {
                    Log.e(TAG, "Failed to initialize bridge: $name")
                }
            }
        }
        
        val successCount = results.count { it.value }
        Log.i(TAG, "Initialization complete: $successCount/${results.size} bridges ready")
        
        return results
    }

    /**
     * Release all bridges (reverse order of initialization)
     */
    fun releaseAll() {
        Log.i(TAG, "Releasing ${bridges.size} bridges...")
        
        // Release in reverse order
        val reverseOrder = initializationOrder.reversed()
        
        for (name in reverseOrder) {
            val bridge = bridges[name]
            if (bridge != null) {
                Log.i(TAG, "Releasing bridge: $name")
                bridge.release()
            }
        }
        
        bridges.clear()
        initializationOrder.clear()
        Log.i(TAG, "All bridges released")
    }

    /**
     * Check if all bridges are ready
     */
    fun allReady(): Boolean {
        if (bridges.isEmpty()) return false
        return bridges.values.all { it.isLoaded() && it.getState() == BaseBridge.State.READY }
    }

    /**
     * Get list of all bridge names
     */
    fun getBridgeNames(): List<String> = bridges.keys.toList()

    /**
     * Get status of all bridges
     */
    fun getAllStatus(): Map<String, BridgeStatus> {
        return bridges.mapValues { it.value.getStatus() }
    }

    /**
     * Get total memory usage of all bridges
     * 
     * @return total memory in bytes, or -1 if any bridge reports unknown
     */
    fun getTotalMemoryUsage(): Long {
        var total: Long = 0
        for (bridge in bridges.values) {
            val usage = bridge.getMemoryUsage()
            if (usage < 0) return -1 // Unknown
            total += usage
        }
        return total
    }

    /**
     * Check if any bridge has an error
     */
    fun hasErrors(): Boolean {
        return bridges.values.any { it.isInError() }
    }

    /**
     * Get all error messages
     */
    fun getAllErrors(): Map<String, String?> {
        return bridges.mapValues { it.value.getLastErrorMessage() }
            .filter { it.value != null }
    }

    /**
     * Attempt to recover all bridges from error state
     * 
     * @return Map of bridge name to recovery success
     */
    fun recoverAll(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        for ((name, bridge) in bridges) {
            if (bridge.isInError()) {
                Log.i(TAG, "Attempting recovery for bridge: $name")
                results[name] = bridge.recoverFromError()
            }
        }
        
        return results
    }

    /**
     * Print debug info about all bridges
     */
    fun printDebugInfo() {
        Log.i(TAG, "=== BridgeManager Debug Info ===")
        Log.i(TAG, "Registered bridges: ${bridges.size}")
        
        for ((name, bridge) in bridges) {
            val status = bridge.getStatus()
            Log.i(TAG, "  [$name] ${status.bridgeName}: ${status.state} " +
                    "(loaded: ${status.isLoaded}, mem: ${status.memoryUsage / 1024 / 1024}MB)")
        }
        
        Log.i(TAG, "Total memory: ${getTotalMemoryUsage() / 1024 / 1024}MB")
        Log.i(TAG, "All ready: ${allReady()}")
        Log.i(TAG, "Has errors: ${hasErrors()}")
    }
}

/**
 * Extension function to safely use a bridge if it's ready
 */
inline fun <T : BaseBridge, R> T.ifReady(block: T.() -> R): R? {
    return if (this.isLoaded() && this.getState() == BaseBridge.State.READY) {
        block()
    } else {
        null
    }
}

/**
 * Extension function to execute operation on bridge with error handling
 */
inline fun <T : BaseBridge, R> T.withErrorHandling(
    operation: String,
    onError: (String) -> Unit = {},
    block: T.() -> R
): R? {
    return try {
        if (!this.isLoaded()) {
            onError("Bridge ${this.getBridgeName()} not loaded")
            return null
        }
        block()
    } catch (e: Exception) {
        val msg = "Error in $operation: ${e.message}"
        Log.e("BridgeManager", msg)
        onError(msg)
        null
    }
}
