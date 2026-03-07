#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <vector>
#include <mutex>
#include <queue>
#include <atomic>

#define LOG_TAG "AudioJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace oboe;

// ============================================
// Audio Recorder using Oboe
// ============================================
class AudioRecorder : public AudioStreamDataCallback {
public:
    AudioRecorder() = default;
    ~AudioRecorder() {
        stop();
    }

    bool start(int sampleRate = 16000, int channelCount = 1) {
        std::lock_guard<std::mutex> lock(mutex_);
        
        if (stream_) {
            LOGI("Recorder already running");
            return true;
        }

        AudioStreamBuilder builder;
        builder.setDirection(Direction::Input)
            ->setPerformanceMode(PerformanceMode::LowLatency)
            ->setSharingMode(SharingMode::Exclusive)
            ->setFormat(AudioFormat::I16)
            ->setChannelCount(channelCount)
            ->setSampleRate(sampleRate)
            ->setDataCallback(this);

        Result result = builder.openStream(stream_);
        if (result != Result::OK) {
            LOGE("Failed to open recording stream: %d", static_cast<int>(result));
            return false;
        }

        result = stream_->requestStart();
        if (result != Result::OK) {
            LOGE("Failed to start recording: %d", static_cast<int>(result));
            stream_->close();
            stream_ = nullptr;
            return false;
        }

        LOGI("Audio recording started: %dHz, %d channels", sampleRate, channelCount);
        isRecording_ = true;
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_ = nullptr;
            LOGI("Audio recording stopped");
        }
        isRecording_ = false;
    }

    bool isRecording() const {
        return isRecording_;
    }

    // Get recorded audio data (called from Java)
    std::vector<int16_t> getAudioData(int maxSamples) {
        std::lock_guard<std::mutex> lock(mutex_);
        
        std::vector<int16_t> result;
        int samplesToRead = std::min(maxSamples, static_cast<int>(audioBuffer_.size()));
        
        result.reserve(samplesToRead);
        for (int i = 0; i < samplesToRead; i++) {
            result.push_back(audioBuffer_.front());
            audioBuffer_.pop();
        }
        
        return result;
    }

    int getAvailableSamples() {
        std::lock_guard<std::mutex> lock(mutex_);
        return static_cast<int>(audioBuffer_.size());
    }

    void clearBuffer() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::queue<int16_t> empty;
        std::swap(audioBuffer_, empty);
    }

private:
    DataCallbackResult onAudioReady(AudioStream* stream, void* data, int32_t numFrames) override {
        auto* audioData = static_cast<int16_t*>(data);
        int32_t numSamples = numFrames * stream->getChannelCount();
        
        std::lock_guard<std::mutex> lock(mutex_);
        for (int i = 0; i < numSamples; i++) {
            // Keep buffer size reasonable (max 10 seconds @ 16kHz)
            if (audioBuffer_.size() < 160000) {
                audioBuffer_.push(audioData[i]);
            }
        }
        
        return DataCallbackResult::Continue;
    }

    std::shared_ptr<AudioStream> stream_;
    std::queue<int16_t> audioBuffer_;
    std::mutex mutex_;
    std::atomic<bool> isRecording_{false};
};

// ============================================
// Audio Player using Oboe
// ============================================
class AudioPlayer : public AudioStreamDataCallback {
public:
    AudioPlayer() = default;
    ~AudioPlayer() {
        stop();
    }

    bool start(int sampleRate = 16000, int channelCount = 1) {
        std::lock_guard<std::mutex> lock(mutex_);
        
        if (stream_) {
            LOGI("Player already running");
            return true;
        }

        sampleRate_ = sampleRate;
        channelCount_ = channelCount;

        AudioStreamBuilder builder;
        builder.setDirection(Direction::Output)
            ->setPerformanceMode(PerformanceMode::LowLatency)
            ->setSharingMode(SharingMode::Exclusive)
            ->setFormat(AudioFormat::I16)
            ->setChannelCount(channelCount)
            ->setSampleRate(sampleRate)
            ->setDataCallback(this);

        Result result = builder.openStream(stream_);
        if (result != Result::OK) {
            LOGE("Failed to open playback stream: %d", static_cast<int>(result));
            return false;
        }

        result = stream_->requestStart();
        if (result != Result::OK) {
            LOGE("Failed to start playback: %d", static_cast<int>(result));
            stream_->close();
            stream_ = nullptr;
            return false;
        }

        LOGI("Audio playback started: %dHz, %d channels", sampleRate, channelCount);
        isPlaying_ = true;
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_ = nullptr;
            LOGI("Audio playback stopped");
        }
        isPlaying_ = false;
    }

    bool isPlaying() const {
        return isPlaying_;
    }

    // Queue audio data for playback (called from Java)
    void queueAudioData(const std::vector<int16_t>& data) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (int16_t sample : data) {
            playbackBuffer_.push(sample);
        }
    }

    void clearBuffer() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::queue<int16_t> empty;
        std::swap(playbackBuffer_, empty);
    }

