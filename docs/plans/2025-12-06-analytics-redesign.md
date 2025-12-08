# Analytics Screen Redesign

**Date:** 2025-12-06
**Status:** Approved
**Problem:** Users can't easily answer "What weight did I use for Squats last week?" or see exercise-specific progression.

## Overview

Redesign the Analytics screen from a PR-focused view to an exercise-centric view with:
1. Restructured tabs (Overview, Log, Exercises)
2. New Exercise Detail drill-down screen
3. Expandable list format for workout history
4. Set-level data storage (forward-only)

---

## 1. Tab Restructure

### Current → New Mapping

| Position | Current | New | Purpose |
|----------|---------|-----|---------|
| Tab 0 | Progression (PR list) | **Overview** | Dashboard stats, streak, muscle balance |
| Tab 1 | History (cards) | **Log** | Chronological feed with filters |
| Tab 2 | Insights (dashboard) | **Exercises** | A-Z exercise list → drill-down |

### Tab Icons

```kotlin
Tab(
    text = { Text("Overview") },
    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) }
)
Tab(
    text = { Text("Log") },
    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }
)
Tab(
    text = { Text("Exercises") },
    icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) }
)
```

### Navigation Flow

```
AnalyticsScreen
├── Overview Tab (dashboard)
├── Log Tab (expandable history list)
└── Exercises Tab (A-Z list)
        └── ExerciseDetailScreen (new)
                ├── Trend Chart
                └── Filtered History
```

---

## 2. Overview Tab (Dashboard)

Consolidates current InsightsTab content:

- Strength Score card
- This Week Stats
- Workout Streak
- Recent PRs (top 3)
- Muscle Balance radar (if available)

No changes required - just rename from "Insights" to "Overview" and move to Tab 0.

---

## 3. Log Tab (Expandable History)

### UI Layout

```
┌─────────────────────────────────────┐
│  [All] [Routines] [Single] [PRs]    │
│                                     │
│  ▶ Dec 5, 2025 - Push Day           │
│    3 exercises • 45 min             │
├─────────────────────────────────────┤
│  ▼ Dec 3, 2025 - Bench Press        │  ← Expanded
│    ┌─────────────────────────────┐  │
│    │ Set 1: 80kg × 10           │  │
│    │ Set 2: 80kg × 10           │  │
│    │ Set 3: 80kg × 8      ⚠️    │  │
│    │ ─────────────────────────  │  │
│    │ Total: 28 reps • 2,240kg   │  │
│    │ Mode: Eccentric            │  │
│    └─────────────────────────────┘  │
├─────────────────────────────────────┤
│  ▶ Dec 1, 2025 - Legs Day           │
│    4 exercises • 52 min             │
└─────────────────────────────────────┘
```

### Filter Chips

| Filter | Shows |
|--------|-------|
| All | Everything |
| Routines | Only routine sessions (grouped) |
| Single | Only single-exercise "Just Lift" sessions |
| PRs | Only sessions containing a PR |

### Collapsed Row Content

- Date + Routine/Exercise name
- Exercise count (for routines) or exercise name (for single)
- Duration

### Expanded Row Content

- Individual set breakdown: `Set N: {weight} × {reps}`
- Visual indicators:
  - 🏆 Gold: Personal Record
  - ✓ Green: Completed target reps
  - ⚠️ Red: Failed target
- Totals: Total reps, total volume
- Mode: Workout mode used

### Month Separators

```
─── November 2025 ───
```

Visual breaks between months for easier scanning.

### Interactions

- Tap row: Expand/collapse
- Long-press: Show delete option
- Swipe left (optional): Delete with confirmation

### Data Class

```kotlin
data class LogEntry(
    val id: String,
    val date: Long,
    val title: String,                    // Routine name or exercise name
    val subtitle: String,                 // "3 exercises • 45 min" or exercise name
    val type: LogEntryType,               // ROUTINE, SINGLE, JUST_LIFT
    val sessions: List<WorkoutSession>,   // Underlying sessions
    val sets: List<CompletedSet>,         // Set-level data (may be empty for legacy)
    val hasPr: Boolean
)

enum class LogEntryType { ROUTINE, SINGLE, JUST_LIFT }
```

---

## 4. Exercises Tab (A-Z List)

### UI Layout

