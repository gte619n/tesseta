# CLAUDE.md — android

- Modular Compose architecture. **All UI in Compose**, no XML layouts beyond
  the obligatory theme XML and manifest.
- **DI**: Hilt — fully wired (`@Module @InstallIn(SingletonComponent)` providers
  across `app` and `core-data`, `@HiltViewModel` on ~30 screens).
- **Repositories are concrete `@Inject` classes in `core-data`** — no
  `core-domain` repository interface, no `@Binds` indirection (single
  implementation each). New repos follow the same pattern; tests mock the
  concrete class directly (MockK handles final Kotlin classes). Modules keep
  only `@Provides` for things Hilt can't construct itself (Retrofit services,
  `@Provides`-with-defaults). `core-domain` holds the domain models/DTOs the
  repos return, not their interfaces.
- **Backend is the system of record; the app is offline-first against it.**
  The backend owns Firestore and is the authority. On-device, `core-data` runs
  an offline-first mirror (see `docs/plans/IMPL-AND-20-offline-first-sync.md`):
  - **Reads** go through a local mirror backed by **Room (SQLCipher-encrypted)**
    via `MirrorRepositorySupport` / `MirrorStore`, kept fresh by `SyncEngine`
    (delta pulls, WorkManager workers, FCM push-to-pull).
  - **Writes** are journaled to an **outbox** and replayed to the backend with
    last-write-wins conflict resolution + tombstones.
  - **DataStore** still holds small key/value state — the ID-token cache
    (`IdTokenCache`) and unit prefs.
  - Not every repository is migrated: the main goals and nutrition repos are on
    the mirror, but some capture/search/reference repos (e.g. `FoodRepository`,
    `NutritionCaptureRepository`, `DrugRepository`, `EquipmentRepository`,
    `ChatRepository`) still read Retrofit-direct. Treat the mirror as the target
    pattern for new work.
- **Offline-first reads (required — [ADR-0018](../docs/decisions/ADR-0018-offline-first-read-pattern.md)).**
  Every user-facing read is **cache-first**. This is a hard rule for read
  surfaces, not a per-screen call:
  - ViewModels **never start in `Loading` when cached data exists** — seed
    instantly from Room/DataStore, then revalidate in the background and keep the
    cached value on failure. `Loading` is acceptable **only** pre-first-sign-in
    (nothing cached yet).
  - Repositories **serve the mirror/cache first and revalidate**; a network read
    is the *revalidation*, never the gate for first paint.
  - Reactive screens use `.stateIn` off the Room mirror with a **30 s TTL-guarded**
    refresh, so re-entry within the window reuses the mirror. Never show a spinner
    on re-entry when last-known data exists.
  - Reference implementations to copy: `DashboardBloodViewModel` (reactive +
    TTL-guarded refresh) and `MedicationRepository.get()` (cache-first →
    revalidate → keep-cache-on-failure); `ProfileRepository.cached()` +
    `ProfileViewModel` show the seed-then-revalidate variant.
  - **Reject in review:** a `loading = true` default paired with a one-shot
    `init` network fetch; a repository read that hits the network before serving
    cache; a spinner on re-entry.
- **Network**: Retrofit + OkHttp + Moshi; 401 → silent refresh via
  `TokenAuthenticator` (a benign refresh replay is recovered server-side by the
  successor chain, [ADR-0019](../docs/decisions/ADR-0019-successor-chain-refresh-token-rotation.md),
  so the app is not logged out on flaky connectivity). SSE in `core-data/net/Sse.kt`.
- All async work via Coroutines + Flow.
- **On-device Health Connect is not integrated** — health data arrives
  backend-side via the Google Health API. If/when device-side access is added,
  introduce a dedicated `core-health` module and confine all
  `androidx.health.connect:*` access to it; app and feature modules must not
  import it directly. (The empty `core-health` placeholder was removed until the
  feature lands.)
- **Wear module shares `core-domain` and `core-ui` (when relevant) but never
  depends on `app`** — pairing is by `applicationId`, not module dependency.
- Phone and wear share `applicationId` (required for pairing); namespaces differ.
- JVM toolchain 21. Min SDK 29 (phone), 30 (wear). Target SDK 35 (phone), 34 (wear).

## Build & validation

Run all Gradle commands from `android/`.

- **Toolchain**: JDK 21 (e.g. `~/.sdkman/candidates/java/current`). Point Gradle
  at the SDK via `local.properties` → `sdk.dir=...` (this file is **gitignored**,
  so a fresh worktree has none — create it) or `ANDROID_HOME`.
- **`WEB_OAUTH_CLIENT_ID` is mandatory** — the build *refuses* to produce an APK
  without a real Google OAuth web client ID (sign-in, and thus the whole app,
  needs it). Supply it one of three ways (see `resolveWebOauthClientId` in
  `app/build.gradle.kts`): `-PwebOauthClientId=<id>.apps.googleusercontent.com`,
  the `WEB_OAUTH_CLIENT_ID` env var, or let Gradle fetch it from GCP Secret
  Manager (`gcloud auth login` first).

