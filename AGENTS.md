# Hey-Nilo: AI Agent Onboarding Guide

**For**: AI Agents working on the Hey-Nilo project  
**Purpose**: Complete project context and working guidelines  
**Last Updated**: April 3, 2026

> **📋 RECENT SESSION**: See `SESSION_LOG_2025_04_03.md` for today's work - ✅ Hugging Face model download added, UI click issues fixed, Release APK/AAB built.

> **📋 PREVIOUS SESSION**: See `logs/session-2025-03-31.md` for March 31 work - ✅ CRITICAL FIXES COMPLETED: LLM generation truncation fixed, TTS completion detection fixed, voice pipeline now stable.  

> **⚠️ IMPORTANT**: Read this entire document before writing any code. This is your source of truth for the project.

---

## 🎯 Quick Start (Read This First)

### Step 1: Understand the Project
Hey-Nilo is an **Android AI Voice Assistant** that operates in two modes:
- **Offline Mode**: Uses local TinyLlama 1.1B model for memory operations and basic assistance
- **Online Mode**: Connects to cloud LLMs (OpenAI, Claude, DeepSeek, Kimi) for complex tasks

**Key Feature**: Persistent local memory system that remembers conversations, personal info, and tasks for 1 year.

### Step 2: Read These Files (In Order)
1. **This file** (`AGENTS.md`) - You're here
2. **`PLANNING.md`** - Architecture and technical specifications
3. **`TODO.md`** - Current tasks and progress tracking
4. **Last `SESSION_LOG_YYYY_MM_DD.md`** - See what was done in previous session
   - Format changed April 2026: Session logs now in root folder with `SESSION_LOG_YYYY_MM_DD.md` naming

### Step 3: Check Current Status
```bash
# Run this to see current project state
cd /Users/kalikali/Desktop/hey-nilo
cat TODO.md | grep -A 5 "Project Status Overview"
```

### Step 4: Continue Work
- Find the first incomplete task in `TODO.md`
- Start working on it
- **Document everything** in the session log (see below)

---

## 🆕 Recent Changes (April 3, 2026)

### Hugging Face Model Download - IMPLEMENTED ✅
**Status**: Feature complete and tested  
**Commit**: `5a53e56` pushed to GitHub  
**See detailed log**: `SESSION_LOG_2025_04_03.md`

#### Features Added:
1. **HuggingFaceSearchScreen** - Browse and download GGUF models directly from Hugging Face Hub
2. **HuggingFaceClient** - API client with download progress tracking
3. **LastModelPreference** - Remember last used model with resume dialog
4. **Release Builds** - Signed APK (181 MB) and AAB (156 MB) ready for Play Store

#### UI Fixes Applied:
1. **ModeCard Click Handling** - Restructured to fix Configure button clicks
2. **Download Button** - Replaced IconButton with Button for better click handling
3. **Progress Dialog** - Fixed missing state assignment causing dialog not to show

---

## 🆕 Recent Changes (March 31, 2026)

### Voice Pipeline - STABLE ✅
**Status**: All critical issues resolved  
**Commit**: `6943d61` pushed to GitHub  
**See detailed log**: `logs/session-2025-03-31.md`

#### Major Fixes Applied:
1. **LLM Generation Truncation** - Removed `<|im_end|>` from native stop sequences
2. **TTS Completion Detection** - Changed from fixed delay to polling `isSpeaking()`
3. **UI/TTS Sync** - Both now use `removeImEndTokenOnly()` to show all generated text

#### Previous Session (March 30, 2026)
See `logs/session-2025-03-30.md` for earlier debugging attempts and state machine issues.

#### 1. C++ Hard Timeout (CRITICAL FIX)
- **File**: `native_android/app/src/main/cpp/ai_chat_jni.cpp`
- **Problem**: Qwen model enters infinite loop generating Chinese tokens on "China" questions
- **Fix**: Added 30-second wall-clock timeout in generation loop
- **Code**:
```cpp
auto start_time = std::chrono::steady_clock::now();
while (n_gen < maxTokens && !g_state->should_stop) {
    auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::steady_clock::now() - start_time).count();
    if (elapsed > 30) { break; }
```

