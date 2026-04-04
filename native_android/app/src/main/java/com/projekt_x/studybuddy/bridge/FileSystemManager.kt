package com.projekt_x.studybuddy.bridge

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import com.projekt_x.studybuddy.model.memory.Relationships
import com.projekt_x.studybuddy.model.memory.Reminders
import com.projekt_x.studybuddy.model.memory.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * FileSystemManager - Handles memory directory structure and file operations
 * 
 * Responsibilities:
 * - Create memory folder structure on app startup
 * - Create default JSON files if they don't exist
 * - Provide file paths for all memory types
 * - Handle storage availability checks
 * 
 * Thread Safety: All file operations run on Dispatchers.IO
 */
class FileSystemManager(private val context: Context) {

    companion object {
        private const val TAG = "FileSystemManager"
    }
    
    // Gson instance with pretty printing for readable JSON files
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    /**
     * Root memory directory
     */
    private val memoryDir: File by lazy {
        File(context.filesDir, MemoryDefaults.MEMORY_DIR)
    }

    /**
     * Subdirectories
     */
    private val coreDir: File by lazy { File(memoryDir, "core") }
    private val conversationsDir: File by lazy { File(memoryDir, "conversations") }
    private val workDir: File by lazy { File(memoryDir, "work") }
    private val workActiveDir: File by lazy { File(workDir, "active") }
    private val workArchiveDir: File by lazy { File(workDir, "archive") }
    private val systemDir: File by lazy { File(memoryDir, "system") }

    /**
     * File references
     */
    private val userProfileFile: File by lazy { File(coreDir, "user_profile.json") }
    private val relationshipsFile: File by lazy { File(coreDir, "relationships.json") }
    private val medicalFile: File by lazy { File(coreDir, "medical.json") }
    private val remindersFile: File by lazy { File(workActiveDir, "reminders.json") }
    private val projectsFile: File by lazy { File(workActiveDir, "projects.json") }
    private val goalsFile: File by lazy { File(workActiveDir, "goals.md") }
    private val memoryStatsFile: File by lazy { File(systemDir, "memory-stats.json") }
    private val compactionLogFile: File by lazy { File(systemDir, "compaction-log.json") }

    /**
     * Initialize file system - create directories and default files
     * Call this on app startup
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing file system...")

            // Step 1: Create directory structure
            val dirsCreated = createDirectoryStructure()
            if (!dirsCreated) {
                Log.e(TAG, "Failed to create directory structure")
                return@withContext false
            }

            // Step 2: Create default files if they don't exist
            val filesCreated = createDefaultFiles()
            if (!filesCreated) {
                Log.e(TAG, "Failed to create default files")
                return@withContext false
            }

            Log.i(TAG, "File system initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing file system", e)
            false
        }
    }

    /**
     * Create all memory directories
     */
    private fun createDirectoryStructure(): Boolean {
        val directories = listOf(
            memoryDir,
            coreDir,
            conversationsDir,
            workDir,
            workActiveDir,
            workArchiveDir,
            systemDir
        )

        var allCreated = true
        for (dir in directories) {
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (created) {
                    Log.d(TAG, "Created directory: ${dir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create directory: ${dir.absolutePath}")
                    allCreated = false
                }
            } else {
                Log.d(TAG, "Directory already exists: ${dir.absolutePath}")
            }
        }

