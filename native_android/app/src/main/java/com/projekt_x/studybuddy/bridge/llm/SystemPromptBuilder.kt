package com.projekt_x.studybuddy.bridge.llm

import com.projekt_x.studybuddy.bridge.LlamaBridge
import com.projekt_x.studybuddy.bridge.MemoryManager

object SystemPromptBuilder {

    fun buildSystemPrompt(memoryManager: Any? = null, maxTokens: Int = 300): String {
        // Simple, clean system prompt. No tags. No markers. No memory context here.
        return "You are SMITH, a helpful personal AI assistant. " +
               "Answer every question directly and completely. " +
               "When asked to list things, list them. " +
               "Do not introduce yourself unless the user asks who you are."
    }

    fun buildSystemPromptWithMemory(userName: String?, userAge: String?, userOccupation: String?): String {
        val base = buildSystemPrompt()
        
        val facts = mutableListOf<String>()
        userName?.let { facts.add("The user's name is $it.") }
        userAge?.let { facts.add("The user is $it years old.") }
        userOccupation?.let { facts.add("The user works as $it.") }
        
        return if (facts.isEmpty()) base
        else "$base ${facts.joinToString(" ")}"
    }
}
