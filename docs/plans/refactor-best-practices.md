# Refactor & Best-Practices Audit (2026-06)

Cross-platform audit of `backend/`, `web/`, and `android/` aimed at reducing
accreted weight and standardizing on simpler, more idiomatic implementations.
Each platform was examined module-by-module.

## Headline

The "heavy and slow" feeling is **not** caused by dependency bloat or wrong tech
choices — the stacks are reasonable and mostly modern. The weight comes from
three recurring patterns:

1. **Copy-paste duplication** instead of shared helpers — the same code stamped
   into dozens of files.
2. **Over-abstraction** — layers/indirection that exist to paper over a problem
   better fixed at the source.
3. **Docs drifting from reality** — most acute on Android (see the persistence
   note, now reconciled).

## Implementation status (branch `claude/app-refactor-best-practices-7u6gy`)

Worked top-down through the tiers. Every change below is build-verified
(`./gradlew test`, `tsc --noEmit` + `eslint`) **except** Android, which has no
SDK in the execution environment and is verified by inspection only.

**Done**
- **Backend:** Firestore `await`+typed-exception helper; shared Gemini `Client`
  + GCS `Storage` beans; `google-api-client` catalog entry removed; dead
  `PlaceholderService` removed; `AdminCheckAspect` → config-driven
  `@PreAuthorize` (+ aop starter dropped); `GlobalExceptionHandler` test added;
  `api`-module `@WebMvcTest` slices added (Backend #6 — `WhoAmI`/`Device`/
  `BodyComposition` controllers, ×9 tests; the module previously had no
  bootstrap config, so a minimal `@SpringBootApplication` anchors the slices).
- **Web:** `proxySseStream`; `send<T>` consolidation; `<ModalBackdrop>` (14
  modals); `<PdfUploadDropzone>`; shared chat stream-consumer + primitives;
  type/date-helper consolidation; `app/page.tsx` blood-markers + daily-vitals
  extracted (977→839).
- **Android:** docs reconciled; empty `:feature-chat` removed; inert
  wear-tiles/complications pruned; `MainActivity` lifecycle-aware collection.

**Deliberately not done (with reasons recorded inline below)**
- Backend #3 (metric-cache collapse) — design trade-off, not a clean win.
- Backend #5 (app re-layering) — large multi-module move; warrants a dedicated PR.
- Backend #7 (drop per-repo `firestore-enabled`) — conditional is load-bearing.
- Backend #8 "dead Goals branches" — none found (read-path-resolvable).
- Web #6 (remove dashboard fixtures) — a product decision (what to show with no
  data), not a pure refactor.
- Android #2/#4/#5/#8 (build-logic plugin, concrete repos, Hilt auth, screen
  splits) — substantive Kotlin/Gradle changes that need a real build to verify.
