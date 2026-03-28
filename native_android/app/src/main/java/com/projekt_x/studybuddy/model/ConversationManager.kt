package com.projekt_x.studybuddy.model

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastUpdatedAt: Long = System.currentTimeMillis(),
    val mode: String // "offline" or "online"
) {
    fun updateTimestamp() {
        lastUpdatedAt = System.currentTimeMillis()
    }
}

class ConversationManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "conversation_prefs"
        private const val KEY_OFFLINE_CONVERSATIONS = "offline_conversations"
        private const val KEY_ONLINE_CONVERSATIONS = "online_conversations"
        private const val MAX_CONVERSATIONS = 3
        const val MAX_MESSAGES_PER_CHAT = 30
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Current active conversation
    private var currentConversation: Conversation? = null
    
    /**
     * Get all conversations for a mode (sorted by last updated, newest first)
     */
    fun getConversations(mode: String): List<Conversation> {
        val key = if (mode == "online") KEY_ONLINE_CONVERSATIONS else KEY_OFFLINE_CONVERSATIONS
        val json = prefs.getString(key, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<Conversation>>() {}.type
            val conversations: List<Conversation> = gson.fromJson(json, type)
            // Sort by last updated, newest first
            conversations.sortedByDescending { it.lastUpdatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Create a new conversation
     */
    fun createNewConversation(mode: String, title: String = "New Chat"): Conversation {
        // Delete oldest conversation if limit reached
        deleteOldestIfNeeded(mode)
        
        val conversation = Conversation(
            title = title,
            mode = mode
        )
        currentConversation = conversation
        saveConversation(conversation)
        return conversation
    }
    
    /**
     * Load an existing conversation
     */
    fun loadConversation(conversationId: String, mode: String): Conversation? {
        val conversations = getConversations(mode)
        currentConversation = conversations.find { it.id == conversationId }
        return currentConversation
    }
    
    /**
     * Get current active conversation
     */
    fun getCurrentConversation(): Conversation? = currentConversation
    
    /**
     * Add message to current conversation
     * Returns false if max messages reached
     */
    fun addMessage(role: String, content: String): Boolean {
        val conversation = currentConversation ?: return false
        
        // Check if max messages reached
        if (conversation.messages.size >= MAX_MESSAGES_PER_CHAT) {
            return false
        }
        
        val message = ChatMessage(role = role, content = content)
        conversation.messages.add(message)
        conversation.updateTimestamp()
        
        // Update title based on first user message if it's still default
        if (conversation.title == "New Chat" && role == "user") {
            conversation.title = content.take(30) + if (content.length > 30) "..." else ""
        }
        
        saveConversation(conversation)
        return true
    }
    
    /**
     * Check if current conversation has reached max messages
     */
    fun isMaxMessagesReached(): Boolean {
        return (currentConversation?.messages?.size ?: 0) >= MAX_MESSAGES_PER_CHAT
    }
    
    /**
     * Get message count for current conversation
     */
    fun getCurrentMessageCount(): Int {
        return currentConversation?.messages?.size ?: 0
    }
    
    /**
     * Delete a specific conversation
     */
    fun deleteConversation(conversationId: String, mode: String) {
        val conversations = getConversations(mode).toMutableList()
        conversations.removeAll { it.id == conversationId }
        saveConversations(conversations, mode)
        
        if (currentConversation?.id == conversationId) {
            currentConversation = null
        }
    }
    
    /**
     * Delete all conversations for a mode
     */
    fun deleteAllConversations(mode: String) {
        val key = if (mode == "online") KEY_ONLINE_CONVERSATIONS else KEY_OFFLINE_CONVERSATIONS
        prefs.edit().remove(key).apply()
        currentConversation = null
    }
    
    /**
     * Delete oldest conversation if limit reached
     */
    private fun deleteOldestIfNeeded(mode: String) {
        val conversations = getConversations(mode).toMutableList()
        
        if (conversations.size >= MAX_CONVERSATIONS) {
            // Sort by created time, oldest first
            val oldest = conversations.minByOrNull { it.createdAt }
            oldest?.let {
                conversations.remove(it)
                saveConversations(conversations, mode)
            }
        }
    }
    
    /**
     * Save a conversation (add or update)
     */
    private fun saveConversation(conversation: Conversation) {
        val mode = conversation.mode
        val conversations = getConversations(mode).toMutableList()
        
        // Remove existing conversation with same ID
        conversations.removeAll { it.id == conversation.id }
        
        // Add updated conversation
        conversations.add(conversation)
        
        saveConversations(conversations, mode)
    }
    
    /**
     * Save all conversations for a mode
     */
    private fun saveConversations(conversations: List<Conversation>, mode: String) {
        val key = if (mode == "online") KEY_ONLINE_CONVERSATIONS else KEY_OFFLINE_CONVERSATIONS
        val json = gson.toJson(conversations)
        prefs.edit().putString(key, json).apply()
    }
    
    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()
        val diff = now.time - date.time
        
        return when {
            diff < 24 * 60 * 60 * 1000 -> "Today" // Less than 24 hours
            diff < 48 * 60 * 60 * 1000 -> "Yesterday"
            else -> {
                val calendar = Calendar.getInstance()
                calendar.time = date
                val day = calendar.get(Calendar.DAY_OF_MONTH)
                val month = calendar.get(Calendar.MONTH) + 1
                val year = calendar.get(Calendar.YEAR)
                "$day/$month/$year"
            }
        }
    }
    
    /**
     * Get conversation preview text (last message)
     */
    fun getConversationPreview(conversation: Conversation): String {
        return conversation.messages.lastOrNull()?.content?.take(50) 
            ?: "No messages yet"
    }
}