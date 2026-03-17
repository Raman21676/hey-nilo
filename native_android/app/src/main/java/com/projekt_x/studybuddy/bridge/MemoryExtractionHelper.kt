package com.projekt_x.studybuddy.bridge

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.projekt_x.studybuddy.model.memory.Relationship
import com.projekt_x.studybuddy.model.memory.RelationshipCategory
import com.projekt_x.studybuddy.model.memory.Reminder
import com.projekt_x.studybuddy.model.memory.ReminderType
import com.projekt_x.studybuddy.model.memory.MemoryDefaults

/**
 * MemoryExtractionHelper - Extracts memory-worthy facts from conversations
 * 
 * This helper analyzes user messages and LLM responses to extract:
 * - Personal facts about the user
 * - Relationships (family, friends, colleagues)
 * - Reminders and tasks
 * 
 * Uses a lightweight extraction approach suitable for on-device LLMs
 */
object MemoryExtractionHelper {
    
    private const val TAG = "MemoryExtractionHelper"
    private val gson = Gson()
    
    /**
     * Maximum tokens for extraction prompt
     */
    private const val EXTRACTION_MAX_TOKENS = 150
    
    /**
     * Minimum confidence threshold for extraction
     */
    private const val MIN_CONFIDENCE = 0.6
    
    /**
     * Extraction prompt template for LLM
     */
    private val EXTRACTION_PROMPT_TEMPLATE = """
Extract memory-worthy facts from this conversation. Output ONLY valid JSON.

Rules:
- Extract only clear, explicit information
- Ignore hypothetical or conditional statements
- Do not make assumptions
- Keep facts concise (under 10 words)

Output format:
{
  "facts": ["fact 1", "fact 2"],
  "relationships": [
    {"name": "John", "relation": "friend", "note": "optional context"}
  ],
  "reminders": [
    {"text": "Call John", "type": "call", "target": "John"}
  ],
  "nothing_new": false
}

Valid relation types: mother, father, sister, brother, spouse, child, friend, colleague, boss, doctor
Valid reminder types: call, alarm, message_whatsapp, message_sms, reminder, note

Conversation:
User: %s
Assistant: %s

JSON output:""".trimIndent()
    
    /**
     * Result of memory extraction
     */
    data class ExtractionResult(
        val facts: List<String> = emptyList(),
        val relationships: List<ExtractedRelationship> = emptyList(),
        val reminders: List<ExtractedReminder> = emptyList(),
        val nothingNew: Boolean = false
    )
    
    /**
     * Extracted relationship data
     */
    data class ExtractedRelationship(
        val name: String,
        val relation: String,
        val note: String? = null
    )
    
    /**
     * Extracted reminder data
     */
    data class ExtractedReminder(
        val text: String,
        val type: String = "reminder",
        val target: String? = null,
        val dueDate: String? = null
    )
    
    /**
     * Build extraction prompt for LLM
     */
    fun buildExtractionPrompt(userMessage: String, assistantMessage: String): String {
        return String.format(
            EXTRACTION_PROMPT_TEMPLATE,
            userMessage.take(500), // Limit length
            assistantMessage.take(300) // Limit length
        )
    }
    
    /**
     * Parse extraction response from LLM
     * Handles various JSON formats and errors gracefully
     */
    fun parseExtractionResponse(json: String): ExtractionResult {
        // Clean up the response - extract JSON if wrapped in markdown
        val cleanedJson = extractJsonFromMarkdown(json)
        
        return try {
            val parsed = gson.fromJson(cleanedJson, ExtractionResponse::class.java)
            
            ExtractionResult(
                facts = parsed.facts?.filter { it.isNotBlank() } ?: emptyList(),
                relationships = parsed.relationships?.map { 
                    ExtractedRelationship(
                        name = it.name ?: "",
                        relation = it.relation ?: "",
                        note = it.note
                    )
                }?.filter { it.name.isNotBlank() } ?: emptyList(),
                reminders = parsed.reminders?.map {
                    ExtractedReminder(
                        text = it.text ?: "",
                        type = it.type ?: "reminder",
                        target = it.target,
                        dueDate = it.due_date
                    )
                }?.filter { it.text.isNotBlank() } ?: emptyList(),
                nothingNew = parsed.nothing_new ?: false
            )
        } catch (e: JsonParseException) {
            Log.w(TAG, "Failed to parse extraction JSON: ${e.message}")
            Log.d(TAG, "Raw response: $json")
            ExtractionResult(nothingNew = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing extraction response", e)
            ExtractionResult(nothingNew = true)
        }
    }
    
    /**
     * Extract JSON from markdown code blocks or plain text
     */
    private fun extractJsonFromMarkdown(text: String): String {
        // Try to extract from markdown code block
        val codeBlockRegex = "```(?:json)?\\s*\\n?([\\s\\S]*?)```".toRegex()
        val match = codeBlockRegex.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // Try to find JSON object
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1)
        }
        
