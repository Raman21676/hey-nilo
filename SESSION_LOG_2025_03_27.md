# Hey-Nilo Voice Mode Session Log - March 27, 2025

## Session Overview
**Date:** March 27, 2025  
**Developer:** Raman  
**AI Agent:** Kimi (with Claude Opus 4.6 guidance)  
**Device:** Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)  
**Model:** TinyLlama-1.1B-Chat Q4_0 (608MB)

---

## Critical Issue: Voice Mode Q3 Failure

### Problem Summary
Voice mode works perfectly for Q1 and Q2, but fails on Q3:
- **Q1**: "Who is the Prime Minister of Nepal?" → ✅ Detected + Answered correctly
- **Q2**: "Who is the Prime Minister of India?" → ✅ Detected + Answered correctly  
- **Q3**: "Who is the Prime Minister of China?" → ✅ Detected correctly, but ❌ **LLM echoes the question instead of answering**

### Current Behavior
Q3 shows user question correctly, but Nilo's response is just the same question text:
```
User: "Who is the Prime Minister of China?"
Nilo: "Who is the Prime Minister of China?"  ← Echoes instead of answering
```

---

## Attempted Fixes (Chronological)

### Fix #1: listeningStartTime Reset (✅ WORKED for Q2)
**Problem:** Q2 was not being detected because 15-second timeout fired immediately  
**Solution:** Added `listeningStartTime = System.currentTimeMillis()` in TTS finally block  
**File:** `VoicePipelineManager.kt`  
**Status:** ✅ FIXED Q2 detection issue

### Fix #2: isRunning Check (✅ WORKED)
**Problem:** Audio callback blocked by `isRunning=false`  
**Solution:** Added log to diagnose when isRunning is false, ensured it's reset properly  
**File:** `VoicePipelineManager.kt`  
**Status:** ✅ Audio now flows to Q2 and Q3

### Fix #3: Special Token Filtering in History (✅ IMPLEMENTED)
**Problem:** `<|im_end|>` tokens were being saved to conversation history  
**Solution:** Filter special tokens before saving to history in `InferenceQueue.kt`  
**File:** `InferenceQueue.kt`  
**Status:** ✅ Implemented but Q3 still fails

### Fix #4: nativeClearContext Implementation (✅ IMPLEMENTED)
**Problem:** `nativeClearContext()` was a STUB - did nothing  
**Solution:** Implemented proper context clearing:
- Reset `current_pos = 0`
- Clear conversation history
- Clear batch tokens
**File:** `ai_chat_jni.cpp`, `bridge_jni.cpp`  
**Status:** ✅ Implemented but Q3 still echoes

### Fix #5: Reduce MAX_HISTORY_PAIRS (✅ IMPLEMENTED)
**Problem:** Conversation history might exceed context window  
**Solution:** Changed `MAX_HISTORY_PAIRS` from 3 to 1  
**File:** `ai_chat_jni.cpp` line ~569  
**Status:** ✅ Implemented but Q3 still echoes

### Fix #6: Prompt Debug Logging (✅ ADDED)
**Problem:** Can't see what prompt is being sent to model  
**Solution:** Added `LOGI("PROMPT DEBUG: %.300s", formatted.c_str())`  
**File:** `ai_chat_jni.cpp`  
**Status:** ✅ Added for next debugging session

---

## Root Cause Analysis (Current Theory)

### What We Know:
1. Q1 and Q2 work perfectly every time
2. Q3 is **detected correctly** (STT works)
3. Q3 **prompt is formatted correctly** (we can verify with new logging)
4. LLM **echoes the question** instead of answering

### Possible Causes:

#### Theory 1: Prompt Format Corruption
The prompt format might be getting corrupted on the 3rd turn. When we clear context and rebuild the prompt, something goes wrong with the chat template formatting.

#### Theory 2: KV Cache Not Fully Cleared
Even though we reset `current_pos` and call `llama_memory_clear`, the model's internal state might not be fully reset. TinyLlama might have some persistent state that's not being cleared.

#### Theory 3: Special Token ID Issues
The special token IDs (`<|im_end|>`, `<|im_start|>`) might be getting confused after multiple turns. The model might think it's still in the user role.

#### Theory 4: Temperature/Sampler State
The sampler state might be getting corrupted or stuck after 2 generations.

---

## Current State of Code

