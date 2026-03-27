package com.projekt_x.studybuddy.bridge.llm

import com.projekt_x.studybuddy.bridge.LlamaBridge
import com.projekt_x.studybuddy.bridge.MemoryManager

/**
 * BUG FIX 5: Shared system prompt builder for ALL providers
 * Ensures every model (TinyLlama, OpenAI, Claude, DeepSeek, Kimi) gets:
 * 1. Identity prompt ("You are Nilo...")
 * 2. Memory context block (user info, relationships, reminders)
 * 
 * This guarantees consistent behavior across all modes (offline/online).
 */
object SystemPromptBuilder {
    
    /**
     * Build complete system prompt with identity and memory context
     * 
     * @param memoryManager Memory manager to get context from (can be null)
     * @param maxTokens Maximum tokens for memory block (default 300)
     * @return Complete system prompt string
     */
    fun buildSystemPrompt(memoryManager: MemoryManager?, maxTokens: Int = 300): String {
        // BALANCED: Helpful responses without forced self-introduction
        val identity = """You are Nilo, a helpful AI assistant. 
Answer questions clearly and completely. Be concise but don't skip important details.
Never start responses with "I am Nilo" unless asked who you are."""
        
        // Get memory context block (now in plain English, no tags)
        val memoryBlock = try {
            memoryManager?.buildContextBlock(maxTokens) ?: ""
        } catch (e: Exception) {
            ""
        }
        
        return if (memoryBlock.isBlank()) {
            identity
        } else {
            // Combine identity with memory context
            "$identity\n\nUser info: $memoryBlock"
        }
    }
    
    /**
     * Build system prompt for offline TinyLlama model
     * Same as buildSystemPrompt but with optional custom identity
     */
    fun buildOfflineSystemPrompt(
        memoryManager: MemoryManager?,
        customSystemPrompt: String? = null,
        maxTokens: Int = 300
    ): String {
        val identity = customSystemPrompt ?: LlamaBridge.DEFAULT_SYSTEM_PROMPT
        
        val memoryBlock = try {
            memoryManager?.buildContextBlock(maxTokens) ?: ""
        } catch (e: Exception) {
            ""
        }
        
        return if (memoryBlock.isBlank()) {
            identity
        } else {
            "$identity\n\n$memoryBlock"
        }
    }
}
