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

**Status:** open — please review.

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

**Status:** informational, no action needed.

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

**Status:** open — flag forwarded to the backend blood-testing work.

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

**Status:** open — please advise.

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

**Status:** informational, please review.

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

**Status:** open — please advise.

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

**Status:** open — please advise.

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

(no questions yet)

---

## Cross-cutting

(no items yet)
