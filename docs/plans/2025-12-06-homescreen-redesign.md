# HomeScreen Redesign: Pro Dashboard

**Date:** 2025-12-06
**Status:** Approved
**Replaces:** Launcher-style grid with 4 equal buttons

## Overview

Transform the HomeScreen from a generic launcher grid to a "Pro Dashboard" that answers three questions instantly:

1. **"What do I need to do today?"** - Up Next Hero Card
2. **"How consistent have I been?"** - Weekly Compliance Strip
3. **"How do I quickly start lifting?"** - Fixed Bottom Action Bar

## Design Principles

- **Focus on today's workout** - Hero card dominates the screen
- **Instant consistency feedback** - Week strip shows at-a-glance progress
- **One-tap workout start** - Bottom bar always accessible, never scrolled away
- **Preserve existing wrapper** - Top bar with dynamic topper and connect button unchanged

---

## 1. Screen Layout

```
┌─────────────────────────────────────┐
│  [Dynamic Topper]      [Connect 🔗] │  ← Existing Top Bar (UNCHANGED)
├─────────────────────────────────────┤
│  Welcome Back          🔥 5 Day     │  ← Header
│  Let's Crush It                     │
├─────────────────────────────────────┤
│  M  T  W  T  F  S  S                │  ← Week Strip (Mon-Sun)
│  ●  ●  ●  ○  ◐  ○  ○                │
├─────────────────────────────────────┤
│                                     │
│  ┌─────────────────────────────┐    │
│  │  UP NEXT                    │    │  ← Scrollable Content
│  │  Push Day                   │    │
│  │  4 Exercises                │    │
│  │  [Start Workout]            │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────┐  ┌─────────┐          │
│  │ Library │  │Programs │          │
│  └─────────┘  └─────────┘          │
│                                     │
├─────────────────────────────────────┤
│  ┌───────────┐  ┌───────────┐      │  ← Fixed Bottom Bar
│  │ Just Lift │  │ Single Ex │      │
│  └───────────┘  └───────────┘      │
├─────────────────────────────────────┤
│  🏠    📊    ⚙️                     │  ← Nav Bar (UNCHANGED)
└─────────────────────────────────────┘
```

---

## 2. Component Specifications

### 2.1 Header Section

```kotlin
@Composable
fun DashboardHeader(workoutStreak: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Welcome Back", style = Typography.bodyLarge)
            Text("Let's Crush It", style = Typography.headlineMedium, fontWeight = Bold)
        }

        // Streak badge (only if streak > 0)
        if (workoutStreak != null && workoutStreak > 0) {
            StreakBadge(days = workoutStreak)
        }
    }
}

@Composable
fun StreakBadge(days: Int) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(Icons.Default.LocalFireDepartment, tint = Color(0xFFFF6B00))
            Spacer(Modifier.width(4.dp))
            Text("$days Day Streak", fontWeight = Bold)
        }
    }
}
```

### 2.2 Weekly Compliance Strip

Shows the current week (Monday-Sunday), not rolling 7 days.

**Dot States:**
| State | Color | Meaning |
|-------|-------|---------|
| ● Filled green | `#10B981` | Workout completed |
| ◐ Today ring | Primary 30% alpha | Today (not yet done) |
| ● Filled green + ring | Green + primary ring | Today (completed) |
| ○ Empty gray | onSurfaceVariant 20% | Future day |
| ○ Empty gray | onSurfaceVariant 20% | Past day (missed) |

