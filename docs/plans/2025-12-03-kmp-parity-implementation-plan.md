# KMP Parity Implementation Plan

**Created:** 2025-12-03
**Status:** Ready for Implementation
**Approach:** Bottom-Up (Infrastructure First)

## Executive Summary

This plan addresses cross-platform parity issues and code quality improvements identified in the Project Phoenix codebase. After comprehensive research, many "issues" were found to be stale TODO comments referencing already-completed work. The actual changes required are minimal but important for code quality and user experience.

### Key Findings

| Issue | Reported Severity | Actual Severity | Reason |
|-------|-------------------|-----------------|--------|
| UUID Generation | HIGH | LOW | Already implemented via expect/actual |
| Preferences/Settings | HIGH | LOW | Already using multiplatform-settings |
| ThemeViewModel | MEDIUM | MEDIUM | Exists but not wired to persistence |
| Theme Persistence | MEDIUM | MEDIUM | Infrastructure exists, needs connection |
| Stale TODOs | LOW | LOW | 66 TODOs, ~40 are outdated |
| iOS BLE Priority | LOW | LOW | iOS CoreBluetooth handles automatically |

### Scope

- **In Scope:** TODO cleanup, theme persistence, code quality improvements
- **Out of Scope:** New features, chart library integration, iOS BLE priority (no API equivalent)

---

## Phase 1: Foundation Cleanup

**Goal:** Remove noise and fix the one actual bug before making structural changes.
**Estimated Effort:** 1-2 hours
**Risk Level:** Low

### 1.1 Remove Stale TODO Comments

These TODOs reference work that's already complete:

#### ThemeViewModel.kt (12 TODOs to remove)
- Line 3: `// TODO: Replace Android Context with expect/actual pattern`
- Line 5: `// TODO: Replace AndroidX DataStore with multiplatform datastore`
- Line 14: `// TODO: Replace Hilt with Koin for dependency injection`
- Line 25: `// TODO: Replace Android DataStore implementation with multiplatform solution`
- Line 29: `// TODO: Replace @HiltViewModel with Koin annotations`
- Line 31: `// TODO: Replace @Inject constructor with Koin injection`
- Line 32: `// TODO: Replace @ApplicationContext with platform-agnostic context access`
- Line 34: `// TODO: Replace Android Context with expect/actual pattern`
- Line 38: `// TODO: Implement expect/actual pattern for DataStore access`
- Line 42: `// TODO: Replace with actual DataStore implementation for KMP`
- Line 49: `// TODO: Load initial theme from platform-specific storage`
- Line 56: `// TODO: Replace with actual DataStore write for KMP`
- Line 63: `// TODO: Implement expect/actual pattern for theme persistence`

**Reality:** Project uses `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4` which is already multiplatform. Koin is already in use.

#### ExerciseConfigViewModel.kt (8 TODOs to remove)
- Lines 9, 16, 41, 43: Hilt/Timber replacement TODOs
- Lines 125, 151, 181, 280: Timber logging TODOs

**Reality:** Project uses Koin for DI and Kermit for logging.

#### Other Files (6 TODOs to remove)
- `PreferencesManager.kt:81` - "Implement with multiplatform-settings" (already done)
- `RepCounterFromMachine.kt:6,460` - "Timber logging needs expect/actual" (using Kermit)
- `Models.kt:290,314` - "UUID generation needs expect/actual" (already implemented)
- `Stubs.kt:5` - "Replace with proper expect/actual" (evaluate if still needed)

### 1.2 Fix ExerciseConfigViewModel UUID Duplicate

**Location:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt`

**Current Bug (lines 331-335):**
```kotlin
// TODO: Implement expect/actual pattern for UUID generation
private fun generateUUID(): String {
    // Placeholder - needs platform-specific implementation
    return "uuid-${KmpUtils.currentTimeMillis()}-${(0..999).random()}"
}
```

**Fix:**
1. Delete the local `generateUUID()` function
2. Add import: `import com.devil.phoenixproject.domain.model.generateUUID`
3. Existing calls will now use the proper multiplatform implementation

### 1.3 Files Modified

| File | Changes |
|------|---------|
| `ThemeViewModel.kt` | Remove 12 stale TODOs |
| `ExerciseConfigViewModel.kt` | Remove 8 TODOs, delete duplicate UUID function, add import |
| `PreferencesManager.kt` | Remove 1 stale TODO |
| `RepCounterFromMachine.kt` | Remove 2 stale TODOs |
| `Models.kt` | Remove 2 stale TODOs |
| `Stubs.kt` | Evaluate and update 1 TODO |

### 1.4 Verification

```bash
./gradlew build
./gradlew :shared:allTests
```

### 1.5 Commit

```
chore: remove stale TODO comments and fix UUID duplicate