```
┌─────────────────────────────────────┐
│  🔍 Search exercises...             │
│                                     │
│  RECENT                             │
│  ┌─────────────────────────────┐    │
│  │ Bench Press          127kg  │ →  │
│  │ 2 days ago • 12 sessions    │    │
│  ├─────────────────────────────┤    │
│  │ Squat               145kg   │ →  │
│  │ 4 days ago • 8 sessions     │    │
│  └─────────────────────────────┘    │
│                                     │
│  ALL EXERCISES                      │
│  ─── B ───                          │
│  │ Bench Press          127kg  │ →  │
│  │ Bicep Curl            32kg  │ →  │
│  ─── C ───                          │
│  │ Cable Row             55kg  │ →  │
└─────────────────────────────────────┘
```

### Sections

1. **Search Bar**: Filter by exercise name (real-time)
2. **Recent**: Top 5 exercises by last performed date
3. **All Exercises**: Alphabetical with section headers

### Row Content

| Field | Description |
|-------|-------------|
| Exercise name | Primary text |
| Best 1RM | Right-aligned, bold |
| Last performed | "2 days ago" relative format |
| Session count | "12 sessions" |

### Only Shows Performed Exercises

This is not a library browser. Only exercises the user has actually done appear here.

### Data Class

```kotlin
data class ExerciseSummary(
    val exerciseId: String,
    val exerciseName: String,
    val bestOneRepMax: Float?,           // Estimated 1RM
    val bestWeight: Float,               // Highest weight used
    val lastPerformed: Long,             // Timestamp
    val totalSessions: Int,
    val totalSets: Int
)
```

### Composable

```kotlin
@Composable
fun ExercisesTab(
    exerciseSummaries: List<ExerciseSummary>,
    onExerciseClick: (String) -> Unit,   // exerciseId
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(exerciseSummaries, searchQuery) {
        if (searchQuery.isBlank()) exerciseSummaries
        else exerciseSummaries.filter {
            it.exerciseName.contains(searchQuery, ignoreCase = true)
        }
    }

    val recent = filtered.sortedByDescending { it.lastPerformed }.take(5)
    val alphabetical = filtered.sortedBy { it.exerciseName }

    LazyColumn(modifier = modifier) {
        // Search bar
        item { SearchBar(query = searchQuery, onQueryChange = { searchQuery = it }) }

        // Recent section
        if (searchQuery.isBlank()) {
            item { SectionHeader("Recent") }
            items(recent) { summary ->
                ExerciseSummaryRow(summary, onClick = { onExerciseClick(summary.exerciseId) })
            }
        }

        // All exercises with alpha headers
        item { SectionHeader("All Exercises") }
        alphabetical.groupBy { it.exerciseName.first().uppercaseChar() }
            .forEach { (letter, exercises) ->
                item { AlphaHeader(letter.toString()) }
                items(exercises) { summary ->
                    ExerciseSummaryRow(summary, onClick = { onExerciseClick(summary.exerciseId) })
                }
            }
    }
}
```

---

## 5. Exercise Detail Screen (New)

### UI Layout

```
┌─────────────────────────────────────┐
│  ← Bench Press                      │
│                                     │
│  ESTIMATED 1RM                      │
│  ┌─────────────────────────────┐    │
│  │         127.5 kg            │    │
│  │     ▲ +2.5kg from last      │    │
│  └─────────────────────────────┘    │
│                                     │
│  PROGRESSION                        │
│  ┌─────────────────────────────┐    │
│  │    📈 [Chart]               │    │
│  └─────────────────────────────┘    │
│  [1RM ▼]  [30d] [90d] [1y] [All]   │
│                                     │
│  HISTORY                            │
│  ▶ Dec 5 - 80kg × 10, 10, 8         │
│  ▶ Dec 1 - 80kg × 10, 10, 10   🏆   │
│  ▶ Nov 28 - 77.5kg × 10, 10, 10     │
└─────────────────────────────────────┘
```

### Components

#### 1. Hero Metric Card

```kotlin
@Composable
fun OneRepMaxCard(
    currentOneRepMax: Float,
    previousOneRepMax: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    val delta = previousOneRepMax?.let { currentOneRepMax - it }

    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ESTIMATED 1RM", style = Typography.labelMedium)
            Text(
                formatWeight(currentOneRepMax, weightUnit),
                style = Typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            delta?.let {
                val sign = if (it > 0) "▲ +" else "▼ "
                Text(
                    "$sign${formatWeight(abs(it), weightUnit)} from last",
                    color = if (it > 0) Color.Green else Color.Red
                )
            }
        }
    }
}
```

