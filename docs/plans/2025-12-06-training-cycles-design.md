# Training Cycles System Design

**Date:** 2025-12-06
**Status:** Approved
**Replaces:** WeeklyProgram / DailyRoutines calendar-bound system

## Overview

This document describes the redesign of the routine/program scheduling system from a calendar-bound weekly model to a flexible rolling schedule with enhanced set types, RPE tracking, and auto-progression.

## Problem Statement

The current `WeeklyProgram` system binds workouts to specific days of the week (Monday, Tuesday, etc.). This creates friction when:
- Users miss a day and the schedule becomes misaligned
- Users want programs that don't fit a 7-day cycle (e.g., 3-day, 5-day)
- Real life doesn't conform to rigid weekly patterns

## Design Goals

1. **Flexible scheduling** - Rolling "Day 1, Day 2, Day N" instead of weekday binding
2. **Smart recovery** - Handle missed days gracefully without manual intervention
3. **Enhanced tracking** - Set types and RPE for better workout data
4. **Intelligent progression** - Auto-suggest weight increases based on performance

---

## 1. Rolling Schedule

### Data Model

```kotlin
data class TrainingCycle(
    val id: String,
    val name: String,
    val description: String?,
    val days: List<CycleDay>,      // Ordered: Day 1, Day 2, etc.
    val createdAt: Long,
    val isActive: Boolean
)

data class CycleDay(
    val id: String,
    val cycleId: String,
    val dayNumber: Int,            // 1, 2, 3... (not weekday)
    val name: String?,             // Optional: "Push Day", "Legs", etc.
    val routineId: String?,        // Links to existing Routine
    val isRestDay: Boolean
)

data class CycleProgress(
    val id: String,
    val cycleId: String,
    val currentDayNumber: Int,     // Where user is in cycle
    val lastCompletedDate: Long?,  // For gap detection
    val cycleStartDate: Long       // When this cycle iteration began
)
```

### Completion Logic

```
When user completes a workout:
1. Check if completed routine matches current CycleDay's routine
2. If match:
   - Mark day complete
   - Advance currentDayNumber (wrap to 1 if at end)
   - Update lastCompletedDate
3. If no match:
   - Record workout normally
   - Do NOT advance cycle position
```

### Gap Handling (Smart Catch-Up)

```
When user opens app:
1. Calculate days since lastCompletedDate
2. If gap <= 2 days:
   - Continue from current position (skip missed days silently)
3. If gap >= 3 days:
   - Show dialog: "You've been away for X days"
   - Options:
     a) "Continue where I left off" (Day N)
     b) "Restart cycle" (Day 1)
     c) "Pick a day" (show list)
```

### UI: "Up Next" Dashboard Widget

```
┌─────────────────────────────────────┐
│  UP NEXT                            │
│  ─────────────────────────────────  │
│  Day 3: Push Day                    │
│  Bench Press, Shoulder Press, ...   │
│                                     │
│  [Start Workout]                    │
│                                     │
│  ○ ○ ● ○ ○ ○  (Day 3 of 6)         │
└─────────────────────────────────────┘
```

---

## 2. Set Types

Four set types covering all practical use cases:

| Set Type | Description | Rep Handling | Volume Tracking |
|----------|-------------|--------------|-----------------|
| **Standard** | Fixed rep target | Auto-stop at target | Included |
| **AMRAP** | As Many Reps As Possible | Record actual reps | Included |
| **Drop Set** | Reduce weight, continue | Multiple sub-sets | Included |
| **Warm-up** | Lighter preparation set | No target | Excluded |

### Data Model

```kotlin
enum class SetType {
    STANDARD,
    AMRAP,
    DROP_SET,
    WARMUP
}

data class PlannedSet(
    val id: String,
    val exerciseId: String,
    val setNumber: Int,
    val setType: SetType,
    val targetReps: Int?,          // null for AMRAP
    val targetWeight: Float?,      // in kg
    val targetRpe: Int?,           // 1-10, optional
    val restSeconds: Int?          // rest after this set
)

data class CompletedSet(
    val id: String,
    val plannedSetId: String?,     // null if ad-hoc set
    val setType: SetType,
    val actualReps: Int,
    val actualWeight: Float,
    val loggedRpe: Int?,           // user-provided, optional
    val completedAt: Long
)
```

### Drop Set Handling

Drop sets are stored as a parent set with child sub-sets:

```kotlin
data class DropSetDetail(
    val parentSetId: String,
    val drops: List<DropSetEntry>
)

data class DropSetEntry(
    val dropNumber: Int,           // 1, 2, 3...
    val weight: Float,
    val reps: Int
)
```

---

## 3. RPE (Rate of Perceived Exertion)

### Scale Definition

