# Analytics Screen Enhancement Design

**Date:** 2025-12-29
**Status:** Approved
**Branch:** beta0.1.2

## Overview

Redesign the Analytics screen to provide more useful metrics, data visualizations, and PR tracking that ties into the 72-badge gamification system. AI-powered features are reserved for premium tier.

## Current State

- **Tabs:** Overview | Log | Exercises
- **PR Tracking:** Weight PR and Volume PR per exercise/mode
- **Analytics Models:** `TrendData`, `Prediction`, `Plateau` exist but are underutilized
- **Badge System:** 72 badges across 5 categories (Consistency, Strength, Volume, Explorer, Dedication)

## Proposed Tab Structure

### Tab 1: Dashboard (Quick Glance)

The "open the app and feel motivated" view.

| Component | Description | Priority |
|-----------|-------------|----------|
| **Streak Card** | Current streak with fire icon (keep existing) | Existing |
| **This Week Summary Card** | Workouts, volume, PRs vs last week | High |
| **Calendar Heatmap** | Last 3 months, GitHub-style contribution graph | High |
| **Muscle Group Heatmap** | Anatomical body outline, 7-day coverage | Medium |
| **Next Badge Progress** | Closest badge to unlocking with progress bar | High |

### Tab 2: Progress (Deep Dive Analytics)

Where users analyze trends and see growth.

| Component | Description | Priority |
|-----------|-------------|----------|
| **Lifetime Stats Card** | Total workouts, volume ("X Blue Whales"), reps, days training, favorites | High |
| **PR List (Enhanced)** | Filter by exercise/muscle group, show "X kg away from PR" | High |
| **Volume Trend Chart** | Weekly bars with trend line, 12-week view | Medium |
| **Workout Mode Distribution** | Donut chart of mode usage | Low |
| **Training Time Patterns** | Day x hour heatmap | Low |
| **Exercise Variety Chart** | Unique exercises over time | Low |

### Tab 3: History (Log)

Keep current implementation - chronological workout log with delete/export.

### Removed: Exercises Tab

Exercise-specific detail moves to tapping an exercise anywhere (PR list, history) which opens an Exercise Detail screen with that exercise's PRs, history, and charts.

## Detailed Feature Specifications

### Calendar Heatmap

- GitHub contribution graph style
- Shows last 3 months (scrollable to view more)
- Color intensity = volume lifted that day
- Empty cells = rest days
- Tapping a day shows workout summary
- **Badge tie-in:** CONSISTENCY category (streaks visible at a glance)

### Muscle Group Heatmap

- Anatomical body outline (front/back toggle or combined)
- Color intensity per muscle group based on sets/volume in last 7 days
- Darker = more trained, lighter/gray = neglected
- Shows imbalance warnings: "Legs not trained in 14 days"
- **Badge tie-in:** Well Rounded (6 groups), Full Body Master (12 groups)

### This Week Summary Card

Comparison metrics:
- Workouts completed (e.g., "4 workouts (+1 vs last week)")
- Total volume in kg (e.g., "12,450 kg (+8%)")
- Total reps
- PRs hit this week
- Visual indicators: up/down arrows with green/red coloring

### Lifetime Stats Card

- **Total workouts** - feeds into Dedication badges
- **Total volume** - displayed in relatable terms using badge names:
  - < 10,000 kg: "X kg lifted"
  - 10,000+: "X Cars Crushed"
  - 50,000+: "X Elephants Moved"
  - 100,000+: "X Jumbo Jets"
  - 200,000+: "X Blue Whales"
  - 1,000,000+: "X Titanics"
- **Total reps** - feeds into Volume badges
- **Days since first workout**
- **Favorite exercise** (most performed)
- **Favorite workout mode** (most used)

### Next Badge Progress

- Show 1-3 badges closest to being earned
- Progress bar with current/target values
- Tapping navigates to Badges screen
- Exclude secret badges until earned

### Enhanced PR List

- Current PR cards kept
- Add filters: by muscle group, by exercise, by workout mode
- Show context: "2.5 kg away from PR" when close
- Sort options: by date, by exercise, by weight