        return text.trim()
    }
    
    /**
     * Convert extracted relationship to Relationship model
     */
    fun toRelationship(extracted: ExtractedRelationship): Relationship? {
        if (extracted.name.isBlank()) return null
        
        val category = categorizeRelation(extracted.relation)
        
        return Relationship(
            id = MemoryDefaults.generateId("rel"),
            relation = extracted.relation.lowercase(),
            name = extracted.name,
            notes = extracted.note
        )
    }
    
    /**
     * Convert extracted reminder to Reminder model
     */
    fun toReminder(extracted: ExtractedReminder): Reminder? {
        if (extracted.text.isBlank()) return null
        
        val reminderType = parseReminderType(extracted.type)
        
        return Reminder(
            id = MemoryDefaults.generateId("rem"),
            type = reminderType,
            text = extracted.text,
            targetPerson = extracted.target,
            dueDate = extracted.dueDate,
            createdAt = MemoryDefaults.getCurrentTimestamp()
        )
    }
    
    /**
     * Categorize relationship type
     */
    private fun categorizeRelation(relation: String): RelationshipCategory {
        return when (relation.lowercase()) {
            "mother", "father", "parent", "mom", "dad" -> RelationshipCategory.FAMILY
            "sister", "brother", "sibling" -> RelationshipCategory.FAMILY
            "spouse", "husband", "wife", "partner" -> RelationshipCategory.FAMILY
            "child", "son", "daughter", "kid" -> RelationshipCategory.FAMILY
            "friend", "buddy", "pal" -> RelationshipCategory.FRIEND
            "colleague", "coworker", "teammate" -> RelationshipCategory.COLLEAGUE
            "boss", "manager", "supervisor" -> RelationshipCategory.COLLEAGUE
            "doctor", "therapist", "dentist" -> RelationshipCategory.IMPORTANT
            else -> RelationshipCategory.IMPORTANT
        }
    }
    
    /**
     * Parse reminder type string to ReminderType
     */
    private fun parseReminderType(type: String): ReminderType {
        return when (type.lowercase()) {
            "call", "phone" -> ReminderType.CALL
            "alarm", "wake" -> ReminderType.ALARM
            "message_whatsapp", "whatsapp" -> ReminderType.MESSAGE_WHATSAPP
            "message_instagram", "instagram", "dm" -> ReminderType.MESSAGE_INSTAGRAM
            "message_sms", "sms", "text" -> ReminderType.MESSAGE_SMS
            "note" -> ReminderType.NOTE
            else -> ReminderType.REMINDER
        }
    }
    
    /**
     * Filter out duplicate or similar facts
     */
    fun deduplicateFacts(existingFacts: List<String>, newFacts: List<String>): List<String> {
        return newFacts.filter { newFact ->
            val newNormalized = newFact.lowercase().trim()
            !existingFacts.any { existing ->
                val existingNormalized = existing.lowercase().trim()
                // Check for exact match or high similarity
                existingNormalized == newNormalized ||
                existingNormalized.contains(newNormalized) ||
                newNormalized.contains(existingNormalized)
            }
        }
    }
    
    /**
     * Check if conversation is worth extracting from
     * Filters out short or generic conversations
     */
    fun isWorthExtracting(userMessage: String, assistantMessage: String): Boolean {
        // Must be substantial conversation
        if (userMessage.length < 10) return false
        
        // Check for personal information indicators
        val personalIndicators = listOf(
            "my name is", "i am", "i'm", "i live", "i work", "my ",
            "call me", "remind me", "don't forget", "remember to",
            "my mom", "my dad", "my friend", "my brother", "my sister",
            "birthday", "anniversary", "meeting", "appointment"
        )
        
        val hasPersonalInfo = personalIndicators.any { indicator ->
            userMessage.lowercase().contains(indicator)
        }
        
        return hasPersonalInfo
    }
    
    /**
     * Internal data class for JSON parsing
     */
    private data class ExtractionResponse(
        val facts: List<String>? = null,
        val relationships: List<RelationshipResponse>? = null,
        val reminders: List<ReminderResponse>? = null,
        val nothing_new: Boolean? = null
    )
    
    private data class RelationshipResponse(
        val name: String? = null,
        val relation: String? = null,
        val note: String? = null
    )
    
    private data class ReminderResponse(
        val text: String? = null,
        val type: String? = null,
        val target: String? = null,
        val due_date: String? = null
    )
}
