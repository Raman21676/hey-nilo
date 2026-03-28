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
import androidx.compose.material.icons.filled.Warning
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

/**
 * FIX: Global download state that persists across navigation using Compose-observable state
 * This ensures downloads continue even when user navigates away, and UI updates properly
 */
object DownloadManager {
    data class DownloadInfo(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
        val completed: Boolean = false,
        val error: String? = null
    )
    
    // FIX: Use SnapshotStateMap which is observable by Compose
    val downloadStates = androidx.compose.runtime.mutableStateMapOf<String, DownloadInfo>()
    
    // FIX: Track completion callbacks so we can notify when screen reopens
    private val completionCallbacks = mutableMapOf<String, (Boolean, String?) -> Unit>()
    
    fun updateProgress(modelId: String, progress: Float, bytesDownloaded: Long, totalBytes: Long) {
        downloadStates[modelId] = DownloadInfo(
            isDownloading = true,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes
        )
    }
    
    fun markComplete(modelId: String) {
        downloadStates[modelId] = DownloadInfo(completed = true)
        completionCallbacks[modelId]?.invoke(true, null)
        completionCallbacks.remove(modelId)
    }
    
    fun markError(modelId: String, error: String) {
        downloadStates[modelId] = DownloadInfo(error = error)
        completionCallbacks[modelId]?.invoke(false, error)
        completionCallbacks.remove(modelId)
    }
    
    fun clear(modelId: String) {
        downloadStates.remove(modelId)
        completionCallbacks.remove(modelId)
    }
    
    fun isDownloading(modelId: String): Boolean {
        return downloadStates[modelId]?.isDownloading == true
    }
    
    fun registerCallback(modelId: String, callback: (Boolean, String?) -> Unit) {
        completionCallbacks[modelId] = callback
    }
    