### Volume Trend Chart

- Bar chart showing weekly volume (kg)
- 12-week default view
- Trend line overlay showing direction
- Utilizes existing `TrendData` and `TrendDirection` models
- Tap bar to see week details

### Workout Mode Distribution

- Donut/pie chart
- Shows percentage of workouts per mode (Old School, Pump, TUT, TUT Beast, Eccentric, Echo)
- **Badge tie-in:** Mode Explorer badge (use all 6 modes)
- Encourages variety

### Training Time Patterns

- Heatmap grid: X-axis = hour of day (0-23), Y-axis = day of week
- Color intensity = workout frequency/volume at that time slot
- **Badge tie-in:** Early Bird, Night Owl, Dawn Patrol, Lunch Lifter, Weekend Warrior

## PR Tracking Enhancements

### Rep Max Tracking (Future Enhancement)

Track PRs at multiple rep ranges per exercise:
- 1RM (actual or estimated)
- 3RM
- 5RM
- 8RM
- 10RM

Uses existing Epley/Brzycki formulas from `Constants.kt`.

### Estimated 1RM Display

- Calculate estimated 1RM from any logged set
- Show 1RM progression chart per exercise
- Display on Exercise Detail screen

## Premium Features (AI-Powered)

Reserved for premium subscription tier:

| Feature | Description |
|---------|-------------|
| **PR Prediction** | "Based on your progress, attempt bench PR in ~2 weeks" |
| **Plateau Detection** | Alert when progress stalls on an exercise |
| **Personalized Insights** | "You're 15% stronger on Tuesday evenings" |
| **Badge Prediction** | "12 more workouts to earn Iron Will badge" |
| **Smart Recommendations** | "Add more leg exercises for Full Body Master badge" |

## Implementation Priority

### Tier 1 - High Impact, Low/Medium Effort

1. This Week vs Last Week Card
2. Lifetime Stats Card
3. Next Badge Progress
4. Calendar Heatmap
5. Workout Mode Donut Chart

### Tier 2 - Medium Effort, High Value

1. Muscle Group Heatmap
2. Volume Trend Chart (12 weeks)
3. Enhanced PR List with filters
4. Training Time Heatmap

### Tier 3 - Premium (Post-Launch)

1. PR Prediction (AI)
2. Plateau Detection (AI)
3. Personalized Insights (AI)
4. Badge Prediction (AI)

## Data Sources

All visualizations use data already collected:

| Visualization | Data Source |
|---------------|-------------|
| Calendar Heatmap | `WorkoutSession.timestamp`, volume calculations |
| Muscle Group Heatmap | `Exercise.muscleGroup`, `WorkoutSession` joins |
| This Week Summary | `WorkoutSession` aggregations |
| Lifetime Stats | `GamificationStats` + `WorkoutSession` counts |
| Volume Trend | `WorkoutSession` weekly aggregations |
| Mode Distribution | `WorkoutSession.workoutMode` counts |
| Time Patterns | `WorkoutSession.timestamp` hour/day extraction |
| PR List | `PersonalRecord` table |

## Technical Notes

- Existing `AnalyticsModels.kt` has `TrendData`, `TrendPoint`, `TrendDirection` ready to use
- `ComparativeAnalyticsUseCase.kt` can be extended for week-over-week comparisons
- Badge requirements in `Gamification.kt` provide target values for progress bars
- Consider using Compose Multiplatform charting library (e.g., Vico, Charts by Patrykandpatrick)

## Research Sources

- [Hevy - Muscle Group Charts](https://www.hevyapp.com/features/muscle-group-workout-chart/)
- [Strong App](https://www.strong.app/)
- [StrengthLog](https://www.strengthlog.com/)
- [PRZone](https://www.przone.app/)
- [MuscleSquad Heatmaps](https://musclesquad.com/blogs/musclesquad-training-app/hits-and-heatmap)
- [Fito Data Visualization](https://getfitoapp.com/en/best-fitness-data-analysis/)
- [Quantize Analytics - Fitness Dashboard Examples](https://www.quantizeanalytics.co.uk/fitness-dashboard-example/)
