#include <jni.h>
#include <android/log.h>
#include <onnxruntime_cxx_api.h>
#include <vector>
#include <memory>
#include <cmath>
#include <fstream>
#include <cstring>

#define LOG_TAG "VAD_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Silero VAD configuration
static const int SAMPLE_RATE = 16000;
static const int FRAME_SAMPLES = 512;  // 32ms at 16kHz
static const float THRESHOLD = 0.5f;

// VAD context structure
struct VADContext {
    std::shared_ptr<Ort::Session> session;
    std::shared_ptr<Ort::Env> env;
    std::shared_ptr<Ort::MemoryInfo> memoryInfo;
    
    // State tensors for LSTM
    float state_h[128];  // Hidden state
    float state_c[128];  // Cell state
    
    bool isLoaded = false;
    int sampleRate = SAMPLE_RATE;
    
    VADContext() {
        memset(state_h, 0, sizeof(state_h));
        memset(state_c, 0, sizeof(state_c));
    }
};

// Global ONNX Runtime environment
static std::unique_ptr<Ort::Env> g_onnxEnv = nullptr;
static int g_instanceCount = 0;

static bool initONNXEnv() {
    if (g_onnxEnv == nullptr) {
        try {
            OrtLoggingLevel logLevel = ORT_LOGGING_LEVEL_WARNING;
            g_onnxEnv = std::make_unique<Ort::Env>(logLevel, "VAD_ONNX");
            LOGI("ONNX Runtime environment initialized");
        } catch (const Ort::Exception& e) {
            LOGE("Failed to initialize ONNX environment: %s", e.what());
            return false;
        }
    }
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_VADBridge_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("nativeInit called");
    
    if (!initONNXEnv()) {
        LOGE("Failed to initialize ONNX environment");
        return 0;
    }
    
    try {
        VADContext* context = new VADContext();
        g_instanceCount++;
        LOGI("VAD context created, instance count: %d", g_instanceCount);
        return reinterpret_cast<jlong>(context);
    } catch (const std::exception& e) {
        LOGE("Failed to create VAD context: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_VADBridge_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("nativeRelease called");
    
    if (handle == 0) return;
    
    VADContext* context = reinterpret_cast<VADContext*>(handle);
    delete context;
    g_instanceCount--;
    
    LOGI("VAD context released, instance count: %d", g_instanceCount);
}

JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_VADBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jlong handle, jstring modelPath
) {
    LOGI("nativeLoadModel called");
    
    if (handle == 0) return JNI_FALSE;
    if (!g_onnxEnv) return JNI_FALSE;
    
    VADContext* context = reinterpret_cast<VADContext*>(handle);
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return JNI_FALSE;
    
    LOGI("Loading model from: %s", path);
    
    try {
        Ort::SessionOptions sessionOptions;
        sessionOptions.SetIntraOpNumThreads(1);
        sessionOptions.SetInterOpNumThreads(1);
        sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        sessionOptions.DisableMemPattern();
        sessionOptions.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        sessionOptions.SetLogSeverityLevel(ORT_LOGGING_LEVEL_WARNING);
        
        LOGI("Creating ONNX session...");
        
        context->session = std::make_shared<Ort::Session>(*g_onnxEnv, path, sessionOptions);
        context->memoryInfo = std::make_shared<Ort::MemoryInfo>(Ort::MemoryInfo::CreateCpu(
            OrtAllocatorType::OrtArenaAllocator, OrtMemType::OrtMemTypeDefault));
        
        LOGI("Model loaded successfully");
        context->isLoaded = true;
        
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_TRUE;
        
    } catch (const Ort::Exception& e) {
        LOGE("ONNX exception loading model: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
}

JNIEXPORT jfloat JNICALL
Java_com_projekt_1x_studybuddy_bridge_VADBridge_nativeDetectSpeech(
    JNIEnv* env, jobject thiz, jlong handle, jshortArray audioData
) {
    if (handle == 0) return -1.0f;
    
    VADContext* context = reinterpret_cast<VADContext*>(handle);
    
    if (!context->isLoaded || !context->session) {
        LOGE("Model not loaded");
        return -1.0f;
    }
    
    jsize audioLength = env->GetArrayLength(audioData);
    jshort* audioBytes = env->GetShortArrayElements(audioData, nullptr);
    
    if (!audioBytes || audioLength == 0) {
        LOGE("Invalid audio data");
        return -1.0f;
    }
    
    if (audioLength != FRAME_SAMPLES) {
        LOGE("Audio length %d != expected %d samples", audioLength, FRAME_SAMPLES);
        env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
        return -1.0f;
    }
    
    try {
        // Convert audio to float
        float inputData[FRAME_SAMPLES];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            inputData[i] = audioBytes[i] / 32768.0f;
        }
        
        env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
        
        // Create input tensor {1, 512}
        int64_t inputShape[] = {1, FRAME_SAMPLES};
        Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
            *context->memoryInfo, inputData, FRAME_SAMPLES, inputShape, 2);
        
        // Create state tensor {2, 1, 128} = 256 elements
        // h = first 128, c = second 128
        float stateData[256];
        memcpy(stateData, context->state_h, 128 * sizeof(float));
        memcpy(stateData + 128, context->state_c, 128 * sizeof(float));
        int64_t stateShape[] = {2, 1, 128};
        Ort::Value stateTensor = Ort::Value::CreateTensor<float>(
            *context->memoryInfo, stateData, 256, stateShape, 3);
        
        // Create sample rate tensor (int64 scalar)
        int64_t srData = context->sampleRate;
        Ort::Value srTensor = Ort::Value::CreateTensor<int64_t>(
            *context->memoryInfo, &srData, 1, nullptr, 0);
        
        // Set up inputs
        const char* inputNames[] = {"input", "state", "sr"};
        Ort::Value inputs[] = {
            std::move(inputTensor),
            std::move(stateTensor),
            std::move(srTensor)
        };
        
        // Run inference
        const char* outputNames[] = {"output", "stateN"};
        auto outputs = context->session->Run(
            Ort::RunOptions{nullptr},
            inputNames,
            inputs,
            3,
            outputNames,
            2
        );
        
        // Get speech probability
        float speechProb = outputs[0].GetTensorMutableData<float>()[0];
        
        // Update state
        float* newState = outputs[1].GetTensorMutableData<float>();
        memcpy(context->state_h, newState, 128 * sizeof(float));
        memcpy(context->state_c, newState + 128, 128 * sizeof(float));
        
        return speechProb;
        
    } catch (const Ort::Exception& e) {
        LOGE("ONNX exception during inference: %s", e.what());
        env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
        return -1.0f;
    } catch (const std::exception& e) {
        LOGE("Standard exception during inference: %s", e.what());
        env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
        return -1.0f;
    }
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_VADBridge_nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("nativeReset called");
    
    if (handle == 0) return;
    
    VADContext* context = reinterpret_cast<VADContext*>(handle);
    memset(context->state_h, 0, sizeof(context->state_h));
    memset(context->state_c, 0, sizeof(context->state_c));
    
    LOGI("VAD state reset");
}

} // extern "C"
