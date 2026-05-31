# IMPL-AND-* — Implementation follow-up questions & decisions

This document records the decisions, assumptions, and open questions that came
up while implementing the Android parity specs (IMPL-AND-00 through -06, plus
the dashboard live-data wiring of IMPL-AND-01). It is the "ask follow-up
questions once implementation is done" artifact requested for this task.

Date: 2026-05-30.

---

## Decisions taken (post-review, 2026-05-30)

The questions below were reviewed with the owner. Resolutions:

- **Q0 — Skip** the IMPL-AND-00 structural refactor (no `core-network` module,
  keep string routes, keep `AuthCoordinator`). Current architecture stands.
- **Q1 — Add** the `TokenAuthenticator` (silent refresh + one retry on 401).
- **Q2 — Keep** the single overridable `BACKEND_BASE_URL` (no dev/staging/prod
  flavors).
- **Q3 — Full dashboard rewire**: flip the phone + foldable screens onto the
  live `DashboardViewModel`, fixtures → `DashboardFallbacks` + `DashboardFlags`.
- **Q4 — ISO-8601 confirmed (by Spring Boot default).** Backend `application.yml`
  has **no `spring.jackson` block** at all, so Jackson is configured by Spring
  Boot's `Jackson2ObjectMapperBuilder`, which **disables
  `WRITE_DATES_AS_TIMESTAMPS` by default** → `Instant` serializes as an ISO-8601
  string and `LocalDate` as `yyyy-MM-dd`. The existing `LocalDateAdapter` /
  `InstantAdapter` parse exactly that. No change. (If a future change adds
  `spring.jackson.serialization.write-dates-as-timestamps: true`, the
  `InstantAdapter` would need a numeric `@FromJson` — noted as a tripwire.)
