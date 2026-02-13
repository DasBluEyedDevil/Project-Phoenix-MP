# Requirements: Project Phoenix MP

**Defined:** 2026-02-12
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.

## v0.4.1 Requirements

Requirements for Architectural Cleanup milestone. Each maps to roadmap phases.

### Testing Foundation

- [ ] **TEST-01**: Characterization tests lock in workout lifecycle behavior (startWorkout, stopWorkout, handleSetCompletion, checkAutoStop, saveWorkoutSession)
- [ ] **TEST-02**: Characterization tests lock in routine flow behavior (loadRoutine, enterSetReady, advanceToNextExercise, superset navigation)
- [ ] **TEST-03**: Test fixtures provide pre-built workout states (WorkoutState.Active, WorkoutState.Resting, RoutineFlowState.SetReady, etc.)
- [ ] **TEST-04**: All characterization tests pass in commonTest (KMP-compatible, no platform-specific dependencies)

### Manager Decomposition

- [ ] **MGR-01**: WorkoutCoordinator extracts all shared MutableStateFlows and guard flags from DefaultWorkoutSessionManager into a dedicated state bus class
- [ ] **MGR-02**: RoutineFlowManager extracts routine CRUD, exercise/set navigation, and superset navigation from DefaultWorkoutSessionManager (~1,200 lines)
- [ ] **MGR-03**: ActiveSessionEngine extracts workout start/stop, rep processing, auto-stop, weight adjustment, Just Lift, and training cycle integration from DefaultWorkoutSessionManager (~1,800 lines)
- [ ] **MGR-04**: DefaultWorkoutSessionManager reduced to orchestration layer (~800 lines) delegating to sub-managers
- [ ] **MGR-05**: Circular dependency between BleConnectionManager and WorkoutSessionManager eliminated via SharedFlow event pattern (no more lateinit var)
- [ ] **MGR-06**: All existing tests pass after each extraction phase (zero behavior regression)
- [ ] **MGR-07**: MainViewModel public API unchanged — UI screens require zero modifications

### UI Decomposition

- [ ] **UI-01**: HistoryAndSettingsTabs.kt (2,750 lines) split into separate HistoryTab.kt and SettingsTab.kt files
- [ ] **UI-02**: WorkoutTab.kt (2,840 lines) decomposed into focused composable files (cards, dialogs, core screen)
- [ ] **UI-03**: Dialog composables extracted to own files (WorkoutSetupDialog, ModeSubSelectorDialog, ExercisePickerDialog)
- [ ] **UI-04**: All composables render identically after extraction (no visual regression)

### DI Cleanup

- [ ] **DI-01**: Single commonModule (30+ bindings) split into feature-scoped Koin modules (dataModule, syncModule, domainModule, presentationModule) using includes()
- [ ] **DI-02**: Koin verify() test confirms all DI bindings resolve correctly after module split
- [ ] **DI-03**: App starts and runs normally after Koin reorganization (no missing binding crashes)

## Future Requirements

Deferred beyond v0.4.1. Tracked but not in current roadmap.

### Architecture Evolution

- **ARCH-01**: Interface-backed sub-managers for swappable test implementations
- **ARCH-02**: WorkoutCoordinator state transition validation (state machine enforcement)
- **ARCH-03**: Separate ViewModels per feature screen (replaces MainViewModel facade)

### UI Modernization

- **UINEXT-01**: Screens take narrow interfaces instead of MainViewModel
- **UINEXT-02**: Navigation migration to Navigation 3 or Decompose
- **UINEXT-03**: Card composable extraction from WorkoutTab (ConnectionCard, RepCounterCard, LiveMetricsCard)

### Testing Expansion

- **TESTNEXT-01**: Full test coverage of all 60+ manager functions
- **TESTNEXT-02**: Compose UI tests for extracted composables
- **TESTNEXT-03**: Performance benchmarks for handleMonitorMetric hot path at 10-20Hz

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| KableBleRepository decomposition (2,886L) | Works reliably, high risk/low reward |
| Premium features (data foundation, biomechanics, intelligence) | Deferred to post-cleanup milestone |
| New user-facing features | This milestone is purely architectural |
| Reactive state management rewrite (MVI/Redux) | Would touch every function; keep imperative StateFlow style |
| Multi-module Gradle builds | App not large enough to benefit; use package organization |
| Managers registered in Koin | Lifecycle mismatch with viewModelScope; stay manually constructed |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| TEST-01 | — | Pending |
| TEST-02 | — | Pending |
| TEST-03 | — | Pending |
| TEST-04 | — | Pending |
| MGR-01 | — | Pending |
| MGR-02 | — | Pending |
| MGR-03 | — | Pending |
| MGR-04 | — | Pending |
| MGR-05 | — | Pending |
| MGR-06 | — | Pending |
| MGR-07 | — | Pending |
| UI-01 | — | Pending |
| UI-02 | — | Pending |
| UI-03 | — | Pending |
| UI-04 | — | Pending |
| DI-01 | — | Pending |
| DI-02 | — | Pending |
| DI-03 | — | Pending |

**Coverage:**
- v0.4.1 requirements: 18 total
- Mapped to phases: 0
- Unmapped: 18 ⚠️

---
*Requirements defined: 2026-02-12*
*Last updated: 2026-02-12 after initial definition*
