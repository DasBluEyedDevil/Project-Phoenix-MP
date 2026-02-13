# Technology Stack: Refactoring Additions

**Project:** Project Phoenix MP - Architectural Decomposition
**Researched:** 2026-02-12
**Scope:** Stack additions for large-scale KMP refactoring (testing, DI patterns, Compose tooling)

## Current Stack (Already Validated - DO NOT Change)

| Technology | Version | Notes |
|------------|---------|-------|
| Kotlin | 2.3.0 | Upgraded from 2.0.21 documented in CLAUDE.md |
| Compose Multiplatform | 1.10.0 | Upgraded from 1.7.1 - includes unified @Preview |
| AGP | 9.0.0 | Upgraded from 8.5.2 |
| Koin | 4.1.1 | Upgraded from 4.0.0 |
| SQLDelight | 2.2.1 | Upgraded from 2.0.2 |
| Coroutines | 1.10.2 | Upgraded from 1.9.0 |

**Important:** The version catalog (`libs.versions.toml`) reflects significant upgrades beyond what CLAUDE.md documents. All research below targets the actual current versions.

## Recommended Stack Additions for Refactoring

### Testing (commonTest) - Already Configured, No Changes Needed

The project already has the right test stack configured in `shared/build.gradle.kts`:

| Library | Version | Artifact | Status |
|---------|---------|----------|--------|
| kotlin-test | 2.3.0 | `org.jetbrains.kotlin:kotlin-test` | Already in commonTest |
| kotlinx-coroutines-test | 1.10.2 | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Already in commonTest |
| Turbine | 1.2.1 | `app.cash.turbine:turbine` | Already in commonTest |
| Koin Test | 4.1.1 | `io.insert-koin:koin-test` | Already in commonTest |
| Multiplatform Settings Test | 1.3.0 | `com.russhwolf:multiplatform-settings-test` | Already in commonTest |

**Verdict: Add nothing.** The existing test dependencies are current and sufficient for characterization testing of extracted managers.

### What Each Library Covers for Refactoring

| Library | Use in Refactoring | Confidence |
|---------|-------------------|------------|
| **kotlin-test** | `@Test`, `assertEquals`, `assertTrue` for characterization tests proving manager behavior matches monolith | HIGH |
| **kotlinx-coroutines-test** | `runTest`, `TestScope`, `advanceUntilIdle()` for testing coroutine-heavy managers. `UnconfinedTestDispatcher` for StateFlow emission testing | HIGH |
| **Turbine** | `test { }` blocks on Flow/StateFlow for asserting emission sequences from managers. Use `awaitItem()`, `expectNoEvents()`, `cancelAndIgnoreRemainingEvents()` | HIGH |
| **Koin Test** | `verify()` on modules after splitting `commonModule` into sub-modules. Catches missing bindings at test time | HIGH |

### NOT Recommended - Libraries to Skip

| Library | Why Skip |
|---------|----------|
| **Kotest** | Adds an entirely separate testing framework. kotlin-test assertions are sufficient for characterization tests. The project already has 26+ test files using kotlin-test patterns. Switching frameworks mid-refactor adds unnecessary risk. |
| **MockK** | Already in version catalog (`mockk = "1.14.7"`) but NOT in commonTest dependencies. This is correct -- MockK does not support KMP commonTest (JVM/Android only). The project already uses hand-written fakes (FakeBleRepository, FakeWorkoutRepository, etc.), which is the right pattern for KMP. |
| **Compose UI Test** (`compose.uiTest`) | The refactoring is decomposing logic OUT of UI. Testing managers (pure Kotlin + coroutines) does not require Compose test infrastructure. Add Compose UI tests later when UI components are stabilized, not during decomposition. |
| **Koin Annotations / Compiler Plugin** | Over-engineering for this codebase size. Manual module definitions in AppModule.kt are readable and the team knows them. The compile-time verification is nice but `verify()` in tests achieves the same safety. |

## Koin Module Organization for Extracted Managers

### Current State (Single Module Problem)

`AppModule.kt` is a single `commonModule` with ~30 declarations. As managers are extracted, this needs decomposition.

### Recommended Pattern: Feature-Scoped Modules

Split `commonModule` into focused modules. Use Koin 4.1's `includes()` to compose them:

```kotlin
// di/modules/DataModule.kt
val dataModule = module {
    single { DatabaseFactory(get()).createDatabase() }
    single { ExerciseImporter(get()) }
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }
    single<UserProfileRepository> { SqlDelightUserProfileRepository(get()) }
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ProgressionRepository> { SqlDelightProgressionRepository(get()) }
}

// di/modules/ManagerModule.kt
val managerModule = module {
    single { BleConnectionManager(get()) }
    single { HistoryManager(get(), get()) }
    single { SettingsManager(get()) }
    single { GamificationManager(get()) }
    // WorkoutSessionManager gets its own sub-managers when decomposed
    single { DefaultWorkoutSessionManager(get(), get(), get(), get()) }
}

// di/modules/UseCaseModule.kt
val useCaseModule = module {
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }
    factory { ResolveRoutineWeightsUseCase(get()) }
    single { TemplateConverter(get()) }
}

// di/modules/ViewModelModule.kt
val viewModelModule = module {
    factory { MainViewModel(get(), get(), get(), get()) }  // fewer deps after manager extraction
    factory { ConnectionLogsViewModel() }
    factory { CycleEditorViewModel(get()) }
    factory { GamificationViewModel(get()) }
    single { ThemeViewModel(get()) }
    single { EulaViewModel(get()) }
    factory { LinkAccountViewModel(get()) }
}

// di/AppModule.kt (composition root)
val commonModule = module {
    includes(dataModule, managerModule, useCaseModule, viewModelModule, syncModule)
}
```

**Why this pattern:**
- Each module can be independently `verify()`-tested
- Managers can be tested with only `dataModule` loaded, not the whole graph
- MainViewModel's constructor shrinks as it delegates to managers
- `includes()` is a Koin 4.x feature that keeps composition clean

### Koin Module Verification Test

```kotlin
// commonTest/kotlin/.../di/ModuleVerificationTest.kt
class ModuleVerificationTest {
    @Test
    fun `verify all modules resolve correctly`() {
        dataModule.verify()
        managerModule.verify()
        useCaseModule.verify()
        viewModelModule.verify()
    }
}
```

**Note:** `verify()` replaces the deprecated `checkModules()` in Koin 4.x. It checks constructor parameter resolution without starting the full Koin container.

## Compose Decomposition Support

### Compose Multiplatform 1.10.0 Benefits (Already Available)

The project's upgrade to CMP 1.10.0 provides significant refactoring support:

| Feature | How It Helps | Confidence |
|---------|-------------|------------|
| **Unified @Preview** | Common `@Preview` annotation works across platforms. Extracted composables can be previewed individually in Android Studio without running the app. | HIGH |
| **Compose Hot Reload** | Stable in 1.10.0. Iterate on extracted UI components without full rebuild. | HIGH |
| **compose.uiTooling** | Already in `androidMain` dependencies. Provides `@Preview` rendering. | HIGH |

### Compose Decomposition Strategy (No New Libraries)

For decomposing WorkoutTab (2,840L) and HistoryAndSettingsTabs (2,750L):

**Pattern: Extract-and-Preview**
1. Extract a composable function to its own file
2. Add `@Preview` with hardcoded sample data
3. Verify visual correctness via preview
4. Wire to manager StateFlow via `collectAsState()`

```kotlin
// Before: 2,840 lines in WorkoutTab.kt
// After: Small composables in ui/workout/ package

@Composable
fun WorkoutControlPanel(
    weight: Float,
    mode: WorkoutMode,
    isConnected: Boolean,
    onWeightChange: (Float) -> Unit,
    onModeChange: (WorkoutMode) -> Unit,
)

@Preview
@Composable
private fun WorkoutControlPanelPreview() {
    WorkoutControlPanel(
        weight = 50f,
        mode = WorkoutMode.STANDARD,
        isConnected = true,
        onWeightChange = {},
        onModeChange = {},
    )
}
```

**No additional libraries needed.** The existing Compose Multiplatform 1.10.0 + compose.uiTooling setup handles previews. Extracted composables should be stateless (receive data, emit events) which makes them naturally previewable.

## Characterization Test Patterns for Manager Extraction

### Pattern: Snapshot Current Behavior, Then Extract