private:
    DataCallbackResult onAudioReady(AudioStream* stream, void* data, int32_t numFrames) override {
        auto* audioData = static_cast<int16_t*>(data);
        int32_t numSamples = numFrames * channelCount_;
        
        std::lock_guard<std::mutex> lock(mutex_);
        
        for (int i = 0; i < numSamples; i++) {
            if (!playbackBuffer_.empty()) {
                audioData[i] = playbackBuffer_.front();
                playbackBuffer_.pop();
            } else {
                audioData[i] = 0; // Silence if no data
            }
        }
        
        return DataCallbackResult::Continue;
    }

    std::shared_ptr<AudioStream> stream_;
    std::queue<int16_t> playbackBuffer_;
    std::mutex mutex_;
    std::atomic<bool> isPlaying_{false};
    int sampleRate_ = 16000;
    int channelCount_ = 1;
};

// ============================================
// Global instances
// ============================================
static AudioRecorder* g_recorder = nullptr;
static AudioPlayer* g_player = nullptr;

// ============================================
// JNI Functions
// ============================================

extern "C" {

// Recording functions
JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeStartRecording(
    JNIEnv* env,
    jobject thiz,
    jint sampleRate,
    jint channelCount
) {
    if (!g_recorder) {
        g_recorder = new AudioRecorder();
    }
    return g_recorder->start(sampleRate, channelCount);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeStopRecording(
    JNIEnv* env,
    jobject thiz
) {
    if (g_recorder) {
        g_recorder->stop();
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeReadAudioData(
    JNIEnv* env,
    jobject thiz,
    jint maxSamples
) {
    if (!g_recorder) {
        return nullptr;
    }
    
    std::vector<int16_t> data = g_recorder->getAudioData(maxSamples);
    if (data.empty()) {
        return nullptr;
    }
    
    // Convert to byte array
    jbyteArray result = env->NewByteArray(data.size() * 2);
    env->SetByteArrayRegion(result, 0, data.size() * 2, 
                           reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

JNIEXPORT jint JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeGetAvailableSamples(
    JNIEnv* env,
    jobject thiz
) {
    if (!g_recorder) {
        return 0;
    }
    return g_recorder->getAvailableSamples();
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeClearRecordingBuffer(
    JNIEnv* env,
    jobject thiz
) {
    if (g_recorder) {
        g_recorder->clearBuffer();
    }
}

// Playback functions
JNIEXPORT jboolean JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeStartPlayback(
    JNIEnv* env,
    jobject thiz,
    jint sampleRate,
    jint channelCount
) {
    if (!g_player) {
        g_player = new AudioPlayer();
    }
    return g_player->start(sampleRate, channelCount);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeStopPlayback(
    JNIEnv* env,
    jobject thiz
) {
    if (g_player) {
        g_player->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeQueueAudioData(
    JNIEnv* env,
    jobject thiz,
    jbyteArray audioData
) {
    if (!g_player || !audioData) {
        return;
    }
    
    jsize len = env->GetArrayLength(audioData);
    std::vector<int16_t> data(len / 2);
    env->GetByteArrayRegion(audioData, 0, len, 
                           reinterpret_cast<jbyte*>(data.data()));
    
    g_player->queueAudioData(data);
}

JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeClearPlaybackBuffer(
    JNIEnv* env,
    jobject thiz
) {
    if (g_player) {
        g_player->clearBuffer();
    }
}

// Cleanup
JNIEXPORT void JNICALL
Java_com_projekt_1x_studybuddy_bridge_AudioBridge_nativeCleanup(
    JNIEnv* env,
    jobject thiz
) {
    delete g_recorder;
    g_recorder = nullptr;
    
    delete g_player;
    g_player = nullptr;
    
    LOGI("Audio JNI cleanup complete");
}

} // extern "C"
