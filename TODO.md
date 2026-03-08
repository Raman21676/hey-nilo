# Hey-Nilo: Implementation TODO

**Project**: Android AI Voice Assistant with Persistent Memory & Dual Mode  
**Target**: Samsung Galaxy Tab A7 Lite (3GB RAM, Android 14)  
**Last Updated**: March 8, 2025  

> **Legend**:  
> 🔴 Critical Path (blocking other tasks)  
> 🟡 Important (can parallelize)  
> 🟢 Nice to have (can skip if needed)  
> ⏱️ Estimated time  

---

## 📊 Project Status Overview

| Phase | Status | Progress | Estimated Completion |
|-------|--------|----------|---------------------|
| Phase 1: Memory Foundation | ✅ Complete | 100% | Week 1-2 |
| Phase 2: LLM Abstraction | ⏸️ Blocked | 0% | Week 3-4 |
| Phase 3: Online Mode | ⏸️ Blocked | 0% | Week 5-6 |
| Phase 4: Memory Extraction | ⏸️ Blocked | 0% | Week 7-8 |
| Phase 5: Compaction | ⏸️ Blocked | 0% | Week 9-10 |
| Phase 6: UI Polish | ⏸️ Blocked | 0% | Week 11-12 |

---

## 🔴 Phase 1: Memory Foundation (Weeks 1-2)
**Goal**: Create the core memory infrastructure with file I/O and context building  
**Dependencies**: None (can start immediately)  

### Job 1.1: Data Models & Schemas ✅ COMPLETE ⏱️ 4h
- [x] Create `UserProfile` data class with all fields
- [x] Create `Relationship` data class
- [x] Create `Reminder` data class with all reminder types
- [x] Create `MemoryStats` data class
- [x] Create `ConversationSummary` data class
- [x] Define JSON serialization using Gson
- [x] Write unit tests for model serialization/deserialization

**Files created:**
- `model/memory/UserProfile.kt` (130 lines)
- `model/memory/Relationship.kt` (200 lines)
- `model/memory/Reminder.kt` (260 lines)
- `model/memory/MemoryStats.kt` (170 lines)
- `model/memory/Conversation.kt` (250 lines)
- `model/memory/MemoryDefaults.kt` (210 lines)

### Job 1.2: File System Setup ✅ COMPLETE ⏱️ 3h
- [x] Create memory directory structure on app startup
- [x] Implement folder creation: `core/`, `conversations/YYYY/MM-Month/`, `work/active/`, `system/`
- [x] Create default `user_profile.json` if doesn't exist
- [x] Create default `relationships.json` if doesn't exist
- [x] Create default `reminders.json` if doesn't exist
- [x] Verify folder permissions and storage availability
- [x] Add error handling for storage full scenarios

**Files created:**
- `bridge/filesystem/FileSystemManager.kt` (380 lines)
- `bridge/filesystem/MemoryInitialization.kt` (75 lines)

### Job 1.3: MemoryManager Core ✅ COMPLETE ⏱️ 8h
- [x] Implement `MemoryManager.initialize()` - load files into RAM cache
- [x] Implement `buildContextBlock(maxTokens: Int)` - generate ≤300 token context
- [x] Implement `getUserProfile()` - read from cache
- [x] Implement `updateProfileField(field, value)` - update and persist
- [x] Implement `addFact(fact)` - append to profile facts
- [x] Implement `getStats()` - calculate storage usage
- [x] Add thread-safety with coroutines (Dispatchers.IO)
- [x] Add comprehensive logging for debugging

**Files created:**
- `bridge/MemoryManager.kt` (535 lines)

### Job 1.4: Profile Operations ✅ COMPLETE ⏱️ 4h
- [x] Implement `addRelationship(relationship)`
- [x] Implement `updateRelationship(id, updates)`
- [x] Implement `deleteRelationship(id)`
- [x] Implement `getRelationship(id)`
- [x] Implement `getAllRelationships()`
- [x] Implement `searchRelationships(query)` - simple text search
- [x] Validate JSON schema on read/write
- [x] Handle edge cases (duplicate IDs, missing fields)

**Note:** All implemented in `MemoryManager.kt`

### Job 1.5: Reminder Operations ✅ COMPLETE ⏱️ 3h
- [x] Implement `addReminder(reminder)`
- [x] Implement `completeReminder(id)` - mark status as completed
- [x] Implement `deleteReminder(id)`
- [x] Implement `getPendingReminders()` - filter by status
- [x] Implement `getOverdueReminders()` - check due dates
- [x] Implement `updateReminder(id, updates)`

