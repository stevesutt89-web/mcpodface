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
install an update over an existing release install. See **Release** below.

## 4. Tests

```sh
./gradlew :app:testDebugUnitTest     # JVM + Robolectric (about 1,000 tests)
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

## 6. Release (a signed APK to install like a normal app)

Do steps 1 and 2 once. Keep the keystore and passwords on your own machine: never commit them
or paste them into a cloud session.

**1. Create the release keystore.** Skip this if you already have one.

```sh
"/c/Program Files/Android/Android Studio/jbr/bin/keytool" -genkeypair -v \
  -keystore ~/mcpodface-release.jks -alias mcpodface \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Back up the `.jks` file and both passwords** (password manager, cloud drive). Every future
update must be signed with this same key, on both phones. If it's lost, the only way to update is
to uninstall, which deletes the app's data.

**2. Add it to `local.properties`** (git-ignored):

```properties
RELEASE_STORE_FILE=C:/Users/you/mcpodface-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=mcpodface
RELEASE_KEY_PASSWORD=...
```

**3. Set the version** in `app/build.gradle.kts` (`defaultConfig`), and commit it:

```kotlin
versionCode = 1          // a whole number; add 1 for every release after this
versionName = "1.0.0"    // what Settings → About shows
```

Android only installs an update over an existing install if its `versionCode` is higher.

**4. Check, then build:**

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Confirm it's signed with your key:

```sh
"$ANDROID_HOME/build-tools/<version>/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

**5. Install it.** Either `./gradlew :app:installRelease` with a phone connected, or copy the APK
to the phone and tap it. The first time, Android asks you to allow installs from that app (Files,
Chrome), and Play Protect may offer to scan it; both are normal for apps from outside the Play
Store. Install on both phones from the same APK.

**6. Move your data over.** The release app (`com.steve.mcpodface`) is separate from the debug app
(`com.steve.mcpodface.debug`) and starts empty. In the debug app use Settings → Backup and
transfer → Export all data, then Import all data in the release app. After that the debug app
can be uninstalled.

**7. Tag the release** so you can find the code later:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

For the next release: bump `versionCode` and `versionName`, then repeat steps 4, 5 and 7.
Updates install over the existing app and keep its data.

## Releases on GitHub (automatic)

Pushing a tag like `v1.0.0` runs `.github/workflows/release.yml`: it runs the unit tests, builds the
signed APK and publishes it as a GitHub Release anyone can download. Running the workflow by hand
(Actions tab) builds the APK as a downloadable artifact without publishing.

One-time setup, under the repo's Settings -> Secrets and variables -> Actions:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore as base64: `base64 -w0 ~/mcpodface-release.jks` |
| `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | the same values as in `local.properties` |
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET`, `LISTENNOTES_KEY` | optional: search keys, as in `local.properties` |

Bump `versionCode` and `versionName` before tagging; the APK is named from `versionName`.

## Cloud sessions (Claude Code on the web)

The container has no Android SDK. With `dl.google.com` allowed in the environment's network
settings, install one and point `local.properties` at it:

```sh
mkdir -p /root/android-sdk/cmdline-tools && cd /root/android-sdk
curl -sSo cl.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q cl.zip -d cmdline-tools && mv cmdline-tools/cmdline-tools cmdline-tools/latest
yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk \
  "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=/root/android-sdk" > <project>/local.properties
```

Then `bash ./gradlew …` works (the wrapper isn't executable in the checkout). A first
`testDebugUnitTest` can fail one class with HTTP 429 while Robolectric downloads its Android jar
from Maven Central; rerun it.

There is no KVM, so an emulator only runs with `-accel off`: a 30–60 minute boot, the system
server needs `adb root` then `setprop ro.hw_timeout_multiplier 40` early in boot or the watchdog
kills it, screenshots come back blank, and injected swipes don't register. It can still drive the
UI through `uiautomator dump` and taps, which is enough to check navigation and screen contents.

## Database migrations

The Room schema is exported to `app/schemas/`. Any entity change needs a version bump, a real
`Migration`, and the new schema JSON committed. Destructive migrations are never used, because
your listening history lives only on the device.
