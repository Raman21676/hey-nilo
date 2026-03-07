package com.projekt_x.studybuddy

import android.content.Context
import android.util.Log
import com.projekt_x.studybuddy.model.ModelInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "ModelDownloader"

/**
 * Download state for UI updates
 */
data class DownloadState(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,
    val isComplete: Boolean = false,
    val isVerifying: Boolean = false,
    val error: String? = null,
    val modelName: String = ""
)

/**
 * Enhanced Model Downloader with:
 * - Multiple model support
 * - Resume capability
 * - SHA256 verification
 * - Device capability-based recommendations
 */
class ModelDownloader(private val context: Context) {

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private var downloadJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * Start downloading a specific model
     */
    fun startDownload(modelInfo: ModelInfo) {
        if (downloadJob?.isActive == true) return

        _state.value = DownloadState(isDownloading = true, modelName = modelInfo.name)

        downloadJob = coroutineScope.launch {
            try {
                downloadModel(modelInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${modelInfo.name}", e)
                _state.value = _state.value.copy(
                    isDownloading = false,
                    error = e.message ?: "Download failed"
                )
            }
        }
    }

    /**
     * Start downloading the default/recommended model
     */
    fun startDownloadDefault() {
        val ramMB = getAvailableRamMB()
        val recommended = ModelInfo.getRecommendedModel(ramMB.toInt())
        startDownload(recommended)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = DownloadState()
    }

    /**
     * Get download progress as percentage
     */
    fun getProgress(): Int = _state.value.progress

    /**
     * Check if a model is already downloaded
     */
    fun isModelDownloaded(modelInfo: ModelInfo): Boolean {
        val file = getModelFile(modelInfo.filename)
        return file.exists() && file.length() == modelInfo.size
    }

    /**
     * Get downloaded models
     */
    fun getDownloadedModels(): List<ModelInfo.LLM> {
        return ModelInfo.LLM_MODELS.filter { isModelDownloaded(it) }
    }

    /**
     * Verify model SHA256 checksum
     */
    fun verifyModel(modelInfo: ModelInfo): Boolean {
        if (modelInfo.sha256 == null) {
            Log.w(TAG, "No SHA256 provided for ${modelInfo.name}, skipping verification")
            return true
        }

        val file = getModelFile(modelInfo.filename)
        if (!file.exists()) return false

        _state.value = _state.value.copy(isVerifying = true)

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val hashString = hashBytes.joinToString("") { "%02x".format(it) }

            val matches = hashString.equals(modelInfo.sha256, ignoreCase = true)
            if (!matches) {
                Log.e(TAG, "SHA256 mismatch for ${modelInfo.name}")
                Log.e(TAG, "Expected: ${modelInfo.sha256}")
                Log.e(TAG, "Actual:   $hashString")
            }

            _state.value = _state.value.copy(isVerifying = false)
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed: ${e.message}")
            _state.value = _state.value.copy(isVerifying = false)
            false
        }
    }

    private suspend fun downloadModel(modelInfo: ModelInfo) {
        val outputFile = getModelFile(modelInfo.filename)
        val tempFile = File(outputFile.parentFile, "${modelInfo.filename}.tmp")

        // Check if already exists and correct size
        if (outputFile.exists() && outputFile.length() == modelInfo.size) {
            // Verify if SHA256 available
            if (modelInfo.sha256 != null) {
                _state.value = _state.value.copy(isVerifying = true)
                if (verifyModel(modelInfo)) {
                    _state.value = DownloadState(
                        isComplete = true,
                        progress = 100,
                        modelName = modelInfo.name
                    )
                    return
                } else {
                    // Delete corrupted file
                    outputFile.delete()
                }
            } else {
                _state.value = DownloadState(
                    isComplete = true,
                    progress = 100,
                    modelName = modelInfo.name
                )
                return
            }
        }

        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var fileOutput: FileOutputStream? = null

            try {
                val url = URL(modelInfo.url)
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mini/1.0")

                    // Support resume
                    if (tempFile.exists()) {
                        setRequestProperty("Range", "bytes=${tempFile.length()}-")
                        Log.i(TAG, "Resuming download from ${tempFile.length()} bytes")
                    }
                }

                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL
                ) {
                    throw Exception("Server returned HTTP $responseCode")
                }

