package com.projekt_x.studybuddy.bridge

import android.util.Log

/**
 * Classifies user queries into response length categories.
 * Used to dynamically set max_tokens for LLM generation.
 */
object QueryClassifier {
    
    private const val TAG = "QueryClassifier"
    
    enum class ResponseLength {
        SHORT,   // ~80 tokens - greetings, yes/no, simple facts
        MEDIUM,  // ~200 tokens - explanations, how-to, opinions
        LONG     // ~400 tokens - essays, detailed technical, storytelling
    }
    
    data class ClassificationResult(
        val length: ResponseLength,
        val maxTokens: Int,
        val maxSentences: Int,
        val maxChars: Int
    )
    
    // Keywords that indicate SHORT responses
    private val SHORT_INDICATORS = setOf(
        "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
        "how are you", "how're you", "what's up", "sup",
        "yes", "no", "yeah", "nope", "sure", "okay", "ok",
        "bye", "goodbye", "see you", "later",
        "thanks", "thank you", "appreciate it",
        "who are you", "what is your name", "your name",
        "what time", "what day", "what date", "today",
        "stop", "quit", "exit", "close"
    )
    
    // Keywords that indicate LONG responses
    private val LONG_INDICATORS = setOf(
        "explain in detail", "detailed explanation", "comprehensive",
        "write a story", "tell me a story", "creative writing",
        "essay", "article", "blog post", "write about",
        "compare and contrast", "pros and cons", "advantages and disadvantages",
        "history of", "origin of", "evolution of",
        "step by step", "detailed guide", "tutorial",
        "analyze", "analysis", "in depth", "in-depth",
        "list all", "name all", "all the", "every single",
        "code", "program", "script", "function", "algorithm",
        "recipe", "ingredients", "instructions",
        "biography", "life story", "about himself", "about herself"
    )
    
    // Keywords that indicate MEDIUM responses (default category)
    private val MEDIUM_INDICATORS = setOf(
        "explain", "how to", "how do", "how does", "what is", "what are",
        "why", "when", "where", "who", "which",
        "describe", "tell me about", "what about",
        "difference between", "vs", "versus",
        "example", "examples", "for instance",
        "meaning", "definition", "define",
        "suggest", "recommend", "advice",
        "help me", "can you help", "i need help",
        "opinion", "think about", "thoughts on",
        "weather", "forecast", "news"
    )
    
    /**
     * Classify a query and return the appropriate response parameters
     */
    fun classify(query: String): ClassificationResult {
        val normalizedQuery = query.lowercase().trim()
        
        val length = when {
            isShortQuery(normalizedQuery) -> ResponseLength.SHORT
            isLongQuery(normalizedQuery) -> ResponseLength.LONG
            else -> ResponseLength.MEDIUM
        }
        
        // NOTE: Limits are now very high - LLM decides response length naturally
        // Only role leakage detection will stop generation (not arbitrary limits)
        return when (length) {
            ResponseLength.SHORT -> ClassificationResult(
                length = length,
                maxTokens = 512,   // ~1-2 sentences for greetings
                maxSentences = 50, // Very high - let LLM decide
                maxChars = 2000    // Very high - let LLM decide
            )
            ResponseLength.MEDIUM -> ClassificationResult(
                length = length,
                maxTokens = 1024,  // ~4-6 sentences for explanations
                maxSentences = 50, // Very high - let LLM decide
                maxChars = 4000    // Very high - let LLM decide
            )
            ResponseLength.LONG -> ClassificationResult(
                length = length,
                maxTokens = 2048,  // For detailed responses, stories, etc.
                maxSentences = 100,// Very high - let LLM decide
                maxChars = 8000    // Very high - let LLM decide
            )
        }.also {
            Log.d(TAG, "Query classified as ${it.length}: '$query' -> maxTokens=${it.maxTokens} (LLM decides actual length)")
        }
    }
    
    private fun isShortQuery(query: String): Boolean {
        // Check for exact matches or contained phrases
        for (indicator in SHORT_INDICATORS) {
            if (query.contains(indicator)) {
                return true
            }
        }
        
        // Very short queries (5 words or less) are usually short responses
        val wordCount = query.split("\\s+".toRegex()).size
        if (wordCount <= 3) {
            return true
        }
        
        // Questions that can be answered with a single sentence
        if (query.startsWith("who is") && wordCount <= 5) return true
        if (query.startsWith("what is") && wordCount <= 5) return true
        if (query.startsWith("where is") && wordCount <= 5) return true
        if (query.startsWith("when is") && wordCount <= 5) return true
        
        return false
    }
    
    private fun isLongQuery(query: String): Boolean {
        for (indicator in LONG_INDICATORS) {
            if (query.contains(indicator)) {
                return true
            }
        }
        
        // Multiple questions in one query usually need longer responses
        val questionMarks = query.count { it == '?' }
        if (questionMarks >= 2) {
            return true
        }
        
        // Very long queries likely need detailed responses
        val wordCount = query.split("\\s+".toRegex()).size
        if (wordCount > 20) {
            return true
        }
        
        return false
    }
    
    /**
     * Get max tokens for a query (convenience method)
     */
    fun getMaxTokens(query: String): Int = classify(query).maxTokens
    
    /**
     * Check if the response has exceeded the expected length for the query
     */
    fun hasExceededLength(query: String, currentSentences: Int, currentChars: Int): Boolean {
        val classification = classify(query)
        return currentSentences >= classification.maxSentences || 
               currentChars >= classification.maxChars
    }
}
