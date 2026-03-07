#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

// Sherpa-ONNX C API headers
#include "sherpa-onnx/c-api/c-api.h"

#define LOG_TAG "SherpaTTS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global empty string for unused fields
static const char* kEmptyString = "";

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_SherpaTTSBridge_nativeCreateTts(
    JNIEnv* env, jobject thiz,
    jstring modelPath, jstring tokensPath, jstring lexiconPath,
    jstring dataDir, jint numThreads
) {
    LOGI("nativeCreateTts called");
    
    // Get C strings from Java
    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    const char* tokens = env->GetStringUTFChars(tokensPath, nullptr);
    const char* lexicon = lexiconPath ? env->GetStringUTFChars(lexiconPath, nullptr) : kEmptyString;
    const char* dataDirCstr = dataDir ? env->GetStringUTFChars(dataDir, nullptr) : kEmptyString;
    
    // Validate inputs
    if (!model || !tokens) {
        LOGE("Model or tokens path is null");
        if (model) env->ReleaseStringUTFChars(modelPath, model);
        if (tokens) env->ReleaseStringUTFChars(tokensPath, tokens);
        if (lexiconPath && lexicon != kEmptyString) env->ReleaseStringUTFChars(lexiconPath, lexicon);
        if (dataDir && dataDirCstr != kEmptyString) env->ReleaseStringUTFChars(dataDir, dataDirCstr);
        return 0;
    }
    
    LOGI("Loading TTS model:");
    LOGI("  Model: %s", model);
    LOGI("  Tokens: %s", tokens);
    LOGI("  Lexicon: %s", lexicon);
    LOGI("  DataDir: %s", dataDirCstr);
    
    // Allocate config on heap and zero it
    SherpaOnnxOfflineTtsConfig* config = 
        (SherpaOnnxOfflineTtsConfig*)calloc(1, sizeof(SherpaOnnxOfflineTtsConfig));
    
    if (!config) {
        LOGE("Failed to allocate config");
        env->ReleaseStringUTFChars(modelPath, model);
        env->ReleaseStringUTFChars(tokensPath, tokens);
        if (lexiconPath && lexicon != kEmptyString) env->ReleaseStringUTFChars(lexiconPath, lexicon);
        if (dataDir && dataDirCstr != kEmptyString) env->ReleaseStringUTFChars(dataDir, dataDirCstr);
        return 0;
    }
    
    // Configure VITS model (for Piper)
    config->model.vits.model = model;
    config->model.vits.lexicon = lexicon;
    config->model.vits.tokens = tokens;
    config->model.vits.data_dir = dataDirCstr;
    config->model.vits.noise_scale = 0.667f;
    config->model.vits.noise_scale_w = 0.8f;
    config->model.vits.length_scale = 1.0f;
    config->model.vits.dict_dir = kEmptyString;
    
    // Model config
    config->model.num_threads = numThreads > 0 ? numThreads : 2;
    config->model.debug = 0;
    config->model.provider = "cpu";
    
    // Other TTS settings
    config->rule_fsts = kEmptyString;
    config->max_num_sentences = 1;
    config->rule_fars = kEmptyString;
    config->silence_scale = 0.2f;
    
    // Create TTS
    LOGI("Creating Sherpa-ONNX TTS...");
    const SherpaOnnxOfflineTts* tts = SherpaOnnxCreateOfflineTts(config);
    
    // Free config struct
    free(config);
    
    // Release Java strings
    env->ReleaseStringUTFChars(modelPath, model);
    env->ReleaseStringUTFChars(tokensPath, tokens);
    if (lexiconPath && lexicon != kEmptyString) env->ReleaseStringUTFChars(lexiconPath, lexicon);
    if (dataDir && dataDirCstr != kEmptyString) env->ReleaseStringUTFChars(dataDir, dataDirCstr);
    
    if (!tts) {
        LOGE("Failed to create TTS");
        return 0;
    }
    
    LOGI("TTS created: %p", tts);
    return reinterpret_cast<jlong>(tts);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_SherpaTTSBridge_nativeDestroyTts(
    JNIEnv* env, jobject thiz, jlong ptr
) {
    LOGI("nativeDestroyTts called");
    
    if (ptr == 0) return;
    
    const SherpaOnnxOfflineTts* tts = reinterpret_cast<const SherpaOnnxOfflineTts*>(ptr);
    SherpaOnnxDestroyOfflineTts(tts);
    LOGI("TTS destroyed");
}

JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_bridge_SherpaTTSBridge_nativeGetSampleRate(
    JNIEnv* env, jobject thiz, jlong ptr
) {
    if (ptr == 0) return 22050;
    
    const SherpaOnnxOfflineTts* tts = reinterpret_cast<const SherpaOnnxOfflineTts*>(ptr);
    return SherpaOnnxOfflineTtsSampleRate(tts);
}

JNIEXPORT jshortArray JNICALL
Java_com_projekt_1x_studybuddy_bridge_SherpaTTSBridge_nativeGenerateSpeech(
    JNIEnv* env, jobject thiz, jlong ptr, jstring text, jint sid, jfloat speed
) {
    if (ptr == 0) {
        LOGE("Invalid TTS pointer");
        return nullptr;
    }
    
    const SherpaOnnxOfflineTts* tts = reinterpret_cast<const SherpaOnnxOfflineTts*>(ptr);
    
    const char* textStr = env->GetStringUTFChars(text, nullptr);
    if (!textStr) {
        LOGE("Failed to get text");
        return nullptr;
    }
    
    LOGI("Generating speech for: %s", textStr);
    
    // Generate speech
    const SherpaOnnxGeneratedAudio* audio = SherpaOnnxOfflineTtsGenerate(tts, textStr, sid, speed);
    
    // Release text string immediately after use
    env->ReleaseStringUTFChars(text, textStr);
    
    if (!audio) {
        LOGE("Failed to generate speech");
        return nullptr;
    }
    
    // Get audio data
    int32_t numSamples = audio->n;
    const float* samples = audio->samples;
    
    LOGI("Generated %d samples", numSamples);
    
    if (numSamples <= 0 || !samples) {
        LOGE("No audio generated");
        return nullptr;
    }
    
    // Convert float [-1, 1] to short [-32768, 32767]
    jshortArray result = env->NewShortArray(numSamples);
    if (result) {
        std::vector<jshort> shortSamples(numSamples);
        for (int i = 0; i < numSamples; i++) {
            float sample = samples[i];
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            shortSamples[i] = static_cast<jshort>(sample * 32767.0f);
        }
        env->SetShortArrayRegion(result, 0, numSamples, shortSamples.data());
    }
    
    return result;
}

} // extern "C"