                val totalLength = if (responseCode == HttpURLConnection.HTTP_PARTIAL && tempFile.exists()) {
                    tempFile.length() + connection.contentLengthLong
                } else {
                    connection.contentLengthLong
                }

                // Reset temp file if server doesn't support resume
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    tempFile.delete()
                }

                val append = responseCode == HttpURLConnection.HTTP_PARTIAL
                fileOutput = FileOutputStream(tempFile, append)

                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var downloaded = if (append) tempFile.length() else 0
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastDownloaded = downloaded

                    while (isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        fileOutput.write(buffer, 0, read)
                        downloaded += read

                        // Update progress every 500ms
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500) {
                            val timeDelta = (currentTime - lastUpdateTime) / 1000.0
                            val byteDelta = downloaded - lastDownloaded
                            val speed = if (timeDelta > 0) (byteDelta / timeDelta).toLong() else 0

                            val progress = if (totalLength > 0) {
                                ((downloaded * 100) / totalLength).toInt()
                            } else 0

                            _state.value = _state.value.copy(
                                isDownloading = true,
                                progress = progress.coerceIn(0, 100),
                                downloadedBytes = downloaded,
                                totalBytes = totalLength,
                                speed = speed,
                                modelName = modelInfo.name
                            )

                            lastUpdateTime = currentTime
                            lastDownloaded = downloaded
                        }
                    }
                }

                fileOutput.close()

                // Verify download completeness
                if (tempFile.length() < totalLength * 0.99) {
                    throw Exception("Download incomplete: ${tempFile.length()}/$totalLength")
                }

                // Move temp to final
                if (!tempFile.renameTo(outputFile)) {
                    throw Exception("Failed to move downloaded file")
                }

                // Verify SHA256 if available
                if (modelInfo.sha256 != null) {
                    _state.value = _state.value.copy(isVerifying = true)
                    if (!verifyModel(modelInfo)) {
                        outputFile.delete()
                        throw Exception("SHA256 verification failed")
                    }
                }

                _state.value = DownloadState(
                    isDownloading = false,
                    isComplete = true,
                    progress = 100,
                    downloadedBytes = outputFile.length(),
                    totalBytes = outputFile.length(),
                    modelName = modelInfo.name
                )

                Log.i(TAG, "Download complete: ${outputFile.absolutePath}")

            } finally {
                try {
                    fileOutput?.close()
                } catch (e: Exception) {
                    // Ignore
                }
                connection?.disconnect()
            }
        }
    }

    private fun getModelFile(filename: String): File {
        val modelsDir = File(context.getExternalFilesDir(null), "models").apply {
            if (!exists()) mkdirs()
        }
        return File(modelsDir, filename)
    }

    private fun getAvailableRamMB(): Long {
        return try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/meminfo"))
            var line: String?
            var memAvailableKB = 0L
            while (reader.readLine().also { line = it } != null) {
                if (line?.startsWith("MemAvailable:") == true) {
                    memAvailableKB = line?.replace(Regex("[^0-9]"), "")?.toLong() ?: 0
                    break
                }
            }
            reader.close()
            memAvailableKB / 1024
        } catch (e: Exception) {
            2000 // Conservative default
        }
    }

    /**
     * Format bytes to human readable string
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    /**
     * Format speed to human readable string
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000.0)
            bytesPerSecond >= 1_000 -> "%.0f KB/s".format(bytesPerSecond / 1_000.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    /**
     * Get recommended model for this device
     */
    fun getRecommendedModel(): ModelInfo.LLM {
        val ramMB = getAvailableRamMB()
        return ModelInfo.getRecommendedModel(ramMB.toInt())
    }

    /**
     * Get all suitable models for this device
     */
    fun getSuitableModels(): List<ModelInfo.LLM> {
        val ramMB = getAvailableRamMB()
        return ModelInfo.getSuitableModels(ramMB.toInt())
    }
}
