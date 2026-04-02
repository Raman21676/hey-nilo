package com.projekt_x.studybuddy.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projekt_x.studybuddy.util.HuggingFaceClient
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

private const val TAG = "HuggingFaceSearch"

/**
 * Hugging Face Search Screen
 * Allows users to search and download any GGUF model from Hugging Face
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceSearchScreen(
    onBack: () -> Unit,
    onModelDownloaded: (file: File, modelName: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hfClient = remember { HuggingFaceClient() }
    
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<HuggingFaceClient.HuggingFaceModel>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Download state
    var downloadingModel by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadStatus by remember { mutableStateOf("") }
    
    // Load trending on start
    LaunchedEffect(Unit) {
        isSearching = true
        errorMessage = null
        
        hfClient.getTrendingModels(limit = 10).fold(
            onSuccess = { trendingModels ->
                models = trendingModels
                isSearching = false
            },
            onFailure = { error ->
                errorMessage = error.message
                isSearching = false
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hugging Face Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search bar with search button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search models (e.g., llama, qwen)...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (searchQuery.isNotBlank()) {
                                scope.launch {
                                    isSearching = true
                                    errorMessage = null
                                    
                                    hfClient.searchModels(query = searchQuery, limit = 20).fold(
                                        onSuccess = { results ->
                                            models = results
                                            isSearching = false
                                        },
                                        onFailure = { error ->
                                            errorMessage = error.message
                                            isSearching = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                )
                
                // Search button
                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            scope.launch {
                                isSearching = true
                                errorMessage = null
                                
                                hfClient.searchModels(query = searchQuery, limit = 20).fold(
                                    onSuccess = { results ->
                                        models = results
                                        isSearching = false
                                    },
                                    onFailure = { error ->
                                        errorMessage = error.message
                                        isSearching = false
                                    }
                                )
                            }
                        }
                    },
                    enabled = searchQuery.isNotBlank() && !isSearching,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Search")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Info text
            Text(
                text = "Download any GGUF model from Hugging Face. Models are saved to your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Loading or error
            when {
                isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Error: $errorMessage",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                models.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No models found. Try searching.")
                    }
                }
                else -> {
                    // Model list
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(models) { model ->
                            ModelCard(
                                model = model,
                                isDownloading = downloadingModel == model.id,
                                downloadProgress = if (downloadingModel == model.id) downloadProgress else null,
                                onDownload = { ggufFile ->
                                    scope.launch {
                                        downloadingModel = model.id
                                        downloadModel(
                                            client = hfClient,
                                            model = model,
                                            ggufFile = ggufFile,
                                            context = context,
                                            onProgress = { progress, status ->
                                                downloadProgress = progress
                                                downloadStatus = status
                                            },
                                            onComplete = { file ->
                                                downloadingModel = null
                                                onModelDownloaded(file, model.id)
                                            },
                                            onError = { error ->
                                                downloadingModel = null
                                                errorMessage = error
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Download progress dialog
        if (downloadingModel != null) {
            AlertDialog(
                onDismissRequest = { /* Don't dismiss while downloading */ },
                title = { Text("Downloading Model") },
                text = {
                    Column {
                        Text("Downloading: $downloadingModel")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$downloadProgress%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (downloadStatus.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                downloadStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = { }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: HuggingFaceClient.HuggingFaceModel,
    isDownloading: Boolean,
    downloadProgress: Int?,
    onDownload: (HuggingFaceClient.GGUFFile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Model header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = "by ${model.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Stats
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${formatDownloads(model.downloads)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${model.likes} likes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Tags
            if (model.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    model.tags.take(3).forEach { tag ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            
            // GGUF files
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Available Files:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                model.ggufFiles.forEach { file ->
                    FileRow(
                        file = file,
                        isDownloading = isDownloading && downloadProgress != null,
                        downloadProgress = downloadProgress,
                        onDownload = { onDownload(file) }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${model.ggufFiles.size} GGUF file(s) available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = true }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HuggingFaceClient.GGUFFile,
    isDownloading: Boolean,
    downloadProgress: Int?,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.path.substringAfterLast("/"),
                style = MaterialTheme.typography.bodyMedium
            )
            if (file.size > 0) {
                Text(
                    text = formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        when {
            isDownloading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
            else -> {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Download", fontSize = 12.sp)
                }
            }
        }
    }
}

private suspend fun downloadModel(
    client: HuggingFaceClient,
    model: HuggingFaceClient.HuggingFaceModel,
    ggufFile: HuggingFaceClient.GGUFFile,
    context: android.content.Context,
    onProgress: (Int, String) -> Unit,
    onComplete: (File) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val modelsDir = File(context.getExternalFilesDir(null), "models/hf_downloads")
        modelsDir.mkdirs()
        
        val fileName = ggufFile.path.substringAfterLast("/")
        val destinationFile = File(modelsDir, fileName)
        
        onProgress(0, "Starting download...")
        
        client.downloadModel(model.id, ggufFile.path, destinationFile).collect { progress ->
            when (progress) {
                is HuggingFaceClient.DownloadProgress.Started -> {
                    onProgress(0, "Connected...")
                }
                is HuggingFaceClient.DownloadProgress.Progress -> {
                    onProgress(progress.percent, "Downloading...")
                }
                is HuggingFaceClient.DownloadProgress.Completed -> {
                    onComplete(progress.file)
                }
                is HuggingFaceClient.DownloadProgress.Error -> {
                    onError(progress.message)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        onError(e.message ?: "Download failed")
    }
}

private fun formatDownloads(downloads: Int): String {
    return when {
        downloads >= 1_000_000 -> "${DecimalFormat("0.0").format(downloads / 1_000_000.0)}M"
        downloads >= 1_000 -> "${DecimalFormat("0.0").format(downloads / 1_000.0)}K"
        else -> downloads.toString()
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "${DecimalFormat("0.00").format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "${DecimalFormat("0.00").format(bytes / 1_000_000.0)} MB"
        else -> "${bytes / 1_000} KB"
    }
}
