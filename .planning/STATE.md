# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-12)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.
**Current focus:** Phase 2 — Manager Decomposition

## Current Position

Phase: 2 of 4 (Manager Decomposition)
Plan: 3 of 4 complete in current phase
Status: Plan 02-03 complete, ready for Plan 02-04
Last activity: 2026-02-13 — RoutineFlowManager extracted from DWSM (~1,091 lines)

Progress: [███████░░░] 63%

## Performance Metrics

**Velocity:**
- Total plans completed: 5
- Average duration: ~43min per plan
- Total execution time: ~3.5 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 Characterization Tests | 2/2 | ~3h | ~1.5h |
| 02 Manager Decomposition | 3/4 | 27min | 9min |

**Recent Trend:**
- Last 5 plans: 01-01 (complete), 01-02 (complete), 02-01 (complete), 02-02 (complete), 02-03 (complete)
- Trend: Phase 2 plans averaging ~9min each

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

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: handleMonitorMetric() hot path (10-20Hz) performance must not regress — may need profiling
- [Phase 2]: Init block collector ordering documented: collectors #1-2 in RoutineFlowManager, #3+ in DWSM
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
Stopped at: Completed 02-03-PLAN.md (RoutineFlowManager extraction)
Resume file: None
