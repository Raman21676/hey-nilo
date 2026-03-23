# Hey-Nilo Project Session Summary

**Date:** March 23, 2026  
**Session Duration:** Full day of development  
**Status:** Multiple fixes applied, voice mode needs further tuning

---

## 🎯 PROJECT OVERVIEW

**Hey-Nilo** is an offline AI Voice Assistant for Android using:
- **LLM:** Qwen2.5 0.5B (Q4_0 quantized, 428MB GGUF)
- **STT:** Whisper.cpp (base.en model, 148MB)
- **VAD:** Silero VAD (2.3MB)
- **TTS:** Android System TextToSpeech (Kokoro TTS)
- **Architecture:** Jetpack Compose + Kotlin + llama.cpp (JNI)
- **Target Device:** Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)

---

## ✅ COMPLETED TODAY

### 1. **History Trimming Fix** (CRITICAL)
**Problem:** Conversation history growing to 487 tokens causing 3+ minute prefill time.

**Root Cause:** `nativeAddToHistory` in C++ was adding assistant responses without trimming.

**Fix Applied:**
- Added trimming logic to `nativeAddToHistory()` in `ai_chat_jni.cpp`
- History now limited to 6 messages max (3 exchanges)
- File: `native_android/app/src/main/cpp/ai_chat_jni.cpp`

```cpp
// Added to nativeAddToHistory:
const size_t MAX_HISTORY_PAIRS = 3;
const size_t MAX_HISTORY_SIZE = MAX_HISTORY_PAIRS * 2;
while (g_state->history.size() > MAX_HISTORY_SIZE) {
    g_state->history.erase(g_state->history.begin());
}
```

---

### 2. **Auto-Scroll Fix**
**Problem:** Chat wasn't auto-scrolling to bottom during streaming generation.

**Fix Applied:**
- Fixed `MainActivity.kt` auto-scroll logic
- Used `snapshotFlow` and `derivedStateOf` for reactive scrolling
- Added aggressive 100ms interval scrolling during streaming
- File: `native_android/app/src/main/java/com/projekt_x/studybuddy/MainActivity.kt`

---

### 3. **Token Filtering for Chat Display**
**Problem:** Special tokens like `<|im_end|>`, `<|im_start|>` appearing in chat UI.

**Fix Applied:**
- Added aggressive filtering in `MainActivity.kt`
- Filters: `<|im_end|>`, `<|im_start|>`, `|im_end|>`, `assistant`, `system`, `user`
- File: `MainActivity.kt` - `filterStreamingToken()` and `filterAiResponse()`

---

### 4. **TTS Token Filtering**
**Problem:** TTS was speaking special tokens like "less than pipe im end pipe greater than".

**Fix Applied:**
- Added `filterTTSText()` function in `VoicePipelineManager.kt`
- Applied filtering at all TTS entry points
- Prevents speaking: `<|im_end|>`, `<|im_start|>`, `assistant`, `system`, `user`
- File: `VoicePipelineManager.kt`

---

### 5. **Voice Mode Manual Stop Button**
**Problem:** Voice mode listening too long (10-20 seconds) after user finished speaking.

**Fix Applied:**
- Green voice orb now tappable to manually stop listening
- Added "Tap to stop" hint text below orb
- `forceStopAndProcess()` combines pre-speech and speech buffers
- File: `MainActivity.kt` - `VoiceModeOverlay`

---

### 6. **Removed Low Memory Warning**
**Problem:** Red "Low memory" warning at bottom of chat UI.

**Fix Applied:**
- Removed the warning text from chat UI
- File: `MainActivity.kt`

---

### 7. **Model Copy Script**
**Created:** Model files are stored at:
- **MacBook:** `/Users/kalikali/Desktop/heynilo_models/qwen2.5-0.5b-instruct-q4_0.gguf` (409MB)
- **Device:** `/sdcard/Android/data/com.projekt_x.studybuddy/files/models/`