#### 2. Sequential Pipeline Architecture
- **Goal**: Strict flow: LLM generates → TTS speaks → TTS done → LISTENING
- **Implementation**: `startStreamingTTS()` now waits for TTS completion before transitioning
- **Issue**: Still not perfect - TTS sometimes doesn't start until LLM completes

#### 3. State Machine Hardening
- Added debug logs: `NILO_DEBUG` prefix for tracking state transitions
- Hard reset of all buffers at start of each LLM call
- `isLLMResponseComplete` flag properly reset

#### 4. Token Filtering Fixes
- Added `|im_end|` and `|im_start|` (without brackets) to `filterAiResponse()` in MainActivity.kt
- **Still seeing**: Model generates Chinese text for China questions (filtered but visible in logs)

### Dead Code Cleanup - Pre-Play Store (March 29, 2026)
- **Removed**: Unused `ConversationManager` (30 messages/chat limit, 3-chat FIFO)
  - File deleted: `ConversationManager.kt`
  - Removed from `MainActivity.kt`: import, instantiation, and related states
- **Why**: Partially implemented feature, never fully integrated

---

## 📝 Previous Changes (March 21, 2026)

### Logo & UI Refresh
- **New Logo**: Replaced panda with user's custom "Nilo" blue face logo
  - Updated: Launcher icons, main screen, voice button, splash screen
  - Deleted: Old panda assets
- **3D Message Bubbles**: Added shadow elevation and depth effects
- **Theme Toggle**: Light/dark mode button in top-right corner (🌙/☀️)
- **Back Button Fix**: Intercepted back button in ModelSetupView to prevent app exit

### System Prompt Tag Filtering
- **Fixed**: AI responses no longer leak `[MEMORY]`, `[/MEMORY]`, `[/s]`, `</s>` tags
- **Solution**: Added `filterAiResponse()` function + changed memory context format
- **Files**: `MainActivity.kt`, `SystemPromptBuilder.kt`, `MemoryManager.kt`

---

## 📝 Previous Changes (March 17, 2026)

### Play Store Publication Ready
- **Release APK/AAB**: Built and signed with release keystore
- **APK Size**: 180 MB | **AAB Size**: 155 MB
- **Package**: `com.projekt_x.studybuddy`
- **Version**: 1.0 (versionCode 1)
- **Assets**: Icons, screenshots, feature graphic, privacy policy all ready
- **Location**: All assets in `/Users/kalikali/Desktop/hey-nilo-final/`

### Memory System Completion
- **MemoryExtractionHelper.kt**: Rule-based and LLM-based extraction from conversations
- **MemoryCompaction.kt**: Automatic archiving of conversations older than 1 year
- **MemoryManagerScreen.kt**: Full UI for viewing/editing profile, relationships, reminders
- **extractAndSave()**: Extracts facts, relationships, reminders with confidence scoring
- **Duplicate detection**: Prevents duplicate memory entries

### Model Expansion (10 → 28 Models)
- **Categories**: General, Coding, Creative, Multilingual, Reasoning, Ultra-Light
- **RAM Tiers**: 3GB, 4GB, 6GB, 8GB, 12GB+ support
- **New models**: Qwen, DeepSeek, Gemma, Phi, Mistral variants
- **Smart filtering**: Models filtered by device RAM capability

---

## 📝 Previous Changes (March 10, 2025)

### UI Redesign: Compact Siri-style Overlay
- **Before**: Large overlay taking 50% of screen with "Panda" branding
- **After**: Minimal 44dp orb at bottom-center with "Nilo" branding
- **Status text**: "Nilo is listening/speaking..." displayed below orb
- **Colors**: Blue (listening), Red (speech detected), Purple (speaking), Yellow (thinking)
- **Interaction**: Tap orb to stop voice mode

### TTS Fixes
- **Fixed**: TTS completion tracking (was hanging in SPEAKING state)
- **Fixed**: Fragmented speech (now speaks full sentences)
- **Implementation**: Added `pendingUtterances` set in `KokoroTTSBridge.kt`

