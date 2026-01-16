# Project Phoenix - Context for Gemini

## Project Overview
**Project Phoenix** is a Kotlin Multiplatform (KMP) application designed to control Vitruvian Trainer workout machines (V-Form Trainer and Trainer+) via Bluetooth Low Energy (BLE). This is a community-driven "rescue project" to ensure these machines remain functional.

## Key Technologies
*   **Language:** Kotlin 2.0+ (2.1.20)
*   **UI Framework:** Compose Multiplatform 1.8.0 (Android, Desktop, iOS)
*   **Architecture:** Clean Architecture + MVVM
*   **Dependency Injection:** Koin 4.0.0
*   **Database:** SQLDelight 2.2.1
*   **Asynchronous:** Coroutines 1.10.2 + Flow
*   **BLE:** Nordic Android BLE Library (Android), CoreBluetooth (iOS), BlueZ (Linux/Desktop - planned)

## Project Structure
*   `shared/`: Kotlin Multiplatform shared code (Domain, Data, BLE logic, ViewModels).
    *   `commonMain/`: Cross-platform logic.
    *   `androidMain/`: Android-specific implementations (e.g., Nordic BLE).
    *   `desktopMain/`: Desktop-specific implementations.
    *   `iosMain/`: iOS-specific implementations.
*   `androidApp/`: Android entry point and platform-specific configuration.
*   `desktopApp/`: Desktop (JVM) entry point.
*   `iosApp/`: iOS application (Xcode project consuming shared framework).

## Build & Run Commands

### Android
*   **Build:** `./gradlew :androidApp:assembleDebug`
*   **Install:** `./gradlew :androidApp:installDebug`
*   **Run Tests:** `./gradlew :androidApp:testDebugUnitTest`

### Desktop
*   **Run:** `./gradlew :desktopApp:run`

### Shared Library
*   **Run All Tests:** `./gradlew :shared:allTests`
*   **Build iOS Framework:** `./gradlew :shared:assembleXCFramework`

### General
*   **Clean:** `./gradlew clean`
*   **Full Build:** `./gradlew build`

## Development Conventions
*   **UI:** Use **Compose Multiplatform**. Avoid `android.view.*` or `java.awt.*` in `commonMain`.
*   **Date/Time:** Use `kotlinx-datetime`. Avoid `java.util.Date` or `java.time.*`.
*   **Logging:** Use a multiplatform logging library (e.g., Kermit) instead of `android.util.Log` or `Timber`.
*   **Resources:** Use Moko Resources or Compose Multiplatform Resources for strings/images.
*   **BLE:** The BLE logic is critical.
    *   **Service UUID:** `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART)
    *   **TX Char:** `6e400002...`
    *   **RX Char:** `6e400003...`
    *   **Device Names:** Start with `Vee_` (V-Form) or `VIT` (Trainer+).

## Current State & Migration
The project is currently migrating UI components from an existing Android-only codebase to Compose Multiplatform.
*   **Status:** ~80% of UI components migrated.
*   **Pending:** Complex charts (MPAndroidChart -> Vico/Canvas), Video Player (expect/actual needed), some platform-specific date/time logic.
*   **Refer to:** `UI_COMPONENTS_MIGRATION_REPORT.md` for detailed status on specific components.

## Important Files
*   `shared/build.gradle.kts`: Shared module dependencies.
*   `gradle/libs.versions.toml`: Dependency version catalog.
*   `shared/src/commonMain/sqldelight/`: Database schemas.
*   `shared/src/commonMain/kotlin/com/example/vitruvianredux/domain/model/`: Core domain models.