```kotlin
class WorkoutSessionManagerCharacterizationTest {
    private val bleRepo = FakeBleRepository()
    private val workoutRepo = FakeWorkoutRepository()
    private val exerciseRepo = FakeExerciseRepository()

    // Test captures CURRENT behavior before extraction
    @Test
    fun `starting workout creates session and sends BLE command`() = runTest {
        val manager = DefaultWorkoutSessionManager(bleRepo, workoutRepo, exerciseRepo)

        manager.startWorkout(exerciseId = "bench-press", weight = 80f)

        // Assert the observable side effects
        manager.currentSession.test {
            val session = awaitItem()
            assertNotNull(session)
            assertEquals("bench-press", session.exerciseId)
            assertEquals(80f, session.weight)
        }

        // Assert BLE command was sent
        assertTrue(bleRepo.lastSentCommand.isNotEmpty())
    }
}
```

### Pattern: Test Fake Base Class

The project already has excellent fakes. For new managers extracted from DefaultWorkoutSessionManager, follow the same pattern:

```kotlin
// Extend existing fakes, don't create mocking infrastructure
class FakeWorkoutSessionManager : WorkoutSessionManager {
    private val _currentSession = MutableStateFlow<WorkoutSession?>(null)
    override val currentSession: StateFlow<WorkoutSession?> = _currentSession

    // Test control methods
    fun emitSession(session: WorkoutSession) { _currentSession.value = session }
}
```

## Installation

**No new dependencies to install.** The existing `libs.versions.toml` and `shared/build.gradle.kts` already contain everything needed.

If Koin module splitting reveals the need for module-scoped testing:

```toml
# Already present in libs.versions.toml - no changes needed:
# koin-test = "4.1.1"  -> io.insert-koin:koin-test
# turbine = "1.2.1"    -> app.cash.turbine:turbine
# kotlinx-coroutines-test via kotlinx-coroutines = "1.10.2"
```

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Testing framework | kotlin-test (existing) | Kotest | Already 26+ files using kotlin-test. Migration cost with zero benefit for characterization tests. |
| Mocking | Hand-written fakes (existing) | MockK in commonTest | MockK does not support KMP commonTest. Fakes are already written for all major dependencies. |
| UI testing | @Preview visual verification | compose.uiTest | Premature -- decompose logic first, test UI later. compose.uiTest is still Experimental. |
| DI verification | `Module.verify()` | Koin Annotations compiler plugin | Over-engineering. Manual modules are readable at this scale. |
| Flow testing | Turbine (existing) | Manual Flow collection | Turbine handles timing, cancellation, and timeout correctly. Manual collection is error-prone. |

## Summary

**The stack is already correctly configured for refactoring.** The key insight: this project needs architectural work, not tooling work. The testing infrastructure (kotlin-test + coroutines-test + Turbine + Koin Test + hand-written fakes) is exactly right for characterization testing during manager extraction. The Compose Multiplatform 1.10.0 upgrade provides unified @Preview for UI decomposition.

The only "stack work" needed is organizational: splitting `AppModule.kt` into feature-scoped Koin modules as managers are extracted.

## Sources

- [Kotlin Multiplatform Testing Guide](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) - Testing patterns overview
- [Kotlin Multiplatform Run Tests](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) - Official test documentation
- [Turbine GitHub](https://github.com/cashapp/turbine) - Flow testing library (v1.2.1)
- [Koin Verify API](https://insert-koin.io/docs/reference/koin-test/verify/) - Module verification (replaces deprecated checkModules)
- [Koin 4.1 Release](https://blog.kotzilla.io/koin-4.1-is-here) - Safer configs, smarter scopes
- [Koin KMP Advanced Patterns](https://insert-koin.io/docs/reference/koin-mp/kmp/) - Module organization
- [Koin CheckModules Deprecation](https://insert-koin.io/docs/4.0/reference/koin-test/checkmodules/) - Deprecated in favor of verify()
- [Compose Multiplatform 1.10.0 Release](https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/) - Unified @Preview, Hot Reload, Navigation 3
- [Testing Compose Multiplatform UI](https://kotlinlang.org/docs/multiplatform/compose-test.html) - compose.uiTest (Experimental)
- [Koin KMP Professional Guide](https://medium.com/@SrimanthChowdary/koin-for-kotlin-multiplatform-kmp-a-professional-end-to-end-guide-a901fddc0d9b) - Module patterns
