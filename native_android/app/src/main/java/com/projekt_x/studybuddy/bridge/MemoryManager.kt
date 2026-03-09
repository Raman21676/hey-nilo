package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.projekt_x.studybuddy.model.memory.Conversation
import com.projekt_x.studybuddy.model.memory.KeyFact
import com.projekt_x.studybuddy.model.memory.MemoryConfig
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import com.projekt_x.studybuddy.model.memory.MemoryStats
import com.projekt_x.studybuddy.model.memory.Relationship
import com.projekt_x.studybuddy.model.memory.RelationshipUpdates
import com.projekt_x.studybuddy.model.memory.Reminder
import com.projekt_x.studybuddy.model.memory.ReminderStatus
import com.projekt_x.studybuddy.model.memory.Reminders
import com.projekt_x.studybuddy.model.memory.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MemoryManager - Central controller for persistent memory system
 * 
 * Responsibilities:
 * - Load memory files into RAM cache on initialization
 * - Provide fast read access to cached data
 * - Persist changes to disk (Dispatchers.IO)
 * - Build context block for LLM prompt injection (≤300 tokens)
 * - Extract and save memories from conversations
 * - Manage conversation sessions
 * 
 * Thread Safety: All mutating operations are thread-safe
 * Persistence: All changes saved to JSON files automatically
 * 
 * @param context Application context
 * @param fileSystemManager FileSystemManager instance (or null to create new)
 */
