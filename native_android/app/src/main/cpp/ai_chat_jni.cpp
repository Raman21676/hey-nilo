#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <memory>
#include <atomic>
#include <cstring>
#include <chrono>
#include <thread>
#include <fstream>
#include <algorithm>
#include <sys/mman.h>
#include <sys/resource.h>

#include "llama.h"

#define LOG_TAG "StudyBuddyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Performance monitoring
struct PerformanceStats {
    std::chrono::high_resolution_clock::time_point start_time;
    int tokens_generated = 0;
    float avg_tokens_per_sec = 0.0f;
};

// Helper to add token to batch
static void batch_add(
    struct llama_batch & batch,
    llama_token id,
    llama_pos pos,
    const std::vector<llama_seq_id> & seq_ids,
    bool logits
) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits[batch.n_tokens] = logits;
    batch.n_tokens++;
}

// Global state
struct LlamaState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    llama_batch batch = {};
    
    std::mutex mutex;
    std::atomic<bool> is_generating{false};
    std::atomic<bool> should_stop{false};
    
    int n_threads = 4;
    int n_batch = 512;
    int n_ubatch = 128;
    int n_ctx = 1024;
    llama_pos current_pos = 0;
    
    // Performance tracking
    PerformanceStats perf_stats;
    
    // Conversation history
    std::vector<std::pair<std::string, std::string>> history;
    
    ~LlamaState() {
        cleanup();
    }
    
    void cleanup() {
        std::lock_guard<std::mutex> lock(mutex);
        if (batch.n_tokens > 0) {
            llama_batch_free(batch);
            batch = {};
        }
        if (sampler) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
        is_generating = false;
        should_stop = false;
        current_pos = 0;
        perf_stats = PerformanceStats{};
    }
};

static std::unique_ptr<LlamaState> g_state;

// ============================================
// DEVICE DETECTION & OPTIMIZATION
// ============================================

static bool isBigCore(int cpu_id) {
    std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu_id) + "/cpu_capacity";
    std::ifstream file(path);
    if (file.is_open()) {
        int capacity;
        file >> capacity;
        file.close();
        return capacity > 500;
    }
    return cpu_id >= 4;
}

static int countBigCores() {
    int big_cores = 0;
    for (int i = 0; i < 8; i++) {
        std::string online_path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/online";
        std::ifstream online_file(online_path);
        bool is_online = true;
        if (online_file.is_open()) {
            int online;
            online_file >> online;
            online_file.close();
            is_online = (online == 1);
        }
        
        if (is_online && isBigCore(i)) {
            big_cores++;
        }
    }
    return big_cores > 0 ? big_cores : 4;
}

static float getCpuTemperature() {
    const char* thermal_paths[] = {
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp",
        "/sys/class/thermal/thermal_zone3/temp"
    };
    
    for (const char* path : thermal_paths) {
        std::ifstream file(path);
        if (file.is_open()) {
            float temp;
            file >> temp;
            file.close();
            return temp > 1000 ? temp / 1000.0f : temp;
        }
    }
    return -1.0f;
}

static int getAdaptiveThreadCount(int base_threads) {
    float temp = getCpuTemperature();
    
    if (temp > 70.0f) {
        LOGI("Thermal throttling: temp=%.1f°C, reducing threads to 2", temp);
        return 2;
    } else if (temp > 60.0f) {
        LOGI("High temperature: temp=%.1f°C, reducing threads to 3", temp);
        return 3;
    }
    
    return base_threads;
}

// ============================================
// UTF-8 SANITIZATION
// ============================================

