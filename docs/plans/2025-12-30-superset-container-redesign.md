# Superset Container Redesign

**Date:** 2025-12-30
**Status:** Design Complete

## Problem

The current superset implementation stores grouping as properties on exercises (`supersetGroupId`, `supersetOrder`). This leads to:
- UI showing all supersets as one continuous visual block
- No clear distinction between separate superset groups
- Clunky grouping mechanics

## Solution Overview

Redesign supersets as **first-class container entities** with:
- Explicit `Superset` data model
- Nested list UI with drag-and-drop
- Color-coded visual distinction
- Configurable rest times per superset

---

## Data Model

### New Superset Entity

```kotlin
data class Superset(
    val id: String,
    val name: String,              // "Superset A" or user-defined
    val colorIndex: Int,           // 0=indigo, 1=pink, 2=green, 3=amber
    val restBetweenSeconds: Int,   // 0-120, default 10
    val exercises: List<RoutineExercise>
)
```

### Routine Structure

```kotlin
data class Routine(
    val id: String,
    val name: String,
    val items: List<RoutineItem>  // Mixed list of singles + supersets
)

sealed class RoutineItem {
    data class Single(val exercise: RoutineExercise) : RoutineItem()
    data class SupersetItem(val superset: Superset) : RoutineItem()
}
```

---

## Database Schema

### New Superset Table

```sql
CREATE TABLE Superset (
    id TEXT PRIMARY KEY NOT NULL,
    routineId TEXT NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
    orderIndex INTEGER NOT NULL,
    FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
);
```

### Modified RoutineExercise Table

```sql
-- Remove: supersetGroupId, supersetOrder, supersetRestSeconds
-- Add:
supersetId TEXT,                         -- NULL = standalone
orderInSuperset INTEGER NOT NULL DEFAULT 0,
FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL
```

### Migration Path

1. Create `Superset` table
2. For each distinct `supersetGroupId`, create a `Superset` row
3. Update `RoutineExercise` rows to reference new `supersetId`
4. Drop old superset columns

---

## UI Design

### Routine Editor Layout

```
┌─────────────────────────────────────┐
│ Routine Name                    [+] │
├─────────────────────────────────────┤
│ ≡ Bench Press                   ⋮   │  ← Standalone
│   3 sets × 10 reps                  │
├─────────────────────────────────────┤
│ ▌ Superset A                 ⋮  ▾   │  ← Collapsible header
│   ├─ ≡ Bicep Curl            ⋮      │  ← Indented, draggable
│   │    3 sets × 12 reps             │
│   ├─ ≡ Tricep Extension      ⋮      │
│   │    3 sets × 12 reps             │
│   └─ ≡ Shoulder Press        ⋮      │
│        3 sets × 10 reps             │
│   Rest between: 10s          [edit] │
├─────────────────────────────────────┤
│ ▌ Superset B                 ⋮  ▾   │  ← Different color
│   ├─ ≡ Squat                 ⋮      │
│   └─ ≡ Lunges                ⋮      │
│   Rest between: 0s           [edit] │
└─────────────────────────────────────┘
```

### Visual Elements

- **Colored left border:** 4dp on header, 2dp on indented exercises
- **Tree connectors:** Subtle gray lines (├─ and └─)
- **Collapse toggle:** ▾/▸ to expand/collapse superset contents
- **Drag handle:** ≡ on all draggable items

### Color Palette

| Index | Color  | Hex     |
|-------|--------|---------|
| 0     | Indigo | #6366F1 |
| 1     | Pink   | #EC4899 |
| 2     | Green  | #10B981 |
| 3     | Amber  | #F59E0B |

Colors cycle after index 3. Background tint: 8% opacity (12% in dark mode).

---

## Interactions

### Drag-and-Drop

| Action | Result |
|--------|--------|
| Exercise from superset → another superset | Moves into target superset |
| Exercise from superset → between standalones | Becomes standalone |
| Standalone → superset (header or between exercises) | Joins superset |
| Exercise within superset → different position | Reorders within superset |
| Superset header → different position | Reorders entire superset |
| Superset header → another superset header | Merges supersets |

### Empty Superset Behavior

- Superset with 1 exercise: **persists** (valid editing state)
- Superset with 0 exercises: **auto-deletes on drop** (not during drag)

### Creation Flows

**[+] Add button menu:**
- Add Exercise → exercise picker, adds standalone
- Add Superset → creates empty "Superset A/B/C", adds at bottom

**Multi-select existing exercises:**
- Long-press or checkbox to select
- Bottom bar: `[Create Superset] [Duplicate] [Delete]`
- Create Superset wraps selection, inserts at first selected position

### Overflow Menus

**Standalone exercise (⋮):**
- Duplicate
- Delete

**Superset header (⋮):**
- Rename
- Edit Rest Time
- Duplicate (superset + all exercises)
- Delete (exercises become standalone)

**Exercise in superset (⋮):**
- Duplicate
- Remove from Superset
- Delete

---

## Workout Execution

### Cycle Pattern

For superset with A, B, C (3 sets each, 10s rest between):

```
Set 1: A → 10s → B → 10s → C → [main rest]
Set 2: A → 10s → B → 10s → C → [main rest]
Set 3: A → 10s → B → 10s → C → Done
```

### Rest Behavior

- Between exercises in superset: `superset.restBetweenSeconds` (can be 0)
- After full cycle: normal rest timer from settings
- If rest = 0: immediate transition with brief "Next: [Exercise]" flash

### HUD Display

- Current exercise name
- "Superset: [Name]" badge/subtitle
- Set counter: "Set 1/3" for overall cycle
- Cycle progress: "Exercise 2/3"

### Weight/Rep Tracking

- Each exercise tracks independently
- Weight persists when cycling back (A set 2 remembers A set 1's weight)
- Set counts can differ between exercises in same superset

---

## Implementation Notes

### Key Files to Modify

- `Routine.kt` - Domain model changes
- `VitruvianDatabase.sq` - Schema + migration
- `RoutineEditorScreen.kt` - Complete UI rewrite
- `MainViewModel.kt` - Superset CRUD operations
- `ActiveWorkoutScreen.kt` - Execution logic for cycling

### Migration Considerations

- Must handle existing routines with old `supersetGroupId` format
- Generate `Superset` rows from distinct group IDs
- Preserve exercise order within groups
