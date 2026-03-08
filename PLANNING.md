# Hey-Nilo: Master Planning & Implementation Document
**Version**: 2.0 (Synchronized with Implementation Guide)  
**Status**: Ready for Implementation  
**Target Device**: Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)

---

## ⚠️ Critical Implementation Notes

### Constraints (Non-Negotiable)
| Constraint | Limit | Why |
|------------|-------|-----|
| Memory block in prompt | ≤ 300 tokens | Preserve TinyLlama 2048 context window |
| Extraction response tokens | ≤ 150 tokens | JSON only, no prose |
| Extraction call | Background only | Never delay voice pipeline |
| TinyLlama context window | 2048 tokens | System + memory + chat + response |
| **NO vector DB** | — | 3GB RAM cannot handle it with TinyLlama loaded |
| **NO response cache** | — | Skip Phase 1-5, add later if needed |
| API keys | EncryptedSharedPreferences only | Never plain text |
| File I/O | Dispatchers.IO only | Never on main thread |

### What NOT to Touch (Existing Working Code)
- `VADBridge.kt` / `VADProcessor` — Silero VAD pipeline
- `whisper_jni.cpp` / `RealSTTBridge.kt` — STT pipeline
- `KokoroTTSBridge.kt` / `SherpaTTSBridge.kt` — TTS pipeline
- `CMakeLists.txt` — NDK build config
- `SimpleAudioRecorder.kt` — Audio recording

**Memory and Online mode are new layers ON TOP of the existing voice pipeline.**

---

## 🎯 Project Vision

**Hey-Nilo** is an intelligent AI companion with dual-mode architecture:
- **Offline Mode**: Local TinyLlama 1.1B for memory operations, history access, and basic assistance without internet
- **Online Mode**: Cloud LLMs (OpenAI, Claude, DeepSeek, Kimi) for complex reasoning and tasks

Both modes share a unified **local persistent memory system** with 1-year rolling retention for conversations, permanent storage for personal information.

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    USER INTERFACE LAYER                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Chat UI   │  │  Voice UI   │  │    Memory Manager UI    │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MODE CONTROLLER                               │
│              (Mode Detection, Switching, Fallback)               │
│                                                                  │
│   ┌─────────────────────┐        ┌─────────────────────┐        │
│   │    OFFLINE MODE     │◄──────►│     ONLINE MODE     │        │
│   │   (TinyLlama 1.1B)  │        │   (API Providers)   │        │
│   │                     │        │                     │        │
│   │ • Memory CRUD       │        │ • GPT-4o-mini       │        │
│   │ • History Access    │        │ • Claude 3.5        │        │
│   │ • Local Reasoning   │        │ • DeepSeek          │        │
│   │ • No internet       │        │ • Kimi              │        │
│   └─────────────────────┘        └─────────────────────┘        │
│            │                              │                      │
│            └──────────────┬───────────────┘                      │
│                           ▼                                      │
│              ┌─────────────────────┐                             │
│              │  UNIFIED MEMORY     │                             │
│              │  (Local Storage)    │                             │
│              └─────────────────────┘                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              UNIFIED MEMORY SYSTEM (Local Storage)               │
│                                                                  │
│  /data/data/com.projekt_x.studybuddy/files/memory/              │
│  ├── core/                                                       │
│  │   ├── user_profile.json        ← Permanent (Personal Info)   │
│  │   ├── relationships.json       ← Permanent (Family/Friends)  │
│  │   └── medical.json             ← Permanent, Encrypted        │
│  │                                                                │
│  ├── conversations/                                              │
│  │   └── YYYY/                                                   │
│  │       └── MM-MonthName/                                       │
│  │           └── YYYY-MM-DD_HH-mm.md  ← One file per session    │
│  │                                                                │
│  ├── conversations/archive/                                      │
│  │   ├── 2025-Q1-summary.md       ← Auto-generated              │
│  │   └── 2024-summary.md                                         │
│  │                                                                │
│  ├── work/                                                       │
│  │   ├── active/                                                 │
│  │   │   ├── projects.json                                       │
│  │   │   ├── reminders.json                                      │
│  │   │   └── goals.md                                            │
│  │   └── archive/                                                │
│  │                                                                │
│  └── system/                                                     │
│      ├── memory-stats.json                                       │
│      └── compaction-log.json                                     │
│                                                                  │
│  ⚠️ RETENTION POLICY:                                            │
│  • Core (Personal Info): FOREVER                                │
│  • Conversations: 1 YEAR (auto-compact to summaries)            │
│  • Work/Activities: 1 YEAR (then archive)                       │
│                                                                  │
│  ❌ NO cache/online-responses/ (skip Phase 1-5)                 │
│  ❌ NO cache/embeddings/ (3GB RAM constraint)                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Dual Mode System

