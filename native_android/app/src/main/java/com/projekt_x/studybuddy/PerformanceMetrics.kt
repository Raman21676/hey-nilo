package com.projekt_x.studybuddy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real-time performance metrics for the status bar
 */
data class PerformanceMetrics(
    val cpuTemperature: Float = 0f,
    val tokensPerSecond: Float = 0f,
    val tokensGenerated: Int = 0,
    val totalTokens: Int = 0,
    val memoryUsedMB: Int = 0,
    val memoryTotalMB: Int = 0,
    val isGenerating: Boolean = false,
    val threadCount: Int = 4,
    val contextSize: Int = 1024,
    val generationTimeMs: Long = 0
) {
    val memoryUsagePercent: Float
        get() = if (memoryTotalMB > 0) (memoryUsedMB.toFloat() / memoryTotalMB.toFloat()) * 100f else 0f
    
    val temperatureStatus: TemperatureStatus
        get() = when {
            cpuTemperature <= 0 -> TemperatureStatus.UNKNOWN
            cpuTemperature < 45f -> TemperatureStatus.COOL
            cpuTemperature < 60f -> TemperatureStatus.NORMAL
            cpuTemperature < 70f -> TemperatureStatus.WARM
            else -> TemperatureStatus.HOT
        }
    
    val performanceStatus: PerformanceStatus
        get() = when {
            tokensPerSecond >= 7f -> PerformanceStatus.EXCELLENT
            tokensPerSecond >= 5f -> PerformanceStatus.GOOD
            tokensPerSecond >= 3f -> PerformanceStatus.FAIR
            tokensPerSecond > 0f -> PerformanceStatus.SLOW
            else -> PerformanceStatus.IDLE
        }
    
    enum class TemperatureStatus {
        UNKNOWN, COOL, NORMAL, WARM, HOT
    }
    
    enum class PerformanceStatus {
        IDLE, SLOW, FAIR, GOOD, EXCELLENT
    }
}

/**
 * Mutable state holder for performance metrics
 */
class PerformanceMetricsState {
    var metrics by mutableStateOf(PerformanceMetrics())
        private set
    
    private var generationStartTime: Long = 0
    private var tokenCount: Int = 0
    
    fun startGeneration(threadCount: Int, contextSize: Int) {
        generationStartTime = System.currentTimeMillis()
        tokenCount = 0
        metrics = metrics.copy(
            isGenerating = true,
            tokensGenerated = 0,
            tokensPerSecond = 0f,
            threadCount = threadCount,
            contextSize = contextSize,
            generationTimeMs = 0
        )
    }
    
    fun onTokenGenerated() {
        tokenCount++
        val elapsed = System.currentTimeMillis() - generationStartTime
        val tps = if (elapsed > 0) (tokenCount * 1000f / elapsed) else 0f
        
        metrics = metrics.copy(
            tokensGenerated = tokenCount,
            tokensPerSecond = tps,
            generationTimeMs = elapsed
        )
    }
    
    fun stopGeneration() {
        val elapsed = System.currentTimeMillis() - generationStartTime
        metrics = metrics.copy(
            isGenerating = false,
            generationTimeMs = elapsed,
            totalTokens = metrics.totalTokens + tokenCount
        )
    }
    
    fun updateSystemStats(
        temperature: Float,
        memoryUsed: Int,
        memoryTotal: Int,
        threadCount: Int? = null,
        contextSize: Int? = null
    ) {
        metrics = metrics.copy(
            cpuTemperature = temperature,
            memoryUsedMB = memoryUsed,
            memoryTotalMB = memoryTotal,
            threadCount = threadCount ?: metrics.threadCount,
            contextSize = contextSize ?: metrics.contextSize
        )
    }
    
    fun reset() {
        metrics = PerformanceMetrics()
        tokenCount = 0
        generationStartTime = 0
    }
}
