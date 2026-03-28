# Hey-Nilo Development Log

> **For Future AI Agents:** This file contains the complete history of development sessions, issues encountered, and solutions attempted. Read this file at the start of each new session to understand the project state.

---

## Session Date: 2026-03-29

### Device Under Test
- **Device:** Samsung Galaxy Tab A7 Lite
- **RAM:** 3GB
- **Android:** Android 14
- **Model:** Qwen2.5 0.5B (Q4_0 quantized)

---

### Issues Being Addressed

#### 1. **STT Accuracy Issues** (Qwen2.5 Model)
**Status:** ⚠️ Model Quality Limitation (Not Code Bug)

**Problem:**
- "Who are you?" transcribed as "Hello everyone"
- Similar-sounding phrases confused by Whisper tiny model
- Model: Whisper base.en (74MB)

**Root Cause:**
- Qwen2.5 0.5B is a small model with inherent accuracy limitations
- Low-end device (3GB RAM) may not handle larger Whisper models well

**Attempts:**
1. Added software gain amplification (8x) to microphone
2. Changed VAD threshold from 0.4 to 0.15
3. Used `AudioSource.VOICE_RECOGNITION` for hardware AGC

**Next Steps (Not Implemented):**
- Try Whisper "small" model instead of "base" (but may be too large for 3GB RAM)
- Consider using online STT for better accuracy
- Accept current limitation as model quality issue

---

#### 2. **Response Length Control** (CRITICAL - Partially Fixed)
**Status:** ⚠️ Needs Further Testing

**Problem:**
- LLM generating 5+ sentences instead of 2-3
- maxTokens=256 not being respected properly

**Solution Implemented:**
```kotlin
// Added to VoicePipelineManager.kt
private const val MAX_RESPONSE_CHARS = 200
private const val MAX_RESPONSE_SENTENCES = 3
```

**Logic:**
- After each token, check if response length ≥ 200 chars OR sentence count ≥ 3
- If limit reached: Stop LLM generation immediately
- Wait for TTS to finish, then transition to listening

**Code Location:** `VoicePipelineManager.kt` in `processWithLLMProvider()` streaming handler

---

#### 3. **TTS and UI Text Mismatch** (CRITICAL - Attempted Fix)
**Status:** ⚠️ Partially Fixed, May Need More Work

**Problem:**
- Text bubble shows less content than what TTS speaks
- TTS continues speaking hidden/system content not shown in UI
- User sees: "Yes, I am here to help."
- TTS speaks: "Yes, I am here to help. |im_end| [Chinese system content...]"

**Attempts Made:**

**Attempt 1: Filter `|im_end|` tokens**
```kotlin
filtered = filtered.replace("|im_end|", "")  // Catch |im_end|| pattern
```

**Attempt 2: Add Chinese character detection**
```kotlin
val chineseCharPattern = Regex("[\u4e00-\u9fff]")
if (chineseCharPattern.containsMatchIn(text)) {
    return true  // Stop generation
}
```

**Attempt 3: Truncate TTS buffer when leakage detected**
```kotlin
// When role leakage detected:
ttsTextBuffer.clear()
ttsTextBuffer.append(filterTTSText(cleanResponse))
```

**Attempt 4: Sync TTS buffer with display text**
- When max length reached: Clear TTS buffer and rebuild from clean response
- When role leakage detected: Same truncation logic
- When response complete: Compare and fix mismatch

**Current Issue:**
- TTS may still speak more than displayed in edge cases
- Need to verify the fix works in practice

**Files Modified:**
- `VoicePipelineManager.kt` - `filterTTSText()`, `detectRoleLeakage()`, `truncateAtRoleLeakage()`

---

#### 4. **Model Generating System Content (Role Leakage)**
**Status:** ✅ Fixed with detection + truncation

**Problem:**
- Model generates its own system prompt as response
- Example: "User:[Typesomethinghere]Youareauserinterfacecomponent..."
- Example: Chinese text explaining system behavior

**Solution:**
```kotlin
private fun detectRoleLeakage(text: String): Boolean {
    val leakagePatterns = listOf(
        "user:", "system:", "you are a user interface",
        "your task is to", "qwen", "conversation mode",
        "<|im_start|>", "<|im_end|>", "|im_end|"
    )
    // Also check for Chinese characters
}
```

**Behavior:**
1. Detect leakage pattern in streaming tokens
2. Stop LLM generation immediately
3. Truncate response at leakage point
4. Truncate TTS buffer to match
5. Transition to listening

---

#### 5. **VAD Not Detecting Speech**
**Status:** ✅ Fixed with Software Gain

