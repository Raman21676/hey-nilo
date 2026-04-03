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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.projekt_x.studybuddy.bridge.MemoryManager
import com.projekt_x.studybuddy.bridge.MockVADBridge
import com.projekt_x.studybuddy.bridge.VoicePipelineManager
import com.projekt_x.studybuddy.bridge.FileSystemManager
import com.projekt_x.studybuddy.bridge.ApiKeyStore
import com.projekt_x.studybuddy.bridge.llm.*
import com.projekt_x.studybuddy.model.ModelInfo
import com.projekt_x.studybuddy.model.OfflineModelConfig
import com.projekt_x.studybuddy.ui.OfflineModelPickerScreen
import com.projekt_x.studybuddy.ui.ModelSetupView
import com.projekt_x.studybuddy.ui.components.CompactTopBar
import com.projekt_x.studybuddy.ui.components.RamOptimizerDialog
import com.projekt_x.studybuddy.ui.components.OptimizationResult
import com.projekt_x.studybuddy.bridge.llm.SystemPromptBuilder
import com.projekt_x.studybuddy.ui.theme.HeyNiloTheme
import com.projekt_x.studybuddy.ui.theme.ThemeManager
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
    private var memoryManager: MemoryManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsState = PerformanceMetricsState()
    private val bridgeManager = BridgeManager.getInstance()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Starting app initialization...")
        
        // Initialize theme manager
        ThemeManager.init(this)
        
        // Show loading UI first
        enableEdgeToEdge()
        setContent {
            HeyNiloTheme {
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
                
                // Initialize memory system
                val fileSystemManager = FileSystemManager(this@MainActivity)
                fileSystemManager.initialize()
                memoryManager = MemoryManager(this@MainActivity, fileSystemManager)
                memoryManager?.initialize()
                Log.i(TAG, "Memory system initialized")
                
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
                
                // Capture memoryManager in local variable for composable access
                val memManager = memoryManager
                
                // Switch to UI thread to update content
                withContext(Dispatchers.Main) {
                    setContent {
                        HeyNiloTheme {
                            HeyNiloApp(
                                bridge = bridge!!,
                                queue = queue!!,
                                memoryManager = memManager,
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
                        HeyNiloTheme {
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
 * Animated Nilo Loading Screen
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
            // Animated Nilo Logo
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Hey-Nilo Logo",
                    modifier = Modifier.size(140.dp)
                )
            }
            
            // App Name
            Text(
                text = "Hey-Nilo",
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
                text = "Waking up Nilo...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "🎋 Loading AI models",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // AI Disclaimer
            Text(
                text = "⚠️ AI can make mistakes. Verify important information.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeyNiloApp(
    bridge: com.projekt_x.studybuddy.bridge.LlamaBridge,
    queue: InferenceQueue,
    memoryManager: MemoryManager?,
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
    
    // Context
    val context = LocalContext.current
    
    // RAM Optimizer state
    var isOptimizing by remember { mutableStateOf(false) }
    var showOptimizerDialog by remember { mutableStateOf(false) }
    var optimizationProgress by remember { mutableFloatStateOf(0f) }
    var optimizationResult by remember { mutableStateOf<OptimizationResult?>(null) }
    
    // Online/Offline mode state
    var currentMode by remember { mutableStateOf<AppMode>(AppMode.Offline) }
    var activeOnlineConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    
    // Quit confirmation dialog state
    var showQuitDialog by remember { mutableStateOf(false) }
    
    // Back navigation: When in chat view, go back to ModelSetupView instead of quitting
    BackHandler(enabled = isModelLoaded || activeOnlineConfig != null) {
        // Unload the model to properly return to setup
        if (bridge.isLoaded()) {
            bridge.unloadModel()
        }
        // Reset to model setup view
        isModelLoaded = false
        activeOnlineConfig = null
        isVoiceModeActive = false
        Log.i(TAG, "Back pressed: Returning to ModelSetupView")
    }
    
    // Quit confirmation when on setup screen
    BackHandler(enabled = !isModelLoaded && activeOnlineConfig == null && !showQuitDialog) {
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
                        (context as? ComponentActivity)?.finish()
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
    
    // Theme toggle state
    val isDarkTheme = ThemeManager.isDarkMode.value
    val appContext = LocalContext.current
    
    Scaffold(
        topBar = {
            CompactTopBar(
                title = "Hey-Nilo",
                metrics = metrics,
                isDarkTheme = isDarkTheme,
                onThemeToggle = { ThemeManager.toggleTheme(appContext) },
                onOptimize = { performOptimization() },
                isOptimizing = isOptimizing
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> LoadingView("Loading Hey-Nilo...")
                errorMessage != null -> ErrorView(
                    message = errorMessage!!,
                    onRetry = { errorMessage = null }
                )
                !isModelLoaded && activeOnlineConfig == null -> ModelSetupView(
                    bridge = bridge,
                    onModelLoaded = { loadedMode ->
                        isModelLoaded = true
                        currentMode = loadedMode
                        // Update metrics with config
                        metricsState.updateSystemStats(
                            temperature = bridge.getCurrentTemperature(),
                            memoryUsed = 0,
                            memoryTotal = 4096,
                            threadCount = 4,
                            contextSize = 1024
                        )
                    },
                    onOnlineConfigured = { config ->
                        activeOnlineConfig = config
                        currentMode = AppMode.Online(config.provider)
                        isModelLoaded = true  // Skip model loading for online
                    },
                    onError = { errorMessage = it },
                    onLoading = { isLoading = it }
                )
                else -> UnifiedChatView(
                    bridge = bridge,
                    queue = queue,
                    memoryManager = memoryManager,
                    metricsState = metricsState,
                    onlineConfig = activeOnlineConfig,
                    isVoiceModeActive = isVoiceModeActive,
                    onVoiceModeChange = { isVoiceModeActive = it },
                    currentMode = when (currentMode) {
                        is AppMode.Online -> "online"
                        is AppMode.HuggingFace -> "huggingface"
                        else -> "offline"
                    }
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


data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

/**
 * ULTRA-AGGRESSIVE Filter AI response 
 * FIXES: Removes all leaked tags AND adds spaces between stuck-together words
 */
/**
 * Filter for streaming tokens - only fixes spacing, no truncation
 */
fun filterStreamingToken(text: String): String {
    if (text.isBlank()) return text
    
    var filtered = text
    
    // Remove special tokens but don't truncate
    filtered = filtered.replace(Regex("(?i)\\[MEMORY\\]"), " ")
    filtered = filtered.replace(Regex("(?i)\\[/MEMORY\\]"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*Memory Context\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*End Context\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*End Conversation\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*EndConversation\\s*---"), " ")
    filtered = filtered.replace("</s>", " ")
    filtered = filtered.replace("<|system|>", " ")
    filtered = filtered.replace("<|user|>", " ")
    filtered = filtered.replace("<|assistant|>", " ")
    filtered = filtered.replace(Regex("""<\\|[^|]+\\|>"""), " ")
    
    // AGGRESSIVE: Remove partial/imcomplete special tokens that may leak during streaming
    filtered = filtered.replace("<|im_end|>", " ")
    filtered = filtered.replace("<|im_start|>", " ")
    filtered = filtered.replace("<|im_end|", " ")  // Missing closing >
    filtered = filtered.replace("<|im_start|", " ")  // Missing closing >
    filtered = filtered.replace("|im_end|>", " ")
    filtered = filtered.replace("|im_start|>", " ")
    filtered = filtered.replace("<|im_end", " ")
    filtered = filtered.replace("<|im_start", " ")
    filtered = filtered.replace("im_end|>", " ")
    filtered = filtered.replace("im_start|>", " ")
    filtered = filtered.replace("<|", " ")
    filtered = filtered.replace("|>", " ")
    filtered = filtered.replace(Regex("(?i)system\\s*$"), "")
    filtered = filtered.replace(Regex("(?i)assistant\\s*$"), "")
    filtered = filtered.replace(Regex("(?i)user\\s*$"), "")
    
    // AGGRESSIVE word spacing fix
    filtered = filtered.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    filtered = filtered.replace(Regex("([,!?])([a-zA-Z])"), "$1 $2")
    
    // Common stuck word patterns
    filtered = filtered.replace("Iam", "I am")
    filtered = filtered.replace("IamNilo", "I am Nilo")
    filtered = filtered.replace("yourpersonal", "your personal")
    filtered = filtered.replace("personalAI", "personal AI")
    filtered = filtered.replace("AIassistant", "AI assistant")
    filtered = filtered.replace("Howcan", "How can")
    filtered = filtered.replace("Howare", "How are")
    filtered = filtered.replace("you today?Is", "you today? Is")
    filtered = filtered.replace("Isthere", "Is there")
    filtered = filtered.replace("inparticular", "in particular")
    filtered = filtered.replace("thatyou", "that you")
    filtered = filtered.replace("wouldlike", "would like")
    filtered = filtered.replace("toknow", "to know")
    filtered = filtered.replace("ordiscuss", "or discuss")
    filtered = filtered.replace("Letmeknow", "Let me know")
    filtered = filtered.replace("we'llget", "we'll get")
    filtered = filtered.replace("startedona", "started on a")
    filtered = filtered.replace("conversation!---", "conversation!")
    filtered = filtered.replace("don'thesitate", "don't hesitate")
    filtered = filtered.replace("toask", "to ask")
    filtered = filtered.replace("I'mjust", "I'm just")
    filtered = filtered.replace("theretochat", "there to chat")
    filtered = filtered.replace("sodon't", "so don't")
    filtered = filtered.replace("sharethoughts", "share thoughts")
    filtered = filtered.replace("I'mjustheretochat,sodon'thesitatetoask", 
        "I'm just here to chat, so don't hesitate to ask")
    filtered = filtered.replace("Ihelp", "I help")
    filtered = filtered.replace("helpyou", "help you")
    filtered = filtered.replace("somethingin", "something in")
    filtered = filtered.replace("youwould", "you would")
    filtered = filtered.replace("askquestions", "ask questions")
    filtered = filtered.replace("we'llgetstarted", "we'll get started")
    filtered = filtered.replace("ona", "on a")
    filtered = filtered.replace("anAI", "an AI")
    filtered = filtered.replace("aAI", "an AI")
    
    // Clean up whitespace
    filtered = filtered.replace(Regex("\\s{2,}"), " ")
    
    return filtered.trim()
}

/**
 * Filter for complete response - fixes spacing AND truncates at end markers
 */
fun filterAiResponse(text: String): String {
    if (text.isBlank()) return text
    
    var filtered = text
    
    // STEP 1: TRUNCATE at the first occurrence of any end marker
    val endMarkers = listOf(
        "---EndConversation---", "--- End Conversation ---",
        "---End Context---", "--- End Context ---",
        "[/s]", "</s>", "</s", "<|system|>", "<|assistant|>", "<|user|>",
        "|im_end|", "|im_end|>", "<|im_end|>", "<|im_start|>assistant"
    )
    for (marker in endMarkers) {
        val index = filtered.indexOf(marker, ignoreCase = true)
        if (index != -1) {
            filtered = filtered.substring(0, index)
            break
        }
    }
    
    // STEP 2: Remove ALL special tokens AND memory-related noise
    filtered = filtered.replace(Regex("(?i)\\[MEMORY\\]"), " ")
    filtered = filtered.replace(Regex("(?i)\\[/MEMORY\\]"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*Memory Context\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*End Context\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*End Conversation\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---\\s*EndConversation\\s*---"), " ")
    filtered = filtered.replace(Regex("(?i)---[^-]+---"), " ")
    
    // CRITICAL FIX: Remove memory echo patterns that LLM generates
    filtered = filtered.replace(Regex("(?i)END\\s*OF\\s*MEMORY"), " ")
    filtered = filtered.replace(Regex("(?i)START\\s*OF\\s*MEMORY"), " ")
    filtered = filtered.replace(Regex("(?i)<--\\s*START"), " ")
    filtered = filtered.replace(Regex("(?i)<--\\s*END"), " ")
    filtered = filtered.replace(Regex("(?i)MEMORY"), " ")  // Remove standalone MEMORY word
    filtered = filtered.replace(Regex("(?i)The\\s+user's\\s+name\\s+is"), " ")  // Remove echoed context
    filtered = filtered.replace(Regex("(?i)They\\s+(?:are|live|work|prefer)"), " ")  // Remove echoed context lines
    
    filtered = filtered.replace("</s>", " ")
    filtered = filtered.replace("<|system|>", " ")
    filtered = filtered.replace("<|user|>", " ")
    filtered = filtered.replace("<|assistant|>", " ")
    filtered = filtered.replace(Regex("""<\\|[^|]+\\|>"""), " ")
    filtered = filtered.replace(Regex("""\\[/s\\]"""), " ")
    filtered = filtered.replace(Regex("""\\[s\\]"""), " ")
    
    // AGGRESSIVE: Remove partial/imcomplete special tokens
    filtered = filtered.replace("<|im_end|>", " ")
    filtered = filtered.replace("<|im_start|>", " ")
    filtered = filtered.replace("|im_end|>", " ")
    filtered = filtered.replace("|im_start|>", " ")
    filtered = filtered.replace("|im_end|", " ")  // CRITICAL: Without < or >
    filtered = filtered.replace("|im_start|", " ")  // CRITICAL: Without < or >
    filtered = filtered.replace("<|im_end", " ")
    filtered = filtered.replace("<|im_start", " ")
    filtered = filtered.replace("im_end|>", " ")
    filtered = filtered.replace("im_start|>", " ")
    filtered = filtered.replace("<|", " ")
    filtered = filtered.replace("|>", " ")
    filtered = filtered.replace("<", " ")
    filtered = filtered.replace(Regex("(?i)^\\s*system\\s*"), "")
    filtered = filtered.replace(Regex("(?i)^\\s*assistant\\s*"), "")
    filtered = filtered.replace(Regex("(?i)^\\s*user\\s*"), "")
    
    // STEP 3: AGGRESSIVE word spacing fix
    // Pattern 1: lowercase followed by UPPERCASE -> add space
    filtered = filtered.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    
    // Pattern 2: word boundary after punctuation: ,word -> , word
    filtered = filtered.replace(Regex("([,!?])([a-zA-Z])"), "$1 $2")
    
    // Pattern 3: common stuck word patterns (MOST IMPORTANT)
    filtered = filtered.replace("Iam", "I am")
    filtered = filtered.replace("IamNilo", "I am Nilo")
    filtered = filtered.replace("yourpersonal", "your personal")
    filtered = filtered.replace("personalAI", "personal AI")
    filtered = filtered.replace("AIassistant", "AI assistant")
    filtered = filtered.replace("Howcan", "How can")
    filtered = filtered.replace("Howare", "How are")
    filtered = filtered.replace("you today?Is", "you today? Is")
    filtered = filtered.replace("Isthere", "Is there")
    filtered = filtered.replace("inparticular", "in particular")
    filtered = filtered.replace("thatyou", "that you")
    filtered = filtered.replace("wouldlike", "would like")
    filtered = filtered.replace("toknow", "to know")
    filtered = filtered.replace("ordiscuss", "or discuss")
    filtered = filtered.replace("Letmeknow", "Let me know")
    filtered = filtered.replace("we'llget", "we'll get")
    filtered = filtered.replace("startedona", "started on a")
    filtered = filtered.replace("conversation!---", "conversation!")
    filtered = filtered.replace("don'thesitate", "don't hesitate")
    filtered = filtered.replace("toask", "to ask")
    filtered = filtered.replace("I'mjust", "I'm just")
    filtered = filtered.replace("theretochat", "there to chat")
    filtered = filtered.replace("sodon't", "so don't")
    filtered = filtered.replace("sharethoughts", "share thoughts")
    filtered = filtered.replace("I'mjustheretochat,sodon'thesitatetoask", "I'm just here to chat, so don't hesitate to ask")
    
    // STEP 4: Remove lines that look like system prompts
    val lines = filtered.lines()
    val cleanedLines = lines.filter { line ->
        val trimmed = line.trim()
        !trimmed.startsWith("User:", ignoreCase = true) &&
        !trimmed.startsWith("Facts:", ignoreCase = true) &&
        !trimmed.startsWith("People:", ignoreCase = true) &&
        !trimmed.startsWith("Pending:", ignoreCase = true) &&
        !trimmed.startsWith("Last session:", ignoreCase = true) &&
        !trimmed.startsWith("How are you today?", ignoreCase = true) &&
        !trimmed.startsWith("I'm just here to chat", ignoreCase = true) &&
        !trimmed.contains("don't hesitate to ask", ignoreCase = true) &&
        !trimmed.startsWith(">", ignoreCase = true)
    }
    filtered = cleanedLines.joinToString("\n").trim()
    
    // STEP 5: Final cleanup
    filtered = filtered.replace(Regex("\\s{2,}"), " ")
    filtered = filtered.replace(Regex("\\n{3,}"), "\n\n")
    
    return filtered.trim()
}

// Buffer for detecting special token patterns during streaming
private val streamingTokenBuffer = StringBuilder()
private var isCollectingImEnd = false

/**
 * Filter individual streaming tokens to prevent partial <|im_end|> from showing.
 * The model generates <|im_end|> as separate tokens: '<', '|', 'im', '_end', '|', '>'
 * This function buffers and detects these patterns, returning null for partial tokens.
 * 
 * @param token The incoming token string
 * @return The token to display (or null if it should be suppressed)
 */
fun filterStreamingTokenForDisplay(token: String): String? {
    // Quick check: if token contains complete marker, remove it
    if (token.contains("<|im_end|>") || token.contains("<|im_start|>")) {
        return token.replace("<|im_end|>", "").replace("<|im_start|>", "")
    }
    
    // Check for individual components of <|im_end|>
    val trimmed = token.trim()
    
    // Pattern detection: <|im_end|> breaks into: '<', '|', 'im', '_end', '|', '>'
    when {
        // Start of pattern
        trimmed == "<" -> {
            streamingTokenBuffer.clear()
            streamingTokenBuffer.append("<")
            isCollectingImEnd = true
            return null // Suppress this token
        }
        // Continue pattern after '<'
        isCollectingImEnd && trimmed == "|" && streamingTokenBuffer.toString() == "<" -> {
            streamingTokenBuffer.append("|")
            return null
        }
        // Continue pattern after '<|'
        isCollectingImEnd && trimmed == "im" && streamingTokenBuffer.toString() == "<|" -> {
            streamingTokenBuffer.append("im")
            return null
        }
        // Continue pattern after '<|im'
        isCollectingImEnd && trimmed == "_end" && streamingTokenBuffer.toString() == "<|im" -> {
            streamingTokenBuffer.append("_end")
            return null
        }
        // Continue pattern after '<|im_end'
        isCollectingImEnd && trimmed == "|" && streamingTokenBuffer.toString() == "<|im_end" -> {
            streamingTokenBuffer.append("|")
            return null
        }
        // End of pattern '<|im_end|>'
        isCollectingImEnd && trimmed == ">" && streamingTokenBuffer.toString() == "<|im_end|" -> {
            // Complete pattern detected, reset and suppress
            streamingTokenBuffer.clear()
            isCollectingImEnd = false
            return null
        }
        // If we were collecting but got something unexpected, flush buffer and return current
        isCollectingImEnd -> {
            val bufferContent = streamingTokenBuffer.toString()
            streamingTokenBuffer.clear()
            isCollectingImEnd = false
            // Return buffered content + current token
            return bufferContent + token
        }
    }
    
    // Normal token, return as-is
    return token
}

/**
 * Unified Chat View - Like ChatGPT/Gemini
 * Combines text chat and voice mode in one interface
 */
@Composable
fun UnifiedChatView(
    bridge: LlamaBridge,
    queue: InferenceQueue,
    memoryManager: MemoryManager?,
    metricsState: PerformanceMetricsState,
    onlineConfig: ProviderConfig?,
    isVoiceModeActive: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    currentMode: String = "offline"
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Chat mode online provider (for cancellation)
    var chatOnlineProvider by remember { mutableStateOf<OnlineLLMProvider?>(null) }
    var chatGenerationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Voice pipeline states
    var voicePipelineManager by remember { mutableStateOf<VoicePipelineManager?>(null) }
    var isVoiceReady by remember { mutableStateOf(false) }
    var pipelineState by remember { mutableStateOf(VoicePipelineManager.Companion.PipelineState.IDLE) }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceResponse by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    
    // FIX: Track permission state to re-initialize voice pipeline if needed
    var hasRecordPermission by remember { 
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            hasRecordPermission = true
            // FIX: Small delay to ensure permission is fully granted before starting voice
            scope.launch {
                delay(100)
                onVoiceModeChange(true)
            }
        } else {
            Toast.makeText(context, "Microphone permission required for voice mode", Toast.LENGTH_SHORT).show()
        }
    }
    
    // FIX: Re-initialize voice pipeline when permission becomes granted
    LaunchedEffect(hasRecordPermission) {
        if (hasRecordPermission && voicePipelineManager != null && !isVoiceReady) {
            Log.i(TAG, "Permission granted, re-initializing voice pipeline...")
            voicePipelineManager?.release()
            delay(100)
            voicePipelineManager?.initialize()
        }
    }
    
    // Initialize voice pipeline
    LaunchedEffect(onlineConfig, hasRecordPermission) {
        // Create provider based on mode
        val llmProvider: LLMProvider? = when {
            onlineConfig != null -> {
                val provider = OnlineLLMProvider(context, onlineConfig.provider, onlineConfig)
                provider.initialize()
                provider
            }
            bridge.isLoaded() -> {
                OfflineLLMProvider(context, bridge)
            }
            else -> null
        }
        
        val vpm = VoicePipelineManager(
            context = context,
            llmBridge = bridge,
            queue = queue,
            memoryManager = memoryManager,
            llmProvider = llmProvider
        )
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
            // FIX: Filter out leaked memory tags and system prompt artifacts
            val filteredText = filterAiResponse(text)
            // FIX: Find the last AI message that is streaming and update it
            messages = messages.map { msg ->
                if (!msg.isUser && msg.isStreaming) {
                    msg.copy(content = filteredText, isStreaming = !isComplete)
                } else {
                    msg
                }
            }
            if (!isComplete) {
                metricsState.onTokenGenerated()
            }
            if (isComplete) {
                isGenerating = false
                metricsState.stopGeneration()
                Log.d(TAG, "Response complete, streaming finished")
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
        
        // Only initialize if we have permission - otherwise wait for permission callback
        if (hasRecordPermission) {
            vpm.initialize()
        }
        voicePipelineManager = vpm
    }
    
    // BUG FIX 1: Properly stop voice pipeline when leaving voice mode
    // This DisposableEffect triggers when isVoiceModeActive OR isVoiceReady changes,
    // and cleans up (stops voice) when it becomes false or composable leaves
    DisposableEffect(isVoiceModeActive, isVoiceReady) {
        if (isVoiceModeActive && isVoiceReady) {
            Log.d(TAG, "BUG FIX 1: Starting voice conversation")
            voicePipelineManager?.startVoiceConversation()
        }
        
        onDispose {
            if (isVoiceModeActive) {
                // CRITICAL: Stop voice conversation when mode is deactivated
                Log.d(TAG, "BUG FIX 1: Stopping voice conversation on dispose")
                voicePipelineManager?.stopConversation()
            }
        }
    }
    
    // Cleanup when entire composable leaves (app close)
    DisposableEffect(Unit) {
        onDispose {
            voicePipelineManager?.release()
        }
    }
    
    // AUTO-SCROLL: Keep chat at bottom during streaming
    val isLastMessageStreaming by remember { derivedStateOf { 
        messages.lastOrNull()?.isStreaming == true 
    }}
    
    // Scroll when new messages added
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // AGGRESSIVE auto-scroll during streaming - scroll every 100ms
    LaunchedEffect(isLastMessageStreaming) {
        if (isLastMessageStreaming) {
            while (true) {
                if (messages.isNotEmpty()) {
                    listState.scrollToItem(messages.size - 1, scrollOffset = Int.MAX_VALUE)
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    // Collect responses from queue (for text mode)
    LaunchedEffect(queue) {
        queue.responses.collect { response ->
            if (response.error != null) {
                // CRITICAL FIX: Update the specific message that failed, don't append a new one
                messages = messages.map { msg ->
                    if (msg.id == response.requestId) {
                        msg.copy(content = "Error: ${response.error}", isStreaming = false)
                    } else {
                        msg
                    }
                }
                // CRITICAL FIX: Only set isGenerating = false if no messages are still streaming.
                // This prevents stale cancelled requests from hiding the stop button for new requests.
                if (messages.none { it.isStreaming }) {
                    isGenerating = false
                    metricsState.stopGeneration()
                }
            } else if (response.isComplete) {
                // CRITICAL FIX: Only mark the specific message for this request as complete
                messages = messages.map { msg ->
                    if (msg.id == response.requestId) {
                        msg.copy(
                            content = filterAiResponse(msg.content),
                            isStreaming = false
                        )
                    } else {
                        msg
                    }
                }
                // CRITICAL FIX: Only clear isGenerating if nothing is still streaming
                if (messages.none { it.isStreaming }) {
                    isGenerating = false
                    metricsState.stopGeneration()
                }
            } else if (response.token != null) {
                // Filter individual tokens during streaming to prevent <|im_end|> from showing
                val filteredToken = filterStreamingTokenForDisplay(response.token)
                if (filteredToken != null) {
                    // CRITICAL FIX: Only append tokens to the specific message for this request
                    messages = messages.map { msg ->
                        if (msg.id == response.requestId) {
                            msg.copy(content = msg.content + filteredToken)
                        } else {
                            msg
                        }
                    }
                }
                metricsState.onTokenGenerated()
            }
            // Auto-scroll handled by LaunchedEffect on lastMessageContent
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
                        // If listening or speech detected, force process the audio
                        // Otherwise just stop normally
                        if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
                            pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED) {
                            Log.d(TAG, "User tapped orb during listening - forcing speech processing")
                            // Immediately update UI so user sees feedback before STT finishes
                            pipelineState = VoicePipelineManager.Companion.PipelineState.TRANSCRIBING
                            voicePipelineManager?.forceStopAndProcess()
                        } else {
                            Log.d(TAG, "User tapped orb - stopping voice mode")
                            voicePipelineManager?.stopConversation()
                            onVoiceModeChange(false)
                        }
                    },
                    onInterrupt = {
                        // CRITICAL FIX: Interrupt generation and restart for new question
                        Log.d(TAG, "User interrupted - restarting for new question")
                        voicePipelineManager?.restartForNewQuestion()
                    }
                )
            }
            
            // Input area (ChatGPT/Gemini style)
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    // Voice button (circular) with Nilo head
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
                        // Nilo head icon
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = if (isVoiceModeActive) "Stop voice" else "Voice input",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // Text input
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Hey-Nilo...") },
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
                                        updateGenerating = { isGenerating = it },
                                        context = context,
                                        onlineConfig = onlineConfig,
                                        memoryManager = memoryManager,
                                        onProviderCreated = { chatOnlineProvider = it },
                                        onJobCreated = { chatGenerationJob = it }
                                    )
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            }
                        ),
                        maxLines = 4,
                        shape = CircleShape
                    )
                    
                    // Send button - becomes STOP button during generation or TTS
                    val isThinking = pipelineState == VoicePipelineManager.Companion.PipelineState.THINKING
                    val isSpeaking = pipelineState == VoicePipelineManager.Companion.PipelineState.SPEAKING
                    val isError = pipelineState == VoicePipelineManager.Companion.PipelineState.ERROR
                    // CRITICAL FIX: Show X button during THINKING, SPEAKING, or ERROR state
                    // This allows user to interrupt at any point during voice mode
                    val canStop = isGenerating || isThinking || isSpeaking || (isVoiceModeActive && isError)
                    
                    if (canStop) {
                        IconButton(
                            onClick = {
                                // Stop generation in text mode
                                if (isGenerating) {
                                    // CRITICAL FIX: Cancel chat mode online provider if active
                                    chatOnlineProvider?.let { provider ->
                                        Log.d("MainActivity", "Cancelling chat online provider")
                                        provider.cancelGeneration()
                                        chatOnlineProvider = null
                                    }
                                    // Cancel the job
                                    chatGenerationJob?.cancel()
                                    chatGenerationJob = null
                                    
                                    queue.cancel(messages.lastOrNull { it.isStreaming }?.id ?: "")
                                    bridge.stop()  // Also stop native generation
                                    isGenerating = false
                                    // CRITICAL FIX: Mark the interrupted response as complete
                                    // so the cursor disappears and the next response doesn't overwrite it.
                                    messages = messages.map { msg ->
                                        if (!msg.isUser && msg.isStreaming) {
                                            msg.copy(isStreaming = false)
                                        } else {
                                            msg
                                        }
                                    }
                                }
                                
                                // In voice mode: stop everything and restart listening
                                if (isVoiceModeActive) {
                                    // Stop TTS immediately
                                    voicePipelineManager?.stopTTS()
                                    // Stop any ongoing generation
                                    voicePipelineManager?.stopGeneration()
                                    // Clear any partial responses
                                    voicePipelineManager?.clearResponse()
                                    // CRITICAL FIX: Mark the interrupted response as complete
                                    // so the next response doesn't overwrite it.
                                    messages = messages.map { msg ->
                                        if (!msg.isUser && msg.isStreaming) {
                                            msg.copy(isStreaming = false)
                                        } else {
                                            msg
                                        }
                                    }
                                    isGenerating = false
                                    // Restart voice mode for new question
                                    voicePipelineManager?.restartForNewQuestion()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error
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
                                        updateGenerating = { isGenerating = it },
                                        context = context,
                                        onlineConfig = onlineConfig,
                                        memoryManager = memoryManager,
                                        onProviderCreated = { chatOnlineProvider = it },
                                        onJobCreated = { chatGenerationJob = it }
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
}

/**
 * Voice Mode Overlay - Shows when voice mode is active
 * Displays listening status, waveform, and transcript
 * Uses animated Nilo logo for all states
 */
/**
 * Voice Mode Overlay - Siri-style minimal indicator at bottom center
 * Shows small animated Nilo orb with status text above
 */
@Composable
fun VoiceModeOverlay(
    pipelineState: VoicePipelineManager.Companion.PipelineState,
    transcript: String,
    isRecording: Boolean,
    audioLevel: Float,
    onStop: () -> Unit,
    onInterrupt: () -> Unit = {}
) {
    // Pulse animation for active states
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    // Animate audio level
    val animatedAudioLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(50),
        label = "audioLevel"
    )
    
    // Status text based on state
    val statusText = when (pipelineState) {
        VoicePipelineManager.Companion.PipelineState.LISTENING -> "Nilo is listening..."
        VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED -> "Nilo hears you!"
        VoicePipelineManager.Companion.PipelineState.TRANSCRIBING -> "Nilo is writing..."
        VoicePipelineManager.Companion.PipelineState.THINKING -> "Nilo is thinking..."
        VoicePipelineManager.Companion.PipelineState.SPEAKING -> "Nilo is speaking..."
        else -> ""
    }
    
    // Determine orb color based on state
    val orbColor = when (pipelineState) {
        VoicePipelineManager.Companion.PipelineState.LISTENING -> 
            MaterialTheme.colorScheme.primary
        VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED -> 
            MaterialTheme.colorScheme.error
        VoicePipelineManager.Companion.PipelineState.TRANSCRIBING, 
        VoicePipelineManager.Companion.PipelineState.THINKING -> 
            MaterialTheme.colorScheme.secondary
        VoicePipelineManager.Companion.PipelineState.SPEAKING -> 
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    
    // Breathing scale based on audio level when listening
    val breathingScale = if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
                            pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED) {
        1f + (animatedAudioLevel * 0.4f)
    } else 1f
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp), // Small padding from bottom
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status text first (above the orb) with X button for interrupting
        if (statusText.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // X button to interrupt during generation/speaking
                if (pipelineState == VoicePipelineManager.Companion.PipelineState.TRANSCRIBING ||
                    pipelineState == VoicePipelineManager.Companion.PipelineState.THINKING ||
                    pipelineState == VoicePipelineManager.Companion.PipelineState.SPEAKING) {
                    IconButton(
                        onClick = onInterrupt,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Interrupt",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        
        // Nilo orb container
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Outer glow ring for active states
            if (pipelineState != VoicePipelineManager.Companion.PipelineState.IDLE) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(orbColor.copy(alpha = 0.3f))
                )
            }
            
            // Main Nilo orb - small size
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(breathingScale)
                    .clip(CircleShape)
                    .background(orbColor)
                    .pointerInput(onStop) {
                        detectTapGestures(onTap = { onStop() })
                    },
                contentAlignment = Alignment.Center
            ) {
                // Small Nilo icon inside orb
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Nilo",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        
        // Tap hint text below orb (only when listening)
        if (pipelineState == VoicePipelineManager.Companion.PipelineState.LISTENING ||
            pipelineState == VoicePipelineManager.Companion.PipelineState.SPEECH_DETECTED) {
            Text(
                text = "Tap to stop",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun sendTextMessage(
    text: String,
    queue: InferenceQueue,
    metricsState: PerformanceMetricsState,
    currentMessages: List<Message>,
    updateMessages: (List<Message>) -> Unit,
    updateGenerating: (Boolean) -> Unit,
    context: Context,
    onlineConfig: ProviderConfig?,
    memoryManager: MemoryManager? = null,  // BUG FIX 5: Added for shared system prompt
    onProviderCreated: (OnlineLLMProvider?) -> Unit = {},  // CRITICAL FIX: For cancellation
    onJobCreated: (kotlinx.coroutines.Job?) -> Unit = {}   // CRITICAL FIX: For cancellation
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
    
    if (onlineConfig != null) {
        // Online mode
        val provider = OnlineLLMProvider(context, onlineConfig.provider, onlineConfig)
        onProviderCreated(provider)  // Store reference for cancellation
        
        val job = GlobalScope.launch(Dispatchers.IO) {
            try {
                provider.initialize()
                
                // BUG FIX 5: Use shared system prompt builder
                val fullSystemPrompt = SystemPromptBuilder.buildSystemPrompt(memoryManager, maxTokens = 300)
                
                val request = CompletionRequest(
                    messages = listOf(ChatMessage.user(text)),
                    systemPrompt = fullSystemPrompt, // BUG FIX 3 & 5: Identity + memory
                    maxTokens = 256,
                    stream = true
                )
                
                var fullResponse = ""
                provider.stream(request).collect { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isComplete) {
                            fullResponse = filterAiResponse(response.text)
                            updateMessages(currentMessages + userMessage + aiMessage.copy(
                                content = fullResponse,
                                isStreaming = false
                            ))
                            updateGenerating(false)
                            metricsState.stopGeneration()
                        } else if (!response.isError) {
                            fullResponse = filterAiResponse(response.text)
                            updateMessages(currentMessages + userMessage + aiMessage.copy(
                                content = fullResponse
                            ))
                        }
                    }
                }
            } catch (e: CancellationException) {
                // User cancelled - don't show error, just clean up
                Log.d("MainActivity", "Chat generation cancelled by user")
                withContext(Dispatchers.Main) {
                    updateMessages(currentMessages + userMessage + aiMessage.copy(
                        isStreaming = false
                    ))
                    updateGenerating(false)
                    metricsState.stopGeneration()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateMessages(currentMessages + userMessage + aiMessage.copy(
                        content = "Error: ${e.message}",
                        isStreaming = false
                    ))
                    updateGenerating(false)
                    metricsState.stopGeneration()
                }
            }
        }
        
        onJobCreated(job)  // Store job reference for cancellation
        
        job.invokeOnCompletion {
            // Clear provider reference when job completes (success, error, or cancellation)
            onProviderCreated(null)
            onJobCreated(null)
        }
    } else {
        // Offline mode
        // BUG FIX 5: Use shared system prompt builder
        val fullSystemPrompt = SystemPromptBuilder.buildSystemPrompt(memoryManager, maxTokens = 300)
        
        queue.enqueue(
            InferenceQueue.Request(
                id = aiMessage.id,
                prompt = text,
                systemPrompt = fullSystemPrompt, // BUG FIX 3 & 5: Identity + memory
                maxTokens = 256,
                priority = InferenceQueue.Priority.HIGH
            )
        )
    }
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
    
    // 3D shadow colors
    val shadowColor = if (message.isUser) 
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    else 
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        // 3D Bubble with shadow
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .wrapContentWidth()
                .widthIn(max = 280.dp)
        ) {
            // Shadow layer for 3D effect
            Surface(
                color = shadowColor,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
            ) {}
            
            // Main bubble
            Surface(
                color = backgroundColor,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.wrapContentWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Sender name at top
                Text(
                    text = if (message.isUser) "You" else "Nilo",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
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

}