#### 2. Trend Chart

- **Y-Axis**: Estimated 1RM (default) or Max Volume (toggle)
- **X-Axis**: Date
- **Time Range**: 30d, 90d, 1y, All (chip toggles)
- **Data Points**: One per session, connected line

```kotlin
@Composable
fun ProgressionChart(
    dataPoints: List<ChartDataPoint>,
    yAxisMode: YAxisMode,           // ONE_REP_MAX, MAX_VOLUME
    timeRange: TimeRange,           // DAYS_30, DAYS_90, YEAR_1, ALL
    onYAxisModeChange: (YAxisMode) -> Unit,
    onTimeRangeChange: (TimeRange) -> Unit
)

data class ChartDataPoint(
    val date: Long,
    val value: Float,               // 1RM or volume depending on mode
    val isPr: Boolean
)
```

#### 3. Filtered History List

Same expandable list format as Log tab, but filtered to this exercise only.

```kotlin
@Composable
fun ExerciseHistoryList(
    sessions: List<WorkoutSession>,
    sets: Map<String, List<CompletedSet>>,  // sessionId -> sets
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
)
```

### 1RM Formula

```kotlin
/**
 * Epley formula for estimated one-rep max
 */
fun calculateOneRepMax(weight: Float, reps: Int): Float {
    if (reps <= 0) return weight
    if (reps == 1) return weight
    return weight * (1 + 0.0333f * reps)
}

/**
 * Get best 1RM from a list of sets
 */
fun List<CompletedSet>.bestOneRepMax(): Float? {
    return mapNotNull { set ->
        if (set.reps > 0) calculateOneRepMax(set.weightKg, set.reps) else null
    }.maxOrNull()
}
```

### Navigation

```kotlin
// In ExercisesTab
onExerciseClick = { exerciseId ->
    navController.navigate("exercise_detail/$exerciseId")
}

// Route definition
composable("exercise_detail/{exerciseId}") { backStackEntry ->
    val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: return@composable
    ExerciseDetailScreen(exerciseId = exerciseId)
}
```

---

## 6. Data Model: Set-Level Storage

### New Database Table

```sql
CREATE TABLE CompletedSet (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    set_number INTEGER NOT NULL,
    weight_kg REAL NOT NULL,
    reps INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    rpe INTEGER,
    is_pr INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_completed_set_session ON CompletedSet(session_id);
CREATE INDEX idx_completed_set_exercise ON CompletedSet(session_id);
```

### Domain Model

```kotlin
data class CompletedSet(
    val id: String,
    val sessionId: String,
    val setNumber: Int,
    val weightKg: Float,
    val reps: Int,
    val setType: SetType,
    val rpe: Int?,
    val isPr: Boolean,
    val completedAt: Long
)

enum class SetType {
    STANDARD,
    AMRAP,
    DROP_SET,
    WARMUP
}
```

### Display Helpers

```kotlin
/**
 * Compact notation: "80kg × 10, 10, 8"
 */
fun List<CompletedSet>.toCompactString(formatWeight: (Float) -> String): String {
    if (isEmpty()) return ""

    // Group by weight for cleaner display
    val byWeight = groupBy { it.weightKg }

    return if (byWeight.size == 1) {
        // Same weight for all sets
        val weight = formatWeight(first().weightKg)
        val reps = sortedBy { it.setNumber }.joinToString(", ") { it.reps.toString() }
        "$weight × $reps"
    } else {
        // Mixed weights - show each set
        sortedBy { it.setNumber }
            .joinToString(", ") { "${formatWeight(it.weightKg)} × ${it.reps}" }
    }
}
```

### Legacy Data Fallback

```kotlin
/**
 * For sessions without CompletedSet data, infer from aggregates
 */
fun WorkoutSession.toFallbackDisplay(): String {
    val estimatedSets = if (reps > 0 && workingReps > 0) {
        (workingReps / reps).coerceAtLeast(1)
    } else 1

    return if (reps > 0) {
        "$estimatedSets sets × ~$reps reps @ ${weightPerCableKg}kg"
    } else {
        "$totalReps reps (AMRAP) @ ${weightPerCableKg}kg"
    }
}
```

---

## 7. Repository Interface

