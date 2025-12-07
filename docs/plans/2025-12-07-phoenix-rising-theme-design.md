# Phoenix Rising Theme Design

## Overview

Replace the current purple/teal theme with a unified "Phoenix Rising" color system: **Energetic Orange (Primary) + Deep Slate (Backgrounds) + Electric Teal (Accents)**.

### Why This Works

- **Orange (Activity)**: Biologically stimulates alertness, high visibility against dark backgrounds
- **Teal (Data)**: Cool color to balance the hot primary, colorblind-safe when paired with orange
- **Slate (Surfaces)**: Deep blue-grey (`#0F172A`) instead of pure black - reduces OLED smearing and eye strain

## Scope

### Files to Modify
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt`

### Files to Add
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/DataColors.kt`

### Out of Scope
- `util/ColorScheme.kt` (LED device colors - separate system)

## Color Palette

### Core Brand Colors

| Role | Light Mode | Dark Mode | Usage |
|------|------------|-----------|-------|
| Phoenix Orange (Primary) | `#D94600` | `#FFB590` | FABs, main actions, active states |
| Ember Yellow (Secondary) | `#6A5F00` | `#E2C446` | Secondary actions, toggles |
| Ash Blue (Tertiary) | `#006684` | `#6ED2FF` | Cool accents to balance heat |

### Slate Neutrals

| Token | Hex | Usage |
|-------|-----|-------|
| Slate950 | `#020617` | Near-black base (OLED-friendly) |
| Slate900 | `#0F172A` | Main background |
| Slate800 | `#1E293B` | Card backgrounds |
| Slate700 | `#334155` | Borders, dividers |
| Slate50 | `#F8FAFC` | Light mode background |

### Signal Colors

| Status | Hex | Notes |
|--------|-----|-------|
| Success | `#22C55E` | Green - not orange to avoid confusion |
| Error | `#EF4444` | Red |
| Warning | `#F59E0B` | Amber |

### Data Colors (Charts)

Colorblind-safe palette with distinct luminance values:

| Metric | Hex | Color |
|--------|-----|-------|
| Volume | `#3B82F6` | Blue |
| Intensity | `#F59E0B` | Amber |
| HeartRate | `#EF4444` | Red (use with icons) |
| Duration | `#10B981` | Emerald |
| OneRepMax | `#8B5CF6` | Violet |
| Power | `#06B6D4` | Cyan |

Delivery: Simple `DataColors` object (not CompositionLocal) since these don't change with light/dark mode.

## Material 3 Token Mapping

### Dark Mode

```kotlin
darkColorScheme(
    primary = PhoenixOrangeDark,           // #FFB590
    onPrimary = Color(0xFF4C1400),         // Deep brown
    primaryContainer = Color(0xFF702300),
    onPrimaryContainer = Color(0xFFFFDBCF),

    secondary = EmberYellowDark,           // #E2C446
    onSecondary = Color(0xFF373100),
    secondaryContainer = Color(0xFF4F4700),
    onSecondaryContainer = Color(0xFFFFE06F),

    tertiary = AshBlueDark,                // #6ED2FF
    onTertiary = Color(0xFF003546),

    background = Slate900,                 // #0F172A
    onBackground = Color(0xFFE2E8F0),      // Slate200

    surface = Slate900,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Slate800,             // Cards
    onSurfaceVariant = Color(0xFF94A3B8),  // Slate400

    surfaceDim = Slate950,
    surfaceContainer = Slate900,
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,

    outline = Slate700,
    error = SignalError
)
```

### Light Mode

```kotlin
lightColorScheme(
    primary = PhoenixOrangeLight,          // #D94600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF380D00),

    secondary = EmberYellowLight,          // #6A5F00
    onSecondary = Color.White,

    background = Slate50,                  // #F8FAFC
    onBackground = Slate900,

    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFE2E8F0),    // Slate200
    onSurfaceVariant = Slate700,

    outline = Color(0xFF94A3B8)            // Slate400
)
```

## Migration Strategy

### Phase 1: Core Theme Files
1. Update `Color.kt` with Phoenix palette (replace purple/teal colors)
2. Update `shared/.../Theme.kt` with new color scheme mappings
3. Add `DataColors.kt` object
4. Update `androidApp/.../Theme.kt`:
   - Remove dynamic color (breaks brand identity)
   - Delegate to shared theme
   - Keep status bar coloring logic

### Phase 2: Chart Components (8 files)
Update each to use `DataColors` instead of hardcoded colors:
- `VolumeHistoryChart.kt`
- `VolumeTrendChart.kt`
- `AreaChart.kt`
- `ComboChart.kt`
- `CircleChart.kt`
- `GaugeChart.kt`
- `RadarChart.kt`
- `WorkoutMetricsDetailChart.kt`

### Phase 3: Spot Fixes
Grep for hardcoded `Color(0x...)` values outside theme files and migrate to semantic tokens.

## Testing

- Visual check on both light/dark modes
- Verify charts remain distinguishable (especially Volume vs Intensity)
- Test on actual OLED device (smearing check with Slate vs pure black)
- Accessibility: confirm 4.5:1 contrast ratios for text

## Design Principles

1. **Hierarchy via Tone**: Use Slate scale for depth, not opacity hacks
2. **Action Color**: Warm (Orange) = clickable, Cool (Slate/Blue) = readable
3. **Accessible Charts**: Distinct luminance ensures greyscale distinguishability
4. **One Identity**: Same Orange across modes (light/dark variants), not different colors per mode