### Voice Pipeline Improvements
- **Reduced delays**: VAD silence detection 1200ms → 500ms
- **Faster response**: ~5s → ~1-2s after speech ends
- **Better barge-in**: Energy threshold 1500 → 2500 (ignores typing/horns)

---

## 📚 Project Overview

### What is Hey-Nilo?
```
┌─────────────────────────────────────────────────────────────┐
│  HEY-NILO: Your Personal AI Companion                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  🏠 OFFLINE MODE                    🌐 ONLINE MODE          │
│  • TinyLlama 1.1B (local)           • GPT-4 / Claude        │
│  • 100% Private                     • Smarter responses     │
│  • Works without internet           • Requires API key      │
│  • Perfect for memory & history     • Complex reasoning     │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  UNIFIED MEMORY SYSTEM (Both modes share this)      │    │
│  │  • Personal info: FOREVER                           │    │
│  │  • Conversations: 1 YEAR                            │    │
│  │  • Tasks/Reminders: 1 YEAR                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Target Device
- **Device**: Samsung Galaxy Tab A7 Lite
- **RAM**: 3GB
- **OS**: Android 14
- **Constraints**: Low RAM, must work offline, privacy-focused

### Technology Stack
| Layer | Technology | Purpose |
|-------|------------|---------|
| **UI** | Jetpack Compose | Modern Android UI |
| **Language** | Kotlin (95%) | Business logic, memory management |
| **Native** | C++ (5%) | LLM inference via llama.cpp |
| **Bridge** | JNI | Kotlin ↔ C++ communication |
| **Storage** | JSON + Markdown | Human-readable memory files |
| **HTTP** | OkHttp | API calls for online mode |
| **Security** | EncryptedSharedPreferences | API key storage |

---

## 🏗️ Architecture Deep Dive

### Folder Structure
```
hey-nilo/
├── AGENTS.md              ← You are here
├── PLANNING.md            ← Technical specs (READ THIS)
├── TODO.md                ← Task tracking (READ THIS)
├── logs/                  ← Session logs (WRITE HERE)
│   └── session-YYYY-MM-DD.md
│
└── native_android/
    └── app/src/main/
        ├── java/com/projekt_x/studybuddy/
        │   ├── MainActivity.kt
        │   ├── bridge/              ← YOUR WORK GOES HERE
        │   │   ├── MemoryManager.kt
        │   │   ├── LLMProvider.kt
        │   │   ├── OfflineLLMProvider.kt
        │   │   ├── OnlineLLMProvider.kt
        │   │   ├── ModeController.kt
        │   │   ├── ApiKeyStore.kt
        │   │   ├── MemoryExtractionHelper.kt
        │   │   └── VoicePipelineManager.kt
        │   ├── model/memory/        ← Data models
        │   │   ├── UserProfile.kt
        │   │   ├── Relationship.kt
        │   │   ├── Reminder.kt
        │   │   └── MemoryStats.kt
        │   ├── ui/                  ← UI screens
        │   │   ├── ModelSetupView.kt
        │   │   ├── OfflineModelPickerScreen.kt
        │   │   ├── HuggingFaceSearchScreen.kt  ← NEW: HF model browser
        │   │   └── ChatActivity.kt
        │   └── util/                ← Utilities
        │       ├── HuggingFaceClient.kt        ← NEW: HF API client
        │       └── LastModelPreference.kt      ← NEW: Model persistence
        │
        └── cpp/
            └── ai_chat_jni.cpp      ← DON'T TOUCH (existing)
```

### Memory System Architecture
```
App Start
    │
    ▼
┌─────────────────────┐
│ MemoryManager       │
│ .initialize()       │ ← Load JSON files into RAM cache
└─────────────────────┘
    │
    ▼
Voice/Chat Session
    │
    ▼
