# Building McPodface

## Requirements

- **Android Studio** (recent). The build uses its bundled JDK (`jbr`) and the Android SDK.
- **Android SDK platform 37** and build tools, installed through the SDK Manager.
- A phone or emulator on **Android 13 (API 33)** or newer.

The Gradle wrapper downloads Gradle 9.8 itself. All library versions are pinned in
`gradle/libs.versions.toml`.

## 1. API keys

Discovery uses free catalogue APIs. Feed refreshes and episode data come straight from each
podcast's RSS feed, so they use no API calls at all.

| Key | Where to get it | Needed? |
|---|---|---|
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` | <https://api.podcastindex.org> (free) | Yes for discovery. Without it, search falls back to iTunes only |
| `LISTENNOTES_KEY` | <https://www.listennotes.com/api/> (free plan) | Optional. Used only when the other sources find nothing |

iTunes Search needs no key.

## 2. `local.properties`

Create `local.properties` in the project root. It is git-ignored, so **never commit it**:

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk

PODCASTINDEX_KEY=...
PODCASTINDEX_SECRET=...
LISTENNOTES_KEY=...

# Optional: only for release builds
RELEASE_STORE_FILE=C:/path/to/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Use forward slashes in paths. The keys reach the app through `BuildConfig`. Missing keys build
fine; the matching source is simply skipped.

## 3. Build and install

From Git Bash on Windows (on macOS/Linux, point `JAVA_HOME` at Android Studio's `jbr` instead):

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew :app:compileDebugKotlin    # quick compile check
./gradlew :app:installDebug          # debug build to every connected device
./gradlew :app:installFast           # non-debuggable build for judging smoothness (see below)
./gradlew :app:assembleRelease       # signed release APK (needs the RELEASE_* properties)
```

To target one device when several are connected, set `ANDROID_SERIAL`, e.g.
`ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug`.

### Build types

| Type | Application ID | Notes |
|---|---|---|
| `debug` | `com.steve.mcpodface.debug` | Debuggable, unoptimised. Use it for development and inspection |
| `fast` | `com.steve.mcpodface.debug` | Release settings, not debuggable (so ART compiles it fully), signed with the debug key. Installs over the debug app and keeps its data. **Use it to judge smoothness**: Compose in a debug build is much slower |
| `release` | `com.steve.mcpodface` | Signed with your release keystore. (R8 minification is currently off.) Installs alongside the debug app |

Back up the release keystore and its passwords somewhere off the machine. Without them you can't
install an update over an existing release install.

## 4. Tests

```sh
./gradlew :app:testDebugUnitTest     # JVM + Robolectric (about 550 tests)
./gradlew :app:lintDebug
```

- Robolectric emulates SDK 36 (`app/src/test/resources/robolectric.properties`), because it doesn't
  support 37 yet.
- Feed fixtures live in `app/src/test/resources/feeds/`.
- Network code is tested against OkHttp's MockWebServer.
- Room DAOs are tested against an in-memory database.

To measure jank on a device, install the `fast` build, use the app, then run:

```sh
adb shell dumpsys gfxinfo com.steve.mcpodface.debug
```

## 5. Emulator

Any Pixel image on API 33+ works. Downloads land in
`/sdcard/Android/data/com.steve.mcpodface.debug/files/Podcasts/<podcastId>/`.

To force a background refresh:

```sh
adb shell cmd jobscheduler run -f com.steve.mcpodface.debug <jobId>
```

Find the job ID with `adb shell dumpsys jobscheduler | grep mcpodface`.

## Database migrations

The Room schema is exported to `app/schemas/`. Any entity change needs a version bump, a real
`Migration`, and the new schema JSON committed. Destructive migrations are never used, because
your listening history lives only on the device.