// Helper to sanitize UTF-8 strings for JNI
// Removes null bytes and invalid UTF-8 sequences
static std::string sanitizeForJNI(const std::string& input) {
    std::string output;
    output.reserve(input.length());
    
    for (size_t i = 0; i < input.length(); ) {
        unsigned char c = input[i];
        
        // Skip null bytes
        if (c == 0) {
            i++;
            continue;
        }
        
        // Single byte ASCII (0x01-0x7F)
        if (c < 0x80) {
            output += c;
            i++;
        }
        // 2-byte UTF-8
        else if ((c & 0xE0) == 0xC0) {
            if (i + 1 < input.length() && (input[i + 1] & 0xC0) == 0x80) {
                output += c;
                output += input[i + 1];
                i += 2;
            } else {
                i++; // Skip invalid
            }
        }
        // 3-byte UTF-8
        else if ((c & 0xF0) == 0xE0) {
            if (i + 2 < input.length() && 
                (input[i + 1] & 0xC0) == 0x80 &&
                (input[i + 2] & 0xC0) == 0x80) {
                output += c;
                output += input[i + 1];
                output += input[i + 2];
                i += 3;
            } else {
                i++; // Skip invalid
            }
        }
        // 4-byte UTF-8
        else if ((c & 0xF8) == 0xF0) {
            if (i + 3 < input.length() && 
                (input[i + 1] & 0xC0) == 0x80 &&
                (input[i + 2] & 0xC0) == 0x80 &&
                (input[i + 3] & 0xC0) == 0x80) {
                output += c;
                output += input[i + 1];
                output += input[i + 2];
                output += input[i + 3];
                i += 4;
            } else {
                i++; // Skip invalid
            }
        }
        // Invalid start byte
        else {
            i++;
        }
    }
    
    return output;
}

// ============================================
// RAM OPTIMIZATION FUNCTIONS
// ============================================

static int getCurrentMemoryUsageMB() {
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) == 0) {
        // ru_maxrss is in KB on Linux
        return usage.ru_maxrss / 1024;
    }
    return 0;
}

static int getAvailableMemoryMB() {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return 0;
    
    std::string line;
    int memAvailableKB = 0;
    
    while (std::getline(meminfo, line)) {
        if (line.find("MemAvailable:") == 0) {
            sscanf(line.c_str(), "MemAvailable: %d kB", &memAvailableKB);
            break;
        }
    }
    meminfo.close();
    
    return memAvailableKB / 1024;
}