**Note:** All implemented in `MemoryManager.kt`

### Job 1.6: Testing & Validation ✅ COMPLETE ⏱️ 4h
- [x] Unit test: Create profile, read back, verify fields
- [x] Unit test: Add relationships, verify persistence
- [x] Unit test: Build context block, verify token count ≤ 300
- [x] Unit test: Reminder CRUD operations
- [x] Integration test: Full initialization flow
- [x] Test storage limit handling (hasAvailableSpace check)
- [ ] Test corruption recovery (malformed JSON) - deferred to Phase 5

**Files created:**
- `test/MemoryManagerTest.kt` (175 lines)

### Job 1.7: VoicePipelineManager Integration ✅ COMPLETE ⏱️ 4h
- [x] Add `MemoryManager` to `VoicePipelineManager` constructor
- [x] Call `memoryManager.initialize()` in pipeline setup
- [x] Inject `buildContextBlock()` into LLM prompts
- [x] Save conversations to memory after each exchange
- [x] Wire up MainActivity to initialize memory system

**Files modified:**
- `bridge/VoicePipelineManager.kt` - Added memory context injection
- `bridge/MemoryManager.kt` - Added saveConversationExchange()
- `bridge/FileSystemManager.kt` - Added appendConversationExchange()
- `MainActivity.kt` - Initialize memory system, pass to VoicePipelineManager
- `app/build.gradle` - Added Gson dependency

### ✅ PHASE 1 COMPLETE

**Deliverable**: Memory system fully integrated with voice pipeline

**Features Working:**
- Memory directory structure created on startup
- User profile stored and retrieved from JSON
- Relationships CRUD operations
- Reminders CRUD operations  
- Context block generation (≤300 tokens) injected into LLM prompts
- Conversations saved to dated markdown files
- RAM cache for fast reads, disk persistence for durability

**Next**: Phase 2 - LLM Abstraction Layer

---

## 🔴 Phase 2: LLM Abstraction (Weeks 3-4)
**Goal**: Create provider interface and offline implementation  
**Dependencies**: Phase 1 complete  

### Job 2.1: LLMProvider Interface ✅ COMPLETE ⏱️ 2h
- [x] Define `LLMProvider` interface with all methods
- [x] Define `ChatMessage` data class
- [x] Define `LLMResponse` data class with error handling
- [x] Define `AppMode` sealed class
- [x] Define `ApiProvider` enum

**Files created:**
- `bridge/llm/LLMModels.kt` - Data classes (ChatMessage, LLMResponse, CompletionRequest, etc.)
- `bridge/llm/LLMProvider.kt` - Interface, factory, and manager

### Job 2.2: OfflineLLMProvider ✅ COMPLETE ⏱️ 4h
- [x] Create `OfflineLLMProvider` implementing `LLMProvider`
- [x] Wrap existing `LlamaBridge` functionality
- [x] Implement TinyLlama chat template formatting
- [x] Map `LLMProvider.complete()` to existing inference method
- [x] Handle streaming callbacks
- [x] Add availability check (model loaded)

**Template format implemented:**
```
<|system|>
{systemPrompt}</s>
<|user|>
{userMessage}</s>
<|assistant|>
```

**Files created:**
- `bridge/llm/OfflineLLMProvider.kt`

### Job 2.3: Modify LlamaBridge ⏱️ 3h
- [ ] Update inference method to accept dynamic system prompt
- [ ] Change from hardcoded system prompt to parameter
- [ ] Maintain backward compatibility (default parameter)
- [ ] Test: Verify existing voice pipeline still works
- [ ] Verify TinyLlama chat template is correct

**Files to modify:**
- `bridge/LlamaBridge.kt`

### Job 2.4: VoicePipelineManager Integration ⏱️ 4h
- [ ] Inject memory context block before each LLM call
- [ ] Build system prompt with `[MEMORY]` block
- [ ] Use `OfflineLLMProvider` instead of direct `LlamaBridge` calls
- [ ] Test: Verify offline voice mode still works end-to-end
- [ ] Verify context injection doesn't break token limits
- [ ] Measure latency impact (should be < 50ms)

**Files to modify:**
- `bridge/VoicePipelineManager.kt`

### Job 2.5: Testing ⏱️ 3h
- [ ] Test: Voice mode with memory context
- [ ] Test: Memory context appears in responses
- [ ] Test: Fallback when memory manager fails
- [ ] Test: Large memory context doesn't overflow context window
- [ ] Performance test: Measure latency with/without memory