- Remove 26 outdated TODOs referencing already-completed KMP migration
- Fix ExerciseConfigViewModel to use shared generateUUID() function
- No functional changes to runtime behavior
```

---

## Phase 2: Theme Persistence Wiring

**Goal:** Connect existing infrastructure so theme preferences persist across app restarts.
**Estimated Effort:** 2-3 hours
**Risk Level:** Medium

### 2.1 Architecture Overview

Current state (disconnected):
```
ThemeViewModel ──X── PreferencesManager ──✓── Settings (platform)
      │
      X (not injected)
      │
   App.kt (local mutableState - theme resets on restart)
```

Target state (connected):
```
ThemeViewModel ──✓── PreferencesManager ──✓── Settings (platform)
      │
      ✓ (Koin injection)
      │
   App.kt (observes ViewModel state - theme persists)
```

### 2.2 Changes Required

#### File 1: ThemeViewModel.kt

**Before:**
```kotlin
class ThemeViewModel : ViewModel() {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }
}
```

**After:**
```kotlin
class ThemeViewModel(
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        loadSavedTheme()
    }

    private fun loadSavedTheme() {
        viewModelScope.launch {
            val savedScheme = preferencesManager.preferencesFlow.value.colorScheme
            _themeMode.value = ThemeMode.entries.getOrElse(savedScheme) { ThemeMode.SYSTEM }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            _themeMode.value = mode
            preferencesManager.setColorScheme(mode.ordinal)
        }
    }
}
```

#### File 2: AppModule.kt

**Add to commonModule:**
```kotlin
factory { ThemeViewModel(get()) }
```

#### File 3: App.kt

**Before:**
```kotlin
@Composable
fun App() {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }

    VitruvianTheme(themeMode = themeMode) {
        EnhancedMainScreen(
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it }
        )
    }
}
```

**After:**
```kotlin
@Composable
fun App() {
    val themeViewModel: ThemeViewModel = koinViewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()

    VitruvianTheme(themeMode = themeMode) {
        EnhancedMainScreen(
            themeMode = themeMode,
            onThemeModeChange = { themeViewModel.setThemeMode(it) }
        )
    }
}
```

### 2.3 Files Modified

| File | Changes |
|------|---------|
| `ThemeViewModel.kt` | Add PreferencesManager injection, load/save logic |
| `AppModule.kt` | Register ThemeViewModel in Koin |
| `App.kt` | Use koinViewModel() instead of local state |

### 2.4 Verification

1. Build and run on Android
2. Change theme to DARK
3. Force-stop app
4. Reopen app
5. Verify theme is still DARK

Repeat for iOS.

### 2.5 Commit

```
feat: wire ThemeViewModel to PreferencesManager for persistence

- Inject PreferencesManager into ThemeViewModel
- Load saved theme preference on app startup
- Persist theme changes to platform storage
- Theme now survives app restarts on both Android and iOS
```

---

## Phase 3: Remaining TODO Cleanup

**Goal:** Address remaining valid TODOs and improve code consistency.
**Estimated Effort:** 1-2 hours
**Risk Level:** Low

### 3.1 TODOs to Keep (Valid Backlog Items)

These represent genuine incomplete work:

| File | Line | TODO | Action |
|------|------|------|--------|
| `ComboChart.kt` | 20,41 | Vico chart library | Update: "BLOCKED: Vico KMP support pending" |
| `WorkoutMetricsDetailChart.kt` | 21,41 | Vico chart library | Update: "BLOCKED: Vico KMP support pending" |
| `AreaChart.kt` | 22,29,52 | compose-charts library | Update: "BLOCKED: compose-charts KMP support pending" |
| `RadarChart.kt` | 33,157 | Canvas text rendering | Keep as-is (valid limitation) |
| `GaugeChart.kt` | 32,130 | Canvas text rendering | Keep as-is (valid limitation) |
| `CircleChart.kt` | 86 | Segment click detection | Keep as-is (feature backlog) |
| `ExerciseLibraryViewModel.kt` | 13,32 | Full implementation pending | Keep as-is (feature backlog) |
| `ExerciseEditDialog.kt` | 16,48 | Stub implementation | Keep as-is (feature backlog) |
| `SqlDelightWorkoutRepository.kt` | 239,243,415 | Delete queries | Keep as-is (feature backlog) |
| `MainViewModel.kt` | 616,617,1134,1144 | Preferences/packet parsing | Keep as-is (feature backlog) |

### 3.2 Import Path Fixes

These TODOs indicate broken imports that should be fixed:

| File | Line | Issue | Fix |
|------|------|-------|-----|
| `ConnectionStatusBanner.kt` | 13 | "Import Spacing from theme" | Update import path, remove TODO |
| `EmptyStateComponent.kt` | 14 | "Import Spacing from theme" | Update import path, remove TODO |
| `SafetyEventsCard.kt` | 15 | "Import Spacing from theme" | Update import path, remove TODO |
| `ShimmerEffect.kt` | 18 | "Import Spacing from theme" | Update import path, remove TODO |

**Correct Import:**
```kotlin
import com.devil.phoenixproject.ui.theme.Spacing
```

### 3.3 Optional Logging Additions

Where TODOs mention "add logging when available", optionally add Kermit calls:

```kotlin
private val log = Logger.withTag("ComponentName")
log.d { "Useful debug message" }
```

### 3.4 Commit

```
chore: update remaining TODOs and fix import paths