---

## ❌ PENDING ISSUES (For Tomorrow)

### **CRITICAL: Voice Mode VAD Tuning**
**Status:** NOT WORKING RELIABLY

**Current Behavior:**
1. User taps voice orb → "Nilo is listening..."
2. User says "Who are you?"
3. One of these happens:
   - VAD detects too fast (within 1 second) → processes incomplete audio → "[BLANK_AUDIO]"
   - VAD never detects (probability 0.000-0.005) → timeout after 15 seconds
   - Manual tap works but doesn't trigger LLM (buffer issues)

**Current VAD Settings:**
```kotlin
private const val VAD_THRESHOLD = 0.15f
private const val MIN_SPEECH_MS = 800L
private const val MIN_SILENCE_MS = 600L
private const val TRAILING_SILENCE_FRAMES = 18
```

**Previous Attempts:**
- Tried 0.05 threshold → STT distortion (3x audio gain caused clipping)
- Tried 0.20 threshold → worked before but now doesn't
- Tried 0.25 threshold → too strict, never detects
- Tried 0.15 threshold → current, still not detecting reliably

**Audio Level Observations:**
- Samsung Tab A7 Lite microphone is very quiet
- Normal speech produces audio level 0.001-0.058 (should be 0.1-0.5)
- VAD probability stays at 0.000-0.030 (threshold is 0.15)

**What Needs Investigation:**
1. **Hardware issue?** Microphone gain on Samsung Tab A7 Lite
2. **Audio source?** Currently using `VOICE_RECOGNITION`, try `MIC`?
3. **Pre-speech buffer?** Check if audio is actually being captured
4. **VAD model?** Verify Silero VAD is loading correctly
5. **Audio effects?** Check if AcousticEchoCanceler/NoiseSuppressor are suppressing voice

**Test Commands for Next Session:**
```bash
# Check audio levels
adb logcat -d | grep -i "audio level"

# Check VAD probability
adb logcat -d | grep -i "vad prob"

# Check force stop
adb logcat -d | grep -i "forceStop"
```

---

### **MEDIUM: Auto-Scroll Verification**
**Status:** CODE FIXED, NEEDS USER TESTING

- Auto-scroll code updated in `MainActivity.kt`
- Uses `snapshotFlow` and `derivedStateOf`
- User should verify it scrolls during streaming

---

### **LOW: TTS "assistant" Word Filter**
**Status:** PARTIALLY FIXED

- Previous fix removed "assistant" from normal text like "AI assistant"
- Fixed regex to require newline after role marker
- User needs to verify TTS says "assistant" correctly now

---

## 📁 KEY FILES MODIFIED TODAY

### C++ Layer:
1. `native_android/app/src/main/cpp/ai_chat_jni.cpp`
   - Added history trimming to `nativeAddToHistory()`

### Kotlin Layer:
2. `native_android/app/src/main/java/com/projekt_x/studybuddy/MainActivity.kt`
   - Auto-scroll fixes
   - Token filtering for chat
   - Voice mode UI (tap to stop)
   - Removed low memory warning

3. `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/VoicePipelineManager.kt`
   - TTS token filtering
   - `forceStopAndProcess()` implementation
   - VAD threshold tuning (multiple attempts)

4. `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/SimpleAudioRecorder.kt`
   - Briefly added 3x audio gain (caused distortion, reverted)
   - Briefly added 1.5x audio gain (reverted)

---

## 🔧 NEXT SESSION PRIORITIES

### Priority 1: Fix Voice Mode VAD
**Options to Try:**
1. **Hardware AGC:** Verify `AudioSource.VOICE_RECOGNITION` is providing gain
2. **Disable audio effects:** Try disabling AcousticEchoCanceler/NoiseSuppressor
3. **Manual gain:** Try gentle 1.2x or 1.5x gain with proper clamping
4. **Debug audio capture:** Add logging to verify audio data is being received
5. **Test with louder speech:** User should speak very loudly close to mic
6. **Try AudioSource.MIC:** Instead of VOICE_RECOGNITION