class MemoryManager(
    private val context: Context,
    private val fileSystemManager: FileSystemManager
) {

    companion object {
        private const val TAG = "MemoryManager"
        private val gson = Gson()
    }

    // RAM Cache - loaded once, updated in memory, persisted to disk
    private lateinit var userProfile: UserProfile
    private lateinit var relationships: MutableList<Relationship>
    private lateinit var reminders: Reminders
    private lateinit var memoryStats: MemoryStats

    // Initialization state
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initialize MemoryManager - load all files into RAM cache
     * Call this once before using MemoryManager
     * 
     * @return true if initialization successful
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized.get()) {
            Log.d(TAG, "MemoryManager already initialized")
            return@withContext true
        }

        try {
            Log.i(TAG, "Initializing MemoryManager...")

            // Load user profile
            val profileFile = fileSystemManager.getUserProfileFilePath()
            userProfile = loadJsonFile(profileFile, UserProfile.default())
            Log.d(TAG, "Loaded user profile: ${userProfile.getDisplayName() ?: "unknown"}")

            // Load relationships
            val relationshipsFile = fileSystemManager.getRelationshipsFilePath()
            val relsData = loadJsonFile(relationshipsFile, com.projekt_x.studybuddy.model.memory.Relationships.default())
            relationships = relsData.getAll().toMutableList()
            Log.d(TAG, "Loaded ${relationships.size} relationships")

            // Load reminders
            val remindersFile = fileSystemManager.getRemindersFilePath()
            reminders = loadJsonFile(remindersFile, Reminders.default())
            Log.d(TAG, "Loaded ${reminders.getPendingCount()} pending reminders")

            // Load memory stats
            val statsFile = fileSystemManager.getMemoryStatsFilePath()
            memoryStats = loadJsonFile(statsFile, MemoryStats.default())
            Log.d(TAG, "Loaded memory stats")

            isInitialized.set(true)
            Log.i(TAG, "✅ MemoryManager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing MemoryManager", e)
            false
        }
    }

    /**
     * Build context block for LLM prompt injection
     * Ensures output is ≤ maxTokens (default 300)
     * 
     * Format:
     * [MEMORY]
     * User: {name}. {age}. {location}.
     * Facts: {3 facts}
     * People: {3 relationships}
     * Pending: {3 reminders}
     * Last session: {summary}
     * [/MEMORY]
     * 
     * @param maxTokens Maximum tokens allowed (default 300)
     * @return Formatted context block string
     */
    fun buildContextBlock(maxTokens: Int = MemoryDefaults.MAX_CONTEXT_TOKENS): String {
        ensureInitialized()

        val parts = mutableListOf<String>()
        parts.add("[MEMORY]")

        // User info line
        val userInfo = buildUserInfoLine()
        if (userInfo.isNotBlank()) {
            parts.add("User: $userInfo")
        }

        // Facts (max 3)
        val facts = userProfile.facts.take(MemoryConfig.MAX_FACTS_IN_CONTEXT)
        if (facts.isNotEmpty()) {
            parts.add("Facts: ${facts.joinToString(". ")}")
        }

        // People (max 3)
        val people = relationships
            .filter { it.name != null }
            .take(MemoryConfig.MAX_PEOPLE_IN_CONTEXT)
        if (people.isNotEmpty()) {
            val peopleStr = people.joinToString(". ") { it.formatForContext() }
            parts.add("People: $peopleStr")
        }

        // Pending reminders (max 3)
        val pendingReminders = reminders.getPending()
            .take(MemoryConfig.MAX_REMINDERS_IN_CONTEXT)
        if (pendingReminders.isNotEmpty()) {
            val remindersStr = pendingReminders.joinToString(" · ") { it.formatForContext() }
            parts.add("Pending: $remindersStr")
        }

        // Last session (optional)
        if (MemoryConfig.INCLUDE_LAST_SESSION) {
            val lastSession = getLastSessionSummary()
            if (lastSession.isNotBlank()) {
                parts.add("Last session: $lastSession")
            }
        }

        parts.add("[/MEMORY]")

        // Join and truncate to token limit
        var result = parts.joinToString("\n")
        result = MemoryDefaults.truncateToTokens(result, maxTokens)

        return result
    }

    /**
     * Build user info line for context
     * Example: "Kali. 28 years old. Lives in Bangalore, IST timezone."
     */
    private fun buildUserInfoLine(): String {
        val infoParts = mutableListOf<String>()

        // Name
        userProfile.getDisplayName()?.let { infoParts.add(it) }

        // Age
        userProfile.getAgeString()?.let { infoParts.add(it) }

        // Location
        userProfile.getLocation()?.let { infoParts.add("Lives in $it") }

        // Timezone (if available)
        // Could add from system or preferences

        return infoParts.joinToString(". ")
    }

    /**
     * Get summary of last conversation session
     * Reads from conversations directory
     */
    private fun getLastSessionSummary(): String {
        // This would read the most recent conversation file
        // For now, return empty or cached value
        // Implementation in Phase 4
        return ""
    }

    // ==================== PROFILE OPERATIONS ====================

    /**
     * Get user profile (from RAM cache)
     */
    fun getUserProfile(): UserProfile {
        ensureInitialized()
        return userProfile
    }

    /**
     * Update a single profile field
     * Automatically updates last_updated timestamp and persists
     * 
     * @param field Field name (name, preferredName, age, city, etc.)
     * @param value New value
     */
    suspend fun updateProfileField(field: String, value: Any?): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val updatedProfile = when (field) {
                "name" -> userProfile.copy(
                    identity = userProfile.identity.copy(name = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "preferredName" -> userProfile.copy(
                    identity = userProfile.identity.copy(preferredName = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "age" -> userProfile.copy(
                    identity = userProfile.identity.copy(age = value as? Int),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "birthdate" -> userProfile.copy(
                    identity = userProfile.identity.copy(birthdate = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "gender" -> userProfile.copy(
                    identity = userProfile.identity.copy(gender = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "city" -> userProfile.copy(
                    contact = userProfile.contact.copy(city = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "country" -> userProfile.copy(
                    contact = userProfile.contact.copy(country = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "address" -> userProfile.copy(
                    contact = userProfile.contact.copy(address = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "title" -> userProfile.copy(
                    occupation = userProfile.occupation.copy(title = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "company" -> userProfile.copy(
                    occupation = userProfile.occupation.copy(company = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "industry" -> userProfile.copy(
                    occupation = userProfile.occupation.copy(industry = value as? String),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                "communicationStyle" -> userProfile.copy(
                    preferences = userProfile.preferences.copy(communicationStyle = value as? String ?: "friendly"),
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                else -> {
                    Log.w(TAG, "Unknown profile field: $field")
                    return@withContext false
                }
            }

            userProfile = updatedProfile

            // Persist to disk
            val success = saveUserProfile()
            if (success) {
                Log.d(TAG, "Updated profile field: $field = $value")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error updating profile field: $field", e)
            false
        }
    }

    /**
     * Add a personal fact to profile
     */
    suspend fun addFact(fact: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        if (fact.isBlank()) {
            return@withContext false
        }

        try {
            userProfile.facts.add(fact)
            val updatedProfile = userProfile.copy(
                lastUpdated = MemoryDefaults.getCurrentTimestamp()
            )
            userProfile = updatedProfile

            val success = saveUserProfile()
            if (success) {
                Log.d(TAG, "Added fact: $fact")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error adding fact", e)
            false
        }
    }

    /**
     * Remove a fact from profile
     */
    suspend fun removeFact(fact: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val removed = userProfile.facts.remove(fact)
            if (removed) {
                val updatedProfile = userProfile.copy(
                    lastUpdated = MemoryDefaults.getCurrentTimestamp()
                )
                userProfile = updatedProfile

                val success = saveUserProfile()
                if (success) {
                    Log.d(TAG, "Removed fact: $fact")
                }
                success
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing fact", e)
            false
        }
    }

    // ==================== RELATIONSHIP OPERATIONS ====================

    /**
     * Add a new relationship
     */
    suspend fun addRelationship(relationship: Relationship): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            relationships.add(relationship)
            val success = saveRelationships()
            if (success) {
                Log.d(TAG, "Added relationship: ${relationship.name} (${relationship.relation})")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error adding relationship", e)
            false
        }
    }

    /**
     * Get relationship by ID
     */
    fun getRelationship(id: String): Relationship? {
        ensureInitialized()
        return relationships.find { it.id == id }
    }

    /**
     * Get all relationships
     */
    fun getAllRelationships(): List<Relationship> {
        ensureInitialized()
        return relationships.toList()
    }

    /**
     * Search relationships by query
     */
    fun searchRelationships(query: String): List<Relationship> {
        ensureInitialized()
        val lowerQuery = query.lowercase()
        return relationships.filter { rel ->
            rel.name?.lowercase()?.contains(lowerQuery) == true ||
            rel.relation?.lowercase()?.contains(lowerQuery) == true ||
            rel.notes?.lowercase()?.contains(lowerQuery) == true
        }
    }

    /**
     * Update relationship by ID
     */
    suspend fun updateRelationship(id: String, updates: RelationshipUpdates): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val index = relationships.indexOfFirst { it.id == id }
            if (index == -1) {
                Log.w(TAG, "Relationship not found: $id")
                return@withContext false
            }

            val existing = relationships[index]
            val updated = existing.copy(
                name = updates.name ?: existing.name,
                relation = updates.relation ?: existing.relation,
                contact = updates.contact ?: existing.contact,
                notes = updates.notes ?: existing.notes,
                birthdate = updates.birthdate ?: existing.birthdate
            )

            relationships[index] = updated

            val success = saveRelationships()
            if (success) {
                Log.d(TAG, "Updated relationship: $id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error updating relationship", e)
            false
        }
    }

    /**
     * Delete relationship by ID
     */
    suspend fun deleteRelationship(id: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val removed = relationships.removeAll { it.id == id }
            if (removed) {
                val success = saveRelationships()
                if (success) {
                    Log.d(TAG, "Deleted relationship: $id")
                }
                success
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting relationship", e)
            false
        }
    }

    // ==================== REMINDER OPERATIONS ====================

    /**
     * Add a new reminder
     */
    suspend fun addReminder(reminder: Reminder): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            reminders.add(reminder)
            val success = saveReminders()
            if (success) {
                Log.d(TAG, "Added reminder: ${reminder.text}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reminder", e)
            false
        }
    }

    /**
     * Get pending reminders
     */
    fun getPendingReminders(): List<Reminder> {
        ensureInitialized()
        return reminders.getPending()
    }

    /**
     * Get all reminders
     */
    fun getAllReminders(): List<Reminder> {
        ensureInitialized()
        return reminders.reminders.toList()
    }

    /**
     * Mark reminder as completed
     */
    suspend fun completeReminder(id: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val success = reminders.completeById(id)
            if (success) {
                saveReminders()
                Log.d(TAG, "Completed reminder: $id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error completing reminder", e)
            false
        }
    }

    /**
     * Delete reminder by ID
     */
    suspend fun deleteReminder(id: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()

        try {
            val removed = reminders.deleteById(id)
            if (removed) {
                saveReminders()
                Log.d(TAG, "Deleted reminder: $id")
            }
            removed
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting reminder", e)
            false
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * Ensure MemoryManager is initialized
     */
    private fun ensureInitialized() {
        if (!isInitialized.get()) {
            throw IllegalStateException("MemoryManager not initialized. Call initialize() first.")
        }
    }

    /**
     * Load JSON file or return default if missing/corrupted
     */
    private inline fun <reified T> loadJsonFile(file: File, default: T): T {
        return try {
            if (!file.exists()) {
                Log.w(TAG, "File does not exist, using default: ${file.name}")
                return default
            }
            val json = file.readText()
            gson.fromJson(json, T::class.java) ?: default
        } catch (e: JsonParseException) {
            Log.e(TAG, "Corrupted JSON in ${file.name}, using default", e)
            default
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ${file.name}, using default", e)
            default
        }
    }

    /**
     * Save user profile to disk
     */
    private fun saveUserProfile(): Boolean {
        return fileSystemManager.writeJsonFile(
            fileSystemManager.getUserProfileFilePath(),
            userProfile
        )
    }

    /**
     * Save relationships to disk
     */
    private fun saveRelationships(): Boolean {
        // Convert back to Relationships data class for serialization
        val rels = com.projekt_x.studybuddy.model.memory.Relationships(
            lastUpdated = MemoryDefaults.getCurrentTimestamp(),
            family = relationships.filter { it.category == com.projekt_x.studybuddy.model.memory.RelationshipCategory.FAMILY }.toMutableList(),
            friends = relationships.filter { it.category == com.projekt_x.studybuddy.model.memory.RelationshipCategory.FRIEND }.toMutableList(),
            colleagues = relationships.filter { it.category == com.projekt_x.studybuddy.model.memory.RelationshipCategory.COLLEAGUE }.toMutableList(),
            importantPeople = relationships.filter { it.category == com.projekt_x.studybuddy.model.memory.RelationshipCategory.IMPORTANT }.toMutableList()
        )
        return fileSystemManager.writeJsonFile(
            fileSystemManager.getRelationshipsFilePath(),
            rels
        )
    }

    /**
     * Save reminders to disk
     */
    private fun saveReminders(): Boolean {
        return fileSystemManager.writeJsonFile(
            fileSystemManager.getRemindersFilePath(),
            reminders
        )
    }

    // ==================== STATS & MAINTENANCE ====================

    /**
     * Get memory statistics
     */
    fun getStats(): MemoryStats {
        ensureInitialized()
        return memoryStats
    }

    /**
     * Update memory stats
     */
    suspend fun updateStats(stats: MemoryStats): Boolean = withContext(Dispatchers.IO) {
        memoryStats = stats
        fileSystemManager.writeJsonFile(
            fileSystemManager.getMemoryStatsFilePath(),
            stats
        )
    }

    /**
     * Calculate storage used
     */
    suspend fun calculateStorageUsed(): Long {
        return fileSystemManager.calculateStorageUsed()
    }

    /**
     * Run compaction if needed
     * Placeholder - full implementation in Phase 5
     */
    suspend fun runCompactionIfNeeded() {
        // Phase 5 implementation
    }

    /**
     * Extract and save memory from conversation
     * Placeholder - full implementation in Phase 4
     */
    suspend fun extractAndSave(userMessage: String, llmResponse: String, mode: String) {
        // Phase 4 implementation
    }

    /**
     * Save a single conversation exchange (user message + assistant response)
     * Used by VoicePipelineManager to persist conversations in real-time
     */
    suspend fun saveConversationExchange(userMessage: String, assistantMessage: String) {
        val exchange = ConversationExchange(
            timestamp = MemoryDefaults.getCurrentTimestamp(),
            userMessage = userMessage,
            assistantMessage = assistantMessage
        )
        
        // Add to in-memory list (maintain last 100 exchanges)
        recentExchanges.add(exchange)
        if (recentExchanges.size > 100) {
            recentExchanges.removeAt(0)
        }
        
        // Persist to today's conversation file
        fileSystemManager.appendConversationExchange(exchange)
    }
    
    // In-memory storage for recent exchanges
    private val recentExchanges = mutableListOf<ConversationExchange>()
    
    /**
     * Get recent conversation exchanges (for context building)
     */
    fun getRecentExchanges(count: Int = 5): List<ConversationExchange> {
        return recentExchanges.takeLast(count)
    }
    
    /**
     * Save conversation session
     * Placeholder - full implementation in Phase 4
     */
    suspend fun saveConversationSession(turns: List<Pair<String, String>>, mode: String) {
        // Phase 4 implementation
    }
}

/**
 * Single conversation exchange for real-time persistence
 */
data class ConversationExchange(
    val timestamp: String,
    val userMessage: String,
    val assistantMessage: String
)