┌─────────────────────┐
│ buildContextBlock() │ ← Generate ≤300 token memory context
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ LLM Prompt          │ ← [MEMORY] block + system prompt
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ generateResponse()  │ ← Offline or Online provider
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ extractAndSave()    │ ← Background: Extract facts from conversation
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ saveConversation()  │ ← Save session as markdown
└─────────────────────┘
```

### Dual Mode Flow
```
User Input
    │
    ▼
ModeController.getActiveProvider()
    │
    ├─► Offline Mode ──► OfflineLLMProvider ──► TinyLlama (local)
    │
    └─► Online Mode ───► OnlineLLMProvider ───► OpenAI/Claude/DeepSeek/Kimi API
```

---

## 📋 Critical Rules (NEVER Break These)

### 1. Code Style
```kotlin
// ✅ DO: Use coroutines for async operations
suspend fun saveMemory() {
    withContext(Dispatchers.IO) {
        // File operations here
    }
}

// ❌ DON'T: Block main thread
fun saveMemory() {
    file.writeText(json)  // This will freeze UI!
}
```

### 2. Error Handling
```kotlin
// ✅ DO: Graceful error handling
try {
    val result = parseJson(jsonString)
} catch (e: JsonParseException) {
    Log.e(TAG, "Failed to parse memory JSON", e)
    // Return default/empty, don't crash
    return MemoryDefaults.empty()
}

// ❌ DON'T: Let exceptions crash the app
val result = parseJson(jsonString)  // Will crash if malformed!
```

### 3. Token Limits
```kotlin
// ✅ DO: Respect TinyLlama's context window
const val MAX_CONTEXT_TOKENS = 300
const val MAX_EXTRACTION_TOKENS = 150
const val TINYLLAMA_CONTEXT_WINDOW = 2048

// ❌ DON'T: Exceed limits
val contextBlock = buildHugeContext()  // Will overflow!
```

### 4. File I/O
```kotlin
// ✅ DO: Always use Dispatchers.IO
scope.launch(Dispatchers.IO) {
    file.writeText(content)
}

// ❌ DON'T: Use Dispatchers.Main for files
scope.launch(Dispatchers.Main) {
    file.writeText(content)  // ANR risk!
}
```

### 5. Don't Touch Existing Code (Unless Fixing Bugs)
**Avoid modifying these working components unless fixing specific bugs:**
- `VADBridge.kt` / `VADProcessor` (Silero VAD)
- `whisper_jni.cpp` / `RealSTTBridge.kt` (Speech-to-text)
- `CMakeLists.txt` (Build config)
- `SimpleAudioRecorder.kt` (Audio recording)

**Recently Fixed:**
- `KokoroTTSBridge.kt` - Fixed TTS completion tracking with pending utterance set
- `VoicePipelineManager.kt` - Reduced VAD delays, fixed TTS streaming
- `MainActivity.kt` - Compact Siri-style UI overlay

**Why**: Core pipeline is now stable. Prefer configuration changes over code changes.

---

## 🔧 Development Guidelines

### Before You Start Coding
1. Read `TODO.md` to find the current active task
2. Read the last session log in `logs/` folder
3. Check git status: `git status` (don't start on dirty state)
4. Create a new branch if making significant changes

### While Coding
1. **Write clean, documented code**
   ```kotlin
   /**
    * Builds memory context block for LLM prompt injection.
    * Ensures output is ≤ maxTokens (default 300).
    * 
    * @param maxTokens Maximum tokens allowed (default 300)
    * @return Formatted context block string
    */
   fun buildContextBlock(maxTokens: Int = 300): String {
       // Implementation
   }
   ```

2. **Add logging for debugging**
   ```kotlin
   Log.d(TAG, "Building context block for user: ${userProfile.name}")
   ```

3. **Test edge cases**
   - Empty files
   - Malformed JSON
   - Storage full
   - Network timeout (for online mode)

### Before You Finish Session
1. **Run basic tests**
   ```bash
   cd native_android
   ./gradlew testDebugUnitTest
   ```

2. **Verify app builds**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Write session log** (SEE BELOW - This is CRITICAL)

4. **Commit your work**
   ```bash
   git add -A
   git commit -m "feat: [description of what you did]"
   git push origin main
   ```

---

## 📝 Session Log System (CRITICAL)

### What is a Session Log?
A **session log** is a record of what you worked on, what problems you faced, and what you accomplished. It's how the NEXT AI agent knows where to continue.

### Where to Write Session Logs
**Location**: `logs/session-YYYY-MM-DD.md`

**Example**: If you work on March 8, 2025, create:
`logs/session-2025-03-08.md`

### Session Log Template

```markdown
# Session Log - 2025-03-08

