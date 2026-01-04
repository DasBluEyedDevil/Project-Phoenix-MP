# Phoenix Web Portal - Design Document

**Date:** 2026-01-03
**Status:** Approved
**Author:** Brainstorming session with Claude

---

## Executive Summary

A premium web portal for Phoenix (Vitruvian Trainer) users featuring comprehensive analytics, anatomical muscle visualization, and social features. Built on Ktor + Supabase + Next.js, with a future self-hosted AI coach.

---

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| MetricSamples Sync | Pre-computed summaries only | Computed on-device, ~5KB/session, sufficient for all visualizations |
| Sync Scope | All historical data | Fresh user base, small payload sizes |
| Shared Routines | Frozen snapshots | Immutable after publish, simple, no version confusion |
| AI Provider | Self-hosted (Llama/Mistral) | Full fine-tuning, zero per-request cost, complete privacy |
| Backend | Ktor from day one | Code sharing with KMP shared module, Kotlin everywhere |
| Hosting | Azure | Existing account, Container Apps + N-series VMs for AI |
| Social Scope | Routines, flames, challenges only | No following/feeds/comments for launch |
| Priority Order | Analytics → Social → AI Coach | Core value first, community second, AI as differentiator |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENTS                                      │
├──────────────────────┬──────────────────────┬───────────────────────┤
│   Phoenix Mobile     │    Web Portal        │   (Future: Watch)     │
│   (KMP - Android/iOS)│    (Next.js)         │                       │
└──────────┬───────────┴──────────┬───────────┴───────────────────────┘
           │                      │
           ▼                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    AZURE INFRASTRUCTURE                              │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐  │
│  │  Ktor Backend   │    │    Supabase     │    │  Self-Hosted AI │  │
│  │  (Container App)│    │  (Auth + RLS)   │    │  (N-series VM)  │  │
│  │                 │    │                 │    │                 │  │
│  │  • Sync API     │◄──►│  • PostgreSQL   │    │  • Llama/Mistral│  │
│  │  • Analytics    │    │  • Auth         │    │  • Fine-tuned   │  │
│  │  • AI Orchestr. │    │  • Row Security │    │  • Ollama       │  │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

- **Ktor Backend**: Sync endpoints, analytics computation, AI orchestration, code sharing with KMP
- **Supabase**: Authentication, PostgreSQL with RLS, real-time subscriptions for social features
- **Next.js Portal**: SSR dashboard, interactive visualizations, community browsing
- **Self-Hosted AI**: Fine-tuned model for routine generation and coaching (Phase 3)

---

## Data Layer

### Cloud Schema (Supabase PostgreSQL)

#### Core Tables (Synced from Mobile)

```sql
-- User profiles (extends Supabase auth.users)
CREATE TABLE profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    display_name TEXT,
    avatar_url TEXT,
    is_premium BOOLEAN DEFAULT FALSE,
    subscription_status TEXT DEFAULT 'free',
    subscription_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    last_synced_at TIMESTAMPTZ
);

-- Workout sessions with sync metadata
CREATE TABLE workout_sessions (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    -- Core fields from mobile SQLite
    timestamp BIGINT NOT NULL,
    mode TEXT NOT NULL,
    target_reps INTEGER NOT NULL,
    weight_per_cable_kg REAL NOT NULL,
    progression_kg REAL DEFAULT 0.0,
    duration BIGINT DEFAULT 0,
    total_reps INTEGER DEFAULT 0,
    warmup_reps INTEGER DEFAULT 0,
    working_reps INTEGER DEFAULT 0,
    is_just_lift BOOLEAN DEFAULT FALSE,
    stop_at_top BOOLEAN DEFAULT FALSE,
    eccentric_load INTEGER DEFAULT 100,
    echo_level INTEGER DEFAULT 1,
    exercise_id TEXT,
    exercise_name TEXT,
    routine_session_id TEXT,
    routine_name TEXT,
    -- Safety tracking
    safety_flags INTEGER DEFAULT 0,
    deload_warning_count INTEGER DEFAULT 0,
    rom_violation_count INTEGER DEFAULT 0,
    spotter_activations INTEGER DEFAULT 0,
    -- Set Summary Metrics
    peak_force_concentric_a REAL,
    peak_force_concentric_b REAL,
    peak_force_eccentric_a REAL,
    peak_force_eccentric_b REAL,
    avg_force_concentric_a REAL,
    avg_force_concentric_b REAL,
    avg_force_eccentric_a REAL,
    avg_force_eccentric_b REAL,
    heaviest_lift_kg REAL,
    total_volume_kg REAL,
    estimated_calories REAL,
    warmup_avg_weight_kg REAL,
    working_avg_weight_kg REAL,
    burnout_avg_weight_kg REAL,
    peak_weight_kg REAL,
    rpe INTEGER,
    -- Sync metadata
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    version INTEGER DEFAULT 1
);

-- Pre-computed session analytics
CREATE TABLE session_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES workout_sessions(id) ON DELETE CASCADE,
    rep_velocities JSONB,           -- [{rep: 1, peak: 0.5, avg: 0.3}, ...]
    rep_powers JSONB,
    rep_tut_ms JSONB,
    sticking_point_position_mm REAL,
    sticking_point_min_velocity REAL,
    left_right_imbalance_percent REAL,
    position_force_bins JSONB,      -- 20x20 grid for heatmap
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Personal records
CREATE TABLE personal_records (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    exercise_id TEXT NOT NULL,
    exercise_name TEXT NOT NULL,
    weight REAL NOT NULL,
    reps INTEGER NOT NULL,
    one_rep_max REAL NOT NULL,
    achieved_at BIGINT NOT NULL,
    workout_mode TEXT NOT NULL,
    pr_type TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
    volume REAL DEFAULT 0.0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, exercise_id, workout_mode, pr_type)
);

-- Routines
CREATE TABLE routines (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    created_at_local BIGINT NOT NULL,
    last_used BIGINT,
    use_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    version INTEGER DEFAULT 1
);

-- Routine exercises as JSON document
CREATE TABLE routine_documents (
    routine_id UUID PRIMARY KEY REFERENCES routines(id) ON DELETE CASCADE,
    exercises_json JSONB NOT NULL,
    supersets_json JSONB,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    version INTEGER DEFAULT 1
);

-- Weekly rollups for trend analysis
CREATE TABLE weekly_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    week_start DATE NOT NULL,
    total_volume_kg REAL DEFAULT 0,
    total_reps INTEGER DEFAULT 0,
    workout_count INTEGER DEFAULT 0,
    avg_intensity REAL,
    muscle_group_distribution JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, week_start)
);
```