- Backend #6 **persistence** mapper round-trips — need the Firestore emulator
  (the `api`-module half of #6 is now done; see "Done" above).

## Cross-cutting themes (appear on 2+ platforms)

| Theme | Backend | Web | Android |
|---|---|---|---|
| Duplicated HTTP/stream helper | `await(ApiFuture)` in 30 repos | `send<T>` in 4 lib files; 5 SSE proxy routes | — |
| Duplicated client/dep construction | ~12 Gemini + 8 Storage clients, no shared bean | — | ~13 near-identical Gradle dep blocks |
| Near-identical feature pairs | — | 2 chat clients (~85% dup), 2 upload buttons | two repo-access patterns split features |
| Hand-rolled where a primitive exists | string-matched errors vs typed exception | hand-rolled SSE parse vs `readSseStream`; modal backdrop ×16 | manual DI in `MainActivity` vs Hilt |
| Layering drift / stale docs | services+controllers misplaced in `app` | fixtures shipped to prod | sync stack vs "Room is dead" docs |

---

## Backend (Spring Boot 3.5 / Java 21)

Well-organized but heavy in three places: per-repository Firestore boilerplate,
integrations duplication, and muddled `app`-module boundaries. Test coverage is
badly skewed — `api` has **0** tests, `persistence` has **1**.

### Top recommendations (impact ÷ effort)

1. **Shared Firestore `await` + typed exception** `[low]` — one helper replaces the
   `await(ApiFuture)` copy in 30 repos (~300 lines) and lets
   `GlobalExceptionHandler` stop string-matching `getMessage()`. Throw a typed
   `FirestoreAccessException` and switch on the gRPC `StatusCode`.
2. **`@Bean Client geminiClient` + `@Bean Storage storage`** `[low-med]` — removes
   ~20 duplicated client constructions, centralizes API-key/model config, cuts
   memory/cold-start.
3. **Collapse the 3-layer metric caching to one** `[med]` — **investigated;
   needs design review, not a blind refactor.** The repeated `resolve()` spans
   two passes at *different* call sites: `StepEvaluationService.evaluateGoal`
   (eval) and the controller response mapper's `computeRegressionFlag` (read).
   `RequestScopedMetricCache` dedups across both passes *and across all goals in
   one request*. "Resolve once per goal" would require threading a shared memo
   through the controller↔service flow and would lose the cross-goal dedup, so
   it's a behavioral/perf trade-off rather than a clean simplification. The
   current `CachingMetricResolver` decorator (no-ops outside request scope for
   event/batch paths) + `@RequestScope` memo is a reasonable pattern; recommend
   deferring to a reviewed PR with a clear perf rationale.
4. ✅ **Replaced `AdminCheckAspect` + hardcoded emails with config + `@PreAuthorize`**
   — `@AdminOnly` is now a composed `@PreAuthorize("@adminAuthorizer.isAdmin()")`
   annotation; allowlist moved to `app.admin.emails` (`ADMIN_EMAILS` env); custom
   AOP aspect and `spring-boot-starter-aop` removed from `api`.
5. **Realign `app` with documented layering** `[med]` — controllers → `api`,
   services → `core`; unify the two package roots
   (`...healthfitness.app.*` vs `...healthfitness.*`).
6. ✅ **Backfilled `api` tests** `[med]` — `GlobalExceptionHandler` unit test plus
   `@WebMvcTest` slices for the `WhoAmI`/`Device`/`BodyComposition` controllers
   (request mapping, enum/`Instant` param binding, JSON serialization, and the
   `ResponseStatusException` → `GlobalExceptionHandler` 400 path). The module had
   no `@SpringBootConfiguration`, so `ApiTestApplication` (a minimal
   `@SpringBootApplication`) anchors the slices. **Still open:** mapper
   round-trip tests in `persistence` need the Firestore emulator.
7. **~~Drop per-repo `@ConditionalOnProperty(firestore-enabled)` from the 31 impls~~**
   `[don't]` — **investigated: not safe as written.** The impls are
   component-scanned `@Repository` classes that inject the `Firestore` bean.
   In tests `firestore-enabled=false` turns off *both* `FirestoreConfig` (so no
   `Firestore` bean) *and* the per-repo conditional, and `TestPersistenceConfig`
   supplies in-memory fakes. Removing the per-repo conditional would make the
   scanned classes instantiate with no `Firestore` to inject → context fails to
   start. The conditional is load-bearing, not redundant with `FirestoreConfig`'s
   gate. (Would only be possible by also moving the impls off component-scan to
   `@Bean` factories — a larger change, not this item.)
8. **Delete dead code** `[low]` — ✅ `PlaceholderService` (+ its test) removed;
   the `google-api-client` catalog entry removed. **Not removed:** `HelloService`/
   `HelloController`/`/api/hello` — despite "actuator covers liveness", `/api/hello`
   is the canonical permitAll probe in `SecurityConfigTest` and has its own
   `HelloEndpointTest`; removing it means repointing the security test at
   `/actuator/health` (whose status depends on health indicators in the test
   context), so it's a small change with test ripples, not pure dead code.
   **"Dead Goals metric branches" — investigated, none found:** every `MetricKey`
   has a `FirestoreMetricResolver` branch (one per constant). The ones with no
   `MetricChangedEvent` *writer* (vitals.*, workouts.*) are still resolved on the
   read path, so goals bound to them evaluate when viewed — functional, not dead.
   Removing them would drop the ability to set goals on those metrics.

---

## Web (Next.js 15 App Router)

Architecturally healthy: lean deps (no Redux/Zustand/chart lib), Server Components
as default, Suspense + `cache()`-deduped loaders avoiding waterfalls. The
heaviness is **code duplication and oversized files**, not tech.

### Top recommendations

1. **Extract a `useChatStream` hook + shared chat primitives** `[high]` — collapses
   `GoalsChat.tsx` (615 LOC) and `WorkoutProgramChat.tsx` (913 LOC), ~85%
   duplicated. ~600-line win.
2. **One `<PdfUploadDropzone>` using `readSseStream`** `[med]` — replaces the two
   ~400-line `BloodTestUploadButton`/`DexaUploadButton` copies and three
   hand-rolled SSE parsers.
3. **`proxySseStream()` helper in `lib/api.ts`** `[low]` — rewrites the 5 near-identical
   SSE route handlers (and collapses the 2 identical upload routes); removes
   header-drift risk.
4. **Promote `send<T>` into `lib/api.ts`** `[low]` — one definition instead of four
   (`goals-api`, `nutrition-api`, `workout-program-api`, `exercise-admin-api`).
5. **Decompose `app/page.tsx` (~990 LOC)** `[med]` — move blood-marker
   normalization/refs to `lib/blood-markers.ts`, vital builders to
   `lib/dashboard-vitals.ts`, reuse `lib/chart.ts` for the 3 bespoke sparkline funcs.
6. **Remove dashboard fixtures** `[med]` — wire `RecentFeed` + `TopBar` date to real
   data (or render nothing) and delete `lib/fixtures/dashboard.ts`; fake data is
   currently shipped to prod.
7. **Extract `components/ui/ModalBackdrop.tsx`** `[med]` — boilerplate is now in 16
   files; `web/CLAUDE.md`'s own threshold for extraction was 6.
8. **Consolidate duplicated types + date helpers** `[low]` — `WhoAmI`/`Reading`/`Session`
   into `lib/types/`, date helpers into one `lib/format-date.ts`.

> Not a problem: dependency tree is lean, no client-side state managers, Suspense/
> streaming used well. Focus on de-duplication, not removing deps.

---

## Android (Kotlin 2.0 / Compose)

Module structure is sound (clean core/feature split, curated version catalog,
lifecycle-aware collection nearly everywhere). Weight concentrates at the
build/architecture level, not in individual screens.

> **Offline-first sync stack — decision: KEEP.** The SQLCipher Room + outbox +
> `SyncEngine` + WorkManager + FCM stack in `core-data` is an intentional,
> ticketed feature (`docs/plans/IMPL-AND-20-offline-first-sync.md`). The docs
> claiming "Room is dead / DataStore only" were stale and have been corrected.

### Top recommendations

1. **Reconcile docs with reality** `[low]` — ✅ done in this pass
   (`android/CLAUDE.md` persistence section).
2. **`build-logic/` convention plugin** `[med]` — collapses ~13 near-identical
   dependency/plugin blocks copy-pasted across `feature-*` modules into one place;
   reduces per-module plugin overhead. Highest build-hygiene win.
3. **Delete empty placeholder modules** `[low]` — `feature-chat` is only a
   `.gitkeep` (chat lives in `core-chat`, consumed by `feature-goals`); remove
   `:feature-chat` from `settings.gradle.kts`. `core-health` is also empty —
   either populate it with the Health Connect gateway it's documented to be, or
   remove it + the `health-connect` catalog entry until the feature lands.
4. **Standardize on concrete `@Inject` repositories** `[med]` — goals/nutrition
   already inject concrete classes; apply the same to blood/body/medical/profile/
   workouts, deleting ~10 single-implementation `core-domain` interfaces and their
   `@Binds`. MockK mocks concrete classes fine.
5. **Move auth wiring into Hilt** `[low]` — replace the manual
   `IdTokenCache`/`GoogleAuthRepository`/`AuthCoordinator` construction in
   `MainActivity` with injection (a `@Module` already exists).
6. **Fix non-lifecycle state collection** `[low]` — `MainActivity`'s
   `coordinator.state.collectAsState()` is the lone non-lifecycle collection;
   switch to `collectAsStateWithLifecycle()`.
7. **Prune speculative deps** `[low]` — drop `wear-tiles` + `wear-complications`
   (declared "inert"); confirm `mlkit-text-recognition` is actually used in
   `feature-nutrition` and drop if not (bundled model = APK weight).
8. **Break up 450–570 LOC screens** `[med]` — `AddMedicationScreen` (567),
   `NutritionTodayScreen` (518), `NutritionCaptureScreen` (507 — hoist
   camera/capture side-effects into its ViewModel), `MedicationDetailScreen` (513).
   Lowest urgency; the rest of the Compose layer is healthy.

---

## Suggested sequencing

**Tier 1 — safe quick wins (low effort, isolated):** backend dead-code +
catalog cleanup; web `proxySseStream` + `send<T>` promotion; Android doc fix +
inert-dep pruning + `collectAsStateWithLifecycle` fix.

**Tier 2 — shared-helper extractions (mechanical, build-verified):** backend
Firestore `await`/typed-exception helper + shared Gemini/Storage beans; web
`ModalBackdrop` + type/date-helper consolidation; Android `build-logic`
convention plugin.

**Tier 3 — structural (needs review):** backend metric-cache collapse + `app`
re-layering; web `useChatStream` + `<PdfUploadDropzone>` + `app/page.tsx`
decomposition; Android repository-pattern standardization + empty-module removal.