| RPE | Meaning | Reps in Reserve (RIR) |
|-----|---------|----------------------|
| 10 | Max effort, couldn't do another | 0 |
| 9 | Could maybe do 1 more | 1 |
| 8 | Could do 2 more | 2 |
| 7 | Could do 3 more | 3 |
| 6 | Could do 4+ more | 4+ |

### Capture UX

- **When:** Optional, after each set
- **How:** Tap to reveal emoji slider
- **Visual:** Emoji faces from relaxed to exhausted

```
RPE Input Slider:
😊 ──●────────── 😵
 6   7   8   9   10
```

### Storage

RPE is stored per `CompletedSet` as an optional `Int?` (1-10).

---

## 4. Auto-Progression

### Trigger Conditions

Weight increase suggested when EITHER:
1. **Rep-based:** User hits target reps for 2+ consecutive sessions
2. **RPE-based:** User logs RPE below target (e.g., target RPE 8, logged RPE 6)

### Progression Increment

- **Formula:** `currentWeight * 1.025` (2.5% increase)
- **Rounding:** To nearest 0.5 kg
- **Minimum increment:** 0.5 kg

```kotlin
fun calculateProgressionWeight(currentWeight: Float): Float {
    val rawIncrease = currentWeight * 0.025f
    val increment = maxOf(0.5f, (rawIncrease * 2).roundToInt() / 2f)
    return currentWeight + increment
}
```

### Suggestion Delivery

- **Timing:** When user starts next session with that exercise
- **UI:** Weight field pre-filled with suggested weight
- **Indicator:** Subtle "+" badge or highlight showing it's a progression
- **User action:** Accept by proceeding, or manually adjust

```
┌─────────────────────────────────────┐
│  Bench Press                        │
│  ─────────────────────────────────  │
│  Set 1: [82.5 kg ↑] × 8 reps       │
│          ~~~~~~~~                   │
│          Suggested +2.5kg           │
└─────────────────────────────────────┘
```

### Progression History

Track suggestions and responses for analysis:

```kotlin
data class ProgressionEvent(
    val id: String,
    val exerciseId: String,
    val suggestedWeight: Float,
    val previousWeight: Float,
    val reason: ProgressionReason,   // REPS_ACHIEVED, LOW_RPE
    val userResponse: ProgressionResponse, // ACCEPTED, MODIFIED, REJECTED
    val actualWeight: Float?,        // what user actually used
    val timestamp: Long
)

enum class ProgressionReason { REPS_ACHIEVED, LOW_RPE }
enum class ProgressionResponse { ACCEPTED, MODIFIED, REJECTED }
```

---

## 5. Database Schema

### New Tables

```sql
-- Training Cycles (replaces WeeklyProgram)
CREATE TABLE TrainingCycle (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    created_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 0
);

-- Cycle Days (replaces ProgramDay)
CREATE TABLE CycleDay (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    day_number INTEGER NOT NULL,
    name TEXT,
    routine_id TEXT,
    is_rest_day INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE,
    FOREIGN KEY (routine_id) REFERENCES Routine(id) ON DELETE SET NULL
);

-- Cycle Progress (new)
CREATE TABLE CycleProgress (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL UNIQUE,
    current_day_number INTEGER NOT NULL DEFAULT 1,
    last_completed_date INTEGER,
    cycle_start_date INTEGER NOT NULL,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

-- Planned Sets (new)
CREATE TABLE PlannedSet (
    id TEXT PRIMARY KEY NOT NULL,
    routine_exercise_id TEXT NOT NULL,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL,
    target_reps INTEGER,
    target_weight_kg REAL,
    target_rpe INTEGER,
    rest_seconds INTEGER,
    FOREIGN KEY (routine_exercise_id) REFERENCES RoutineExercise(id) ON DELETE CASCADE
);

-- Completed Sets (enhanced)
CREATE TABLE CompletedSet (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    planned_set_id TEXT,
    set_type TEXT NOT NULL,
    actual_reps INTEGER NOT NULL,
    actual_weight_kg REAL NOT NULL,
    logged_rpe INTEGER,
    completed_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE,
    FOREIGN KEY (planned_set_id) REFERENCES PlannedSet(id) ON DELETE SET NULL
);

-- Progression Events (new)
CREATE TABLE ProgressionEvent (
    id TEXT PRIMARY KEY NOT NULL,
    exercise_id TEXT NOT NULL,
    suggested_weight_kg REAL NOT NULL,
    previous_weight_kg REAL NOT NULL,
    reason TEXT NOT NULL,
    user_response TEXT,
    actual_weight_kg REAL,
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
);
```

### Indexes

```sql
CREATE INDEX idx_cycle_day_cycle ON CycleDay(cycle_id);
CREATE INDEX idx_cycle_progress_cycle ON CycleProgress(cycle_id);
CREATE INDEX idx_planned_set_exercise ON PlannedSet(routine_exercise_id);
CREATE INDEX idx_completed_set_session ON CompletedSet(session_id);
CREATE INDEX idx_progression_exercise ON ProgressionEvent(exercise_id);
```