- **Q5 — Window casing confirmed UPPERCASE; Android already correct.** Read the
  source: `TimeWindow` is a **plain enum** (`MORNING…BEDTIME`, no `@JsonValue` /
  `@JsonCreator`), so Jackson and Spring bind it by constant name = **uppercase**
  on the wire. `AdherenceController` confirms it: `POST .../adherence` takes a
  `LogDoseRequest` record whose `window` field is a `TimeWindow` (→ uppercase
  JSON), and `DELETE .../adherence/{date}/{window}` binds `@PathVariable
  TimeWindow window` via Spring's case-sensitive `Enum.valueOf` (uppercase).
  Android sends `window.name` (uppercase) on both the body and the path, and
  decodes response windows case-insensitively → all correct. No change. (Unlike
  `DayOfWeek`, which really IS lowercased on the wire by an explicit Jackson
  serializer per PR#8, `TimeWindow` has no such serializer.)
- **Q6 — No change.** Terminal `complete` SSE frame carries the full object.
- **Q7 — Promote** `MarkerReferenceBar` into `core-ui`; dashboard `BloodPanel`
  and `feature-blood` share it (folded into the Q3 rewire).
- **Q8 — Keep** Settings/Profile chrome-light + placeholder Privacy/Terms URLs.
- **Q9 — Add** per-category equipment placeholder vector drawables in `core-ui`.
- **Q10 — Flag for backend.** TESTOSTERONE is absent from the backend
  `BloodMarker` enum; tracked as a backend follow-up (see below). No Android
  change — the dashboard panel renders gracefully without it.

### Backend follow-up tracked (Q10) — spec'd
The dashboard blood panel's display order references `TESTOSTERONE`, which is
**not** a value in the backend `BloodMarker` enum (only reachable via extracted
report markers). Written up as a full implementable spec:
**[`IMPL-13-blood-testosterone-marker.md`](IMPL-13-blood-testosterone-marker.md)**
— add `TESTOSTERONE` to the backend `BloodMarker` enum + `BloodReferenceRanges`
(mirroring the web's `ng/dL`, HIGHER_IS_BETTER, 500/200/1200) and to the Android
`core-domain` enum + `MarkerCatalog`. Purely additive; no migration. Until it
lands, the panel simply omits testosterone.

---

## 0. Biggest reconciliation: the scaffold had already diverged from the specs

Several IMPL-AND specs describe a **greenfield** starting point ("ad-hoc Compose
scaffold, manual graph wiring in `MainActivity`, no Hilt, no network calls").
The actual `android/` module on this branch is **further along**:

- **Hilt is already integrated** (`app`, `core-data`) and the whole app already
  builds (`./gradlew :app:assembleDebug` was green before this work).
- The **network stack already lives in `core-data/net`** (`NetworkModule`,
  `AuthInterceptor`, `@BackendBaseUrl` qualifier, Moshi, OkHttp, Retrofit) — there
  is **no separate `core-network` module** as IMPL-AND-00 proposes.
- The **Goals feature (IMPL-AND-12) is already fully implemented** (`feature-goals`,
  `core-data/goals`, `core-domain/goals`, `core-chat` SSE client).
- A single **string-route `AppNavHost`** exists (not the spec's typed
  `@Serializable` `Route` sealed hierarchy).

**Decision:** I implemented the missing features **against the existing
architecture** rather than rewriting it to match the specs' assumptions.
Concretely:

| Spec assumption | What I did instead | Why |
|---|---|---|
| New `core-network` module | Reused `core-data/net`; added `SseClient` + `MultipartSseClient` there | Avoids a large, risky module split; the network surface already lives in `core-data` and every feature already depends on `core-data`. |
| Typed `@Serializable` nav routes | String-based Navigation-Compose routes | Matches the existing `AppNavHost`/`Routes` pattern; avoids adding the kotlinx-serialization plugin to every module. |
| Migrate `AuthCoordinator` → `AuthViewModel` (IMPL-AND-00) | Left `AuthCoordinator` as-is | Out of scope for feature delivery; it works. A follow-up can do the VM migration. |
| `TokenAuthenticator` (silent refresh on 401) | **Not added** | The existing `AuthInterceptor` attaches the bearer token; 401-refresh is still handled at the UI layer as before. See open question Q1. |
| Build flavors (`dev`/`staging`/`prod`) + cleartext config | **Not added** | The app uses a single `BuildConfig.BACKEND_BASE_URL` (overridable via `-PbackendBaseUrl`). See open question Q2. |
| Per-feature `DayOfWeek` enums | One shared `domain.common.DayOfWeek` + one Moshi adapter | Two enums would need two conflicting Moshi adapters; medications (AND-03) and gym (AND-06) now share one. |

**Q0 — Do you want the full IMPL-AND-00 refactor (separate `core-network`
module, typed routes, `AuthViewModel`, build flavors, `TokenAuthenticator`)?**
I treated these as non-blocking because the existing equivalents work, but they
are real deltas from the spec text.

---

## 1. Foundation (IMPL-AND-00 gaps) — what was added

All verified compiling (`core-ui`, `core-data`, `core-domain`):

- `core-ui`: `LoadingState` / `EmptyState` / `ErrorState` (`ui/state`),
  `SnackbarController` + `ProvideSnackbarController` (`ui/snackbar`, a Hilt
  `@Singleton` injectable into ViewModels), `EditableNumber` (`ui/input`),
  `HfAsyncImage` (`ui/image`, Coil), `ConfirmDialog` (`ui/components`). Added the
  Coil dependency to `core-ui`.
- `core-data`: Moshi `LocalDateAdapter` / `InstantAdapter` (ISO-8601),
  `DayOfWeekMoshiAdapter` (lowercase `"mon"…"sun"`), all registered in
  `NetworkModule`'s `Moshi`; `DispatcherModule` (`@IoDispatcher` /
  `@DefaultDispatcher` / `@MainDispatcher`); generic `SseClient`
  (POST-JSON → `Flow<SseEvent>`) and `MultipartSseClient`
  (multipart-POST → `Flow<SseEvent>`) in `data/net/Sse.kt`. Added
  `play-services-auth` + `kotlinx-coroutines-play-services` for the Google Health
  scope flow, and test deps (junit/mockk/turbine/mockwebserver/coroutines-test).
- `core-domain`: shared `domain.common.DayOfWeek`.
- `app`: `FileProvider` (`<provider>` in manifest + `res/xml/file_paths.xml`,
  authority `${applicationId}.fileprovider`) for blood/DEXA PDF viewing; Hilt
  providers for `SnackbarController` and `@Named("webOauthClientId")`.
- `gradle/libs.versions.toml`: added `turbine`, `mockk`, `okhttp-mockwebserver`,
  `play-services-auth`, `androidx-activity-ktx`, `compose-foundation`.
- New modules created + wired into `settings.gradle.kts` / `app/build.gradle.kts`:
  `feature-settings`, `feature-blood`, `feature-body-composition` (and the
  pre-existing empty `feature-medical` / `feature-workouts` got full build files).

**Build-cache note:** this environment's Gradle **build cache served stale KSP
output** repeatedly (a renamed `@Provides` kept failing with a phantom error). I
set `org.gradle.caching=false` in `gradle.properties` to get reliable
incremental builds. **Revert this to `true` once off this machine** — it is a
workaround, not a desired setting.

---

## 2. Per-feature status

| Spec | Module(s) | Status |
|---|---|---|
| AND-01 Dashboard live data | `core-domain/dashboard`, `core-data/dashboard`, `app/dashboard` | Data layer + `DashboardViewModel` + `CardSwitch` + mapper tests **written & compiling**. The existing dashboard **screens still render fixtures** — they were NOT rewired to the VM (see Q3). |
| AND-02 Settings & Profile | `feature-settings`, `core-data/{profile,googlehealth,auth}`, `core-domain/{profile,googlehealth}` | **Compiles** (`:feature-settings:compileDebugKotlin` green). |
| AND-03 Medications | `feature-medical`, `core-data/medications`, `core-domain/medications` | **Compiles** (`:feature-medical:compileDebugKotlin` green). |
| AND-04 Blood | `feature-blood`, `core-data/blood`, `core-domain/blood` | **Compiles.** |
| AND-05 Body comp / DEXA | `feature-body-composition`, `core-data/bodycomposition`, `core-domain/bodycomposition` | **Compiles.** |
| AND-06 Gym / equipment | `feature-workouts`, `core-data/workouts`, `core-domain/workouts` | **Compiles.** |

**`./gradlew :app:assembleDebug` is GREEN** — all six feature verticals + the
foundation + the dashboard data layer compile and the **whole Hilt graph
resolves** across the app. The feature verticals (domain models, DTOs/mappers,
Retrofit APIs, repositories, ViewModels, Compose screens, nav graph functions,
and JVM unit tests) were implemented per the specs. Each feature provides its
Retrofit APIs through its **own** Hilt `@Module` (e.g. `MedicationsDataModule`,
`BloodDataModule`, `BodyCompositionModule`, `WorkoutsDataModule`,
`SettingsDataModule`, `DashboardDataModule`) — `NetworkModule` was left
untouched.

### Navigation IS wired (update)
All five feature graphs are registered in `app/.../nav/AppNavHost.kt`
(`medicationsGraph`, `bloodGraph`, `bodyCompositionGraph`, `workoutsGraph`,
`settingsGraph`). `DashboardRoot` gained an `onNavigate(route)` callback threaded
to both screens:
- **Foldable sidebar** routes Body → `body`, Blood → `blood`, Workouts →
  `workouts`, Meds → `medications`, Goals → goals.
- **Phone bottom nav** routes "More" → `settings`, "Log" → goals.
The `app/.../mobile/di/SettingsAppModule.kt` provides `GoogleAuthRepository` +
`AppVersionInfo` into the graph. `feature-settings` got the auth impl libs
(credentials / google-id / play-services-auth) added to its `build.gradle.kts`
so KSP can resolve the concrete auth repositories it injects.

### Corruption cleanup during integration
Several agent-written files had leaked spec markdown (``` fences, duplicated
imports/blocks, a stray `Mang. .kt` file full of "wait wait", a stub
`TodaysDosesCard`). These were rewritten/cleaned. A full scan for fences /
duplicate imports / stub bodies across all generated `.kt` is now clean.

### Tests — GREEN
`./gradlew :core-domain:test :core-data:test :feature-*:test --continue` →
**BUILD SUCCESSFUL, 109 tests, 0 failures, 0 errors.** Counts: core-domain 21,
core-data 33, feature-medical 9, feature-blood 13, feature-body-composition 13,
feature-workouts 12, feature-settings 8. The agent-authored tests were written
by inspection (never executed when authored); a dedicated test-fix pass made
them pass with test-only changes (markdown-fence cleanup, dispatcher rules,
import fixes) — production code stayed frozen since the app already built.

### Cross-cutting fixes applied during integration
- `collectAsStateWithLifecycle` was imported from the wrong package
  (`androidx.lifecycle.runtime.compose.*`) in several files → corrected to
  `androidx.lifecycle.compose.collectAsStateWithLifecycle`.
- Feature modules needed an explicit `androidx.compose.foundation:foundation`
  dependency (the `lazy`/`ExperimentalFoundationApi` APIs are not transitively
  exposed by `material3`) and `androidx.activity:activity-ktx` (for
  `ActivityResultContracts` / `IntentSenderRequest`). Both were added to all five
  feature modules.

---

## Residual work (remaining)

Items 1–3, 6, 7 are **DONE** (build green, nav wired, tests pass, caching
restored). Items 4–5 remain — they re-skin the already-polished dashboard, so I
left them for your confirmation (see Q3).

1. ~~Clear residual compile errors in feature-blood / body-comp / workouts.~~ **DONE.**
2. ~~App DI providers (`AppVersionInfo`, `GoogleAuthRepository`).~~ **DONE** via
   `app/.../mobile/di/SettingsAppModule.kt`.
3. ~~Navigation wiring (register feature graphs + dashboard `onNavigate`).~~ **DONE.**

4. **Dashboard screen wiring (AND-01):** the data layer is done; flip
   `PhoneTodayScreen` / `FoldableDashboardScreen` to read
   `hiltViewModel<DashboardViewModel>()` and feed the live weight/blood/doses into
   the existing widgets via `CardSwitch`, refreshing on `ON_RESUME`. This requires
   changing the widget signatures (`WeightChart`, `BloodPanel`, `TodayCard`,
   `StatCard`) to accept data parameters and renaming `DashboardFixtures` →
   `DashboardFallbacks` + a `DashboardFlags` gate, per the spec.

5. **Embed `TodaysDosesCard`** (from `feature-medical`) into the dashboard
   `TodayCard`, and **swap the dashboard `BloodPanel`** to
   `feature-blood`'s `DashboardBloodViewModel` (AND-03 / AND-04 dashboard hooks).

6. ~~Run the test suites.~~ **DONE — 109 tests pass, 0 failures.**

7. ~~Revert build settings.~~ **DONE** — `org.gradle.caching` and
   `org.gradle.configuration-cache` are back to `true`; final
   `:app:assembleDebug` is green with caching on. (They had been temporarily set
   to `false` because this machine's Gradle caches served stale KSP output
   during heavy multi-module work.)

---

## Open questions for you

- **Q1 (401 refresh):** Should I add the `TokenAuthenticator` (OkHttp
  `Authenticator` doing a silent refresh + one retry on 401) from IMPL-AND-00?
  Currently 401s are not auto-retried at the network layer.
- **Q2 (env/flavors):** Keep the single `BACKEND_BASE_URL` (overridable via
  `-PbackendBaseUrl`), or add the `dev`/`staging`/`prod` product flavors +
  `network_security_config` cleartext rules from IMPL-AND-00?
- **Q3 (dashboard rewire scope):** The AND-01 data layer is in place but the
  screens still show fixtures. Confirm you want the full screen rewrite (widget
  signature changes + `DashboardFixtures` → `DashboardFallbacks`) — it touches the
  already-polished dashboard UI.
- **Q4 (date wire format):** I assumed the backend emits `Instant` as **ISO-8601
  strings** (Spring Boot + Jackson JavaTimeModule, timestamps-as-ISO) and
  `LocalDate` as `yyyy-MM-dd`. If any endpoint emits **epoch millis**, the
  `InstantAdapter` needs a numeric `@FromJson`. Please confirm against the backend
  Jackson config.
- **Q5 (adherence window case):** Medications posts/paths use the **uppercase**
  `TimeWindow` enum name (e.g. `.../adherence/2026-05-30/EVENING`). Confirm the
  backend expects uppercase here (vs. lowercase like `DayOfWeek`). One-line change
  in `DefaultAdherenceRepository` if not.
- **Q6 (DEXA SSE completion frame):** The DEXA/blood upload mappers treat a
  `complete` phase **without** a `scan`/`report` payload as a failure. Confirm the
  backend's terminal SSE frame carries the full object.
- **Q7 (`MarkerReferenceBar` promotion):** IMPL-AND-04 suggests moving the
  dashboard `BloodPanel.RangeBar` into `core-ui` as `MarkerReferenceBar` and
  sharing it. It currently lives in `feature-blood`. Promote it?
- **Q8 (Settings chrome & links):** Settings/Profile screens are chrome-light (no
  `TopAppBar` back button) to match the Goals screens; About uses placeholder
  `https://placeholder.tesseta.app/{privacy,terms}` URLs. Confirm both.
- **Q9 (Equipment placeholder art):** Gym equipment with `imageStatus` PENDING/
  FAILED renders a generic `FitnessCenter` icon rather than per-category vector
  drawables (deferred). OK?
- **Q10 (`TESTOSTERONE` marker):** The dashboard blood panel includes
  `TESTOSTERONE` in its display order, but it is **not** in the backend
  `BloodMarker` enum (only available via extracted report markers). It will simply
  be absent for users with only manual readings until the backend adds it.
