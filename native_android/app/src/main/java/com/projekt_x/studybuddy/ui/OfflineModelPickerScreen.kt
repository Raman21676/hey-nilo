package com.projekt_x.studybuddy.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import com.projekt_x.studybuddy.model.CustomModelManager
import com.projekt_x.studybuddy.model.ModelCategory
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
            
            // Custom Model Section
            CustomModelSection(
                context = context,
                selectedModelId = selectedModelId,
                onModelSelected = { model ->
                    selectedModelId = model.id
                    onModelSelected(model)
                },
                onBrowseRequest = {
                    // Launch file browser intent
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                        }
                        // Start activity for result - this requires activity context
                        (context as? android.app.Activity)?.startActivityForResult(intent, 1001)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open file browser", e)
                        snackbarMessage = "Cannot open file browser. Please enter path manually."
                    }
                }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
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
                                        snackbarMessage = "${model.displayName} downloaded!"
                                        // CRITICAL FIX: Auto-load model after download completes
                                        // This ensures the model is ready for use immediately
                                        val modelsDir = java.io.File(context.getExternalFilesDir(null), "models")
                                        val modelFile = java.io.File(modelsDir, model.fileName)
                                        if (modelFile.exists()) {
                                            Log.i(TAG, "Auto-loading model after download: ${model.displayName}")
                                            // Notify parent to load the model
                                            onModelSelected(model)
                                        }
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
                        onCancelDownload = {
                            // TODO: Implement cancel
                            DownloadManager.clear(it.id)
                        },
                        onDelete = { model ->
                            // Delete the downloaded file
                            val modelsDir = File(context.getExternalFilesDir(null), "models")
                            val modelFile = File(modelsDir, model.fileName)
                            if (modelFile.exists()) {
                                modelFile.delete()
                                downloadedModels = downloadedModels - model.id
                                snackbarMessage = "${model.displayName} deleted"
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Incompatible model warning dialog
    if (showIncompatibleWarning && pendingDownloadModel != null) {
        AlertDialog(
            onDismissRequest = { 
                showIncompatibleWarning = false
                pendingDownloadModel = null
            },
            title = { Text("⚠️ Model May Not Work") },
            text = {
                Column {
                    Text("This model requires ${pendingDownloadModel!!.minRamGB}GB RAM, but your device only has ${String.format("%.1f", totalRamGB)}GB total.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The app may crash or run very slowly.", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Do you want to try anyway?")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val model = pendingDownloadModel!!
                        showIncompatibleWarning = false
                        pendingDownloadModel = null
                        
                        // Proceed with download
                        DownloadManager.updateProgress(model.id, 0f, 0, 0)
                        startDownload(
                            context = context,
                            model = model,
                            onProgress = { progress, bytesDownloaded, totalBytes ->
                                DownloadManager.updateProgress(model.id, progress, bytesDownloaded, totalBytes)
                            },
                            onComplete = { success, error ->
                                if (success) {
                                    DownloadManager.markComplete(model.id)
                                    downloadedModels = downloadedModels + (model.id to true)
                                    selectedModelId = model.id
                                    snackbarMessage = "${model.displayName} downloaded!"
                                } else {
                                    DownloadManager.markError(model.id, error ?: "Unknown error")
                                    snackbarMessage = "Download failed: $error"
                                }
                            }
                        )
                    }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Device Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${String.format("%.2f", totalRamGB)}GB Total RAM",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${String.format("%.2f", deviceSpecs.availableRamMB / 1024f)}GB Free RAM",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Models are filtered by your device's TOTAL RAM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LegendSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        LegendItem(color = MaterialTheme.colorScheme.primary, text = "Selected")
        LegendItem(color = MaterialTheme.colorScheme.tertiary, text = "Compatible")
        LegendItem(color = MaterialTheme.colorScheme.error, text = "Not Compatible")
        LegendItem(color = MaterialTheme.colorScheme.primary, text = "★ Recommended")
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Custom Model Section - Allows users to add their own GGUF models
 */
@Composable
private fun CustomModelSection(
    context: Context,
    selectedModelId: String?,
    onModelSelected: (OfflineModelConfig) -> Unit,
    onBrowseRequest: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var customModelPath by remember { mutableStateOf("") }
    var customModelName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Load saved custom model
    val customModel = remember { CustomModelManager.getCustomModel(context) }
    val isCustomModelSelected = selectedModelId?.startsWith("custom_") == true
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCustomModelSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📝 Custom Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Load your own GGUF model file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                
                if (customModel != null && customModel.exists()) {
                    // Show select button if custom model exists
                    Button(
                        onClick = {
                            val model = customModel.toOfflineModelConfig()
                            onModelSelected(model)
                        },
                        colors = if (isCustomModelSelected)
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        else
                            ButtonDefaults.buttonColors()
                    ) {
                        Text(if (isCustomModelSelected) "Selected" else "Select")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Change")
                    }
                } else {
                    // Show add button
                    Button(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Model")
                    }
                }
            }
            
            // Show current custom model info
            if (customModel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                if (customModel.exists()) {
                    Text(
                        text = "✓ ${customModel.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${String.format("%.2f", customModel.sizeGB)}GB at ${customModel.path}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "✗ Model file not found: ${customModel.path}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    
    // Add Custom Model Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                errorMessage = null
            },
            title = { Text("Add Custom Model") },
            text = {
                Column {
                    Text(
                        text = "Browse or enter the path to your GGUF model file:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Browse button
                    OutlinedButton(
                        onClick = {
                            onBrowseRequest()
                            showAddDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Files...")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "OR enter path manually:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customModelPath,
                        onValueChange = { 
                            customModelPath = it
                            errorMessage = null
                        },
                        label = { Text("Model Path") },
                        placeholder = { Text("/sdcard/Download/my-model.gguf") },
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customModelName,
                        onValueChange = { customModelName = it },
                        label = { Text("Model Name (optional)") },
                        placeholder = { Text("My Custom Model") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Common locations:\n• /sdcard/Download/\n• /storage/emulated/0/Download/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Validate path
                        if (customModelPath.isBlank()) {
                            errorMessage = "Please enter a model path"
                            return@TextButton
                        }
                        
                        if (!CustomModelManager.isValidModelFile(customModelPath)) {
                            errorMessage = "Invalid GGUF file. Make sure:\n• File exists\n• Ends with .gguf\n• Is readable"
                            return@TextButton
                        }
                        
                        // Get file size
                        val sizeGB = CustomModelManager.getFileSizeGB(customModelPath)
                        if (sizeGB <= 0) {
                            errorMessage = "Could not read file size"
                            return@TextButton
                        }
                        
                        // Save custom model
                        val name = if (customModelName.isBlank()) {
                            File(customModelPath).nameWithoutExtension
                        } else {
                            customModelName
                        }
                        
                        CustomModelManager.saveCustomModel(context, customModelPath, name, sizeGB)
                        
                        // Auto-select the custom model
                        val modelConfig = CustomModelManager.getCustomModel(context)?.toOfflineModelConfig()
                        modelConfig?.let { onModelSelected(it) }
                        
                        showAddDialog = false
                        customModelPath = ""
                        customModelName = ""
                    }
                ) {
                    Text("Add & Select")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showAddDialog = false
                        errorMessage = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
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
    onCancelDownload: (OfflineModelConfig) -> Unit,
    onDelete: (OfflineModelConfig) -> Unit
) {
    val tierColor = when {
        totalRamGB >= tier.minRamGB -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Tier header
        Surface(
            color = tierColor.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tier.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = tierColor
                    )
                    Text(
                        text = tier.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                // Show compatibility indicator
                if (totalRamGB >= tier.minRamGB) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Compatible",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Not Compatible",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Models in this tier
        models.forEach { model ->
            val isSelected = model.id == selectedModelId
            val isDownloaded = downloadedModels[model.id] == true
            val isCompatible = model.isCompatibleWith(totalRamGB.toInt())
            val downloadState = downloadProgress[model.id]
            val isDownloading = downloadState?.isDownloading == true
            
            ModelCard(
                model = model,
                isSelected = isSelected,
                isDownloaded = isDownloaded,
                isCompatible = isCompatible,
                isDownloading = isDownloading,
                downloadState = downloadState,
                onSelect = { onModelSelected(model) },
                onDownload = { onDownload(model, isCompatible) },
                onCancelDownload = { onCancelDownload(model) },
                onDelete = { onDelete(model) }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private data class DownloadState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0
)

@Composable
private fun ModelCard(
    model: OfflineModelConfig,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isCompatible: Boolean,
    isDownloading: Boolean,
    downloadState: DownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val cardColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        !isCompatible -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        onClick = {
            if (isDownloaded && isCompatible) {
                onSelect()
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Model info
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (model.isRecommended && isCompatible) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Recommended",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
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
                            // Not downloaded - show download button
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = onDownload,
                                    enabled = true,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Text("+ Get", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun startDownload(
    context: Context,
    model: OfflineModelConfig,
    onProgress: (Float, Long, Long) -> Unit,
    onComplete: (Boolean, String?) -> Unit
) {
    // Launch download in background thread
    Thread {
        try {
            val url = model.downloadUrl
            val fileName = model.fileName
            
            // Create models directory
            val modelsDir = File(context.getExternalFilesDir(null), "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            
            val outputFile = File(modelsDir, fileName)
            
            // Download file
            val connection = java.net.URL(url).openConnection()
            connection.connect()
            
            val totalBytes = connection.contentLength.toLong()
            val input = connection.getInputStream()
            val output = java.io.FileOutputStream(outputFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            var lastProgressUpdate = 0L
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                
                // Update progress every 100ms
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100) {
                    val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                    onProgress(progress, totalRead, totalBytes)
                    lastProgressUpdate = now
                }
            }
            
            output.flush()
            output.close()
            input.close()
            
            onComplete(true, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onComplete(false, e.message)
        }
    }.start()
}

private fun deleteModel(context: Context, model: OfflineModelConfig): Boolean {
    return try {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        val modelFile = File(modelsDir, model.fileName)
        if (modelFile.exists()) {
            modelFile.delete()
        } else {
            false
        }
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