- Update chart TODOs to indicate blocked status
- Fix Spacing import paths in 4 component files
- Keep valid backlog TODOs for future work
```

---

## Phase 4: Testing & Verification

**Goal:** Ensure all changes work correctly without regressions.
**Estimated Effort:** 2-3 hours
**Risk Level:** Low

### 4.1 New Unit Tests

**File:** `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModelTest.kt`

```kotlin
package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeViewModelTest {

    @Test
    fun `default theme is SYSTEM`() = runTest {
        val settings = MapSettings()
        val prefs = SettingsPreferencesManager(settings)
        val vm = ThemeViewModel(prefs)

        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `setThemeMode updates state`() = runTest {
        val settings = MapSettings()
        val prefs = SettingsPreferencesManager(settings)
        val vm = ThemeViewModel(prefs)

        vm.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }

    @Test
    fun `theme persists across ViewModel instances`() = runTest {
        val settings = MapSettings()
        val prefs = SettingsPreferencesManager(settings)

        // First instance - set dark mode
        val vm1 = ThemeViewModel(prefs)
        vm1.setThemeMode(ThemeMode.DARK)

        // Second instance - should load dark mode
        val vm2 = ThemeViewModel(prefs)
        assertEquals(ThemeMode.DARK, vm2.themeMode.value)
    }

    @Test
    fun `invalid colorScheme defaults to SYSTEM`() = runTest {
        val settings = MapSettings()
        settings.putInt("color_scheme", 999)  // Invalid value
        val prefs = SettingsPreferencesManager(settings)
        val vm = ThemeViewModel(prefs)

        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }
}
```

### 4.2 Manual Test Matrix

| Test Case | Android | iOS |
|-----------|:-------:|:---:|
| Fresh install starts with SYSTEM theme | ☐ | ☐ |
| Switch to DARK, verify UI updates | ☐ | ☐ |
| Switch to LIGHT, verify UI updates | ☐ | ☐ |
| Kill app, reopen, theme persists | ☐ | ☐ |
| Switch to SYSTEM, verify follows device setting | ☐ | ☐ |
| Settings screen theme toggle works | ☐ | ☐ |
| Workout flow unaffected by changes | ☐ | ☐ |
| BLE connection unaffected | ☐ | ☐ |

### 4.3 Build Commands

```bash
# Full project build
./gradlew build

# Shared module tests
./gradlew :shared:allTests

# Android-specific tests
./gradlew :androidApp:testDebugUnitTest

# iOS framework compilation
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# Check for warnings
./gradlew build --warning-mode all
```

### 4.4 Commit

```
test: add ThemeViewModel unit tests

- Test default theme value
- Test theme state updates
- Test persistence across ViewModel instances
- Test invalid colorScheme fallback
```

---

## Phase 5: Documentation & Cleanup

**Goal:** Document changes and commit cleanly.
**Estimated Effort:** 30 minutes
**Risk Level:** Low

### 5.1 Update CLAUDE.md

Add section documenting KMP status:

```markdown
## KMP Migration Status

The codebase is fully Kotlin Multiplatform compatible:

| Component | Library | Status |
|-----------|---------|--------|
| **UI** | Compose Multiplatform 1.8.0 | ✅ Complete |
| **Navigation** | Navigation Compose 2.8.0 | ✅ Complete |
| **DI** | Koin 4.0.0 | ✅ Complete |
| **Database** | SQLDelight 2.2.1 | ✅ Complete |
| **Preferences** | multiplatform-settings 1.3.0 | ✅ Complete |
| **BLE** | Kable 0.40.0 | ✅ Complete |
| **Logging** | Kermit 2.0.5 | ✅ Complete |
| **ViewModel** | lifecycle-viewmodel-compose 2.8.4 | ✅ Complete |
| **UUID** | expect/actual (native APIs) | ✅ Complete |
| **Theme Persistence** | PreferencesManager | ✅ Complete |
```

### 5.2 Clean Up Uncommitted Files

Per git status, address `UPDATED_IMPLEMENTATION_PLAN.md`:
- If obsolete: delete it
- If relevant: commit or update it

### 5.3 Final Commit

```
docs: update CLAUDE.md with KMP migration status

- Document completed KMP library integration
- Add component status table
- Remove references to incomplete migration
```

---

## Summary

### Total Estimated Effort

| Phase | Effort | Risk |
|-------|--------|------|
| Phase 1: Foundation Cleanup | 1-2 hours | Low |
| Phase 2: Theme Persistence | 2-3 hours | Medium |
| Phase 3: TODO Cleanup | 1-2 hours | Low |
| Phase 4: Testing | 2-3 hours | Low |
| Phase 5: Documentation | 30 min | Low |
| **Total** | **7-11 hours** | **Low-Medium** |

### Files Modified (Complete List)

| File | Phase | Changes |
|------|-------|---------|
| `ThemeViewModel.kt` | 1, 2 | Remove TODOs, add persistence |
| `ExerciseConfigViewModel.kt` | 1 | Remove TODOs, fix UUID |
| `PreferencesManager.kt` | 1 | Remove stale TODO |
| `RepCounterFromMachine.kt` | 1 | Remove stale TODOs |
| `Models.kt` | 1 | Remove stale TODOs |
| `AppModule.kt` | 2 | Register ThemeViewModel |
| `App.kt` | 2 | Use koinViewModel |
| `ConnectionStatusBanner.kt` | 3 | Fix import |
| `EmptyStateComponent.kt` | 3 | Fix import |
| `SafetyEventsCard.kt` | 3 | Fix import |
| `ShimmerEffect.kt` | 3 | Fix import |
| `ThemeViewModelTest.kt` | 4 | New file |
| `CLAUDE.md` | 5 | Add KMP status |

### Success Criteria

- [ ] All stale TODOs removed (26 total)
- [ ] ExerciseConfigViewModel uses shared UUID function
- [ ] Theme persists on Android across app restarts
- [ ] Theme persists on iOS across app restarts
- [ ] All unit tests passing
- [ ] No new build warnings
- [ ] Clean git history with descriptive commits

---

## Appendix A: Research Findings

### A.1 Existing Expect/Actual Implementations

The project already uses expect/actual for:
- `Platform.kt` / `Platform.android.kt` / `Platform.ios.kt`
- `DriverFactory.kt` (SQLDelight drivers)
- `PlatformUtils.kt` (currentTimeMillis)
- `UUIDGeneration.android.kt` / `UUIDGeneration.ios.kt`
- `BleExtensions.android.kt` / `BleExtensions.ios.kt`
- `HapticFeedbackEffect.kt` (Compose)
- `VideoPlayer.kt` (Compose)
- `CsvExporter.kt` (data export)

### A.2 Dependency Versions

From `gradle/libs.versions.toml`:
- Kotlin: 2.1.20
- Compose Multiplatform: 1.8.0
- Koin: 4.0.0
- SQLDelight: 2.2.1
- multiplatform-settings: 1.3.0
- Kable: 0.40.0
- Kermit: 2.0.5

### A.3 iOS BLE Priority Note

The `requestHighPriority()` function is a no-op on iOS:
```kotlin
// BleExtensions.ios.kt
actual suspend fun Peripheral.requestHighPriority() {
    // No-op on iOS (handled by OS)
}
```

This is correct behavior. iOS CoreBluetooth automatically manages connection parameters and there is no public API equivalent to Android's `requestConnectionPriority()`. No action required.