**Agent**: [Your name/identifier]  
**Time**: 14:00 - 18:00 IST (4 hours)  
**Focus**: Phase 1, Job 1.3 - MemoryManager Core  
**Status**: ✅ Complete / 🔄 In Progress / ❌ Blocked

---

## What Was Planned
Implement `MemoryManager` class with:
- [x] Initialize and load files
- [x] `buildContextBlock()` method
- [ ] Profile CRUD operations
- [ ] Unit tests

---

## What Was Completed

### ✅ Done
1. Created `MemoryManager.kt` with basic structure
2. Implemented `initialize()` - loads JSON into RAM cache
3. Implemented `buildContextBlock()` - generates ≤300 token context
4. Added thread-safety with `Dispatchers.IO`
5. Added logging

### 🔄 Partially Done
- Profile read operations work
- Profile write operations need testing

### ❌ Not Done
- Unit tests (will do next session)
- Reminder operations

---

## Errors Faced & Solutions

### Error 1: JSON Parse Exception
**Problem**: App crashed when `user_profile.json` was malformed
```
com.google.gson.JsonSyntaxException: Expected ':' at line 5
```

**Root Cause**: File was manually edited and had syntax error

**Solution**: Added try-catch with default fallback
```kotlin
try {
    gson.fromJson(json, UserProfile::class.java)
} catch (e: JsonParseException) {
    Log.e(TAG, "Corrupted profile, using default")
    UserProfile.default()
}
```

**Status**: ✅ Fixed

### Error 2: ANR on File Write
**Problem**: UI froze when saving large conversation

**Root Cause**: File I/O on main thread

**Solution**: Wrapped in `withContext(Dispatchers.IO)`

**Status**: ✅ Fixed

---

## Code Written

### Files Created
- `bridge/MemoryManager.kt` (150 lines)
- `model/memory/UserProfile.kt` (50 lines)

### Files Modified
- None

### Key Code Snippet
```kotlin
// Context block generation with token limit
fun buildContextBlock(maxTokens: Int = 300): String {
    val parts = mutableListOf<String>()
    
    // User info
    userProfile.name?.let { parts.add("User: $it") }
    
    // Facts (max 3)
    userProfile.facts.take(3).forEach { parts.add("Fact: $it") }
    
    // Join and truncate if needed
    return parts.joinToString("\n").truncateToTokens(maxTokens)
}
```

---

## Testing Done

| Test | Result | Notes |
|------|--------|-------|
| App builds | ✅ Pass | No errors |
| Memory loads | ✅ Pass | 50ms load time |
| Context block | ✅ Pass | 245 tokens (under limit) |
| File write | ✅ Pass | No ANR |

---

## Blockers for Next Session

1. **Need to decide**: Should we use Gson or kotlinx.serialization?
   - Gson is already in project
   - kotlinx.serialization is more modern
   - **Decision needed before writing more code**

---

## Next Steps (For Next Agent)

1. ✅ Complete `updateProfileField()` method
2. ✅ Add relationship operations
3. ✅ Write unit tests for MemoryManager
4. ⏸️ Blocked on: Gson vs kotlinx.serialization decision

**Start with**: Open `TODO.md`, find Job 1.4

---

## Notes for Future Reference

