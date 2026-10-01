# Handoff — Windows / desktop target

## Where this stands

**The KMP restructure is done.** One module, `composeApp`, builds both targets from one
codebase:

| Target | Task | Verified |
|---|---|---|
| Android APK | `./gradlew :composeApp:assembleDebug` | builds on macOS |
| Desktop app | `./gradlew :composeApp:run` | runs; all 5 endpoints exercised on macOS |
| Windows `.exe` | `./gradlew :composeApp:packageReleaseExe` | **not yet — must run on Windows** |

The desktop build was verified end-to-end on macOS: server starts with the process,
Room created `~/.tucount-server/ktor-server.db`, the seed callback ran, and
`GET/POST/DELETE` returned 200/201/204 with 400/404/409 on the error paths.

Because the desktop target is plain Kotlin/JVM, "runs on macOS" and "runs on Windows"
differ only in packaging — but the `.exe` itself is still unproduced (see below).

## What is left

1. **Build the installer on Windows.** `jpackage` does not cross-compile.
   On the Windows PC: `./gradlew :composeApp:packageReleaseExe`.
   Needs a JDK 17+ that includes `jpackage`, and WiX Toolset 3.x on PATH for `.exe`/`.msi`.
2. **Windows Firewall** will prompt on first run because the server binds `0.0.0.0:8080`.
   Allow it on private networks only.
3. **Auth.** Still none. On a desktop on a shop LAN this is more exposed than on a phone.

## How the code is split

```
composeApp/src/
├── commonMain/   Ktor routes, Room entities/DAO/repository, the whole Compose UI
├── androidMain/  MainActivity, foreground service, Application, Context-based DB open
└── desktopMain/  fun main(), DesktopServerController, LOCALAPPDATA-based DB open
```

`ServerController` (commonMain) is the seam: Android routes start/stop through the
foreground service, desktop calls the engine directly. The UI only sees the interface.

## Decisions made during the port

- **Desktop target is `jvm("desktop")`, not Kotlin/Native `mingwX64`.** Room has no
  stable Windows-native support and CIO on native is limited. JVM keeps one codebase
  and all the existing JVM libraries; `jpackage` bundles a JRE so the Windows machine
  needs no Java installed.
- **Compose Multiplatform 1.8.2**, which is the stable release built for Kotlin 2.1.20.
  1.9.x needs Kotlin 2.2 — do not bump one without the other.
- **Compose imports stay `androidx.compose.*`** — the earlier note in this file about
  moving to `org.jetbrains.compose.*` was wrong for current CMP. Only `compose.desktop`
  and resources use the JetBrains namespace. The screen body ported untouched.
- `android.util.Log` -> `io.ktor.util.logging.KtorSimpleLogger` (multiplatform).
- `SimpleDateFormat`/`System.currentTimeMillis()` -> `kotlinx-datetime` `Clock`.
- `SQLiteConstraintException` -> `androidx.sqlite.SQLiteException`.
- `Dispatchers.IO` is not in common code, so `ioDispatcher` is `expect`/`actual`.
  Both actuals are identical JVM code — the indirection exists only for the compiler.
- Room KMP needs `@ConstructedBy` + an `expect object` constructor; KSP generates the
  actual per target (`kspAndroid`, `kspDesktop`).

## Open questions, still not decided

1. GitHub Actions to build the APK and the `.exe` on every tag — would remove the
   "must be on Windows" bottleneck for releases.
2. Auth before this leaves a LAN (a static bearer token would do).

## Environment

Gradle 8.13 · AGP 8.8.2 · Kotlin 2.1.20 · CMP 1.8.2 · Ktor 3.1.3 · Room 2.7.2
KSP 2.1.20-2.0.1 · compileSdk 35 · minSdk 26 · JDK 17