```kotlin
@Composable
fun WeeklyComplianceStrip(history: List<WorkoutSession>) {
    // Get current week's Monday
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val monday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
    val weekDays = (0..6).map { monday.plus(DatePeriod(days = it)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            weekDays.forEach { date ->
                val hasWorkout = history.any { session ->
                    Instant.fromEpochMilliseconds(session.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date == date
                }
                val isToday = date == today
                val isFuture = date > today

                DayDot(
                    dayLetter = date.dayOfWeek.name.first().toString(),
                    isToday = isToday,
                    hasWorkout = hasWorkout,
                    isFuture = isFuture
                )
            }
        }
    }
}

@Composable
fun DayDot(
    dayLetter: String,
    isToday: Boolean,
    hasWorkout: Boolean,
    isFuture: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = dayLetter,
            style = Typography.labelSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when {
                        hasWorkout -> Color(0xFF10B981)  // Green
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    }
                )
                .then(
                    if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
        )
    }
}
```

### 2.3 Up Next Hero Card

#### Active Program State

```kotlin
@Composable
fun ActiveProgramHero(
    program: WeeklyProgramWithDays,
    routines: List<Routine>,
    onStartRoutine: (String) -> Unit,
    onViewProgram: () -> Unit
) {
    val todayDayValue = KmpUtils.currentDayOfWeek() // 1=Mon ... 7=Sun
    val todayRoutineId = program.days.find { it.dayOfWeek == todayDayValue }?.routineId
    val todayRoutine = todayRoutineId?.let { id -> routines.find { it.id == id } }
    val isRestDay = todayRoutineId == null

    Card(
        modifier = Modifier.fillMaxWidth().shadow(16.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(
            modifier = Modifier.background(
                if (!isRestDay) {
                    Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
                } else {
                    Brush.linearGradient(listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainer
                    ))
                }
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Label row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Chip(text = if (isRestDay) "REST DAY" else "UP NEXT")
                    TextButton(onClick = onViewProgram) {
                        Text("View Schedule")
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isRestDay) {
                    // Rest day content
                    Text("Recovery & Rest", style = Typography.headlineMedium, fontWeight = Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Enjoy your rest day.", style = Typography.bodyLarge)
                } else {
                    // Workout day content
                    todayRoutine?.let { routine ->
                        Text(
                            routine.name,
                            style = Typography.headlineMedium,
                            fontWeight = Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${routine.exercises.size} Exercises",
                            style = Typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onStartRoutine(routine.id) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF4F46E5)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Workout", fontWeight = Bold)
                        }
                    }
                }
            }
        }
    }
}
```

#### No Program State

```kotlin
@Composable
fun NoProgramHero(
    onCreateProgram: () -> Unit,
    onJustLift: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.FitnessCenter, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("No Active Program", style = Typography.titleLarge, fontWeight = Bold)
            Text(
                "Create a weekly schedule or jump straight into lifting.",
                style = Typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onJustLift, modifier = Modifier.weight(1f)) {
                    Text("Just Lift")
                }
                Button(onClick = onCreateProgram, modifier = Modifier.weight(1f)) {
                    Text("Create Program")
                }
            }
        }
    }
}
```

### 2.4 Secondary Action Tiles

Optional tiles in the scrollable area for Library and Programs navigation.

```kotlin
@Composable
fun SecondaryActionTiles(
    onLibraryClick: () -> Unit,
    onProgramsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        QuickActionCard(
            title = "Library",
            icon = Icons.Default.CollectionsBookmark,
            color = Color(0xFF3B82F6),
            modifier = Modifier.weight(1f),
            onClick = onLibraryClick
        )
        QuickActionCard(
            title = "Programs",
            icon = Icons.Default.DateRange,
            color = Color(0xFF10B981),
            modifier = Modifier.weight(1f),
            onClick = onProgramsClick
        )
    }
}

@Composable
fun QuickActionCard(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, tint = color, modifier = Modifier.size(20.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(title, style = Typography.titleSmall, fontWeight = SemiBold)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
```

### 2.5 Fixed Bottom Action Bar

**Critical**: This bar is positioned above the navigation bar and does NOT scroll with content.

