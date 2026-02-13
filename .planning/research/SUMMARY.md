# Project Research Summary

**Project:** Project Phoenix MP - Architectural Decomposition v0.4.1
**Domain:** KMP fitness app refactoring (4,024-line manager + 2,840-line UI screen decomposition)
**Researched:** 2026-02-12
**Confidence:** HIGH

## Executive Summary

This is a large-scale refactoring of a working KMP fitness app to decompose two architectural monoliths: a 4,024-line manager class (DefaultWorkoutSessionManager) and two 2,800+ line UI screens (WorkoutTab, HistoryAndSettingsTabs). The app controls Vitruvian workout machines via BLE, and the monoliths accumulated over months of feature additions. Prior cleanup phases (1-2) successfully extracted SettingsManager, HistoryManager, GamificationManager, and BleConnectionManager, proving the pattern works. This phase tackles the remaining God object.

The recommended approach is **conservative decomposition**: split DWSM into exactly two sub-managers (RoutineFlowManager for routine navigation/CRUD, ActiveSessionEngine for workout execution) coordinated by a shared state bus (WorkoutCoordinator). UI decomposition is mechanical file splitting since composables are already well-bounded. The tech stack is already correctly configured - no new dependencies needed. The primary risk is breaking the real-time metric processing loop (10-20Hz) or introducing state synchronization bugs between sub-managers.

Critical mitigations: (1) write characterization tests BEFORE extraction to lock in behavior, (2) keep all state mutations in the WorkoutCoordinator to prevent race conditions, (3) preserve the same public API so UI screens require zero changes, (4) do NOT over-decompose into 6+ tiny managers which would create worse coupling. The existing test infrastructure (9 fake repositories, WorkoutRobot, TestFixtures) is production-ready. Follow the established extraction pattern from phases 1-2. This is architectural cleanup, not a rewrite.

## Key Findings

### Recommended Stack

**Verdict: No changes needed.** The project already has the right tooling for refactoring.

The testing stack is correctly configured with kotlin-test (2.3.0), kotlinx-coroutines-test (1.10.2), Turbine (1.2.1), and Koin Test (4.1.1) for characterization tests. Hand-written fakes (FakeBleRepository, FakeWorkoutRepository, etc.) are already built and follow KMP best practices - no MockK needed since it doesn't support commonTest. Compose Multiplatform 1.10.0 provides unified @Preview for visual verification of extracted composables. The version catalog reflects significant upgrades beyond CLAUDE.md documentation (Kotlin 2.3.0, CMP 1.10.0, AGP 9.0.0).

**Core technologies:**
- **Kotlin 2.3.0 / CMP 1.10.0** - KMP app framework with unified @Preview for composable extraction
- **kotlinx-coroutines-test 1.10.2** - TestScope, runTest, advanceUntilIdle for manager coroutine testing (already in commonTest)
- **Turbine 1.2.1** - Flow/StateFlow emission testing with awaitItem(), expectNoEvents() (already in commonTest)
- **Koin 4.1.1** - DI with verify() for module splitting validation (checkModules deprecated)
- **Hand-written fakes** - KMP-compatible test doubles for all repositories (9 existing fakes in testutil/)

The only "stack work" is organizational: splitting the single `commonModule` (30+ bindings) into feature-scoped modules (dataModule, managerModule, useCaseModule, viewModelModule) using Koin's `includes()` as sub-managers are extracted.

### Expected Features

**Must have (table stakes):**
- **DefaultWorkoutSessionManager split into 2 sub-managers** - 4,024 lines with 8 responsibility clusters is unmaintainable; extraction is the core deliverable
- **Preserved public API surface** - MainViewModel already delegates 80+ functions to DWSM; UI must not change
- **Zero behavior regression** - Workout lifecycle, BLE commands, rep counting, auto-stop, superset navigation work identically
- **Characterization tests for workout lifecycle** - Lock in startWorkout (325L), handleSetCompletion (194L), checkAutoStop (212L), saveWorkoutSession (168L) before extraction
- **Characterization tests for routine flow** - Lock in loadRoutine, enterSetReady, advanceToNextExercise, superset navigation (1,200+ lines of coupled logic)
- **WorkoutTab.kt composable decomposition** - 2,840 lines with 20+ composables in one file; dialogs/cards to separate files
- **HistoryAndSettingsTabs.kt split** - 2,750 lines mixing two already-separate composable functions; trivial to split
- **Koin module reorganization** - Single commonModule has 30+ bindings with no structure; split into feature modules with includes()

