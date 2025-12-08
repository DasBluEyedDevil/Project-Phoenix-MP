# Project Phoenix MP - Updated Implementation Plan (Android/iOS Focus)

## Revised Strategy Based on Parent Repository Analysis

After examining the parent repository `VitruvianProjectPhoenix-master`, I've identified the key differences and created an updated implementation plan that:

1. **Focuses exclusively on Android and iOS** (desktop deprecated)
2. **Aligns with parent repository architecture** (Hilt, Timber, etc.)
3. **Prioritizes replication of proven functionality**

## Key Differences from Parent Repository

### Architecture Differences:
- **Parent**: Uses Hilt for DI, Timber for logging, Android-specific patterns
- **Current MP**: Uses Koin for DI, mixed logging, multiplatform patterns

### Critical Gaps to Address:

1. **Theme Management**: Parent uses DataStore, current uses MutableStateFlow placeholder
2. **Exercise Library**: Parent has complete implementation, current has stubs
3. **BLE Implementation**: Parent has proven Nordic implementation, current uses Kable
4. **Logging**: Parent uses Timber consistently, current has mixed logging

## Updated Implementation Priority

### Phase 1: Core Functionality Alignment (Week 1-2)

#### 1. ThemeViewModel Storage Implementation (CRITICAL)
**Goal**: Replicate parent's DataStore-based theme management
**Files**: `ThemeViewModel.kt`
**Implementation**:
- Create Android-specific DataStore implementation (expect/actual)
- Implement theme persistence matching parent's approach
- Add theme migration from preferences
- Test with actual device storage

#### 2. Exercise Library Completion (CRITICAL)
**Goal**: Replicate parent's exercise library functionality
**Files**: `ExerciseLibraryViewModel.kt`, `ExerciseRepository.kt`
**Implementation**:
- Implement exercise import from parent's data format
- Complete getVideos() with actual video data
- Add exercise search and filtering
- Implement exercise detail views

#### 3. BLE Protocol Alignment (CRITICAL)
**Goal**: Ensure BLE protocol matches parent repository
**Files**: `KableBleRepository.kt`
**Implementation**:
- Verify all BLE UUIDs match parent's constants
- Ensure command formats are identical
- Test with same Vitruvian hardware models
- Match polling rates and timeouts

### Phase 2: Architecture Alignment (Week 3-4)

#### 4. Dependency Injection Migration
**Goal**: Align DI approach with parent (Hilt → Koin migration)
**Files**: Multiple ViewModels and repositories
**Implementation**:
- Review parent's Hilt modules and scopes
- Ensure Koin modules provide equivalent functionality
- Verify all dependencies are properly injected
- Test DI consistency across platforms

#### 5. Logging System Standardization
**Goal**: Implement consistent logging like parent's Timber usage
**Files**: Multiple components
**Implementation**:
- Choose multiplatform logging library (Napier)
- Replace mixed logging with unified approach
- Add log level configuration
- Implement log file rotation for debugging

#### 6. Workout State Management
**Goal**: Replicate parent's workout state handling
**Files**: `MainViewModel.kt`
**Implementation**:
- Implement workout state machine matching parent
- Add screen keep-on during active workouts
- Replicate workout initialization flow
- Test state transitions with real hardware

### Phase 3: Feature Parity (Week 5-6)

#### 7. Exercise Import System
**Goal**: Replicate parent's exercise import functionality
**Files**: `ExerciseRepository.kt`, `ExerciseImporter.kt`
**Implementation**:
- Implement exercise JSON import from assets
- Add exercise database seeding
- Implement exercise update mechanism
- Test with parent's exercise data format

#### 8. Analytics and Charts
**Goal**: Complete analytics visualization
**Files**: Chart components
**Implementation**:
- Integrate charting library compatible with parent
- Implement workout metrics visualization
- Add historical data analysis
- Test with real workout data

#### 9. Preferences System
**Goal**: Complete user preferences management
**Files**: `PreferencesManager.kt`
**Implementation**:
- Implement multiplatform settings storage
- Add preference migration from parent format
- Implement preference change listeners
- Test preference persistence

### Phase 4: Quality and Testing (Week 7+)

#### 10. Testing Infrastructure
**Goal**: Add comprehensive testing
**Files**: New test files
**Implementation**:
- Add unit tests for ViewModels
- Implement BLE integration tests
- Add UI tests for Compose components
- Test with real Vitruvian hardware

#### 11. Performance Optimization
**Goal**: Optimize BLE and UI performance
**Files**: `KableBleRepository.kt`, performance-critical components
**Implementation**:
- Review and optimize BLE polling strategy
- Implement backpressure handling
- Add performance monitoring
- Test with various hardware models

## Key Implementation Decisions

### 1. BLE Library Choice
**Decision**: Continue with Kable but ensure protocol compatibility
**Rationale**: Kable provides multiplatform BLE, but must match parent's Nordic implementation exactly

### 2. DI Framework
**Decision**: Keep Koin but replicate Hilt's functionality
**Rationale**: Koin is multiplatform-compatible, but must provide same DI capabilities as parent

### 3. Logging Approach
**Decision**: Implement Napier for multiplatform logging
**Rationale**: Provides Timber-like API across Android/iOS while being multiplatform-compatible

### 4. Theme Storage
**Decision**: Implement expect/actual DataStore pattern
**Rationale**: Matches parent's approach while supporting multiplatform

## Parent Repository Features to Replicate

### From MainActivity.kt:
- Splash screen implementation with proper timing
- Screen keep-on during workouts
- Workout state management
- Proper Material 3 theming

### From VitruvianApp.kt:
- Application-level coroutine scope
- Exercise import on first launch
- Timber logging initialization
- Hilt application setup

## Implementation Timeline

| Phase | Duration | Focus | Key Deliverables |
|-------|----------|-------|------------------|
| 1 | 2 weeks | Core functionality alignment | Theme storage, exercise library, BLE protocol |
| 2 | 2 weeks | Architecture alignment | DI migration, logging standardization, state management |
| 3 | 2 weeks | Feature parity | Exercise import, analytics, preferences |
| 4 | 1+ weeks | Quality and testing | Testing infrastructure, performance optimization |

## Resource Allocation

1. **2 developers**: Core functionality alignment (ThemeViewModel + Exercise Library)
2. **1 developer**: BLE protocol verification and optimization
3. **1 developer**: Architecture alignment (DI, logging, state management)
4. **QA resources**: Testing phase with real hardware

## Success Metrics

1. **Protocol Compatibility**: 100% BLE protocol match with parent repository
2. **Feature Parity**: All parent features replicated in multiplatform version
3. **Performance**: BLE polling ≤20ms average, no memory leaks
4. **Test Coverage**: ≥80% unit test coverage, hardware testing completed
5. **User Acceptance**: Successful testing with same Vitruvian hardware models

## Risk Mitigation

**High Risk Items**:
- BLE protocol compatibility (critical for hardware communication)
- Theme storage implementation (blocks multiple features)
- Exercise library completion (core user functionality)

**Mitigation Strategies**:
- Prioritize BLE protocol verification first
- Implement critical path items with feature flags
- Test with real hardware early and often
- Maintain compatibility with parent's data formats

This updated plan focuses on achieving feature parity with the parent repository while maintaining the multiplatform benefits for Android and iOS.