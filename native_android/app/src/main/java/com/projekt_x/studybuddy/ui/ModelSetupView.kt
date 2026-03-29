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
import com.projekt_x.studybuddy.bridge.DeviceInfo
import com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder
import kotlinx.coroutines.launch
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
    onModelLoaded: () -> Unit,
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
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var showQuitDialog by remember { mutableStateOf(false) }
    
    // API Key Store
    val apiKeyStore = remember { ApiKeyStore(context) }
    
    // Check for saved configurations on startup
    LaunchedEffect(Unit) {
        Log.i(TAG, "ModelSetupView launched, checking saved configs")
        
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
        
        // Check for ALL existing offline models (scan all models)
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
        
        downloadedModel?.let { model ->
            selectedOfflineModel = model
            selectedMode = AppMode.Offline  // Auto-select offline mode
            Log.i(TAG, "Selected model: ${model.displayName}, auto-selected Offline mode")
        } ?: run {
            Log.i(TAG, "No offline models found. Available models: ${allModels.size}")
        }
    }
    
    // CRITICAL FIX: Poll for model download completion and auto-load
    // This handles the case where download finishes while user is away from picker
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000) // Check every 2 seconds
            
            // If no model selected yet, check if one appeared
            if (selectedOfflineModel == null) {
                val modelsDir = File(context.getExternalFilesDir(null), "models")
                val allModels = getAllModels()
                val downloadedModel = allModels.find { model ->
                    File(modelsDir, model.fileName).exists()
                }
                
                downloadedModel?.let { model ->
                    Log.i(TAG, "Detected newly downloaded model: ${model.displayName}")
                    selectedOfflineModel = model
                    selectedMode = AppMode.Offline
                    
                    // CRITICAL FIX: Auto-load the model immediately after detection
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
                                onModelLoaded()
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Top section: Logo and title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Hey-Nilo Logo",
                    modifier = Modifier.size(80.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Hey-Nilo",
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
                ModeCard(
                    icon = Icons.Default.Settings,
                    title = "Offline",
                    subtitle = "Private · No internet",
                    statusText = selectedOfflineModel?.let { "Model: ${it.displayName} ✓" } 
                        ?: "No model selected",
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
                                    val modelFile = File(
                                        context.getExternalFilesDir(null), 
                                        "models/${selectedOfflineModel!!.fileName}"
                                    )
                                    if (modelFile.exists()) {
                                        onLoading(true)
                                        scope.launch {
                                            try {
                                                val config = bridge.detectDeviceConfig()
                                                val success = bridge.loadModel(modelFile.absolutePath, config)
                                                if (success) {
                                                    bridge.setSystemPrompt(com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder.buildSystemPrompt())
                                                    onModelLoaded()
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
                                        snackbarMessage = "Model not found. Tap 'Configure' on Offline card to download."
                                    }
                                } else {
                                    snackbarMessage = "Tap 'Configure' on Offline card to select a model first."
                                }
                            }
                            is AppMode.Online -> {
                                if (onlineConfig?.apiKey?.isNotBlank() == true) {
                                    onOnlineConfigured(onlineConfig!!)
                                } else {
                                    snackbarMessage = "Tap 'Configure' on Online card to set up API key."
                                }
                            }
                            null -> {
                                snackbarMessage = "Select a mode (Offline or Online) first"
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
    
    // Handle back button on main setup screen - show quit confirmation
    BackHandler(enabled = !showOfflinePicker && !showOnlineSetup) {
        Log.i(TAG, "BackHandler: Showing quit confirmation dialog")
        showQuitDialog = true
    }
    
    // Quit confirmation dialog
    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text("Quit Hey-Nilo?") },
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
            onBack = { showOfflinePicker = false }
        )
        return  // Don't render anything else when picker is showing
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
    Box(modifier = Modifier.fillMaxWidth()) {
        // Background card - clickable for selection
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { 
                    Log.i(TAG, "Card clicked: $title")
                    onSelect()
                },
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
                Column(modifier = Modifier.weight(1f)) {
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
                
                // Reserve space for button when not selected
                if (!isSelected) {
                    Spacer(modifier = Modifier.width(90.dp))
                }
            }
        }
        
        // Configure button - positioned on top of card (higher z-index)
        if (isSelected) {
            Button(
                onClick = {
                    Log.i(TAG, "Configure button clicked for $title")
                    onConfigure()
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .height(40.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Configure", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
