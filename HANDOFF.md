# Handoff — continuing on Windows

## Where this stands

A working Android app: embedded Ktor (CIO) HTTP server in a foreground service, backed by
SQLite via Room, with a Compose UI. Built, installed and verified end-to-end on a physical
Samsung SM-X210 (Android 15) — all endpoints exercised, persistence confirmed across a
process force-stop.

See `README.md` for endpoints, build steps and the Android-specific gotchas already solved
(CIO over Netty, slf4j-simple instead of Logback, `dataSync` foreground service type,
META-INF packaging excludes, R8 keep rules).

## The goal

**One Kotlin codebase producing both an Android APK and a Windows .exe.**

Target structure — Kotlin Multiplatform + Compose Multiplatform, single module:

```
composeApp/
├── src/commonMain/    Ktor routes, Room entities/DAO, Compose UI  <- everything shared
├── src/androidMain/   MainActivity + foreground service
└── src/desktopMain/   fun main() { application { Window { App() } } }
```

```bash
./gradlew :composeApp:assembleDebug        # -> APK
./gradlew :composeApp:packageReleaseExe    # -> Windows installer (Windows only)
```

## Why the work moved to Windows

`jpackage`, which Compose Desktop uses to produce the installer, does not cross-compile.
A Windows `.exe` must be built on Windows. Everything else (KMP restructure, APK) can be
done anywhere; the decision was to do it all on the Windows PC.

## Migration notes for the next session

What ports unchanged:
- **Ktor CIO** is plain Kotlin/JVM. `server/KtorServer.kt` is 206 lines with exactly one
  Android import (`android.util.Log`) — swap it for SLF4J and it compiles for both targets.
- **Room 2.7.2** (already pinned here) supports Multiplatform via the bundled SQLite driver.
  Needs `androidx.sqlite:sqlite-bundled`, `@ConstructedBy` + an `expect object` database
  constructor, and per-target KSP configurations (`kspAndroid`, `kspDesktop`).
- `util/NetworkUtils.kt` — zero Android imports.

What gets rewritten:
- `MainActivity.kt` (221 lines, 41 androidx imports): Compose imports move from
  `androidx.compose.*` to `org.jetbrains.compose.*`; the screen body itself is portable.
- `service/KtorServerService.kt` (158 lines): Android-only. On desktop the server just
  starts with the process — no service, no notification.
- `UserRepository.kt` catches `android.database.sqlite.SQLiteConstraintException`; needs a
  common equivalent (Room KMP throws `androidx.sqlite.SQLiteException`).

Version compatibility to verify on Windows:
- Compose Multiplatform release matching Kotlin 2.1.20 (check JetBrains' compatibility table;
  CMP 1.8.x is the likely match — confirm before pinning).
- Keep AGP 8.8.2 / Gradle 8.13 unless something forces an upgrade.

## Open questions, deliberately not decided

1. Share the UI too (Compose Multiplatform, one screen both platforms), or share only the
   server + database and write a separate desktop UI? Recommendation: share everything —
   it matches the stated goal.
2. Add a GitHub Actions workflow to build both artifacts on every tag? Useful even with a
   Windows machine available, so the APK and .exe are always built the same way.

## Security note

On a phone the server is one app among many; on a desktop, binding `0.0.0.0:8080` exposes it
to the whole network and Windows Firewall will prompt on first run. Worth adding auth (even a
static bearer token) before the desktop build leaves your LAN.

## Environment on the Mac (for reference)

Gradle 8.13 · AGP 8.8.2 · Kotlin 2.1.20 · Ktor 3.1.3 · Room 2.7.2 · KSP 2.1.20-2.0.1
compileSdk 35 · minSdk 26 · JDK 17