### Mode Definition

```kotlin
sealed class AppMode {
    abstract val name: String
    
    object Offline : AppMode() {
        override val name = "TinyLlama (Offline)"
    }
    
    data class Online(
        val provider: ApiProvider,
        val model: String
    ) : AppMode() {
        override val name = "${provider.displayName} (Online)"
    }
}

enum class ApiProvider(val displayName: String) {
    OPENAI("OpenAI"),
    CLAUDE("Claude"),
    DEEPSEEK("DeepSeek"),
    KIMI("Kimi")
}
```

### Provider Configuration (UPDATED - Correct Models)

```kotlin
data class ProviderConfig(
    val provider: ApiProvider,
    val apiKey: String,           // Encrypted storage
    val baseUrl: String,
    val defaultModel: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f
)

object ProviderDefaults {
    val OPENAI = ProviderConfig(
        provider = ApiProvider.OPENAI,
        apiKey = "",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini"  // ✅ Correct
    )
    
    val CLAUDE = ProviderConfig(
        provider = ApiProvider.CLAUDE,
        apiKey = "",
        baseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-3-5-haiku-20241022"  // ✅ Correct (not outdated)
    )
    
    val DEEPSEEK = ProviderConfig(
        provider = ApiProvider.DEEPSEEK,
        apiKey = "",
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat"
    )
    
    val KIMI = ProviderConfig(
        provider = ApiProvider.KIMI,
        apiKey = "",
        baseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k"
    )
}
```

### API Format Reference

| Provider | Model | API Format | Special Header |
|----------|-------|------------|----------------|
| OpenAI | `gpt-4o-mini` | OpenAI | — |
| Claude | `claude-3-5-haiku-20241022` | Anthropic | `anthropic-version: 2023-06-01` |
| DeepSeek | `deepseek-chat` | OpenAI-compatible | — |
| Kimi | `moonshot-v1-8k` | OpenAI-compatible | — |

**OpenAI-compatible request:**
```json
{
  "model": "gpt-4o-mini",
  "messages": [
    {"role": "system", "content": "{systemPrompt}"},
    {"role": "user", "content": "{userMessage}"}
  ],
  "max_tokens": 512,
  "stream": false
}
```

**Anthropic (Claude) request:**
```json
{
  "model": "claude-3-5-haiku-20241022",
  "max_tokens": 512,
  "system": "{systemPrompt}",
  "messages": [
    {"role": "user", "content": "{userMessage}"}
  ]
}
```

### Mode Capabilities Matrix

| Feature | Offline (TinyLlama) | Online (API) |
|---------|---------------------|--------------|
| **Memory Read** | ✅ Full Access | ✅ Full Access |
| **Memory Write** | ✅ Full Access | ✅ Full Access |
| **Memory Edit** | ✅ Full Access | ✅ Full Access |
| **Memory Delete** | ✅ Full Access | ✅ Full Access |
| **Complex Reasoning** | ⚠️ Limited | ✅ Excellent |
| **Creative Writing** | ⚠️ Basic | ✅ Excellent |
| **Code Generation** | ⚠️ Basic | ✅ Excellent |
| **Web Search** | ❌ No | ✅ Possible |
| **Image Analysis** | ❌ No | ✅ GPT-4V |
| **Response Speed** | ✅ Fast (local) | ⚠️ Network dependent |
| **Privacy** | ✅ 100% Private | ⚠️ Sent to API |
| **Works Offline** | ✅ Yes | ❌ No |

---

## 🧠 Memory System Design

### Memory Types & Retention