```kotlin
@Composable
fun FixedBottomActionBar(
    onJustLiftClick: () -> Unit,
    onSingleExerciseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Just Lift Button
            Button(
                onClick = onJustLiftClick,
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF9333EA)  // Purple
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.FitnessCenter, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Just Lift", fontWeight = FontWeight.Bold)
            }

            // Single Exercise Button
            Button(
                onClick = onSingleExerciseClick,
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEC4899)  // Pink
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Single Ex", fontWeight = FontWeight.Bold)
            }
        }
    }
}
```

---

## 3. Screen Structure

```kotlin
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode
) {
    // State collection
    val activeProgram by viewModel.activeProgram.collectAsState()
    val routines by viewModel.routines.collectAsState()
    val workoutStreak by viewModel.workoutStreak.collectAsState()
    val recentSessions by viewModel.allWorkoutSessions.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(gradientBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Scrollable content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Header
                DashboardHeader(workoutStreak)

                // 2. Weekly Strip
                WeeklyComplianceStrip(recentSessions)

                // 3. Hero Card
                if (activeProgram != null) {
                    ActiveProgramHero(
                        program = activeProgram!!,
                        routines = routines,
                        onStartRoutine = { routineId ->
                            viewModel.ensureConnection {
                                viewModel.loadRoutineById(routineId)
                                viewModel.startWorkout()
                                navController.navigate(NavigationRoutes.ActiveWorkout.route)
                            }
                        },
                        onViewProgram = {
                            navController.navigate(NavigationRoutes.WeeklyPrograms.route)
                        }
                    )
                } else {
                    NoProgramHero(
                        onCreateProgram = { navController.navigate(NavigationRoutes.WeeklyPrograms.route) },
                        onJustLift = { navController.navigate(NavigationRoutes.JustLift.route) }
                    )
                }

                // 4. Secondary Tiles
                SecondaryActionTiles(
                    onLibraryClick = { navController.navigate(NavigationRoutes.DailyRoutines.route) },
                    onProgramsClick = { navController.navigate(NavigationRoutes.WeeklyPrograms.route) }
                )

                // Bottom padding for scroll content
                Spacer(Modifier.height(20.dp))
            }

            // 5. Fixed Bottom Action Bar (NOT in scroll)
            FixedBottomActionBar(
                onJustLiftClick = { navController.navigate(NavigationRoutes.JustLift.route) },
                onSingleExerciseClick = { navController.navigate(NavigationRoutes.SingleExercise.route) }
            )
        }
    }
}
```

---

## 4. Implementation Notes

### Week Calculation

```kotlin
/**
 * Get Monday of the current week
 */
fun getWeekMonday(): LocalDate {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
}

/**
 * Get all days of current week (Mon-Sun)
 */
fun getCurrentWeekDays(): List<LocalDate> {
    val monday = getWeekMonday()
    return (0..6).map { monday.plus(DatePeriod(days = it)) }
}
```

### Connection Flow

The "Start Workout" button uses `ensureConnection` pattern:

```kotlin
viewModel.ensureConnection(
    onConnected = {
        viewModel.loadRoutineById(routineId)
        viewModel.startWorkout()
        navController.navigate(NavigationRoutes.ActiveWorkout.route)
    }
)
```

This triggers the connection overlay if not already connected.

---

## 5. Files to Modify

| File | Changes |
|------|---------|
| `HomeScreen.kt` | Complete rewrite with new layout |
| `MainViewModel.kt` | No changes needed (data already exposed) |
| Scaffold wrapper | No changes (top bar preserved) |

---

## 6. Summary of Changes from Original Design

| Original | New |
|----------|-----|
| 4 equal grid buttons | Hero card + secondary tiles |
| Just Lift/Single Ex in grid | Fixed bottom action bar |
| No week view | Weekly compliance strip (Mon-Sun) |
| Streak in separate card | Streak badge in header |
| Hardcoded "45 min" estimate | Removed (no fake data) |
| Rolling 7 days | Current week Mon-Sun |
| Rest day shows next workout date | Generic "Enjoy your rest day" |

---

*Document generated from brainstorming session on 2025-12-06*
