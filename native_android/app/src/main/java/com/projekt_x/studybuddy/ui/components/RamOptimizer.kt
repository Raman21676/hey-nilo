package com.projekt_x.studybuddy.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * RAM Optimizer Button and Dialog
 * One-click memory optimization for the app
 */
@Composable
fun RamOptimizerButton(
    onOptimize: () -> Unit,
    isOptimizing: Boolean,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onOptimize,
        enabled = !isOptimizing,
        modifier = modifier
    ) {
        if (isOptimizing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Optimize RAM",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Dialog showing optimization progress and results
 */
@Composable
fun RamOptimizerDialog(
    isVisible: Boolean,
    isOptimizing: Boolean,
    progress: Float,
    result: OptimizationResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return
    
    AlertDialog(
        onDismissRequest = { if (!isOptimizing) onDismiss() },
        title = {
            Text(
                text = if (isOptimizing) "Optimizing Memory..." else "Optimization Complete!",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isOptimizing) {
                    // Progress indicator
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = "Releasing unused memory...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (result != null) {
                    // Results
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    // Before/After stats
                    OptimizationStat(
                        label = "Before",
                        value = "${result.memoryBeforeMB}MB",
                        color = MaterialTheme.colorScheme.error
                    )
                    
                    OptimizationStat(
                        label = "After",
                        value = "${result.memoryAfterMB}MB",
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Freed amount
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Freed ${result.memoryFreedMB}MB",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    if (result.memoryFreedMB > 100) {
                        Text(
                            text = "🚀 Ready for faster inference!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isOptimizing) {
                Button(onClick = onDismiss) {
                    Text("Cool!")
                }
            }
        },
        dismissButton = {
            if (isOptimizing) {
                TextButton(
                    onClick = onDismiss,
                    enabled = false
                ) {
                    Text("Please wait...")
                }
            }
        }
    )
}

@Composable
private fun OptimizationStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * Result data class for optimization
 */
data class OptimizationResult(
    val memoryBeforeMB: Int,
    val memoryAfterMB: Int,
    val memoryFreedMB: Int
)
