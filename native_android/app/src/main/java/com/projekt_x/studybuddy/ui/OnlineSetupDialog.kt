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
@Composable
fun OnlineSetupDialog(
    apiKeyStore: ApiKeyStore,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ApiProvider.DEEPSEEK) }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    
    // Set default model
    LaunchedEffect(selectedProvider) {
        modelName = ProviderConfig.defaultModel(selectedProvider)
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
                
                // Model name
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("Model (optional)") },
                    placeholder = { Text("Leave blank for default") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
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
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
