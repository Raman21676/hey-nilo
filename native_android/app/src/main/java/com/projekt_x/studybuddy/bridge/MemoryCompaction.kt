package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import com.projekt_x.studybuddy.model.memory.MemoryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MemoryCompaction - Archives old conversations and generates summaries
 * 
 * Responsibilities:
 * - Archive conversations older than 1 year
 * - Generate quarterly summaries
 * - Free up storage space
 * - Track compaction history
 * 
 * Retention Policy:
 * - Core data (profile, relationships): FOREVER
 * - Recent conversations (<1 year): Keep as-is
 * - Old conversations (1-2 years): Archive to summaries
 * - Very old conversations (>2 years): Delete after archiving
 */
class MemoryCompaction(
    private val context: Context,
    private val fileSystemManager: FileSystemManager,
    private val memoryManager: MemoryManager
) {
    
    companion object {
        private const val TAG = "MemoryCompaction"
        
        // Retention periods
        private const val RETENTION_DAYS = 365L // 1 year
        private const val ARCHIVE_CUTOFF_DAYS = 730L // 2 years (delete after)
        
        // Storage thresholds
        private const val STORAGE_WARNING_BYTES = 300L * 1024 * 1024 // 300MB
        private const val STORAGE_CRITICAL_BYTES = 400L * 1024 * 1024 // 400MB
        
        // Batch size for processing
        private const val BATCH_SIZE = 10
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    
    /**
     * Check if compaction is needed and run if necessary
     * Call this on app startup (background thread)
     * 
     * @return true if compaction was performed
     */
    suspend fun runIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking if compaction is needed...")
            
            val stats = memoryManager.getStats()
            
            // Check various triggers
            val shouldCompact: Boolean = when {
                // Storage critical - compact immediately
                stats.storage.totalBytes > STORAGE_CRITICAL_BYTES -> {
                    Log.w(TAG, "Storage critical (${stats.getTotalSizeFormatted()}), forcing compaction")
                    true
                }
                // Storage warning - check age of files
                stats.storage.totalBytes > STORAGE_WARNING_BYTES -> {
                    val hasOldFiles = findConversationsOlderThan(RETENTION_DAYS).isNotEmpty()
                    if (hasOldFiles) {
                        Log.i(TAG, "Storage warning and old files found, compacting")
                        true
                    } else {
                        false
                    }
                }
                // Check if we have very old files (>1 year)
                else -> {
                    val oldFiles = findConversationsOlderThan(RETENTION_DAYS)
                    if (oldFiles.isNotEmpty()) {
                        Log.i(TAG, "Found ${oldFiles.size} conversations older than 1 year, compacting")
                        true
                    } else {
                        false
                    }
                }
            }
            
            if (shouldCompact) {
                val result = performCompaction()
                result.success && result.filesArchived > 0
            } else {
                Log.d(TAG, "Compaction not needed")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking compaction status", e)
            false
        }
    }
    
    /**
     * Force compaction to run immediately
     */
    suspend fun forceCompact(): CompactionResult = withContext(Dispatchers.IO) {
        performCompaction()
    }
    
    /**
     * Perform the actual compaction
     */
    private suspend fun performCompaction(): CompactionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting compaction...")
        
        var filesArchived = 0
        var bytesFreed: Long = 0
        val archivedQuarters = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        try {
            // Step 1: Find old conversations
            val oldConversations = findConversationsOlderThan(RETENTION_DAYS)
            Log.i(TAG, "Found ${oldConversations.size} conversations to archive")
            
            // Step 2: Group by quarter
            val byQuarter = oldConversations.groupBy { getQuarter(it) }
            
            // Step 3: Process each quarter
            byQuarter.forEach { (quarter, files) ->
                try {
                    Log.d(TAG, "Processing quarter: $quarter (${files.size} files)")
                    
                    // Generate summary
                    val summary = generateQuarterSummary(quarter, files)
                    
                    // Save summary to archive
                    saveQuarterlySummary(quarter, summary)
                    archivedQuarters.add(quarter)
                    
                    // Delete original files
                    files.forEach { file ->
                        val fileSize = file.length()
                        if (file.delete()) {
                            filesArchived++
                            bytesFreed += fileSize
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing quarter $quarter", e)
                    errors.add("$quarter: ${e.message}")
                }
            }
            
            // Step 4: Clean up very old archived files (>2 years)
            val veryOldFiles = findConversationsOlderThan(ARCHIVE_CUTOFF_DAYS)
            veryOldFiles.forEach { file ->
                val fileSize = file.length()
                if (file.delete()) {
                    bytesFreed += fileSize
                }
            }
            
            // Step 5: Update stats
            updateCompactionStats(filesArchived, bytesFreed)
            
            Log.i(TAG, "Compaction complete: $filesArchived files archived, ${MemoryDefaults.formatBytes(bytesFreed)} freed")
            
            CompactionResult(
                success = true,
                filesArchived = filesArchived,
                bytesFreed = bytesFreed,
                quartersArchived = archivedQuarters,
                errors = errors
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Compaction failed", e)
            CompactionResult(
                success = false,
                filesArchived = filesArchived,
                bytesFreed = bytesFreed,
                quartersArchived = archivedQuarters,
                errors = errors + e.message
            )
        }
    }
    
    /**
     * Find conversation files older than specified days
     */
    private fun findConversationsOlderThan(days: Long): List<File> {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)
        val conversationsDir = File(context.filesDir, "memory/conversations")
        
        if (!conversationsDir.exists()) return emptyList()
        
        return conversationsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains("/archive/") }
            .filter { it.lastModified() < cutoffTime }
            .toList()
    }
    
    /**
     * Get quarter string (e.g., "2024-Q1") from file
     */
    private fun getQuarter(file: File): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = file.lastModified()
        
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) // 0-11
        val quarter = when (month) {
            in 0..2 -> "Q1"
            in 3..5 -> "Q2"
            in 6..8 -> "Q3"
            else -> "Q4"
        }
        
        return "$year-$quarter"
    }
    
    /**
     * Generate summary for a quarter
     */
    private fun generateQuarterSummary(quarter: String, files: List<File>): QuarterSummary {
        val topics = mutableSetOf<String>()
        val keyFacts = mutableSetOf<String>()
        var totalExchanges = 0
        
        files.forEach { file ->
            try {
                val content = file.readText()
                
                // Count exchanges (pairs of User: / Assistant:)
                val userMatches = content.split("**User:**").size - 1
                totalExchanges += userMatches
                
                // Extract topics (simple keyword matching)
                extractTopics(content).forEach { topics.add(it) }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error reading file for summary: ${file.name}")
            }
        }
        
        return QuarterSummary(
            quarter = quarter,
            totalConversations = files.size,
            totalExchanges = totalExchanges,
            topics = topics.toList().take(20), // Top 20 topics
            dateRange = getDateRange(files)
        )
    }
    
    /**
     * Extract topics from conversation content
     */
    private fun extractTopics(content: String): List<String> {
        val topics = mutableListOf<String>()
        val lowerContent = content.lowercase()
        
        // Simple topic detection based on keywords
        val topicKeywords = mapOf(
            "work" to listOf("work", "job", "office", "meeting", "project", "deadline"),
            "family" to listOf("family", "mom", "dad", "mother", "father", "sister", "brother"),
            "food" to listOf("food", "eat", "restaurant", "cooking", "recipe", "dinner", "lunch"),
            "health" to listOf("health", "doctor", "medicine", "sick", "appointment"),
            "travel" to listOf("travel", "trip", "vacation", "flight", "hotel", "booking"),
            "shopping" to listOf("shopping", "buy", "purchase", "store", "amazon"),
            "technology" to listOf("phone", "computer", "app", "software", "internet"),
            "entertainment" to listOf("movie", "music", "game", "show", "netflix")
        )
        
        topicKeywords.forEach { (topic, keywords) ->
            if (keywords.any { lowerContent.contains(it) }) {
                topics.add(topic)
            }
        }
        
        return topics
    }
    
    /**
     * Get date range from files
     */
    private fun getDateRange(files: List<File>): String {
        if (files.isEmpty()) return ""
        
        val dates = files.map { it.lastModified() }.sorted()
        val oldest = dateFormat.format(dates.first())
        val newest = dateFormat.format(dates.last())
        
        return "$oldest to $newest"
    }
    
    /**
     * Save quarterly summary to archive
     */
    private fun saveQuarterlySummary(quarter: String, summary: QuarterSummary) {
        val archiveDir = File(context.filesDir, "memory/conversations/archive")
        archiveDir.mkdirs()
        
        val summaryFile = File(archiveDir, "$quarter-summary.md")
        
        val content = buildString {
            appendLine("# Conversation Summary - $quarter")
            appendLine()
            appendLine("**Period:** ${summary.dateRange}")
            appendLine("**Total Conversations:** ${summary.totalConversations}")
            appendLine("**Total Exchanges:** ${summary.totalExchanges}")
            appendLine()
            
            if (summary.topics.isNotEmpty()) {
                appendLine("## Topics Discussed")
                summary.topics.forEach { topic ->
                    appendLine("- $topic")
                }
                appendLine()
            }
            
            appendLine("---")
            appendLine("*This is an automated summary of archived conversations*")
            appendLine("*Generated: ${MemoryDefaults.getCurrentTimestamp()}*")
        }
        
        summaryFile.writeText(content)
        Log.d(TAG, "Saved quarterly summary: ${summaryFile.absolutePath}")
    }
    
    /**
     * Update compaction statistics
     */
    private suspend fun updateCompactionStats(filesArchived: Int, bytesFreed: Long) {
        try {
            val currentStats = memoryManager.getStats()
            val updatedStats = currentStats.copy(
                lastUpdated = MemoryDefaults.getCurrentTimestamp(),
                compaction = currentStats.compaction.copy(
                    lastCompactionDate = MemoryDefaults.getCurrentTimestamp(),
                    filesCompacted = currentStats.compaction.filesCompacted + filesArchived,
                    bytesSaved = currentStats.compaction.bytesSaved + bytesFreed,
                    nextScheduled = calculateNextCompaction()
                )
            )
            memoryManager.updateStats(updatedStats)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update compaction stats", e)
        }
    }
    
    /**
     * Calculate next scheduled compaction (3 months from now)
     */
    private fun calculateNextCompaction(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, 3)
        return dateFormat.format(cal.time)
    }
    
    /**
     * Get storage breakdown for UI
     */
    suspend fun getStorageBreakdown(): StorageBreakdown = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(context.filesDir, "memory")
            
            StorageBreakdown(
                totalUsed = memoryManager.calculateStorageUsed(),
                coreSize = calculateDirSize(File(baseDir, "core")),
                conversationsSize = calculateDirSize(File(baseDir, "conversations")),
                workSize = calculateDirSize(File(baseDir, "work")),
                systemSize = calculateDirSize(File(baseDir, "system")),
                maxSize = MemoryDefaults.MAX_STORAGE_BYTES
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage breakdown", e)
            StorageBreakdown(0, 0, 0, 0, 0, MemoryDefaults.MAX_STORAGE_BYTES)
        }
    }
    
    /**
     * Calculate directory size recursively
     */
    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }
    
    /**
     * Result of compaction operation
     */
    data class CompactionResult(
        val success: Boolean,
        val filesArchived: Int,
        val bytesFreed: Long,
        val quartersArchived: List<String>,
        val errors: List<String?>
    ) {
        fun getBytesFreedFormatted(): String = MemoryDefaults.formatBytes(bytesFreed)
    }
    
    /**
     * Summary of a quarter's conversations
     */
    data class QuarterSummary(
        val quarter: String,
        val totalConversations: Int,
        val totalExchanges: Int,
        val topics: List<String>,
        val dateRange: String
    )
    
    /**
     * Storage breakdown for UI
     */
    data class StorageBreakdown(
        val totalUsed: Long,
        val coreSize: Long,
        val conversationsSize: Long,
        val workSize: Long,
        val systemSize: Long,
        val maxSize: Long
    ) {
        fun getTotalFormatted(): String = MemoryDefaults.formatBytes(totalUsed)
        fun getCoreFormatted(): String = MemoryDefaults.formatBytes(coreSize)
        fun getConversationsFormatted(): String = MemoryDefaults.formatBytes(conversationsSize)
        fun getWorkFormatted(): String = MemoryDefaults.formatBytes(workSize)
        fun getSystemFormatted(): String = MemoryDefaults.formatBytes(systemSize)
        fun getMaxFormatted(): String = MemoryDefaults.formatBytes(maxSize)
        
        fun getUsagePercentage(): Int {
            return if (maxSize > 0) {
                ((totalUsed.toDouble() / maxSize) * 100).toInt()
            } else 0
        }
    }
}