```
┌─────────────────────────────────────────────────────────────────┐
│                    MEMORY LIFECYCLE                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  TIER 1: PERMANENT (Forever)                                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ • User's name, age, birthdate                           │    │
│  │ • Family members (mom, dad, siblings, spouse, kids)     │    │
│  │ • Home address, workplace                               │    │
│  │ • Core preferences (diet, allergies, likes/dislikes)    │    │
│  │ • Important relationships                               │    │
│  │ • Medical conditions (if shared) - ENCRYPTED            │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│                              ▼                                   │
│  TIER 2: EPHEMERAL (1 Year Rolling)                             │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ • Daily conversations                                   │    │
│  │ • Temporary tasks & reminders                           │    │
│  │ • Work projects (active)                                │    │
│  │ • Travel plans                                          │    │
│  │ • Event planning                                        │    │
│  │ • Recent interests/hobbies                              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│                              ▼ (After 1 year)                   │
│  TIER 3: COMPACTED (Archive)                                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ • Summarized themes from old conversations              │    │
│  │ • Key facts extracted (upgraded to Tier 1 if important) │    │
│  │ • Deleted: Raw conversation details                     │    │
│  │ • Deleted: Temporary context                            │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### File Structure (FINAL)

```
memory/
├── core/                                    # PERMANENT
│   ├── user_profile.json
│   ├── relationships.json
│   └── medical.json (encrypted)
│
├── conversations/                           # 1 YEAR RETENTION
│   └── YYYY/
│       └── MM-MonthName/
│           └── YYYY-MM-DD_HH-mm.md
│
├── conversations/archive/
│   ├── 2025-Q1-summary.md
│   └── 2024-summary.md
│
├── work/                                    # 1 YEAR RETENTION
│   ├── active/
│   │   ├── projects.json
│   │   ├── reminders.json
│   │   └── goals.md
│   └── archive/
│       └── 2024-work-summary.md
│
└── system/
    ├── memory-stats.json
    └── compaction-log.json

❌ NO cache/online-responses/ - Skip until Phase 6
❌ NO cache/embeddings/ - 3GB RAM constraint
```

### File Schemas (EXACT)

**core/user_profile.json:**
```json
{
  "version": 1,
  "last_updated": "2025-03-08T14:32:00Z",
  "identity": {
    "name": null,
    "preferred_name": null,
    "birthdate": null,
    "age": null,
    "gender": null
  },
  "contact": {
    "address": null,
    "city": null,
    "country": null,
    "phone": null
  },
  "occupation": {
    "title": null,
    "company": null,
    "industry": null
  },
  "preferences": {
    "communication_style": "friendly",
    "food": [],
    "music": [],
    "hobbies": []
  },
  "facts": []
}
```

**core/relationships.json:**
```json
{
  "version": 1,
  "last_updated": "2025-03-08T14:32:00Z",
  "family": [],
  "friends": [],
  "colleagues": [],
  "important_people": []
}
```

Relationship entry:
```json
{
  "id": "rel_001",
  "relation": "mother",
  "name": "Priya",
  "contact": null,
  "notes": "Lives in Mumbai",
  "birthdate": null
}
```

**work/active/reminders.json:**
```json
{
  "version": 1,
  "last_updated": "2025-03-08T14:32:00Z",
  "reminders": [
    {
      "id": "rem_001",
      "type": "call",
      "text": "Call mom about dinner",
      "target_person": "Priya (mom)",
      "due_date": null,
      "priority": "normal",
      "status": "pending",
      "created_at": "2025-03-08T14:32:00Z"
    }
  ]
}
```

Reminder types: `call`, `alarm`, `message_whatsapp`, `message_instagram`, `message_sms`, `reminder`, `note`

**conversations/YYYY/MM-Month/YYYY-MM-DD_HH-mm.md:**
```markdown
# Conversation — 2025-03-08 at 14:32
**Mode**: Online (GPT-4o-mini)
**Duration**: ~4 minutes

## Summary
User asked about weekend plans and set a reminder to call mom on Sunday.

## Key Facts Extracted
- [PROFILE] User likes Italian food
- [REMINDER] Call mom on Sunday

## Raw Exchange
User: What should I do this weekend?
Assistant: Here are some ideas...
User: Any good Italian places?
Assistant: ...
```

### Storage Limits

| Memory Type | Max Size | Format | Retention | Auto-Action |
|-------------|----------|--------|-----------|-------------|
| Core Profile | 100 KB | JSON | Forever | Never delete |
| Relationships | 500 KB | JSON | Forever | Never delete |
| Conversations | 100 MB total | Markdown | 1 Year | Compact to summary after 365 days |
| Work/Active | 50 MB | JSON/Markdown | 1 Year | Archive after 1 year |
| System | 1 MB | JSON | Forever | Managed internally |
| **Total** | **~350 MB** | — | — | — |

### Memory Operations Interface

```kotlin
class MemoryManager(private val context: Context) {
    
