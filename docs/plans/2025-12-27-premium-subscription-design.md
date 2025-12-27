# Premium Subscription Model Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a premium subscription tier that unlocks cloud sync, AI-generated routines, community routine library, and third-party health integrations.

**Architecture:** RevenueCat handles cross-platform payments, Supabase provides backend (Auth, PostgreSQL, Edge Functions), premium features gated at repository layer via single `SubscriptionManager.hasProAccess` flow.

**Tech Stack:** RevenueCat SDK, Supabase (Auth + Database + Edge Functions), Claude API for AI routines, Health Connect (Android), HealthKit (iOS), Garmin/Hevy/Trainheroid APIs.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Subscription & Authentication Flow](#2-subscription--authentication-flow)
3. [Cloud Sync Strategy](#3-cloud-sync-strategy)
4. [AI Routine Generation](#4-ai-routine-generation)
5. [AI Program Import](#5-ai-program-import)
6. [Community Routine Library](#6-community-routine-library)
7. [Health Integrations Architecture](#7-health-integrations-architecture)
8. [Premium Feature Gating](#8-premium-feature-gating)
9. [Implementation Phases](#9-implementation-phases)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        Mobile App (KMP)                      │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose/SwiftUI)                                 │
│    ├── Subscription Gate (checks entitlements)              │
│    └── Premium Feature UIs                                  │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer                                               │
│    ├── SubscriptionManager (RevenueCat wrapper)             │
│    ├── CloudSyncManager                                     │
│    ├── RoutineLibraryRepository                             │
│    ├── AIRoutineGenerator                                   │
│    └── HealthIntegrationManager                             │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│    ├── Local: SQLDelight (existing)                         │
│    ├── Remote: Supabase Client                              │
│    └── Platform: Health APIs (expect/actual)                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                        Supabase                             │
├─────────────────────────────────────────────────────────────┤
│  Auth          │  Database       │  Edge Functions          │
│  - Email/Pass  │  - users        │  - generate-routine      │
│  - Google      │  - shared_routines │  - garmin-oauth       │
│  - Apple       │  - sync_data    │  - hevy-sync             │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
         RevenueCat      Claude API      Third-Party
         (payments)      (AI routines)   (Garmin, Hevy)
```

**Key Principle:** Premium features are gated at the repository/manager level, not scattered throughout UI code. A single `SubscriptionManager.hasProAccess` flow determines access.

---

## 2. Subscription & Authentication Flow

### User Journey

```
Free User                          Premium User
    │                                   │
    ▼                                   ▼
┌──────────────┐                  ┌──────────────┐
│ Local Profile │                  │ Supabase Auth│
│ (no account) │                  │ (email/social)│
└──────────────┘                  └──────────────┘
    │                                   │
    │  Taps "Go Pro"                    │
    ▼                                   ▼
┌──────────────┐                  ┌──────────────┐
│ Auth Screen  │───────────────▶ │ RevenueCat   │
│ Sign up/in   │                  │ Purchase     │
└──────────────┘                  └──────────────┘
                                        │
                                        ▼
                                  ┌──────────────┐
                                  │ Link RC to   │
                                  │ Supabase UID │
                                  └──────────────┘
                                        │
                                        ▼
                                  ┌──────────────┐
                                  │ Initial Sync │
                                  │ Local → Cloud│
                                  └──────────────┘
```

### Key Flows

1. **Free → Premium conversion:**
   - User taps premium feature or "Go Pro" button
   - Auth screen appears (create account or sign in)
   - After auth success, RevenueCat paywall shown
   - On purchase, link RevenueCat customer ID to Supabase user ID
   - Trigger initial sync of local data to cloud

2. **Existing subscriber on new device:**
   - Sign in with same credentials
   - RevenueCat restores purchases automatically
   - Cloud data syncs down to device

3. **Subscription expires:**
   - RevenueCat webhook notifies status change
   - App checks entitlements on launch and periodically
   - Features gracefully degrade (read-only access to synced data)
   - Data stays in cloud, just can't sync new data

### Per-Profile Subscription Model

Each local profile is a distinct identity. If "Dad" and "Kid" share a device, each needs their own premium account.

```
Device
  └── Local Profiles (existing)
        ├── Profile "Dad"
        │     └── linked to → Supabase Account A (has Pro)
        │                      └── RevenueCat Customer A
        │
        ├── Profile "Kid"
        │     └── linked to → Supabase Account B (no Pro)
        │
        └── Profile "Guest"
              └── not linked (free, local-only)
```

### Database Changes

```sql
-- Extend existing UserProfile table
ALTER TABLE UserProfile ADD COLUMN supabase_user_id TEXT;
ALTER TABLE UserProfile ADD COLUMN subscription_status TEXT DEFAULT 'free';
ALTER TABLE UserProfile ADD COLUMN last_auth_at INTEGER;

-- New table for subscription tracking
CREATE TABLE user_subscription (
    id TEXT PRIMARY KEY,
    supabase_user_id TEXT,
    revenuecat_customer_id TEXT,
    subscription_status TEXT, -- 'active', 'expired', 'grace_period'
    expires_at INTEGER,
    last_verified_at INTEGER
);
```

### Behavior

- Switching profiles = switching subscription context
- Profile without linked account = always free tier
- Each profile's data syncs only to its own Supabase account
- "Go Pro" flow is per-profile, not per-device
- RevenueCat checks entitlements using profile's linked customer ID

---

## 3. Cloud Sync Strategy

### Sync Scope (Premium Only)

- Workout sessions + metric samples
- Routines + routine exercises
- Training cycles + cycle days + progress
- Personal records
- Exercise customizations (favorites, custom exercises)
- User preferences

### Strategy: Last-Write-Wins with Conflict Detection

```
┌─────────────┐         ┌─────────────┐
│  Device A   │         │  Device B   │
│  (phone)    │         │  (tablet)   │
└──────┬──────┘         └──────┬──────┘
       │                       │
       │  Push changes         │  Push changes
       ▼                       ▼
┌─────────────────────────────────────┐
│            Supabase                 │
│  ┌─────────────────────────────┐   │
│  │  sync_operations table      │   │
│  │  - id, user_id, entity_type │   │
│  │  - entity_id, operation     │   │
│  │  - payload, device_id       │   │
│  │  - client_timestamp         │   │
│  │  - server_timestamp         │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────┐
│  Conflict Resolution Rules:         │
│  1. Workouts: Never conflict        │
│     (unique per device+time)        │
│  2. Routines: Last-write-wins       │
│     (show "edited on other device") │
│  3. Cycles: Last-write-wins         │
│  4. PRs: Keep highest value         │
└─────────────────────────────────────┘
```

### Sync Triggers

- App launch (pull changes)
- After workout completion (push)
- After routine/cycle edit (push)
- Background sync every 15 minutes when app is open
- Manual "Sync Now" button in settings

### Offline Handling

- All operations work offline against local SQLite
- Changes queued in `pending_sync_operations` local table
- Pushed when connectivity returns
- Sync status indicator in UI

### Supabase Tables

```sql
-- Mirrored versions of local tables with user_id
synced_workout_sessions (user_id, ...existing fields..., device_id, synced_at)
synced_routines (user_id, ...existing fields..., device_id, synced_at)
synced_training_cycles (user_id, ...existing fields..., device_id, synced_at)
synced_personal_records (user_id, ...existing fields..., device_id, synced_at)
```

---

## 4. AI Routine Generation

### User Flow

```
┌─────────────────────────────────────────────────────────────┐
│  "Create AI Routine" Screen                                 │
├─────────────────────────────────────────────────────────────┤
│  1. Select target muscle groups (multi-select chips)        │
│     [Chest] [Back] [Shoulders] [Biceps] [Triceps]          │
│     [Quads] [Hamstrings] [Glutes] [Core] [Calves]          │
│                                                             │
│  2. Workout type                                            │
│     ○ Strength (heavy, low reps)                           │
│     ○ Hypertrophy (moderate, 8-12 reps)                    │
│     ○ Endurance (light, high reps)                         │
│                                                             │
│  3. Duration preference                                     │
│     ○ Quick (4-5 exercises, ~30 min)                       │
│     ○ Standard (6-8 exercises, ~45 min)                    │
│     ○ Extended (8-12 exercises, ~60 min)                   │
│                                                             │
│  4. Equipment (auto-detected from app)                      │
│     ☑ Vitruvian Trainer (always)                           │
│                                                             │
│  [Generate Routine]                                         │
└─────────────────────────────────────────────────────────────┘
```

### Backend Flow

```
App                         Supabase Edge Function           Claude API
 │                                   │                            │
 │  POST /generate-routine           │                            │
 │  { muscles, type, duration,       │                            │
 │    user_exercise_history }        │                            │
 │ ─────────────────────────────────▶│                            │
 │                                   │                            │
 │                                   │  Build prompt with:        │
 │                                   │  - Exercise library        │
 │                                   │  - User's PRs/history      │
 │                                   │  - Vitruvian capabilities  │
 │                                   │                            │
 │                                   │  POST /messages            │
 │                                   │──────────────────────────▶│
 │                                   │                            │
 │                                   │  Structured JSON response  │
 │                                   │◀──────────────────────────│
 │                                   │                            │
 │  { routine: Routine,              │                            │
 │    explanation: string }          │                            │
 │◀─────────────────────────────────│                            │
 │                                   │                            │
 │  Show preview + explanation       │                            │
 │  [Save] [Regenerate] [Edit]       │                            │
```

### AI Response Schema

```kotlin
@Serializable
data class AIRoutineResponse(
    val routine: GeneratedRoutine,
    val explanation: String,  // "This push-focused routine targets..."
    val estimatedDuration: Int // minutes
)

@Serializable
data class GeneratedRoutine(
    val name: String,
    val exercises: List<GeneratedExercise>
)

@Serializable
data class GeneratedExercise(
    val exerciseId: String,  // References existing Exercise library
    val sets: Int,
    val reps: List<Int>,
    val suggestedWeightPercent: Float, // % of user's 1RM if known
    val restSeconds: Int,
    val notes: String?
)
```

### Edge Function Prompt Strategy

- Include full exercise library (names, muscle groups, equipment)
- Include user's known 1RMs for weight suggestions
- Constrain to Vitruvian-compatible exercises only
- Request structured JSON output
- ~500-800 tokens per generation (~$0.01-0.02 cost)

---

## 5. AI Program Import

Import workout programs from other apps via screenshot, text, or structured formats. AI converts them to Phoenix routines/cycles.

### Import Methods

```
┌─────────────────────────────────────────────────────────────┐
│  Import Program                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │ 📷          │  │ 📋          │  │ 📄          │        │
│  │ Screenshot  │  │ Paste Text  │  │ File        │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
│                                                             │
│  Supported sources:                                         │
│  • TrainHeroic screenshots/exports                         │
│  • SugarWOD program text                                   │
│  • Garmin workout exports                                  │
│  • Hevy workout history                                    │
│  • Generic workout text (coach emails, PDFs, etc.)         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Backend Flow

```
App                         Supabase Edge Function           Claude API
 │                                   │                            │
 │  POST /import-program             │                            │
 │  { type: "screenshot",            │                            │
 │    image_base64: "...",           │                            │
 │    source_hint: "trainheroic" }   │                            │
 │ ─────────────────────────────────▶│                            │
 │                                   │                            │
 │                                   │  Build prompt with:        │
 │                                   │  - Image/text content      │
 │                                   │  - Exercise library        │
 │                                   │  - Output schema           │
 │                                   │                            │
 │                                   │  POST /messages (vision)   │
 │                                   │──────────────────────────▶│
 │                                   │                            │
 │                                   │  Parsed routine JSON       │
 │                                   │◀──────────────────────────│
 │                                   │                            │
 │  { routine: Routine,              │                            │
 │    confidence: 0.92,              │                            │
 │    warnings: [...] }              │                            │
 │◀─────────────────────────────────│                            │
 │                                   │                            │
 │  Show preview with warnings       │                            │
 │  [Save] [Edit] [Cancel]           │                            │
```

### Import Response Schema

```kotlin
@Serializable
data class AIImportResponse(
    val routine: GeneratedRoutine?,        // Single routine
    val cycle: GeneratedCycle?,            // Or full cycle
    val confidence: Float,                 // 0.0-1.0 parse confidence
    val warnings: List<ImportWarning>,     // Issues found
    val unmatchedExercises: List<String>   // Exercises not in our library
)

@Serializable
data class ImportWarning(
    val type: String,  // "unknown_exercise", "ambiguous_sets", "missing_weight"
    val message: String,
    val suggestion: String?
)

@Serializable
data class GeneratedCycle(
    val name: String,
    val days: List<GeneratedCycleDay>
)

@Serializable
data class GeneratedCycleDay(
    val dayNumber: Int,
    val name: String?,
    val routine: GeneratedRoutine?,
    val isRestDay: Boolean
)
```

### Exercise Matching Strategy

1. **Exact match:** Exercise name matches our library
2. **Fuzzy match:** "Bench Press" → "Flat Bench Press"
3. **Suggest alternatives:** "Smith Machine Squat" → suggest "Squat" with warning
4. **Flag unmatched:** "TRX Rows" → flag as not available on Vitruvian

### UI Preview Flow

```
┌─────────────────────────────────────────────────────────────┐
│  Import Preview                                    92% match│
├─────────────────────────────────────────────────────────────┤
│  ⚠️ 2 exercises need review                                │
├─────────────────────────────────────────────────────────────┤
│  ✓ Bench Press           → Flat Bench Press                │
│  ✓ Squat                 → Squat                           │
│  ✓ Lat Pulldown          → Lat Pulldown                    │
│  ⚠️ Cable Fly            → [Select replacement ▼]          │
│  ❌ TRX Row (not available) → [Remove] [Replace]           │
├─────────────────────────────────────────────────────────────┤
│  Routine: "Push Day" • 5 exercises                         │
│  Sets/Reps preserved from source                           │
├─────────────────────────────────────────────────────────────┤
│  [Cancel]                              [Edit] [Save As-Is]  │
└─────────────────────────────────────────────────────────────┘
```

### Cost Considerations

- Screenshot import uses vision model (~$0.03-0.05 per image)
- Text import cheaper (~$0.01-0.02)
- Consider daily/monthly limits per user

---

## 6. Community Routine Library

### User Experience

```
┌─────────────────────────────────────────────────────────────┐
│  Community Library                                    🔍    │
├─────────────────────────────────────────────────────────────┤
│  [Popular] [New] [Following] [My Shared]                   │
├─────────────────────────────────────────────────────────────┤
│  Filter: [All Muscles ▼] [All Types ▼] [Duration ▼]        │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 💪 Push Day Destroyer                        ⭐ 4.8  │   │
│  │ Chest, Shoulders, Triceps • 8 exercises • ~45min   │   │
│  │ by @FitnessFan42 • 234 imports                      │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🦵 Leg Day Essentials                        ⭐ 4.5  │   │
│  │ Quads, Hamstrings, Glutes • 6 exercises • ~40min   │   │
│  │ by @VitruvianVet • 189 imports                      │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Database Schema

```sql
-- Shared routines table
CREATE TABLE shared_routines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_user_id UUID REFERENCES auth.users(id),
    author_display_name TEXT NOT NULL,

    -- Content
    title TEXT NOT NULL,
    description TEXT,
    routine_data JSONB NOT NULL,

    -- Metadata for filtering/search
    muscle_groups TEXT[] NOT NULL,
    exercise_count INT NOT NULL,
    estimated_duration_min INT,
    workout_type TEXT,

    -- Engagement
    import_count INT DEFAULT 0,
    rating_sum INT DEFAULT 0,
    rating_count INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT now()
);

-- Same pattern for shared_cycles
CREATE TABLE shared_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_user_id UUID REFERENCES auth.users(id),
    author_display_name TEXT NOT NULL,

    title TEXT NOT NULL,
    description TEXT,
    cycle_data JSONB NOT NULL,

    day_count INT NOT NULL,
    routine_count INT NOT NULL,

    import_count INT DEFAULT 0,
    rating_sum INT DEFAULT 0,
    rating_count INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT now()
);

-- User ratings (one per user per routine)
CREATE TABLE routine_ratings (
    user_id UUID REFERENCES auth.users(id),
    routine_id UUID REFERENCES shared_routines(id),
    rating INT CHECK (rating >= 1 AND rating <= 5),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (user_id, routine_id)
);

-- Import tracking
CREATE TABLE routine_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES auth.users(id),
    routine_id UUID REFERENCES shared_routines(id),
    imported_at TIMESTAMPTZ DEFAULT now()
);
```

No moderation needed - only structured workout data can be shared, no messaging between users.

---

## 7. Health Integrations Architecture

### Abstraction Layer

```kotlin
// Common interface for all health platforms
interface HealthIntegration {
    val platformName: String
    val isAvailable: Boolean  // Platform exists on device

    suspend fun isConnected(): Boolean
    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    suspend fun syncWorkout(session: WorkoutSession): Result<Unit>
    suspend fun getRecentWorkouts(limit: Int): Result<List<ExternalWorkout>>
}

// Platform implementations via expect/actual
expect class HealthConnectIntegration : HealthIntegration    // Android
expect class HealthKitIntegration : HealthIntegration        // iOS
expect class GarminIntegration : HealthIntegration           // Both (via API)
expect class HevyIntegration : HealthIntegration             // Both (via API)
expect class TrainheroidIntegration : HealthIntegration      // Both (via API)
```

### Integration Manager

```kotlin
class HealthIntegrationManager(
    private val integrations: List<HealthIntegration>,
    private val subscriptionManager: SubscriptionManager
) {
    val availableIntegrations: Flow<List<HealthIntegration>>
    val connectedIntegrations: Flow<List<HealthIntegration>>

    suspend fun syncWorkoutToAll(session: WorkoutSession): Map<String, Result<Unit>>
    suspend fun connectIntegration(name: String): Result<Unit>
    suspend fun disconnectIntegration(name: String): Result<Unit>
}
```

### Platform Implementation Matrix

| Integration | Android | iOS | Auth Method |
|-------------|---------|-----|-------------|
| Health Connect | Native SDK | N/A | System permissions |
| HealthKit | N/A | Native SDK | System permissions |
| Garmin | Supabase Edge Function | Supabase Edge Function | OAuth 2.0 |
| Hevy | Supabase Edge Function | Supabase Edge Function | OAuth/API Key |
| Trainheroid | Supabase Edge Function | Supabase Edge Function | TBD (research needed) |

### OAuth Flow (Garmin/Hevy)

```
App                     Supabase Edge Function          Third-Party
 │                              │                            │
 │ GET /auth/garmin/start       │                            │
 │─────────────────────────────▶│                            │
 │                              │                            │
 │ { auth_url }                 │                            │
 │◀─────────────────────────────│                            │
 │                              │                            │
 │ Open in-app browser          │                            │
 │─────────────────────────────────────────────────────────▶│
 │                              │                            │
 │ Callback with code           │                            │
 │◀─────────────────────────────────────────────────────────│
 │                              │                            │
 │ POST /auth/garmin/callback   │                            │
 │─────────────────────────────▶│                            │
 │                              │ Exchange code for token    │
 │                              │───────────────────────────▶│
 │                              │                            │
 │                              │ Store token in Supabase    │
 │                              │◀───────────────────────────│
 │                              │                            │
 │ { success: true }            │                            │
 │◀─────────────────────────────│                            │
```

### Token Storage (Supabase)

```sql
CREATE TABLE integration_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES auth.users(id),
    integration_name TEXT NOT NULL,
    access_token TEXT NOT NULL,  -- Encrypted at rest
    refresh_token TEXT,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, integration_name)
);
```

---

## 8. Premium Feature Gating

### Central Subscription State

```kotlin
class SubscriptionManager(
    private val revenueCat: Purchases,
    private val activeProfile: StateFlow<UserProfile?>
) {
    // Reactive entitlement status for current profile
    val hasProAccess: StateFlow<Boolean>

    // Check specific features (all tied to single Pro tier for now)
    val canUseAIRoutines: Flow<Boolean> = hasProAccess
    val canAccessCommunityLibrary: Flow<Boolean> = hasProAccess
    val canSyncToCloud: Flow<Boolean> = hasProAccess
    val canUseHealthIntegrations: Flow<Boolean> = hasProAccess

    suspend fun showPaywall(): PaywallResult
    suspend fun restorePurchases(): Result<Unit>
}
```

### UI Pattern

```kotlin
@Composable
fun PremiumFeatureGate(
    feature: String,
    subscriptionManager: SubscriptionManager,
    freeContent: @Composable () -> Unit = { LockedFeaturePrompt(feature) },
    premiumContent: @Composable () -> Unit
) {
    val hasAccess by subscriptionManager.hasProAccess.collectAsState()

    if (hasAccess) {
        premiumContent()
    } else {
        freeContent()
    }
}

// Usage
PremiumFeatureGate("AI Routines", subscriptionManager) {
    AIRoutineGeneratorScreen()
}
```

### Locked State UI

- Grayed out / locked icon overlay on premium features
- Tapping shows brief value prop + "Unlock with Pro" button
- Button opens paywall
- Consistent visual language across all locked features

### Grace Period Handling

- RevenueCat provides billing grace period info
- During grace period: full access + "Payment issue" banner
- After grace expires: features locked, data preserved

---

## 9. Implementation Phases

### Phase 1: Foundation (Must complete first)

- Supabase project setup (Auth, Database, Edge Functions)
- RevenueCat integration (SDK, entitlements, paywall UI)
- Subscription gating infrastructure in app
- Link local profiles to Supabase accounts
- Basic "Go Pro" flow working end-to-end

### Phase 2: Cloud Sync

- Sync tables in Supabase
- `CloudSyncManager` implementation
- Offline queue for pending operations
- Conflict resolution logic
- Sync status UI indicators

### Phase 3: AI Features (Generation + Import)

- Edge Function for Claude API calls
- AI routine generation UI
- AI program import (screenshot, text, file)
- Vision model integration for screenshot parsing
- Exercise matching/fuzzy search logic
- Prompt engineering with exercise library
- Preview/edit/save flow for both features
- Rate limiting (X generations/imports per day/month)

### Phase 4: Community Library

- Shared routines/cycles tables
- Browse/search/filter UI
- Publish flow from existing routines
- Import flow with local save
- Ratings system

### Phase 5: Health Integrations

- `HealthIntegration` abstraction layer
- Health Connect (Android)
- HealthKit (iOS)
- Garmin OAuth + sync
- Hevy integration
- Trainheroid integration (pending API research)

### Phase Dependencies

| Phase | Complexity | Can Ship Independently |
|-------|------------|------------------------|
| 1 | High | No (required for all) |
| 2 | High | Yes (after Phase 1) |
| 3 | Medium | Yes (after Phase 1) |
| 4 | Medium | Yes (after Phase 1) |
| 5 | High | Yes (after Phase 1) |

---

## Summary

| Component | Decision |
|-----------|----------|
| Payments | RevenueCat, single "Pro" tier |
| Backend | Supabase (Auth, DB, Edge Functions) |
| Auth | Local profiles free, Supabase account for Pro (per-profile) |
| Cloud Sync | Full sync for premium, last-write-wins, offline queue |
| AI Routines | Claude API via Edge Function, structured JSON output |
| AI Import | Screenshot/text/file parsing via Claude Vision, exercise matching |
| Community | Browse/search/import/publish routines & cycles, ratings |
| Health | Abstraction layer + Health Connect, HealthKit, Garmin, Hevy, Trainheroid |
| Phases | 5 phases, Foundation first, others can parallelize |