**Should have (competitive):**
- **WorkoutCoordinator shared state bus** - Centralizes 15+ MutableStateFlows that sub-managers share; prevents lateinit/circular dependency smell
- **Interface-backed sub-managers** - Enables swapping implementations in tests; cleaner dependency graph (HistoryManager inconsistency exists)
- **Extracted dialog composables** - WorkoutSetupDialog (434L), ModeSubSelectorDialog (172L), ExercisePickerDialog (149L) to separate files
- **Turbine-based flow testing** - Cleaner StateFlow assertion API than manual collect/advanceUntilIdle
- **Koin verification tests** - Ensure all DI bindings resolve correctly after module split

**Defer (v2+):**
- **New ViewModel per feature** - Splitting MainViewModel would require rewriting all navigation; massive scope creep
- **Decompose/Voyager navigation migration** - Separate concern from manager decomposition
- **Reactive state management rewrite (MVI/Redux)** - Would touch every function; keep imperative StateFlow style
- **Multi-module Gradle builds** - App not large enough to benefit; use package organization
- **Full test coverage of all 60+ manager functions** - Characterize critical paths only; defer edge cases

### Architecture Approach

**Target: 2-sub-manager decomposition coordinated by shared state bus.**

Split DWSM (4,024L) into WorkoutCoordinator (200L state bus), RoutineFlowManager (1,200L for routine nav/CRUD), and ActiveSessionEngine (1,800L for workout execution/auto-stop/weight). DefaultWorkoutSessionManager becomes 800L coordinator delegating to sub-managers. This is the minimum viable decomposition - deeper splits (separate AutoStopManager, WeightManager) create more coupling, not less.

**Major components:**
1. **WorkoutCoordinator** - Holds all 30+ MutableStateFlows, exposes read-only StateFlows; dumb data container with zero methods
2. **RoutineFlowManager** - Routine CRUD, exercise/set/superset navigation, set-ready flow; reads coordinator state, writes routine position
3. **ActiveSessionEngine** - Start/stop/pause, rep processing, auto-stop, weight adjustment, Just Lift, training cycles, session saving; reads/writes coordinator, sends BLE commands
4. **DefaultWorkoutSessionManager** - Public API, init block wiring, delegates to sub-managers; preserves existing facade for MainViewModel

**Critical patterns:**
- Coordinator as shared state bus prevents race conditions from split mutable state (Pitfall 1)
- All sub-managers share the same parent CoroutineScope (viewModelScope) to prevent job lifecycle chaos (Pitfall 2)
- handleMonitorMetric() stays as orchestration point; sub-managers expose processing functions called synchronously (Pitfall 3)
- Event-based BLE error reporting (SharedFlow) eliminates the existing lateinit var bleConnectionManager smell (Pitfall 4)
- Keep managers OUT of Koin; manually construct in MainViewModel matching phases 1-2 pattern (Pitfall 5)

### Critical Pitfalls