- TinyLlama context window is tight. Always verify token counts.
- File I/O is fast enough with coroutines, no need for premature optimization.
- Consider adding memory compression for large profiles.
```

### What to Include in Session Log

| Section | Why Important | Example |
|---------|---------------|---------|
| **What Was Planned** | Next agent knows what you intended to do | "Implement MemoryManager" |
| **What Was Completed** | Clear status of progress | "Done: initialize(), buildContextBlock()" |
| **Errors Faced** | Next agent doesn't repeat mistakes | "JSON parse error, solution: add try-catch" |
| **Code Written** | Quick reference for changes | "Created MemoryManager.kt" |
| **Testing Done** | Quality assurance | "Unit tests: 5/5 passed" |
| **Blockers** | What needs decision/help | "Need to choose JSON library" |
| **Next Steps** | Where to continue | "Complete Job 1.4" |

### Session Log Checklist
Before ending your session, verify:
- [ ] Created/updated log file in `logs/session-YYYY-MM-DD.md`
- [ ] Listed all files created/modified
- [ ] Documented all errors and solutions
- [ ] Clearly stated what's complete vs incomplete
- [ ] Provided next steps for following agent
- [ ] Mentioned any blockers or decisions needed

---

## 🐛 Common Issues & Solutions

### Issue 1: "Out of Memory" on Tab A7 Lite
**Symptoms**: App crashes with OOM during inference
**Solution**: 
- Reduce TinyLlama threads from 4 to 2
- Don't load full conversation history into RAM
- Clear bitmaps/drawables when not needed

### Issue 2: "JNI Crash" in llama.cpp
**Symptoms**: Native crash, SIGSEGV
**Solution**:
- Check model file is not corrupted
- Verify context size doesn't exceed model limit
- Don't call JNI from multiple threads simultaneously

### Issue 3: "Slow Voice Response"
**Symptoms**: Delay between speech and response
**Solution**:
- Check memory block isn't too large (>300 tokens)
- Verify extraction runs on background thread
- Reduce VAD silence threshold if needed

### Issue 4: "API Key Not Working"
**Symptoms**: Online mode fails with 401 Unauthorized
**Solution**:
- Check key is saved in EncryptedSharedPreferences
- Verify key format (some need "Bearer " prefix)
- **IMPORTANT**: Kimi keys from `kimi.com/code/console` (format: `sk-kimi-...`) are IDE-only and won't work with the app
- For Kimi, get a key from `platform.moonshot.cn` (format: `sk-...`)
- Check API provider status page

---

## 🔗 External Resources

### API Documentation
- OpenAI: https://platform.openai.com/docs
- Claude: https://docs.anthropic.com/
- DeepSeek: https://platform.deepseek.com/ (Free tier available)
- Kimi: https://platform.moonshot.cn/ (NOT kimi.com/code/console)

### Android References
- Coroutines: https://developer.android.com/kotlin/coroutines
- Jetpack Compose: https://developer.android.com/jetpack/compose
- EncryptedSharedPreferences: https://developer.android.com/topic/security/data

### Project Files
- All planning: `PLANNING.md`
- All tasks: `TODO.md`
- All logs: `logs/session-*.md`

---

## ✅ Agent Checklist (Read Before Every Session)

- [ ] I read this AGENTS.md file completely
- [ ] I read PLANNING.md for technical specs
- [ ] I read TODO.md to find current tasks
- [ ] I read the most recent session log
- [ ] I checked git status (`git status`)
- [ ] I know what to work on next
- [ ] I will write a session log when done
- [ ] I will commit my changes

---

## 🎯 Project Goals Summary

| Goal | Description | Status |
|------|-------------|--------|
| **Dual Mode** | Work offline (TinyLlama) or online (APIs) | ✅ Complete |
| **Persistent Memory** | Remember 1 year of conversations | ✅ Complete |
| **Smart Extraction** | Auto-extract facts from chats | ✅ Complete |
| **Auto-Compaction** | Archive old memories | ✅ Complete |
| **Voice Interface** | Natural voice conversations | ✅ Complete |
| **Branding** | Custom Nilo logo, theme toggle | ✅ Complete |

---

## 📞 Escalation Path

If you're stuck:
1. Check `PLANNING.md` for specification
2. Check `logs/` for similar past issues
3. Review error logs carefully
4. Document the blocker in your session log
5. Ask for help with specific technical question

---

**Remember**: You're building something meaningful — an AI companion that respects privacy and remembers what matters. Take pride in your work!

**Now go read `TODO.md` and find your first task! 🚀**

---

## 🆕 Latest Development Session (March 29, 2026)

### Current Work: Voice Pipeline Improvements

**See `native_android/DEVELOPMENT_LOG.md` for complete details**

**Status**: Multiple fixes in progress, some issues remaining

**Key Issues Addressed:**
1. ✅ **Microphone Gain**: Added 8x software gain for Samsung Tab A7 Lite
2. ✅ **Role Leakage Detection**: Stops LLM when generating system content
3. ✅ **Response Length Control**: Max 200 chars / 3 sentences
4. ⚠️ **TTS/UI Sync**: Attempted fix - needs verification
5. ⚠️ **STT Accuracy**: Model limitation, not code bug

**Git Commit**: `fb18a13`

**For Next Session:**
- Read `native_android/DEVELOPMENT_LOG.md` first
- Test TTS/UI sync fix
- Consider switching to online STT for better accuracy


---

## 🆕 Latest Development Session (March 29, 2026 - Evening Session)

### Current Work: Voice Pipeline Critical Fixes - COMPLETE

**Status**: ✅ Voice mode fully functional. App can recover from ERROR state via X button.

**Git Commit**: `3133877`

---

### Problems Encountered & Solutions

#### **Problem #1: CRITICAL - App stuck in ERROR state, voice mode broken**

**Symptom**: 
- App continuously showing `state: ERROR` in logs
- No speech detection or TTS response
- X button not recovering from ERROR state
- Voice mode completely non-functional

**Root Cause Analysis**:
Race condition in `restartForNewQuestion()` function:
1. `isListeningForNextQuery.set(false)` was called AFTER `stopTTS()` and `stopGeneration()`
2. These functions trigger async cancellation callbacks
3. Callbacks could set `isListeningForNextQuery = true` AFTER the reset
4. `transitionToState(PipelineState.LISTENING)` would then be BLOCKED by the guard in `transitionToState()`
5. App stuck in ERROR state permanently

**Attempted Solutions**:
1. ✅ **FINAL FIX** (commit `3133877`): Move `isListeningForNextQuery.set(false)` to BEGINNING of function, before any stop calls
2. ✅ Removed duplicate reset at end of function (was redundant)

**Code Change** (VoicePipelineManager.kt line ~1262):
```kotlin
fun restartForNewQuestion() {
    Log.i(TAG, "🔄 Restarting voice mode for new question (HARD RESET)")
    
    // CRITICAL FIX: Reset isListeningForNextQuery FIRST before stopping anything
    // This prevents race conditions where stopTTS/stopGeneration callbacks
    // set isListeningForNextQuery=true AFTER we reset it, blocking the transition
    isListeningForNextQuery.set(false)  // <-- MOVED HERE (was after stop calls)
    
    // Stop any ongoing processes
    stopTTS()
    stopGeneration()
    // ... rest of function
}
```

**Verification**:
- ✅ App initializes correctly (IDLE → LISTENING)
- ✅ Speech detected (LISTENING → SPEECH_DETECTED)
- ✅ Transcription works (SPEECH_DETECTED → TRANSCRIBING → LISTENING)
- ✅ X button visible during THINKING, SPEAKING, and ERROR states
- ✅ X button can recover from ERROR state back to LISTENING

---

#### **Problem #2: Responses ending mid-word or at numbered lists**

**Symptom**: Responses were cutting off at exactly 200 chars even if mid-word, or stopping at "1." which looked incomplete.

**Root Cause**: `stopGeneration()` was called immediately when char limit reached, regardless of sentence boundaries.

**Solution** (commit `f58d32c`):
- Check if response ends with complete sentence (., !, ?) before stopping
- Don't treat numbered lists ("1.", "2.") as sentence endings
- Continue collecting tokens until sentence boundary is reached

**Code**:
```kotlin
val endsWithPunctuation = lastChar == '.' || lastChar == '!' || lastChar == '?'
val endsWithNumberedList = trimmedResponse.matches(Regex(".*\\d\\.$"))
val endsWithCompleteSentence = endsWithPunctuation && !endsWithNumberedList