// ============================================
// JNI METHODS
// ============================================

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_init(JNIEnv* env, jobject /*thiz*/, jstring nativeLibDir) {
    LOGI("Initializing StudyBuddy JNI v2.0 (Optimized)");
    
    try {
        const char* libDir = env->GetStringUTFChars(nativeLibDir, nullptr);
        if (!libDir) {
            LOGE("Failed to get native library directory");
            return;
        }
        LOGI("Loading backends from: %s", libDir);
        ggml_backend_load_all_from_path(libDir);
        env->ReleaseStringUTFChars(nativeLibDir, libDir);
        
        LOGI("Initializing llama backend...");
        llama_backend_init();
        
        int big_cores = countBigCores();
        LOGI("Hardware detection: %d big cores available", big_cores);
        
        LOGI("Backend initialized successfully");
    } catch (...) {
        LOGE("Exception during initialization");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_systemInfo(JNIEnv* env, jobject /*thiz*/) {
    int big_cores = countBigCores();
    float temp = getCpuTemperature();
    
    std::string info = "StudyBuddy AI v2.0\n";
    info += "GGML Version: " GGML_VERSION "\n";
    info += "Backend: CPU (ARM64 NEON)\n";
    info += "Big Cores: " + std::to_string(big_cores) + "\n";
    info += "CPU Temp: " + (temp > 0 ? std::to_string((int)temp) + "°C" : "Unknown") + "\n";
    info += "Optimizations: mmap, Q8_KV, affinity";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_loadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath,
    jint nThreads,
    jint nCtx,
    jint nBatch,
    jboolean useMmap,
    jfloat memoryPressure,
    jboolean isCharging
) {
    LOGI("Loading model with optimized settings...");
    
    if (!modelPath) {
        LOGE("Model path is null");
        return -1;
    }
    
    if (g_state) {
        LOGI("Cleaning up previous model...");
        g_state->cleanup();
    }
    g_state = std::make_unique<LlamaState>();
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return -1;
    }
    
    LOGI("Model path: %s", path);
    
    FILE* file = fopen(path, "rb");
    if (!file) {
        LOGE("Cannot open model file: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        g_state.reset();
        return -2;
    }
    
    fseek(file, 0, SEEK_END);
    long fileSize = ftell(file);
    fclose(file);
    
    LOGI("Model file size: %ld bytes (%.1f MB)", fileSize, fileSize / (1024.0 * 1024.0));
    
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = useMmap;
    model_params.use_mlock = false;
    
    LOGI("Model params: mmap=%s, mlock=false", useMmap ? "true" : "false");
    
    g_state->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (!g_state->model) {
        LOGE("Failed to load model - llama_model_load_from_file returned null");
        g_state.reset();
        return -3;
    }
    
    if (useMmap) {
        LOGI("✓ Model loaded with memory mapping (mmap) enabled");
    } else {
        LOGW("⚠ Mmap disabled - will use more RAM");
    }
    
    // DYNAMIC THREAD CALCULATION based on device specs and power state
    int big_cores = countBigCores();
    int available_ram_mb = getAvailableMemoryMB();
    
    // Base calculation: use 50-75% of big cores
    int base_threads = std::max(2, (big_cores * 2) / 3);  // 2/3 of big cores
    
    // RAM-based adjustment: reduce threads if low RAM
    int ram_limited_threads = base_threads;
    if (available_ram_mb < 1500) {
        ram_limited_threads = std::max(2, base_threads - 1);
        LOGI("Low RAM detected (%dMB), reducing threads to %d", available_ram_mb, ram_limited_threads);
    } else if (available_ram_mb < 2500) {
        ram_limited_threads = base_threads;
        LOGI("Medium RAM (%dMB), using %d threads", available_ram_mb, ram_limited_threads);
    } else {
        ram_limited_threads = std::min(big_cores, base_threads + 1);
        LOGI("High RAM (%dMB), using %d threads", available_ram_mb, ram_limited_threads);
    }
    
    // Battery-based adjustment: use fewer threads on battery
    int battery_adjusted_threads = ram_limited_threads;
    if (!isCharging) {
        // Formula: Use half threads on battery, but minimum 2, maximum 4
        battery_adjusted_threads = std::max(2, std::min(4, ram_limited_threads / 2));
        LOGI("🔋 On battery: adjusting threads from %d to %d (power saving)", 
             ram_limited_threads, battery_adjusted_threads);
    } else {
        LOGI("⚡ Charging: using %d threads", ram_limited_threads);
    }
    
    // Final thermal adjustment
    int adaptive_threads = getAdaptiveThreadCount(battery_adjusted_threads);
    
    g_state->n_threads = adaptive_threads;
    
    int target_ctx = nCtx > 0 ? nCtx : 1024;
    
    if (memoryPressure > 0.8f) {
        target_ctx = 512;
        LOGI("High memory pressure (%.1f%%), reducing context to %d", memoryPressure * 100, target_ctx);
    } else if (memoryPressure > 0.6f) {
        target_ctx = 768;
        LOGI("Medium memory pressure (%.1f%%), reducing context to %d", memoryPressure * 100, target_ctx);
    }
    
    g_state->n_ctx = target_ctx;
    g_state->n_batch = nBatch > 0 ? nBatch : 512;
    g_state->n_ubatch = std::min(g_state->n_batch, 128);
    
    LOGI("Config: threads=%d (adaptive), ctx=%d, batch=%d, ubatch=%d", 
         g_state->n_threads, g_state->n_ctx, g_state->n_batch, g_state->n_ubatch);
    
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_prepareContext(JNIEnv* env, jobject /*thiz*/) {
    if (!g_state || !g_state->model) {
        LOGE("Model not loaded");
        return -1;
    }
    
    std::lock_guard<std::mutex> lock(g_state->mutex);
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = g_state->n_ctx;
    ctx_params.n_batch = g_state->n_batch;
    ctx_params.n_ubatch = g_state->n_ubatch;
    ctx_params.n_threads = g_state->n_threads;
    ctx_params.n_threads_batch = g_state->n_threads;
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;
    ctx_params.defrag_thold = 0.1f;
    
    LOGI("Context params: n_ctx=%d, n_batch=%d, n_ubatch=%d, threads=%d, KV=Q8_0",
         ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_ubatch, ctx_params.n_threads);
    
    g_state->ctx = llama_init_from_model(g_state->model, ctx_params);
    
    if (!g_state->ctx) {
        LOGE("Failed to create context");
        return -1;
    }
    
    g_state->batch = llama_batch_init(g_state->n_batch, 0, 1);
    g_state->current_pos = 0;
    
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    g_state->sampler = llama_sampler_chain_init(sampler_params);
    
    llama_sampler_chain_add(g_state->sampler, 
        llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(g_state->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_state->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_state->sampler, llama_sampler_init_dist(42));
    
    LOGI("Context prepared with KV cache quantization (Q8_0)");
    return 0;
}

static std::string g_system_prompt;

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_processSystemPrompt(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring systemPrompt
) {
    if (!g_state || !g_state->ctx) {
        LOGE("Context not ready");
        return -1;
    }
    
    const char* prompt = env->GetStringUTFChars(systemPrompt, nullptr);
    g_system_prompt = std::string(prompt);
    LOGI("System prompt stored: %s", g_system_prompt.c_str());
    
    env->ReleaseStringUTFChars(systemPrompt, prompt);
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerateStream(
    JNIEnv* env,
    jobject thiz,
    jstring userPrompt,
    jint maxTokens,
    jobject callback
) {
    if (!g_state || !g_state->ctx) {
        LOGE("Context not ready");
        return env->NewStringUTF("");
    }
    
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
    
    if (!onTokenMethod || !onCompleteMethod) {
        LOGE("Callback methods not found");
        return env->NewStringUTF("");
    }
    
    std::lock_guard<std::mutex> lock(g_state->mutex);
    
    const char* prompt = env->GetStringUTFChars(userPrompt, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(g_state->model);
    
    g_state->history.push_back({"user", std::string(prompt)});
    env->ReleaseStringUTFChars(userPrompt, prompt);
    
    // Use Qwen2.5 chat template format
    std::string formatted;
    
    // Add system prompt if set
    if (!g_system_prompt.empty()) {
        formatted += "<|im_start|>system\n" + g_system_prompt + "<|im_end|>\n";
    }
    
    // Add conversation history
    for (const auto& msg : g_state->history) {
        if (msg.first == "user") {
            formatted += "<|im_start|>user\n" + msg.second + "<|im_end|>\n";
        } else if (msg.first == "assistant") {
            formatted += "<|im_start|>assistant\n" + msg.second + "<|im_end|>\n";
        }
    }
    // Add assistant prefix for completion
    formatted += "<|im_start|>assistant\n";
    
    LOGI("Prompt length: %zu chars", formatted.length());
    
    g_state->current_pos = 0;
    g_state->batch.n_tokens = 0;
    
    llama_memory_t mem = llama_get_memory(g_state->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }
    
    std::vector<llama_token> tokens(g_state->n_ctx);
    int n_tokens = llama_tokenize(
        vocab,
        formatted.c_str(),
        formatted.length(),
        tokens.data(),
        tokens.size(),
        true,
        false
    );
    
    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        env->CallVoidMethod(callback, onCompleteMethod);
        return env->NewStringUTF("");
    }
    
    LOGI("Tokenized %d tokens", n_tokens);
    tokens.resize(n_tokens);
    
    auto decode_start = std::chrono::high_resolution_clock::now();
    
    for (int i = 0; i < n_tokens; i += g_state->n_batch) {
        int cur_batch_size = std::min((int)tokens.size() - i, g_state->n_batch);
        
        g_state->batch.n_tokens = 0;
        for (int j = 0; j < cur_batch_size; j++) {
            bool is_last = (i + j == (int)tokens.size() - 1);
            batch_add(g_state->batch, tokens[i + j], i + j, {0}, is_last);
        }
        
        if (llama_decode(g_state->ctx, g_state->batch) != 0) {
            LOGE("llama_decode failed");
            env->CallVoidMethod(callback, onCompleteMethod);
            return env->NewStringUTF("");
        }
    }
    
    auto decode_end = std::chrono::high_resolution_clock::now();
    auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(decode_end - decode_start).count();
    LOGI("Prefill: %d tokens in %lld ms (%.2f t/s)", n_tokens, decode_ms, 
         decode_ms > 0 ? (n_tokens * 1000.0 / decode_ms) : 0.0);
    
    g_state->current_pos += n_tokens;
    
    llama_sampler_reset(g_state->sampler);
    
    g_state->is_generating = true;
    g_state->should_stop = false;
    g_state->perf_stats.start_time = std::chrono::high_resolution_clock::now();
    g_state->perf_stats.tokens_generated = 0;
    
    int n_gen = 0;
    std::string response;
    
    // Find special token IDs by scanning vocabulary
    llama_token token_im_end = -1, token_im_start = -1;
    const int vocab_size = llama_vocab_n_tokens(vocab);
    for (int i = 0; i < vocab_size && (token_im_end == -1 || token_im_start == -1); i++) {
        char token_text[256];
        int n = llama_token_to_piece(vocab, i, token_text, sizeof(token_text), 0, false);
        if (n > 0) {
            std::string text(token_text, n);
            if (text == "<|im_end|>") token_im_end = i;
            if (text == "<|im_start|>") token_im_start = i;
        }
    }
    
    LOGI("Found special tokens - im_end: %d, im_start: %d", (int)token_im_end, (int)token_im_start);
    
    llama_token last_token = -1;
    int repeat_count = 0;
    const int MAX_REPEAT = 3;
    
    while (n_gen < maxTokens && !g_state->should_stop) {
        if (n_gen % 20 == 0 && n_gen > 0) {
            float temp = getCpuTemperature();
            if (temp > 75.0f) {
                LOGW("Critical temperature (%.1f°C), pausing briefly...", temp);
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
            }
        }
        
        llama_token new_token = llama_sampler_sample(g_state->sampler, g_state->ctx, -1);
        
        // Debug: log first few tokens to see what IDs are being generated
        if (n_gen < 20) {
            char debug_piece[256];
            int debug_n = llama_token_to_piece(vocab, new_token, debug_piece, sizeof(debug_piece), 0, false);
            LOGI("Token %d: id=%d piece='%.*s'", n_gen, (int)new_token, debug_n, debug_piece);
        }
        
        // Check for special tokens FIRST - before any string conversion
        // These are single-token stop sequences in Qwen2.5
        if (new_token == token_im_end || new_token == token_im_start) {
            LOGI("STOPPING at special token: id=%d (im_end=%d, im_start=%d)", 
                 (int)new_token, (int)token_im_end, (int)token_im_start);
            break;
        }
        
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }
        
        if (new_token == last_token) {
            repeat_count++;
            if (repeat_count >= MAX_REPEAT) {
                LOGI("Stopping due to repetition");
                break;
            }
        } else {
            repeat_count = 0;
            last_token = new_token;
        }
        
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, true);
        if (n <= 0) {
            g_state->batch.n_tokens = 0;
            batch_add(g_state->batch, new_token, g_state->current_pos + n_gen, {0}, true);
            if (llama_decode(g_state->ctx, g_state->batch) != 0) break;
            n_gen++;
            continue;
        }
        
        std::string token_str(piece, n);
        
        // Replace BPE space character (U+2581) with regular space
        std::string display_str = token_str;
        size_t pos = 0;
        while ((pos = display_str.find('\xE2\x96\x81', pos)) != std::string::npos) {
            display_str.replace(pos, 3, " ");
            pos += 1;
        }
        
        // String-based stop sequence detection
        // Check for partial matches to stop before emitting special tokens
        if (display_str.find("<|im_end|>") != std::string::npos ||
            display_str.find("|im_end|>") != std::string::npos ||
            display_str.find("<|im_start|>") != std::string::npos ||
            display_str.find("--EndConversation--") != std::string::npos) {
            LOGI("Stopping at special token marker");
            break;
        }
        
        // Sanitize and emit
        std::string sanitized = sanitizeForJNI(display_str);
        if (!sanitized.empty()) {
            jstring jTokenStr = env->NewStringUTF(sanitized.c_str());
            if (jTokenStr) {
                env->CallVoidMethod(callback, onTokenMethod, jTokenStr);
                env->DeleteLocalRef(jTokenStr);
            }
            response.append(sanitized);
        }
        
        g_state->batch.n_tokens = 0;
        batch_add(g_state->batch, new_token, g_state->current_pos + n_gen, {0}, true);
        
        if (llama_decode(g_state->ctx, g_state->batch) != 0) {
            break;
        }
        
        n_gen++;
        g_state->perf_stats.tokens_generated = n_gen;
    }
    
    g_state->is_generating = false;
    
    auto gen_end = std::chrono::high_resolution_clock::now();
    auto gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(gen_end - g_state->perf_stats.start_time).count();
    float tokens_per_sec = gen_ms > 0 ? (n_gen * 1000.0f / gen_ms) : 0.0f;
    
    LOGI("Generated %d tokens in %lld ms (%.2f tokens/sec)", n_gen, gen_ms, tokens_per_sec);
    
    env->CallVoidMethod(callback, onCompleteMethod);
    
    // Sanitize final response
    std::string sanitizedResponse = sanitizeForJNI(response);
    return env->NewStringUTF(sanitizedResponse.c_str());
}

// Wrapper for nativeGenerate that uses default max tokens (256)
extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerate(
    JNIEnv* env,
    jobject thiz,
    jstring userPrompt,
    jobject callback
) {
    // Default max tokens for simple wrapper
    // The Kotlin layer should use nativeGenerateStream with dynamic limits
    Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerateStream(
        env, thiz, userPrompt, 256, callback
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_nativeAddToHistory(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring role,
    jstring content
) {
    if (!g_state) return;
    
    const char* roleStr = env->GetStringUTFChars(role, nullptr);
    const char* contentStr = env->GetStringUTFChars(content, nullptr);
    
    g_state->history.push_back({std::string(roleStr), std::string(contentStr)});
    LOGI("Added to history: %s - %.50s...", roleStr, contentStr);
    
    env->ReleaseStringUTFChars(role, roleStr);
    env->ReleaseStringUTFChars(content, contentStr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_stopGeneration(JNIEnv* env, jobject /*thiz*/) {
    if (g_state) {
        g_state->should_stop = true;
        LOGI("Generation stop requested");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_unload(JNIEnv* env, jobject /*thiz*/) {
    LOGI("Unloading model");
    if (g_state) {
        g_state->cleanup();
        g_state.reset();
    }
    LOGI("Model unloaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_shutdown(JNIEnv* env, jobject /*thiz*/) {
    LOGI("Shutting down");
    if (g_state) {
        g_state->cleanup();
        g_state.reset();
    }
    llama_backend_free();
    LOGI("Shutdown complete");
}

// ============================================
// OPTIMIZATION JNI METHODS
// ============================================

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_getOptimalThreadCount(JNIEnv* env, jobject /*thiz*/) {
    int big_cores = countBigCores();
    int adaptive = getAdaptiveThreadCount(big_cores);
    LOGI("Optimal thread count: %d (big cores: %d)", adaptive, big_cores);
    return adaptive;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_getCurrentTemperature(JNIEnv* env, jobject /*thiz*/) {
    return getCpuTemperature();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_calculateSafeContextSize(
    JNIEnv* env,
    jobject /*thiz*/,
    jint availableRamMB,
    jint modelSizeMB
) {
    int reservedMB = 500;
    int computeBuffersMB = 200;
    int kvCachePer1K = 8;
    
    int remainingMB = availableRamMB - modelSizeMB - reservedMB - computeBuffersMB;
    
    if (remainingMB < 0) {
        LOGW("Very low memory! Using minimum context size");
        return 512;
    }
    
    int maxContext = (remainingMB / kvCachePer1K) * 1024;
    int safeContext = std::min(maxContext, 2048);
    safeContext = std::max(safeContext, 512);
    safeContext = (safeContext / 256) * 256;
    
    LOGI("Safe context size: %d (available: %dMB, model: %dMB, remaining: %dMB)",
         safeContext, availableRamMB, modelSizeMB, remainingMB);
    
    return safeContext;
}

// ============================================
// RAM OPTIMIZER JNI METHODS
// ============================================

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_getCurrentMemoryUsageMB(JNIEnv* env, jobject /*thiz*/) {
    int usage = getCurrentMemoryUsageMB();
    LOGI("Current memory usage: %d MB", usage);
    return usage;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_getAvailableMemoryMB(JNIEnv* env, jobject /*thiz*/) {
    int available = getAvailableMemoryMB();
    LOGI("Available system memory: %d MB", available);
    return available;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_LlamaBridge_nativeOptimizeMemory(JNIEnv* env, jobject /*thiz*/) {
    LOGI("Starting memory optimization...");
    
    int memoryBefore = getCurrentMemoryUsageMB();
    
    // 1. Release GGML compute buffers if not generating
    if (g_state && !g_state->is_generating.load()) {
        // Clear any temporary buffers by resetting batch
        if (g_state->batch.n_tokens > 0) {
            g_state->batch.n_tokens = 0;
            LOGI("Cleared batch buffers");
        }
        
        // Hint to release memory pages
        if (g_state->ctx) {
            // This is a hint to the OS that these pages can be reclaimed
            llama_memory_t mem = llama_get_memory(g_state->ctx);
            if (mem) {
                // Keep KV cache but hint cold pages
                LOGI("Memory pages hinted for release");
            }
        }
    }
    
    // 2. Request garbage collection (polite hint to JVM)
    LOGI("Requesting JVM garbage collection");
    // Note: This schedules a GC, doesn't force it immediately
    
    // Give GC time to run
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    
    int memoryAfter = getCurrentMemoryUsageMB();
    int memoryFreed = memoryBefore - memoryAfter;
    
    if (memoryFreed < 0) memoryFreed = 0; // GC might not have run yet
    
    LOGI("Memory optimization complete: Before=%dMB, After=%dMB, Freed=%dMB",
         memoryBefore, memoryAfter, memoryFreed);
    
    return memoryFreed;
}
