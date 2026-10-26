# AGENTS.md

Engineering notes for **healthconnect-sync-bridge**: an Android app
("Wanderer Sync") that reads GPS workouts from Android Health Connect,
converts them to GPX and uploads them to a self-hosted Wanderer instance.

## Source of truth
- `docs/design.md` describes the architecture, the two APIs being bridged,
  the GPX format and the sync/dedup rules. Read it before writing code and
  keep it current when behaviour changes.
- `README.md` is the user-facing guide: clone → debug APK on a phone.
- User instructions in chat override both unless they conflict with safety
  constraints.
- Any API fact (class name, permission constant, Wanderer endpoint) must be
  verified against the current SDK / official docs before being relied on.
  When the docs disagree with `docs/design.md`, follow the verified source,
  update the doc, and call the discrepancy out explicitly in the report.

## CRITICAL: data sanitization (public repo, private server, personal data)
This repository is public. The server it talks to and the data it moves are
personal. Never put in any file, commit, comment, test, fixture, screenshot,
log sample or example:
- The real Wanderer hostname / URL / IP, usernames, passwords, session
  cookies, tokens, trail ids from the real instance.
- Real Health Connect record ids, real GPS coordinates, real workout
  times/places, device identifiers, account names.
- Real `local.properties`, keystores, `google-services.json` (all gitignored —
  keep them so).
Use placeholders only: `https://wanderer.example.com`, user `alice`,
synthetic coordinates (e.g. around `0.0, 0.0` or a clearly fake grid),
UUIDs generated for the test. Before every commit, grep the diff for the real
host, for `[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+`, and for credential-looking
strings. Never log the password or cookie value at any log level.

## Stack & conventions
- Kotlin, Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`.
- `minSdk = 34`, `targetSdk` = current stable. Pin AGP, Kotlin, Compose BOM
  and `androidx.health.connect:connect-client` (newest **stable**) only after
  checking the published version, never from memory.
- Jetpack Compose + Material 3, single screen, no XML layouts, no navigation.
- Diagrams in docs use Mermaid fenced blocks, not ASCII art.
- `androidx.work:work-runtime-ktx` for scheduling,
  `androidx.datastore:datastore-preferences` for settings and sync state,
  `androidx.security:security-crypto` for the password and session cookie,
  OkHttp for HTTP. No Retrofit, no Ktor, no DI framework, no multi-module.
- GPX 1.1 is written by hand (`StringBuilder` / `XmlSerializer`); no GPX lib.
- Tests: JUnit on the JVM; OkHttp `MockWebServer` for the Wanderer client;
  GPX output validated against the bundled GPX 1.1 XSD (test resource).
- Package `ru.vehq.hcsync` (reverse of `hcsync.vehq.ru`), flat layout (see `docs/design.md`).
  `SyncEngine` depends only on small interfaces so it runs in plain JUnit.
- Non-obvious Android boilerplate (manifest entries, permission contracts,
  activity-aliases, WorkManager wiring) gets a one-line comment saying *why*
  it exists, and a pointer to the official sample it was copied from.
- Network calls and Health Connect reads only on `Dispatchers.IO`.
- `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` must be green
  before every commit.

## Commands
- Build debug APK: `./gradlew assembleDebug`
- Install on a connected phone: `./gradlew installDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Worker logs: `adb logcat -s SyncWorker`
- Debug signing only; no release keystore in this repo.

## Critical rules
- User directives are absolute: if the user says `DO NOT <action>`, do not
  perform that action without explicit permission.
- Never edit credential/config files or fabricate/overwrite credentials unless
  the user explicitly asks.
- Preserve user data and configuration; ask before changing if in doubt. Do
  not undo user choices in favor of your own approach without discussing first.
- Keep links absolute unless requested otherwise; prefer minimal, targeted
  patches.
- Every text file must end with a final newline (`\n`).

## Commit messages
- Conventional Commits: `<type>(<scope>): <description>` (see
  https://www.conventionalcommits.org/en/v1.0.0/#summary). Scopes: `app`,
  `hc`, `gpx`, `wanderer`, `sync`, `ui`, `build`, `docs`.
