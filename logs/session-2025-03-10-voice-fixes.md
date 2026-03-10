# Session Log - 2025-03-10 Voice Mode Fixes

**Agent**: Kimi Code CLI  
**Time**: 09:10 - 09:20 IST (10 minutes)  
**Focus**: Fix 5-second delay and false barge-in triggers  
**Status**: ✅ Complete - APK Installed and Ready for Testing

---

## Issues Fixed

### Issue 1: 5-Second Delay After Speech

**Root Cause**: 
- `VADConfig.minSilenceDurationMs = 1200ms` in VADBridge.kt
- VoicePipelineManager had `TRAILING_SILENCE_FRAMES = 25` (~800ms)
- Combined delays caused ~5 second wait before response

**Fix Applied**:

| Parameter | Before | After | Impact |
|-----------|--------|-------|--------|
| `minSilenceDurationMs` | 1200ms | 500ms | Faster SPEECH_END detection |
| `TRAILING_SILENCE_FRAMES` | 25 (~800ms) | 12 (~384ms) | Faster trailing silence trigger |
| `MIN_SILENCE_MS` | 800ms | 400ms | Minimum silence threshold |

**Expected Result**: Response time reduced from ~5s to ~1-2s after speech ends

---

### Issue 2: False Barge-in from Typing/Horns/Rain

**Root Cause**:
- Energy threshold 1500 was too low for sharp sounds (keyboard, horns)
- 8 frames (~256ms) confirmation was too short
- VAD threshold 0.6 still caught some background noise

**Fix Applied**:

| Parameter | Before | After | Impact |
|-----------|--------|-------|--------|
| `ttsEnergyThreshold` | 1500 | 2500 | Ignores typing/light rain |
| `HIGH_ENERGY_THRESHOLD` | 8 frames | 20 frames | Requires ~640ms sustained audio |
| `VAD_THRESHOLD` | 0.6 | 0.65 | Less sensitive to non-speech |

**Key Insight**: Human speech is sustained over time; horns/typing are brief spikes. By requiring 20 consecutive high-energy frames (~640ms), we filter out transient noises while still catching intentional interruptions.

---

## Files Modified

| File | Changes |
|------|---------|
| `VoicePipelineManager.kt` | Updated VAD config: threshold 0.65, silence 400ms, trailing 12 frames |
| `SimpleAudioRecorder.kt` | Barge-in: energy 2500, confirmation 20 frames |
| `VADBridge.kt` | Default threshold 0.65, silence duration 500ms |

---

## Testing Instructions

### Test 1: Response Time
1. Tap panda to start voice mode
2. Say "What is my name?"
3. **Expected**: Response within 1-2 seconds after you stop speaking

### Test 2: Barge-in Immunity
1. Start voice mode, ask a question that gives a long response
2. While TTS is speaking:
   - Type on keyboard near the device
   - Make a sharp clap sound
   - Simulate rain/wind noise
3. **Expected**: TTS should NOT interrupt - continues speaking

### Test 3: Valid Barge-in Still Works
1. While TTS is speaking, clearly say "Stop" or "Wait"
2. **Expected**: TTS should stop and app should listen for new input

### Test 4: Multi-sentence Speech
1. Say "Who are you? Can you help me?"
2. **Expected**: Both questions captured, not cut off mid-sentence

---

## Build Status

```
BUILD SUCCESSFUL in 1m 12s
Installed on 1 device (CPH1801 - Android 7.1.1)
```

---

## Next Steps

If issues persist:
1. Check logcat for VAD state transitions: `adb logcat | grep -E "VAD|VoicePipelineManager"`
2. Adjust `ttsEnergyThreshold` further if typing still triggers barge-in
3. Consider adding frequency-based filtering for more noise immunity
