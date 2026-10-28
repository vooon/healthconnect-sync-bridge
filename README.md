# Wanderer Sync (healthconnect-sync-bridge)

[![CI](https://github.com/vooon/healthconnect-sync-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/vooon/healthconnect-sync-bridge/actions/workflows/ci.yml)

An Android app that copies GPS-tracked workouts from **Health Connect** (where
Samsung Health puts them, e.g. from a Galaxy Fit3) to a self-hosted
[**Wanderer**](https://wanderer.to) instance as GPX trails. It runs in the
background every hour (configurable), never uploads the same workout twice,
and talks to nothing but your own server.

Design notes live in [`docs/design.md`](docs/design.md).

## What you need

- A phone on **Android 14 or newer** (Health Connect is part of the OS there).
- Samsung Health (or any app) writing exercise sessions **with routes** to
  Health Connect. A Galaxy Fit3 only records a route when the phone is along,
  since the band has no GPS.
- A Wanderer account and an **API token**: in Wanderer open Settings →
  Account → API tokens → create one and copy it (it starts with
  `wanderer_key`).
- On the computer: git, a Java runtime (any JRE 17+ to launch Gradle; the
  build downloads its own JDK), and the Android SDK. The SDK comes with
  Android Studio, or as the command-line tools:

  ```sh
  # Android Studio: the setup wizard installs the SDK to ~/Android/Sdk.
  # Command-line alternative:
  mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
  curl -LO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  unzip -q commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
  latest/bin/sdkmanager --licenses
  latest/bin/sdkmanager "platform-tools" "platforms;android-37" "build-tools;36.0.0"
  ```

## Build and install

```sh
git clone https://github.com/vooon/healthconnect-sync-bridge.git
cd healthconnect-sync-bridge
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug                                 # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                             # unit tests (no device needed)
```

To install on the phone:

1. Enable developer options: Settings → About phone → Software information
   → tap **Build number** seven times.
2. Settings → Developer options → turn on **USB debugging**.
3. Connect the phone by USB, accept the "Allow USB debugging?" prompt on the
   phone, then:

   ```sh
   ~/Android/Sdk/platform-tools/adb devices     # must list the phone as "device"
   ./gradlew installDebug
   ```

The app appears as **Wanderer Sync**. This is a debug build (package
`ru.vehq.hcsync.debug`); signed release APKs are on the GitHub Releases page. (Android Studio users: open the folder, let it sync, and
press Run.)

## First run

1. **Grant permissions** → accept in the Health Connect dialog.
   With "heart rate and cadence" on (default), the dialog also asks for heart
   rate and steps.
2. **Open Health Connect** → App permissions → Wanderer Sync →
   *Additional access* → **Exercise routes → Always allow**. No app can ask
   for this one; the screen shows ✔ next to "Read exercise routes" once it is
   on. Without it every workout comes back as "consent needed" and Sync now
   asks you per workout instead.
3. Enter the **server URL** (e.g. `https://wanderer.example.com`) and the
   **API token**, then **Test connection**. It replies with the token name.
4. **Probe** lists the last 30 days of sessions with their route status
   (`Data(N pts)`, `NoData`, `ConsentRequired`). `NoData` walks have no GPS
   track and are skipped for good.
5. **Sync now** uploads every routed workout and prints the Wanderer trail
   ids. **Re-sync** does the same but ignores what the phone remembers and
   asks the server about each workout; use it after deleting trails on
   Wanderer to get them back. **Save & schedule** (also done by Test/Sync) starts the background
   job at the chosen interval.

Background runs happen only on Wi-Fi by default; turn "only on Wi-Fi" off to
allow mobile data, and "only while charging" on to wait for the charger.

Android defers the background jobs of apps it considers rarely used (App
Standby buckets: *working set* ~2 h, *frequent* ~8 h, *rare* once a day), so
an hourly sync may not run for most of a day if you seldom open the app. The
screen shows the current bucket next to "Battery optimized". **Allow
unrestricted battery use** opens the system dialog that exempts the app;
this is optional, and Sync now always works without it.
"Include workouts older than 30 days" asks for Health Connect's history
permission on the next **Grant permissions** and widens the first sync.

## Watching it work

```sh
adb logcat -s SyncWorker WandererSync
```

`SyncWorker` is the background job, `WandererSync` the screen. The screen
also shows the last run time and result (`uploaded N, no route N, consent
needed N, failed: …`).

The Sync section keeps running totals: trails uploaded, sessions that had no
route, and how many sessions the last run saw. Background runs need the
"Read in background" permission (shown on the screen) and a network that
matches the toggles. WorkManager decides the exact minute;
expect drift of a few minutes, and up to the interval after a reboot.

If background runs seem to stop, check the job and the standby bucket:

```sh
adb shell dumpsys jobscheduler | grep -A 40 'SyncWorker#@androidx.work.systemjobscheduler@ru.vehq.hcsync'
adb shell am get-standby-bucket ru.vehq.hcsync   # 10 active … 40 rare, 5 exempted
adb shell dumpsys deviceidle whitelist | grep hcsync
```

## Known limitations

- Heart rate and cadence go into the GPX as Garmin `TrackPointExtension`
  fields (toggle on the screen, needs the heart-rate and steps permissions).
  Wanderer keeps them in the stored file but does not display them; tools
  reading the downloaded GPX (Strava, Garmin, GPX viewers) do. Speed, power
  and SpO₂ are not exported.
- One `<trk>`/`<trkseg>` per workout: pauses are not split into segments.
- Wanderer's own duplicate check is bypassed (`ignoreDuplicates`), because a
  daily walk on the same loop would otherwise be rejected. Deduplication is
  by Health Connect record id: the phone remembers what it uploaded and, for
  anything it does not remember, asks Wanderer whether a trail with that id
  in its description already exists. Trails whose description was edited by
  hand lose that marker.
- Trail category and date are whatever Wanderer infers from the GPX; nothing
  is patched afterwards.
- `androidx.security:security-crypto`, used for the token, is deprecated by
  Google but still functional.
- A sync started from the screen is cancelled if the app is killed mid-run;
  everything uploaded before that is already recorded.

## Releases

Every push to `main` runs CI (build, unit tests, lint) and keeps the debug APK
as a workflow artifact. A release is made by tagging:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

The Release workflow builds a **signed** release APK, names it
`wanderer-sync-v0.1.0.apk`, and attaches it to a GitHub release with
generated notes. The version name is the tag; the version code is derived
from it (`v1.2.3` → `10203`), so every release upgrades the previous one.

Signing needs a keystore once, kept only in GitHub secrets:

```sh
keytool -genkeypair -v -keystore release.jks -alias wanderer-sync \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks   # → secret ANDROID_KEYSTORE_BASE64
```

Add three repository secrets: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`wanderer-sync`). A fourth,
`ANDROID_KEY_PASSWORD`, is only needed if the key has its own password (a
PKCS12 keystore, keytool's default, has none; the store password is used).
A tagged build without these fails at once instead of producing an unsigned APK. Keep `release.jks` somewhere safe and never commit
it; losing it means users must uninstall before the next update.

Release builds are shrunk with R8 (about 3 MiB instead of 30); the matching
`…-mapping.txt.gz` on the release page decodes obfuscated stack traces
(`retrace` from the Android build-tools). Debug builds are not shrunk.

Debug builds use the package id `ru.vehq.hcsync.debug`, so a development
install and the release can live on the phone side by side (each with its own
Health Connect permissions and settings). Dependabot opens weekly PRs for
Gradle dependencies and GitHub Actions.

## Development

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Engineering rules for contributors and agents are in [`AGENTS.md`](AGENTS.md);
third-party code attribution in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Licensed under the
AGPL-3.0 (see `LICENSE`).
