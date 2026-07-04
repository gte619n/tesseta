# health-fitness-android

Native Android (Kotlin 2.0, Jetpack Compose, Material 3), multi-module.
Phone app + standalone Wear OS app, sharing `core-*` modules.

## Modules
- `app` — phone application (`com.gte619n.healthfitness.mobile`)
- `wear` — Wear OS application (`com.gte619n.healthfitness.wear`)
- `core-data` — Room, DataStore, Retrofit
- `core-domain` — use cases + models, pure Kotlin
- `core-ui` — Compose theme + shared composables (phone)
- `core-chat` — shared AI-chat client (consumed by feature modules)
- `feature-workouts`, `feature-medical`, `feature-goals`, `feature-nutrition`,
  `feature-settings`, `feature-blood`, `feature-body-composition` — feature modules

## Build
```bash
cp local.properties.example local.properties   # edit sdk.dir
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

Phone and Wear share `applicationId` (`com.gte619n.healthfitness`) for pairing,
but use different namespaces.
