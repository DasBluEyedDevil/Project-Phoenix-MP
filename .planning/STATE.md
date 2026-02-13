# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-12)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.
**Current focus:** Phase 3 in progress — UI Composable Decomposition

## Current Position

Phase: 3 of 4 (UI Composable Decomposition) -- COMPLETE
Plan: 2 of 2 in phase 3 (03-02 complete)
Status: Phase 3 complete. Ready for Phase 4.
Last activity: 2026-02-13 — Extracted WorkoutTab dialogs to dedicated files

Progress: [████████░░] 80%

## Performance Metrics

**Velocity:**
- Total plans completed: 8
- Average duration: ~31min per plan
- Total execution time: ~4.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 Characterization Tests | 2/2 | ~3h | ~1.5h |
| 02 Manager Decomposition | 4/4 | 56min | 14min |
| 03 UI Composable Decomposition | 2/2 | 19min | 10min |

**Recent Trend:**
- Last 5 plans: 02-02 (complete), 02-03 (complete), 02-04 (complete), 03-01 (complete), 03-02 (complete)
- Trend: Phase 3 completed in 19min total (2 plans)

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Managers stay OUT of Koin (manual construction in MainViewModel) — lifecycle mismatch with viewModelScope
- [Roadmap]: Concrete classes for sub-managers, not interfaces — test through DWSM public API with fake repos
- [Roadmap]: WorkoutCoordinator is a dumb state bus with zero methods — prevents it from becoming new monolith
- [Roadmap]: BLE commands stay co-located with state transitions in ActiveSessionEngine — not a separate concern
- [Phase 1]: advanceUntilIdle() MUST be placed after DWSMTestHarness construction and BEFORE loadRoutine() — init block infinite re-dispatch
- [Phase 1]: Navigation tests use advanceTimeBy(7000) not advanceUntilIdle() — startActiveWorkoutPolling re-awakens init collectors
- [Phase 1]: stopWorkout(exitingWorkout=false) between navigations — true clears _loadedRoutine
- [Phase 2]: No delegation properties on DWSM -- Kotlin overload resolution ambiguity; callers use coordinator directly
- [Phase 2]: MainViewModel accesses state via workoutSessionManager.coordinator.* (not workoutSessionManager.*)
- [Phase 2]: Tests access state via dwsm.coordinator.* for assertions
- [Phase 2]: BLE errors propagate via coordinator.bleErrorEvents SharedFlow (one-way: DWSM emits, BleConnectionManager collects)
- [Phase 2]: WorkoutStateProvider interface retained on BleConnectionManager for connection-loss detection
- [Phase 2]: WorkoutLifecycleDelegate interface bridges RoutineFlowManager BLE/startWorkout calls back to DWSM
- [Phase 2]: isBodyweightExercise() and isSingleExerciseMode() are top-level package functions (shared by RFM + DWSM)
- [Phase 2]: RoutineFlowManager navigation helpers (getNextStep, isInSuperset, etc.) are internal for DWSM access
- [Phase 2]: WorkoutFlowDelegate interface bridges ActiveSessionEngine -> RoutineFlowManager (no direct reference)
- [Phase 2]: Delegate wired in DWSM init block (not .also) for Kotlin internal visibility resolution
- [Phase 2]: proceedFromSummary() stays in DWSM as cross-cutting orchestration
- [Phase 2]: ActiveSessionEngine does NOT implement WorkoutLifecycleDelegate (that stays on DWSM)
- [Phase 3]: formatFloat and Float.pow are internal in SetSummaryCard.kt (used by both SetSummaryCard helpers and WorkoutTab composables)
- [Phase 3]: Same-package visibility eliminates need for import changes when splitting composable files

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: handleMonitorMetric() hot path (10-20Hz) performance must not regress — may need profiling
- [Phase 2]: Init block collector ordering documented: collectors #1-2 in RoutineFlowManager, #3-8 in ActiveSessionEngine
- [Phase 2]: SharedFlow event loss risk — inventory all shared flows before extraction
- [Phase 2]: DWSM init block creates infinite re-dispatch loops with advanceUntilIdle() — sub-managers will need same pattern

## Phase 1 Completion Summary

**38 characterization tests across 2 test classes:**
- DWSMWorkoutLifecycleTest: 16 tests (start, stop, reset, params, auto-stop, save)
- DWSMRoutineFlowTest: 22 tests (load, setReady, navigation, superset, overview, flow)

**Test infrastructure built:**
- DWSMTestHarness.kt — full DWSM construction with all 13+ fakes
- WorkoutStateFixtures.kt — one-liner factories for Active, SetReady states

**All tests pass in ~3 seconds via `./gradlew :shared:testDebugUnitTest`**

## Session Continuity

Last session: 2026-02-13
Stopped at: Completed 03-02-PLAN.md (WorkoutTab dialog extraction). Phase 3 complete. Ready for Phase 4.
Resume file: None