---

## 6. Migration Strategy

### From WeeklyProgram to TrainingCycle

```kotlin
fun migrateWeeklyProgram(program: WeeklyProgram): TrainingCycle {
    // Create 7-day cycle
    val cycle = TrainingCycle(
        id = generateId(),
        name = program.name,
        description = "Migrated from weekly program",
        days = program.days.map { day ->
            CycleDay(
                id = generateId(),
                cycleId = cycle.id,
                dayNumber = day.dayOfWeek,  // 1-7 maps directly
                name = dayOfWeekName(day.dayOfWeek),
                routineId = day.routineId,
                isRestDay = day.routineId == null
            )
        },
        createdAt = Clock.System.now().toEpochMilliseconds(),
        isActive = program.isActive
    )

    // Initialize progress at Day 1
    val progress = CycleProgress(
        id = generateId(),
        cycleId = cycle.id,
        currentDayNumber = 1,
        lastCompletedDate = null,
        cycleStartDate = Clock.System.now().toEpochMilliseconds()
    )

    return cycle to progress
}
```

### Migration Steps

1. **Schema migration:** Add new tables alongside existing
2. **Data migration:** Convert WeeklyProgram → TrainingCycle
3. **UI migration:** Update screens to use new data model
4. **Cleanup:** Remove old tables after validation period

### Backward Compatibility

- Keep old tables for 2 app versions
- Show migration prompt on first launch
- Allow manual trigger of migration from settings

---

## 7. UI Components

### Cycle Builder Screen

```
┌─────────────────────────────────────┐
│  ← Create Training Cycle            │
├─────────────────────────────────────┤
│  Name: [Push/Pull/Legs________]     │
│                                     │
│  DAYS                               │
│  ┌─────────────────────────────┐    │
│  │ Day 1: Push Day        [⋮]  │    │
│  │ Bench, Shoulder Press       │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ Day 2: Pull Day        [⋮]  │    │
│  │ Rows, Bicep Curls           │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ Day 3: Leg Day         [⋮]  │    │
│  │ Squats, Lunges              │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ Day 4: Rest            [⋮]  │    │
│  └─────────────────────────────┘    │
│                                     │
│  [+ Add Day]                        │
│                                     │
│  ════════════════════════════════   │
│  TEMPLATES                          │
│  [3-Day Full Body] [PPL] [Upper/L]  │
│                                     │
├─────────────────────────────────────┤
│           [Save Cycle]              │
└─────────────────────────────────────┘
```

### Set Configuration in Exercise Editor

```
┌─────────────────────────────────────┐
│  Bench Press - Sets                 │
├─────────────────────────────────────┤
│  Set 1: Warm-up                     │
│  [40 kg] × [10 reps]                │
│                                     │
│  Set 2: Standard                    │
│  [80 kg] × [8 reps] @ RPE [8]       │
│                                     │
│  Set 3: Standard                    │
│  [80 kg] × [8 reps] @ RPE [8]       │
│                                     │
│  Set 4: AMRAP                       │
│  [75 kg] × [max]                    │
│                                     │
│  [+ Add Set]                        │
└─────────────────────────────────────┘
```

---

## 8. Implementation Order

### Phase 1: Data Layer
1. Add new SQLDelight schema
2. Create repository interfaces
3. Implement SQLDelight repositories
4. Write migration logic

### Phase 2: Set Types & RPE
1. Update WorkoutSession to track set types
2. Add RPE capture UI component
3. Integrate into workout flow

### Phase 3: Training Cycles
1. Create TrainingCycle management screens
2. Implement "Up Next" dashboard widget
3. Add gap detection and recovery flow
4. Build Cycle Builder UI

### Phase 4: Auto-Progression
1. Implement progression calculation logic
2. Add progression suggestion UI
3. Track progression events
4. Display progression history

### Phase 5: Migration & Polish
1. Run data migration on upgrade
2. Remove deprecated tables
3. Update documentation

---

## Appendix: Preset Templates

### 3-Day Full Body
- Day 1: Full Body A
- Day 2: Rest
- Day 3: Full Body B
- Day 4: Rest
- Day 5: Full Body C
- Day 6: Rest
- Day 7: Rest

### Push/Pull/Legs (6-Day)
- Day 1: Push
- Day 2: Pull
- Day 3: Legs
- Day 4: Push
- Day 5: Pull
- Day 6: Legs

### Upper/Lower (4-Day)
- Day 1: Upper
- Day 2: Lower
- Day 3: Rest
- Day 4: Upper
- Day 5: Lower

---

*Document generated from brainstorming session on 2025-12-06*