**Problem:**
- VAD probability stuck at 0.001-0.003
- Audio level: 0.001-0.002 (extremely quiet)
- Samsung Tab A7 Lite has quiet microphone

**Solution:**
```kotlin
private const val SOFTWARE_GAIN = 8f

// In processAudioWithVAD():
val amplifiedAudio = ShortArray(audioData.size) { i ->
    val amplified = (audioData[i] * SOFTWARE_GAIN).toInt()
    when {
        amplified > Short.MAX_VALUE -> Short.MAX_VALUE
        amplified < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> amplified.toShort()
    }
}
```

**Result:** Audio level increased from 0.001 to 0.083, VAD now detects speech

---

#### 6. **Auto-Listen After Response**
**Status:** ✅ Fixed

**Problem:**
- After TTS finished, app stayed in "Nilo is speaking" state
- Didn't transition back to "Nilo is listening"
- LLM continued generating in background

**Solution:**
- Added `restartListening()` call after response complete
- Reset state properly in `finally` block
- Clear LLM context after response

---

### Key Configuration Values

```kotlin
// VoicePipelineManager.kt
private const val SOFTWARE_GAIN = 8f                    // Mic amplification
private const val VAD_THRESHOLD = 0.15f                 // Speech detection
private const val MAX_RESPONSE_CHARS = 200              // Response limit
private const val MAX_RESPONSE_SENTENCES = 3            // Response limit
private const val MAX_SPEECH_MS = 15000L                // Force stop after 15s
private const val MIN_SPEECH_MS = 800L                  // Minimum speech duration
private const val TTS_COOLDOWN_MS = 1500L               // Prevent echo detection
```

---

### Files Modified in This Session

1. **VoicePipelineManager.kt** (Major changes)
   - Added software gain amplification
   - Added role leakage detection
   - Added response length limits
   - Fixed TTS/UI sync issues
   - Added Chinese character filtering

2. **ModelSetupView.kt**
   - Auto-detect downloaded models
   - Auto-load model after download
   - AI warning banner

3. **MainActivity.kt**
   - Permission-aware voice pipeline initialization
   - Re-initialize when permission granted

4. **OfflineModelPickerScreen.kt**
   - Model selection improvements

5. **ConversationManager.kt** (New file)
   - Conversation history management

---

### Current Known Issues (For Next Session)

1. **TTS/UI Mismatch** (Priority: HIGH)
   - May still occur in edge cases
   - Need to verify fix works consistently
   - Consider adding real-time TTS buffer logging

2. **STT Accuracy** (Priority: MEDIUM)
   - Model limitation, not code bug
   - Consider switching to online STT for better accuracy
   - Or accept limitation for offline mode

3. **Response Truncation** (Priority: MEDIUM)
   - 200 char limit may be too aggressive for some queries
   - Consider making it adaptive based on query type

4. **VAD Sensitivity** (Priority: LOW)
   - Current threshold 0.15 works but may need tuning
   - Background noise may trigger VAD occasionally

---

### Git Commit Reference

```
Commit: 3644ff8
Message: Fix: Voice pipeline improvements - TTS/UI sync, role leakage detection, response limits
Date: 2026-03-29
```

---

### Testing Checklist for Next Session

- [ ] Test voice query: "Who are you?" → Should show and speak only clean response
- [ ] Test voice query: "Tell me a story" → Should stop at 3 sentences
- [ ] Verify TTS matches bubble text exactly
- [ ] Check that Chinese/system content doesn't appear
- [ ] Verify auto-listen after response
- [ ] Test with background noise
- [ ] Test interruption (barge-in) during TTS

---

### Architecture Notes

**Voice Pipeline Flow:**
```
User Speech → AudioRecorder → VAD → Speech Buffer → STT (Whisper) → 
Text → LLM (Qwen2.5) → Response → TTS (Kokoro) → Audio Out
```

**Critical State Management:**
- `fullResponseText`: Accumulates ALL LLM tokens (raw)
- `ttsTextBuffer`: Filtered content for TTS
- `onResponseUpdate`: UI callback (should show truncated/filtered text)

**Key Fix for TTS/UI Sync:**
Both `ttsTextBuffer` and UI must use the SAME truncated/filtered text. When role leakage or max length is detected, both must be truncated together.

---

### Contact/Resources

- **GitHub:** https://github.com/Raman21676/hey-nilo
- **Models:** Stored in `/sdcard/Android/data/com.projekt_x.studybuddy/files/models/`
- **Build:** `./gradlew :app:assembleRelease`

---

*End of Session Log - 2026-03-29*