    // Initialization
    suspend fun initialize()
    
    // Context Building (≤300 tokens)
    fun buildContextBlock(maxTokens: Int = 300): String
    
    // Extraction & Saving (Background, non-blocking)
    suspend fun extractAndSave(
        userMessage: String, 
        llmResponse: String, 
        mode: String
    )
    
    // Session Management
    suspend fun saveConversationSession(
        turns: List<Pair<String, String>>, 
        mode: String
    )
    
    // Profile CRUD
    suspend fun getUserProfile(): UserProfile
    suspend fun updateProfileField(field: String, value: Any)
    suspend fun addFact(fact: String)
    
    // Relationships
    suspend fun addRelationship(relation: Relationship)
    suspend fun updateRelationship(id: String, updates: RelationshipUpdates)
    suspend fun deleteRelationship(id: String)
    suspend fun getRelationship(id: String): Relationship?
    
    // Reminders
    suspend fun addReminder(reminder: Reminder)
    suspend fun completeReminder(id: String)
    suspend fun getPendingReminders(): List<Reminder>
    
    // Maintenance
    suspend fun runCompactionIfNeeded()
    suspend fun getStats(): MemoryStats
}
```

---

## 🤖 LLM Provider Architecture

### LLMProvider Interface

```kotlin
interface LLMProvider {
    val name: String
    val isAvailable: Boolean

    suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 512,
        onToken: ((String) -> Unit)? = null
    ): LLMResponse

    suspend fun healthCheck(): Boolean
}

data class ChatMessage(
    val role: String,
    val content: String
)

data class LLMResponse(
    val text: String,
    val tokensUsed: Int,
    val provider: String,
    val error: String? = null
)
```

### OfflineLLMProvider (TinyLlama)

```kotlin
class OfflineLLMProvider(private val llamaBridge: LlamaBridge) : LLMProvider {
    override val name = "TinyLlama (Offline)"
    override val isAvailable get() = llamaBridge.isLoaded()

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): LLMResponse {
        // TinyLlama chat template (CRITICAL)
        val prompt = buildTinyLlamaPrompt(systemPrompt, messages)
        return llamaBridge.infer(prompt, maxTokens, onToken)
    }
    
    private fun buildTinyLlamaPrompt(system: String, messages: List<ChatMessage>): String {
        val lastUserMsg = messages.last { it.role == "user" }.content
        return """<|system|>
$system</s>
<|user|>
$lastUserMsg</s>
<|assistant|>
"""
    }
}
```

### OnlineLLMProvider

```kotlin
class OnlineLLMProvider(private val config: ProviderConfig) : LLMProvider {

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): LLMResponse {
        return when (config.provider) {
            ApiProvider.CLAUDE -> callAnthropicApi(systemPrompt, messages, maxTokens)
            else -> callOpenAICompatibleApi(systemPrompt, messages, maxTokens)
        }
    }
    
    // Use OkHttp (simpler than Retrofit)
    private val client = OkHttpClient()
}
```

---

## 🔐 Security

### ApiKeyStore

```kotlin
class ApiKeyStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "hey_nilo_api_keys",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveKey(provider: ApiProvider, key: String)
    fun getKey(provider: ApiProvider): String?
    fun deleteKey(provider: ApiProvider)
    fun hasKey(provider: ApiProvider): Boolean
}
```

**Dependency:**
```gradle
implementation "androidx.security:security-crypto:1.1.0-alpha06"
```

---

## 📝 Context Injection

### Context Block Format (EXACT)

```
[MEMORY]
User: {name}. {age if known}. {city if known}.
Facts: {up to 3 most recent personal facts}.
People: {up to 3 relationships}.
Pending: {up to 3 reminders}.
Last session ({date}): {one-line summary}.
[/MEMORY]
```

**Example:**
```
[MEMORY]
User: Kali. Lives in Bangalore, IST timezone.
Facts: Prefers voice over typing. Uses Samsung Tab A7 Lite.
People: Mom: Priya (Mumbai). Friend: Rohan.
Pending: Call mom · Q2 planning deadline Apr 15.
Last session (Mar 7): Discussed weekend plans, asked about Italian restaurants.
[/MEMORY]
```

**Rules:**
- Max 300 tokens
- Omit empty fields entirely
- Never write "null" or "unknown"

### Integration in VoicePipelineManager

```kotlin
private fun processWithLLM(transcription: String) {
    scope.launch {
        // 1. Build memory context block
        val memoryBlock = memoryManager.buildContextBlock(maxTokens = 300)

        // 2. Build system prompt with memory
        val systemPrompt = """
$memoryBlock

You are Panda, a friendly personal AI assistant. Be concise and conversational.
Speak naturally — your response will be read aloud.
        """.trimIndent()

        // 3. Use active provider
        val provider = modeController.getActiveProvider()
        val response = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(ChatMessage("user", transcription)),
            maxTokens = 256,
            onToken = { token -> /* stream to TTS */ }
        )

        // 4. Extract memory in background
        scope.launch(Dispatchers.IO) {
            memoryManager.extractAndSave(transcription, response.text, provider.name)
        }
    }
}
```

---

## 🧩 Memory Extraction

### MemoryExtractionHelper

```kotlin
object MemoryExtractionHelper {
    