#### Social Tables (Cloud-Only)

```sql
-- Shared routine snapshots (immutable)
CREATE TABLE shared_routine_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id UUID REFERENCES profiles(id) ON DELETE SET NULL,
    author_display_name TEXT,
    source_routine_id UUID,
    name TEXT NOT NULL,
    description TEXT,
    routine_doc JSONB NOT NULL,
    tags TEXT[],
    difficulty_level TEXT,
    estimated_duration_min INTEGER,
    flame_count INTEGER DEFAULT 0,
    download_count INTEGER DEFAULT 0,
    published_at TIMESTAMPTZ DEFAULT NOW(),
    is_featured BOOLEAN DEFAULT FALSE
);

-- Flames (upvotes)
CREATE TABLE flames (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    snapshot_id UUID REFERENCES shared_routine_snapshots(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, snapshot_id)
);

-- Routine downloads
CREATE TABLE routine_downloads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    snapshot_id UUID REFERENCES shared_routine_snapshots(id) ON DELETE CASCADE,
    downloaded_at TIMESTAMPTZ DEFAULT NOW(),
    imported_as_routine_id UUID,
    UNIQUE(user_id, snapshot_id)
);

-- Challenges
CREATE TABLE challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    description TEXT,
    goal_type TEXT NOT NULL,  -- TOTAL_VOLUME, WORKOUT_COUNT, STREAK, SPECIFIC_EXERCISE
    goal_target REAL NOT NULL,
    goal_exercise_id TEXT,
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES profiles(id),
    is_official BOOLEAN DEFAULT FALSE,
    participant_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Challenge participants
CREATE TABLE challenge_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    challenge_id UUID REFERENCES challenges(id) ON DELETE CASCADE,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    current_progress REAL DEFAULT 0,
    last_updated TIMESTAMPTZ DEFAULT NOW(),
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(challenge_id, user_id)
);
```

#### Row Level Security

```sql
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE personal_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE routines ENABLE ROW LEVEL SECURITY;

-- Users can only access their own data
CREATE POLICY "Users own their data" ON workout_sessions
    FOR ALL USING (auth.uid() = user_id);

CREATE POLICY "Users own their routines" ON routines
    FOR ALL USING (auth.uid() = user_id);

-- Anyone can read shared routines
CREATE POLICY "Public shared routines" ON shared_routine_snapshots
    FOR SELECT USING (true);

-- Only author can manage their shares
CREATE POLICY "Authors manage shares" ON shared_routine_snapshots
    FOR ALL USING (auth.uid() = author_id);
```

### Sync Strategy