        return allCreated
    }

    /**
     * Create default JSON files if they don't exist
     */
    private fun createDefaultFiles(): Boolean {
        var allCreated = true

        // User profile
        if (!userProfileFile.exists()) {
            val defaultProfile = UserProfile.default()
            allCreated = allCreated && writeJsonFile(userProfileFile, defaultProfile)
            Log.i(TAG, "Created default user_profile.json")
        }

        // Relationships
        if (!relationshipsFile.exists()) {
            val defaultRelationships = Relationships.default()
            allCreated = allCreated && writeJsonFile(relationshipsFile, defaultRelationships)
            Log.i(TAG, "Created default relationships.json")
        }

        // Reminders
        if (!remindersFile.exists()) {
            val defaultReminders = Reminders.default()
            allCreated = allCreated && writeJsonFile(remindersFile, defaultReminders)
            Log.i(TAG, "Created default reminders.json")
        }

        // Medical (encrypted placeholder)
        if (!medicalFile.exists()) {
            allCreated = allCreated && createEncryptedPlaceholder(medicalFile)
            Log.i(TAG, "Created encrypted medical.json placeholder")
        }

        // Goals (markdown)
        if (!goalsFile.exists()) {
            allCreated = allCreated && createDefaultGoalsFile()
            Log.i(TAG, "Created default goals.md")
        }

        // Projects
        if (!projectsFile.exists()) {
            allCreated = allCreated && createDefaultProjectsFile()
            Log.i(TAG, "Created default projects.json")
        }

        // Memory stats
        if (!memoryStatsFile.exists()) {
            allCreated = allCreated && createDefaultMemoryStatsFile()
            Log.i(TAG, "Created default memory-stats.json")
        }

        // Compaction log
        if (!compactionLogFile.exists()) {
            allCreated = allCreated && createDefaultCompactionLogFile()
            Log.i(TAG, "Created default compaction-log.json")
        }

        return allCreated
    }

    /**
     * Write object to JSON file
     */
    fun <T> writeJsonFile(file: File, data: T): Boolean {
        return try {
            val json = gson.toJson(data)
            file.writeText(json)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to file: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Read JSON file and parse to object
     */
    fun <T> readJsonFile(file: File, clazz: Class<T>): T? {
        return try {
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: ${file.absolutePath}")
                return null
            }
            val json = file.readText()
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading/parsing file: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Create encrypted placeholder for sensitive files
     */
    private fun createEncryptedPlaceholder(file: File): Boolean {
        val placeholder = """
            {
              "encrypted": true,
              "note": "This file is encrypted. Use secure decryption to access.",
              "created_at": "${MemoryDefaults.getCurrentTimestamp()}"
            }
        """.trimIndent()
        return try {
            file.writeText(placeholder)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating encrypted placeholder", e)
            false
        }
    }

    /**
     * Create default goals.md file
     */
    private fun createDefaultGoalsFile(): Boolean {
        val content = """# Goals

## Professional
- [ ] Set your professional goals here

## Personal
- [ ] Set your personal goals here

---
*This file is automatically managed by AGENT SMITH*
"""
        return try {
            goalsFile.writeText(content)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating goals.md", e)
            false
        }
    }

    /**
     * Create default projects.json file
     */
    private fun createDefaultProjectsFile(): Boolean {
        val defaultProjects = """
            {
              "version": 1,
              "last_updated": "${MemoryDefaults.getCurrentTimestamp()}",
              "projects": []
            }
        """.trimIndent()
        return try {
            projectsFile.writeText(defaultProjects)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating projects.json", e)
            false
        }
    }

    /**
     * Create default memory-stats.json file
     */
    private fun createDefaultMemoryStatsFile(): Boolean {
        val defaultStats = """
            {
              "version": 1,
              "last_updated": "${MemoryDefaults.getCurrentTimestamp()}",
              "storage": {
                "total_bytes": 0,
                "max_bytes": ${MemoryDefaults.MAX_STORAGE_BYTES},
                "core_bytes": 0,
                "conversations_bytes": 0,
                "work_bytes": 0,
                "system_bytes": 0
              },
              "counts": {
                "profile_facts": 0,
                "relationships": 0,
                "pending_reminders": 0,
                "completed_reminders": 0,
                "conversation_files": 0,
                "archived_files": 0
              },
              "compaction": {
                "files_compacted": 0,
                "bytes_saved": 0
              }
            }
        """.trimIndent()
        return try {
            memoryStatsFile.writeText(defaultStats)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating memory-stats.json", e)
            false
        }
    }

    /**
     * Create default compaction-log.json file
     */
    private fun createDefaultCompactionLogFile(): Boolean {
        val defaultLog = """
            {
              "version": 1,
              "compactions": []
            }
        """.trimIndent()
        return try {
            compactionLogFile.writeText(defaultLog)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating compaction-log.json", e)
            false
        }
    }

    /**
     * Check if file system is initialized
     */
    fun isInitialized(): Boolean {
        return memoryDir.exists() &&
               coreDir.exists() &&
               userProfileFile.exists() &&
               relationshipsFile.exists()
    }

    /**
     * Get file path for user profile
     */
    fun getUserProfileFilePath(): File = userProfileFile

    /**
     * Get file path for relationships
     */
    fun getRelationshipsFilePath(): File = relationshipsFile

    /**
     * Get file path for reminders
     */
    fun getRemindersFilePath(): File = remindersFile

    /**
     * Get file path for medical (encrypted)
     */
    fun getMedicalFilePath(): File = medicalFile

    /**
     * Get file path for memory stats
     */
    fun getMemoryStatsFilePath(): File = memoryStatsFile

    /**
     * Get conversations directory for a specific date
     * Creates year/month subdirectories if needed
     */
    suspend fun getConversationDirForDate(date: String): File = withContext(Dispatchers.IO) {
        val year = MemoryDefaults.getYear(date)
        val monthFolder = MemoryDefaults.getMonthFolder(date)
        
        val yearDir = File(conversationsDir, year)
        val monthDir = File(yearDir, monthFolder)
        
        if (!monthDir.exists()) {
            monthDir.mkdirs()
            Log.d(TAG, "Created conversation directory: ${monthDir.absolutePath}")
        }
        
        monthDir
    }

    /**
     * Get conversation archive directory
     */
    fun getConversationArchiveDir(): File {
        val archiveDir = File(conversationsDir, "archive")
        if (!archiveDir.exists()) {
            archiveDir.mkdirs()
        }
        return archiveDir
    }

    /**
     * Get work archive directory
     */
    fun getOrCreateWorkArchiveDir(): File {
        if (!workArchiveDir.exists()) {
            workArchiveDir.mkdirs()
        }
        return workArchiveDir
    }

    /**
     * Calculate total storage used by memory system
     */
    suspend fun calculateStorageUsed(): Long = withContext(Dispatchers.IO) {
        try {
            memoryDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage", e)
            0L
        }
    }

    /**
     * Check available storage space
     */
    fun hasAvailableSpace(): Boolean {
        val available = context.filesDir.freeSpace
        val minRequired = 50 * 1024 * 1024L // 50MB minimum
        return available > minRequired
    }

    /**
     * Append a conversation exchange to today's conversation file
     * Creates the file with header if it doesn't exist
     */
    suspend fun appendConversationExchange(exchange: com.projekt_x.studybuddy.bridge.ConversationExchange) = withContext(Dispatchers.IO) {
        try {
            val date = MemoryDefaults.getCurrentDate()
            val time = MemoryDefaults.getCurrentTime()
            val year = MemoryDefaults.getYear(date)
            val monthFolder = MemoryDefaults.getMonthFolder(date)
            
            // Create directory structure
            val yearDir = File(conversationsDir, year)
            val monthDir = File(yearDir, monthFolder)
            if (!monthDir.exists()) {
                monthDir.mkdirs()
            }
            
            // File name: YYYY-MM-DD.md
            val file = File(monthDir, "$date.md")
            val isNewFile = !file.exists()
            
            // Build markdown content
            val content = buildString {
                if (isNewFile) {
                    appendLine("# Conversation - $date")
                    appendLine()
                    appendLine("## Session $time")
                    appendLine()
                } else {
                    // Check if we need a new session header (if last write was > 30 min ago)
                    val lastModified = file.lastModified()
                    val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000)
                    if (lastModified < thirtyMinutesAgo) {
                        appendLine()
                        appendLine("## Session $time")
                        appendLine()
                    } else {
                        appendLine()
                    }
                }
                appendLine("**User:** ${exchange.userMessage}")
                appendLine()
                appendLine("**Assistant:** ${exchange.assistantMessage}")
                appendLine()
            }
            
            // Append to file
            file.appendText(content)
            Log.d(TAG, "Appended conversation exchange to ${file.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error appending conversation exchange", e)
        }
    }

    /**
     * Delete all memory files (DANGER - for testing only)
     */
    suspend fun deleteAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            memoryDir.deleteRecursively()
            Log.w(TAG, "All memory files deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting memory files", e)
            false
        }
    }
}
