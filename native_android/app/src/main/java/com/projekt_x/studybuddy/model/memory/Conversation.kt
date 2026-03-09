package com.projekt_x.studybuddy.model.memory

import com.google.gson.annotations.SerializedName

/**
 * Conversation session data
 * Represents a single conversation saved as markdown file
 * 
 * File: memory/conversations/YYYY/MM-Month/YYYY-MM-DD_HH-mm.md
 * Retention: 1 YEAR (then compacted)
 */
data class Conversation(
    @SerializedName("date")
    val date: String,  // YYYY-MM-DD
    
    @SerializedName("time")
    val time: String,  // HH-mm
    
    @SerializedName("timestamp_iso")
    val timestampIso: String,  // Full ISO timestamp
    
    @SerializedName("mode")
    val mode: String,  // "Offline" or "Online (Provider)"
    
    @SerializedName("duration_minutes")
    val durationMinutes: Int? = null,
    
    @SerializedName("summary")
    val summary: String = "",
    
    @SerializedName("key_facts")
    val keyFacts: MutableList<KeyFact> = mutableListOf(),
    
    @SerializedName("topics")
    val topics: MutableList<String> = mutableListOf(),
    
    @SerializedName("exchanges")
    val exchanges: MutableList<Exchange> = mutableListOf(),
    
    @SerializedName("metadata")
    val metadata: ConversationMetadata = ConversationMetadata()
) {
    /**
     * Generate filename from date and time
     */
    fun generateFilename(): String {
        return "${date}_${time}.md"
    }
    
    /**
     * Get month folder name
     */
    fun getMonthFolder(): String {
        val monthNames = listOf(
            "01-January", "02-February", "03-March", "04-April",
            "05-May", "06-June", "07-July", "08-August",
            "09-September", "10-October", "11-November", "12-December"
        )
        val monthIndex = date.substring(5, 7).toInt() - 1
        return monthNames.getOrElse(monthIndex) { "00-Unknown" }
    }
    
    /**
     * Get year from date
     */
    fun getYear(): String {
        return date.substring(0, 4)
    }
    
    /**
     * Add a user-assistant exchange
     */
    fun addExchange(userMessage: String, assistantResponse: String) {
        exchanges.add(Exchange(userMessage, assistantResponse))
    }
    
    /**
     * Add a key fact extracted from conversation
     */
    fun addKeyFact(category: FactCategory, description: String) {
        keyFacts.add(KeyFact(category, description))
    }
    
    /**
     * Generate markdown content for file storage
     */
    fun toMarkdown(): String {
        val sb = StringBuilder()
        
        sb.appendLine("# Conversation — $date at ${time.replace("-", ":")}")
        sb.appendLine("**Mode**: $mode")
        durationMinutes?.let { sb.appendLine("**Duration**: ~$it minutes") }
        sb.appendLine()
        
        sb.appendLine("## Summary")
        sb.appendLine(summary)
        sb.appendLine()
        
        if (keyFacts.isNotEmpty()) {
            sb.appendLine("## Key Facts Extracted")
            keyFacts.forEach { fact ->
                sb.appendLine("- [${fact.category}] ${fact.description}")
            }
            sb.appendLine()
        }
        
        if (topics.isNotEmpty()) {
            sb.appendLine("## Topics")
            sb.appendLine(topics.joinToString(", "))
            sb.appendLine()
        }
        
        if (exchanges.isNotEmpty()) {
            sb.appendLine("## Raw Exchange")
            exchanges.forEach { exchange ->
                sb.appendLine("> User: ${exchange.userMessage}")
                sb.appendLine("> Assistant: ${exchange.assistantResponse}")
                sb.appendLine()
            }
        }
        
        sb.appendLine("## Metadata")
        sb.appendLine("- Created: $timestampIso")
        sb.appendLine("- Exchanges: ${exchanges.size}")
        sb.appendLine("- Facts extracted: ${keyFacts.size}")
        metadata.tokensUsed?.let { sb.appendLine("- Tokens used: $it") }
        
        return sb.toString()
    }
    
    companion object {
        fun createNew(
            mode: String,
            summary: String = ""
        ): Conversation {
            val now = java.util.Date()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val timeFormat = java.text.SimpleDateFormat("HH-mm", java.util.Locale.getDefault())
            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            
            val date = dateFormat.format(now)
            val time = timeFormat.format(now)
            
            return Conversation(
                date = date,
                time = time,
                timestampIso = isoFormat.format(now),
                mode = mode,
                summary = summary
            )
        }
    }
}

/**
 * Individual exchange (user message + assistant response)
 */
data class Exchange(
    @SerializedName("user")
    val userMessage: String,
    
    @SerializedName("assistant")
    val assistantResponse: String
)

/**
 * Key fact extracted from conversation
 */
data class KeyFact(
    @SerializedName("category")
    val category: FactCategory,
    
    @SerializedName("description")
    val description: String
)

/**
 * Fact categories for organizing extracted facts
 */
enum class FactCategory(val displayName: String) {
    PROFILE("Profile"),
    RELATIONSHIP("Relationship"),
    REMINDER("Reminder"),
    PREFERENCE("Preference"),
    WORK("Work"),
    MEDICAL("Medical"),
    OTHER("Other")
}

/**
 * Conversation metadata
 */
data class ConversationMetadata(
    @SerializedName("tokens_used")
    val tokensUsed: Int? = null,
    
    @SerializedName("importance_score")
    val importanceScore: Float? = null,  // 0.0 - 1.0
    
    @SerializedName("language")
    val language: String? = "en",
    
    @SerializedName("audio_recorded")
    val audioRecorded: Boolean = false
)

/**
 * Archive summary for compacted conversations
 * Stored after 1 year to preserve key information
 */
data class ArchiveSummary(
    @SerializedName("period")
    val period: String,  // "2025-Q1" or "2025"
    
    @SerializedName("generated_at")
    val generatedAt: String,
    
    @SerializedName("themes")
    val themes: List<String>,
    
    @SerializedName("key_facts")
    val keyFacts: List<String>,
    
    @SerializedName("conversations_count")
    val conversationsCount: Int,
    
    @SerializedName("original_files")
    val originalFiles: List<String>
) {
    /**
     * Generate markdown content for archive file
     */
    fun toMarkdown(): String {
        val sb = StringBuilder()
        
        sb.appendLine("# $period Summary")
        sb.appendLine()
        
        sb.appendLine("## Major Themes")
        themes.forEach { sb.appendLine("- $it") }
        sb.appendLine()
        
        sb.appendLine("## Key Facts")
        keyFacts.forEach { sb.appendLine("- $it") }
        sb.appendLine()
        
        sb.appendLine("## Statistics")
        sb.appendLine("- Total conversations: $conversationsCount")
        sb.appendLine("- Generated: $generatedAt")
        sb.appendLine()
        
        sb.appendLine("## Original Files Archived")
        originalFiles.forEach { sb.appendLine("- $it") }
        
        return sb.toString()
    }
}
