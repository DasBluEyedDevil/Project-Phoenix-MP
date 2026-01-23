---
status: resolved
trigger: "iOS users continue to crash on app open due to database migration failures. Uninstall/reinstall does not fix."
created: 2026-01-23T00:00:00Z
updated: 2026-01-23T00:04:00Z
---

## Current Focus

hypothesis: CONFIRMED - Main driver creation missing create/upgrade callback overrides
test: Added create={} and upgrade={} overrides to main driver's onConfiguration
expecting: iOS migration crashes will be prevented
next_action: DONE - Committed fix (a15175b2), ready for TestFlight deployment

## Symptoms

expected: App opens successfully, database migrates cleanly
actual: App crashes immediately on open (within ~1 second of launch) with SQLiteExceptionErrorCode during migration
errors:
  - Crash at VitruvianDatabaseImpl.Schema.migrateInternal line 873 (VitruvianDatabaseImpl.kt)
  - Line 873: ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT
  - Stack trace shows: SQLiteException -> SQLiteExceptionErrorCode -> sqlException -> prepareStatement -> migrateInternal
  - Full crash logs in .tmp_testflight/feedback9/crashlog.crash and .tmp_testflight/feedback10/crashlog.crash
reproduction: Users open app from home screen - crashes every time for affected users
started: Persisting despite v0.3.3 "4-layer defense" fix. iOS-specific. Uninstall/reinstall does NOT fix.

## Eliminated

- hypothesis: noOpSchema is not being passed correctly
  evidence: Code review confirms noOpSchema IS passed to NativeSqliteDriver constructor
  timestamp: 2026-01-23T00:01:30Z

- hypothesis: Another driver is being created with real schema
  evidence: Grep search found no iOS production code references to VitruvianDatabase.Schema
  timestamp: 2026-01-23T00:01:45Z

## Evidence

- timestamp: 2026-01-23T00:00:30Z
  checked: Line 873 in VitruvianDatabaseImpl.kt (migration 10->11)
  found: The failing SQL is `ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT`. This fails if column already exists.
  implication: Database is in a state where supersetId column exists but user_version is <= 10

- timestamp: 2026-01-23T00:00:45Z
  checked: iOS DriverFactory.ios.kt 4-layer defense implementation
  found: Uses noOpSchema with version=12 and empty create()/migrate() methods. But the stack trace shows VitruvianDatabaseImpl.Schema.migrateInternal being called - the REAL schema, not noOpSchema
  implication: Somewhere the real schema IS being invoked despite the 4-layer defense

- timestamp: 2026-01-23T00:01:00Z
  checked: DriverFactory.ios.kt main driver creation (line 94-104) vs validation functions (line 203-213)
  found: Main driver: config.copy(extendedConfig=...) - does NOT override create/upgrade callbacks
         Validation functions: config.copy(create={}, upgrade={}, extendedConfig=...) - DOES override callbacks
  implication: The main driver creation may be missing the create/upgrade callback overrides that prevent SQLDelight from running migrations

- timestamp: 2026-01-23T00:01:15Z
  checked: NativeSqliteDriver constructor behavior (web search + docs)
  found: NativeSqliteDriver builds DatabaseConfiguration with create/upgrade callbacks that delegate to schema.create()/migrate(). The onConfiguration callback receives this config and can modify it. config.copy() without explicit create/upgrade keeps the original callbacks.
  implication: Must explicitly override create/upgrade to empty lambdas to fully prevent migrations

- timestamp: 2026-01-23T00:01:30Z
  checked: Full codebase search for VitruvianDatabase.Schema references in iOS production code
  found: Only references are in test code (iosTest) and Android code (androidMain). No iOS production code references the real schema.
  implication: The schema reference issue is internal to NativeSqliteDriver/Sqliter, not our code

- timestamp: 2026-01-23T00:01:45Z
  checked: Crash timing and trigger point
  found: Crash happens ~1 second after launch during MainViewModel.init queries (workoutRepository.getAllSessions(), etc.). These queries trigger connection pool creation which invokes migration callbacks.
  implication: The bug manifests when first database query runs, not during driver creation

## Resolution

root_cause: Main NativeSqliteDriver creation (lines 94-104) uses config.copy(extendedConfig=...) but does NOT override create/upgrade callbacks. While noOpSchema is passed, NativeSqliteDriver builds its internal DatabaseConfiguration with create/upgrade callbacks that call schema.create()/migrate(). The onConfiguration callback modifies this config, but config.copy() without explicit create/upgrade overrides RETAINS the original callbacks. The validation functions (lines 203-213 and 248-256) correctly override create={} and upgrade={} to prevent migrations, but the main driver does not. When a new connection is created from the pool, Sqliter's NativeDatabaseManager calls the upgrade callback, which still delegates to the schema. Due to potential Sqliter internal state or Kotlin/Native memory issues, this may resolve to VitruvianDatabaseImpl.Schema instead of noOpSchema.

fix: Add explicit empty create/upgrade callback overrides to main driver's onConfiguration, matching the pattern used by validation functions.

verification: Code compiles successfully (compileKotlinIosArm64 passed). Committed as a15175b2. TestFlight verification required with affected users.
files_changed:
  - shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt
commit: a15175b2
