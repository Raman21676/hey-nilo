# Hey-Nilo: AI Agent Onboarding Guide

**For**: AI Agents working on the Hey-Nilo project  
**Purpose**: Complete project context and working guidelines  
**Last Updated**: March 17, 2026  

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
4. **Last `logs/session-YYYY-MM-DD.md`** - See what was done in previous session

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

## 🆕 Recent Changes (March 17, 2026)

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
        │   └── ui/                  ← UI screens
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
| **Dual Mode** | Work offline (TinyLlama) or online (APIs) | 🔴 Not Started |
| **Persistent Memory** | Remember 1 year of conversations | 🔴 Not Started |
| **Smart Extraction** | Auto-extract facts from chats | 🔴 Not Started |
| **Auto-Compaction** | Archive old memories | 🔴 Not Started |
| **Voice Interface** | Natural voice conversations | ✅ Already Works |

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
