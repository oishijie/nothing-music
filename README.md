<p align="center">
  <h1 align="center">Nothing Music</h1>
</p>

<p align="center">
  <b>A distraction-free Android music player.</b><br />
  Fully offline, local library — with a 5-band equalizer, LRC lyrics, sleep timer, and a clean monochrome interface.
</p>

<p align="center">
  <a href="https://github.com/NavatejR/nothing-music/actions/workflows/ci.yml">
    <img src="https://github.com/NavatejR/nothing-music/actions/workflows/ci.yml/badge.svg" alt="CI" />
  </a>
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-26%2B-blue" alt="Android" />
  <img src="https://img.shields.io/badge/Media3%2FExoPlayer-1.3.1-9cf" alt="ExoPlayer" />
  <img src="https://img.shields.io/badge/License-MIT-green" alt="License" />
</p>

---

## About

Nothing Music is a native Android music player built with **Kotlin** and **Jetpack Compose** (Material 3). It plays your local audio library through **Media3/ExoPlayer** — no accounts, no cloud, no ads. The app indexes your files into a local **Room** database, serves playback through a foreground media service, and renders everything in a calm, high-contrast "dot-matrix" aesthetic.

## Features

- **Local-first library** — scans folders via the storage-access framework and persists metadata to a local Room/SQLite database (`nothing_music.db`). Supports MP3, AAC, M4A, FLAC, OGG, Opus, WAV, ALAC, AIFF, WMA, DSF and DFF.
- **Foreground media playback** — a `mediaPlayback` foreground service (Media3 `MediaSessionService`) keeps audio playing with the screen off, with wake-lock support.
- **5-band equalizer** — ±15 dB per band, tuned via the equalizer screen.
- **LRC lyrics** — parses `.lrc` sidecar files and shows synced lyrics.
- **Sleep timer** — fades playback out (default 30 s) and stops at the configured time.
- **Playlists & favorites** — create playlists and favorite tracks, all stored locally.
- **Album & artist browsing** — instant search across tracks; quick-stats on the search screen.
- **Mini player** — a floating mini player keeps controls available on the library screen.

## Requirements

- Android 26+ (AndroidX / Kotlin 2.0 / Jetpack Compose)
- JDK 17 (Temurin recommended — Gradle 8.9 comes via the included wrapper)
- Android SDK 34 (`local.properties` → `sdk.dir`)

## Building

A Gradle wrapper is included, so no local Gradle install is needed. Point `local.properties` at your Android SDK (or set `ANDROID_HOME`) — the file is machine-specific and gitignored:

```
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew app:assembleDebug      # debug build (app suffix `.debug`)
./gradlew app:assembleRelease    # release build (minified + shrinkResources)
```

Install the resulting `app/build/outputs/apk/.../app-*.apk` on a device or emulator.

## Testing

```bash
./gradlew app:testDebugUnitTest  # unit tests (JUnit + Robolectric)
./gradlew app:lintDebug          # Android lint
```

GitHub Actions runs tests, the debug build, and lint on every push and pull request. Tagged releases (`v*`) additionally build a release APK and attach it to a GitHub Release.

## Project structure

```
NothingMusic/
├── build.gradle.kts           # Root build — declares shared plugins
├── settings.gradle.kts        # Gradle multi-project setup
├── gradlew                    # Gradle wrapper (pinned Gradle 8.9)
├── gradle/
│   ├── libs.versions.toml     # Version catalog (deps & plugins)
│   └── wrapper/               # Wrapper jar & properties
└── app/
    ├── build.gradle.kts       # App module (Android + Kotlin + Compose)
    ├── proguard-rules.pro     # Keep rules for Room entities & Media3
    └── src/main/
        ├── AndroidManifest.xml      # Permissions, activities, media service
        ├── java/com/nothingmusic/
        │   ├── MainActivity.kt      # App entry (Hilt + NavGraph)
        │   ├── data/                # Room DB, entity/DAO, lyrics, audio scanner
        │   ├── domain/              # Domain models & repository interface
        │   ├── service/             # Playback service, controller, sleep timer
        │   ├── ui/                  # Compose screens, components, theme, navigation
        │   └── util/                # Constants, file/permission utils, settings
        └── res/                     # App icon, fonts, themes, strings
```

## Architecture

- **Hilt** wires the app together (`di/AppModule.kt`): a singleton `MusicRepositoryImpl` owns the Room database, the `AudioScanner` indexes files into it, and `PlayerViewModel` exposes player state to the Compose UI.
- **PlaybackService** (`service/PlaybackService.kt`) is an AndroidX foreground service that owns the ExoPlayer `PlayerController` and media session, decoupled from the UI.
- **Navigation** uses `androidx.navigation.compose` (`ui/navigation/NavGraph.kt`) with routes for Library, Search, Settings, Now Playing, Queue, Equalizer, Folder Picker, Album detail and Playlist detail.
- **Theme** (`ui/theme/`) is a custom monochrome Material 3 theme with a dot-matrix display font.

## Contributing

This is an open source project. See [CONTRIBUTING.md](CONTRIBUTING.md) for environment setup, build commands, and PR guidelines.

## License

Released under the **MIT License**. See [LICENSE](LICENSE).