**Deliverable**: Offline mode works with memory injection, no regression in voice pipeline

---

## 🔴 Phase 3: Online Mode (Weeks 5-6)
**Goal**: Implement API providers and mode switching  
**Dependencies**: Phase 2 complete  

### Job 3.1: Secure API Key Storage ⏱️ 3h
- [ ] Add dependency: `androidx.security:security-crypto:1.1.0-alpha06`
- [ ] Create `ApiKeyStore` class
- [ ] Implement `saveKey(provider, key)` with encryption
- [ ] Implement `getKey(provider)` with decryption
- [ ] Implement `hasKey(provider)` check
- [ ] Implement `deleteKey(provider)`
- [ ] Test: Verify keys are encrypted in SharedPreferences

**Files to create:**
- `bridge/ApiKeyStore.kt`

### Job 3.2: OnlineLLMProvider - OpenAI ⏱️ 6h
- [ ] Create `OnlineLLMProvider` class
- [ ] Implement OpenAI API client using OkHttp
- [ ] Implement request formatting for OpenAI
- [ ] Implement response parsing
- [ ] Add error handling (rate limits, invalid keys, network)
- [ ] Support streaming responses
- [ ] Add timeout configuration (10s connect, 30s read)
- [ ] Test with curl/Postman first, then code

**Files to create:**
- `bridge/OnlineLLMProvider.kt`

### Job 3.3: ModeController ⏱️ 4h
- [ ] Create `ModeController` class
- [ ] Implement `switchToOffline()`
- [ ] Implement `switchToOnline(provider)` with API key validation
- [ ] Implement `getActiveProvider()` - returns current LLMProvider
- [ ] Implement `isOnline()` check
- [ ] Implement `detectAndSetBestMode()` - auto-select based on connectivity
- [ ] Persist last used mode
- [ ] Handle mode switch failures gracefully

**Files to create:**
- `bridge/ModeController.kt`

### Job 3.4: Claude Provider Support ⏱️ 3h
- [ ] Implement Anthropic API format (different from OpenAI)
- [ ] Add `anthropic-version` header
- [ ] Handle Claude's different request/response structure
- [ ] Test with Claude API
- [ ] Add to provider selector

### Job 3.5: DeepSeek & Kimi Providers ⏱️ 3h
- [ ] Add DeepSeek configuration (OpenAI-compatible)
- [ ] Add Kimi configuration (OpenAI-compatible)
- [ ] Test both providers
- [ ] Document pricing differences

### Job 3.6: Mode Switch UI ⏱️ 4h
- [ ] Create mode indicator component (top bar)
- [ ] Add tap-to-switch functionality
- [ ] Create mode selection dialog
- [ ] Show current mode status (Online/Offline)
- [ ] Add provider name display when online
- [ ] Add visual distinction (colors: green offline, blue online)
- [ ] Test: Switch modes during conversation

**Files to modify:**
- `MainActivity.kt` (add mode indicator)
- Create mode switch dialog composable

### Job 3.7: Testing ⏱️ 4h
- [ ] Test: OpenAI API integration
- [ ] Test: Claude API integration
- [ ] Test: Mode switching during voice session
- [ ] Test: Fallback to offline when API fails
- [ ] Test: Invalid API key handling
- [ ] Test: Network timeout handling
- [ ] Test: Cost tracking (token usage)

**Deliverable**: Can switch between offline and online modes, all 4 providers work

---

## 🟡 Phase 4: Memory Extraction (Weeks 7-8)
**Goal**: Extract and save memories from conversations  
**Dependencies**: Phase 3 complete  

### Job 4.1: MemoryExtractionHelper ⏱️ 4h
- [ ] Create extraction system prompt (under 120 tokens)
- [ ] Define JSON output format for extraction
- [ ] Create `buildExtractionMessage()` helper
- [ ] Implement JSON parsing with error handling
- [ ] Add retry logic for malformed responses
- [ ] Create extraction result data class

**Files to create:**
- `bridge/MemoryExtractionHelper.kt`

### Job 4.2: Extraction Integration - Offline ⏱️ 3h
- [ ] Call extraction after each LLM response (background)
- [ ] Use TinyLlama for extraction in offline mode
- [ ] Limit extraction to 150 tokens max
- [ ] Run on Dispatchers.IO (non-blocking)
- [ ] Parse JSON and update memory files
- [ ] Handle extraction failures gracefully (log and continue)

### Job 4.3: Extraction Integration - Online ⏱️ 3h
- [ ] Use same online provider for extraction
- [ ] Don't spin up TinyLlama just for extraction
- [ ] Same background processing
- [ ] Verify extraction quality is better with online models

