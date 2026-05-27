# Android IMPL — Open Questions for Evan

This file collects questions, blockers, and assumptions surfaced by the
unattended overnight implementation of the IMPL-AND-00 .. IMPL-AND-06 specs
on the `feature/android_impl` branch.

Each implementing sub-agent appends to the relevant stage section below.
Review tomorrow morning.

---

## How to read this file

- **Question** — something that needs a decision before the work can be
  considered fully done. The agent made a best-guess assumption and the
  text of the assumption is recorded so you can confirm or reverse it.
- **Blocker** — something the agent could not work around. The work was
  partially landed or skipped. Marked with **BLOCKER** prefix.
- **Note** — useful context surfaced during implementation that doesn't
  need a decision (deferred follow-ups, surprising discoveries, etc).

When an item is resolved, replace its **Status:** line with `Resolved
{date} — {short note}`.

---

## Stage 00 — Foundations

### Note — `android/cloudbuild.yaml` updated for flavored build paths

**Status:** Resolved 2026-05-27 — keep `:app:assembleProdRelease` for Firebase App Distribution. Internal testers exercise the prod backend.

The IMPL-AND-00 spec adds `dev`/`staging`/`prod` flavors to `app/`, which
moves the release APK out from
`app/build/outputs/apk/release/app-release.apk` to
`app/build/outputs/apk/prod/release/app-prod-release.apk`, and replaces
the `:app:assembleRelease` umbrella task (which now assembles every
flavor's release variant) with `:app:assembleProdRelease` (only prod).

Updated `android/cloudbuild.yaml` so Cloud Build:
  - runs `:app:assembleProdRelease` instead of `:app:assembleRelease`;
  - pulls the APK from the flavored path for the Firebase App
    Distribution step and the artifacts upload.

If you'd rather Cloud Build distribute the `staging` variant during pre-
release weeks (so internal testers exercise the staging backend), flip
the task and path to `:app:assembleStagingRelease` /
`app-staging-release.apk`. No other CI files changed.

### Note — generated local `android/debug.keystore`

**Status:** Resolved 2026-05-27 — check the debug keystore into the repo. Round 2 Stage A loosens `.gitignore` and tracks `android/debug.keystore`; shared SHA-1 so OAuth works on first checkout.

The repo gitignores `*.keystore` and ships no local debug keystore, so a
clean checkout doesn't have one. The implementing agent ran
`keytool -genkeypair ... -alias androiddebugkey -storepass android` to
unblock `:app:assembleDevDebug` (which references
`../debug.keystore` in the IMPL-02 signing config). The keystore stays
local and gitignored. If you want every developer to start from the same
SHA-1, either:
  - check the dev keystore into the repo (loosen `.gitignore`), or
  - document the `keytool` invocation in `android/README.md`.

### Note — `feature-*` modules now activate Hilt + KSP

**Status:** informational.

Per spec section "Feature modules": each empty placeholder module gains
Hilt + KSP plugins and an `implementation(project(":core-network"))`
line even though the modules contain no Kotlin code yet. This stabilises
Hilt's aggregating annotation-processor module list now so the first
real `@HiltViewModel` per feature doesn't re-trigger a full graph rescan.

### Note — `DispatcherModule` provider function names

**Status:** informational.

The spec sample uses provider names `fun io()`, `fun default()`,
`fun main()`. Kotlin allows `default` as an identifier in a function
position but Hilt's KSP processor errors with
`not a valid name: default`. Renamed to
`provideIoDispatcher` / `provideDefaultDispatcher` /
`provideMainDispatcher` — same `@Qualifier` annotations, same return
types. No behaviour change.

### Note — `AuthUiState.toLegacyAuthState()` shim

**Status:** informational, deferred to IMPL-AND-02.

Per spec, this IMPL keeps the existing `SignInScreen` untouched. The
screen still consumes the IMPL-02 `AuthState`. `AuthViewModel` exposes
`AuthUiState`; a small `toLegacyAuthState()` extension bridges the two
inside `app/`. The extension passes `idToken = ""` because
`SignInScreen` doesn't display it. Drop the extension once IMPL-AND-02
lifts the screen's signature to `AuthUiState`.

### Note — `:wear` does not depend on `:core-network` yet

**Status:** informational.

The wear app gains Hilt + the dev/staging/prod flavor scaffolding and
the `BACKEND_BASE_URL` BuildConfig field, but it does *not* take an
`implementation(project(":core-network"))` line in this IMPL. Wear has
nothing to call yet; the network module drops in for AND-08 when the
first wear-side surface needs the backend.

---

## Stage 01 — Dashboard live data

### Note — TESTOSTERONE absent from backend `BloodMarker` enum

**Status:** Resolved 2026-05-27 — Round 2 Stage A adds `TESTOSTERONE` + adult-male reference range to the backend enum.

The spec calls for the dashboard blood panel to show Testosterone, LDL,
ApoB, HbA1c. The backend `BloodMarker` enum (in `core/blood/BloodMarker.java`)
covers lipids + glycemic + inflammation but **does not** include
`TESTOSTERONE`. The Android mapper's `DISPLAY_ORDER` still references it
so that:
  - the moment a user has a TESTOSTERONE reading (either from a future
    backend enum addition or from extracted markers via IMPL-AND-04's
    `/api/me/blood/reports` endpoint), it renders in the correct slot;
  - markers with no reading are transparently omitted, so today's
    users just see LDL + ApoB + HbA1c without an empty row.

No Android-side workaround is added. The one-line backend enum
addition + reference-range entry should live with backend blood work.

### Note — `lifecycle-runtime-compose` added to `:app`

**Status:** informational.

IMPL-AND-01 needs `collectAsStateWithLifecycle` and `LifecycleEventEffect`
in the two dashboard screens (resume-only refresh per spec). IMPL-AND-00
only depended on `lifecycle-runtime-ktx`, so the implementing agent
added `androidx-lifecycle-runtime-compose` to `gradle/libs.versions.toml`
and wired it into `:app`'s dependencies. Same `lifecycle` version
(2.8.7). Drop-in addition, no behaviour change for existing screens.

### Note — `core-network` now exposes Retrofit + Moshi via `api()`

**Status:** informational.

Per the spec, feature modules (here just `core-data` for the dashboard
slice) declare their own Retrofit interfaces (`DashboardApi`) and
Moshi-annotated DTOs. That requires Retrofit + Moshi to be on
downstream modules' compile classpath. The implementing agent flipped
`core-network`'s Retrofit/Moshi declarations from `implementation()` to
`api()`. OkHttp stays internal — the auth-aware client is wholly owned
by `core-network`.

### Note — `Instant` / `LocalDate` Moshi adapters added in `core-network`

**Status:** informational.

The backend serialises `java.time.Instant` and `java.time.LocalDate` as
ISO-8601 strings. Moshi has no built-in adapters for either; the
implementing agent added `InstantJsonAdapter` and `LocalDateJsonAdapter`
in `NetworkModule.kt` and registered them on the shared `Moshi`
instance. This means all future feature DTOs can declare `Instant` /
`LocalDate` fields without `@Json(name = ...)` overrides.

### Note — DTOs use the reflective Moshi adapter, not codegen

**Status:** informational.

IMPL-AND-00 wired `moshi` + `moshi-kotlin` (reflective adapter) but did
**not** wire `moshi-kotlin-codegen` into KSP. The IMPL-AND-01 DTOs
therefore drop `generateAdapter = true` and rely on the
`KotlinJsonAdapterFactory` already registered in `NetworkModule.moshi`.
If we ever need codegen for hot-path serialization, add the KSP
processor in a follow-up — the DTO sources should be trivial to flip.

### Note — single `DashboardApiHttpTest` covers MockWebServer + Moshi contract

**Status:** informational.

The spec called for one MockWebServer test per repository plus a
separate `MoshiContractTest`. The implementing agent consolidated all
three repos + the contract round-trip checks into a single
`DashboardApiHttpTest` — same coverage, fewer setup blocks. Per-mapper
edge cases stay in the dedicated `BodyCompositionMapperTest` and
`BloodMarkerSummaryMapperTest`.

### Note — `DashboardScreenSnapshotTest` deferred

**Status:** Resolved 2026-05-27 — Paparazzi is the snapshot framework. Round 2 Stage A wires it; Round 2 Stage D adds the DashboardScreen snapshot.

The spec asks for a Paparazzi-style preview snapshot test in
`androidTest/`. IMPL-AND-00 did not wire Paparazzi or any other
snapshot framework, and the test would need the full Compose UI test
harness. Skipped this IMPL — the three-state shape is covered by
`DashboardViewModelTest` (Loading / Loaded / Error transitions per
card) and the visual states are observable by running the dev variant.
If you want a snapshot test added, point at the framework you want
(Paparazzi vs. Roborazzi vs. plain Compose UI test bitmap diff) and
it can land in IMPL-AND-02 alongside settings.

### Note — x-axis chart labels uppercased to match web

**Status:** informational.

The web's `shortDate` helper uppercases the formatted month abbreviation
("MAY 20" rather than "May 20"). The Android `BodyCompositionMapper`
now does the same so the chart labels read identically across both
clients. Spec text says "MMM dd" without specifying casing; matched web
for parity.

---

## Stage 02 — Settings & profile

### Note — backend extended additively for `{ serverAuthCode }` connect body

**Status:** informational, no action needed but please review the backend slice.

Per the IMPL-AND-02 spec's "Backend touchpoint" section, this IMPL adds an
Android branch to `GoogleHealthConnectController.connect()`:

  - `ConnectRequest` now carries an additional optional `serverAuthCode`
    field alongside the existing `refreshToken` + `accessToken`. The web
    path continues to use the original shape exactly; the Android client
    posts `{"serverAuthCode": "..."}` only.
  - `GoogleHealthOAuthClient.exchangeServerAuthCode(String)` is the new
    helper that posts the auth-code grant against
    `https://oauth2.googleapis.com/token`, reusing the existing web
    OAuth client id + secret config. Returns a `TokenPair` record that
    feeds the same KMS-encrypt-and-store path the web shape uses.
  - The controller branches on `serverAuthCode` presence and rejects
    bodies that have neither shape with `IllegalArgumentException`.

No new IMPL spec needed; this is the small additive backend slice the
spec explicitly says lands with AND-02. Pre-existing backend test
failures (DrugRepository bean wiring, Gemini-API-key-gated image
generation tests) are unrelated.

### Note — `AuthorizationClientGateway` seam moved request-building out of repo

**Status:** informational.

The spec sketches `requestHealthAuthorization()` as building an
`AuthorizationRequest` directly inside the repository and passing it to
the GMS client. That works at runtime but blocks JVM unit tests:
`com.google.android.gms.common.api.Scope.<init>` calls
`android.text.TextUtils.isEmpty`, which throws `RuntimeException: Method
isEmpty in android.text.TextUtils not mocked` under JUnit. To keep the
repository unit-testable without Robolectric, the gateway interface now
takes `(webOauthClientId: String, scope: String)` and the request is
constructed inside `DefaultAuthorizationClientGateway` (the GMS-touching
side). Public behaviour is identical.

### Note — `AppRoot` threads `onSignedOut` down through the scaffold

**Status:** informational.

The spec calls for an `AuthCoordinator.markSignedOut()` method that the
sign-out NavGraph callback dispatches into. The current `AuthViewModel`
already has a `signOut()` that flips its UI state, and it's owned at
the `AppRoot` composable's lifecycle, so re-doing that as a singleton
coordinator would double the surface. Instead, `AppRoot` passes a small
`onSignedOut: () -> Unit` lambda (bound to `authViewModel.signOut()`)
into `SignedInScaffold`, which threads it through `AppNavHost` to the
`SettingsScreen` route. `SettingsScreen` calls its own `SettingsViewModel.signOut(onDone)`
which clears tokens + Credential Manager state and then invokes the
lambda. The lambda flips the auth UI state, the top-level `when (state)`
in `AppRoot` re-evaluates, and the sign-in screen takes over.

### Note — height-clear via PATCH omits the field per default Moshi semantics

**Status:** informational.

The web posts `{"heightCm": null}` to clear the value; Android, with
the reflective `KotlinJsonAdapterFactory`, omits null fields entirely
from the JSON body. This means a hypothetical "clear my height" path
on Android wouldn't actually clear the backend field. The Save button
in `ProfileScreen` is disabled when both ft and in are blank, so the
UI never exercises this path today. If the unit-system ADR or a future
"Remove height" affordance lands, switch the Moshi instance to
`.serializeNulls()` (or expose the field as a sealed `Update<Int>`
type so the writer can distinguish "unset" from "set to null").

### Note — `KDoc` containing `/*` would break KSP

**Status:** informational, drive-by fix already in place.

`GoogleHealthService.kt`'s class-level KDoc originally read
`Retrofit service for /api/me/google-health/*. Three endpoints:`. The
`/*` inside the `/** ... */` block confused KSP's incremental parser
into reporting "Unclosed comment" at EOF, which in turn made every
Hilt generation step error with `error.NonExistentClass`. Rewriting
the prose to avoid `/*` fixed it. Watch for the same pattern in
future KDocs (`URLs with /* paths`).

### Note — `feature-settings` test for the NeedsUserConsent path uses fake repo

**Status:** informational.

`GoogleHealthScopeRepositoryTest` exercises the Resolved + Failed
branches via the gateway seam, but skips the `NeedsUserConsent` path
because constructing a real `android.content.IntentSender` in a JVM
test is impractical (its public constructor is package-private). The
`NeedsUserConsent → onConsentResult → Connected` flow is instead
covered end-to-end by `GoogleHealthViewModelTest`, which uses a fake
`GoogleHealthScopeRepositoryApi` that doesn't go through GMS at all.

---

## Stage 03 — Medications

### Note — bottom nav promoted Meds out of the "More" tab

**Status:** Resolved 2026-05-27 — final bottom nav is `Today / Body / Meds / More` (4 slots + overflow). Round 2 Stage B implements MoreScreen with Blood, Workouts, Settings, Sign out.

Stage 02 set the phone bottom nav to `Today / Body / Blood / Workouts /
More`, with `More` going straight to Settings. IMPL-AND-03's spec calls
for Meds in the bottom nav; the simplest fit was to replace `Workouts`
with `Meds` and keep `More → Settings` until Workouts (IMPL-AND-06)
ships. If you'd rather keep Workouts visible and drop Body or Blood,
the change is a one-line tweak in
`android/app/src/main/java/com/gte619n/healthfitness/mobile/navigation/NavDestinations.kt`'s
`BottomNavDestinations` list. The foldable sidebar keeps all six
primary destinations including Workouts (no space pressure there).

### Note — Route.MedicationDetail moved to the feature module

**Status:** informational.

The IMPL-AND-00 `Route` sealed hierarchy declared
`Route.MedicationDetail(id)` as a placeholder. To let
`MedicationDetailViewModel` (in `feature-medical`) call
`savedState.toRoute<MedicationDetailRoute>()` without `:feature-medical`
depending on `:app`, the canonical detail route is now
`com.gte619n.healthfitness.feature.medical.nav.MedicationDetailRoute`.
The `AppNavHost` registers a `composable<MedicationDetailRoute>` against
the same NavHost and the dashboard's "View all" link routes to
`Route.Medications`. `Route.MedicationDetail` is removed; a comment
points the next reader at the new location.

### Note — dashboard `TodaysDosesRepository` kept alongside the new medications path

**Status:** informational.

IMPL-AND-01 added a stand-alone `TodaysDosesRepository` (mapping the
backend's `/api/me/medications/today` shape into a dashboard-local
`TodaysDoseSummary`). IMPL-AND-03 adds a parallel
`MedicationRepository.todaysDoses()` returning the medications-domain
`TodaysDose`. The new interactive `TodaysDosesSection` composable on the
dashboard reads from the medications repository through its own
`TodaysDosesViewModel`. The old dashboard repo + its CardState in
`DashboardViewModel` are still wired (so `DashboardViewModelTest` keeps
passing) but no UI surface consumes the result anymore. Safe to delete
in IMPL-AND-04 if no other surface picks it up; left in place for now
to keep this stage's diff additive.

### Note — `DrugLookupStreamClient` is `internal open class` for test stubbing

**Status:** informational.

`DefaultDrugRepository` takes `DrugLookupStreamClient` as a concrete
type (Hilt injection target). The catalog-only test
`MedicationsApiHttpTest` doesn't exercise the SSE path but still has to
construct a `DefaultDrugRepository`. To avoid pulling MockWebServer +
SseConsumer + Moshi factories into every catalog test, the production
class is `internal open` and `NoOpLookupStreamClient` subclasses it with
an `emptyFlow()` override. The parent constructor's eager
`moshi.adapter<LookupPhaseDto>()` call is satisfied with a
`KotlinJsonAdapterFactory()`-bearing throwaway Moshi.

### Note — CYCLE frequency type modeled but not editable

**Status:** informational.

The spec calls out that `FrequencyType.CYCLE` must round-trip so an
existing web-created med doesn't break the Android client, but the Add
/ Edit UI doesn't expose a cycle editor. `FrequencySelector` lists
only `DAILY / WEEKLY / MONTHLY / PRN`; switching from CYCLE to one of
these via edit will drop the `cycle` config (the backend `UPDATE`
handler accepts the new `frequency` wholesale). That matches web's
current behavior. A dedicated cycle editor lands later — track it
alongside the protocol-grouping UI which has the same "model exists, UI
doesn't" status.

### Note — `feature-medical` had to opt into `kotlin-serialization` plugin

**Status:** informational.

`feature-medical/nav/MedicationsNav.kt` declares the three
`@Serializable` nav-route objects required by Navigation-Compose 2.8's
type-safe routes. That needs the `kotlin-serialization` Gradle plugin,
which IMPL-AND-00 only applied to `:app` (the only module that owned a
`@Serializable` route before). Added
`alias(libs.plugins.kotlin.serialization)` to
`feature-medical/build.gradle.kts`. Stage 04+ feature modules that add
their own routes will need the same.

### Note — `dose=0.0` accepted in `LogDoseRequest`; Android always sends null

**Status:** informational.

The backend's `AdherenceController.LogDoseRequest` accepts an optional
`dose: Double` and falls back to the medication's current dose when
absent. Android's `DefaultAdherenceRepository.logDose` always sends
`dose=null`, which serializes as an omitted field (reflective Moshi
default behavior; see Stage 02 note on `serializeNulls`). This is the
correct behavior — Android never needs to override the dose when
logging because the optimistic UI doesn't surface a "log at half dose"
affordance. If a future PRN-with-dose-override flow lands, the field
is already on the DTO and the request DTO needs no change.

---

## Stage 04 — Blood testing

### Note — BloodTestReportResponse omits `createdAt`; Android tolerates null

**Status:** informational.

The backend's `BloodTestReportResponse.from(BloodTestReport)` only
copies `reportId`, `sampleDate`, `labSource`, and `markers` — the
domain record's `createdAt` is dropped on the wire. Android's
`BloodTestReportDto` declares `createdAt: Instant?` so the missing
field deserialises as null, and the report-list / detail UI falls
back to copy that doesn't depend on a timestamp.

If we ever need to render "uploaded N hours ago" we'd add `createdAt`
to the response record on the backend — the Android DTO is already
ready for it.

### Note — `BloodTestReport.labSource` is non-null at the API boundary

**Status:** informational.

The backend's `BloodTestReportResponse.labSource` is declared
`String` (not nullable), but the underlying core record allows null
in the constructor path. To stay safe across both shapes the Android
DTO declares the field as `String?` and the mapper falls back to an
empty string when null. The UI then renders "Lab report" when blank,
matching the web client's behaviour.

### Note — FileProvider authority added in app manifest

**Status:** informational, no action needed.

`ReportDetailViewModel.openPdf()` calls
`FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)`.
The IMPL-AND-04 work adds the missing manifest entries:
  - `android/app/src/main/AndroidManifest.xml` declares the
    `androidx.core.content.FileProvider` provider with authority
    `${applicationId}.fileprovider`.
  - `android/app/src/main/res/xml/file_paths.xml` exposes
    `cacheDir/blood/` as `<cache-path name="blood_cache" path="blood/" />`.

The spec's Decisions table called this an "existing authority"
assumption — the authority did not previously exist, so this IMPL
adds it. No backend / Cloud Build change.

### Note — `BloodMarkerSummaryMapper` retired but file retained

**Status:** informational.

IMPL-AND-04 rewires `BloodMarkerSummaryRepositoryImpl` so the
dashboard panel reads through `BloodReadingRepository` +
`BloodTestReportRepository` (the same repos the new feature-blood
module uses). The Stage 01 `BloodMarkerSummaryMapper` is no longer
called by any production code, but the file + its tests are kept as
a reference for the single-endpoint derivation logic in case a
narrower path needs to be revived. Safe to delete by IMPL-AND-08 if
neither use case resurfaces.

### Note — multipart-SSE upload uses a custom parser, not OkHttp EventSource

**Status:** informational.

OkHttp's `EventSource.Factory` builds GET-only requests, so the blood-
test PDF upload (POST multipart/form-data + text/event-stream
response) cannot share its plumbing with `SseConsumer`. IMPL-AND-04
adds `MultipartSseClient` in `core-network/` that combines a
`MultipartBody` POST with a hand-rolled SSE line parser on the
response `BufferedSource`. Reusable for IMPL-AND-05 DEXA upload
(same shape — PDF in, phase events out).

The parser supports:
  - `event:` field for the optional event name
  - `data:` field, multiple lines joined with `\n`
  - blank-line event delimiters
  - `:`-prefixed comments / heartbeats
  - end-of-body flush (servers sometimes omit the trailing blank line)
  - non-2xx response throws `IOException` so the upstream flow can
    map it to `UploadEvent.Failed`

### Note — `Route.BloodReportDetail` kept as a redirect to feature route

**Status:** informational.

The original `Route` sealed hierarchy in `app/.../navigation/Route.kt`
declares `Route.BloodReportDetail(reportId)`. To avoid breaking any
deep-links that may already target it, `AppNavGraph` registers a
`composable<Route.BloodReportDetail>` that immediately pops itself
and navigates to the feature-owned
`com.gte619n.healthfitness.feature.blood.nav.ReportDetailRoute`. The
feature route is now the source of truth — drop `Route.BloodReportDetail`
in a future stage if no caller references it.

---

## Stage 05 — Body composition & DEXA

### Note — `WeightChart` xLabels parameter dropped during promotion

**Status:** informational.

The old `WeightChart` in `app/.../dashboard/` accepted an
`xLabels: List<ChartXLabel>` argument that was never rendered (the
foldable hero draws the date strip above the chart, not inside it).
The spec's promotion target is `core-ui`, which doesn't depend on
`core-domain`, so keeping the parameter would have either forced a
new module dep or required defining a parallel `ChartXLabel` in
`core-ui`. Both moves felt heavier than warranted given the value
was unused — so the IMPL drops the parameter, and the dashboard
caller no longer threads `summary.xLabels` through. The
`BodyCompositionMapper.buildXLabels` helper still runs and the
result is still in `WeightSummary.xLabels` for any future caller
that wants to render labels above/below the chart.

### Note — body-composition domain types live alongside (not replacing) the dashboard's

**Status:** Resolved 2026-05-27 — `domain.bodycomposition.*` (new) is canonical. Round 2 Stage C migrates the downsampler/xLabels to the snapshot pipeline and retires `WeightSummary`.

The spec calls for the dashboard hero to consume the new
`BodyCompositionRepository.observeSnapshot()` directly, with
`WeightSummary` retired in favour of `BodyCompositionSnapshot`. The
IMPL added the new canonical types under
`com.gte619n.healthfitness.domain.bodycomposition.*` and the
feature module is wired against them, but the dashboard's IMPL-AND-01
plumbing (`WeightSummary` + `BodyMetric` + `BodyCompositionPoint` in
`domain.dashboard`, plus its own `BodyCompositionMapper` +
`BodyCompositionRepositoryImpl` in `core-data/dashboard/`) is
**still in place** and the hero still reads from it. Reasons:

  - The dashboard mapper has a tested downsampler + xLabel builder +
    derived-lean-mass path that would need re-implementing on the
    new snapshot shape.
  - Switching the hero ViewModel mid-stage felt like high churn for
    no observable behaviour change — both code paths produce the
    same numbers from the same backend response.

If you want a single source of truth here, point me at the preferred
direction:

  - Keep new types as the canonical home (replace dashboard's), or
  - Drop the new `BodyCompositionSnapshot` shape and route the
    feature module through the existing `WeightSummary` instead.

The two-implementations state can stay through IMPL-AND-06 without
hurting anything else.

### Note — FileProvider `cache-path` for DEXA PDFs added

**Status:** informational.

`res/xml/file_paths.xml` previously only declared the `blood/` cache
path. IMPL-AND-05 adds a parallel `dexa_cache → dexa/` entry so the
DEXA detail's "View PDF" action can hand a granted-readable URI to
the system PDF viewer over the same `${applicationId}.fileprovider`
authority. No manifest change needed.

### Note — `EditableNumberCell` is a thin wrapper for now

**Status:** informational.

The spec calls for `EditableNumberCell` to "wrap `core-ui`'s
`EditableNumber` with PATCH + revert + snackbar wiring". The
PATCH/revert/snackbar logic actually lives one layer up in
`DexaScanDetailViewModel.patchField` (optimistic update,
`runCatching { repo.patchField(...) }`, revert + transient message
on failure), which the screen surfaces through
`LocalSnackbarController`. The cell composable itself stays a thin
pass-through to `EditableNumber` so the same wiring works for any
future numeric cell — bone density, RMR, etc. Spinner / inline
error state can land on the wrapper later without touching the
ViewModel API.

### Note — Dashboard body-composition hero unchanged

**Status:** informational.

The hero card on the foldable dashboard still uses the IMPL-AND-01
`WeightSummary` path. It was already wired to a real repository
(not fixtures) so there's no behaviour gap. The `WeightChart`
composable itself moved to `core-ui` and the hero imports the new
location, but the data feed didn't switch over. See the
"body-composition domain types live alongside" note above for the
consolidation decision needed.

### Note — no Paparazzi / Compose UI snapshot tests landed

**Status:** Resolved 2026-05-27 — Paparazzi is the framework. Round 2 Stage A wires it across modules; Round 2 Stage D adds DEXA region grid + EditableNumberCell snapshots.

The spec lists `DexaRegionGridTest` and `EditableNumberCellTest`
as desired tests. Both need either Paparazzi (snapshot bitmaps) or
a full Compose UI test harness, neither of which IMPL-AND-00 wired.
Skipped for this stage — the behavioural surface is covered by:

  - `BodyCompositionSnapshotTest` (snapshot math)
  - `UploadDexaViewModelTest` (phase ladder + size-limit + terminals)
  - `WithFieldPatchedTest` (optimistic-fold path strings)

Point at the framework you want (Paparazzi / Roborazzi / plain
Compose UI test bitmaps) and the visual tests can land in
IMPL-AND-08 alongside any other deferred snapshot coverage.

---

## Stage 06 — Gym & equipment

### Note — bottom nav: Blood dropped, Workouts promoted

**Status:** Resolved 2026-05-27 — final bottom nav is `Today / Body / Meds / More`. Blood AND Workouts both move to the More sheet (4 primary + overflow); see Round 2 Stage B.

Stage 03 had `Today / Body / Blood / Meds / More`. With Workouts coming
back online this IMPL changes it to `Today / Body / Workouts / Meds /
More` (Blood drops from the bar to the foldable sidebar / dashboard
panel). Five tappable slots is already pushing the spec's "4 max"
guidance, so this slot juggling tries to keep the most-touched
mutating surfaces in the bar. If you'd rather keep Blood (it has the
multipart-SSE upload surface that benefits from quick access), swap
Body to More or drop it entirely — the change is a one-line tweak in
`android/app/src/main/java/com/gte619n/healthfitness/mobile/navigation/NavDestinations.kt`'s
`BottomNavDestinations` list. The foldable sidebar keeps all six.

### Note — Equipment `specs` Polymorphic adapter swapped for a map-projecting mapper

**Status:** informational.

The IMPL-AND-06 spec sketched a Moshi
`PolymorphicJsonAdapterFactory.of(EquipmentSpec::class.java, "specSchema")`
for the typed `EquipmentSpec` sealed class. That factory expects the
discriminator (`specSchema`) to live inside the spec JSON object. The
backend's `EquipmentResponse` instead places `specSchema` as a sibling
field next to `specs: Map<String, Object>`. Trying to make the factory
work would need either a JSON pre-processor that moves the discriminator
into the spec object, or backend changes — neither is appealing.

The implementing agent kept the `EquipmentSpec` sealed class as the
domain shape but did the projection by hand:
  - `EquipmentDto` mirrors the wire shape (`specSchema: String`,
    `specs: Map<String, Any?>`).
  - `WorkoutsMappers.specsFromMap(schema, map)` projects into the typed
    subtype; `specsToMap(spec)` projects back.
  - Unknown / missing schemas degrade to `EquipmentSpec.Bodyweight`
    (same forward-compat behaviour the spec called for).

Behavioural surface is identical to what the spec asked for;
implementation differs by a couple of files. Round-trip tests in
`WorkoutsMappersTest` cover all six schemas plus Int/Double tolerance.

### Note — Kotlin package `new` renamed to `create`

**Status:** informational.

`feature.workouts.new` was the natural home for `NewGymScreen` +
`NewGymViewModel`. Hilt's annotation processor emits Java code, and
Java rejects `new` as a package identifier. The aggregating processor
failed with a `NullPointerException` during element validation.
Renamed to `feature.workouts.create` (`NewGym*` class names retained
since Kotlin treats `new` as a soft keyword, only the package was the
problem).

### Note — no add-equipment-to-gym backend endpoint; PATCH the location

**Status:** informational, please review.

The spec listed a hypothetical `POST /api/me/gyms/{id}/equipment/{equipmentId}`.
That endpoint does not exist on the backend's `LocationController` —
the only mutation path is `PATCH /api/me/gyms/{id}` with a new
`equipmentIds[]` list. The Android client mirrors that: both
`AddEquipmentViewModel.addFromCatalog` and the `submitNew` flow read
the current location, append the new id, and PATCH. The
`LocationRepository.addEquipment`/`removeEquipment` methods from the
spec are NOT in the domain interface — they would have been thin
wrappers around `update()` with no extra value.

If a future product cycle wants atomic add (e.g., transactional add
that prevents two concurrent UI tabs from clobbering each other's
edits), the backend should grow the endpoint and the repo gets a
single method; for now this matches both the web's behaviour and the
backend's current API surface.

### Note — `HoursMatrix` uses TextField instead of TimePickerDialog

**Status:** Resolved 2026-05-27 — keep `TextField`. No rebuild.

The spec called for Material3 `TimePickerDialog`. First cut used
plain `HH:mm` `TextField`s so the entire 7-day matrix stays inline
without modals — feels less heavy than seven popups. The spec
explicitly said "if the modal feels heavy in QA, fall back to a
DropdownMenu of 30-minute slots". This is an even lighter fallback —
free text with the format placeholder. If you want the picker,
swap `CompactTimeField` for `Material3 TimePickerDialog`-launching
buttons in `HoursMatrix.kt`.

### Note — `MultipartUploadClient` returns a `Result<T>` via caller-supplied parser

**Status:** informational.

The IMPL-AND-04 `MultipartSseClient` emits a `Flow<MultipartSseEvent>`
that the caller streams from. The cover-photo upload doesn't have a
phase ladder, just a single JSON LocationResponse on success, so the
new `MultipartUploadClient` takes a `parse: (ResponseBody) -> T`
lambda and returns `Result<T>`. The two helpers sit next to each
other in `core-network/upload/` and `core-network/sse/`; the SSE one
was NOT refactored to depend on the new helper because the SSE flow
needs to start emitting before the request completes, which doesn't
fit the `Result<T>` shape. If IMPL-AND-04 / 05's blood + DEXA upload
flows ever drop SSE (the backend exposes the same operations
synchronously today via the JSON endpoints), they can collapse onto
this client.

### Note — Equipment override sheet's "unsupported" branch is wired but unreachable

**Status:** informational.

The spec says: if the backend introduces a spec schema this build
doesn't know, the override sheet should refuse to edit and point at
the web app. The schema-tag enum lives in `core-domain` and the
mapper already coerces unknown discriminators to `BODYWEIGHT`. The
sheet's `unsupported` UI branch exists, but the path that sets the
flag isn't currently active — the catalog Equipment's schema is read
through the same enum, so an unknown schema arrives as
`BODYWEIGHT` and the form just renders the "no specs" body-weight
copy. This is the safer default for forward-compat; if you want the
hard "open in web" message, add a marker on `EquipmentDto.toDomain()`
that distinguishes "wire reported BODYWEIGHT" from "wire reported
something unknown that we coerced".

### Note — No Compose UI snapshot tests for the new screens

**Status:** Resolved 2026-05-27 — Paparazzi is the framework. Round 2 Stage D adds LocationCard + EquipmentSpecForm (per category) snapshots.

Same situation as Stage 04 and 05. Paparazzi / Roborazzi / Compose UI
test harness was never wired by IMPL-AND-00. The behavioural surface
is covered by `WorkoutsMappersTest` (the spec round-trip) and
`GymsListViewModelTest` (loading / error / refresh transitions). The
spec also listed `LocationCardTest` and `EquipmentSpecFormTest`
snapshots — skipped this stage. Point at the framework when you want
the visual tests added.

---

## Cross-cutting

(no items yet)

---

## Round 2 — Stage A

### Note — debug keystore tracked via negation rule

**Status:** informational.

`android/.gitignore` now reads `*.keystore` followed by
`!debug.keystore`, so the shared `android/debug.keystore` is tracked
while any other `*.keystore` file (release keystores, ad-hoc test
keystores) stays ignored. The root `.gitignore` already had an
explicit `*-release.keystore` rule plus a comment noting the debug
keystore is "intentionally tracked", so no root-level change was
needed. SHA-1 + alias + passwords now live under the new "Debug
keystore" section of `android/README.md`.

### Note — Paparazzi 1.3.5 (latest stable) chosen over 2.0.0-alpha

**Status:** informational.

Maven Central currently advertises `app.cash.paparazzi 2.0.0-alpha05`
as `latest`, but the alpha branch tracks newer AGP / Compose
versions and our project is on AGP 8.7.3 + Kotlin 2.0.21. The
1.3.5 release also tracks Kotlin 2.0.21 via its BOM, so the version
pairs match without forcing a stack bump. When the project moves to
AGP 8.8+, revisit jumping to the 2.0 line.

### Note — Paparazzi smoke test scoped to read-mode states

**Status:** informational.

`EditableNumberPaparazziTest` covers the two read-mode flavours the
production UI actually paints today (formatted value with suffix,
placeholder em-dash for null). The composable's edit-mode branch
needs a real tap to flip `isEditing`, which Paparazzi can't
dispatch. Stage D's `EditableNumberCell` snapshot will cover the
editing state via the cell wrapper's `initiallyEditing` flag (per
the IMPL-AND-05 spec sketch).