1. **Split mutable state creates race conditions without WorkoutCoordinator** - 30+ MutableStateFlows + 15 mutable vars across sub-managers cause race conditions when handleSetCompletion reads workoutState from one manager but another already transitioned it; keep state machine in single coordinator; all mutations through coordinator
2. **Coroutine scope sharing creates lifecycle chaos** - 35+ scope.launch calls with tracked jobs (monitorDataCollectionJob, restTimerJob, workoutJob); sub-managers MUST share parent viewModelScope or cancellation from one won't reach jobs in another
3. **Breaking handleMonitorMetric() hot path** - Called at 10-20Hz, touches rep counting + auto-stop + metrics + phase animation; splitting into multiple sub-manager calls adds overhead; keep as single orchestration point calling sub-manager functions synchronously
4. **Circular dependency proliferation via lateinit** - Existing bleConnectionManager lateinit pattern; naive extraction creates RoutineFlow needs WorkoutExecution needs RoutineFlow cycles; use event bus/callback interfaces, unidirectional dependencies (sub-managers depend on coordinator only)
5. **Koin module registration order and scoping failures** - DWSM currently NOT in Koin (manual construction in MainViewModel); moving to Koin creates circular dep with BleConnectionManager; keep manual construction matching SettingsManager/HistoryManager/GamificationManager pattern

## Implications for Roadmap

Based on research, suggested 4-phase structure:

### Phase 1: Characterization Tests (Foundation)
**Rationale:** Safety inspector test plan already specifies exact tests; 9 fake repositories exist; lowest-risk starting point; MUST pass before extraction
**Delivers:** Test suite locking in current behavior for startWorkout, stopWorkout, handleSetCompletion, checkAutoStop, loadRoutine, enterSetReady, advanceToNextExercise
**Addresses:** Zero behavior regression (table stakes), test infrastructure (table stakes)
**Avoids:** Pitfall 3 (detecting hot path breakage), Pitfall 1 (detecting race conditions), Pitfall 7 (KMP test patterns established early)

### Phase 2: DefaultWorkoutSessionManager Decomposition
**Rationale:** Core deliverable; split along natural seams identified by archaeologist analysis (Rounds 1-8); RoutineFlow is cleanest extraction (mostly reads shared state); ActiveSession is hardest (touches everything)
**Delivers:** WorkoutCoordinator (200L state bus), RoutineFlowManager (1,200L), ActiveSessionEngine (1,800L), reduced DWSM (800L coordinator)
**Uses:** kotlinx-coroutines-test for manager tests, Turbine for StateFlow testing, TestFixtures for workout states
**Implements:** Coordinator as shared state bus, event-based BLE error reporting, unidirectional dependencies
**Avoids:** Pitfall 1 (coordinator holds all state), Pitfall 2 (shared scope), Pitfall 3 (metric orchestration point), Pitfall 4 (event bus eliminates lateinit), Pitfall 9 (BLE commands stay co-located with state transitions)

### Phase 3: UI Composable Decomposition
**Rationale:** Mechanical file splitting; composables already well-bounded; independent from manager work
**Delivers:** HistoryTab.kt + SettingsTab.kt (split from 2,750L file), extracted WorkoutTab dialogs (WorkoutSetupDialog, ModeSubSelectorDialog, ExercisePickerDialog), extracted WorkoutTab cards (ConnectionCard, RepCounterCard, LiveMetricsCard), extracted SettingsTab sections (4 sections from 1,540L)
**Uses:** Compose Multiplatform 1.10.0 unified @Preview for visual verification
**Avoids:** Pitfall 6 (maintain same StateFlow signatures to prevent recomposition cascades)

### Phase 4: Koin Module Reorganization
**Rationale:** Add sub-managers create DI binding sprawl; split commonModule into feature modules; verify() prevents missing bindings
**Delivers:** dataModule, managerModule (if managers move to Koin), useCaseModule, viewModelModule; Koin verify() test
**Implements:** Koin includes() hierarchy
**Avoids:** Pitfall 5 (manual construction pattern OR careful module ordering if Koin-registered)

### Phase Ordering Rationale

- **Phase 1 before Phase 2**: Characterization tests are the prerequisite gate; extraction without tests is unsafe
- **Phase 2 before Phase 4**: Cannot reorganize Koin until managers are extracted and their dependencies are known
- **Phase 3 parallel to Phase 2**: UI decomposition is independent IF no API changes; WorkoutTab uses WorkoutUiState wrapper already
- **Phase 4 last**: DI cleanup after all architectural changes stabilize

### Research Flags

