package com.projekt_x.studybuddy.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projekt_x.studybuddy.bridge.ApiKeyStore
import com.projekt_x.studybuddy.bridge.llm.ApiProvider
import com.projekt_x.studybuddy.bridge.llm.ProviderConfig

/**
 * Online Setup Dialog - Configure API key for online provider
 * Now saves API key securely using ApiKeyStore
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSetupDialog(
    apiKeyStore: ApiKeyStore,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ApiProvider.DEEPSEEK) }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    
    // Load saved API key when dialog opens
    LaunchedEffect(Unit) {
        val savedKey = apiKeyStore.getApiKey()
        val savedProvider = apiKeyStore.getSavedProvider()
        val savedModelName = apiKeyStore.getSavedModelName()
        
        Log.d("OnlineSetupDialog", "Loading saved config - Key exists: ${savedKey != null}, Provider: ${savedProvider?.name}, Model: $savedModelName")
        
        if (savedKey != null) {
            apiKey = savedKey
            Log.d("OnlineSetupDialog", "API key loaded successfully (length: ${savedKey.length})")
        } else {
            Log.d("OnlineSetupDialog", "No saved API key found")
        }
        if (savedProvider != null) {
            selectedProvider = savedProvider
            Log.d("OnlineSetupDialog", "Provider set to: ${savedProvider.name}")
        }
        if (savedModelName.isNotBlank()) {
            modelName = savedModelName
            Log.d("OnlineSetupDialog", "Model name loaded: $savedModelName")
        }
    }
    
    // Update model name when provider changes (if not already set)
    LaunchedEffect(selectedProvider) {
        if (modelName.isBlank()) {
            modelName = ProviderConfig.defaultModel(selectedProvider)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Online AI") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Provider selection
                Text("Select Provider:", style = MaterialTheme.typography.labelMedium)
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ApiProvider.values().filter { it != ApiProvider.OFFLINE }.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider.name) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // API Key input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("Enter your API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Model selection - Show dropdown for OpenRouter, text field for others
                if (selectedProvider == ApiProvider.OPENROUTER) {
                    // OpenRouter model dropdown
                    ExposedDropdownMenuBox(
                        expanded = isModelDropdownExpanded,
                        onExpandedChange = { isModelDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            readOnly = false,
                            label = { Text("Model") },
                            placeholder = { Text("Select or type model name") },
                            trailingIcon = { 
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = isModelDropdownExpanded
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = isModelDropdownExpanded,
                            onDismissRequest = { isModelDropdownExpanded = false }
                        ) {
                            // Popular free/cheap OpenRouter models
                            OpenRouterModels.popularModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(model.name)
                                            Text(
                                                model.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        modelName = model.id
                                        isModelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Link to browse all models
                    TextButton(
                        onClick = { /* Could open browser to openrouter.ai/models */ },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("Browse all models at openrouter.ai/models", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // Standard text field for other providers
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Model (optional)") },
                        placeholder = { Text("Leave blank for default") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Security note
                Text(
                    text = "🔒 Your API key will be encrypted and saved locally",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedKey = apiKey.trim()
                    val finalModelName = modelName.ifBlank { 
                        ProviderConfig.defaultModel(selectedProvider) 
                    }
                    
                    // Save API key securely
                    val saved = apiKeyStore.saveApiKey(
                        provider = selectedProvider,
                        apiKey = trimmedKey,
                        modelName = finalModelName
                    )
                    
                    if (saved) {
                        Log.i("MainActivity", "API key saved successfully for ${selectedProvider.name}")
                    } else {
                        Log.w("MainActivity", "Failed to save API key, but continuing...")
                    }
                    
                    onSave(ProviderConfig(
                        provider = selectedProvider,
                        apiKey = trimmedKey,
                        modelName = finalModelName,
                        enabled = true
                    ))
                },
                enabled = apiKey.isNotBlank()
            ) {
                Text("Save & Continue")
            }
        },
        dismissButton = {
            Row {
                // Show Delete button only if there's a saved API key
                if (apiKey.isNotBlank()) {
                    TextButton(
                        onClick = {
                            apiKeyStore.deleteApiKey()
                            apiKey = ""
                            modelName = ""
                            Log.i("MainActivity", "API key cleared")
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * Popular OpenRouter models for the dropdown
 */
object OpenRouterModels {
    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String
    )
    
    val popularModels = listOf(
        // 🌟 FREE & POPULAR MODELS
        ModelInfo(
            id = "nvidia/llama-3.1-nemotron-70b-instruct",
            name = "⭐ NVIDIA Nemotron 70B",
            description = "Free • Most powerful free model • Best overall"
        ),
        ModelInfo(
            id = "meta-llama/llama-3.3-70b-instruct",
            name = "Meta Llama 3.3 70B",
            description = "Free • Latest Meta model • Great reasoning"
        ),
        ModelInfo(
            id = "meta-llama/llama-3.1-70b-instruct",
            name = "Meta Llama 3.1 70B",
            description = "Free • Meta's best open model • Reliable"
        ),
        ModelInfo(
            id = "google/gemini-flash-1.5",
            name = "Google Gemini Flash 1.5",
            description = "Free • Fast • Multilingual • Great value"
        ),
        ModelInfo(
            id = "google/gemini-flash-1.5-8b",
            name = "Google Gemini Flash 1.5 8B",
            description = "Free • Ultra fast • Good for simple tasks"
        ),
        ModelInfo(
            id = "meta-llama/llama-3.1-8b-instruct",
            name = "Meta Llama 3.1 8B",
            description = "Free • Fast • Good for simple tasks"
        ),
        ModelInfo(
            id = "mistralai/mistral-7b-instruct",
            name = "Mistral 7B",
            description = "Free • Fast • Efficient • Good balance"
        ),
        ModelInfo(
            id = "microsoft/phi-3-medium-128k-instruct",
            name = "Microsoft Phi-3 Medium",
            description = "Free • Good reasoning • 128K context"
        ),
        ModelInfo(
            id = "microsoft/phi-3-mini-128k-instruct",
            name = "Microsoft Phi-3 Mini",
            description = "Free • Very fast • 128K context"
        ),
        ModelInfo(
            id = "deepseek/deepseek-chat",
            name = "DeepSeek V3",
            description = "Free • Excellent for coding • Chinese & English"
        ),
        ModelInfo(
            id = "qwen/qwen-2.5-72b-instruct",
            name = "Qwen 2.5 72B",
            description = "Free • Alibaba's best • Multilingual"
        ),
        ModelInfo(
            id = "qwen/qwen-2.5-7b-instruct",
            name = "Qwen 2.5 7B",
            description = "Free • Fast • Good for daily tasks"
        ),
        ModelInfo(
            id = "huggingfaceh4/zephyr-7b-beta",
            name = "Zephyr 7B",
            description = "Free • Helpful assistant • Good chat"
        ),
        
        // 💻 CODING SPECIALISTS
        ModelInfo(
            id = "deepseek/deepseek-coder",
            name = "DeepSeek Coder",
            description = "Free • Specialized for code • Programming"
        ),
        ModelInfo(
            id = "codellama/codellama-70b-instruct",
            name = "CodeLlama 70B",
            description = "Free • Meta's code model • Programming"
        ),
        ModelInfo(
            id = "mistralai/codestral-mamba",
            name = "Codestral Mamba",
            description = "Free • Code specialist • Fill-in-the-middle"
        ),
        
        // 🚀 PREMIUM MODELS (Require credits)
        ModelInfo(
            id = "openai/gpt-4o",
            name = "💎 OpenAI GPT-4o",
            description = "Premium • Most capable • Vision support"
        ),
        ModelInfo(
            id = "openai/gpt-4o-mini",
            name = "OpenAI GPT-4o Mini",
            description = "Cheap • Smart • Fast • Good value"
        ),
        ModelInfo(
            id = "anthropic/claude-3.5-sonnet",
            name = "💎 Claude 3.5 Sonnet",
            description = "Premium • Excellent reasoning • Coding"
        ),
        ModelInfo(
            id = "anthropic/claude-3-haiku",
            name = "Claude 3 Haiku",
            description = "Cheap • Fast • Good for quick tasks"
        ),
        ModelInfo(
            id = "google/gemini-pro-1.5",
            name = "💎 Gemini Pro 1.5",
            description = "Premium • 2M context • Vision • Reasoning"
        ),
        ModelInfo(
            id = "x-ai/grok-beta",
            name = "xAI Grok",
            description = "Premium • X platform knowledge • Real-time"
        ),
        
        // 🎯 SPECIALIZED MODELS
        ModelInfo(
            id = "perplexity/sonar",
            name = "Perplexity Sonar",
            description = "Web search • Real-time info • Citations"
        ),
        ModelInfo(
            id = "nvidia/llama-3.1-nemotron-70b-instruct:free",
            name = "Nemotron 70B (Extended)",
            description = "Free • Extended thinking • Step-by-step"
        )
    )
}
