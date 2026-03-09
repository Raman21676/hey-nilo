package com.projekt_x.studybuddy.model.memory

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Default values and helper functions for memory system
 */
object MemoryDefaults {
    
    // Token limits
    const val MAX_CONTEXT_TOKENS = 300
    const val MAX_EXTRACTION_TOKENS = 150
    const val TINYLLAMA_CONTEXT_WINDOW = 2048
    
    // Storage limits
    const val MAX_STORAGE_BYTES = 350L * 1024 * 1024  // 350MB
    const val MAX_CONVERSATION_BYTES = 100L * 1024 * 1024  // 100MB
    const val COMPACTION_THRESHOLD_BYTES = 50L * 1024 * 1024  // 50MB
    const val MAX_CONVERSATION_FILES = 30
    const val RETENTION_DAYS = 365L
    
    // File paths (relative to filesDir)
    const val MEMORY_DIR = "memory"
    const val CORE_DIR = "memory/core"
    const val CONVERSATIONS_DIR = "memory/conversations"
    const val CONVERSATIONS_ARCHIVE_DIR = "memory/conversations/archive"
    const val WORK_DIR = "memory/work"
    const val WORK_ACTIVE_DIR = "memory/work/active"
    const val WORK_ARCHIVE_DIR = "memory/work/archive"
    const val SYSTEM_DIR = "memory/system"
    
    // File names
    const val USER_PROFILE_FILE = "memory/core/user_profile.json"
    const val RELATIONSHIPS_FILE = "memory/core/relationships.json"
    const val MEDICAL_FILE = "memory/core/medical.json"
    const val REMINDERS_FILE = "memory/work/active/reminders.json"
    const val PROJECTS_FILE = "memory/work/active/projects.json"
    const val GOALS_FILE = "memory/work/active/goals.md"
    const val MEMORY_STATS_FILE = "memory/system/memory-stats.json"
    const val COMPACTION_LOG_FILE = "memory/system/compaction-log.json"
    
    /**
     * Generate unique ID for relationships/reminders
     */
    fun generateId(prefix: String): String {
        return "${prefix}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    // Date formatters (reusable)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH-mm", Locale.getDefault())
    private val readableFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    /**
     * Get current timestamp in ISO format
     */
    fun getCurrentTimestamp(): String {
        return isoFormat.format(Date())
    }
    
    /**
     * Get current date in YYYY-MM-DD format
     */
    fun getCurrentDate(): String {
        return dateFormat.format(Date())
    }
    
    /**
     * Get current time in HH-mm format
     */
    fun getCurrentTime(): String {
        return timeFormat.format(Date())
    }
    
    /**
     * Format ISO timestamp to human-readable date
     */
    fun formatDate(isoString: String?): String? {
        if (isoString == null) return null
        return try {
            val date = isoFormat.parse(isoString)
            date?.let { readableFormat.format(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Format bytes to human-readable string
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
    
    /**
     * Truncate text to approximate token count
     * Rough estimate: 1 token ≈ 4 characters for English
     */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        val maxChars = maxTokens * 4
        return if (text.length <= maxChars) {
            text
        } else {
            text.substring(0, maxChars) + "..."
        }
    }
    
    /**
     * Estimate token count for text
     * Rough estimate: 1 token ≈ 4 characters for English
     */
    fun estimateTokens(text: String): Int {
        return text.length / 4
    }
    
    /**
     * Get month folder name from date string (YYYY-MM-DD)
     */
    fun getMonthFolder(dateString: String): String {
        val monthNames = listOf(
            "01-January", "02-February", "03-March", "04-April",
            "05-May", "06-June", "07-July", "08-August",
            "09-September", "10-October", "11-November", "12-December"
        )
        val monthIndex = try {
            dateString.substring(5, 7).toInt() - 1
        } catch (e: Exception) {
            0
        }
        return monthNames.getOrElse(monthIndex) { "00-Unknown" }
    }
    
    /**
     * Get year from date string (YYYY-MM-DD)
     */
    fun getYear(dateString: String): String {
        return try {
            dateString.substring(0, 4)
        } catch (e: Exception) {
            getCurrentDate().substring(0, 4)
        }
    }
}

/**
 * Memory configuration
 */
object MemoryConfig {
    // Whether to encrypt sensitive files
    const val ENCRYPT_MEDICAL = true
    const val ENCRYPT_API_KEYS = true
    
    // Compaction settings
    const val AUTO_COMPACT = true
    const val COMPACT_AFTER_DAYS = 365
    const val COMPACT_TO_SUMMARY = true
    
    // Search settings
    const val MAX_SEARCH_RESULTS = 10
    const val SEARCH_INDEX_UPDATES = true
    
    // Context building
    const val MAX_FACTS_IN_CONTEXT = 3
    const val MAX_PEOPLE_IN_CONTEXT = 3
    const val MAX_REMINDERS_IN_CONTEXT = 3
    const val INCLUDE_LAST_SESSION = true
}