```kotlin
interface CompletedSetRepository {
    suspend fun saveSet(set: CompletedSet)
    suspend fun getSetsForSession(sessionId: String): List<CompletedSet>
    suspend fun getSetsForExercise(exerciseId: String): List<CompletedSet>
    suspend fun deleteSet(setId: String)
    suspend fun deleteSetsForSession(sessionId: String)
}
```

---

## 8. ViewModel Changes

### MainViewModel Additions

```kotlin
// New state
private val _exerciseSummaries = MutableStateFlow<List<ExerciseSummary>>(emptyList())
val exerciseSummaries: StateFlow<List<ExerciseSummary>> = _exerciseSummaries.asStateFlow()

// Load exercise summaries
fun loadExerciseSummaries() {
    viewModelScope.launch {
        val sessions = workoutRepository.getAllSessions()
        val summaries = sessions
            .filter { it.exerciseId != null }
            .groupBy { it.exerciseId!! }
            .map { (exerciseId, sessions) ->
                val exercise = exerciseRepository.getExerciseById(exerciseId)
                ExerciseSummary(
                    exerciseId = exerciseId,
                    exerciseName = exercise?.name ?: "Unknown",
                    bestOneRepMax = calculateBestOneRepMax(sessions),
                    bestWeight = sessions.maxOf { it.weightPerCableKg },
                    lastPerformed = sessions.maxOf { it.timestamp },
                    totalSessions = sessions.size,
                    totalSets = sessions.sumOf { estimateSets(it) }
                )
            }
        _exerciseSummaries.value = summaries
    }
}

// Save set during workout
fun saveCompletedSet(
    sessionId: String,
    setNumber: Int,
    weight: Float,
    reps: Int,
    setType: SetType,
    rpe: Int?
) {
    viewModelScope.launch {
        val set = CompletedSet(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            setNumber = setNumber,
            weightKg = weight,
            reps = reps,
            setType = setType,
            rpe = rpe,
            isPr = false,  // Calculate separately
            completedAt = Clock.System.now().toEpochMilliseconds()
        )
        completedSetRepository.saveSet(set)
    }
}
```

---

## 9. Implementation Order

### Phase 1: Data Layer
1. Add CompletedSet table to SQLDelight schema
2. Create CompletedSetRepository interface and implementation
3. Register in Koin DI

### Phase 2: Tab Restructure
1. Rename tabs in AnalyticsScreen
2. Move DashboardTab content to Tab 0 (Overview)
3. Keep HistoryTab at Tab 1 (Log)
4. Create placeholder ExercisesTab at Tab 2

### Phase 3: Exercises Tab
1. Create ExerciseSummary data class
2. Add loadExerciseSummaries() to MainViewModel
3. Build ExercisesTab composable with search and sections

### Phase 4: Exercise Detail Screen
1. Create ExerciseDetailScreen composable
2. Add navigation route
3. Build OneRepMaxCard component
4. Build ProgressionChart component (line chart)
5. Build filtered history list

### Phase 5: Log Tab Refactor
1. Replace card-based layout with expandable list
2. Add filter chips
3. Add month separators
4. Integrate CompletedSet display

### Phase 6: Set Capture
1. Modify workout flow to save CompletedSet after each set
2. Wire up in MainViewModel.handleSetCompletion()

---

## 10. Files to Modify

| File | Changes |
|------|---------|
| `VitruvianDatabase.sq` | Add CompletedSet table |
| `AnalyticsScreen.kt` | Restructure tabs, add ExercisesTab |
| `HistoryAndSettingsTabs.kt` | Refactor to expandable list |
| `MainViewModel.kt` | Add exercise summaries, set saving |
| New: `ExerciseDetailScreen.kt` | Full detail view |
| New: `ExercisesTab.kt` | A-Z list with search |
| New: `CompletedSetRepository.kt` | Data access |
| New: `ProgressionChart.kt` | Trend line chart |

---

## Appendix: Relationship to Training Cycles Design

This design shares the `CompletedSet` table with the Training Cycles design (`2025-12-06-training-cycles-design.md`). The schema is compatible:

- Training Cycles adds `planned_set_id` FK (nullable here)
- Set types align (STANDARD, AMRAP, DROP_SET, WARMUP)
- RPE field exists in both

Implementation should coordinate to avoid duplicate table definitions.

---

*Document generated from brainstorming session on 2025-12-06*
