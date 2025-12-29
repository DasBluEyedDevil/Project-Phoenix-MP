# Audio Rep Counter Feature Design

## Overview

A new setting that replaces the beep sound with spoken rep numbers (1-25) during working sets. Warmup reps continue using the beep. Reps beyond 25 fall back to the beep.

## Settings

- **Location:** Settings screen, Audio/Sounds section
- **Label:** "Audio Rep Counter"
- **Description:** "Play spoken rep numbers during working sets"
- **Control:** Toggle switch
- **Default:** Off

## Audio Files

- **Source:** 25 audio files (`01_repcount.ogg` through `25_repcount.ogg`)
- **Android:** Copy to `androidApp/src/main/res/raw/` as `rep_01.ogg` through `rep_25.ogg`
- **iOS:** Convert to `.caf` format, bundle in `iosApp/iosApp/Sounds/`

## Implementation

### New HapticEvent

Add a new event type to carry the rep number:

```kotlin
enum class HapticEvent {
    REP_COMPLETED,      // Existing beep
    REP_COUNT_ANNOUNCED(val repNumber: Int),  // New: spoken number
    // ... other events
}
```

### Data Flow

```
User completes working rep
       ↓
RepCountTracker detects rep complete
       ↓
MainViewModel.onRepCompleted() called
       ↓
Check: userPreferences.audioRepCountEnabled?
       ├─ No  → emit HapticEvent.REP_COMPLETED (beep)
       └─ Yes → Check: workingRepCount <= 25?
                  ├─ Yes → emit HapticEvent.REP_COUNT_ANNOUNCED(workingRepCount)
                  └─ No  → emit HapticEvent.REP_COMPLETED (beep fallback)
       ↓
HapticFeedbackEffect receives event
       ↓
Platform sound manager plays appropriate audio
```

### Code Changes

1. **`HapticEvent`** - Add `REP_COUNT_ANNOUNCED(repNumber: Int)` variant
2. **`UserPreferences`** - Add `audioRepCountEnabled: Boolean = false`
3. **`PreferencesManager`** - Add getter/setter for the new preference
4. **`MainViewModel`** - Add logic in rep completion handler to choose event type
5. **`HapticFeedbackEffect` (Android)** - Load rep count sounds array, handle new event
6. **`HapticFeedbackEffect` (iOS)** - Load rep count sounds array, handle new event
7. **Settings UI** - Add toggle in appropriate section

## Behavior

- **Warmup reps:** Always use beep (unchanged)
- **Working reps 1-25:** Play spoken number when enabled
- **Working reps 26+:** Fall back to beep
- **All workout modes:** Supported (Old School, Echo, TUT, etc.)
- **Haptic feedback:** Still fires alongside audio

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| AMRAP with 30+ reps | Reps 26+ use beep fallback |
| Sound file missing | Graceful fallback to beep |
| Device muted | Haptic only (existing behavior) |
| Setting toggled mid-workout | Takes effect on next rep |
