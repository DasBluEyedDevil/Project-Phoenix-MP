# Enhancement Implementation Progress

**Last Updated:** 2026-01-08
**Branch:** `enhancements`

## Completed Issues

### Issue #111: PR Tracking by Mode ✅
**Commits:** 8c8fdd0f, 48e2dbb4, 25d62acd, 7492508c

**What was implemented:**
- Mode-specific SQL queries (`selectBestWeightPRByMode`, `selectBestVolumePRByMode`)
- Updated `PersonalRecordRepository` interface with mode parameter
- Updated `MainViewModel` PR check logic to use mode-specific lookups
- Added mode context to PR celebration UI ("NEW OLD SCHOOL PR!")
- PRs grouped by mode in Analytics screen
- 18 unit tests for mode-specific PR tracking

**Key files:**
- `VitruvianDatabase.sq` - New queries
- `PersonalRecordRepository.kt` - Interface methods
- `SqlDelightPersonalRecordRepository.kt` - Implementation
- `PRCelebrationAnimation.kt` - UI updates
- `AnalyticsScreen.kt` - PR grouping by mode

---

### Issue #105: Show PR Beside Weight Config ✅
**Commits:** a164a66e, 2fbc4389, 13ed69b1, 9618d0c5, 07bc641a, fc49b3ee

**What was implemented:**
- `PRIndicator` component showing percentage and up/down arrow
- Float equality fix with epsilon comparison
- PR lookup in `ExerciseConfigViewModel`
- `WeightStepper` integration with optional `prWeight` parameter
- `ExerciseConfigModal` wiring for all mode panels
- Complete data flow through `ModeConfirmationScreen`

**Key files:**
- `PRIndicator.kt` - New component
- `WeightStepper.kt` - Added prWeight param
- `ExerciseConfigViewModel.kt` - PR state management
- `ExerciseConfigModal.kt` - Panel wiring
- `ModeConfirmationScreen.kt` - Data flow

---

### Issue #57: Scaling by % of PR ✅
**Commits:** cfe7e8d0, 136ba048, c455384b, 4d2b1b86, 69f91caf, b7bb7a07, e8924910

**What was implemented:**
- Domain model fields: `usePercentOfPR`, `weightPercentOfPR`, `prTypeForScaling`, `setWeightsPercentOfPR`
- Helper methods: `resolveWeight()`, `resolveSetWeights()`, `roundToHalfKg()`
- Edge case handling (zero percentage protection)
- iOS-safe database migration (7.sqm)
- `ResolveRoutineWeightsUseCase` for weight resolution
- UI controls: toggle, slider (50-120%), preset buttons (70%, 80%, 90%, 100%)
- Workout start integration in `MainViewModel`
- Backup/restore support
- 11 unit tests

**Key files:**
- `Routine.kt` - Domain model
- `VitruvianDatabase.sq` - Schema
- `migrations/7.sqm` - Migration
- `ResolveRoutineWeightsUseCase.kt` - Use case
- `ExerciseEditBottomSheet.kt` - UI controls
- `MainViewModel.kt` - Workout integration

---

### Issue #114: Import/Export Data UI ✅
**Commits:** ac2f2b4c, cbdaae2f, 71a01d58, c50f82d0, 9ed28e32, 2d2a7a15, a63d7640, 66ecd603, 4087d2b6

**What was implemented:**
- Complete backup/restore for all database tables (WorkoutSession, MetricSample, PersonalRecord, Exercise, Routine, RoutineExercise, TrainingCycle, CycleWeek, CycleDay)
- Platform-specific file picker interfaces (Android/iOS)
- Material 3 Expressive styled backup dialogs
- Export/Import buttons in Settings screen
- DataBackupManager with full SQL queries for backup/restore
- Koin DI wiring

**Key files:**
- `BackupModels.kt` - Data models for backup
- `DataBackupManager.kt` - Backup/restore logic
- `VitruvianDatabase.sq` - Backup queries
- `SettingsScreen.kt` - UI integration

---

### Issue #104: Analytics Volume Chart ✅
**Commits:** a725a3c8, 47dfd0cb, deae1467, 1a5ffe13, 25a57a25

**What was implemented:**
- SQL aggregation queries for weekly/monthly/yearly volume totals
- VolumeDataPoint and VolumePeriod data classes
- Repository methods with proper IO threading
- Canvas-based VolumeChartCard component (KMP compatible)
- AnalyticsViewModel with period selection state
- Integration into InsightsTab

**Key files:**
- `VitruvianDatabase.sq` - Aggregation queries
- `AnalyticsModels.kt` - Data classes
- `VolumeChartCard.kt` - Chart component
- `AnalyticsViewModel.kt` - State management
- `InsightsTab.kt` - Screen integration

---

## Remaining Issues (In Priority Order)

| # | Issue | Description | Plan File |
|---|-------|-------------|-----------|
| 1 | #113 | Just Lift Rest Timer | `2026-01-07-issue-113-just-lift-rest-timer.md` |
| 2 | #100 | Sound Improvements | `2026-01-07-issue-100-sound-improvements.md` |
| 3 | #30 | Variable Warm-up | `2026-01-07-issue-30-variable-warmup.md` |
| 4 | #29 | Echo Level Per Set | `2026-01-07-issue-29-echo-level-per-set.md` |
| 5 | #103 | Published Workouts (Phase 1) | `2026-01-07-issue-103-published-workouts.md` |

## Implementation Approach

Using **subagent-driven development**:
1. Create task list from plan
2. Dispatch implementer subagent per task
3. Spec reviewer validates completeness
4. Code quality reviewer checks standards
5. Fix any issues found
6. Commit and move to next task

## Resume Instructions

To continue implementation:

1. Check current branch: `git branch` (should be `enhancements`)
2. Check status: `git status` (should be clean)
3. Review this file for next issue
4. Read the corresponding plan file in `docs/plans/`
5. Use `superpowers:subagent-driven-development` skill
6. Create todo list from plan tasks
7. Implement task by task

## iOS Database Migration Note

**CRITICAL:** iOS has known SQLDelight migration issues (cashapp/sqldelight#1356):
- Use simple `ALTER TABLE ADD COLUMN` statements
- Avoid PRAGMA settings in migrations
- Don't use foreign key constraints in migration files
- Test on both Android and iOS simulators

## Git Summary

```
git log --oneline enhancements ^main
```

Shows all enhancement commits ready to push or create PR.
