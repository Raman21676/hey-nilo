package com.projekt_x.studybuddy.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projekt_x.studybuddy.R
import com.projekt_x.studybuddy.bridge.ApiKeyStore
import com.projekt_x.studybuddy.bridge.LlamaBridge
import com.projekt_x.studybuddy.bridge.llm.AppMode
import com.projekt_x.studybuddy.bridge.llm.ApiProvider
import com.projekt_x.studybuddy.bridge.llm.ProviderConfig
import com.projekt_x.studybuddy.model.OfflineModelConfig
import com.projekt_x.studybuddy.model.getAllModels
import com.projekt_x.studybuddy.model.getRecommendedModel
import com.projekt_x.studybuddy.model.CustomModelManager
import com.projekt_x.studybuddy.bridge.DeviceInfo
import com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder
import com.projekt_x.studybuddy.util.LastModelPreference
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

private const val TAG = "ModelSetupView"

/**
 * Clean Model Setup View
 * Shows two mode cards (Offline/Online) and Start Chatting button
 * Tap card to select, tap Configure button to open settings
 */
@Composable
fun ModelSetupView(
    bridge: LlamaBridge,
    onModelLoaded: (AppMode) -> Unit,
    onError: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onOnlineConfigured: (ProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State for mode selection
    var selectedMode by remember { mutableStateOf<AppMode?>(null) }
    var onlineConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    var selectedOfflineModel by remember { mutableStateOf<OfflineModelConfig?>(null) }
    
    // Navigation states
    var showOnlineSetup by remember { mutableStateOf(false) }
    var showOfflinePicker by remember { mutableStateOf(false) }
    var showHuggingFaceSearch by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var showQuitDialog by remember { mutableStateOf(false) }
    
    // API Key Store
    val apiKeyStore = remember { ApiKeyStore(context) }
    
    // Last Model Preference (for resume dialog)
    val lastModelPref = remember { LastModelPreference(context) }
    var showResumeDialog by remember { mutableStateOf(false) }
    var savedConfig by remember { mutableStateOf<LastModelPreference.SavedConfig?>(null) }
    var isAutoLoading by remember { mutableStateOf(false) }
    
    // FIX: Track HuggingFace downloaded models for dedicated bar
    var hfDownloadedModels by remember { mutableStateOf<List<File>>(emptyList()) }
    
    // FIX: Refresh HF models - now runs on IO thread to avoid blocking UI
    suspend fun refreshHfModels() = withContext(Dispatchers.IO) {
        val hfDir = File(context.getExternalFilesDir(null), "models/hf_downloads")
        val models = if (hfDir.exists() && hfDir.canRead()) {
            hfDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else emptyList()
        
        // Update state on main thread
        withContext(Dispatchers.Main) {
            hfDownloadedModels = models
        }
        models
    }
    
    // Check for saved configurations on startup - NON-BLOCKING
    LaunchedEffect(Unit) {
        Log.i(TAG, "ModelSetupView launched, checking saved configs")
        
        // Scan HF downloads first (runs on IO thread)
        val hfModels = refreshHfModels()
        
        // Check for last used model (for resume dialog)
        if (lastModelPref.hasSavedConfig()) {
            val config = lastModelPref.getSavedConfig(context)
            if (config != null) {
                savedConfig = config
                showResumeDialog = true
                Log.i(TAG, "Found saved config, showing resume dialog")
            }
        }
        
        // Check for saved API key
        val savedKey = apiKeyStore.getApiKey()
        val savedProvider = apiKeyStore.getSavedProvider()
        
        if (savedKey != null && savedProvider != null) {
            val config = ProviderConfig(
                provider = savedProvider,
                apiKey = savedKey,
                modelName = apiKeyStore.getSavedModelName().takeIf { it.isNotBlank() } ?: ProviderConfig.defaultModel(savedProvider),
                enabled = true
            )
            onlineConfig = config
            Log.i(TAG, "Restored online config for ${savedProvider.name}")
        }
        
        // Check for ALL existing offline models (scan all models + HF downloads) - on IO thread
        withContext(Dispatchers.IO) {
            val modelsDir = File(context.getExternalFilesDir(null), "models")
            val deviceRamGB = DeviceInfo.getTotalRamGB(context)
            Log.i(TAG, "Scanning for models in: ${modelsDir.absolutePath}")
            
            // Find the first downloaded model (any model)
            val allModels = getAllModels()
            val downloadedModel = allModels.find { model ->
                val modelFile = File(modelsDir, model.fileName)
                val exists = modelFile.exists()
                if (exists) {
                    Log.i(TAG, "Found downloaded model: ${model.displayName} at ${modelFile.absolutePath}")
                }
                exists
            }
            
            // Update state on main thread
            withContext(Dispatchers.Main) {
                downloadedModel?.let { model ->
                    selectedOfflineModel = model
                    selectedMode = AppMode.Offline
                    Log.i(TAG, "Selected model: ${model.displayName}, auto-selected Offline mode")
                } ?: run {
                    // Auto-select first HF downloaded model if no predefined model found
                    hfModels.firstOrNull()?.let { file ->
                        val config = OfflineModelConfig(
                            id = "hf_${file.nameWithoutExtension.replace(" ", "_")}",
                            displayName = file.nameWithoutExtension,
                            fileName = file.name,
                            sizeGB = file.length() / (1024f * 1024f * 1024f),
                            minRamGB = 3,
                            description = "Downloaded from Hugging Face",
                            downloadUrl = "",
                            isRecommended = false,
                            category = com.projekt_x.studybuddy.model.ModelCategory.GENERAL
                        )
                        selectedOfflineModel = config
                        selectedMode = AppMode.HuggingFace
                        Log.i(TAG, "Auto-selected HF model: ${config.displayName}")
                    } ?: run {
                        Log.i(TAG, "No offline models found. Available models: ${allModels.size}")
                    }
                }
            }
        }
    }
    
    // FIX: Poll for model download completion - runs on IO thread, less frequent
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000) // Check every 5 seconds (was 2 seconds)
            
            // Refresh HF models list (on IO thread)
            val hfModels = refreshHfModels()
            
            // If no model selected yet, check if one appeared
            if (selectedOfflineModel == null) {
                withContext(Dispatchers.IO) {
                    val modelsDir = File(context.getExternalFilesDir(null), "models")
                    val allModels = getAllModels()
                    val downloadedModel = allModels.find { model ->
                        File(modelsDir, model.fileName).exists()
                    }
                    
                    withContext(Dispatchers.Main) {
                        downloadedModel?.let { model ->
                            Log.i(TAG, "Detected newly downloaded model: ${model.displayName}")
                            selectedOfflineModel = model
                            selectedMode = AppMode.Offline
                            
                            // Auto-load the model immediately after detection
                            if (!bridge.isLoaded()) {
                                Log.i(TAG, "Auto-loading model after detection: ${model.displayName}")
                                onLoading(true)
                                try {
                                    val modelFile = File(modelsDir, model.fileName)
                                    val config = bridge.detectDeviceConfig()
                                    val success = bridge.loadModel(modelFile.absolutePath, config)
                                    if (success) {
                                        bridge.setSystemPrompt(SystemPromptBuilder.buildSystemPrompt())
                                        Log.i(TAG, "Model auto-loaded successfully, navigating to chat")
                                        onModelLoaded(AppMode.Offline)
                                    } else {
                                        onError("Failed to auto-load model")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error auto-loading model", e)
                                    onError("Error loading model: ${e.message}")
                                } finally {
                                    onLoading(false)
                                }
                            }
                        } ?: run {
                            // Auto-select first HF downloaded model
                            hfModels.firstOrNull()?.let { file ->
                                val config = OfflineModelConfig(
                                    id = "hf_${file.nameWithoutExtension.replace(" ", "_")}",
                                    displayName = file.nameWithoutExtension,
                                    fileName = file.name,
                                    sizeGB = file.length() / (1024f * 1024f * 1024f),
                                    minRamGB = 3,
                                    description = "Downloaded from Hugging Face",
                                    downloadUrl = "",
                                    isRecommended = false,
                                    category = com.projekt_x.studybuddy.model.ModelCategory.GENERAL
                                )
                                selectedOfflineModel = config
                                selectedMode = AppMode.HuggingFace
                                Log.i(TAG, "Auto-selected HF model after detection: ${config.displayName}")
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Show snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Top section: Logo and title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 0.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "AGENT SMITH Logo",
                    modifier = Modifier.size(80.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "AGENT SMITH",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Your personal AI companion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // AI Warning Banner
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI can make mistakes. Always verify important information.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // Middle section: Mode selection cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Offline Mode Card
                val offlineStatusText = when {
                    selectedMode is AppMode.Offline && selectedOfflineModel != null -> 
                        "Model: ${selectedOfflineModel?.displayName} ✓"
                    selectedMode is AppMode.Offline -> "No model selected"
                    else -> "Tap to select"
                }
                ModeCard(
                    icon = Icons.Default.Settings,
                    title = "Offline",
                    subtitle = "Private · No internet",
                    statusText = offlineStatusText,
                    isSelected = selectedMode is AppMode.Offline,
                    onSelect = { 
                        selectedMode = AppMode.Offline
                        Log.i(TAG, "Offline mode selected")
                    },
                    onConfigure = {
                        showOfflinePicker = true
                        Log.i(TAG, "Offline picker opened")
                    }
                )
                
                // Hugging Face Mode Card (ALWAYS shown)
                val selectedHfModel = if (selectedMode is AppMode.HuggingFace) selectedOfflineModel else null
                ModeCard(
                    icon = Icons.Default.Search,
                    title = "Hugging Face",
                    subtitle = "Downloaded models",
                    statusText = when {
                        selectedHfModel != null -> "Model: ${selectedHfModel.displayName} ✓"
                        hfDownloadedModels.isNotEmpty() -> "${hfDownloadedModels.size} model(s) available"
                        else -> "Tap Configure to download"
                    },
                    isSelected = selectedMode is AppMode.HuggingFace,
                    onSelect = { 
                        // Auto-select first HF model if none selected
                        if (selectedHfModel == null && hfDownloadedModels.isNotEmpty()) {
                            val firstHf = hfDownloadedModels.first()
                            selectedOfflineModel = OfflineModelConfig(
                                id = "hf_${firstHf.nameWithoutExtension.replace(" ", "_")}",
                                displayName = firstHf.nameWithoutExtension,
                                fileName = firstHf.name,
                                sizeGB = firstHf.length() / (1024f * 1024f * 1024f),
                                minRamGB = 3,
                                description = "Downloaded from Hugging Face",
                                downloadUrl = "",
                                isRecommended = false,
                                category = com.projekt_x.studybuddy.model.ModelCategory.GENERAL
                            )
                        }
                        selectedMode = AppMode.HuggingFace
                        Log.i(TAG, "HuggingFace mode selected")
                    },
                    onConfigure = {
                        showHuggingFaceSearch = true
                        Log.i(TAG, "HuggingFace search opened")
                    }
                )
                
                // Online Mode Card
                ModeCard(
                    icon = Icons.Default.Send,
                    title = "Online",
                    subtitle = "Smarter · Needs internet",
                    statusText = onlineConfig?.let { "Model: ${it.provider.name} ✓" } 
                        ?: "API key required",
                    isSelected = selectedMode is AppMode.Online,
                    onSelect = { 
                        selectedMode = AppMode.Online(onlineConfig?.provider ?: ApiProvider.DEEPSEEK)
                        Log.i(TAG, "Online mode selected")
                    },
                    onConfigure = {
                        showOnlineSetup = true
                        Log.i(TAG, "Online setup opened")
                    }
                )
            }
            
            // Bottom section: Start button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        when (selectedMode) {
                            is AppMode.Offline -> {
                                if (selectedOfflineModel != null) {
                                    // Check model type to determine path
                                    val customModel = CustomModelManager.getCustomModel(context)
                                    val isCustomModel = selectedOfflineModel!!.id.startsWith("custom_")
                                    
                                    val modelPath = when {
                                        isCustomModel && customModel != null -> customModel.path
                                        else -> File(context.getExternalFilesDir(null), "models/${selectedOfflineModel!!.fileName}").absolutePath
                                    }
                                    
                                    val modelFile = File(modelPath)
                                    if (modelFile.exists()) {
                                        onLoading(true)
                                        scope.launch {
                                            try {
                                                val config = bridge.detectDeviceConfig()
                                                val success = bridge.loadModel(modelPath, config)
                                                if (success) {
                                                    bridge.setSystemPrompt(com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder.buildSystemPrompt())
                                                    // Save config for resume
                                                    val isCustom = selectedOfflineModel!!.id.startsWith("custom_")
                                                    val customPath = if (isCustom) modelPath else null
                                                    lastModelPref.saveOfflineModel(selectedOfflineModel!!, isCustom, customPath)
                                                    Log.i(TAG, "Saved offline model config for resume: ${selectedOfflineModel!!.displayName}")
                                                    onModelLoaded(AppMode.Offline)
                                                } else {
                                                    onError("Failed to load model")
                                                }
                                            } catch (e: Exception) {
                                                onError("Error: ${e.message}")
                                            } finally {
                                                onLoading(false)
                                            }
                                        }
                                    } else {
                                        snackbarMessage = "Model not found at: $modelPath"
                                    }
                                } else {
                                    snackbarMessage = "Tap 'Configure' on Offline card to select a model first."
                                }
                            }
                            is AppMode.HuggingFace -> {
                                if (selectedOfflineModel != null && selectedOfflineModel!!.id.startsWith("hf_")) {
                                    val modelPath = File(context.getExternalFilesDir(null), "models/hf_downloads/${selectedOfflineModel!!.fileName}").absolutePath
                                    val modelFile = File(modelPath)
                                    if (modelFile.exists()) {
                                        onLoading(true)
                                        scope.launch {
                                            try {
                                                val config = bridge.detectDeviceConfig()
                                                val success = bridge.loadModel(modelPath, config)
                                                if (success) {
                                                    bridge.setSystemPrompt(com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder.buildSystemPrompt())
                                                    // Save HF config for resume
                                                    lastModelPref.saveOfflineModel(selectedOfflineModel!!, false, modelPath)
                                                    Log.i(TAG, "Saved HF model config for resume: ${selectedOfflineModel!!.displayName}")
                                                    onModelLoaded(AppMode.HuggingFace)
                                                } else {
                                                    onError("Failed to load model")
                                                }
                                            } catch (e: Exception) {
                                                onError("Error: ${e.message}")
                                            } finally {
                                                onLoading(false)
                                            }
                                        }
                                    } else {
                                        snackbarMessage = "Model not found at: $modelPath"
                                    }
                                } else {
                                    snackbarMessage = "Tap 'Configure' on Hugging Face card to download a model first."
                                }
                            }
                            is AppMode.Online -> {
                                if (onlineConfig?.apiKey?.isNotBlank() == true) {
                                    // Save the config for resume on next app launch
                                    lastModelPref.saveOnlineConfig(onlineConfig!!)
                                    Log.i(TAG, "Saved online config for resume: ${onlineConfig!!.provider.name}")
                                    onOnlineConfigured(onlineConfig!!)
                                } else {
                                    snackbarMessage = "Tap 'Configure' on Online card to set up API key."
                                }
                            }
                            null -> {
                                snackbarMessage = "Select a mode first"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = selectedMode != null
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Chatting", style = MaterialTheme.typography.titleMedium)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Tap card to select · Tap Configure to set up",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            }
        }
        
        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    
    // Handle system back button
    BackHandler(enabled = showOfflinePicker) {
        Log.i(TAG, "BackHandler: Closing OfflineModelPickerScreen")
        showOfflinePicker = false
    }
    
    BackHandler(enabled = showHuggingFaceSearch) {
        Log.i(TAG, "BackHandler: Closing HuggingFaceSearchScreen")
        showHuggingFaceSearch = false
    }
    
    // Handle back button on main setup screen - show quit confirmation
    BackHandler(enabled = !showOfflinePicker && !showHuggingFaceSearch && !showOnlineSetup) {
        Log.i(TAG, "BackHandler: Showing quit confirmation dialog")
        showQuitDialog = true
    }
    
    // Quit confirmation dialog
    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text("Quit AGENT SMITH?") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        Log.i(TAG, "User confirmed quit")
                        (context as? android.app.Activity)?.finish()
                    }
                ) {
                    Text("Quit", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Resume previous session dialog
    if (showResumeDialog && savedConfig != null) {
        val configName = lastModelPref.getSavedConfigDisplayName(context) ?: "Previous Model"
        AlertDialog(
            onDismissRequest = { 
                showResumeDialog = false 
                // Clear saved config if user dismisses
                lastModelPref.clear()
            },
            title = { Text("Resume Previous Session?") },
            text = { 
                Text("Do you want to continue with:\n\n$configName") 
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResumeDialog = false
                        isAutoLoading = true
                        onLoading(true)
                        
                        scope.launch {
                            try {
                                when (val mode = savedConfig!!.mode) {
                                    is AppMode.Offline -> {
                                        val model = savedConfig!!.offlineModel!!
                                        val modelPath = if (savedConfig!!.isCustomModel && savedConfig!!.customModelPath != null) {
                                            savedConfig!!.customModelPath!!
                                        } else {
                                            File(context.getExternalFilesDir(null), "models/${model.fileName}").absolutePath
                                        }
                                        
                                        val modelFile = File(modelPath)
                                        if (modelFile.exists()) {
                                            val config = bridge.detectDeviceConfig()
                                            val success = bridge.loadModel(modelPath, config)
                                            if (success) {
                                                bridge.setSystemPrompt(SystemPromptBuilder.buildSystemPrompt())
                                                // Restore selection state
                                                selectedOfflineModel = model
                                                selectedMode = AppMode.Offline
                                                Log.i(TAG, "Auto-loaded offline model: ${model.displayName}")
                                                onModelLoaded(AppMode.Offline)
                                            } else {
                                                onError("Failed to load model")
                                                lastModelPref.clear()
                                            }
                                        } else {
                                            onError("Model file not found: $modelPath")
                                            lastModelPref.clear()
                                        }
                                    }
                                    is AppMode.HuggingFace -> {
                                        val model = savedConfig!!.offlineModel!!
                                        val modelPath = savedConfig!!.customModelPath 
                                            ?: File(context.getExternalFilesDir(null), "models/hf_downloads/${model.fileName}").absolutePath
                                        
                                        val modelFile = File(modelPath)
                                        if (modelFile.exists()) {
                                            val config = bridge.detectDeviceConfig()
                                            val success = bridge.loadModel(modelPath, config)
                                            if (success) {
                                                bridge.setSystemPrompt(SystemPromptBuilder.buildSystemPrompt())
                                                // Restore selection state
                                                selectedOfflineModel = model
                                                selectedMode = AppMode.HuggingFace
                                                Log.i(TAG, "Auto-loaded HF model: ${model.displayName}")
                                                onModelLoaded(AppMode.HuggingFace)
                                            } else {
                                                onError("Failed to load model")
                                                lastModelPref.clear()
                                            }
                                        } else {
                                            onError("Model file not found: $modelPath")
                                            lastModelPref.clear()
                                        }
                                    }
                                    is AppMode.Online -> {
                                        val config = savedConfig!!.onlineConfig!!
                                        // Restore selection state
                                        onlineConfig = config
                                        selectedMode = AppMode.Online(config.provider)
                                        Log.i(TAG, "Auto-loaded online config: ${config.provider.name}")
                                        onOnlineConfigured(config)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error auto-loading saved config", e)
                                onError("Error loading: ${e.message}")
                                lastModelPref.clear()
                            } finally {
                                isAutoLoading = false
                                onLoading(false)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { 
                        showResumeDialog = false 
                        // Clear saved config if user cancels
                        lastModelPref.clear()
                        Log.i(TAG, "User cancelled resume, cleared saved config")
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Full screen picker overlay - shown instead of main UI
    if (showOfflinePicker) {
        Log.i(TAG, "Showing OfflineModelPickerScreen")
        OfflineModelPickerScreen(
            context = context,
            currentModelId = selectedOfflineModel?.id,
            onModelSelected = { model ->
                selectedOfflineModel = model
                selectedMode = AppMode.Offline
                showOfflinePicker = false
            },
            onBack = { showOfflinePicker = false },
            onBrowseHuggingFace = {
                showOfflinePicker = false
                showHuggingFaceSearch = true
            }
        )
        return  // Don't render anything else when picker is showing
    }
    
    // Hugging Face Search Screen
    if (showHuggingFaceSearch) {
        Log.i(TAG, "Showing HuggingFaceSearchScreen")
        HuggingFaceSearchScreen(
            onBack = { showHuggingFaceSearch = false },
            onModelDownloaded = { file, modelId ->
                Log.i(TAG, "onModelDownloaded called: file=${file.absolutePath}, size=${file.length()}, modelId=$modelId")
                
                // FIX: Verify file exists before updating state
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Downloaded file missing or empty: ${file.absolutePath}")
                    snackbarMessage = "Download failed: file missing"
                    showHuggingFaceSearch = false
                    return@HuggingFaceSearchScreen
                }
                
                // Create a custom model config for the downloaded file
                val modelName = file.nameWithoutExtension
                val customModel = com.projekt_x.studybuddy.model.OfflineModelConfig(
                    id = "hf_${modelId.replace("/", "_")}",
                    displayName = modelName,
                    fileName = file.name,
                    sizeGB = file.length() / (1024f * 1024f * 1024f),
                    minRamGB = 4, // Default requirement
                    isRecommended = false,
                    description = "Downloaded from Hugging Face: $modelId",
                    downloadUrl = "",
                    category = com.projekt_x.studybuddy.model.ModelCategory.GENERAL
                )
                
                // FIX: Update state BEFORE closing the search screen to avoid recomposition race
                selectedOfflineModel = customModel
                selectedMode = com.projekt_x.studybuddy.bridge.llm.AppMode.HuggingFace
                Log.i(TAG, "State updated: selectedOfflineModel=${customModel.displayName}, selectedMode=HuggingFace")
                
                // Refresh HF models list AFTER state updates (launch in coroutine scope)
                scope.launch {
                    refreshHfModels()
                }
                
                // Save the config immediately so resume works
                lastModelPref.saveOfflineModel(customModel, false, file.absolutePath)
                
                // Now close the search screen
                showHuggingFaceSearch = false
                snackbarMessage = "Model downloaded: $modelName"
            }
        )
        return  // Don't render anything else when search is showing
    }
    
    // Dialog - shown on top of main UI
    if (showOnlineSetup) {
        Log.i(TAG, "Rendering OnlineSetupDialog")
        OnlineSetupDialog(
            apiKeyStore = apiKeyStore,
            onDismiss = { showOnlineSetup = false },
            onSave = { config: ProviderConfig ->
                onlineConfig = config
                selectedMode = AppMode.Online(config.provider)
                showOnlineSetup = false
            }
        )
    }
}

/**
 * Mode selection card with select and configure buttons
 * Fixed: Uses Box overlay approach to properly handle button clicks
 */
@Composable
private fun ModeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    statusText: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConfigure: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clickable area for card selection (icon + text)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { 
                        Log.i(TAG, "Card clicked: $title")
                        onSelect()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = if (isSelected) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Text content
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusText.contains("✓")) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            // Configure button - completely separate from clickable area
            if (isSelected) {
                Button(
                    onClick = {
                        Log.i(TAG, "Configure button clicked for $title")
                        onConfigure()
                    },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Configure", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                // Reserve space when not selected
                Spacer(modifier = Modifier.width(90.dp))
            }
        }
    }
}

/**
 * Dedicated Hugging Face downloaded models bar
 * Shows below the Offline card when HF models are present
 */
@Composable
private fun HuggingFaceModelsBar(
    models: List<File>,
    selectedModelId: String?,
    onModelSelected: (File) -> Unit,
    onModelDeselected: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🤗 Hugging Face",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Downloaded Models",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            models.forEach { file ->
                val modelId = "hf_${file.nameWithoutExtension.replace(" ", "_")}"
                val isSelected = selectedModelId == modelId
                val sizeGB = file.length() / (1024f * 1024f * 1024f)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = "${String.format("%.2f", sizeGB)}GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    
                    if (isSelected) {
                        OutlinedButton(
                            onClick = onModelDeselected,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Text("Selected", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = { onModelSelected(file) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            )
                        ) {
                            Text("Select", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