### Files Modified:
1. `VoicePipelineManager.kt` - listening timer fix, isRunning fix
2. `InferenceQueue.kt` - special token filtering
3. `ai_chat_jni.cpp` - nativeClearContext implementation, MAX_HISTORY_PAIRS=1, prompt debug logging
4. `bridge_jni.cpp` - forward declaration for nativeClearContext
5. `VADBridge.kt` - threshold adjustments (reverted)

### Key Code Locations:
- **Prompt building:** `ai_chat_jni.cpp` lines 577-594
- **Context clearing:** `ai_chat_jni.cpp` lines 968-999
- **History management:** `ai_chat_jni.cpp` lines 567-573
- **Token generation:** `ai_chat_jni.cpp` lines 669-750

---

## Debugging Commands for Next Session

### Check Prompt Being Sent:
```bash
adb logcat -d | grep "PROMPT DEBUG" | tail -3
```

### Check History Size:
```bash
adb logcat -d | grep "History size" | tail -5
```

### Check Special Tokens:
```bash
adb logcat -d | grep "Scanned vocabulary" | tail -1
```

### Full Log Capture:
```bash
adb logcat -c
# Test Q1, Q2, Q3
adb logcat -d --pid=$(adb shell pidof com.projekt_x.studybuddy) > /sdcard/voice_full.log
adb pull /sdcard/voice_full.log .
```

---

## Next Steps for Future AI Agent

### Step 1: Verify Prompt Format
Check the actual prompt being sent for Q3. Look for:
- Is the system prompt included?
- Is the user message formatted correctly?
- Is the assistant prefix present?
- Any special characters or corruption?

### Step 2: Check Token Generation
Add more detailed logging in the token generation loop to see:
- What are the first few token IDs being generated?
- Are they the user question tokens being replayed?
- Is the model immediately hitting stop tokens?

### Step 3: Try Alternative Context Clearing
Instead of just resetting position, try:
- Fully recreating the llama_context
- Or using `llama_kv_cache_clear()` if available
- Or reducing context size to force fresh start

### Step 4: Simplify Prompt Format
Try a simpler prompt format without conversation history:
```
system: You are Nilo...
user: Who is the Prime Minister of China?
assistant: [generate here]
```

### Step 5: Check Model Loading
Verify the model isn't being corrupted in memory after multiple generations.

---

## Related Issues from Session

### Issue: In-App Model Download (NOT FIXED)
- Progress bar shows erratic values (72% → 61%)
- Download fails silently at ~82%
- **Workaround:** Use `adb push` to manually copy models

### Issue: Qwen 0.5B Model (BROKEN - DO NOT USE)
- Generates chat tokens (`<|im_start|>`, `<|im_end|>`)
- Wrong facts
- Infinite repetition
- **Solution:** Use TinyLlama 1.1B only

### Issue: STT Accuracy
- "Who is" sometimes transcribed as "We are" or "Who am"
- This is a Whisper model accuracy issue, not code

---

## Model Information

| Model | Size | Status |
|-------|------|--------|
| TinyLlama 1.1B Q4_0 | 608MB | ✅ Working (Q1, Q2) |
| Qwen 0.5B | 409MB | ❌ Broken |
| Whisper STT | 141MB | ✅ Working |
| VAD | 2.3MB | ✅ Working |

---

## Environment

```bash
# Build command
cd native_android && ./gradlew :app:assembleDebug

# Install command  
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Model path on device
/sdcard/Android/data/com.projekt_x.studybuddy/files/models/

# Log capture
adb logcat -s VoicePipelineManager:V InferenceQueue:V ai-chat:V
```

---

## Git Commits from This Session

1. `e97b9b5` - Fix: Reset listening timer after TTS to enable Q2 response
2. `6c0c6d4` - WIP: Voice mode Q3 issue - nativeClearContext implemented, MAX_HISTORY_PAIRS=1, prompt debugging added

---

## Open Questions

1. Why does the model echo the question on Q3 but not Q1 or Q2?
2. Is the KV cache actually being cleared properly?
3. Is there a memory corruption happening after 2 turns?
4. Would a full context recreation fix the issue?
5. Is this a TinyLlama-specific issue or would other models work?

---

## Session Duration
Started: Evening of March 27, 2025  
Status: **ONGOING** - Q3 issue unresolved  
Next Action: **Run prompt debug logging and analyze Q3 prompt format**

---

*Document created for continuity in future AI agent sessions.*
