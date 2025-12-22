# Profile Side Panel Design

**Date:** 2025-12-21
**Status:** Approved
**Replaces:** ProfileSpeedDial FAB component

## Problem

The ProfileSpeedDial FAB on the homescreen and JustLiftScreen feels intrusive. Users need a less obtrusive way to switch profiles.

## Solution

Replace the FAB with a slide-in panel from the right edge, triggered by a chevron handle or edge swipe.

---

## Visual Structure

### Collapsed State (Chevron Handle)
- Small chevron `‹` on the right edge, vertically centered
- Pill-shaped container (~24dp wide × 48dp tall)
- Accent color matches active profile's color
- Triggers: tap to open, or swipe left from right edge (~20dp zone)

### Expanded State (Slide-out Panel)
- 200dp wide panel slides in from right
- Semi-transparent scrim covers rest of screen
- Rounded top-left and bottom-left corners
- Background: `surfaceContainer` elevated surface

### Panel Contents
1. Header: "Profiles" label
2. Profile list: avatar circle (initial + color) + name
3. Active profile: highlighted with checkmark
4. Footer: "Add Profile" row with + icon

---

## Interactions

### Opening
- Tap chevron → panel slides in (300ms, ease-out)
- Edge swipe from right → follows finger, snaps open/closed
- Scrim fades in with panel

### Closing
- Tap scrim → panel slides out
- Swipe right on panel → snaps closed
- Profile selection → auto-close after selection

### Profile Selection
- Tap profile row → `setActiveProfile(id)`, close panel
- Chevron handle color updates to new active profile

### Profile Management (Long-press)
- Long-press profile row → context menu appears
- Options: Edit (pencil) | Delete (trash)
- Default profile: delete option disabled

### Edit Profile Flow
- Opens EditProfileDialog
- Pre-filled name field
- Color picker: 8 color circles
- Save updates profile and refreshes list

### Delete Profile Flow
- Confirmation dialog: "Delete profile [Name]?"
- If deleting active profile → switch to default first
- Cannot delete the default profile

---

## Component Architecture

### New Components

**ProfileSidePanel.kt**
- Main composable replacing ProfileSpeedDial
- Manages open/closed state
- Renders chevron handle + drawer content
- Handles swipe gesture detection

**ProfileListItem.kt**
- Individual profile row composable
- Avatar + name + active indicator
- Tap to select, long-press for context menu
- Reuses existing `ProfileAvatar` from ProfileSpeedDial.kt

**EditProfileDialog.kt**
- Dialog for editing existing profiles
- Name text field (pre-filled)
- Color picker (8 color circles in a row)
- Save/Cancel buttons

### Repository
- No new methods needed
- Uses existing: `updateProfile(id, name, colorIndex)`, `deleteProfile(id)`

### Screen Integration
- Replace `ProfileSpeedDial` in:
  - `JustLiftScreen.kt`
  - `EnhancedMainScreen.kt`
- Props: profiles, activeProfile, onProfileSelected, onAddProfile, onEditProfile, onDeleteProfile

---

## Files to Modify

| File | Action |
|------|--------|
| `ProfileSpeedDial.kt` | Keep `ProfileAvatar`, `AddProfileDialog`, `ProfileColors` |
| `ProfileSidePanel.kt` | Create new (main component) |
| `ProfileListItem.kt` | Create new (list row) |
| `EditProfileDialog.kt` | Create new (edit dialog) |
| `JustLiftScreen.kt` | Replace ProfileSpeedDial with ProfileSidePanel |
| `EnhancedMainScreen.kt` | Replace ProfileSpeedDial with ProfileSidePanel |

---

## Out of Scope
- Profile reordering
- Profile import/export
- Profile avatars (images instead of initials)
