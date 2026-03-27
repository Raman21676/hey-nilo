#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "BridgeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations from ai_chat_jni.cpp
extern "C" {
    // These are the actual implementations in ai_chat_jni.cpp
    JNIEXPORT void JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_init(JNIEnv* env, jobject thiz, jstring nativeLibDir);
    JNIEXPORT jint JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jint nThreads, jint nCtx, jint nBatch, jboolean useMmap, jfloat memoryPressure, jboolean isCharging);
    JNIEXPORT void JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerateStream(JNIEnv* env, jobject thiz, jstring prompt, jint maxTokens, jobject callback);
    JNIEXPORT void JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_stopGeneration(JNIEnv* env, jobject thiz);
    JNIEXPORT void JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_unload(JNIEnv* env, jobject thiz);
    JNIEXPORT jint JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_prepareContext(JNIEnv* env, jobject thiz);
    JNIEXPORT jint JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_getOptimalThreadCount(JNIEnv* env, jobject thiz);
    JNIEXPORT jfloat JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_getCurrentTemperature(JNIEnv* env, jobject thiz);
    JNIEXPORT jlong JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_nativeOptimizeMemory(JNIEnv* env, jobject thiz);
    JNIEXPORT void JNICALL Java_com_projekt_1x_studybuddy_LlamaBridge_nativeClearContext(JNIEnv* env, jobject thiz);
}

// Bridge package JNI wrappers - these call the original implementations

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeInit(JNIEnv* env, jobject thiz, jstring nativeLibDir) {
    LOGI("Bridge nativeInit called");
    Java_com_projekt_1x_studybuddy_LlamaBridge_init(env, thiz, nativeLibDir);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeLoadModel(JNIEnv* env, jobject thiz, jstring path, jint threads, jint contextSize, jint batchSize, jboolean useMmap, jfloat memoryPressure) {
    LOGI("Bridge nativeLoadModel called: %s", path ? env->GetStringUTFChars(path, nullptr) : "null");
    // Original JNI expects isCharging parameter - pass true (charging) for now
    jint result = Java_com_projekt_1x_studybuddy_LlamaBridge_loadModel(env, thiz, path, threads, contextSize, batchSize, useMmap, memoryPressure, JNI_TRUE);
    if (result == 0) {
        // Model loaded, now prepare context
        LOGI("Model loaded, preparing context...");
        jint ctxResult = Java_com_projekt_1x_studybuddy_LlamaBridge_prepareContext(env, thiz);
        LOGI("prepareContext returned: %d", ctxResult);
        return ctxResult == 0 ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeRelease(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeRelease called");
    Java_com_projekt_1x_studybuddy_LlamaBridge_unload(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGenerate(JNIEnv* env, jobject thiz, jstring prompt, jobject callback) {
    LOGI("Bridge nativeGenerate called");
    // Use default maxTokens = 256
    Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerateStream(env, thiz, prompt, 256, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGenerateStream(JNIEnv* env, jobject thiz, jstring prompt, jint maxTokens, jobject callback) {
    LOGI("Bridge nativeGenerateStream called with maxTokens=%d", maxTokens);
    Java_com_projekt_1x_studybuddy_LlamaBridge_nativeGenerateStream(env, thiz, prompt, maxTokens, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeStopGeneration(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeStopGeneration called");
    Java_com_projekt_1x_studybuddy_LlamaBridge_stopGeneration(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeUnloadModel(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeUnloadModel called");
    Java_com_projekt_1x_studybuddy_LlamaBridge_unload(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeSetSystemPrompt(JNIEnv* env, jobject thiz, jstring prompt) {
    LOGI("Bridge nativeSetSystemPrompt called");
    // TODO: Implement or call existing if available
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeClearContext(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeClearContext calling actual implementation");
    // Call the real implementation in ai_chat_jni.cpp
    Java_com_projekt_1x_studybuddy_LlamaBridge_nativeClearContext(env, thiz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGetContextSize(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeGetContextSize called");
    // TODO: Return actual context size
    return 1024;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGetOptimalThreadCount(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeGetOptimalThreadCount called");
    return Java_com_projekt_1x_studybuddy_LlamaBridge_getOptimalThreadCount(env, thiz);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGetCurrentTemperature(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeGetCurrentTemperature called");
    return Java_com_projekt_1x_studybuddy_LlamaBridge_getCurrentTemperature(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeIsModelLoaded(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeIsModelLoaded called");
    // TODO: Check if model is actually loaded in native state
    // For now, return true if we get here (library is loaded)
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeGetMemoryUsage(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeGetMemoryUsage called");
    // Return dummy value for now (150MB in bytes)
    return (jlong)150 * 1024 * 1024;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_LlamaBridge_nativeOptimizeMemory(JNIEnv* env, jobject thiz) {
    LOGI("Bridge nativeOptimizeMemory called");
    return Java_com_projekt_1x_studybuddy_LlamaBridge_nativeOptimizeMemory(env, thiz);
}

// VAD Bridge - implemented in vad_jni.cpp

// STT Bridge stubs
extern "C" JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_STTBridge_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("STT nativeInit stub called");
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_STTBridge_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("STT nativeRelease stub called");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_STTBridge_nativeLoadModel(JNIEnv* env, jobject thiz, jlong handle, jstring path) {
    LOGI("STT nativeLoadModel stub called");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projekt_1x_studybuddy_bridge_STTBridge_nativeTranscribe(JNIEnv* env, jobject thiz, jlong handle, jshortArray audioData) {
    LOGI("STT nativeTranscribe stub called");
    return env->NewStringUTF("Transcription stub");
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_STTBridge_nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("STT nativeReset stub called");
}

// TTS Bridge stubs
extern "C" JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_TTSBridge_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("TTS nativeInit stub called");
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_TTSBridge_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("TTS nativeRelease stub called");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_TTSBridge_nativeLoadModel(JNIEnv* env, jobject thiz, jlong handle, jstring path) {
    LOGI("TTS nativeLoadModel stub called");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_projekt_1x_studybuddy_bridge_TTSBridge_nativeSynthesize(JNIEnv* env, jobject thiz, jlong handle, jstring text, jstring voiceStyle, jfloat speed) {
    LOGI("TTS nativeSynthesize stub called");
    // Return dummy audio (silent)
    jbyteArray result = env->NewByteArray(48000); // 1 second of 24kHz 16-bit mono
    jbyte* bytes = env->GetByteArrayElements(result, nullptr);
    memset(bytes, 0, 48000);
    env->ReleaseByteArrayElements(result, bytes, 0);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_TTSBridge_nativeStopSynthesis(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("TTS nativeStopSynthesis stub called");
}

