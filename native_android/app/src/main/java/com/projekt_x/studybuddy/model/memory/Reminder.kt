package com.projekt_x.studybuddy.model.memory

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reminders container - stores all tasks and reminders
 * 
 * File: memory/work/active/reminders.json
 * Retention: 1 YEAR (then archived)
 */
data class Reminders(
    @SerializedName("version")
    val version: Int = 1,
    
    @SerializedName("last_updated")
    val lastUpdated: String = "",
    
    @SerializedName("reminders")
    val reminders: MutableList<Reminder> = mutableListOf()
) {
    /**
     * Get pending (incomplete) reminders
     */
    fun getPending(): List<Reminder> {
        return reminders.filter { it.status == ReminderStatus.PENDING }
    }
    
    /**
     * Get completed reminders
     */
    fun getCompleted(): List<Reminder> {
        return reminders.filter { it.status == ReminderStatus.COMPLETED }
    }
    
    /**
     * Get overdue reminders (past due date)
     */
    fun getOverdue(): List<Reminder> {
        // This is a simplified check - in production, parse dates properly
        return reminders.filter { 
            it.status == ReminderStatus.PENDING && 
            it.dueDate != null &&
            isPastDue(it.dueDate)
        }
    }
    
    /**
     * Get high priority reminders
     */
    fun getHighPriority(): List<Reminder> {
        return reminders.filter { 
            it.priority == ReminderPriority.HIGH && 
            it.status == ReminderStatus.PENDING 
        }
    }
    
    /**
     * Find reminder by ID
     */
    fun findById(id: String): Reminder? {
        return reminders.find { it.id == id }
    }
    
    /**
     * Add new reminder
     */
    fun add(reminder: Reminder) {
        reminders.add(reminder)
    }
    
    /**
     * Mark reminder as completed
     */
    fun completeById(id: String): Boolean {
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = reminders[index]
            reminders[index] = existing.copy(
                status = ReminderStatus.COMPLETED,
                completedAt = getCurrentTimestamp()
            )
            return true
        }
        return false
    }
    
    /**
     * Delete reminder by ID
     */
    fun deleteById(id: String): Boolean {
        return reminders.removeAll { it.id == id }
    }
    
    /**
     * Update reminder by ID
     */
    fun updateById(id: String, updates: ReminderUpdates): Boolean {
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = reminders[index]
            reminders[index] = existing.copy(
                text = updates.text ?: existing.text,
                type = updates.type ?: existing.type,
                targetPerson = updates.targetPerson ?: existing.targetPerson,
                dueDate = updates.dueDate ?: existing.dueDate,
                priority = updates.priority ?: existing.priority,
                status = updates.status ?: existing.status
            )
            return true
        }
        return false
    }
    
    /**
     * Search reminders by text
     */
    fun search(query: String): List<Reminder> {
        val lowerQuery = query.lowercase()
        return reminders.filter { 
            it.text.lowercase().contains(lowerQuery) ||
            it.targetPerson?.lowercase()?.contains(lowerQuery) == true
        }
    }
    
    /**
     * Format reminders for context block
     * Returns top N pending reminders
     */
    fun formatForContext(maxCount: Int = 3): String {
        val pending = getPending().take(maxCount)
        if (pending.isEmpty()) return ""
        
        return pending.joinToString(" · ") { it.formatForContext() }
    }
    
    /**
     * Get count of pending reminders
     */
    fun getPendingCount(): Int {
        return getPending().size
    }
    
    companion object {
        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        
        fun default(): Reminders = Reminders()
        
        private fun getCurrentTimestamp(): String {
            return isoFormat.format(Date())
        }
        
        private fun isPastDue(dueDate: String): Boolean {
            return try {
                val due = isoFormat.parse(dueDate)
                due != null && due.before(Date())
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Individual reminder/task entry
 */
data class Reminder(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("type")
    val type: ReminderType,
    
    @SerializedName("text")
    val text: String,
    
    @SerializedName("target_person")
    val targetPerson: String? = null,
    
    @SerializedName("due_date")
    val dueDate: String? = null,
    
    @SerializedName("priority")
    val priority: ReminderPriority = ReminderPriority.NORMAL,
    
    @SerializedName("status")
    val status: ReminderStatus = ReminderStatus.PENDING,
    
    @SerializedName("created_at")
    val createdAt: String = "",
    
    @SerializedName("completed_at")
    val completedAt: String? = null
) {
    /**
     * Format for context block display
     * Example: "Call mom" or "Message: Rohan about meeting"
     */
    fun formatForContext(): String {
        return when (type) {
            ReminderType.CALL -> "Call ${targetPerson ?: "someone"}"
            ReminderType.MESSAGE_WHATSAPP -> "WhatsApp ${targetPerson ?: ""}: $text"
            ReminderType.MESSAGE_INSTAGRAM -> "DM ${targetPerson ?: ""}: $text"
            ReminderType.MESSAGE_SMS -> "Text ${targetPerson ?: ""}: $text"
            ReminderType.ALARM -> "Alarm: $text"
            ReminderType.REMINDER -> text
            ReminderType.NOTE -> "Note: $text"
        }
    }
    
    /**
     * Check if reminder is overdue
     */
    fun isOverdue(): Boolean {
        if (status != ReminderStatus.PENDING) return false
        if (dueDate == null) return false
        
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            val due = format.parse(dueDate)
            due != null && due.before(Date())
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Reminder update data class (partial updates)
 */
data class ReminderUpdates(
    val text: String? = null,
    val type: ReminderType? = null,
    val targetPerson: String? = null,
    val dueDate: String? = null,
    val priority: ReminderPriority? = null,
    val status: ReminderStatus? = null
)

/**
 * Reminder types
 */
enum class ReminderType(val displayName: String) {
    CALL("Call"),
    ALARM("Alarm"),
    MESSAGE_WHATSAPP("WhatsApp Message"),
    MESSAGE_INSTAGRAM("Instagram DM"),
    MESSAGE_SMS("SMS"),
    REMINDER("Reminder"),
    NOTE("Note")
}

/**
 * Reminder priority levels
 */
enum class ReminderPriority(val displayName: String) {
    LOW("Low"),
    NORMAL("Normal"),
    HIGH("High"),
    URGENT("Urgent")
}

/**
 * Reminder status
 */
enum class ReminderStatus(val displayName: String) {
    PENDING("Pending"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled")
}