```
Mobile App                    Ktor Backend                 PostgreSQL
    │                              │                            │
    │  1. Workout completes        │                            │
    │  2. Compute SessionSummary   │                            │
    │  3. Add to outbox table      │                            │
    │                              │                            │
    │  4. POST /api/sync/push      │                            │
    │  ─────────────────────────►  │                            │
    │     {deviceId, changes[]}    │                            │
    │                              │  5. Validate auth + RLS    │
    │                              │  6. Check versions         │
    │                              │  7. Apply changes          │
    │                              │  ─────────────────────────►│
    │                              │                            │
    │                              │  8. Update weekly rollups  │
    │                              │  ─────────────────────────►│
    │                              │                            │
    │  9. Ack + new cursor         │                            │
    │  ◄─────────────────────────  │                            │
    │                              │                            │
    │  10. Clear outbox            │                            │
    │                              │                            │
```

**Conflict Resolution:** Last-write-wins for most entities. Routines preserve client changes as "local draft" if versions diverge.

---

## Visualizations

### Data Mapping

| Mobile Data | Visualization | Insight |
|-------------|---------------|---------|
| `muscleGroup`, `muscleGroups` | Anatomical body model | Training balance at a glance |
| `totalVolumeKg`, `timestamp` | Volume trend line | Progressive overload tracking |
| `peakForceConcentricA/B` | Left/right symmetry bars | Imbalance detection |
| `concentricVelAvg/Max` | Velocity trend per exercise | Power development over time |
| `position`, `velocity`, `load` | Force-position heatmap | Sticking point identification |
| `workingReps`, `warmupReps`, `rpe` | Effort distribution chart | Volume quality breakdown |
| `heaviestLiftKg`, `oneRepMax` | PR timeline scatter | Strength milestones |
| `deloadWarningCount`, `romViolationCount` | Safety event log | Injury risk awareness |
| `eccentricLoad`, `echoLevel`, `mode` | Training mode distribution | Program variety |
| `streaks`, `badges` | Gamification dashboard | Motivation + consistency |

### Dashboard Views

#### View 1: Overview
- Anatomical body model (front/back, muscles colored by training volume)
- Weekly volume trend chart
- Current streak + recent badges
- Recent PRs list
- GitHub-style activity calendar

#### View 2: History
- Filterable workout list (date, exercise, muscle group)
- Expandable rows with per-set details
- Quick stats per session

#### View 3: Exercise Deep Dive
- Force-position heatmap (sticking point detection)
- Velocity trend over time
- Left/right symmetry comparison
- Volume history for this exercise
- PR progression with 1RM estimates

#### View 4: Safety & Quality
- Safety events log (deload warnings, spotter activations, ROM violations)
- ROM consistency tracking
- Training mode distribution pie chart

### Chart Libraries

| Visualization | Library | Rationale |
|---------------|---------|-----------|
| Anatomical model | Custom SVG + react-body-highlighter | Best muscle mapping |
| Heatmaps | ECharts | Canvas-based, handles grids well |
| Time series | uPlot | Extremely fast for large datasets |
| Radar/Balance | Recharts or Nivo | Good React integration |
| Calendar heatmap | cal-heatmap or custom | GitHub-style activity view |

---

## Social Features

### Shared Routines

**Publishing Flow:**
1. User creates routine in mobile app
2. Taps "Share to Community"
3. Enters description, tags, difficulty level
4. Immutable snapshot created in `shared_routine_snapshots`
5. Visible in community browse

**Browsing:**
- Sort by: Most Flamed, Newest, Most Downloaded
- Filter by: Muscle Group, Difficulty, Duration
- Preview before download

**Flames:** One per user per routine. Updates `flame_count` via trigger.

**Downloads:** Creates copy as new local routine. No ongoing link.

### Challenges

**Types:**
- `TOTAL_VOLUME` - Lift X kg in time period
- `WORKOUT_COUNT` - Complete X workouts
- `STREAK` - X consecutive training days
- `SPECIFIC_EXERCISE` - Volume/reps on one exercise

**Features:**
- Official challenges (created by you)
- Progress bars with percentage
- Leaderboard per challenge (top 100)
- End date with automatic completion check

---

## AI Coach (Phase 3 - Future)

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  AZURE N-SERIES VM                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │  Ollama Server                                                  ││
│  │  ├── Base: Llama 3.1 8B or Mistral 7B                          ││
│  │  ├── Fine-tuned LoRA: Phoenix-Coach-v1                         ││
│  │  └── RAG: ChromaDB with domain knowledge                       ││
│  └─────────────────────────────────────────────────────────────────┘│
│                              ▲                                      │
│                              │ HTTP                                 │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │  Ktor Endpoints                                                 ││
│  │  ├── /api/ai/routine-generate                                  ││
│  │  ├── /api/ai/weekly-insights                                   ││
│  │  └── /api/ai/analyze-session                                   ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