if (currentSentenceCount > 0 && endsWithCompleteSentence) {
    // Safe to stop - we have complete sentences and end properly
    stopGeneration()
    transitionToState(PipelineState.LISTENING)
} else {
    // Don't stop yet - continue until we get a complete sentence
}
```

---

#### **Problem #3: X button not visible during THINKING state**

**Symptom**: Couldn't interrupt the assistant while it was "thinking" (generating response).

**Solution** (commit `88a45d9`):
- Updated `canStop` condition in MainActivity.kt to include THINKING state
- Also added ERROR state for voice mode recovery

**Code** (MainActivity.kt):
```kotlin
val isThinking = pipelineState == VoicePipelineManager.Companion.PipelineState.THINKING
val isSpeaking = pipelineState == VoicePipelineManager.Companion.PipelineState.SPEAKING
val isError = pipelineState == VoicePipelineManager.Companion.PipelineState.ERROR
val canStop = isGenerating || isThinking || isSpeaking || (isVoiceModeActive && isError)
```

---

### Today's Complete Activity Log

**Session Date**: March 29, 2026 (Evening)
**Device**: Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)
**Build Type**: Debug APK

#### Activities Performed:
1. ✅ Investigated ERROR state stuck issue
2. ✅ Analyzed race condition in `restartForNewQuestion()`
3. ✅ Identified `isListeningForNextQuery` timing issue
4. ✅ Applied fix: Moved reset to beginning of function
5. ✅ Built and deployed debug APK
6. ✅ Tested app functionality
7. ✅ Verified X button recovery works
8. ✅ Committed and pushed to GitHub (commit `3133877`)

#### Key Files Modified:
- `native_android/app/src/main/java/com/projekt_x/studybuddy/bridge/VoicePipelineManager.kt`

#### Key Debugging Commands Used:
```bash
# Build and deploy
./gradlew :app:assembleDebug
adb install -r app-debug.apk

