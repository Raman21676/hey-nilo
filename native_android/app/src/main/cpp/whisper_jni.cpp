#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <mutex>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Context structure to hold whisper state
struct WhisperContext {
    whisper_context* ctx;
    whisper_full_params params;
    bool model_loaded = false;
    std::mutex inference_mutex;  // Prevent concurrent whisper_full() calls
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("nativeInit called");
    WhisperContext* context = new WhisperContext();
    context->ctx = nullptr;
    context->model_loaded = false;
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("nativeRelease called");
    
    if (handle == 0) return;
    
    WhisperContext* context = reinterpret_cast<WhisperContext*>(handle);
    if (context->ctx) {
        whisper_free(context->ctx);
        context->ctx = nullptr;
    }
    context->model_loaded = false;
    delete context;
    LOGI("Whisper context released");
}

JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jlong handle, jstring modelPath
) {
    LOGI("nativeLoadModel called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return JNI_FALSE;
    }
    
    WhisperContext* context = reinterpret_cast<WhisperContext*>(handle);
    
    // Release existing model if any
    if (context->ctx) {
        whisper_free(context->ctx);
        context->ctx = nullptr;
        context->model_loaded = false;
    }
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }
    
    LOGI("Loading model from: %s", path);
    
    // Whisper context parameters
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only for compatibility
    
    context->ctx = whisper_init_from_file_with_params(path, cparams);
    
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (!context->ctx) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    
    // Initialize default parameters
    context->params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    context->params.print_realtime = false;
    context->params.print_progress = false;
    context->params.print_timestamps = false;
    context->params.print_special = false;
    context->params.translate = false;
    context->params.language = "en";
    // Use optimal thread count for device (Samsung Tab A7 Lite has 8 cores)
    // BUG FIX 2: Speed optimization
    context->params.n_threads = 6;  // Use 6 threads for better performance
    context->params.offset_ms = 0;
    context->params.duration_ms = 0;
    context->params.single_segment = true;  // Faster processing for short audio
    context->params.no_context = true;  // BUG FIX 2: Disable context for speed
    context->params.max_len = 0;  // Disable max length limit for faster processing
    
    // BUG FIX 3: Accuracy improvements
    context->params.language = "en";  // Force English for consistency
    context->params.initial_prompt = "User is speaking to an AI assistant.";  // Bias toward conversation
    
    // Prevents hallucinations on silence/noise:
    context->params.no_speech_thold = 0.6f;  // skip if confidence < 60%
    context->params.logprob_thold = -1.0f;   // skip low-probability results
    
    context->model_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// Simple linear resampling
static std::vector<float> resample_audio(const std::vector<float>& input, int src_rate, int dst_rate) {
    if (src_rate == dst_rate) {
        return input;
    }
    
    const size_t dst_length = static_cast<size_t>(input.size() * static_cast<double>(dst_rate) / src_rate);
    std::vector<float> output(dst_length);
    
    const double ratio = static_cast<double>(src_rate) / dst_rate;
    
    for (size_t i = 0; i < dst_length; i++) {
        double src_idx = i * ratio;
        size_t src_i = static_cast<size_t>(src_idx);
        double frac = src_idx - src_i;
        
        if (src_i >= input.size() - 1) {
            output[i] = input.back();
        } else {
            output[i] = input[src_i] * (1.0f - frac) + input[src_i + 1] * frac;
        }
    }
    
    return output;
}

JNIEXPORT jstring JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong handle, jshortArray audioData, jint sampleRate
) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return env->NewStringUTF("");
    }
    
    WhisperContext* context = reinterpret_cast<WhisperContext*>(handle);
    
    if (!context->ctx || !context->model_loaded) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }
    
    if (!audioData) {
        LOGE("Null audio data");
        return env->NewStringUTF("");
    }
    
    jsize audioLength = env->GetArrayLength(audioData);
    if (audioLength == 0) {
        LOGW("Empty audio data");
        return env->NewStringUTF("");
    }
    
    // Limit audio length to prevent memory issues (max 8 seconds at 16kHz)
    // Reduced from 10s to 8s to match MAX_SPEECH_MS
    const int MAX_SAMPLES = 16000 * 8;
    if (audioLength > MAX_SAMPLES) {
        LOGW("Audio too long (%d samples), truncating to %d", audioLength, MAX_SAMPLES);
        audioLength = MAX_SAMPLES;
    }
    
    jshort* audioBytes = env->GetShortArrayElements(audioData, nullptr);
    if (!audioBytes) {
        LOGE("Failed to get audio data");
        return env->NewStringUTF("");
    }
    
    // Convert short to float and normalize to [-1, 1]
    std::vector<float> pcmf32(audioLength);
    for (int i = 0; i < audioLength; i++) {
        pcmf32[i] = audioBytes[i] / 32768.0f;
    }
    
    env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
    
    LOGI("Transcribing %d samples at %d Hz", audioLength, sampleRate);
    
    // Resample to 16kHz if needed
    std::vector<float> pcmf32_resampled = resample_audio(pcmf32, sampleRate, 16000);
    
    if (pcmf32_resampled.empty()) {
        LOGE("Resampling failed");
        return env->NewStringUTF("");
    }
    
    LOGI("Resampled to %zu samples at 16000 Hz", pcmf32_resampled.size());
    
    // Check minimum audio length (500ms at 16kHz = 8000 samples) — filters noise bursts
    if (pcmf32_resampled.size() < 8000) {
        LOGW("Audio too short (%zu samples < 8000), likely noise — skipping", pcmf32_resampled.size());
        return env->NewStringUTF("");
    }
    
    // Run inference with mutex lock to prevent concurrent access
    std::unique_lock<std::mutex> lock(context->inference_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        LOGW("Whisper busy — dropping audio segment to prevent crash");
        return env->NewStringUTF("");
    }
    
    int ret = whisper_full(context->ctx, context->params, pcmf32_resampled.data(), pcmf32_resampled.size());
    
    if (ret != 0) {
        LOGE("Whisper failed to process audio: %d", ret);
        return env->NewStringUTF("");
    }
    
    // Get results
    int n_segments = whisper_full_n_segments(context->ctx);
    LOGI("Got %d segments", n_segments);
    
    std::string result_text;
    
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(context->ctx, i);
        if (text) {
            result_text += text;
        }
    }
    
    // Trim whitespace
    size_t start = result_text.find_first_not_of(" \t\n\r");
    if (start != std::string::npos) {
        size_t end = result_text.find_last_not_of(" \t\n\r");
        result_text = result_text.substr(start, end - start + 1);
    } else {
        result_text = "";
    }
    
    LOGI("Transcription: \"%s\"", result_text.c_str());
    
    return env->NewStringUTF(result_text.c_str());
}

} // extern "C"
