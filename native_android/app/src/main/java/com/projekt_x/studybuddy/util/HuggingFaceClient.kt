package com.projekt_x.studybuddy.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "HuggingFaceClient"
private const val HF_API_BASE = "https://huggingface.co/api"
private const val HF_CDN_BASE = "https://huggingface.co"

/**
 * Hugging Face API Client
 * Allows searching and downloading GGUF models from Hugging Face Hub
 */
class HuggingFaceClient {
    
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    data class HuggingFaceModel(
        val id: String,
        val author: String,
        val downloads: Int,
        val likes: Int,
        val tags: List<String>,
        val ggufFiles: List<GGUFFile>
    )
    
    data class GGUFFile(
        val path: String,
        val size: Long,
        val url: String
    )
    
    /**
     * Search for GGUF models on Hugging Face
     */
    suspend fun searchModels(
        query: String = "",
        limit: Int = 20
    ): Result<List<HuggingFaceModel>> = withContext(Dispatchers.IO) {
        try {
            // First search for models
            val searchQuery = if (query.isBlank()) "gguf" else "$query gguf"
            val url = "$HF_API_BASE/models?search=${searchQuery.encodeUrl()}&limit=$limit"
            
            Log.d(TAG, "Searching HF: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Search failed: ${response.code}"))
            }
            
            val jsonArray = JSONArray(response.body?.string() ?: "[]")
            val models = mutableListOf<HuggingFaceModel>()
            
            Log.d(TAG, "Got ${jsonArray.length()} models from search")
            
            // For each model, get the files
            for (i in 0 until minOf(jsonArray.length(), limit)) {
                val obj = jsonArray.getJSONObject(i)
                val modelId = obj.optString("id", "")
                
                // Get model files
                val filesResult = getModelFiles(modelId)
                val ggufFiles = filesResult.getOrNull() ?: emptyList()
                
                if (ggufFiles.isNotEmpty()) {
                    models.add(
                        HuggingFaceModel(
                            id = modelId,
                            author = obj.optString("author", ""),
                            downloads = obj.optInt("downloads", 0),
                            likes = obj.optInt("likes", 0),
                            tags = parseTags(obj.optJSONArray("tags")),
                            ggufFiles = ggufFiles
                        )
                    )
                }
            }
            
            Log.i(TAG, "Found ${models.size} models with GGUF files")
            Result.success(models)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get files for a specific model
     */
    private suspend fun getModelFiles(modelId: String): Result<List<GGUFFile>> = withContext(Dispatchers.IO) {
        try {
            val url = "$HF_API_BASE/models/$modelId/tree/main"
            
            Log.d(TAG, "Fetching files: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                // Try resolving without /tree/main (some repos use different structure)
                return@withContext Result.success(emptyList())
            }
            
            val jsonArray = JSONArray(response.body?.string() ?: "[]")
            val files = mutableListOf<GGUFFile>()
            
            for (i in 0 until jsonArray.length()) {
                val fileObj = jsonArray.getJSONObject(i)
                val path = fileObj.optString("path", "")
                
                if (path.endsWith(".gguf", ignoreCase = true)) {
                    val size = fileObj.optLong("size", 0)
                    files.add(
                        GGUFFile(
                            path = path,
                            size = size,
                            url = "$HF_CDN_BASE/$modelId/resolve/main/$path"
                        )
                    )
                }
            }
            
            Log.d(TAG, "Found ${files.size} GGUF files for $modelId")
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting files for $modelId", e)
            Result.success(emptyList()) // Return empty on error, don't fail entire search
        }
    }
    
    /**
     * Get trending/popular GGUF models
     */
    suspend fun getTrendingModels(limit: Int = 10): Result<List<HuggingFaceModel>> = withContext(Dispatchers.IO) {
        try {
            // Get popular models sorted by downloads
            val url = "$HF_API_BASE/models?filter=gguf&sort=downloads&direction=-1&limit=${limit * 3}"
            
            Log.d(TAG, "Fetching trending: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Failed: ${response.code}"))
            }
            
            val jsonArray = JSONArray(response.body?.string() ?: "[]")
            val models = mutableListOf<HuggingFaceModel>()
            
            for (i in 0 until minOf(jsonArray.length(), limit * 3)) {
                val obj = jsonArray.getJSONObject(i)
                val modelId = obj.optString("id", "")
                
                // Get model files
                val filesResult = getModelFiles(modelId)
                val ggufFiles = filesResult.getOrNull() ?: emptyList()
                
                if (ggufFiles.isNotEmpty()) {
                    models.add(
                        HuggingFaceModel(
                            id = modelId,
                            author = obj.optString("author", ""),
                            downloads = obj.optInt("downloads", 0),
                            likes = obj.optInt("likes", 0),
                            tags = parseTags(obj.optJSONArray("tags")),
                            ggufFiles = ggufFiles
                        )
                    )
                    
                    if (models.size >= limit) break
                }
            }
            
            Log.i(TAG, "Found ${models.size} trending models")
            Result.success(models)
        } catch (e: Exception) {
            Log.e(TAG, "Trending error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Download a GGUF file with progress tracking
     */
    fun downloadModel(
        modelId: String,
        filePath: String,
        destinationFile: File
    ): Flow<DownloadProgress> = flow {
        try {
            val url = "$HF_CDN_BASE/$modelId/resolve/main/$filePath"
            
            Log.i(TAG, "Downloading: $url")
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                emit(DownloadProgress.Error("Download failed: ${response.code}"))
                return@flow
            }
            
            val body = response.body ?: run {
                emit(DownloadProgress.Error("Empty response"))
                return@flow
            }
            
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            
            emit(DownloadProgress.Started(totalBytes))
            
            FileOutputStream(destinationFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            emit(DownloadProgress.Progress(progress, downloadedBytes, totalBytes))
                        }
                    }
                }
            }
            
            emit(DownloadProgress.Completed(destinationFile))
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            emit(DownloadProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
    
    private fun parseTags(tagsArray: JSONArray?): List<String> {
        val tags = mutableListOf<String>()
        tagsArray?.let {
            for (i in 0 until it.length()) {
                tags.add(it.getString(i))
            }
        }
        return tags
    }
    
    private fun String.encodeUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
    
    sealed class DownloadProgress {
        data class Started(val totalBytes: Long) : DownloadProgress()
        data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadProgress()
        data class Completed(val file: File) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}
