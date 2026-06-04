# CLAUDE.md — android

- Modular Compose architecture. **All UI in Compose**, no XML layouts beyond
  the obligatory theme XML and manifest.
- **DI**: Hilt — fully wired (`@Module @InstallIn(SingletonComponent)` providers
  across `app` and `core-data`, `@HiltViewModel` on ~30 screens).
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
  - Not every repository is migrated yet: `feature-goals` and `feature-nutrition`
    still read directly over Retrofit. Treat the mirror as the target pattern.
- **Network**: Retrofit + OkHttp + Moshi; 401 → silent refresh via
  `TokenAuthenticator`. SSE in `core-data/net/Sse.kt`.
- All async work via Coroutines + Flow.
- **Health Connect access goes through `core-health` only.** App and feature
  modules never import `androidx.health.connect:*` directly.
- **Wear module shares `core-domain` and `core-ui` (when relevant) but never
  depends on `app`** — pairing is by `applicationId`, not module dependency.
- Phone and wear share `applicationId` (required for pairing); namespaces differ.
- JVM toolchain 21. Min SDK 29 (phone), 30 (wear). Target SDK 35.

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
