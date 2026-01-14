# Bug Fixes Test Script - Bugfixes Branch

This test script covers all bug fixes implemented in the `bugfixes` branch. Test each section and mark pass/fail.

---

## Test Environment Setup

- [ ] Build and install fresh: `./gradlew :androidApp:installDebug` (Android) or via Xcode (iOS)
- [ ] Connect to Vitruvian machine via BLE
- [ ] Clear app data (optional but recommended for clean testing)

---

## 1. Compose Multiplatform 1.10.0 Upgrade (Garbled Text Fix)

**Issue:** iOS text corruption - random garbled symbols appearing after using number pickers/weight settings.

**Commits:** `a9724ace`

### Test Steps (iOS only):

1. [ ] Open the app and navigate to workout setup
2. [ ] Adjust weight settings using the number picker multiple times
3. [ ] Adjust rep settings using the number picker
4. [ ] Navigate back to main screen
5. [ ] Navigate to Settings and back
6. [ ] Open a routine and modify exercise settings
7. [ ] Put app in background for 30+ seconds, return to app
8. [ ] Repeat steps 2-6 several times

### Expected Result:
- [ ] **PASS:** All text remains readable throughout testing
- [ ] **PASS:** No garbled/corrupted symbols appear
- [ ] **PASS:** Text stays correct even after backgrounding the app

### Notes:
```
[Record any observations here]
```

---

## 2. Jump to Exercise Functionality

**Issue:** Tapping on exercise in routine list during workout didn't jump to that exercise.

**Commits:** `ab8f041e`

### Test Steps:

1. [ ] Create or load a routine with 3+ exercises
2. [ ] Start the routine workout
3. [ ] Complete the first exercise (or skip to set summary)
4. [ ] While viewing the exercise list/progress:
   - [ ] Tap on exercise #3 in the list
5. [ ] Observe if the workout jumps to exercise #3

### Expected Result:
- [ ] **PASS:** Tapping an exercise jumps directly to that exercise
- [ ] **PASS:** Workout parameters update correctly for the jumped-to exercise
- [ ] **PASS:** Set count resets appropriately for the new exercise

### Notes:
```
[Record any observations here]
```

---

## 3. Animated Rep Counter with Timing Fix (Issue #163)

**Issue:** Rep counter displayed confusingly - showing grey pending number at TOP, then dropping back to previous count before showing confirmed count.

**Commits:** `1edd782a`

### Test Steps:

1. [ ] Start a workout with target reps (e.g., 10 reps)
2. [ ] Perform reps while watching the rep counter closely
3. [ ] Observe the counter behavior during:
   - [ ] **Concentric phase (pulling up):** Counter should show next number outline filling bottom-to-top
   - [ ] **Eccentric phase (lowering):** Counter should show fill revealing top-to-bottom
   - [ ] **Rep completion:** Counter should show solid confirmed number
4. [ ] Complete several reps and watch for any flickering or count drops
5. [ ] Complete the full set

### Expected Result:
- [ ] **PASS:** Rep counter never shows a count, drops to lower count, then goes back up
- [ ] **PASS:** Animation smoothly indicates progress during movement
- [ ] **PASS:** Counter clearly distinguishes pending vs confirmed reps
- [ ] **PASS:** Progress indicator shows "X / Y" format correctly
- [ ] **PASS:** No flickering or jarring number changes

### Visual Reference:
- Pulling up: See outline of next number gradually reveal
- At top: Outline complete
- Lowering: Number fills in with solid color
- At bottom: Solid number = rep confirmed

### Notes:
```
[Record any observations here]
```

---

## 4. iOS Weight Picker Improvements (Issue #166)

**Issue:** Two problems:
1. No visible "Done" button when entering weight via text on iOS
2. Weight wheel jumped back to previously typed value when touched after scrolling

**Commits:** `9e3ce5ff`

### Test Steps (iOS only):

#### Part A: Done Button
1. [ ] Open exercise configuration
2. [ ] Tap on the weight number picker to enter edit mode
3. [ ] Type a weight value (e.g., "25")
4. [ ] Look for a checkmark/Done button next to the text field

### Expected Result (Part A):
- [ ] **PASS:** Visible Done/checkmark button appears next to text input
- [ ] **PASS:** Tapping Done button commits the value
- [ ] **PASS:** Keyboard dismisses after tapping Done

#### Part B: Scroll Value Persistence
1. [ ] Open exercise configuration
2. [ ] Tap on weight picker, type "25", confirm
3. [ ] Scroll the wheel to a different weight (e.g., scroll to "50")
4. [ ] Tap the wheel again (to start editing)
5. [ ] Check what value appears in the text field

### Expected Result (Part B):
- [ ] **PASS:** Text field shows "50" (the scrolled-to value), NOT "25"
- [ ] **PASS:** Scrolling to new values persists correctly
- [ ] **PASS:** No jumping back to previously typed values

### Notes:
```
[Record any observations here]
```

---

## 5. Weight Progression/Regression Logging (Issue #164)

**Issue:** Weight progression per rep not working - diagnostic logging added.

**Commits:** `9e3ce5ff`

### Test Steps (Requires logcat/console output):

1. [ ] Create a routine with an exercise that has weight progression enabled
   - Set weight change (e.g., +1kg per rep)
2. [ ] Start the routine workout
3. [ ] Monitor logcat for "Issue #164" tags:
   ```
   adb logcat | grep "Issue #164"
   ```
4. [ ] Check for logged values at each stage:
   - Exercise config load
   - Routine load
   - Set advancement
   - BLE packet creation

### Expected Result:
- [ ] **PASS:** Logs show progressionKg value being saved correctly
- [ ] **PASS:** Logs show progressionKg being loaded into WorkoutParameters
- [ ] **PASS:** Logs show progressionKg being sent in BLE packet
- [ ] **PASS:** Values are non-zero when progression is configured

### Sample Log Output Expected:
```
Issue #164: ExerciseConfigViewModel - loading progressionKg: 1.0
Issue #164: loadRoutineInternal - progressionKg: 1.0
Issue #164: startNextSetOrExercise - progressionKg: 1.0
Issue #164: BlePacketFactory - progressionRegressionKg: 1.0
```

### Notes:
```
[Record any observations here]
```

---

## Summary

| Test | Platform | Result | Tester | Date |
|------|----------|--------|--------|------|
| 1. Garbled Text Fix | iOS | [ ] Pass / [ ] Fail | | |
| 2. Jump to Exercise | Both | [ ] Pass / [ ] Fail | | |
| 3. Animated Rep Counter | Both | [ ] Pass / [ ] Fail | | |
| 4a. Done Button | iOS | [ ] Pass / [ ] Fail | | |
| 4b. Scroll Persistence | iOS | [ ] Pass / [ ] Fail | | |
| 5. Progression Logging | Both | [ ] Pass / [ ] Fail | | |

---

## Regression Testing

After completing the above tests, verify these core functions still work:

- [ ] BLE connection to machine works
- [ ] Basic workout (no routine) starts and tracks reps correctly
- [ ] Routine workouts complete all exercises
- [ ] Rest timer appears between sets
- [ ] Weight adjustments during workout work
- [ ] App doesn't crash during normal usage

---

## Notes / Issues Found

```
[Record any issues, unexpected behaviors, or additional observations here]


```

---

*Generated: January 14, 2026*
*Branch: bugfixes*
*Commits: a9724ace, 9e3ce5ff, 1edd782a, ab8f041e*