**Needs deeper research during planning:**
- **Phase 2:** handleMonitorMetric() hot path performance - may need profiling to validate no regression at 10-20Hz
- **Phase 2:** Init block collector ordering - document startup sequence before splitting across sub-managers
- **Phase 2:** SharedFlow event loss - inventory all shared flows (hapticEvents, userFeedbackEvents, connectionErrors) before extraction

**Standard patterns (skip research-phase):**
- **Phase 1:** Characterization test patterns well-documented; safety inspector test plan provides specs
- **Phase 3:** Compose file extraction is mechanical; @Preview usage is standard
- **Phase 4:** Koin module splitting is well-established pattern

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Version catalog analysis + existing test infrastructure audit; no new dependencies needed |
| Features | HIGH | Direct codebase analysis (4,024L DWSM, 2,840L WorkoutTab, 2,750L HistoryAndSettingsTabs); archaeologist/architect/surgeon/safety-inspector docs provide exact scope |
| Architecture | HIGH | Existing phases 1-2 extraction pattern proven (SettingsManager, HistoryManager, GamificationManager, BleConnectionManager); target structure validated against coupling analysis |
| Pitfalls | HIGH | Direct codebase analysis of state management (30+ flows, 15+ vars), coroutine usage (35+ launch calls, 6 tracked jobs), BLE interaction points (15+ calls), init block collectors (8 collectors) |

**Overall confidence:** HIGH

### Gaps to Address

**Gap 1: WorkoutCoordinator design vs interface extraction inconsistency**
- HistoryManager was planned as interface in architect-interface-design.md but shipped as concrete class
- Decision needed: interfaces for sub-managers (testability) OR concrete classes (YAGNI)?
- Recommendation: Concrete classes - test through DWSM public API with fake repositories (existing pattern); interfaces premature for internal implementation details

**Gap 2: Manager Koin registration strategy**
- Phases 1-2 managers (SettingsManager, HistoryManager, GamificationManager, BleConnectionManager) are NOT in Koin - manually constructed in MainViewModel
- Decision needed: continue manual construction OR move to Koin?
- Recommendation: Stay out of Koin until screens access managers directly (UI decoupling phase); managers need viewModelScope which doesn't map to Koin single/factory lifecycle

**Gap 3: Metric collection performance baseline**
- handleMonitorMetric() called at 10-20Hz; no existing performance metrics for regression detection
- Recommendation: Add Layout Inspector recomposition count check + battery drain comparison as acceptance criteria for Phase 2

## Sources

### Primary (HIGH confidence)
- Direct codebase analysis: DefaultWorkoutSessionManager.kt (4,024L), WorkoutTab.kt (2,840L), HistoryAndSettingsTabs.kt (2,750L), MainViewModel.kt (420L), AppModule.kt (92L), BleConnectionManager.kt (~200L)
- Existing refactoring docs: `.planning/refactoring/archaeologist-context-map.md`, `architect-interface-design.md`, `surgeon-extraction-plan.md`, `safety-inspector-test-plan.md`, `ui-decoupler-analysis.md`
- Version catalog audit: `libs.versions.toml` (Kotlin 2.3.0, CMP 1.10.0, AGP 9.0.0, Koin 4.1.1, SQLDelight 2.2.1, Coroutines 1.10.2)
- Test infrastructure audit: 26+ test files using kotlin-test, 9 fakes in testutil/, WorkoutRobot.kt, TestFixtures.kt

### Secondary (MEDIUM confidence)
- [Kotlin Multiplatform Testing Guide](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) - KMP testing patterns
- [Koin 4.1 Release](https://blog.kotzilla.io/koin-4.1-is-here) - verify() API, module includes()
- [Compose Multiplatform 1.10.0 Release](https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/) - Unified @Preview, Hot Reload
- [Turbine GitHub](https://github.com/cashapp/turbine) - Flow testing library v1.2.1
- [Koin Verify API](https://insert-koin.io/docs/reference/koin-test/verify/) - Module verification

### Tertiary (LOW confidence)
- None - all research based on direct codebase inspection or official library documentation

---
*Research completed: 2026-02-12*
*Ready for roadmap: yes*
