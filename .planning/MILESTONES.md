# Milestones

## v0.4.0 — Foundation (Pre-GSD)

**Shipped:** BLE control, workout execution, exercise library, routines, supersets, training cycles, personal records, gamification, cloud sync, initial MainViewModel extraction.

**Phases completed:** N/A (pre-GSD — work tracked via git history and OpenSpec)

**Last phase number:** 0

---

## v0.4.1 — Architectural Cleanup (Shipped: 2026-02-13)

**Delivered:** Complete architectural decomposition of remaining monoliths with testing foundation.

**Phases completed:** 4 phases, 10 plans

**Key accomplishments:**
- Created 38 characterization tests with DWSMTestHarness and WorkoutStateFixtures
- Decomposed 4,024-line DefaultWorkoutSessionManager into 4 focused components (449L orchestration layer)
- Extracted WorkoutCoordinator (257L) as zero-method shared state bus
- Extracted RoutineFlowManager (1,091L) for routine CRUD and navigation
- Extracted ActiveSessionEngine (2,174L) for workout lifecycle and BLE commands
- Eliminated circular dependency via bleErrorEvents SharedFlow pattern
- Split 2,750-line HistoryAndSettingsTabs.kt into HistoryTab.kt + SettingsTab.kt
- Extracted SetSummaryCard, WorkoutSetupDialog, ModeSubSelectorDialog from WorkoutTab.kt
- Split 30+ binding commonModule into 4 feature-scoped Koin modules with verify() test

**Last phase number:** 4

**Archive:** `.planning/milestones/v0.4.1-*`

---

