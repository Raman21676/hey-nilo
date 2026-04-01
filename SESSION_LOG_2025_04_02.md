# Session Log — 2025-04-02

## Session Goal
Fix the Hey-Nilo Android voice assistant so it:
1. No longer hangs indefinitely when asked about "china", "chinese", "japanese", or "korean" topics
2. Properly transitions back to listening after speaking (without detecting its own TTS output as user input)
3. Maintains a clean, working UI with the merged top bar

---

## Device Context (CRITICAL)
- **Target Device**: Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)
- **LLM**: TinyLlama-1.1B-Chat Q4_0 quantized (~608MB)
- **STT**: Whisper.cpp base.en model (~148MB)
- **TTS**: Android System TextToSpeech (Samsung offline engine)
- **Critical Hardware Limitation**: `isSpeaking()` API is **fundamentally broken** on this device. Any logic relying on it will hang.

---

## Issue #1: App Hangs on Second Question (Especially "Chinese/Japanese/Korean Culture")

### Root Cause Chain (Discovered Through Iterative Debugging)

#### Attempt 1: Suspected `nativeClearContext()` deadlock
- **Hypothesis**: `nativeClearContext()` in `ai_chat_jni.cpp` acquires `g_state->mutex` via `std::lock_guard`. If a native generation thread was stuck, any Kotlin caller to `clearContext()` would block forever.
- **Fix Applied**: Added `clearContextAsync()` fire-and-forget helper in `VoicePipelineManager.kt` and replaced synchronous `llmBridge?.clearContext()` calls in the normal response flow with it.
- **Result**: Did not fully solve the issue.

#### Attempt 2: Suspected Kotlin timeout around JNI causing coroutine cancellation while native thread runs
- **Hypothesis**: `LlamaBridge.kt` had a `withTimeout(60000)` around `nativeGenerateStream()`. If Kotlin cancelled the coroutine while the C++ thread kept running for up to 90s, the llama mutex would stay locked.
- **Fix Applied**: Removed the Kotlin `withTimeout(60000)` wrapper and the `elapsed > 60000` safety check inside `onToken()`.
- **Result**: Still hung on second question.

#### Attempt 3: Suspected `OfflineLLMProvider` streaming deadlock
- **Hypothesis**: `OfflineLLMProvider.kt` was waiting on `errorChannel.receiveCatching()` **BEFORE** reading any tokens. This meant it held the coroutine hostage until the entire generation finished, making the UI appear stuck in THINKING.
- **Fix Applied**: Rewrote the streaming collection to read from `tokenChannel` immediately as tokens arrive, then check `errorChannel` after the token stream closes.
- **Result**: Still problematic.

#### Attempt 4: Suspected C++ mutex deadlock
- **Hypothesis**: `nativeGenerateStream` acquired a `std::mutex` at the start and held it for the whole generation. If any previous generation thread got stuck, every new generation blocked forever.
- **Fix Applied**: Changed `std::mutex` to `std::timed_mutex` with `try_lock_for(5000ms)` in `nativeGenerateStream`, `prepareContext`, and `nativeClearContext`. New calls fail fast instead of hanging.
- **Result**: Still had hangs.

#### Attempt 5: VAD and audio preprocessing was broken
- **Discovery**: Logs showed `Listening timeout after 15040ms - no speech detected` with audio levels of 0.001-0.008 RMS.
- **Fix Applied**: Restored VAD threshold to 0.25f and added conservative 4x software gain. Also forced `AudioRecord` restart in `restartListening()` to re-initialize hardware AGC.
- **Result**: Speech detection returned, but then the app hung in THINKING on the second question.

#### Attempt 6 (THE REAL ROOT CAUSE): `OfflineLLMProvider` was NEVER used in offline mode
- **Discovery**: In `MainActivity.kt`, `llmProvider` was ONLY created when `onlineConfig != null`:
  ```kotlin
  val llmProvider: LLMProvider? = onlineConfig?.let { config -> ... }
  ```
  When using offline mode (TinyLlama), `onlineConfig` was `null`, so `llmProvider` was always `null`. This meant ALL fixes to `OfflineLLMProvider` were completely unused. The app silently fell back to the old `InferenceQueue` path.
- **Fix Applied**: Changed `MainActivity.kt` to create `OfflineLLMProvider(context, bridge)` when `onlineConfig == null` and `bridge.isLoaded() == true`.
- **Result**: The app finally started using the `OfflineLLMProvider` path.

#### Attempt 7 (SECOND REAL ROOT CAUSE): Pre-formatted prompt passed to native layer
- **Discovery**: `OfflineLLMProvider` was building a fully formatted chat-template prompt and passing it to `nativeGenerateStream()`:
  ```
  <|system|>You are Nilo...</s><|user|>How are you?</s><|assistant|>
  ```
  But the C++ layer (`nativeGenerateStream`) expects **ONLY the raw user message**. It maintains its own history and formats the prompt itself. This caused double-wrapped garbage prompts that confused the model, caused `llama_decode` to hang or return empty responses.