    fun getState(modelId: String): DownloadInfo? {
        return downloadStates[modelId]
    }
}

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
    // FIX: Directly observe the global download manager's state - it's now a SnapshotStateMap
    val downloadProgress by remember { 
        derivedStateOf {
            DownloadManager.downloadStates.mapValues { 
                DownloadState(
                    isDownloading = it.value.isDownloading,
                    progress = it.value.progress,
                    bytesDownloaded = it.value.bytesDownloaded,
                    totalBytes = it.value.totalBytes
                )
            }
        }
    }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    
    // State for incompatible model download warning dialog
    var showIncompatibleWarning by remember { mutableStateOf(false) }
    var pendingDownloadModel by remember { mutableStateOf<OfflineModelConfig?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // FIX: Check for completed downloads that happened while we were away
    LaunchedEffect(Unit) {
        // Check if any downloads completed while screen was closed
        DownloadManager.downloadStates.forEach { (modelId, info) ->
            if (info.completed && !downloadedModels.containsKey(modelId)) {
                // Download finished while we were away
                downloadedModels = downloadedModels + (modelId to true)
                Log.i(TAG, "Download completed while away: $modelId")
            }
        }
    }
    
    // Show snackbar messages
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }
    
    // Scan for downloaded models - merge file check with download manager state
    LaunchedEffect(Unit) {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        val downloaded = allModels.associate { model ->
            val file = File(modelsDir, model.fileName)
            // FIX: Check file existence OR if download manager says it's completed
            // This prevents the "+ Get" bug where file.exists() might temporarily fail
            val fileExists = file.exists()
            val downloadCompleted = DownloadManager.getState(model.id)?.completed == true
            val isDownloaded = fileExists || downloadCompleted
            if (downloadCompleted && !fileExists) {
                Log.w(TAG, "Model ${model.id} marked as completed but file not found - waiting for filesystem")
            }
            model.id to isDownloaded
        }
        downloadedModels = downloaded
        Log.i(TAG, "Device: ${String.format("%.2f", totalRamGB)}GB total, " +
                   "${String.format("%.2f", freeRamGB)}GB free. " +
                   "Scanned ${allModels.size} models, found ${downloaded.count { it.value }} downloaded")
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
                        onDownload = { model, isCompatible ->
                            // Check compatibility - if incompatible, show warning first
                            if (!isCompatible) {
                                pendingDownloadModel = model
                                showIncompatibleWarning = true
                                return@RAMTierSection
                            }
                            
                            // FIX: Start download with global tracking - UI auto-updates via derivedStateOf
                            DownloadManager.updateProgress(model.id, 0f, 0, 0)
                            
                            startDownload(
                                context = context,
                                model = model,
                                onProgress = { progress, bytesDownloaded, totalBytes ->
                                    // Just update global state - UI will auto-update
                                    DownloadManager.updateProgress(model.id, progress, bytesDownloaded, totalBytes)
                                },
                                onComplete = { success, error ->
                                    if (success) {
                                        DownloadManager.markComplete(model.id)
                                        downloadedModels = downloadedModels + (model.id to true)
                                        selectedModelId = model.id
                                        snackbarMessage = "${model.displayName} downloaded successfully!"
                                    } else {
                                        DownloadManager.markError(model.id, error ?: "Unknown error")
                                        snackbarMessage = "Download failed: $error"
                                        // Only clear error state after delay, keep completed state
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            DownloadManager.clear(model.id)
                                        }, 5000)
                                    }
                                    // Note: We don't clear completed downloads - they persist for UI state
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
                            DownloadManager.clear(model.id)
                            snackbarMessage = "Download cancelled"
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Warning dialog for incompatible model download
    if (showIncompatibleWarning && pendingDownloadModel != null) {
        val model = pendingDownloadModel!!
        AlertDialog(
            onDismissRequest = { 
                showIncompatibleWarning = false
                pendingDownloadModel = null
            },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Device Compatibility Warning")
                }
            },
            text = {
                Column {
                    Text(
                        "${model.displayName} requires ${model.minRamGB}GB RAM, " +
                        "but your device has ${String.format("%.1f", totalRamGB)}GB RAM."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Downloading this model may cause:",
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• App crashes or freezes")
                    Text("• Very slow performance")
                    Text("• System instability")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Download at your own risk?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIncompatibleWarning = false
                        val modelToDownload = pendingDownloadModel
                        pendingDownloadModel = null
                        modelToDownload?.let { m ->
                            // Start download for incompatible model
                            DownloadManager.updateProgress(m.id, 0f, 0, 0)
                            startDownload(
                                context = context,
                                model = m,
                                onProgress = { progress, bytesDownloaded, totalBytes ->
                                    DownloadManager.updateProgress(m.id, progress, bytesDownloaded, totalBytes)
                                },
                                onComplete = { success, error ->
                                    if (success) {
                                        DownloadManager.markComplete(m.id)
                                        downloadedModels = downloadedModels + (m.id to true)
                                        selectedModelId = m.id
                                        snackbarMessage = "${m.displayName} downloaded successfully!"
                                    } else {
                                        DownloadManager.markError(m.id, error ?: "Unknown error")
                                        snackbarMessage = "Download failed: $error"
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            DownloadManager.clear(m.id)
                                        }, 5000)
                                    }
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Download Anyway")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showIncompatibleWarning = false
                        pendingDownloadModel = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
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
    onDownload: (OfflineModelConfig, Boolean) -> Unit,
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
            val isModelCompatible = model.isCompatibleWith(totalRamGB.toInt())
            ModelCard(
                model = model,
                totalRamGB = totalRamGB,
                freeRamGB = freeRamGB,
                isSelected = selectedModelId == model.id,
                isDownloaded = downloadedModels[model.id] == true,
                downloadState = downloadState,
                onSelect = { onModelSelected(model) },
                onDownload = { compat -> onDownload(model, compat) },
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
    onDownload: (Boolean) -> Unit,
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
                    else -> {
                        // Not downloaded - show download button for ALL models (even incompatible)
                        // Incompatible models will show a warning dialog before downloading
                        FilledTonalButton(
                            onClick = { onDownload(isCompatible) },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = if (isCompatible) {
                                ButtonDefaults.filledTonalButtonColors()
                            } else {
                                // Use error colors for incompatible models to indicate risk
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
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
    
    // FIX: Create OkHttp client with redirect following enabled
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    
    val request = okhttp3.Request.Builder()
        .url(model.downloadUrl)
        .header("User-Agent", "Hey-Nilo/1.0 (Android)")
        .header("Accept", "*/*")
        .build()
    
    val call = client.newCall(request)
    activeDownloads[model.id] = call
    
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Download failed for ${model.displayName}: $errorMsg", e)
            activeDownloads.remove(model.id)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(false, "Network error: $errorMsg")
            }
        }
        
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            // FIX: Log the response for debugging
            Log.d(TAG, "Download response for ${model.displayName}: HTTP ${response.code}, " +
                      "content-length: ${response.body?.contentLength()}, " +
                      "redirected: ${response.isRedirect}, " +
                      "final-url: ${response.request.url}")
            
            if (!response.isSuccessful) {
                val errorMsg = "HTTP ${response.code}: ${response.message}"
                Log.e(TAG, "Download failed for ${model.displayName}: $errorMsg")
                activeDownloads.remove(model.id)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(false, errorMsg)
                }
                return
            }
            
            // FIX: Check if body is null
            if (response.body == null) {
                Log.e(TAG, "Download failed for ${model.displayName}: Empty response body")
                activeDownloads.remove(model.id)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(false, "Empty response from server")
                }
                return
            }
            
            try {
                val modelsDir = java.io.File(context.getExternalFilesDir(null), "models")
                if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                    throw java.io.IOException("Failed to create models directory: ${modelsDir.absolutePath}")
                }
                
                val outputFile = java.io.File(modelsDir, model.fileName)
                val tempFile = java.io.File(modelsDir, "${model.fileName}.tmp")
                
                // Delete temp file if exists
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                
                val totalBytes = response.body!!.contentLength()
                var downloadedBytes = 0L
                
                Log.i(TAG, "Downloading ${model.displayName}: ${formatBytes(totalBytes)} total")
                
                response.body!!.byteStream().use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastProgressUpdate = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            // Update progress every 200ms
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 200) {
                                val progress = if (totalBytes > 0) {
                                    downloadedBytes.toFloat() / totalBytes
                                } else 0f
                                
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onProgress(progress, downloadedBytes, totalBytes)
                                }
                                lastProgressUpdate = now
                            }
                        }
                        
                        // Ensure all data is written
                        output.flush()
                    }
                }
                
                // Verify download completed
                if (totalBytes > 0 && tempFile.length() < totalBytes * 0.99) {
                    throw java.io.IOException("Download incomplete: ${tempFile.length()}/$totalBytes bytes")
                }
                
                // Rename temp file to final
                if (!tempFile.renameTo(outputFile)) {
                    throw java.io.IOException("Failed to move downloaded file to final location")
                }
                
                activeDownloads.remove(model.id)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(true, null)
                }
                
                Log.i(TAG, "Download completed: ${model.displayName} -> ${outputFile.absolutePath} (${formatBytes(outputFile.length())})")
                
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Error saving download for ${model.displayName}: $errorMsg", e)
                activeDownloads.remove(model.id)
                // Clean up temp file on error
                try {
                    val tempFile = java.io.File(context.getExternalFilesDir(null), "models/${model.fileName}.tmp")
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Exception) {}
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(false, "Save error: $errorMsg")
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
