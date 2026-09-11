# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android application. Gradle configuration lives in `settings.gradle.kts`, the root `build.gradle.kts`, and `gradle.properties`; use the checked-in Gradle wrapper rather than a system Gradle installation. Application code is under `app/src/main/java/xyz/sakulik/comic/`, organized by responsibility: `ui/` and `ui/components/` for Jetpack Compose screens, `viewmodel/` for screen state, `model/db/` for Room persistence, `model/network/` for Retrofit services, `model/loader/` and `model/scanner/` for comic files, and `di/` for dependency injection setup. Android resources and the manifest are in `app/src/main/res/` and `app/src/main/AndroidManifest.xml`. There are currently no checked-in `test` or `androidTest` source trees.

## Build, Test, and Development Commands

Use JDK 17 and Android Studio Ladybug (or newer). The checked-in Gradle wrapper may be used for command-line builds and tests, for example `./gradlew assembleDebug` or `./gradlew test`. Android Studio remains supported for Gradle/KSP synchronization, device runs, and IDE inspections. Select an emulator or connected device and use the IDE's **Run** action for local development when needed. Do not commit generated `build/` or IDE files. The optional ComicVine API key is configured in the app’s settings, not in source control.

## Coding Style & Naming Conventions

Use Kotlin with four-space indentation, expression-oriented functions where readable, and explicit types for public APIs. Follow existing package boundaries and Compose conventions: `PascalCase` for classes/composables, `camelCase` for functions, properties, and parameters, and `UPPER_SNAKE_CASE` for constants. Keep Android resources lowercase with underscores (for example, `ic_folder.xml`). Run Android Studio’s Kotlin formatter/inspection tools; do not commit generated `build/` or IDE files.

## Testing Guidelines

Add JVM tests under `app/src/test` and device tests under `app/src/androidTest`, naming classes and methods after the behavior under test (for example, `ComicNameParserTest`). Exercise parsing, loaders, database behavior, and ViewModel state transitions where practical. Run the narrowest relevant test configuration in Android Studio, then inspect the affected module before submission.

## Commit & Pull Request Guidelines

Existing commits are short, informal, lowercase summaries (for example, `fixes to make the app workable`). Keep new subjects brief and action-oriented, ideally describing one change. Pull requests should explain user-visible and architectural effects, link any issue, list validation commands, and include screenshots or a short recording for UI changes. Call out database/schema, permissions, or API-key handling changes explicitly.