### Priority 2: Verify Auto-Scroll
- Start chat with model
- Ask a question that generates long response
- Verify it auto-scrolls to bottom

### Priority 3: Verify TTS "assistant"
- Ask "Who are you?"
- Verify TTS says "I am Nilo, your personal AI assistant" (not "I am Nilo, your personal AI")

---

## 📊 DEVICE INFO

**Samsung Galaxy Tab A7 Lite:**
- RAM: 3GB
- Android: 14
- Microphone: Very quiet (requires close speaking, loud voice)
- Audio levels: 0.001-0.058 (normal speech should be 0.1-0.5)

---

## 💾 MODEL FILES LOCATION

**On MacBook:**
```
/Users/kalikali/Desktop/heynilo_models/qwen2.5-0.5b-instruct-q4_0.gguf (409MB)
```

**On Device:**
```
/sdcard/Android/data/com.projekt_x.studybuddy/files/models/
├── qwen2.5-0.5b-instruct-q4_0.gguf (LLM)
├── whisper/ggml-base.en.bin (STT)
└── vad/silero_vad.onnx (VAD)
```

---

## 📝 GITHUB COMMITS TODAY

1. `75912ee` - Fix: History trimming, auto-scroll, and token filtering
2. `5dba064` - Remove low memory warning from chat UI
3. `fd4c772` - Fix: Revert audio gain that caused STT distortion
4. `a1bb028` - Fix: TTS regex was matching ' assistant' token
5. `56581db` - Fix: TTS regex was matching ' assistant' token, now requires newline
6. `34081c8` - Add 'Tap to stop' hint for voice mode manual control
7. `825d35e` - Fix: forceStopAndProcess combines pre-speech and speech buffers for STT
8. `075bcea` - Fix: forceStopAndProcess combines pre-speech and speech buffers for STT
9. `7cb709d` - Tune VAD: increase min speech to 1.5s, silence to 1.2s, threshold to 0.25
10. `f4c1fcd` - Fix VAD: lower threshold to 0.15, reduce min speech to 800ms for quiet mic

---

## 🔗 QUICK REFERENCE

### Build Commands:
```bash
cd /Users/kalikali/Desktop/hey-nilo/native_android
./gradlew :app:assembleDebug
```

### Install & Run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.projekt_x.studybuddy/.MainActivity
```

### Copy Model After Fresh Install:
```bash
adb push ~/Desktop/heynilo_models/qwen2.5-0.5b-instruct-q4_0.gguf /sdcard/Android/data/com.projekt_x.studybuddy/files/models/
```

### Check Logs:
```bash
adb logcat -s "VoicePipelineManager" "VADBridge" -v time
```

---

## 🎯 USER'S MAIN COMPLAINTS (To Address)

1. **Voice mode listening too long** → Added manual stop button (needs testing)
2. **VAD not detecting speech** → Tuning in progress (needs more work)
3. **TTS speaking special tokens** → Fixed with filterTTSText()
4. **Chat not auto-scrolling** → Fixed (needs verification)
5. **History growing too large** → Fixed with trimming
6. **Low memory warning annoying** → Removed

---

## 🤖 FOR NEXT AI AGENT

**Start by:**
1. Reading this file completely
2. Checking `AGENTS.md` for project background
3. Running `adb logcat` to see current behavior
4. Ask user to test voice mode
5. Focus on VAD threshold tuning based on actual audio levels observed

**Key Insight:** Samsung Tab A7 Lite has a very quiet microphone. Normal VAD settings (0.20-0.30 threshold) don't work. Need to find the sweet spot between:
- Low enough to detect quiet speech (maybe 0.10-0.15)
- High enough to not trigger on background noise

**Good luck!**
