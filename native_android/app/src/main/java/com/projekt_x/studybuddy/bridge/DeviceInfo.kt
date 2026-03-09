package com.projekt_x.studybuddy.bridge

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.RandomAccessFile
import java.util.regex.Pattern

private const val TAG = "DeviceInfo"

/**
 * Comprehensive device information for model recommendations
 */
object DeviceInfo {
    
    data class DeviceSpecs(
        val totalRamMB: Long,
        val totalRamGB: Float,
        val ramCategory: String,
        val cpuCores: Int,
        val cpuName: String,
        val cpuFrequencyMHz: Int,
        val cpuPerformance: CpuPerformance,
        val availableRamMB: Long,
        val isLowMemory: Boolean
    ) {
        fun getRecommendedModelTier(): ModelTier {
            return when {
                totalRamMB < 2500 -> ModelTier.ULTRA_LIGHT      // < 2.5GB
                totalRamMB < 3500 -> ModelTier.LIGHT            // 2.5-3.5GB (like your device)
                totalRamMB < 5000 -> ModelTier.MEDIUM           // 4-5GB
                totalRamMB < 7000 -> ModelTier.HIGH             // 6-7GB
                totalRamMB < 10000 -> ModelTier.VERY_HIGH       // 8-10GB
                else -> ModelTier.ULTRA_HIGH                     // 12GB+
            }
        }
    }
    
    enum class CpuPerformance {
        LOW,      // Entry-level (4 cores, older)
        MEDIUM,   // Mid-range (8 cores, up to 2GHz)
        HIGH,     // Flagship (8+ cores, 2.5GHz+)
        VERY_HIGH // Premium (8+ cores, 3GHz+)
    }
    
    enum class ModelTier(val displayName: String, val minRamGB: Int, val description: String) {
        ULTRA_LIGHT("Ultra Light (2GB)", 2, "Basic models for low-end devices"),
        LIGHT("Light (3GB)", 2, "Lightweight models for budget devices"),
        MEDIUM("Medium (4-5GB)", 4, "Balanced models for mid-range devices"),
        HIGH("High (6-7GB)", 6, "Better quality models"),
        VERY_HIGH("Very High (8-10GB)", 8, "Large models for flagship devices"),
        ULTRA_HIGH("Ultra (12GB+)", 10, "Full-size models for premium devices")
    }
    
    fun getDeviceSpecs(context: Context): DeviceSpecs {
        val ramInfo = getDetailedRamInfo(context)
        val cpuInfo = getCpuInfo()
        
        return DeviceSpecs(
            totalRamMB = ramInfo.totalMB,
            totalRamGB = ramInfo.totalGB,
            ramCategory = getRamCategory(ramInfo.totalMB),
            cpuCores = cpuInfo.cores,
            cpuName = cpuInfo.name,
            cpuFrequencyMHz = cpuInfo.maxFrequencyMHz,
            cpuPerformance = cpuInfo.performance,
            availableRamMB = ramInfo.availableMB,
            isLowMemory = ramInfo.isLowMemory
        )
    }
    
    /**
     * Get detailed RAM information
     */
    private data class RamInfo(
        val totalMB: Long,
        val totalGB: Float,
        val availableMB: Long,
        val isLowMemory: Boolean
    )
    
