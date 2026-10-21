<p align="center">
  <h1 align="center">Nothing Music</h1>
</p>

<p align="center">
  <b>A distraction-free Android music player.</b><br />
  Local library and cloud drives in one place — 5-band equalizer, synced lyrics, sleep timer, and a clean monochrome interface.
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> | English
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.2-purple" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-26%2B-blue" alt="Android" />
  <img src="https://img.shields.io/badge/Media3%2FExoPlayer-1.3.1-9cf" alt="ExoPlayer" />
  <img src="https://img.shields.io/badge/License-MIT-green" alt="License" />
</p>

---

## About

Nothing Music is a native Android music player built with **Kotlin** and **Jetpack Compose** (Material 3). It plays both your local audio library and music stored on remote sources through **Media3/ExoPlayer** — no accounts required for the app itself and no ads. Local files are indexed into a **Room** database; remote libraries are synced into a separate database and streamed on demand. Everything renders in a calm, high-contrast "dot-matrix" aesthetic.

## Features

- **Local library** — scans folders via the storage-access framework and persists metadata to a local Room/SQLite database. Supports MP3, AAC, M4A, FLAC, OGG, Opus, WAV, ALAC, AIFF, WMA, DSF and DFF.
- **Remote sources** — connect cloud drives and self-hosted servers, sync their catalogs, and stream on demand:
  - **Aliyun Drive** (阿里云盘) — OpenAPI client with signed URL resolution and embedded lyrics/artwork probing.
  - **OpenList / AList** — AList v3 API (`/api/fs/list`, `/api/fs/get`), works with any storage driver OpenList mounts.
  - **WebDAV** — HTTP Range streaming, sidecar lyrics and artwork.
  - **SMB** — direct SMB session streaming for NAS shares.
  - **Navidrome** — Subsonic-compatible music server.
- **Synced lyrics** — embedded lyrics first, then sidecar files; when neither exists it falls back to online lookup on [LRCLIB](https://lrclib.net). Shown inline on the Now Playing screen.
- **Foreground media playback** — a `mediaPlayback` foreground service (Media3 `MediaSessionService`) keeps audio playing with the screen off, with wake-lock support.
- **5-band equalizer** — ±15 dB per band.
- **Sleep timer** — fades playback out and stops at the configured time.
- **Playlists & favorites** — create playlists and favorite tracks.
- **Instant search** — searches tracks, artists and albums as you type.
- **Chinese localization** — full `values-zh` translation, plus a manual language and theme override in Settings.

## Requirements

- Android 8.0+ (API 26), targets API 36
- JDK 17 (Temurin recommended — Gradle comes via the included wrapper)
- Android SDK 36 (`local.properties` → `sdk.dir`)

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

## Project structure

```
NothingMusic/
├── build.gradle.kts           # Root build — declares shared plugins
├── settings.gradle.kts        # Gradle multi-project setup
├── gradlew                    # Gradle wrapper
├── gradle/
│   ├── libs.versions.toml     # Version catalog (deps & plugins)
│   └── wrapper/               # Wrapper jar & properties
└── app/
    ├── build.gradle.kts       # App module (Android + Kotlin + Compose + KSP)
    ├── proguard-rules.pro     # Keep rules for Room entities & Media3
    └── src/main/
        ├── AndroidManifest.xml      # Permissions, activities, media service, artwork provider
        ├── java/com/nothingmusic/
        │   ├── MainActivity.kt      # App entry (Hilt + NavGraph)
        │   ├── data/                # Room DB, repository, lyrics resolution, audio scanner
        │   ├── domain/              # Domain models & repository interfaces
        │   ├── remote/              # Remote track <-> playback mapping and hydration
        │   ├── service/             # Playback service, controller, sleep timer
        │   ├── ui/                  # Compose screens, components, theme, navigation
        │   └── util/                # Constants, file/permission utils, settings, theme mode
        ├── java/com/mica/music/     # Remote-source engine (ported)
        │   ├── data/remote/         # Source models, credential store, sync engines,
        │   │                        #   artwork/lyrics transports, playback routing
        │   │   ├── aliyundrive/     # Aliyun Drive OpenAPI client
        │   │   ├── alist/           # OpenList / AList v3 client
        │   │   ├── navidrome/       # Navidrome (Subsonic) client
        │   │   ├── webdav/          # WebDAV client
        │   │   └── smb/             # SMB client
        │   └── media/               # MicaRoutingDataSource (mica-remote:// routing)
        └── res/                     # App icon, fonts, themes, strings (+ values-zh)
```

## Architecture

- **Hilt** wires the app together (`di/AppModule.kt`): a singleton `MusicRepositoryImpl` owns the local Room database, the `AudioScanner` indexes files into it, and `PlayerViewModel` exposes player state to the Compose UI.
- **Remote catalog** lives in its own Room database (`RemoteDatabase`) behind `RemoteCatalogRepository`. Each source type has a sync engine (`AliyunDriveSourceSync`, `AListSourceSync`, WebDAV, SMB) that walks the remote tree and atomically publishes a catalog snapshot; metadata and artwork are probed lazily, only when a track is played.
- **Playback** goes through `MicaRoutingDataSource`, which routes `mica-remote://` URIs to a short-lived signed URL resolved by the matching source adapter. `PlaybackService` (`service/PlaybackService.kt`) is a foreground service owning the ExoPlayer `PlayerController` and media session, decoupled from the UI.
- **Credentials** for remote sources are kept in `AndroidKeystoreRemoteCredentialStore`, never in plain preferences.
- **Lyrics** resolve through `LrcLyricsResolver`: embedded tags → sidecar file → LRCLIB online lookup, cached on disk per revision.
- **Navigation** uses `androidx.navigation.compose` (`ui/navigation/NavGraph.kt`) with routes for Library, Cloud, Cloud Sources, Settings, Now Playing, Queue, Equalizer, Folder Picker, Album detail and Playlist detail.
- **Theme** (`ui/theme/`) is a custom monochrome Material 3 theme with a dot-matrix display font, plus a manual dark/light override.

## Notes on self-hosted sources

- **OpenList / AList**: the app talks to the AList v3 API. Enter the server as `http(s)://host:port` — a path or query string is rejected. Leave username and password empty only if guest access is enabled on the server; otherwise use an account that can read the music folder.
- **Self-signed / plain HTTP**: self-hosted servers usually run on `http://` with a local IP, which Android blocks by default on API 28+. The app opts into cleartext traffic for this reason.
- **Aliyun Drive** requires a `refresh_token`; automatic refresh is delegated to a relay endpoint because individual developer credentials are not available.

## Contributing

This is an open source project. See [CONTRIBUTING.md](CONTRIBUTING.md) for environment setup, build commands, and PR guidelines.

## License

Released under the **MIT License**. See [LICENSE](LICENSE).