### Note — TESTOSTERONE enum value appended (not alphabetized) to preserve serialized storage order

**Status:** informational.

`BloodMarker` uses `Enum.name()` as the storage key for Firestore
readings, so reordering existing enum values is safe but **inserting**
between them would silently shift the source-order of unrelated
markers in any code that iterates `BloodMarker.values()` or relies
on `ordinal()`. To minimise risk, TESTOSTERONE was appended after
HS_CRP rather than alphabetized. Reference range is the adult-male
Endocrine Society / LabCorp 264–916 ng/dL, modelled as
HIGHER_IS_BETTER with the lower bound as the "good" threshold.
Female and age-stratified ranges land alongside per-user reference
range overrides in a later iteration.

### Note — new `BloodReferenceRangesTest` added (no prior coverage)

**Status:** informational.

There was no existing test for the marker / reference-range
pairing. Added `BloodReferenceRangesTest` covering: every enum
value has a registered range (so future enum additions can't
silently lose reference-range coverage) and the TESTOSTERONE range
fields (canonical unit, orientation, thresholds). Pre-existing
`backend/:app:test` failures (LocationControllerTest +
DrugRepository wiring + Gemini-key-gated image generation tests
flagged in Stage 02 notes) are unchanged by this slice — they
predate the change and reproduce on `HEAD~3`.

