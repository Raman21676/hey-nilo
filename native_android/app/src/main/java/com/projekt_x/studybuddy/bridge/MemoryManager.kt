package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.projekt_x.studybuddy.model.memory.Conversation
import com.projekt_x.studybuddy.model.memory.ConversationSummary
import com.projekt_x.studybuddy.model.memory.KeyFact
import com.projekt_x.studybuddy.model.memory.MemoryConfig
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import com.projekt_x.studybuddy.model.memory.MemoryStats
import com.projekt_x.studybuddy.model.memory.Relationship
import com.projekt_x.studybuddy.model.memory.RelationshipUpdates
import com.projekt_x.studybuddy.model.memory.Reminder
import com.projekt_x.studybuddy.model.memory.ReminderStatus
import com.projekt_x.studybuddy.model.memory.ReminderType
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
     * Reads from conversations directory and returns first exchange
     */
    private fun getLastSessionSummary(): String {
        return try {
            // Get the most recent exchange from memory
            val recent = recentExchanges.lastOrNull()
            if (recent != null) {
                // Summarize the last exchange
                val userMsg = recent.userMessage.take(50)
                val assistantMsg = recent.assistantMessage.take(50)
                "Discussed: $userMsg..."
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting last session summary", e)
            ""
        }
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
     * Archives old conversations and frees up storage
     * 
     * @return CompactionResult with details of what was archived
     */
    suspend fun runCompactionIfNeeded(): MemoryCompaction.CompactionResult {
        ensureInitialized()
        
        val compaction = MemoryCompaction(context, fileSystemManager, this)
        val shouldCompact = compaction.runIfNeeded()
        
        return if (shouldCompact) {
            compaction.forceCompact()
        } else {
            MemoryCompaction.CompactionResult(
                success = true,
                filesArchived = 0,
                bytesFreed = 0,
                quartersArchived = emptyList(),
                errors = emptyList()
            )
        }
    }
    
    /**
     * Force compaction to run immediately
     * Use this for manual compaction trigger
     */
    suspend fun forceCompaction(): MemoryCompaction.CompactionResult {
        ensureInitialized()
        
        val compaction = MemoryCompaction(context, fileSystemManager, this)
        return compaction.forceCompact()
    }
    
    /**
     * Get storage breakdown for UI display
     */
    suspend fun getStorageBreakdown(): MemoryCompaction.StorageBreakdown {
        ensureInitialized()
        
        val compaction = MemoryCompaction(context, fileSystemManager, this)
        return compaction.getStorageBreakdown()
    }

    /**
     * Extract and save memory from conversation
     * Analyzes conversation for facts, relationships, and reminders
     * Updates memory files automatically
     * 
     * @param userMessage User's message
     * @param llmResponse Assistant's response
     * @param mode Which LLM was used (Offline/Online provider name)
     */
    suspend fun extractAndSave(userMessage: String, llmResponse: String, mode: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Extracting memory from conversation...")
            
            // Check if conversation is worth extracting from
            if (!MemoryExtractionHelper.isWorthExtracting(userMessage, llmResponse)) {
                Log.d(TAG, "Conversation not worth extracting, skipping")
                return@withContext
            }
            
            // Use simple rule-based extraction (faster, no LLM call needed)
            val extractedFacts = extractFactsRuleBased(userMessage)
            val extractedRelationships = extractRelationshipsRuleBased(userMessage)
            val extractedReminders = extractRemindersRuleBased(userMessage)
            
            // Add facts (deduplicated)
            val newFacts = MemoryExtractionHelper.deduplicateFacts(userProfile.facts, extractedFacts)
            newFacts.forEach { fact ->
                addFact(fact)
                Log.i(TAG, "Extracted fact: $fact")
            }
            
            // Add relationships (check for duplicates)
            extractedRelationships.forEach { rel ->
                val exists = relationships.any { 
                    it.name?.lowercase() == rel.name?.lowercase() && 
                    it.relation?.lowercase() == rel.relation?.lowercase() 
                }
                if (!exists) {
                    addRelationship(rel)
                    Log.i(TAG, "Extracted relationship: ${rel.name} (${rel.relation})")
                }
            }
            
            // Add reminders (check for duplicates)
            extractedReminders.forEach { rem ->
                val exists = reminders.reminders.any { 
                    it.text.lowercase() == rem.text.lowercase() && 
                    it.status != ReminderStatus.COMPLETED 
                }
                if (!exists) {
                    addReminder(rem)
                    Log.i(TAG, "Extracted reminder: ${rem.text}")
                }
            }
            
            // Update stats
            updateExtractionStats(newFacts.size, extractedRelationships.size, extractedReminders.size)
            
            Log.i(TAG, "Memory extraction complete: ${newFacts.size} facts, ${extractedRelationships.size} relationships, ${extractedReminders.size} reminders")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting memory", e)
            // Don't crash - extraction is non-critical
        }
    }
    
    /**
     * Rule-based fact extraction (faster than LLM-based)
     */
    private fun extractFactsRuleBased(message: String): List<String> {
        val facts = mutableListOf<String>()
        val lowerMsg = message.lowercase()
        
        // Name patterns
        val namePatterns = listOf(
            Regex("my name is (\\w+)", RegexOption.IGNORE_CASE),
            Regex("i am (\\w+)\\s*,?\\s*(\\d+)?", RegexOption.IGNORE_CASE),
            Regex("call me (\\w+)", RegexOption.IGNORE_CASE),
            Regex("i'm (\\w+)", RegexOption.IGNORE_CASE)
        )
        namePatterns.forEach { pattern ->
            pattern.find(message)?.let { match ->
                match.groups[1]?.value?.let { name ->
                    if (name.length > 2 && name !in listOf("the", "and", "but", "here", "there")) {
                        facts.add("Name is $name")
                    }
                }
            }
        }
        
        // Age patterns
        val agePatterns = listOf(
            Regex("i am (\\d+) years? old", RegexOption.IGNORE_CASE),
            Regex("i'm (\\d+) years? old", RegexOption.IGNORE_CASE),
            Regex("(\\d+) years? old", RegexOption.IGNORE_CASE)
        )
        agePatterns.forEach { pattern ->
            pattern.find(message)?.let { match ->
                match.groups[1]?.value?.let { age ->
                    facts.add("$age years old")
                }
            }
        }
        
        // Location patterns
        val locationPatterns = listOf(
            Regex("i live in ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i am from ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i'm from ([^,.]+)", RegexOption.IGNORE_CASE)
        )
        locationPatterns.forEach { pattern ->
            pattern.find(message)?.let { match ->
                match.groups[1]?.value?.let { location ->
                    facts.add("Lives in $location")
                }
            }
        }
        
        // Job/Occupation patterns
        val jobPatterns = listOf(
            Regex("i work as (?:a|an) ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i am (?:a|an) ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i'm (?:a|an) ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i work at ([^,.]+)", RegexOption.IGNORE_CASE)
        )
        jobPatterns.forEach { pattern ->
            pattern.find(message)?.let { match ->
                match.groups[1]?.value?.let { job ->
                    if (job.length > 3) {
                        facts.add("Works as $job")
                    }
                }
            }
        }
        
        // Preference patterns
        val preferencePatterns = listOf(
            Regex("i (?:like|love|enjoy) ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("my favorite ([^,.]+) is ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i prefer ([^,.]+)", RegexOption.IGNORE_CASE)
        )
        preferencePatterns.forEach { pattern ->
            pattern.find(message)?.let { match ->
                val fullMatch = match.value
                if (fullMatch.length > 10 && fullMatch.length < 100) {
                    facts.add(fullMatch.replace(Regex("^i ", RegexOption.IGNORE_CASE), "").replaceFirstChar { it.uppercase() })
                }
            }
        }
        
        return facts.distinct()
    }
    
    /**
     * Rule-based relationship extraction
     */
    private fun extractRelationshipsRuleBased(message: String): List<Relationship> {
        val relationships = mutableListOf<Relationship>()
        
        // Family patterns
        val familyPatterns = mapOf(
            Regex("my (?:mom|mother) (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "mother",
            Regex("my (?:dad|father) (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "father",
            Regex("my sister (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "sister",
            Regex("my brother (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "brother",
            Regex("my wife (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "wife",
            Regex("my husband (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "husband",
            Regex("my (?:child|son|daughter) (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE) to "child"
        )
        
        familyPatterns.forEach { (pattern, relation) ->
            pattern.find(message)?.let { match ->
                match.groups[1]?.value?.let { name ->
                    if (name.length > 2 && !name.contains("name")) {
                        relationships.add(
                            Relationship(
                                id = MemoryDefaults.generateId("rel"),
                                relation = relation,
                                name = name.trim()
                            )
                        )
                    }
                }
            }
        }
        
        // Friend patterns
        val friendPattern = Regex("my friend (?:is|name is) ([^,.]+)", RegexOption.IGNORE_CASE)
        friendPattern.findAll(message).forEach { match ->
            match.groups[1]?.value?.let { name ->
                if (name.length > 2) {
                    relationships.add(
                        Relationship(
                            id = MemoryDefaults.generateId("rel"),
                            relation = "friend",
                            name = name.trim()
                        )
                    )
                }
            }
        }
        
        return relationships
    }
    
    /**
     * Rule-based reminder extraction
     */
    private fun extractRemindersRuleBased(message: String): List<Reminder> {
        val reminders = mutableListOf<Reminder>()
        val lowerMsg = message.lowercase()
        
        // Remind me patterns
        val remindPatterns = listOf(
            Regex("remind me to ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("don't forget to ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("remember to ([^,.]+)", RegexOption.IGNORE_CASE),
            Regex("i need to ([^,.]+)", RegexOption.IGNORE_CASE)
        )
        
        remindPatterns.forEach { pattern ->
            pattern.findAll(message).forEach { match ->
                match.groups[1]?.value?.let { task ->
                    if (task.length > 5) {
                        val type = when {
                            task.contains("call", ignoreCase = true) -> ReminderType.CALL
                            task.contains("text", ignoreCase = true) || task.contains("message", ignoreCase = true) -> ReminderType.MESSAGE_SMS
                            else -> ReminderType.REMINDER
                        }
                        
                        reminders.add(
                            Reminder(
                                id = MemoryDefaults.generateId("rem"),
                                type = type,
                                text = task.trim()
                            )
                        )
                    }
                }
            }
        }
        
        // Call patterns
        val callPattern = Regex("call ([^,.]+) (?:at|on|about)?", RegexOption.IGNORE_CASE)
        callPattern.findAll(message).forEach { match ->
            match.groups[1]?.value?.let { target ->
                if (target.length > 2 && target.length < 50) {
                    reminders.add(
                        Reminder(
                            id = MemoryDefaults.generateId("rem"),
                            type = ReminderType.CALL,
                            text = "Call $target",
                            targetPerson = target.trim()
                        )
                    )
                }
            }
        }
        
        return reminders.distinctBy { it.text.lowercase() }
    }
    
    /**
     * Update stats after extraction
     */
    private suspend fun updateExtractionStats(facts: Int, relationships: Int, reminders: Int) {
        try {
            val updatedStats = memoryStats.copy(
                extractionStats = memoryStats.extractionStats.copy(
                    totalFactsExtracted = memoryStats.extractionStats.totalFactsExtracted + facts,
                    totalRelationshipsExtracted = memoryStats.extractionStats.totalRelationshipsExtracted + relationships,
                    totalRemindersExtracted = memoryStats.extractionStats.totalRemindersExtracted + reminders,
                    lastExtractionAt = MemoryDefaults.getCurrentTimestamp()
                )
            )
            updateStats(updatedStats)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update extraction stats", e)
        }
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
        // This would generate a session summary and save it
    }
    
    // ==================== SEARCH FUNCTIONALITY ====================
    
    /**
     * Search across all memory types
     * Searches in: profile, relationships, reminders, and conversations
     * 
     * @param query Search query string
     * @return Search results grouped by type
     */
    suspend fun searchAll(query: String): SearchResults = withContext(Dispatchers.IO) {
        ensureInitialized()
        
        if (query.isBlank()) {
            return@withContext SearchResults()
        }
        
        val lowerQuery = query.lowercase()
        
        SearchResults(
            profileMatches = searchProfile(lowerQuery),
            relationshipMatches = searchRelationships(lowerQuery),
            reminderMatches = searchReminders(lowerQuery),
            conversationMatches = searchConversations(lowerQuery)
        )
    }
    
    /**
     * Search in user profile
     */
    private fun searchProfile(query: String): List<ProfileMatch> {
        val matches = mutableListOf<ProfileMatch>()
        
        // Search name
        userProfile.identity.name?.let {
            if (it.lowercase().contains(query)) {
                matches.add(ProfileMatch("Name", it))
            }
        }
        
        // Search facts
        userProfile.facts.forEachIndexed { index, fact ->
            if (fact.lowercase().contains(query)) {
                matches.add(ProfileMatch("Fact ${index + 1}", fact))
            }
        }
        
        // Search occupation
        userProfile.occupation.title?.let {
            if (it.lowercase().contains(query)) {
                matches.add(ProfileMatch("Job Title", it))
            }
        }
        userProfile.occupation.company?.let {
            if (it.lowercase().contains(query)) {
                matches.add(ProfileMatch("Company", it))
            }
        }
        
        // Search location
        userProfile.contact.city?.let {
            if (it.lowercase().contains(query)) {
                matches.add(ProfileMatch("City", it))
            }
        }
        
        return matches
    }
    
    /**
     * Search in reminders
     */
    private fun searchReminders(query: String): List<Reminder> {
        return reminders.reminders.filter { reminder ->
            reminder.text.lowercase().contains(query) ||
            reminder.targetPerson?.lowercase()?.contains(query) == true
        }
    }
    
    /**
     * Search in conversation history
     */
    private suspend fun searchConversations(query: String): List<ConversationMatch> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<ConversationMatch>()
        val conversationsDir = File(context.filesDir, "memory/conversations")
        
        if (!conversationsDir.exists()) return@withContext emptyList()
        
        // Search recent files first (last 30 days)
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        
        conversationsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { it.lastModified() > thirtyDaysAgo } // Only recent files for speed
            .take(50) // Limit to 50 files for performance
            .forEach { file ->
                try {
                    val content = file.readText()
                    if (content.lowercase().contains(query)) {
                        // Extract the matching excerpt
                        val excerpt = extractExcerpt(content, query)
                        matches.add(
                            ConversationMatch(
                                fileName = file.name,
                                filePath = file.absolutePath,
                                date = extractDateFromFileName(file.name),
                                excerpt = excerpt,
                                lastModified = file.lastModified()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error searching file: ${file.name}")
                }
            }
        
        // Sort by date (newest first)
        matches.sortedByDescending { it.lastModified }
    }
    
    /**
     * Extract excerpt around matching text
     */
    private fun extractExcerpt(content: String, query: String, maxLength: Int = 150): String {
        val lowerContent = content.lowercase()
        val index = lowerContent.indexOf(query.lowercase())
        
        if (index == -1) return content.take(maxLength)
        
        val start = (index - 50).coerceAtLeast(0)
        val end = (index + query.length + 50).coerceAtMost(content.length)
        
        var excerpt = content.substring(start, end)
        if (start > 0) excerpt = "...$excerpt"
        if (end < content.length) excerpt = "$excerpt..."
        
        return excerpt.replace(Regex("\\*+"), "") // Remove markdown
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    /**
     * Extract date from conversation filename (YYYY-MM-DD.md)
     */
    private fun extractDateFromFileName(fileName: String): String {
        return try {
            fileName.removeSuffix(".md")
        } catch (e: Exception) {
            fileName
        }
    }
    
    /**
     * Get recent conversation summaries for display
     */
    suspend fun getRecentConversationSummaries(count: Int = 10): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val summaries = mutableListOf<ConversationSummary>()
        val conversationsDir = File(context.filesDir, "memory/conversations")
        
        if (!conversationsDir.exists()) return@withContext emptyList()
        
        conversationsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains("/archive/") }
            .sortedByDescending { it.lastModified() }
            .take(count)
            .forEach { file ->
                try {
                    val content = file.readText()
                    val firstExchange = extractFirstExchange(content)
                    
                    summaries.add(
                        ConversationSummary(
                            date = extractDateFromFileName(file.name),
                            time = "", // Could extract from content
                            filename = file.name,
                            summary = firstExchange.take(100),
                            mode = "Unknown", // Could parse from content
                            durationMinutes = null,
                            keyFacts = emptyList(),
                            topics = emptyList()
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading conversation summary: ${file.name}")
                }
            }
        
        summaries
    }
    
    /**
     * Extract first user exchange from conversation
     */
    private fun extractFirstExchange(content: String): String {
        val userMatch = Regex("\\*\\*User:\\*\\* (.+)").find(content)
        return userMatch?.groupValues?.get(1) ?: "Conversation"
    }
    
    // ==================== DATA CLASSES FOR SEARCH ====================
    
    /**
     * Complete search results
     */
    data class SearchResults(
        val profileMatches: List<ProfileMatch> = emptyList(),
        val relationshipMatches: List<Relationship> = emptyList(),
        val reminderMatches: List<Reminder> = emptyList(),
        val conversationMatches: List<ConversationMatch> = emptyList()
    ) {
        fun isEmpty(): Boolean = profileMatches.isEmpty() && 
                                  relationshipMatches.isEmpty() && 
                                  reminderMatches.isEmpty() && 
                                  conversationMatches.isEmpty()
        
        fun getTotalCount(): Int = profileMatches.size + 
                                   relationshipMatches.size + 
                                   reminderMatches.size + 
                                   conversationMatches.size
    }
    
    /**
     * Profile search match
     */
    data class ProfileMatch(
        val field: String,
        val value: String
    )
    
    /**
     * Conversation search match
     */
    data class ConversationMatch(
        val fileName: String,
        val filePath: String,
        val date: String,
        val excerpt: String,
        val lastModified: Long
    )
}

/**
 * Single conversation exchange for real-time persistence
 */
data class ConversationExchange(
    val timestamp: String,
    val userMessage: String,
    val assistantMessage: String
)