### Job 4.4: Conversation Session Saving ⏱️ 4h
- [ ] Track conversation turns during voice session
- [ ] Generate session summary using LLM
- [ ] Create markdown file with proper format
- [ ] Extract key facts and tag them
- [ ] Save to `conversations/YYYY/MM-Month/YYYY-MM-DD_HH-mm.md`
- [ ] Trigger on `stopVoiceConversation()`
- [ ] Handle session timeout (5 min idle)

**Files to modify:**
- `bridge/VoicePipelineManager.kt`

### Job 4.5: Memory Update Logic ⏱️ 3h
- [ ] Parse extraction JSON
- [ ] Update `user_profile.json` for profile facts
- [ ] Update `relationships.json` for new relationships
- [ ] Update `reminders.json` for new tasks
- [ ] Merge duplicates intelligently
- [ ] Update `last_updated` timestamps

### Job 4.6: Testing ⏱️ 4h
- [ ] Test: Memory extraction from sample conversations
- [ ] Test: Profile updates from conversation
- [ ] Test: Relationship extraction
- [ ] Test: Reminder extraction
- [ ] Test: Malformed JSON handling
- [ ] Test: Concurrent extraction (rapid conversation)

**Deliverable**: Memories are automatically extracted and saved after each conversation

---

## 🟡 Phase 5: Compaction & Maintenance (Weeks 9-10)
**Goal**: Implement automatic memory compaction and archiving  
**Dependencies**: Phase 4 complete  

### Job 5.1: Compaction Logic ⏱️ 4h
- [ ] Implement `runCompactionIfNeeded()`
- [ ] Check storage size (> 500MB trigger)
- [ ] Check oldest file age (> 365 days trigger)
- [ ] Check last compaction date
- [ ] Collect files to compact
- [ ] Generate summary using LLM
- [ ] Create archive files
- [ ] Delete original files after successful archive
- [ ] Update `system/compaction-log.json`

**Files to create:**
- `bridge/MemoryCompaction.kt`

### Job 5.2: Summary Generation ⏱️ 3h
- [ ] Read old conversation files
- [ ] Build compaction prompt for LLM
- [ ] Generate quarterly summary
- [ ] Generate yearly summary
- [ ] Extract key facts for permanent storage
- [ ] Save to `conversations/archive/YYYY-Q#-summary.md`

### Job 5.3: Startup Trigger ⏱️ 2h
- [ ] Add compaction check on app startup
- [ ] Run in background coroutine
- [ ] Don't block UI
- [ ] Show notification if compaction runs
- [ ] Handle app termination during compaction

### Job 5.4: Search Implementation ⏱️ 4h
- [ ] Implement `searchAll(query)` in MemoryManager
- [ ] Search in profiles
- [ ] Search in relationships
- [ ] Search in conversations (file content)
- [ ] Use SQLite FTS5 for fast search
- [ ] Rank results by relevance
- [ ] Return formatted results

### Job 5.5: Memory Stats & Monitoring ⏱️ 2h
- [ ] Track storage usage
- [ ] Track conversation count
- [ ] Track fact count
- [ ] Display in Memory Manager UI
- [ ] Add warnings for storage limits

### Job 5.6: Testing ⏱️ 3h
- [ ] Test: Compaction triggers correctly
- [ ] Test: Summary generation quality
- [ ] Test: File deletion after compaction
- [ ] Test: Search functionality
- [ ] Test: Stats accuracy

**Deliverable**: Old memories auto-compact, search works, storage managed

---

## 🟢 Phase 6: UI Polish (Weeks 11-12)
**Goal**: Create user-facing memory management UI  
**Dependencies**: Phase 5 complete  

### Job 6.1: Memory Manager Screen ⏱️ 6h
- [ ] Create Memory Manager main screen
- [ ] Display user profile (editable)
- [ ] Display relationships list (add/edit/delete)
- [ ] Display pending reminders (complete/delete)
- [ ] Display recent conversations
- [ ] Add search functionality
- [ ] Navigation from main app

**Files to create:**
- `ui/memory/MemoryManagerScreen.kt`

### Job 6.2: Profile Editor ⏱️ 3h
- [ ] Edit user name, age, etc.
- [ ] Add/edit personal facts
- [ ] Edit preferences
- [ ] Save changes to JSON

### Job 6.3: Relationship Manager ⏱️ 3h
- [ ] Add new relationship form
- [ ] Edit relationship details
- [ ] Delete relationship
- [ ] Search/filter relationships