    private fun getDetailedRamInfo(context: Context): RamInfo {
        return try {
            // Method 1: ActivityManager (most reliable)
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val totalBytes = memoryInfo.totalMem
            val totalMB = totalBytes / (1024 * 1024)
            val totalGB = totalMB / 1024f
            val availableMB = memoryInfo.availMem / (1024 * 1024)
            
            Log.i(TAG, "RAM detected: ${totalMB}MB (${String.format("%.2f", totalGB)}GB)")
            
            RamInfo(
                totalMB = totalMB,
                totalGB = totalGB,
                availableMB = availableMB,
                isLowMemory = memoryInfo.lowMemory
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get RAM from ActivityManager, trying fallback", e)
            // Fallback to /proc/meminfo
            getRamFromProc()
        }
    }
    
    private fun getRamFromProc(): RamInfo {
        return try {
            val reader = BufferedReader(FileReader("/proc/meminfo"))
            val line = reader.readLine() // First line is MemTotal
            reader.close()
            
            val matcher = Pattern.compile("\\d+").matcher(line)
            val memTotalKB = if (matcher.find()) matcher.group().toLong() else 0
            val totalMB = memTotalKB / 1024
            val totalGB = totalMB / 1024f
            
            Log.i(TAG, "RAM from /proc/meminfo: ${totalMB}MB (${String.format("%.2f", totalGB)}GB)")
            
            RamInfo(
                totalMB = totalMB,
                totalGB = totalGB,
                availableMB = totalMB / 4, // Estimate
                isLowMemory = totalMB < 2000
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read RAM", e)
            RamInfo(3072, 3f, 512, false) // Default to 3GB
        }
    }
    
    private data class CpuInfo(
        val cores: Int,
        val name: String,
        val maxFrequencyMHz: Int,
        val performance: CpuPerformance
    )
    
    private fun getCpuInfo(): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val name = getCpuName()
        val maxFreq = getMaxCpuFrequency()
        
        val performance = when {
            maxFreq > 2500 && cores >= 8 -> CpuPerformance.VERY_HIGH
            maxFreq > 2000 && cores >= 8 -> CpuPerformance.HIGH
            maxFreq > 1500 && cores >= 4 -> CpuPerformance.MEDIUM
            else -> CpuPerformance.LOW
        }
        
        return CpuInfo(cores, name, maxFreq, performance)
    }
    
    private fun getCpuName(): String {
        return try {
            val reader = BufferedReader(FileReader("/proc/cpuinfo"))
            var line: String?
            var hardware = "Unknown"
            var modelName = ""
            
            while (reader.readLine().also { line = it } != null) {
                when {
                    line!!.contains("Hardware") -> {
                        hardware = line!!.substringAfter(":").trim()
                    }
                    line!!.contains("model name") && modelName.isEmpty() -> {
                        modelName = line!!.substringAfter(":").trim()
                    }
                }
            }
            reader.close()
            
            // Return the best available name
            when {
                modelName.isNotEmpty() -> modelName
                hardware.isNotEmpty() && hardware != "Unknown" -> hardware
                else -> Build.HARDWARE
            }
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }
    
    private fun getMaxCpuFrequency(): Int {
        var maxFreq = 0
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val reader = RandomAccessFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq", "r")
                val freq = reader.readLine().toInt() / 1000 // Convert to MHz
                reader.close()
                if (freq > maxFreq) maxFreq = freq
            } catch (e: Exception) {
                // Ignore errors for individual cores
            }
        }
        return if (maxFreq > 0) maxFreq else 1500 // Default to 1.5GHz
    }
    
    private fun getRamCategory(totalMB: Long): String {
        return when {
            totalMB < 1500 -> "2GB"
            totalMB < 2500 -> "2-3GB"
            totalMB < 3500 -> "3-4GB"
            totalMB < 5000 -> "4GB"
            totalMB < 7000 -> "6GB"
            totalMB < 10000 -> "8GB"
            else -> "12GB+"
        }
    }
    
    /**
     * Get exact RAM for display (e.g., "2.8GB" instead of rounded "3GB")
     */
    fun getExactRamGB(context: Context): Float {
        return getDetailedRamInfo(context).totalGB
    }
    
    /**
     * Legacy method for backward compatibility
     */
    fun getTotalRamGB(context: Context): Int {
        return Math.round(getExactRamGB(context))
    }
    
    /**
     * Get available (free) RAM in MB
     */
    fun getAvailableRamMB(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            512 // Assume 512MB free
        }
    }
    
    /**
     * Check if device is low on memory
     */
    fun isLowMemory(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.lowMemory
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Format file size for display
     */
    fun formatFileSize(sizeGB: Float): String {
        return when {
            sizeGB < 1f -> "${(sizeGB * 1024).toInt()}MB"
            sizeGB == 1f -> "1GB"
            else -> "${sizeGB}GB"
        }
    }
    
    /**
     * Get a detailed device summary for debugging/display
     */
    fun getDeviceSummary(context: Context): String {
        val specs = getDeviceSpecs(context)
        return buildString {
            appendLine("📱 Device: ${Build.MODEL} (${Build.MANUFACTURER})")
            appendLine("🧮 RAM: ${String.format("%.2f", specs.totalRamGB)}GB (${specs.totalRamMB}MB)")
            appendLine("💾 Available RAM: ${specs.availableRamMB}MB")
            appendLine("⚡ CPU: ${specs.cpuName}")
            appendLine("🖥️ CPU Cores: ${specs.cpuCores}")
            appendLine("📊 CPU Max Freq: ${specs.cpuFrequencyMHz}MHz")
            appendLine("🏆 CPU Class: ${specs.cpuPerformance}")
            appendLine("🎯 Recommended Tier: ${specs.getRecommendedModelTier().displayName}")
        }
    }
}
