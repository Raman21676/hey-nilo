package com.projekt_x.studybuddy.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projekt_x.studybuddy.model.OfflineModelConfig
import com.projekt_x.studybuddy.model.MODELS_BY_RAM_TIER
import com.projekt_x.studybuddy.model.RAMTier
import com.projekt_x.studybuddy.bridge.DeviceInfo
import java.io.File

private const val TAG = "OfflineModelPicker"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineModelPickerScreen(
    context: Context,
    currentModelId: String?,
    onModelSelected: (OfflineModelConfig) -> Unit,
    onBack: () -> Unit
) {
    // Get full device specs
    val deviceSpecs = remember { DeviceInfo.getDeviceSpecs(context) }
    val totalRamGB = remember { deviceSpecs.totalRamGB }
    val freeRamGB = remember { deviceSpecs.availableRamMB / 1024f }
    
    // Show ALL models from all tiers
    val allModels = remember { MODELS_BY_RAM_TIER.values.flatten() }
    
    var selectedModelId by remember { mutableStateOf(currentModelId) }
    var downloadedModels by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // Track download progress per model
    var downloadProgress by remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show snackbar messages
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }
    
    // Scan for downloaded models
    LaunchedEffect(Unit) {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        val downloaded = allModels.associate { model ->
            val file = File(modelsDir, model.fileName)
            model.id to file.exists()
        }
        downloadedModels = downloaded
        Log.i(TAG, "Device: ${String.format("%.2f", totalRamGB)}GB total, " +
                   "${String.format("%.2f", freeRamGB)}GB free. " +
                   "Scanned ${allModels.size} models")
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Offline Model") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Device info header with total + free RAM, recommendation based on total
            DeviceInfoHeader(deviceSpecs = deviceSpecs, totalRamGB = totalRamGB)
            
            // Legend
            LegendSection()
            
            // Models grouped by RAM tier
            RAMTier.values().forEach { tier ->
                val tierModels = MODELS_BY_RAM_TIER[tier] ?: emptyList()
                if (tierModels.isNotEmpty()) {
                    RAMTierSection(
                        tier = tier,
                        totalRamGB = totalRamGB,
                        freeRamGB = freeRamGB,
                        models = tierModels,
                        selectedModelId = selectedModelId,
                        downloadedModels = downloadedModels,
                        downloadProgress = downloadProgress,
                        onModelSelected = { model ->
                            // Check if model is compatible with device based on TOTAL RAM
                            if (model.isCompatibleWith(totalRamGB.toInt())) {
                                selectedModelId = model.id
                                if (downloadedModels[model.id] == true) {
                                    onModelSelected(model)
                                }
                            }
                        },
                        onDownload = { model ->
                            // Start download with progress tracking
                            downloadProgress = downloadProgress + (model.id to DownloadState(isDownloading = true, progress = 0f))
                            
                            startDownload(
                                context = context,
                                model = model,
                                onProgress = { progress, bytesDownloaded, totalBytes ->
                                    downloadProgress = downloadProgress + (model.id to DownloadState(
                                        isDownloading = true,
                                        progress = progress,
                                        bytesDownloaded = bytesDownloaded,
                                        totalBytes = totalBytes
                                    ))
                                },
                                onComplete = { success, error ->
                                    downloadProgress = downloadProgress - model.id
                                    if (success) {
                                        downloadedModels = downloadedModels + (model.id to true)
                                        selectedModelId = model.id
                                        snackbarMessage = "${model.displayName} downloaded successfully!"
                                    } else {
                                        snackbarMessage = "Download failed: $error"
                                    }
                                }
                            )
                        },
                        onDelete = { model ->
                            if (deleteModel(context, model)) {
                                downloadedModels = downloadedModels - model.id
                                if (selectedModelId == model.id) {
                                    selectedModelId = null
                                }
                                snackbarMessage = "${model.displayName} deleted"
                            } else {
                                snackbarMessage = "Failed to delete ${model.displayName}"
                            }
                        },
                        onCancelDownload = { model ->
                            cancelDownload(model.id)
                            downloadProgress = downloadProgress - model.id
                            snackbarMessage = "Download cancelled"
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceInfoHeader(deviceSpecs: DeviceInfo.DeviceSpecs, totalRamGB: Float) {
    val freeRamGB = deviceSpecs.availableRamMB / 1024f
    val totalRamFormatted = String.format("%.2f", totalRamGB)
    val freeRamFormatted = String.format("%.2f", freeRamGB)
    
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Title row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Your Device",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // RAM stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Total RAM
                Column {
                    Text(
                        text = "Total RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${totalRamFormatted}GB",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Free RAM (shown for info, not used for recommendation)
                Column {
                    Text(
                        text = "Free RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${freeRamFormatted}GB",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Recommendation based on TOTAL RAM (device capability)
            val recommendedTier = when {
                totalRamGB < 2.5f -> DeviceInfo.ModelTier.ULTRA_LIGHT
                totalRamGB < 3.5f -> DeviceInfo.ModelTier.LIGHT
                totalRamGB < 5f -> DeviceInfo.ModelTier.MEDIUM
                totalRamGB < 7f -> DeviceInfo.ModelTier.HIGH
                totalRamGB < 10f -> DeviceInfo.ModelTier.VERY_HIGH
                else -> DeviceInfo.ModelTier.ULTRA_HIGH
            }
            Surface(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "💡 Recommended: ${recommendedTier.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Legend:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Recommended", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Downloaded", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RAMTierSection(
    tier: RAMTier,
    totalRamGB: Float,
    freeRamGB: Float,
    models: List<OfflineModelConfig>,
    selectedModelId: String?,
    downloadedModels: Map<String, Boolean>,
    downloadProgress: Map<String, DownloadState>,
    onModelSelected: (OfflineModelConfig) -> Unit,
    onDownload: (OfflineModelConfig) -> Unit,
    onDelete: (OfflineModelConfig) -> Unit,
    onCancelDownload: (OfflineModelConfig) -> Unit
) {
    // A tier is compatible if TOTAL RAM >= minimum RAM for this tier
    val isTierCompatible = totalRamGB >= tier.minRamGB
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Tier header - show the actual RAM requirement
        Surface(
            color = if (isTierCompatible) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show appropriate label based on tier
                val headerText = when (tier) {
                    RAMTier.RAM_3GB -> "2-3GB RAM Required"
                    RAMTier.RAM_4GB -> "4GB RAM Required"
                    RAMTier.RAM_6GB -> "5-6GB RAM Required"
                    RAMTier.RAM_8GB -> "8GB RAM Required"
                    RAMTier.RAM_12GB_PLUS -> "10GB+ RAM Required"
                }
                
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isTierCompatible) 
                        MaterialTheme.colorScheme.onSecondaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isTierCompatible) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "(Not compatible)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Models in this tier
        models.forEach { model ->
            val downloadState = downloadProgress[model.id]
            ModelCard(
                model = model,
                totalRamGB = totalRamGB,
                freeRamGB = freeRamGB,
                isSelected = selectedModelId == model.id,
                isDownloaded = downloadedModels[model.id] == true,
                downloadState = downloadState,
                onSelect = { onModelSelected(model) },
                onDownload = { onDownload(model) },
                onDelete = { onDelete(model) },
                onCancelDownload = { onCancelDownload(model) }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: OfflineModelConfig,
    totalRamGB: Float,
    freeRamGB: Float,
    isSelected: Boolean,
    isDownloaded: Boolean,
    downloadState: DownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit
) {
    // Check compatibility based on TOTAL RAM (device capability)
    val isCompatible = model.isCompatibleWith(totalRamGB.toInt())
    val isDownloading = downloadState?.isDownloading == true
    
    Card(
        onClick = { if (isCompatible) onSelect() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        enabled = isCompatible,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected && isCompatible -> MaterialTheme.colorScheme.primaryContainer
                !isCompatible -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = if (isSelected && isCompatible) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection indicator (radio button style)
            if (isCompatible) {
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    enabled = isDownloaded
                )
            } else {
                // Incompatible - show disabled indicator
                RadioButton(
                    selected = false,
                    onClick = {},
                    enabled = false
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Model info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Name row with badges
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCompatible) 
                            MaterialTheme.colorScheme.onSurface 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (model.isRecommended && isCompatible) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "BEST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // Description and size
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // RAM requirement badge - show actual min RAM
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${model.sizeGB}GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (isCompatible) 
                            MaterialTheme.colorScheme.tertiaryContainer 
                        else 
                            MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        val ramText = when (model.minRamGB) {
                            2 -> "✓ Needs 2GB RAM"
                            3 -> "✓ Needs 3GB RAM"
                            4 -> "✓ Needs 4GB RAM"
                            5 -> "✓ Needs 5GB RAM"
                            6 -> "✓ Needs 6GB RAM"
                            8 -> "✓ Needs 8GB RAM"
                            10 -> "✓ Needs 10GB RAM"
                            else -> "✓ Needs ${model.minRamGB}GB RAM"
                        }
                        val incompatibleText = when (model.minRamGB) {
                            2 -> "⚠ Needs 2GB RAM"
                            3 -> "⚠ Needs 3GB RAM"
                            4 -> "⚠ Needs 4GB RAM"
                            5 -> "⚠ Needs 5GB RAM"
                            6 -> "⚠ Needs 6GB RAM"
                            8 -> "⚠ Needs 8GB RAM"
                            10 -> "⚠ Needs 10GB RAM"
                            else -> "⚠ Needs ${model.minRamGB}GB RAM"
                        }
                        Text(
                            text = if (isCompatible) ramText else incompatibleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCompatible) 
                                MaterialTheme.colorScheme.onTertiaryContainer 
                            else 
                                MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Action button area
            Box(
                modifier = Modifier.widthIn(min = 90.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isDownloading -> {
                        // Show progress with cancel button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { downloadState?.progress ?: 0f },
                                    modifier = Modifier.size(40.dp),
                                    strokeWidth = 3.dp
                                )
                                Text(
                                    text = "${((downloadState?.progress ?: 0f) * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = onCancelDownload,
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    isDownloaded -> {
                        // Downloaded - show checkmark and delete option
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = onDelete,
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Delete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    isCompatible -> {
                        // Not downloaded - show download button
                        FilledTonalButton(
                            onClick = onDownload,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Get", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    else -> {
                        // Incompatible - no download button
                        Text(
                            text = "Need ${model.minRamGB}GB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/**
 * Download state for tracking progress
 */
private data class DownloadState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0
)

/**
 * Active downloads map
 */
private val activeDownloads = mutableMapOf<String, okhttp3.Call>()

/**
 * Start actual file download with progress tracking
 */
private fun startDownload(
    context: Context,
    model: OfflineModelConfig,
    onProgress: (Float, Long, Long) -> Unit,
    onComplete: (Boolean, String?) -> Unit
) {
    Log.i(TAG, "Starting download for ${model.displayName} from ${model.downloadUrl}")
    
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    val request = okhttp3.Request.Builder()
        .url(model.downloadUrl)
        .header("User-Agent", "Hey-Nilo/1.0")
        .build()
    
    val call = client.newCall(request)
    activeDownloads[model.id] = call
    
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            Log.e(TAG, "Download failed for ${model.displayName}", e)
            activeDownloads.remove(model.id)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(false, e.message)
            }
        }
        
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            if (!response.isSuccessful) {
                activeDownloads.remove(model.id)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(false, "HTTP ${response.code}")
                }
                return
            }
            
            try {
                val modelsDir = java.io.File(context.getExternalFilesDir(null), "models")
                modelsDir.mkdirs()
                
                val outputFile = java.io.File(modelsDir, model.fileName)
                val tempFile = java.io.File(modelsDir, "${model.fileName}.tmp")
                
                val totalBytes = response.body?.contentLength() ?: -1
                var downloadedBytes = 0L
                
                response.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastProgressUpdate = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            // Update progress every 100ms
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100) {
                                val progress = if (totalBytes > 0) {
                                    downloadedBytes.toFloat() / totalBytes
                                } else 0f
                                
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onProgress(progress, downloadedBytes, totalBytes)
                                }
                                lastProgressUpdate = now
                            }
                        }
                    }
                }
                
                // Rename temp file to final
                tempFile.renameTo(outputFile)
                activeDownloads.remove(model.id)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(true, null)
                }
                
                Log.i(TAG, "Download completed: ${model.displayName} -> ${outputFile.absolutePath}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving download", e)
                activeDownloads.remove(model.id)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(false, e.message)
                }
            }
        }
    })
}

/**
 * Cancel active download
 */
private fun cancelDownload(modelId: String) {
    activeDownloads[modelId]?.cancel()
    activeDownloads.remove(modelId)
}

/**
 * Delete downloaded model file
 */
private fun deleteModel(context: Context, model: OfflineModelConfig): Boolean {
    return try {
        val modelsDir = java.io.File(context.getExternalFilesDir(null), "models")
        val file = java.io.File(modelsDir, model.fileName)
        if (file.exists()) {
            file.delete()
        }
        val tempFile = java.io.File(modelsDir, "${model.fileName}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        Log.i(TAG, "Deleted model: ${model.displayName}")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error deleting model", e)
        false
    }
}

/**
 * Format bytes to human readable string
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
