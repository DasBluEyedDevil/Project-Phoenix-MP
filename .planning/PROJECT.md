# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines (V-Form, Trainer+) via BLE. Community rescue project keeping machines functional after company bankruptcy. Supports Android (Compose) and iOS (SwiftUI) from a shared KMP codebase.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ BLE connection to V-Form (`Vee_*`) and Trainer+ (`VIT*`) devices — v0.1
- ✓ 6 workout modes (Old School, Eccentric, etc.) with real-time metric display — v0.1
- ✓ Rep counting via machine + position-based phase detection — v0.2
- ✓ Exercise library with muscle groups, equipment, video support — v0.2
- ✓ Routines with supersets, set/rep/weight tracking — v0.2
- ✓ Personal records with 1RM calculation (Brzycki, Epley) — v0.3
- ✓ Gamification: XP, badges, workout streaks — v0.3
- ✓ Training cycles with day rotation — v0.3
- ✓ Cloud sync infrastructure — v0.4
- ✓ MainViewModel decomposition into 5 managers (History, Settings, BLE, Gamification, WorkoutSession) — v0.4
- ✓ DefaultWorkoutSessionManager decomposed into WorkoutCoordinator + RoutineFlowManager + ActiveSessionEngine — v0.4.1
- ✓ Circular dependency eliminated via bleErrorEvents SharedFlow pattern — v0.4.1
- ✓ Koin DI split into 4 feature-scoped modules with verify() test — v0.4.1
- ✓ HistoryAndSettingsTabs split into focused files (HistoryTab + SettingsTab) — v0.4.1
- ✓ 38 characterization tests covering workout lifecycle and routine flow — v0.4.1
- ✓ Testing foundation with DWSMTestHarness and WorkoutStateFixtures — v0.4.1

### Active

<!-- Current scope. Building toward these. -->

See REQUIREMENTS.md for v0.4.5 milestone requirements.

## Current Milestone: v0.4.5 Premium Features Phase 1

**Goal:** Ship the first premium features (LED biofeedback, rep quality scoring, smart suggestions) with proper data foundation and subscription gating.

**Target features:**
- Data Foundation (Spec 00 Phase A) — RepMetric table, SubscriptionTier enum, FeatureGate utility, migration v13
- Real-Time Feedback (Spec 02) — LED biofeedback with velocity zones, rep quality scoring (0-100), HUD indicators
- Smart Suggestions (Spec 03.2 extract) — Push/pull/legs balance, plateau detection, exercise variety prompts

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- KableBleRepository decomposition — works reliably, refactoring risk outweighs benefit
- Biomechanics MVP (Spec 01) — depends on velocity pipeline, Phase 2 work
- Auto-Regulation (Spec 03.3-4) — depends on Spec 01 velocity pipeline
- Portal sync backend (Spec 05) — Phase 3 infrastructure work
- Portal replay features — no backend exists yet
- iOS-specific UI work — focus is shared module and Android Compose layer
- BLE protocol changes — no hardware interaction changes

## Context

- App is at v0.4.1, actively used by community
- MainViewModel is a thin 420-line facade delegating to 5 specialized managers
- DefaultWorkoutSessionManager (449 lines) orchestrates 3 sub-managers:
  - WorkoutCoordinator (257L) — shared state bus, zero business logic
  - RoutineFlowManager (1,091L) — routine CRUD, navigation, supersets
  - ActiveSessionEngine (2,174L) — workout lifecycle, BLE commands, auto-stop
- UI composables decomposed: WorkoutTab (1,495L), HistoryTab (1,060L), SettingsTab (1,704L)
- 38 characterization tests lock in workout and routine behavior for safe refactoring
- Koin DI: 4 feature-scoped modules (data, sync, domain, presentation) with verify() test
- Test infrastructure: DWSMTestHarness, WorkoutStateFixtures, fakes for all repositories
- OpenSpec specs (00-05) drafted for future premium features
- ~19,955 lines of Kotlin in shared module

## Constraints

- **Platform**: KMP shared module — all business logic must remain in commonMain
- **Compatibility**: No breaking changes to existing workout behavior — characterization tests first
- **BLE stability**: Do not touch KableBleRepository or BLE protocol code
- **Incremental**: Each refactoring phase must leave the app in a buildable, working state

## Current State

**Version:** v0.4.1 (shipped 2026-02-13)

Architecture is clean and well-tested. Ready for feature development or premium features.

## Next Milestone Goals

After v0.4.5:
- **v0.5.0** — Biomechanics MVP (Spec 01 Phases 1-3: VBT engine, velocity HUD, set summary)
- **v0.5.5** — Mobile Platform Features (Spec 04: strength assessment, exercise auto-detection)
- **v0.6.0** — Auth Migration (Spec 05a: Supabase auth, user migration)

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Manager extraction pattern (scope injection, interface-based) | Enables testability, preserves ViewModel lifecycle | ✓ Good |
| MainViewModel as thin facade during transition | Preserves UI API while extracting logic incrementally | ✓ Good — UI unchanged |
| Leave KableBleRepository alone | Works reliably, high risk/low reward to refactor | ✓ Good — validated |
| Characterize before refactoring | Tests lock in behavior, catch regressions during extraction | ✓ Good — 38 tests |
| WorkoutCoordinator as zero-method state bus | Sub-managers share state without circular refs | ✓ Good — v0.4.1 |
| Delegate pattern for sub-manager bridging | WorkoutLifecycleDelegate/WorkoutFlowDelegate avoid direct refs | ✓ Good — v0.4.1 |
| bleErrorEvents SharedFlow for BLE→DWSM | Eliminates lateinit var circular dependency | ✓ Good — v0.4.1 |
| Same-package visibility for UI extractions | Zero import changes needed when splitting files | ✓ Good — v0.4.1 |
| Feature-scoped Koin modules with verify() | Catches DI wiring issues at test time | ✓ Good — v0.4.1 |

---
*Last updated: 2026-02-13 after v0.4.5 milestone start*
