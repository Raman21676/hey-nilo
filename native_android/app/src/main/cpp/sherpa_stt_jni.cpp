#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

// Sherpa-ONNX C API headers
#include "sherpa-onnx/c-api/c-api.h"

#define LOG_TAG "SherpaSTT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeCreateMoonshineRecognizer(
    JNIEnv* env, jobject thiz,
    jstring preprocessPath, jstring encoderPath, jstring uncachedPath, 
    jstring cachedPath, jstring tokensPath, jint sampleRate
) {
    LOGI("nativeCreateMoonshineRecognizer called");
    
    // Get C strings from Java
    const char* preprocess = env->GetStringUTFChars(preprocessPath, nullptr);
    const char* encoder = env->GetStringUTFChars(encoderPath, nullptr);
    const char* uncached = env->GetStringUTFChars(uncachedPath, nullptr);
    const char* cached = env->GetStringUTFChars(cachedPath, nullptr);
    const char* tokens = env->GetStringUTFChars(tokensPath, nullptr);
    
    // Validate inputs
    if (!preprocess || !encoder || !uncached || !cached || !tokens) {
        LOGE("Failed to get one or more paths from Java");
        if (preprocess) env->ReleaseStringUTFChars(preprocessPath, preprocess);
        if (encoder) env->ReleaseStringUTFChars(encoderPath, encoder);
        if (uncached) env->ReleaseStringUTFChars(uncachedPath, uncached);
        if (cached) env->ReleaseStringUTFChars(cachedPath, cached);
        if (tokens) env->ReleaseStringUTFChars(tokensPath, tokens);
        return 0;
    }
    
    LOGI("Loading Moonshine model:");
    LOGI("  Preprocess: %s", preprocess);
    LOGI("  Encoder: %s", encoder);
    LOGI("  Uncached: %s", uncached);
    LOGI("  Cached: %s", cached);
    LOGI("  Tokens: %s", tokens);
    
    // Allocate config on heap and zero it
    SherpaOnnxOfflineRecognizerConfig* config = 
        (SherpaOnnxOfflineRecognizerConfig*)calloc(1, sizeof(SherpaOnnxOfflineRecognizerConfig));
    
    if (!config) {
        LOGE("Failed to allocate config");
        env->ReleaseStringUTFChars(preprocessPath, preprocess);
        env->ReleaseStringUTFChars(encoderPath, encoder);
        env->ReleaseStringUTFChars(uncachedPath, uncached);
        env->ReleaseStringUTFChars(cachedPath, cached);
        env->ReleaseStringUTFChars(tokensPath, tokens);
        return 0;
    }
    
    // Feature config
    config->feat_config.sample_rate = sampleRate;
    config->feat_config.feature_dim = 80;
    
    // Model config - Set EVERY char* field explicitly to "" to avoid NULL
    // Transducer (not used)
    config->model_config.transducer.encoder = "";
    config->model_config.transducer.decoder = "";
    config->model_config.transducer.joiner = "";
    
    // Paraformer (not used) - PROFESSOR: these were missing!
    config->model_config.paraformer.model = "";
    
    // Nemo CTC (not used) - PROFESSOR: this was missing!
    config->model_config.nemo_ctc.model = "";
    
    // Whisper (not used)
    config->model_config.whisper.encoder = "";
    config->model_config.whisper.decoder = "";
    config->model_config.whisper.language = "";
    config->model_config.whisper.task = "";
    config->model_config.whisper.tail_paddings = -1;
    
    // Tdnn (not used)
    config->model_config.tdnn.model = "";
    
    // Main fields
    config->model_config.tokens = tokens;
    config->model_config.num_threads = 2;
    config->model_config.debug = 0;
    config->model_config.provider = "cpu";
    config->model_config.model_type = "moonshine";
    config->model_config.modeling_unit = "";  // PROFESSOR: was "cjkchar", should be "" for English
    config->model_config.bpe_vocab = "";
    config->model_config.telespeech_ctc = "";
    
    // SenseVoice (not used) - PROFESSOR: these were missing!
    config->model_config.sense_voice.model = "";
    config->model_config.sense_voice.language = "";
    config->model_config.sense_voice.use_itn = 0;
    
    // Moonshine (the actual model we use)
    config->model_config.moonshine.preprocessor = preprocess;
    config->model_config.moonshine.encoder = encoder;
    config->model_config.moonshine.uncached_decoder = uncached;
    config->model_config.moonshine.cached_decoder = cached;
    
    // LM config
    config->lm_config.model = "";
    config->lm_config.scale = 1.0f;
    
    // Decoder config
    config->decoding_method = "greedy_search";
    config->max_active_paths = 4;
    config->hotwords_file = "";
    config->hotwords_score = 1.5f;
    config->rule_fsts = "";
    config->rule_fars = "";
    config->blank_penalty = 0.0f;
    
    // Create recognizer
    LOGI("Creating Sherpa-ONNX Moonshine recognizer...");
    const SherpaOnnxOfflineRecognizer* recognizer = SherpaOnnxCreateOfflineRecognizer(config);
    
    // Free the config struct
    free(config);
    
    // Release Java strings
    env->ReleaseStringUTFChars(preprocessPath, preprocess);
    env->ReleaseStringUTFChars(encoderPath, encoder);
    env->ReleaseStringUTFChars(uncachedPath, uncached);
    env->ReleaseStringUTFChars(cachedPath, cached);
    env->ReleaseStringUTFChars(tokensPath, tokens);
    
    if (!recognizer) {
        LOGE("Failed to create recognizer");
        return 0;
    }
    
    LOGI("Recognizer created: %p", recognizer);
    return reinterpret_cast<jlong>(recognizer);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeDestroyRecognizer(
    JNIEnv* env, jobject thiz, jlong ptr
) {
    LOGI("nativeDestroyRecognizer called");
    
    if (ptr == 0) return;
    
    const SherpaOnnxOfflineRecognizer* recognizer = 
        reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(ptr);
    SherpaOnnxDestroyOfflineRecognizer(recognizer);
    LOGI("Recognizer destroyed");
}

JNIEXPORT jstring JNICALL
Java_com_projekt_1x_studybuddy_bridge_RealSTTBridge_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong ptr, jshortArray audioData, jint sampleRate
) {
    if (ptr == 0) {
        LOGE("Invalid recognizer pointer");
        return env->NewStringUTF("");
    }
    
    const SherpaOnnxOfflineRecognizer* recognizer = 
        reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(ptr);
    
    jsize audioLength = env->GetArrayLength(audioData);
    if (audioLength == 0) {
        LOGW("Empty audio data");
        return env->NewStringUTF("");
    }
    
    jshort* audioBytes = env->GetShortArrayElements(audioData, nullptr);
    if (!audioBytes) {
        LOGE("Failed to get audio data");
        return env->NewStringUTF("");
    }
    
    // Convert short to float and normalize to [-1, 1]
    std::vector<float> samples(audioLength);
    for (int i = 0; i < audioLength; i++) {
        samples[i] = audioBytes[i] / 32768.0f;
    }
    
    env->ReleaseShortArrayElements(audioData, audioBytes, JNI_ABORT);
    
    LOGI("Transcribing %d samples at %d Hz", (int)samples.size(), sampleRate);
    
    // Create stream
    const SherpaOnnxOfflineStream* stream = SherpaOnnxCreateOfflineStream(recognizer);
    if (!stream) {
        LOGE("Failed to create stream");
        return env->NewStringUTF("");
    }
    
    // Accept waveform
    SherpaOnnxAcceptWaveformOffline(stream, sampleRate, samples.data(), samples.size());
    
    // Decode
    SherpaOnnxDecodeOfflineStream(recognizer, stream);
    
    // Get result
    const SherpaOnnxOfflineRecognizerResult* result = SherpaOnnxGetOfflineStreamResult(stream);
    
    jstring resultStr;
    if (result && result->text) {
        resultStr = env->NewStringUTF(result->text);
        LOGI("Transcription: %s", result->text);
    } else {
        resultStr = env->NewStringUTF("");
        LOGW("No transcription result");
    }
    
    // Clean up
    SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);
    
    return resultStr;
}

} // extern "C"
