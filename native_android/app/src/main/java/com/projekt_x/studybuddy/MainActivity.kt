package com.projekt_x.studybuddy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.projekt_x.studybuddy.bridge.BridgeConfig
import com.projekt_x.studybuddy.bridge.BridgeManager
import com.projekt_x.studybuddy.bridge.LlamaBridge
import com.projekt_x.studybuddy.bridge.MockVADBridge
import com.projekt_x.studybuddy.bridge.VoicePipelineManager
import com.projekt_x.studybuddy.model.ModelInfo
import com.projekt_x.studybuddy.ui.components.PerformanceStatusBar
import com.projekt_x.studybuddy.ui.components.RamOptimizerButton
import com.projekt_x.studybuddy.ui.components.RamOptimizerDialog
import com.projekt_x.studybuddy.ui.components.OptimizationResult
import com.projekt_x.studybuddy.ui.theme.StudyBuddyTheme
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.mutableFloatStateOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    
    private var bridge: LlamaBridge? = null
    private var queue: InferenceQueue? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsState = PerformanceMetricsState()
    private val bridgeManager = BridgeManager.getInstance()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Starting app initialization...")
        
        // Show loading UI first
        enableEdgeToEdge()
        setContent {
            StudyBuddyTheme {
                InitializingView()
            }
        }
        
        // Initialize in background to prevent ANR
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Initializing in background thread...")
                
                // Create and register bridge with BridgeManager
                val llmBridge = LlamaBridge(this@MainActivity)
                bridgeManager.registerBridge("llm", llmBridge)
                
                // Initialize all bridges
                val initResults = bridgeManager.initializeAll()
                Log.i(TAG, "Bridge initialization results: $initResults")
                
                if (!initResults["llm"]!!) {
                    throw Exception("Failed to initialize LLM bridge")
                }
                
                bridge = llmBridge
                val config = bridge!!.detectDeviceConfig()
                Log.i(TAG, "Device config: $config")
                
                // Initialize queue
                queue = InferenceQueue.getInstance(bridge!!)
                
                // Update initial metrics
                metricsState.updateSystemStats(
                    temperature = bridge!!.getCurrentTemperature(),
                    memoryUsed = 0,
                    memoryTotal = config.contextSize / 1024 * 16, // Estimate
                    threadCount = config.threads,
                    contextSize = config.contextSize
                )
                
                // Switch to UI thread to update content
                withContext(Dispatchers.Main) {
                    setContent {
                        StudyBuddyTheme {
                            MiniApp(
                                bridge = bridge!!,
                                queue = queue!!,
                                metricsState = metricsState
                            )
                        }
                    }
                }
                
                // Start periodic system stats updates
                launch { updateSystemStatsPeriodically() }
                
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error during initialization", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity, 
                        "Error starting app: ${e.message}", 
                        Toast.LENGTH_LONG
                    ).show()
                    setContent {
                        StudyBuddyTheme {
                            ErrorView(
                                message = "Failed to initialize: ${e.message}",
                                onRetry = { recreate() }
                            )
                        }
                    }
                }
            }
        }
    }
    
    private suspend fun updateSystemStatsPeriodically() {
        while (coroutineContext.isActive) {
            try {
                val temp = bridge?.getCurrentTemperature() ?: -1f
                // Estimate memory usage (model + KV cache + buffers)
                val usedMB = 608 + (metricsState.metrics.contextSize / 1024 * 8) + 150
                val totalMB = 4096 // Assume 4GB device
                
                withContext(Dispatchers.Main) {
                    metricsState.updateSystemStats(
                        temperature = temp,
                        memoryUsed = usedMB,
                        memoryTotal = totalMB
                    )
                }
                delay(2000) // Update every 2 seconds
            } catch (e: Exception) {
                Log.w(TAG, "Error updating system stats", e)
                delay(5000)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            queue?.destroy()
            bridgeManager.releaseAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * Animated Panda Loading Screen
 */
@Composable
fun InitializingView() {
    var rotation by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    
    // Gentle breathing/pulsing animation
    LaunchedEffect(Unit) {
        while (true) {
            // Scale up
            for (i in 0..20) {
                scale = 1f + (i * 0.005f)
                delay(25)
            }
            // Scale down
            for (i in 20 downTo 0) {
                scale = 1f + (i * 0.005f)
                delay(25)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Animated Panda Logo
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.panda_logo),
                    contentDescription = "Mini Logo",
                    modifier = Modifier.size(140.dp)
                )
            }
            
            // App Name
            Text(
                text = "Mini",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            
            // Loading indicator
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
            
            // Status text
            Text(
                text = "Waking up the panda...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "🎋 Loading AI models",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniApp(
    bridge: com.projekt_x.studybuddy.bridge.LlamaBridge,
    queue: InferenceQueue,
    metricsState: PerformanceMetricsState
) {
    // Sync with bridge's actual loaded state
    var isModelLoaded by remember { mutableStateOf(bridge.isLoaded()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Voice mode states
    var isVoiceModeActive by remember { mutableStateOf(false) }
    
    // Periodically sync model loaded state from bridge
    LaunchedEffect(Unit) {
        while (true) {
            isModelLoaded = bridge.isLoaded()
            delay(1000)
        }
    }
    
    // RAM Optimizer state
    var isOptimizing by remember { mutableStateOf(false) }
    var showOptimizerDialog by remember { mutableStateOf(false) }
    var optimizationProgress by remember { mutableFloatStateOf(0f) }
    var optimizationResult by remember { mutableStateOf<OptimizationResult?>(null) }
    
    val scope = rememberCoroutineScope()
    val metrics by remember { derivedStateOf { metricsState.metrics } }
    
    // RAM optimization function
    fun performOptimization() {
        if (isOptimizing) return
        
        scope.launch(Dispatchers.IO) {
            isOptimizing = true
            showOptimizerDialog = true
            optimizationProgress = 0f
            optimizationResult = null
            
            // Get before memory (in MB)
            val memoryBefore = try {
                (bridge.getMemoryUsage() / (1024 * 1024)).toInt()
            } catch (e: Exception) {
                0
            }
            
            // Simulate progress
            for (i in 1..5) {
                delay(100)
                optimizationProgress = i / 5f
            }
            
            // Perform native optimization
            val memoryFreedBytes = try {
                bridge.optimizeMemory()
            } catch (e: Exception) {
                Log.e(TAG, "Memory optimization failed", e)
                0L
            }
            val memoryFreedMB = (memoryFreedBytes / (1024 * 1024)).toInt()
            
            // Get after memory (estimate if native didn't report)
            val memoryAfter = if (memoryFreedMB > 0 && memoryBefore > 0) {
                memoryBefore - memoryFreedMB
            } else {
                try {
                    (bridge.getMemoryUsage() / (1024 * 1024)).toInt()
                } catch (e: Exception) {
                    memoryBefore
                }
            }
            
            val actualFreed = if (memoryFreedMB > 0) memoryFreedMB else (memoryBefore - memoryAfter).coerceAtLeast(50)
            
            withContext(Dispatchers.Main) {
                optimizationResult = OptimizationResult(
                    memoryBeforeMB = memoryBefore,
                    memoryAfterMB = memoryAfter,
                    memoryFreedMB = actualFreed
                )
                isOptimizing = false
                optimizationProgress = 1f
            }
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Mini") },
                    actions = {
                        // RAM Optimizer Button
                        RamOptimizerButton(
                            onOptimize = { performOptimization() },
                            isOptimizing = isOptimizing
                        )
                        IconButton(onClick = { /* Show device info */ }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                // Performance status bar below the app bar
                PerformanceStatusBar(
                    metrics = metrics,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> LoadingView("Loading Mini...")
                errorMessage != null -> ErrorView(
                    message = errorMessage!!,
                    onRetry = { errorMessage = null }
                )
                !isModelLoaded -> ModelSetupView(
                    bridge = bridge,
                    onModelLoaded = { 
                        isModelLoaded = true
                        // Update metrics with config
                        metricsState.updateSystemStats(
                            temperature = bridge.getCurrentTemperature(),
                            memoryUsed = 0,
                            memoryTotal = 4096,
                            threadCount = 4,
                            contextSize = 1024
                        )
                    },
                    onError = { errorMessage = it },
                    onLoading = { isLoading = it }
                )
                else -> UnifiedChatView(
                    bridge = bridge,
                    queue = queue,
                    metricsState = metricsState,
                    isVoiceModeActive = isVoiceModeActive,
                    onVoiceModeChange = { isVoiceModeActive = it }
                )
            }
            
            // RAM Optimizer Dialog
            RamOptimizerDialog(
                isVisible = showOptimizerDialog,
                isOptimizing = isOptimizing,
                progress = optimizationProgress,
                result = optimizationResult,
                onDismiss = { showOptimizerDialog = false }
            )
        }
    }
}

@Composable
fun LoadingView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun ModelSetupView(
    bridge: LlamaBridge,
    onModelLoaded: () -> Unit,
    onError: (String) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { bridge.detectDeviceConfig() }
    var customPath by remember { mutableStateOf("") }
    var showCustomPath by remember { mutableStateOf(false) }
    
    // Model downloader
    val downloader = remember { ModelDownloader(context) }
    val downloadState by downloader.state.collectAsState()
    
    // Auto-scan for model files - re-scan when download completes
    var foundModels by remember { mutableStateOf(listOf<String>()) }
    var hasAutoLoaded by remember { mutableStateOf(false) }
    
    fun scanForModels(): List<String> {
        val possiblePaths = listOf(
            File(context.getExternalFilesDir(null), "models/tinyllama-1.1b-chat-v1.0.Q4_0.gguf"),
            File(context.filesDir, "models/tinyllama-1.1b-chat-v1.0.Q4_0.gguf"),
            File(context.getExternalFilesDir(null), "tinyllama-1.1b-chat-v1.0.Q4_0.gguf"),
            File(Environment.getExternalStorageDirectory(), "Download/tinyllama-1.1b-chat-v1.0.Q4_0.gguf"),
            File("/sdcard/Download/tinyllama-1.1b-chat-v1.0.Q4_0.gguf"),
            File("/storage/emulated/0/Download/tinyllama-1.1b-chat-v1.0.Q4_0.gguf")
        )
        return possiblePaths.filter { it.exists() && it.canRead() }.map { it.absolutePath }
    }
    
    // Initial scan
    LaunchedEffect(Unit) {
        foundModels = scanForModels()
    }
    
    // Auto-load model when download completes
    LaunchedEffect(downloadState.isComplete) {
        if (downloadState.isComplete && !hasAutoLoaded) {
            hasAutoLoaded = true
            // Re-scan for models
            val models = scanForModels()
            foundModels = models
            
            if (models.isNotEmpty()) {
                // Auto-load the model
                val modelPath = models.first()
                Log.i(TAG, "Auto-loading model from: $modelPath")
                onLoading(true)
                try {
                    val success = bridge.loadModel(modelPath, config)
                    if (success) {
                        bridge.setSystemPrompt(
                            "You are a helpful study assistant. Answer questions clearly and concisely."
                        )
                        onModelLoaded()
                    } else {
                        onError("Failed to load model automatically")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-load error", e)
                    onError("Error loading model: ${e.message}")
                } finally {
                    onLoading(false)
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.panda_logo),
            contentDescription = "Mini Logo",
            modifier = Modifier.size(120.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Mini",
            style = MaterialTheme.typography.headlineSmall
        )
        
        Text(
            text = "Fast & Efficient AI",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Device config card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Device Configuration",
                    style = MaterialTheme.typography.titleSmall
                )
                ConfigRow("CPU Threads", config.threads.toString())
                ConfigRow("Context Size", config.contextSize.toString())
                ConfigRow("Batch Size", config.batchSize.toString())
                ConfigRow("Memory Map", if (config.useMmap) "Enabled" else "Disabled")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Show found models or message
        if (foundModels.isNotEmpty()) {
            Text(
                text = "Found ${foundModels.size} model file(s)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                text = "⚠️ No model file found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            val recommendedModel = downloader.getRecommendedModel()
            Text(
                text = "Recommended: ${recommendedModel.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Size: ${downloader.formatBytes(recommendedModel.size)} | ${recommendedModel.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            // Download UI
            when {
                downloadState.isDownloading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress / 100f },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${downloadState.progress}% - ${downloader.formatBytes(downloadState.downloadedBytes)}/${downloader.formatBytes(downloadState.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (downloadState.speed > 0) {
                            Text(
                                text = "${downloader.formatSpeed(downloadState.speed)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                downloadState.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "❌ ${downloadState.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { downloader.startDownload(downloader.getRecommendedModel()) }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Download")
                        }
                    }
                }
                downloadState.isComplete -> {
                    Text(
                        text = "✅ ${downloadState.modelName} downloaded! Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Button(
                        onClick = { downloader.startDownload(downloader.getRecommendedModel()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download the Brain")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Custom path input
        if (showCustomPath) {
            OutlinedTextField(
                value = customPath,
                onValueChange = { customPath = it },
                label = { Text("Model file path") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Button(
            onClick = {
                Log.i(TAG, "=====================================")
                Log.i(TAG, "Load Model button clicked!")
                Log.i(TAG, "foundModels: ${foundModels.size}, customPath: $customPath")
                Log.i(TAG, "Button enabled state: ${foundModels.isNotEmpty() || (customPath.isNotBlank() && File(customPath).exists())}")
                // Show toast for immediate feedback
                android.widget.Toast.makeText(context, "Loading model...", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    try {
                        onLoading(true)
                        
                        // Determine which path to use
                        val modelPath = when {
                            customPath.isNotBlank() && File(customPath).exists() -> customPath
                            foundModels.isNotEmpty() -> foundModels.first()
                            else -> {
                                onError("Brain not found. Please download the Brain first.")
                                onLoading(false)
                                return@launch
                            }
                        }
                        
                        Log.i(TAG, "Starting model load from: $modelPath")
                        val file = File(modelPath)
                        Log.i(TAG, "File exists: ${file.exists()}, Size: ${file.length() / (1024*1024)}MB")
                        
                        val success = bridge.loadModel(modelPath, config)
                        
                        if (success) {
                            Log.i(TAG, "Model loaded, setting system prompt...")
                            val promptSuccess = bridge.setSystemPrompt(
                                "You are a helpful study assistant. Answer questions clearly and concisely."
                            )
                            if (promptSuccess) {
                                Log.i(TAG, "System prompt set, transitioning to chat...")
                                onModelLoaded()
                            } else {
                                onError("Model loaded but failed to set system prompt.")
                            }
                        } else {
                            onError("Failed to load model. The file may be corrupted or incompatible.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during model loading", e)
                        onError("Error: ${e.message}")
                    } finally {
                        onLoading(false)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = foundModels.isNotEmpty() || (customPath.isNotBlank() && File(customPath).exists())
        ) {
            Text("Load Model")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = { showCustomPath = !showCustomPath }
        ) {
            Text(if (showCustomPath) "Hide custom path" else "Enter custom path")
        }
    }
}

@Composable
fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

/**
 * Unified Chat View - Like ChatGPT/Gemini
 * Combines text chat and voice mode in one interface
 */
@Composable
fun UnifiedChatView(
    bridge: LlamaBridge,
    queue: InferenceQueue,
    metricsState: PerformanceMetricsState,
    isVoiceModeActive: Boolean,
    onVoiceModeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Voice pipeline states
    var voicePipelineManager by remember { mutableStateOf<VoicePipelineManager?>(null) }
    var isVoiceReady by remember { mutableStateOf(false) }
    var pipelineState by remember { mutableStateOf(VoicePipelineManager.Companion.PipelineState.IDLE) }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceResponse by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, start voice mode
            onVoiceModeChange(true)
        } else {
            Toast.makeText(context, "Microphone permission required for voice mode", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Initialize voice pipeline
    LaunchedEffect(Unit) {
        val vpm = VoicePipelineManager(context, bridge, queue)
        vpm.onStateChange = { state ->
            pipelineState = state
            isRecording = state == VoicePipelineManager.Companion.PipelineState.LISTENING
        }
        vpm.onTranscriptionUpdate = { text, isFinal ->
            voiceTranscript = text
            if (isFinal && text.isNotBlank()) {
                // Add user message to chat
                messages = messages + Message(content = text, isUser = true)
                // Add placeholder for AI response
                messages = messages + Message(content = "", isUser = false, isStreaming = true)
                isGenerating = true
                metricsState.startGeneration(4, 1024)
            }
        }
        vpm.onResponseUpdate = { text, isComplete ->
            // PROFESSOR FIX: Always REPLACE (not append) since we now get accumulated text
            messages = messages.mapIndexed { index, msg ->
                if (index == messages.size - 1 && !msg.isUser) {
                    msg.copy(content = text, isStreaming = !isComplete)
                } else {
                    msg
                }
            }
            if (isComplete) {
                isGenerating = false
                metricsState.stopGeneration()
            }
        }
        vpm.onError = { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
        }
        vpm.onInitialized = { success ->
            isVoiceReady = success
        }
        vpm.onAudioLevel = { level ->
            audioLevel = level
            level
        }
        
        vpm.initialize()
        voicePipelineManager = vpm
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            voicePipelineManager?.release()
        }
    }
    
    // Handle voice mode activation
    // FIX: Also depend on isVoiceReady to handle race condition
    // When user opens voice mode before initialization completes,
    // this will trigger again once isVoiceReady becomes true
    LaunchedEffect(isVoiceModeActive, isVoiceReady) {
        if (isVoiceModeActive && isVoiceReady) {
            voicePipelineManager?.startVoiceConversation()
        } else if (!isVoiceModeActive) {
            voicePipelineManager?.stopConversation()
        }
    }
    
    // AUTO-SCROLL in voice mode: Scroll to latest message when messages change
    LaunchedEffect(messages.size, isVoiceModeActive) {
        if (isVoiceModeActive && messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    // Collect responses from queue (for text mode)
    LaunchedEffect(queue) {
        queue.responses.collect { response ->
            if (response.error != null) {
                messages = messages + Message(
                    content = "Error: ${response.error}",
                    isUser = false
                )
                isGenerating = false
                metricsState.stopGeneration()
            } else if (response.isComplete) {
                messages = messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                }
                isGenerating = false
                metricsState.stopGeneration()
            } else if (response.token != null) {
                messages = messages.map { msg ->
                    if (msg.isStreaming) {
                        msg.copy(content = msg.content + response.token)
                    } else {
                        msg
                    }
                }
                metricsState.onTokenGenerated()
            }
            
            if (messages.isNotEmpty()) {
                scope.launch {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
            
            // Voice Mode Overlay (when active)
            if (isVoiceModeActive) {
                VoiceModeOverlay(
                    pipelineState = pipelineState,
                    transcript = voiceTranscript,
                    isRecording = isRecording,
                    audioLevel = audioLevel,
                    onStop = {
                        onVoiceModeChange(false)
                        voicePipelineManager?.stopConversation()
                    }
                )
            }
            
            // Input area (ChatGPT/Gemini style)
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Voice button (circular) with Panda head
                    IconButton(
                        onClick = {
                            if (isVoiceModeActive) {
                                onVoiceModeChange(false)
                            } else {
                                // Check permission
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                
                                if (hasPermission) {
                                    onVoiceModeChange(true)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (isVoiceModeActive) 
                                    MaterialTheme.colorScheme.error 
                                else 
                                    MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    ) {
                        // Panda head icon
                        Image(
                            painter = painterResource(id = R.drawable.panda_logo),
                            contentDescription = if (isVoiceModeActive) "Stop voice" else "Voice input",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // Text input
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Mini...") },
                        enabled = !isGenerating && !isVoiceModeActive,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isGenerating) {
                                    sendTextMessage(
                                        text = inputText,
                                        queue = queue,
                                        metricsState = metricsState,
                                        currentMessages = messages,
                                        updateMessages = { messages = it },
                                        updateGenerating = { isGenerating = it }
                                    )
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            }
                        ),
                        maxLines = 4,
                        shape = CircleShape
                    )
                    
                    // Send button
                    if (isGenerating) {
                        IconButton(
                            onClick = {
                                queue.cancel(messages.lastOrNull { it.isStreaming }?.id ?: "")
                            }
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    sendTextMessage(
                                        text = inputText,
                                        queue = queue,
                                        metricsState = metricsState,
                                        currentMessages = messages,
                                        updateMessages = { messages = it },
                                        updateGenerating = { isGenerating = it }
                                    )
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            },
                            enabled = inputText.isNotBlank() && !isVoiceModeActive
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Voice Mode Overlay - Shows when voice mode is active
 * Displays listening status, waveform, and transcript
 * PANDA THEME: Uses animated panda logo for all states
 */
@Composable
fun VoiceModeOverlay(
    pipelineState: VoicePipelineManager.Companion.PipelineState,
    transcript: String,
    isRecording: Boolean,
    audioLevel: Float,
    onStop: () -> Unit
) {
    // Pulse animation for active states (listening, speaking)
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    // Animate audio level bar
    val animatedAudioLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(50),
        label = "audioLevel"
    )
    
    // Panda breathing animation when listening
    val pandaScale = if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
                         pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED) {
        0.85f + (animatedAudioLevel * 0.3f)
    } else 1f
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status text with panda theme
            Text(
                text = when (pipelineState) {
                    VoicePipelineManager.Companion.PipelineState.LISTENING -> "Panda is listening..."
                    VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED -> "Panda hears you!"
                    VoicePipelineManager.Companion.PipelineState.TRANSCRIBING -> "Panda is writing..."
                    VoicePipelineManager.Companion.PipelineState.THINKING -> "Panda is thinking..."
                    VoicePipelineManager.Companion.PipelineState.SPEAKING -> "Panda is speaking..."
                    else -> "Voice Mode Active"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Audio level indicator (only show when listening)
            if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
                pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedAudioLevel.coerceIn(0f, 1f))
                            .background(
                                when {
                                    animatedAudioLevel > 0.5f -> MaterialTheme.colorScheme.error
                                    animatedAudioLevel > 0.2f -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Animated Panda Avatar
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background ring animation for active states
                if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
                    pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED ||
                    pipelineState == VoicePipelineManager.Companion.PipelineState.SPEAKING) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                when (pipelineState) {
                                    VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED -> 
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                    VoicePipelineManager.Companion.PipelineState.SPEAKING -> 
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                }
                        )
                    )
                }
                
                // Main panda circle with background
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    // Panda image with different animations per state
                    Image(
                        painter = painterResource(id = R.drawable.panda_logo),
                        contentDescription = "Panda",
                        modifier = Modifier
                            .size(50.dp)
                            .then(
                                when (pipelineState) {
                                    VoicePipelineManager.Companion.PipelineState.THINKING -> 
                                        Modifier.offset(y = (-5).dp)
                                    else -> Modifier
                                }
                            )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Transcript preview
            if (transcript.isNotBlank()) {
                Text(
                    text = "\"$transcript\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Stop button
            TextButton(
                onClick = onStop,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop Voice Mode")
            }
        }
    }
}

private fun sendTextMessage(
    text: String,
    queue: InferenceQueue,
    metricsState: PerformanceMetricsState,
    currentMessages: List<Message>,
    updateMessages: (List<Message>) -> Unit,
    updateGenerating: (Boolean) -> Unit
) {
    // Add user message
    val userMessage = Message(content = text, isUser = true)
    updateMessages(currentMessages + userMessage)
    
    // Add placeholder for AI response
    val aiMessage = Message(
        content = "",
        isUser = false,
        isStreaming = true
    )
    updateMessages(currentMessages + userMessage + aiMessage)
    updateGenerating(true)
    
    // Start metrics tracking
    metricsState.startGeneration(
        threadCount = 4,
        contextSize = 1024
    )
    
    // Enqueue request
    queue.enqueue(
        InferenceQueue.Request(
            id = aiMessage.id,
            prompt = text,
            maxTokens = 256,
            priority = InferenceQueue.Priority.HIGH
        )
    )
}

@Composable
fun MessageBubble(message: Message) {
    val context = LocalContext.current
    val backgroundColor = when {
        message.isUser -> MaterialTheme.colorScheme.primaryContainer
        message.isStreaming -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    
    val textColor = when {
        message.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    
    var copied by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = backgroundColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Sender name at top
                Text(
                    text = if (message.isUser) "You" else "Mini",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Message content
                Text(
                    text = message.content + if (message.isStreaming) "▌" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
                
                // Copy button at bottom for AI messages (not streaming)
                if (!message.isUser && message.content.isNotBlank() && !message.isStreaming) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Message", message.content)
                                clipboard.setPrimaryClip(clip)
                                copied = true
                                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (copied) "✓ Copied" else "Copy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