# Monitor logs
adb logcat -d -s VoicePipelineManager
adb logcat -d --pid=$(adb shell pidof com.projekt_x.studybuddy)

# Check state transitions
adb logcat -d | grep -E "(State changed|ERROR|restartForNew)"
```

---

### Voice Pipeline State Machine Reference

**States (in order)**:
1. `IDLE` - Pipeline not running
2. `LISTENING` - Waiting for speech (mic active)
3. `SPEECH_DETECTED` - VAD detected speech start
4. `TRANSCRIBING` - Processing audio with Whisper STT
5. `THINKING` - LLM generating response
6. `SPEAKING` - TTS playing response
7. `ERROR` - Something went wrong

**Important Guards**:
- `isListeningForNextQuery`: When true, blocks transitions to THINKING/SPEAKING
- Purpose: Prevent race conditions when returning to LISTENING after response
- Must be reset to false before calling `transitionToState(LISTENING)`

---

### For Next AI Agent Session

**Current Status**: 
- ✅ Voice mode fully functional
- ✅ Can recover from ERROR state via X button
- ✅ Responses complete at sentence boundaries
- ⚠️ May need future improvements to TTS quality or STT accuracy

**Potential Future Work**:
1. Consider switching from Whisper tiny to base model for better STT accuracy
2. Consider online STT (Google Speech-to-Text API) as fallback
3. Add more sophisticated hallucination filtering
4. Optimize response length limits based on query complexity

**Important Context**:
- The `isListeningForNextQuery` atomic boolean is CRITICAL for state machine integrity
- Always reset it BEFORE any async operations that might trigger callbacks
- The `transitionToState()` function has a guard that blocks transitions when this flag is true

---

### Session Archive

**Previous Sessions**:
- See `logs/session-2026-03-23.md` for earlier voice pipeline work
- See `logs/session-2026-03-21.md` for logo/UI refresh

**For Next Session**:
1. Read this updated AGENTS.md
2. Check TODO.md for current tasks
3. Review any new issues in GitHub
4. Test voice mode thoroughly before making changes