### Commands
```bash
# CI gate (mirrors .github/workflows/android-ci.yml): all-module unit tests +
# the R8-minified release build.
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest :app:assembleRelease --no-daemon
# Release APK (R8-minified, arm64-only): app/build/outputs/apk/release/app-release.apk
```

### Compose UI tests (JVM, no device)
Compose UI tests run on the JVM under **Robolectric** via `testDebugUnitTest`, so
they gate in CI without an emulator. `core-ui` is the reference; to add UI tests
to a module: `testImplementation`(`compose-ui-test-junit4`, `robolectric`),
`debugImplementation(compose-ui-test-manifest)`, and set
`android { testOptions { unitTests { isIncludeAndroidResources = true } } }`. Test
class: `@RunWith(RobolectricTestRunner)` + `@Config(sdk = [34])` +
`@GraphicsMode(NATIVE)`, a `createComposeRule()` rule, then `setContent { ... }`
and `onNodeWithText(...)`. See `core-ui/.../StateViewsTest.kt`. The `Hf` design
tokens have composition-local defaults, so simple primitives need no theme wrapper.
Note: CI does **not** run `lintRelease`; it currently reports pre-existing
`MissingPermission` errors in `WorkoutSessionService` (false positives — the
calls are guarded by `canPostNotifications()`). Don't treat those as your
regression.

### Release build is minified — TEST THE RELEASE APK, not just debug
The release build runs **R8 full-mode + `shrinkResources`** and is **arm64-v8a
only**, and ML Kit is **unbundled** (models download via Play Services). These
only affect the release variant, so debug builds + unit tests will **not** catch
their failures. After any dependency change, R8 rule change, or
serialization/reflection change you **must** smoke-test an actual release APK on
a **device/emulator with Google Play Services**:
```bash
./gradlew :app:assembleRelease --no-daemon
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c && adb shell am start -W -n com.gte619n.healthfitness/.mobile.MainActivity
adb logcat -d | grep -iE 'FATAL|Cannot serialize|ClassNotFound|UnsatisfiedLinkError'  # must be empty
```
Then exercise Google sign-in → first sync → a nutrition barcode/label scan.

- **Reflective Moshi + R8**: models are serialized by `KotlinJsonAdapterFactory`
  (reflection), and most are plain data classes **without `@JsonClass`**. R8
  full-mode strips the constructor of any class it only sees instantiated via
  reflection and marks it abstract → Moshi crashes with *"Cannot serialize
  abstract class"* at startup. `app/proguard-rules.pro` keeps the model
  namespaces (`domain.**`, `data.**`, `core.chat.**`) to prevent this — **don't
  delete those keeps, and add any new DTO/wire-model package there.** Diagnose
  new R8 crashes by de-obfuscating the logcat class via
  `app/build/outputs/mapping/release/mapping.txt`.
- **ABI**: release defaults to `arm64-v8a` only. Add `-PincludeArmv7` for 32-bit
  ARM, `-PincludeX86` for Intel-host emulators.

## UI conventions
- **No white form/settings backgrounds.** Settings, profile, and other form
  screens sit directly on the canvas (`Hf.colors.canvas`) — never the white
  `Hf.colors.surface` fill. Wrap grouped content in `HfCard(transparent = true)`
  (border only, no fill) or a plain container. The filled `HfCard` is reserved
  for data/dashboard cards. Don't introduce `.background(Hf.colors.surface)` on a
  form surface.
- **Edge-to-edge insets.** The activity runs edge-to-edge, so every top-level
  screen must apply `windowInsetsPadding(WindowInsets.systemBars)` (or
  `statusBars`/`navigationBars`) to its root container, or use a `Scaffold`.
  Forgetting this puts top bars / back buttons *under* the status bar where taps
  don't register.
- **Every pushed screen needs a back affordance.** Any destination reached by
  `navController.navigate(...)` (including all the parity features behind the
  phone "More" hub) must render a back control wired to `popBackStack()` — a
  fixed header `Row` with `IconButton` + `Icons.AutoMirrored.Outlined.ArrowBack`
  for plain screens (see `ProfileScreen`/`BodyCompositionScreen`), or a
  `TopAppBar` `navigationIcon` for `Scaffold` screens (see `GymsListScreen`).
  Don't rely on the system back gesture alone.
- **Predictive back is enabled** (`android:enableOnBackInvokedCallback="true"` in
  the manifest). Under gesture nav on API 35 this gives the back swipe its peek
  animation; without it the gesture works only on full release and reads as
  "back does nothing." Navigation-Compose handles the `OnBackInvokedCallback`
  path — don't add custom `BackHandler`s that swallow it.