    const val EXTRACTION_PROMPT = """
Extract memory-worthy facts from this conversation. Output ONLY valid JSON. No explanation.
Format:
{"profile":[],"relationships":[],"reminders":[],"nothing":true/false}

profile items: {"field":"name/age/city/fact/preference","value":"..."}
relationships items: {"relation":"mother/friend/...","name":"...","note":"..."}
reminders items: {"type":"call/alarm/message_whatsapp/reminder","text":"...","target":"..."}
    """.trimIndent()

    fun buildExtractionMessage(userMsg: String, llmResponse: String): String {
        return """
Conversation:
User: $userMsg
Assistant: $llmResponse

Extract facts.
        """.trimIndent()
    }
}
```

### Extraction Rules

| Mode | Extraction Method |
|------|-------------------|
| **Offline** | Use `LlamaBridge`, `maxTokens = 150` |
| **Online** | Use SAME online provider |

**Error Handling:**
- Parse JSON safely
- Wrap in try-catch
- If parse fails → log and skip

---

## 📋 Implementation Order (Strict)

### Phase 1 — Memory Foundation (Weeks 1-2)
1. Create `MemoryManager.kt` with file I/O and `buildContextBlock()`
2. Create data models: `UserProfile`, `Relationship`, `Reminder`, `MemoryStats`
3. Test: Write unit test that creates profile JSON, reads it back

### Phase 2 — LLM Abstraction (Weeks 3-4)
4. Create `LLMProvider` interface
5. Create `OfflineLLMProvider` wrapping existing `LlamaBridge`
6. Modify `LlamaBridge` to accept dynamic system prompt
7. Test: Verify offline voice still works

### Phase 3 — Online Mode (Weeks 5-6)
8. Create `ApiKeyStore` with encrypted storage
9. Create `OnlineLLMProvider` — implement OpenAI first
10. Create `ModeController`
11. Add mode switch button to UI
12. Test: Send message via online mode

### Phase 4 — Memory Extraction (Weeks 7-8)
13. Create `MemoryExtractionHelper`
14. Wire extraction into `VoicePipelineManager`
15. Wire session save into `stopVoiceConversation()`
16. Add Claude, DeepSeek, Kimi providers

### Phase 5 — Compaction (Weeks 9-10)
17. Implement `runCompactionIfNeeded()`
18. Trigger on app startup (background)

### Phase 6 — UI Polish (Weeks 11-12)
19. Memory Manager screen
20. API key setup screen
21. Provider selector in settings

---

## 📊 Performance Targets

| Operation | Target Time |
|-----------|-------------|
| Save conversation | < 100ms |
| Search memory | < 500ms (SQLite FTS5) |
| Build context | < 50ms (from RAM cache) |
| Mode switch | < 1 second |
| Compaction job | < 5 seconds (background) |

---

## 📄 Document History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-03-08 | Initial planning |
| 2.0 | 2025-03-08 | Synchronized with Implementation Guide - fixed model names, removed cache/embeddings, added exact schemas and context format |

---

*This document is the single source of truth for Hey-Nilo implementation.*
