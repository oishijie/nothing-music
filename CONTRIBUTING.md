# Contributing to Nothing Music

Thanks for your interest in contributing! This document covers everything you need to get a development environment running and submit changes with confidence.

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17 (Temurin recommended) |
| Android SDK | Platform 34, Build-Tools 34.0.0 |
| Gradle | 8.9 (via the included wrapper — no manual install needed) |

The Gradle wrapper (`./gradlew`) downloads the exact Gradle version automatically, so you only need to install a JDK and point the project at an Android SDK.

## Getting started

1. **Fork & clone**

   ```bash
   git clone https://github.com/<your-username>/NothingMusic.git
   cd NothingMusic
   ```

2. **Point the project at your Android SDK** — create a `local.properties` file in the repo root (it is gitignored and machine-specific):

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

   If `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set, Gradle will find the SDK without this file.

3. **Build & test**

   ```bash
   ./gradlew app:assembleDebug      # debug build
   ./gradlew app:testDebugUnitTest  # unit tests
   ./gradlew app:lintDebug          # Android lint
   ./gradlew app:assembleRelease    # minified release build
   ```

CI runs tests, the debug build, and lint on every push and pull request, so keeping those green locally will keep your PR green too.

## Project layout

```
app/src/main/java/com/nothingmusic/
├── data/          # Room DB (entities, DAOs), audio scanner, lyrics parser
├── di/            # Hilt modules
├── domain/        # Domain models & repository interface
├── service/       # PlaybackService, MediaController wrapper, sleep timer
├── ui/            # Compose screens, components, theme, navigation
└── util/          # Constants, settings store, helpers
```

## Pull request guidelines

- **Keep PRs small and reviewable.** One feature or fix per PR.
- **Add tests for new logic.** Pure logic (parsers, formatters) belongs in `app/src/test/` — see `LyricsParserTest` for the pattern.
- **Run the checks before pushing:** `./gradlew app:testDebugUnitTest app:lintDebug`
- **Match existing style.** The project uses the official Kotlin coding style (`.editorconfig` included; most IDEs pick it up automatically).
- **Describe the why.** Commit messages and PR descriptions should explain the motivation, not just the mechanics.

## Reporting bugs

Open an issue and include:

- Device model and Android version
- Steps to reproduce
- Expected vs. actual behavior
- Logcat output if available (`adb logcat | grep nothingmusic`)

## License

By contributing, you agree that your contributions will be licensed under the MIT License that covers this project.
