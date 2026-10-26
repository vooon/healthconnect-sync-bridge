# Third-party notices

This project is licensed under the GNU Affero General Public License v3.0
(see `LICENSE`). Parts of it are derived from other AGPL-3.0 projects. Each
derived source file carries a header naming its origin; this file is the
index.

## OpenVitals

- Project: https://github.com/OpenVitals-MTU/android-app
  (mirror of https://codeberg.org/OpenVitals/mobile-app)
- Copyright: the OpenVitals authors
- License: GNU Affero General Public License v3.0 or later (`LICENSE`)
- Snapshot used: commit `d305e83abcfaeb73db1d1aa6fdfaed219c89ba6e` (2026-10-23)

Files in this repository derived from OpenVitals (origin path → local path):

| OpenVitals file | Local file |
|---|---|
| `app/src/main/kotlin/tech/mmarca/openvitals/healthconnect/HealthConnectMappers.kt` (route mapping) | `app/src/main/java/ru/vehq/hcsync/hc/HealthConnectSource.kt` |

## Google health-samples

- Project: https://github.com/android/health-samples
- Copyright: The Android Open Source Project
- License: Apache License 2.0
- Snapshot used: commit `7183ab9b17330823a33e8216f81e67e2eeea2006` (2026-09-16)

| health-samples file | Local file |
|---|---|
| `health-connect/HealthConnectSample/app/src/main/AndroidManifest.xml` (Health Connect intent-filters and `<queries>`) | `app/src/main/AndroidManifest.xml` |

## Garmin TrackPointExtension schema

- File: `app/src/test/resources/TrackPointExtensionv1.xsd`, test resource only
  (not shipped in the APK)
- Source: https://www8.garmin.com/xmlschemas/TrackPointExtensionv1.xsd
- Copyright: Garmin Ltd. or its subsidiaries; redistributed unmodified for
  schema validation in tests. The GPX 1.1 schema (`gpx.xsd`) is likewise
  TopoGrafix's, from https://www.topografix.com/GPX/1/1/gpx.xsd.