---

## Round 2 — Stage B

### Note — `MoreRoute` lives in `feature-settings`, not `Route` sealed hierarchy

**Status:** informational.

The Round 2 Stage B brief asked for a `MoreRoute` "co-located with
`SettingsRoute`". There is no `SettingsRoute` today — the Settings
screen is still reached via `Route.Settings` in `app/`. To match the
established feature-route convention (`MedicationDetailRoute`,
`GymDetailRoute`, `ReportDetailRoute` all live in their feature
module's `…/nav/` package), the new route was added as
`com.gte619n.healthfitness.feature.settings.more.MoreRoute` next to
the `MoreScreen` composable that consumes it. The phone nav graph
imports the route directly; the foldable sidebar continues to drive
off `Route.Settings` (which it should — the foldable lists every
primary destination separately rather than collapsing into More).

If you'd rather Settings also migrate to a feature-owned
`SettingsRoute` to fully retire the `Route` aggregator, that's a
separate cleanup pass — it would touch deep-link URI declarations
and the foldable sidebar's icon list, neither of which Stage B was
chartered to change.

### Note — `TopLevelDestination.route` widened from `Route` to `Any`

**Status:** informational.

`PrimaryDestinations` still holds `Route` instances exclusively, but
`BottomNavDestinations` now also references `MoreRoute` (a
feature-owned `@Serializable object` that intentionally does not
implement the `Route` sealed interface). The cleanest way to keep
both lists typed-but-mixed is to widen `TopLevelDestination.route`
to `Any`. The runtime call sites (`navController.navigate(route)`
and `destination.hasRoute(route::class)`) both accept `Any`, so the
widening is structurally safe — they just lose the compile-time
guarantee that every destination is a member of the `Route`
hierarchy. If we end up adding more feature-owned routes to the
bottom nav, formalising a shared `interface NavRoute` would be the
right next step; today's two-call-sites surface didn't justify the
refactor.

### Note — `SignOutAction` indirection added to keep `MoreViewModel` testable

**Status:** informational.

`GoogleAuthRepository` is a `class` (not `open`) that holds a live
`CredentialManager`, which makes it impractical to substitute in
JVM tests. `MoreViewModel` therefore depends on a small
`SignOutAction` `fun interface` provided by a co-located Hilt
module that delegates to `GoogleAuthRepository.signOut()`. The
existing `SettingsViewModel` keeps its direct dependency on
`GoogleAuthRepository` (it has no unit-test today), so this
indirection is local to the new code; if/when SettingsViewModel
grows a test it can adopt the same binding.

### Note — Three `Route` redirect shims removed (DexaDetail / BloodReportDetail / GymDetail)

**Status:** informational.

Round 1 Stages 04 / 05 / 06 left thin `LaunchedEffect` redirect
shims at `Route.DexaDetail`, `Route.BloodReportDetail`, and
`Route.GymDetail` so any pre-existing deep-link that targeted the
old aggregator routes would still land on the feature-owned route.
A repo-wide grep confirmed nothing outside `AppNavGraph.kt` and
`Route.kt` ever referenced them — no notifications, no string deep
links, no other modules — so all three were dropped along with
their `Route.kt` declarations. The feature-owned routes
(`DexaScanDetailRoute`, `ReportDetailRoute`, `GymDetailRoute`)
remain the single source of truth for those leaves.

### Note — `MoreViewModel.UiState.NoProfile` instead of `UiState.Error`

**Status:** informational.

The profile fetch in `MoreViewModel` collapses failures into
`UiState.NoProfile` rather than `UiState.Error`. The identity
header is decorative — the menu rows below (Blood / Workouts /
Settings / Sign out) don't depend on profile data, and forcing a
full-screen error state would make the user unable to navigate or
sign out when `/api/me` is transiently down. The Paparazzi snapshot
covers both branches.

### Note — `Outlined.Home` chosen for the Today tab icon (was `Outlined.Dashboard`)

**Status:** informational.

The Round 2 Stage B brief suggested `Outlined.Home / Filled.Home`
for the Today tab on the bottom bar. `BottomNavDestinations` now
uses `Outlined.Home` in that slot, but `PrimaryDestinations` (the
foldable sidebar's source) keeps `Outlined.Dashboard` — the
foldable rail is denser and the dashboard glyph reads better at
18 dp. If you'd rather unify, change `PrimaryDestinations[0]` to
`Icons.Outlined.Home` and the foldable will follow.

### Note — Selected-state filled icons not yet wired

**Status:** informational.

Stage B's brief listed an Outlined-unselected / Filled-selected
icon pair per destination. The current `BottomNavBar` doesn't
swap icons on selection — selection is signalled via the accent
underline + tinted icon already in place from Round 1. Filling out
filled-icon pairs would require widening `TopLevelDestination` to
carry both glyphs and threading `active` into the iconography; left
as a follow-up so this stage stays scoped to the structural
restructure. The accent-underline + tint is still distinctive
enough that selection state reads at a glance.

---

## Round 2 — Stage C

### Note — `WeightHeroDisplay` lives in `core-data/bodycomposition`, not on the domain snapshot

**Status:** informational.

The Stage C brief offered two options for where to host the
downsampler + xLabels math: (A) on the snapshot type as pure
helpers, or (B) inside the repo impl. The IMPL takes a third
shape that aligns with both — `WeightHeroDisplay` is a new
`data class` in `core-data/bodycomposition/` with a
`from(snapshot, now)` factory that derives the lb-converted,
downsampled, padded chart inputs. The snapshot stays canonical kg
(no unit conversion, no display-only fields) while the display is
the lb-shaped view the hero card actually renders. Math lives in
pure companion functions so it test-isolates exactly like the
retired mapper did — `BodyCompositionHeroDisplayTest` mirrors
every assertion from the deleted `BodyCompositionMapperTest`.

Tradeoff: callers (only the dashboard hero today) need to import
`data.bodycomposition.WeightHeroDisplay` rather than calling a
method on the snapshot. Net win is the snapshot interface stays
unit-agnostic and the display can absorb future presentation-only
fields (e.g. delta-rendering tone, mini-sparkline shape) without
polluting the domain type.

### Note — 90-day delta sources from snapshot, 7-day delta re-derived locally

**Status:** informational.

`BodyCompositionSnapshot` already carries `sevenDayDeltaKg` and
`ninetyDayDeltaKg`. `WeightHeroDisplay.from(...)` consumes the
snapshot's 90-day delta as-is (kg → lb conversion only), but
**re-derives** the 7-day delta from `snapshot.series90d` using
the legacy mapper's "latest minus reading at-or-before now − 7d"
rule. Reason: the snapshot's 7d delta uses a slightly different
anchor convention (`maxByOrNull { sampleTime <= now - 7d }`) that
matches the legacy mapper exactly for the dense-data case but
diverges on sparse histories — re-deriving in the display keeps
the visual numbers byte-for-byte identical pre/post consolidation.
The `BodyCompositionHeroDisplayTest` pins both deltas.

If you'd rather collapse to a single delta source of truth, point
me at which convention to keep (snapshot's vs legacy mapper's) and
I'll align the snapshot mapper to match — the display can then
just convert through.

### Note — `DashboardViewModel` keeps both `init`-time collector and explicit `refresh()`

**Status:** informational.

The new repo exposes a hot replay-1 snapshot Flow + a separate
`refresh()` suspend. `DashboardViewModel.init` subscribes to the
flow once (the Loaded transition happens when emissions arrive);
`loadBodyComposition()` flips to Loading then calls `refresh()`.
Success path lets the in-flight collector flip back to Loaded —
the load function doesn't await the emission itself. That keeps
the retry semantics symmetric across the three cards
(blood/doses are still suspend-call-and-update) without forcing
the body card to await a second flow emission inside its
launch block. Trade-off: if `refresh()` succeeds but the
upstream flow never re-emits (a bug we don't have today), the
card would stay Loading. The integration test for that path lives
behind the repository impl, not the VM.

### Note — `DashboardApi.bodyComposition()` endpoint shim removed entirely

**Status:** informational.

The old `DashboardApi.bodyComposition()` Retrofit call and its
`BodyCompositionDto` were only consumed by the retired
`BodyCompositionMapper`. Both were dropped along with the mapper
(rather than left as dead code) — the canonical
`BodyCompositionApi.list()` in `data.bodycomposition/` now owns
the wire shape for that surface. `DashboardApiHttpTest` lost its
two body-composition cases for the same reason; the math layer
is covered by `BodyCompositionHeroDisplayTest` and the wire
layer is exercised by `BodyCompositionRepositoryImpl` in the
feature-body-composition integration paths.

### Note — Dashboard hero now shows an empty state for "no weight readings"

**Status:** informational.

Before consolidation, the `WeightSummary?` shape made
"backend returned no weights" indistinguishable from "card is
loading" at the type level — the card would stay in Loading
until the empty-list response came back. After consolidation,
the snapshot Flow always emits a `BodyCompositionSnapshot`
(possibly with `latestWeightKg = null`), so the card transitions
to `Loaded` even when the user has no readings yet. The hero
composable handles the empty case by checking
`WeightHeroDisplay.from(snapshot) == null` and rendering the
"No body-comp data yet" placeholder. Same behaviour for the
phone vitals row via `VitalFromWeight.weightVitalOrFallback`.
The Connect-Google-Health CTA proper still lands with
IMPL-AND-02.

---

## Round 2 — Stage D

### Note — `*Content` composables extracted so Paparazzi can render UiState directly

**Status:** informational.

`PhoneTodayScreen`, `FoldableDashboardScreen`, and
`TodaysDosesSection` previously combined "collect from VM" +
"render UI" in one composable. To Paparazzi-snapshot the
dashboard at specific `DashboardUiState` shapes without spinning
up Hilt, this stage extracts:

- `PhoneTodayContent(ui, onSeeAllDoses, dosesContent)`
- `FoldableDashboardContent(ui, onSeeAllDoses, onRetry*,
  dosesContent)`
- `TodaysDosesSectionContent(state, onSeeAll, onToggle)`

And `TodayCard` gains a `dosesContent: @Composable () -> Unit`
slot defaulting to `TodaysDosesSection` so the production wiring
is unchanged but tests can inject a stub doses card.

No production behaviour change. The existing `*Screen` /
`*Section` composables are the public surface; the `*Content`
variants are testing affordances mirroring the Stage B
`MoreScreenContent` precedent.

### BLOCKER (deferred) — LocationCard "with cover photo" snapshot

**Status:** deferred to a follow-up.

The Stage D brief asks for three LocationCard snapshots
including "with cover photo". `LocationCard` renders cover
photos through `HfAsyncImage` → Coil's `AsyncImage`, which
spawns a coroutine on `Dispatchers.Main` to decode the request.
Paparazzi's LayoutLib host has no Android main looper, so any
non-null `coverPhotoUrl` blows up with `IllegalStateException:
The main looper is not available` before producing an image.

This stage ships three production-reachable variants (no photo,
default badge, amenities row) and leaves the "with cover photo"
snapshot for a follow-up that adds `io.coil-kt:coil-test` and
installs a `FakeImageLoaderEngine` with a deterministic
`Bitmap`. Same path applies to MedicationCard's `DrugImage`
(SubcomposeAsyncImage); the smoke snapshot here uses
`imageUrl = null` + `imageFallback = null` to short-circuit
straight to the placeholder icon for the same reason.

If you'd rather wire `coil-test` up front, point me at it and
I'll fold in the FakeImageLoaderEngine snapshots — single-shot
addition once it's on the test classpath.

### Note — EditableNumberCell edit/saving snapshots done via wrapper, not focus events

**Status:** informational.

`EditableNumber`'s edit-mode transition is gated on a tap event
(`detectTapGestures` → `isEditing = true`), which Paparazzi
can't fire from a snapshot test. The Stage D coverage for
EditableNumberCell ships three read-mode variants — value,
empty placeholder, and `enabled = false` (the visual the parent
grid uses while a PATCH is in flight, standing in for "saving
mode"). The actual `BasicTextField` render and the "parse
failure reverts" behaviour stay covered by the underlying
EditableNumber's interaction tests when those land.

### Note — Foldable snapshot uses a wide LANDSCAPE device, not a real foldable config

**Status:** informational.

`DashboardScreenPaparazziTest.foldable_loaded` snapshots
`FoldableDashboardContent` at a 1840×1080 landscape device.
That's wide enough to push WindowSizeClass into Medium and the
foldable layout's two-column body, but it's not a "true"
foldable device config (Paparazzi 1.3.5 doesn't ship a
PIXEL_FOLD preset, and the LayoutLib presets that approximate
foldables — UNFOLDED variants — vary across releases). If you
want a more precise foldable shape, pass me the target screen
inches / px and I'll pin the snapshot device-config to it. The
current snapshot does its job — it exercises the same two-column
hero + side-by-side BloodPanel/TodayCard layout the production
foldable shows.

### Note — Paparazzi snapshots committed as PNGs, not text fixtures

**Status:** informational.

Total of 21 PNGs committed across the five wired feature modules
+ :app:
- :app — 4 (Dashboard phone × 3 + foldable × 1)
- :feature-blood — 3 (MarkerReferenceBar)
- :feature-body-composition — 4 (DexaRegionGrid × 1,
  EditableNumberCell × 3)
- :feature-medical — 1 (MedicationCard)
- :feature-workouts — 9 (LocationCard × 3, EquipmentSpecForm × 6)

Each PNG is ~3–80 KB. CI runs `verifyPaparazzi` and diffs
byte-for-byte; any rendering regression caused by a Compose
recomposition / token change shows up as a failed verify with
the actual vs golden diff in the Paparazzi HTML report.