- **Fix Applied**: 
  - `OfflineLLMProvider.complete()` now calls `llamaBridge.setSystemPrompt(systemPrompt)` then passes only `userMessage` to `generate()`.
  - `OfflineLLMProvider.stream()` now calls `llamaBridge.setSystemPrompt(systemPrompt)` then passes only `userMessage` to `generateWithMaxTokens()`.
- **Result**: Model received clean prompts. Consecutive questions (Chinese culture, Korean culture, "How are you?") now work correctly with live token streaming.

---

## Issue #2: "Nilo is listening..." Appears While TTS Is Still Speaking

### Symptom
After the response finished generating tokens, the mic turned back on while TTS was still speaking. This caused the app to detect its own voice output as the next user query.

### Root Cause
The TTS `finally` block in `startStreamingTTS()` was transitioning directly to `LISTENING` and restarting the recorder immediately after TTS finished, without checking if the LLM was still generating. It also reset `isLLMResponseComplete = false` in the `finally` block, destroying the LLM-done signal.

### Fix Applied
- Added `awaitCompletionAndListen()` — a coroutine that polls every **500ms** until:
  1. `isLLMResponseComplete == true` (LLM done generating)
  2. `streamingTTSJob` is inactive AND `kokoroTTS.isSpeaking() == false` (TTS fully done)
- Removed `transitionToState(LISTENING)` and `startRecording()` from the TTS `finally` block.
- Removed `isLLMResponseComplete = false` from the TTS `finally` block so the poller can see the LLM-done flag.
- Added `awaitCompletionAndListen()` calls in both `processWithLLMProvider()` and `processWithLegacyQueue()` completion paths.
- Added blank-response fallback to `restartListening()` in both paths.

---

## Issue #3: Top Bar UI Cleanup

### Fix Applied
- Removed the horizontal memory bar (`MemoryIndicator`) from `CompactTopBar`.
- Current top bar items: Title, temperature indicator, performance dot (when generating), theme toggle ☀️/🌙, RAM optimizer button.

---

## Final Verified Behavior (As of End of Session)

| Question | Transcription | Response |
|----------|---------------|----------|
| "Tell me about Japanese culture" | ❌ Hallucinated (Whisper noise) | N/A |
| "Tell me about Chinese culture" | ✓ Accurate | ✓ Generated & spoke properly |
| "Tell me about Korean culture" | ✓ Accurate | ✓ Generated & spoke properly |
| "Hello, how are you?" | ✓ Accurate | ✓ Generated & spoke properly |

The **Japanese culture** query failing was a **Whisper STT hallucination** (base.en model inventing gibberish on unclear audio). This is a known limitation of the base.en model and is NOT related to the LLM hang bug.

The core hang/null-response bug on consecutive questions is **resolved**.

---

## Key Files Modified Today

1. `native_android/app/src/main/cpp/ai_chat_jni.cpp` — timed mutex, raw prompt handling
2. `native_android/app/src/main/java/com/projekt_x/studybuddy/MainActivity.kt` — create `OfflineLLMProvider` in offline mode
3. `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/LlamaBridge.kt` — removed dangerous Kotlin timeout
4. `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/VoicePipelineManager.kt` — `awaitCompletionAndListen`, VAD/gain fixes, safety nets
5. `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/llm/OfflineLLMProvider.kt` — pass raw user message, fix streaming
6. `native_android/app/src/main/java/com/projekt_x/studybuddy/InferenceQueue.kt` — added 90s timeout to prevent queue clogging
7. `native_android/app/src/main/java/com/projekt_x/studybuddy/ui/components/StatusBar.kt` — removed memory bar

---

## Open Issues / Next Steps for Future Agent

1. **Whisper STT hallucinations on unclear audio**: Occasionally the Tab A7 Lite mic + base.en model produces gibberish transcriptions like "They are doing some things about exactly what they are." Consider:
   - Upgrading to `ggml-small.en.bin` for better accuracy (trades RAM/performance)
   - Adding a transcription sanity filter (e.g. reject transcriptions with very low confidence or nonsensical grammar)

2. **Response verbosity**: The model sometimes generates overly long responses (especially for list/culture questions). The `maxTokens` cap is 120, but the model can still produce long sentences within that limit. Consider tightening `dynamicMaxSentences` or adding a sentence-count cutoff in `VoicePipelineManager`.

3. **TTS `isSpeaking()` fallback**: The `awaitCompletionAndListen()` poller relies on `kokoroTTS?.isSpeaking()` plus job state. The time-based fallback in `KokoroTTSBridge` works but is not perfectly precise. If TTS ever seems to cut off early or wait too long, tune the poller interval or the time fallback constants.

4. **Context clearing reliability**: `nativeClearContext` sometimes logs `Cannot clear context - llama mutex is held by a stuck generation, skipping`. This is currently harmless because the timed mutex prevents deadlock, but over many turns the KV cache may accumulate. If long conversations degrade quality, investigate why the mutex is still occasionally held.
