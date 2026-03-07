package com.projekt_x.studybuddy.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projekt_x.studybuddy.PerformanceMetrics

/**
 * Compact status bar showing real-time performance metrics
 */
@Composable
fun PerformanceStatusBar(
    metrics: PerformanceMetrics,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (metrics.temperatureStatus) {
        PerformanceMetrics.TemperatureStatus.HOT -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        PerformanceMetrics.TemperatureStatus.WARM -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        color = backgroundColor,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Temperature indicator
            TemperatureIndicator(metrics.cpuTemperature, metrics.temperatureStatus)
            
            // Center: Performance metrics (when generating)
            if (metrics.isGenerating) {
                PerformanceIndicator(
                    tokensPerSecond = metrics.tokensPerSecond,
                    tokensGenerated = metrics.tokensGenerated
                )
            } else {
                IdleIndicator()
            }
            
            // Right side: Memory usage
            MemoryIndicator(
                usedMB = metrics.memoryUsedMB,
                totalMB = metrics.memoryTotalMB,
                percent = metrics.memoryUsagePercent
            )
        }
    }
}

@Composable
private fun TemperatureIndicator(
    temperature: Float,
    status: PerformanceMetrics.TemperatureStatus
) {
    val (icon, color, label) = when (status) {
        PerformanceMetrics.TemperatureStatus.UNKNOWN -> 
            Triple(Icons.Default.Settings, Color.Gray, "--°C")
        PerformanceMetrics.TemperatureStatus.COOL -> 
            Triple(Icons.Default.CheckCircle, Color(0xFF2196F3), "${temperature.toInt()}°C")
        PerformanceMetrics.TemperatureStatus.NORMAL -> 
            Triple(Icons.Default.CheckCircle, Color(0xFF4CAF50), "${temperature.toInt()}°C")
        PerformanceMetrics.TemperatureStatus.WARM -> 
            Triple(Icons.Default.Warning, Color(0xFFFF9800), "${temperature.toInt()}°C")
        PerformanceMetrics.TemperatureStatus.HOT -> 
            Triple(Icons.Default.Warning, Color(0xFFF44336), "${temperature.toInt()}°C")
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "CPU Temperature",
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun PerformanceIndicator(
    tokensPerSecond: Float,
    tokensGenerated: Int
) {
    val perfColor = when {
        tokensPerSecond >= 7f -> Color(0xFF4CAF50)
        tokensPerSecond >= 5f -> Color(0xFF8BC34A)
        tokensPerSecond >= 3f -> Color(0xFFFFC107)
        else -> Color(0xFFFF9800)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Static dot with color
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(perfColor)
        )
        
        // Tokens/sec
        Text(
            text = String.format("%.1f t/s", tokensPerSecond),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = perfColor
        )
        
        // Token count
        Text(
            text = "$tokensGenerated",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IdleIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Ready",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Ready",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MemoryIndicator(
    usedMB: Int,
    totalMB: Int,
    percent: Float
) {
    val memoryColor = when {
        percent > 85f -> Color(0xFFF44336)
        percent > 70f -> Color(0xFFFF9800)
        percent > 50f -> Color(0xFFFFC107)
        else -> Color(0xFF4CAF50)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mini progress bar
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent / 100f)
                    .background(memoryColor)
            )
        }
        
        // Memory text
        if (usedMB > 0 && totalMB > 0) {
            Text(
                text = "${usedMB}MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
