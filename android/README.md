# health-fitness-android

Native Android (Kotlin 2.0, Jetpack Compose, Material 3), multi-module.
Phone app + standalone Wear OS app, sharing `core-*` modules.

## Modules
- `app` — phone application (`com.gte619n.healthfitness.mobile`)
- `wear` — Wear OS application (`com.gte619n.healthfitness.wear`)
- `core-data` — Room, DataStore, Retrofit
- `core-domain` — use cases + models, pure Kotlin
- `core-ui` — Compose theme + shared composables (phone)
- `core-health` — Health Connect wrapper
- `feature-workouts`, `feature-medical`, `feature-chat` — feature modules

## Build
```bash
cp local.properties.example local.properties   # edit sdk.dir
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

Phone and Wear share `applicationId` (`com.gte619n.healthfitness`) for pairing,
but use different namespaces.

## Debug keystore

The shared debug keystore lives at `android/debug.keystore` and **is
checked in** so every developer and CI runner builds with the same
signing identity. The negation in `android/.gitignore`
(`!debug.keystore`) keeps the file tracked while other `*.keystore`
files remain ignored.

- Alias: `androiddebugkey`
- Store / key password: `android`
- **SHA-1**: `0F:2B:8E:93:9F:97:C6:91:02:C6:FA:82:94:94:A4:2D:26:EF:4E:00`
- SHA-256: `14:DC:79:7B:8C:11:FF:9C:C9:0B:9B:83:3E:2A:AA:15:58:20:93:3F:B2:7A:86:D6:5F:CB:FD:69:3E:C8:61:B7`

The SHA-1 above must stay registered against the Google OAuth web
client for Credential Manager to issue ID tokens on `dev`/`debug`
variants. Re-fetch with:

```bash
keytool -list -v -keystore android/debug.keystore \
  -storepass android -alias androiddebugkey
```

If the keystore is ever regenerated, register the new SHA-1 in the
Google Cloud OAuth consent screen before merging.

## Snapshot tests (Paparazzi)

UI snapshot coverage runs through
[`app.cash.paparazzi`](https://github.com/cashapp/paparazzi). The
plugin is applied to `:app`, `:core-ui`, and the five feature
modules (`:feature-medical`, `:feature-blood`,
`:feature-body-composition`, `:feature-workouts`,
`:feature-settings`). Snapshots live next to the tests under
`<module>/src/test/snapshots/images/` and are committed alongside
the test source.

Workflow:

```bash
# Generate / refresh baselines after a UI change (run from android/).
./gradlew recordPaparazzi

# Verify the current code matches the committed baselines
# (CI runs this; failures emit a diff under build/reports/paparazzi).
./gradlew verifyPaparazzi
```

Scope a record / verify to a single module to iterate faster:

```bash
./gradlew :core-ui:recordPaparazzi
./gradlew :feature-blood:verifyPaparazzi
```

When a snapshot intentionally changes, re-record, review the PNG
diff, and commit the updated baseline in the same change that
modified the UI. Drive-by re-recordings should not happen without
inspecting the diff.