### Features

| Feature | Input | Output |
|---------|-------|--------|
| Routine Generator | Goals, time, history | Structured Routine JSON |
| Weekly Insights | Last 7 days data | Summary + recommendations |
| Sticking Point Coach | Weak ROM data | Accessory exercise suggestions |
| Deload Detector | Fatigue/velocity trends | Recovery recommendation |

### Fine-Tuning Dataset (To Build)
- Vitruvian machine specs and workout modes
- Domain model documentation
- Exercise science fundamentals
- Example coach Q&A pairs

---

## Project Structure

```
phoenix-portal/
├── apps/
│   ├── web/                      # Next.js 14 (App Router)
│   │   ├── src/
│   │   │   ├── app/
│   │   │   │   ├── (auth)/       # Login, signup
│   │   │   │   ├── dashboard/    # Overview, analytics
│   │   │   │   ├── history/      # Workout history
│   │   │   │   ├── exercise/     # Per-exercise deep dive
│   │   │   │   ├── community/    # Shared routines, challenges
│   │   │   │   └── settings/
│   │   │   ├── components/
│   │   │   │   ├── charts/       # Visualization components
│   │   │   │   ├── anatomy/      # Body model
│   │   │   │   └── ui/           # Shared primitives
│   │   │   └── lib/
│   │   │       ├── api/          # Ktor client
│   │   │       └── supabase/     # Auth client
│   │   └── package.json
│   │
│   └── backend/                  # Ktor
│       ├── src/main/kotlin/com/devil/phoenixproject/
│       │   ├── Application.kt
│       │   ├── routes/
│       │   │   ├── SyncRoutes.kt
│       │   │   ├── AnalyticsRoutes.kt
│       │   │   └── CommunityRoutes.kt
│       │   ├── services/
│       │   └── models/           # Import from shared module
│       └── build.gradle.kts
│
├── packages/
│   └── shared-types/             # Generated TS types
│
├── supabase/
│   ├── migrations/
│   └── seed.sql
│
└── Project-Phoenix-MP/           # KMP app (submodule)
```

---

## Implementation Phases

### Phase 1: Foundation (2-3 weeks)
- [ ] Ktor project setup with shared module import
- [ ] Supabase schema migrations
- [ ] Auth flow (Supabase Auth integration)
- [ ] Basic sync endpoint (push only)
- [ ] Next.js project with auth pages
- [ ] Azure Container App deployment

### Phase 2: Analytics MVP (3-4 weeks)
- [ ] Dashboard overview page
- [ ] Anatomical body model component
- [ ] Volume/trend charts
- [ ] Workout history list with filtering
- [ ] Exercise deep dive page
- [ ] Force-position heatmap

### Phase 3: Sync Integration (2 weeks)
- [ ] Mobile outbox table + sync service
- [ ] Pull endpoint for bidirectional sync
- [ ] Conflict detection and resolution
- [ ] Sync status UI in mobile app

### Phase 4: Social (2-3 weeks)
- [ ] Share routine flow (mobile + web)
- [ ] Community browse/search page
- [ ] Flame system with optimistic updates
- [ ] Download and import flow

### Phase 5: Challenges (1-2 weeks)
- [ ] Challenge CRUD (admin)
- [ ] Join challenge flow
- [ ] Progress tracking (auto-update on sync)
- [ ] Leaderboards

### Phase 6: Polish (2 weeks)
- [ ] Performance optimization
- [ ] Responsive design
- [ ] Error handling + offline states
- [ ] Analytics/monitoring

### Future: AI Coach
- [ ] Azure N-series VM setup
- [ ] Ollama + base model deployment
- [ ] Fine-tuning pipeline
- [ ] Coach endpoints in Ktor
- [ ] Chat UI in portal

---

## Tech Stack Summary

| Layer | Technology |
|-------|------------|
| Mobile | Kotlin Multiplatform (existing) |
| Web Frontend | Next.js 14, TypeScript, Tailwind CSS |
| Charts | ECharts, uPlot, Recharts |
| Backend | Ktor (Kotlin) |
| Database | Supabase (PostgreSQL) |
| Auth | Supabase Auth |
| Hosting | Azure Container Apps |
| AI (Future) | Ollama, Llama/Mistral, Azure N-series VM |

---

## Open Items

1. **RevenueCat webhook** - Need to set up webhook to sync subscription status to Supabase
2. **Anatomical model assets** - Need to source/create SVG body diagrams with muscle regions
3. **Fine-tuning dataset** - Need to write coach Q&A examples for AI training
4. **Mobile sync UI** - Need to design sync status indicator for mobile app

---

*Document generated from brainstorming session. Ready for implementation planning.*
