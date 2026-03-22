# Hey-Nilo: 5 New Features - Implementation Plan

## Overview
All 5 features are achievable on Android. Implementation follows a **Tool Dispatcher** pattern where the LLM detects intent and outputs structured JSON, which gets parsed and dispatched to action handlers.

---

## Architecture

```
User Speech
    ↓
STT (Whisper)
    ↓
LLM (with Tool schema in system prompt)
    ↓
IntentParser (extracts action + params from <action>{...}</action> tags)
    ↓
ActionDispatcher
    ├── AlarmAction (set/cancel)
    ├── CameraAction (timed photo)
    ├── FlashlightAction (on/off)
    ├── CallAction (make call)
    └── CalendarAction (add event)
```

---

## Phase Breakdown

### Phase 0: Foundation (CRITICAL - Do First)
**Duration:** 2-3 hours
**Files to Create:**
- `model/action/NiloAction.kt` - Sealed class for all actions
- `bridge/action/ActionDispatcher.kt` - Central router
- `bridge/action/IntentParser.kt` - Parses <action> tags from LLM output

**Files to Modify:**
- `SystemPromptBuilder.kt` - Add action schema to system prompt
- `VoicePipelineManager.kt` - Wire in IntentParser before TTS
- `AndroidManifest.xml` - Add all required permissions

**Key Design:**
```kotlin
// NiloAction.kt
sealed class NiloAction {
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : NiloAction()
    data class CancelAlarm(val label: String?) : NiloAction()
    data class TakePhoto(val delaySeconds: Int) : NiloAction()
    data class SetFlashlight(val on: Boolean) : NiloAction()
    data class MakeCall(val contactName: String, val phoneNumber: String?) : NiloAction()
    data class AddCalendarEvent(val title: String, val startTimeMillis: Long, val endTimeMillis: Long?) : NiloAction()
    object Unknown : NiloAction()
}
```

**System Prompt Addition:**
```
You are Nilo, a helpful AI assistant that can also control device functions.

If the user asks you to perform a device action, include an action tag in your response:
<action>{"type":"SET_ALARM","hour":7,"minute":30,"label":"Wake up"}</action>
<action>{"type":"CANCEL_ALARM","label":"Wake up"}</action>
<action>{"type":"TAKE_PHOTO","delaySeconds":5}</action>
<action>{"type":"SET_FLASHLIGHT","on":true}</action>
<action>{"type":"MAKE_CALL","contactName":"Mom"}</action>
<action>{"type":"ADD_CALENDAR_EVENT","title":"Doctor Appointment","startTimeMillis":1711000000000,"endTimeMillis":1711003600000}</action>

Otherwise respond normally without action tags.
```

---

### Phase 1: Easy Wins (Alarm + Flashlight + Call)
**Duration:** 3-4 hours
**Difficulty:** 🟢 Easy

#### 1.1 AlarmAction.kt
- Uses `AlarmClock.ACTION_SET_ALARM` and `AlarmClock.ACTION_DISMISS_ALARM` intents
- No special permissions needed (normal level)
- Opens system clock app with pre-filled values

#### 1.2 FlashlightAction.kt  
- Uses `CameraManager.setTorchMode()`
- Permission: `FLASHLIGHT` (normal level)
- One-line implementation

#### 1.3 CallAction.kt
- Uses `Intent.ACTION_CALL` with `tel:` URI
- Permission: `CALL_PHONE` (dangerous - runtime request needed)
- Contact lookup via `ContactsContract` API
- If permission denied, fallback to `ACTION_DIAL` (opens dialer)

**Permissions to add:**
```xml
<uses-permission android:name="android.permission.SET_ALARM"/>
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.READ_CONTACTS"/>
<uses-permission android:name="android.permission.FLASHLIGHT"/>
```

---

### Phase 2: Calendar Events
**Duration:** 2-3 hours  
**Difficulty:** 🟡 Medium

#### 2.1 CalendarAction.kt
- Uses `Intent.ACTION_INSERT` with `CalendarContract.Events.CONTENT_URI`
- Permission: `WRITE_CALENDAR` (dangerous - runtime request needed)
- Opens calendar app pre-filled with event details
- Alternative: Direct insert via ContentProvider

**Key Point:** Let LLM parse natural language dates ("next Friday at 3pm") into epoch milliseconds. Kotlin just receives the timestamp.

---

### Phase 3: Camera with Timed Photo
**Duration:** 4-6 hours
**Difficulty:** 🟡 Medium (most complex)

#### 3.1 CameraAction.kt
- Uses **CameraX** library (modern Android camera API)
- Shows countdown overlay (Compose)
- Takes photo after countdown
- Saves to `Pictures/HeyNilo/` directory

#### 3.2 CameraOverlay.kt
- Full-screen or picture-in-picture camera preview
- Countdown timer display (5s or 10s)
- Capture animation/sound

**Dependencies to add:**
```groovy
implementation "androidx.camera:camera-core:1.3.0"
implementation "androidx.camera:camera-camera2:1.3.0"
implementation "androidx.camera:camera-lifecycle:1.3.0"
implementation "androidx.camera:camera-view:1.3.0"
```

**Permissions:**
```xml
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
```

---

## Complete Permission List

Add to `AndroidManifest.xml`:
```xml
<!-- Phase 1: Easy wins -->
<uses-permission android:name="android.permission.SET_ALARM"/>
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.READ_CONTACTS"/>
<uses-permission android:name="android.permission.FLASHLIGHT"/>

<!-- Phase 2: Calendar -->
<uses-permission android:name="android.permission.WRITE_CALENDAR"/>
<uses-permission android:name="android.permission.READ_CALENDAR"/>

<!-- Phase 3: Camera -->
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
<uses-feature android:name="android.hardware.camera.flash" android:required="false"/>
```

---

## Implementation Order

| Order | Phase | Duration | Dependencies |
|-------|-------|----------|--------------|
| 1 | Phase 0: Foundation | 2-3h | None |
| 2 | Phase 1.2: Flashlight | 30min | Phase 0 |
| 3 | Phase 1.1: Alarm | 1h | Phase 0 |
| 4 | Phase 1.3: Call | 1.5h | Phase 0 |
| 5 | Phase 2: Calendar | 2-3h | Phase 0 |
| 6 | Phase 3: Camera | 4-6h | Phase 0 |

**Total Estimated Time:** 10-15 hours

---

## Critical Implementation Notes

1. **IntentParser must strip `<action>` tags** before displaying response to user
2. **ActionDispatcher runs on Main thread** - use coroutines for async operations
3. **Runtime permissions** for CALL_PHONE, CAMERA, WRITE_CALENDAR
4. **Contact resolution:** Query `ContactsContract` by name, use best match
5. **Date parsing:** Let LLM handle natural language → epoch milliseconds
6. **Camera countdown:** Use `LaunchedEffect` with `delay(1000)` loop

---

## Testing Checklist

- [ ] Say "Set alarm for 7 AM" → System clock opens with 7:00 AM
- [ ] Say "Turn on flashlight" → Flashlight turns on
- [ ] Say "Call Mom" → Dialer opens with Mom's number (if contact exists)
- [ ] Say "Add doctor appointment for tomorrow at 3pm" → Calendar opens pre-filled
- [ ] Say "Take a photo in 5 seconds" → Camera opens, countdown, photo saved

---

Ready to start with Phase 0?