### Job 6.4: API Key Setup Screen ⏱️ 4h
- [ ] Provider selection (OpenAI, Claude, DeepSeek, Kimi)
- [ ] API key input with masking
- [ ] Test connection button
- [ ] Save/Delete key functionality
- [ ] Show current key status
- [ ] Security warning display

**Files to create:**
- `ui/settings/ApiKeySetupScreen.kt`

### Job 6.5: Provider Selector ⏱️ 2h
- [ ] Dropdown to select active provider
- [ ] Show provider details (model, pricing)
- [ ] Quick switch in settings

### Job 6.6: First Launch Experience ⏱️ 3h
- [ ] Create welcome screen
- [ ] Mode selection (Online vs Offline)
- [ ] Brief onboarding explanation
- [ ] API key prompt (if online selected)
- [ ] "Remember my choice" option
- [ ] Skip option for returning users

### Job 6.7: Testing & Bug Fixes ⏱️ 5h
- [ ] End-to-end testing: Full user journey
- [ ] UI/UX testing on Tab A7 Lite
- [ ] Performance testing
- [ ] Battery usage testing
- [ ] Error scenario testing
- [ ] Fix all P0 bugs
- [ ] Fix all P1 bugs

**Deliverable**: Polished UI, all features accessible, stable release

---

## 🔵 Future Enhancements (Post-MVP)

### Phase 7: Advanced Features (Optional)
- [ ] Vector search with embeddings (if RAM allows)
- [ ] Response caching for offline viewing
- [ ] Multi-user support
- [ ] Encrypted cloud backup
- [ ] Memory import/export
- [ ] Conversation analytics
- [ ] Smart reminder notifications

---

## 📋 Current Sprint: Week 1

### This Week's Focus: Phase 1 Jobs 1.1 - 1.3

| Day | Job | Task | Estimated |
|-----|-----|------|-----------|
| Day 1 | 1.1 | Create all data models | 4h |
| Day 1 | 1.2 | File system setup | 3h |
| Day 2 | 1.3 | MemoryManager core - initialization & context | 4h |
| Day 3 | 1.3 | MemoryManager - profile operations | 4h |
| Day 4 | 1.4 | Relationship operations | 4h |
| Day 5 | 1.5 | Reminder operations | 3h |
| Day 5 | 1.6 | Testing & validation | 4h |

**Total Week 1**: ~26 hours

---

## ✅ Definition of Done

### Phase 1 Done When:
- [ ] All data models created and tested
- [ ] Memory folders created on app startup
- [ ] Can create/read/update/delete user profile
- [ ] Can manage relationships
- [ ] Can manage reminders
- [ ] Context block generates ≤300 tokens
- [ ] All unit tests pass

### Phase 2 Done When:
- [ ] LLMProvider interface defined
- [ ] OfflineLLMProvider works
- [ ] LlamaBridge accepts dynamic system prompt
- [ ] Voice pipeline injects memory context
- [ ] No regression in voice mode performance

### Phase 3 Done When:
- [ ] API keys stored securely
- [ ] OpenAI provider works
- [ ] Claude provider works
- [ ] DeepSeek provider works
- [ ] Kimi provider works
- [ ] Can switch modes without crash
- [ ] Fallback to offline works

### Phase 4 Done When:
- [ ] Memory extraction works offline
- [ ] Memory extraction works online
- [ ] Conversations saved as markdown
- [ ] Facts automatically populate memory
- [ ] Extraction doesn't block voice pipeline

### Phase 5 Done When:
- [ ] Compaction runs automatically
- [ ] Old conversations archived
- [ ] Search works across all memory
- [ ] Storage limits enforced

### Phase 6 Done When:
- [ ] Memory Manager UI complete
- [ ] API key setup complete
- [ ] First launch experience polished
- [ ] No critical bugs
- [ ] App ready for beta

---

## 🐛 Bug Tracking

| ID | Description | Severity | Phase | Status |
|----|-------------|----------|-------|--------|
| - | - | - | - | - |

---

## 📝 Notes & Decisions

| Date | Decision | Rationale |
|------|----------|-----------|
| 2025-03-08 | Using JSON not SQLite | Human-readable, simpler implementation |
| 2025-03-08 | 300 token context limit | Conservative for TinyLlama 2048 window |
| 2025-03-08 | No vector DB | 3GB RAM constraint |
| 2025-03-08 | OkHttp not Retrofit | Simpler, already available |

---

*Update this file after each work session. Mark completed tasks with [x].*
