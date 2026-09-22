# Ktor Android Server

An Android app that runs an embedded **Ktor (CIO)** HTTP server on the device, inside a
foreground service, backed by **SQLite via Room**, with a Compose UI that shows the live
state of the table.

Verified on a Samsung SM-X210 running Android 15.

## Endpoints

| Method   | Path          | Result |
|----------|---------------|--------|
| `GET`    | `/health`     | `200` — status, uptime, user count, server time, storage |
| `GET`    | `/users`      | `200` — `{ count, users[] }` |
| `GET`    | `/users/{id}` | `200` user, or `404` |
| `POST`   | `/users`      | `201` created; `400` invalid; `409` duplicate email (unique index) |
| `DELETE` | `/users/{id}` | `204`, or `404` |

`POST /users` body:

```json
{ "name": "Grace Hopper", "email": "grace@example.com" }
```

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app and tap **Start server**. The card shows the URL, e.g. `http://192.168.44.1:8080`.

## Calling it

From another device on the same Wi-Fi, use the URL shown in the app. From your laptop over
USB, forward the port instead — this works even without shared Wi-Fi:

```bash
adb forward tcp:9090 tcp:8080
curl http://localhost:9090/health
curl http://localhost:9090/users
curl -X POST http://localhost:9090/users -H "Content-Type: application/json" -d '{"name":"Grace Hopper","email":"grace@example.com"}'
curl -X DELETE http://localhost:9090/users/1
```

## Layout

```
app/src/main/java/com/example/ktorserver/
├── MainActivity.kt                  Compose UI: status, start/stop, live user list
├── KtorServerApp.kt                 Application: owns the database + repository
├── server/KtorServer.kt             embeddedServer(CIO) + all routes
├── service/KtorServerService.kt     foreground service that owns the engine
├── data/Models.kt                   @Entity / @Serializable types
├── data/AppDatabase.kt              Room database + one-time seed callback
├── data/UserDao.kt                  queries, incl. Flow<List<User>> for the UI
├── data/UserRepository.kt           thin layer over the DAO
└── util/NetworkUtils.kt             local IPv4 lookup for the displayed URL
```

## Storage

Data lives in SQLite at `/data/data/com.example.ktorserver/databases/ktor-server.db`
(the path is shown in the app). Room generates this schema:

```sql
CREATE TABLE IF NOT EXISTS `users` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `name` TEXT NOT NULL,
  `email` TEXT NOT NULL,
  `createdAt` INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`);
```

The DAO exposes `Flow<List<User>>`, so a `POST` from curl repaints the on-screen list with
no refresh. Writes survive force-stop, reboot and app upgrade — verified by POSTing a row,
running `adb shell am force-stop`, and reading it back.

Inspect the file directly:

```bash
adb shell run-as com.example.ktorserver ls -l databases/
adb exec-out run-as com.example.ktorserver cat databases/ktor-server.db > local.db
```

The unique index on `email` is the real duplicate guard — the pre-check in the route is a
courtesy, and a lost race falls back to `409` via `SQLiteConstraintException`.

Schemas are exported to `app/schemas/` so migrations can be diffed in review. Bumping
`@Database(version = ...)` without writing a migration will crash at open; that is intentional
(no `fallbackToDestructiveMigration()`).

## Android-specific notes

- **CIO engine, not Netty.** Netty/Jetty rely on JDK APIs Android doesn't ship.
- **Logback crashes on Android.** Ktor logs via SLF4J 2.x, so `slf4j-simple` is bound instead;
  its output lands in logcat under `System.err`.
- **Foreground service.** A bare service or an Activity gets killed when the app backgrounds.
  `dataSync` type + `FOREGROUND_SERVICE_DATA_SYNC` permission is required from API 34.
  Note Android 15 caps `dataSync` services at ~6 hours per day.
- **`POST_NOTIFICATIONS`** is requested at launch; without it the ongoing notification is
  silent but the service still runs.
- **Ports below 1024 need root** — 8080 is the default here (`KtorServer.DEFAULT_PORT`).
- **`host = "0.0.0.0"`** makes it LAN-reachable; use `127.0.0.1` for on-device only.
- **Doze / battery optimisation** can suspend sockets with the screen off. The
  *Battery settings* button opens the exemption screen.
- **Packaging excludes** in `app/build.gradle.kts` resolve duplicate `META-INF` entries from
  the Ktor jars.
- **R8**: `proguard-rules.pro` keeps Ktor and kotlinx.serialization reflection targets so the
  release build works too.

## Versions

Gradle 8.13 · AGP 8.8.2 · Kotlin 2.1.20 · Ktor 3.1.3 · Room 2.7.2 · KSP 2.1.20-2.0.1 · compileSdk 35 · minSdk 26 (core library desugaring on)
# ktorServer
