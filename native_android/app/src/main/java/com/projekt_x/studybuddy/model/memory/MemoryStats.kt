package com.projekt_x.studybuddy.model.memory

import com.google.gson.annotations.SerializedName

/**
 * Memory statistics for tracking storage usage
 * 
 * File: memory/system/memory-stats.json
 * Retention: FOREVER (updated periodically)
 */
data class MemoryStats(
    @SerializedName("version")
    val version: Int = 1,
    
    @SerializedName("last_updated")
    val lastUpdated: String = "",
    
    @SerializedName("storage")
    val storage: StorageStats = StorageStats(),
    
    @SerializedName("counts")
    val counts: MemoryCounts = MemoryCounts(),
    
    @SerializedName("compaction")
    val compaction: CompactionInfo = CompactionInfo()
) {
    /**
     * Get total storage used in human-readable format
     */
    fun getTotalSizeFormatted(): String {
        return formatBytes(storage.totalBytes)
    }
    
    /**
     * Check if storage is approaching limit (>80%)
     */
    fun isStorageWarning(): Boolean {
        return storage.totalBytes > (storage.maxBytes * 0.8)
    }
    
    /**
     * Check if compaction is needed
     */
    fun isCompactionNeeded(): Boolean {
        // Trigger if >500MB or >30 conversation files
        return storage.totalBytes > 500 * 1024 * 1024 ||
               counts.conversationFiles > 30
    }
    
    companion object {
        fun default(): MemoryStats = MemoryStats()
        
        fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            }
        }
    }
}

/**
 * Storage statistics
 */
data class StorageStats(
    @SerializedName("total_bytes")
    val totalBytes: Long = 0,
    
    @SerializedName("max_bytes")
    val maxBytes: Long = 350 * 1024 * 1024, // 350MB limit
    
    @SerializedName("core_bytes")
    val coreBytes: Long = 0,
    
    @SerializedName("conversations_bytes")
    val conversationsBytes: Long = 0,
    
    @SerializedName("work_bytes")
    val workBytes: Long = 0,
    
    @SerializedName("system_bytes")
    val systemBytes: Long = 0
)

/**
 * Memory counts
 */
data class MemoryCounts(
    @SerializedName("profile_facts")
    val profileFacts: Int = 0,
    
    @SerializedName("relationships")
    val relationships: Int = 0,
    
    @SerializedName("pending_reminders")
    val pendingReminders: Int = 0,
    
    @SerializedName("completed_reminders")
    val completedReminders: Int = 0,
    
    @SerializedName("conversation_files")
    val conversationFiles: Int = 0,
    
    @SerializedName("archived_files")
    val archivedFiles: Int = 0
)

/**
 * Compaction information
 */
data class CompactionInfo(
    @SerializedName("last_compaction_date")
    val lastCompactionDate: String? = null,
    
    @SerializedName("files_compacted")
    val filesCompacted: Int = 0,
    
    @SerializedName("bytes_saved")
    val bytesSaved: Long = 0,
    
    @SerializedName("next_scheduled")
    val nextScheduled: String? = null
)

/**
 * Conversation summary data class
 * Used for storing conversation metadata
 */
data class ConversationSummary(
    @SerializedName("date")
    val date: String,
    
    @SerializedName("time")
    val time: String,
    
    @SerializedName("filename")
    val filename: String,
    
    @SerializedName("summary")
    val summary: String,
    
    @SerializedName("mode")
    val mode: String, // "Offline" or "Online (Provider)"
    
    @SerializedName("duration_minutes")
    val durationMinutes: Int? = null,
    
    @SerializedName("key_facts")
    val keyFacts: List<String> = listOf(),
    
    @SerializedName("topics")
    val topics: List<String> = listOf()
) {
    /**
     * Get full file path
     */
    fun getFilePath(): String {
        return "conversations/${date.substring(0, 4)}/${date.substring(5, 7)}-Month/$filename"
    }
}

/**
 * Profile update data class (partial updates)
 */
data class ProfileUpdates(
    val name: String? = null,
    val preferredName: String? = null,
    val birthdate: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val address: String? = null,
    val city: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val title: String? = null,
    val company: String? = null,
    val industry: String? = null,
    val communicationStyle: String? = null
)
