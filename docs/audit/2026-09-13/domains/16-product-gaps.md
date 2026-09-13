# D16 — Feature & Product Gaps (PROD)

Auditor: D16 product-gaps. Date: 2026-09-13. Scope: half-shipped inventory, dead/unused surface, core-journey friction, architecture-resistance, table-stakes vs category. All code claims verified in this worktree; market claims cite fetched URLs or are tagged [Guessing].

## BLUF

The product is unusually *complete* for a solo build — the feature catalog is honest, the dashboard is nearly all-live, and the "dead code" sweep found almost no deadwood (website, UAT, TRT, thumbnails fn are all real and current). The two product problems that matter today are **(1) every shipped push-delivered UX moment is silently degraded or dead** because FCM delivery is broken in prod — async Adjust/Leftover "review ready" notifications never arrive (6-hour periodic-sync floor is the only recovery) and Google Health/Withings reconnect alerts are fully silent — and **(2) user #2 does not exist as a designed experience**: signup is open, there is zero onboarding on either client, and a fresh account lands on error-styled empty states ("Couldn't load today's nutrition") with placeholder privacy/terms URLs behind the About screen. Table-stakes-wise, the biggest value-per-effort wins sit on existing rails: live barcode scanning (backend `GET /api/foods/barcode/{code}` already exists; no client scanner), a PRs/records card (e1RM engine already user-visible), and web photo logging. The architecture actively resists social/coach features, iOS, and cross-domain analytics — worth knowing before promising any of them.

---

## Half-shipped inventory

| Feature | State | Evidence | Recommendation |
|---|---|---|---|
| Async review notifications (Adjust-with-AI, Leftovers) | Shipped but delivery rail dead in prod; content arrives via 6h periodic sync only, notification lost | `backend/src/main/java/com/gte619n/healthfitness/api/nutrition/AdjustReviewNotifier.java:44`, `LeftoverReviewNotifier.java:42`; fallback `android/core-data/src/main/java/com/gte619n/healthfitness/data/sync/SyncWorkers.kt:126` (6h `PeriodicSyncWorker`) | **Finish** (ops fix: register signing-cert SHA fingerprints with Firebase; then verify end-to-end) |
| GH/Withings reconnect alerts | Shipped, **completely broken** — FCM-only, no in-app fallback surface | `backend/.../api/googlehealth/GoogleHealthReconnectNotifier.java:37`, `api/withings/WithingsReconnectNotifier.java:36`; only handler `android/app/.../mobile/push/HfMessagingService.kt:79` | **Finish**: add an in-app "connection broken" banner driven by status endpoint, independent of push |
| Withings integration | Merged to main; web connect UI reachable; all secrets env-empty → fails if clicked without prod config; no Android UI | `backend/src/main/resources/application.yml:175-194` (`WITHINGS_CLIENT_ID:` empty default), `web/app/me/profile/page.tsx:262-326` | **Feature-flag off (hide the button) until prod secrets + partner app are wired**, then finish |
| Wear OS module | Sign-in relay + literal "Signed in" hello screen; `health.services.client` dep declared, unused | `android/wear/src/main/java/com/gte619n/healthfitness/wear/MainActivity.kt:19-44`, `android/wear/build.gradle.kts` | **Mothball** (drop from release build + remove dead dep) or commit to Phase-8 glance tile; shipping a hello-world watch app is negative value |
| Dashboard Readiness tile | Fixture on Android (`value="—"`), absent on web; 2 of 3 `DashboardFlags` are dead code | `android/app/.../mobile/dashboard/Fallbacks.kt:165-171`; dead flags at `Fallbacks.kt:114,120` | **Delete** tile + dead flags, or finish (sleep/HRV/RHR inputs already live via `/api/me/daily-metrics`) |
| Settings About links | `https://placeholder.tesseta.app/privacy` & `/terms` | `android/feature-settings/.../about/AboutSection.kt:49-50` | **Finish** — real privacy/terms pages on website/; trivial, and open-signup health app without a privacy policy is a trust/compliance hole |
| Exercise demo videos (Veo, ADR-0009) | PoC only: generation code + 5 sample MP4s in docs/test_reports; `Exercise.videoUrl` reserved null; no UI, no backfill job | `backend/.../integrations/exercise/` (~1.5KL), `docs/test_reports/workout_logs/media_preview/*.mp4`, ADR-0009 | **Keep as-is** (well-gated experiment) — explicitly deferred, not half-shipped to users |
| Web meal logging | Manual search/entry only — no photo capture, no offline/PWA | `web/app/me/nutrition/page.tsx:137-153` (foodId/foodName entry actions); no service worker/manifest anywhere in web/ | **Finish** photo upload (backend capture endpoints already exist); accept offline as Android-only |
| Owner-gated flag-demo-frame | Works, but hardcoded to founder's two emails on both sides | `backend/.../api/exercise/ExerciseController.java:114-115`, `android/feature-workouts/.../session/WorkoutSessionViewModel.kt:508` | **Finish** — fold into the existing admin-email mechanism (`app.admin.emails`) |
| isAdmin web allow-list | `admin@example.com` + founder email baked into web bundle (backend is env-driven, fail-closed) | `web/lib/admin.ts:7-10` vs `backend/src/main/resources/application.yml:85-90` | **Finish** — drop built-ins, env-only like backend |
| Dark mode | Absent on both platforms (no `dark:` classes, no `darkColorScheme`) | negative sweep of web/ and android/ | **Feature-flag off is the current state; fine** — deprioritize consciously (parity roadmap Phase-9 stretch) |

Fixture-row verification: 3 catalog claims spot-checked (Readiness fixture, dead DashboardFlags, Wear sign-in-only) — all accurate; the feature-catalog doc is trustworthy. [Certain]

## Dead/unused surface verdicts

- **website/** — live static marketing site (Firebase Hosting, tesseta.com), content matches current product, last touched 2026-07-29 (publishes the /v1 OpenAPI spec). Keep. [Certain]
- **TRT decision support (ADR-0015)** — genuinely shipped and reachable: `backend/.../core/trt/` (~5KL incl. `TrtAdvisorContextService.java`), `web/components/workouts/TrtPanel.tsx`, Android `TrtContext.kt`. Not dead. [Certain]
- **Third-party OAuth platform (ADR-0020)** — fully built (~8KL: `backend/.../api/platform/`, `oauthClients`/`oauthGrants` stores, consent page, admin registration UI at `web/app/admin/oauth/page.tsx`, published OpenAPI) with **zero evidence of any registered client**. This is launch-ready shelfware: real maintenance + attack surface (a second token family, per-route scope enforcement) serving no user. See PROD-008.
- **uat/** — 10-flow Selenium E2E harness against emulator stack; high product value as the only journey-level regression net. Keep. [Certain]
- **functions/exercise-thumbnails** — small, deployed, operational. Keep. [Certain]

## Core-journey friction (from code)

- **Android meal log**: bottom-nav "Log" button goes straight to capture (`android/app/.../dashboard/PhoneTodayScreen.kt:66` → `Routes.NUTRITION_CAPTURE`) — ~3 interactions to a confirmed photo log (Log → shutter → confirm). This journey is genuinely good; no launcher shortcut/QS tile, but marginal. [Certain]
- **Workout process death**: fully protected — every set edit persists to Room; draft resumes via `repository.peekDraft`/`observeDraft` (`android/feature-workouts/.../session/WorkoutSessionViewModel.kt:159-192`). Not a gap. [Certain]
- **First run (user #2)**: open signup (Google OAuth, no allow-list — `application.yml:64-90`), **no onboarding route on either client** (Android's only first-run surface is a "Setting up…" sync gate, `MainActivity.kt:322`). Fresh web dashboard renders *error-styled* strings for empty data: "Couldn't load today's nutrition." (`web/components/.../NutritionCard.tsx:61`), "Couldn't load your workouts." (`WorkoutCard.tsx:40`), em-dash vitals. Android workouts hub does this right ("Design your first program" CTA, `WorkoutsLandingScreen.kt:168-181`); web nutrition has a decent target prompt (`web/app/me/nutrition/page.tsx:335-348`). The pattern exists — it's just unevenly applied. [Certain]

---

## Findings

### PROD-001 — Every push-delivered product moment is degraded or dead in prod; two flows have no fallback at all
- severity: high · confidence: [Certain] on code dependence; [Likely] on prod FCM state (from ops context, not repo)
- evidence: senders `AdjustReviewNotifier.java:44`, `LeftoverReviewNotifier.java:42`, `GoogleHealthReconnectNotifier.java:37`, `WithingsReconnectNotifier.java:36`, `SyncChangePublisher.java:69`; sole receiver `android/app/.../push/HfMessagingService.kt:62-110`; only fallback = 6h `PeriodicSyncWorker` (`SyncWorkers.kt:126`). Reconnect alerts have **no** non-push surface.
- impact: async Adjust/Leftover reviews (the marquee nutrition UX of #244/#245) silently lose their "ready — Apply" moment; cross-device sync degrades to 6h staleness; a broken Google Health/Withings connection is invisible until the user stumbles on it. #246 added loud *logs*, not user-facing recovery.
- effort_days: 0.5 ops (Firebase SHA registration) + 2 for an in-app reconnect banner off the status endpoints
- autonomy: banner=high; Firebase console=low (needs operator)
- null_option_cost: the product's most differentiated flows feel broken/laggy to every future user; wearable data silently rots on token expiry
- prompt: "Add an in-app 'connection needs attention' banner on Android dashboard + web profile driven by /api/me/googlehealth/status and /api/me/withings/status (not push). Separately: register prod signing-cert SHA-1/256 in Firebase console and E2E-verify one adjust-review push."

### PROD-002 — User #2 has no designed first-run: open signup, zero onboarding, error-styled empty dashboards
- severity: high · confidence: [Certain]
- evidence: no onboarding routes (negative sweep); `MainActivity.kt:322` (sync gate only); "Couldn't load today's nutrition." `NutritionCard.tsx:61`; "Couldn't load your workouts." `WorkoutCard.tsx:40`; contrast good pattern `WorkoutsLandingScreen.kt:168-181`
- impact: any acquired user bounces — the empty dashboard reads as a broken app, and nothing routes them to the three activation moments (log a meal, set targets, design a program)
- effort_days: 3–5 (distinguish empty-vs-error states, add CTA empty cards reusing the Android EmptyState pattern; no new backend)
- autonomy: high
- null_option_cost: zero conversion from any future marketing; website/ traffic is wasted
- prompt: "On web dashboard, split 'no data yet' from 'fetch failed' in NutritionCard/WorkoutCard/RecentFeed and render CTA empty states (Log your first meal → /me/nutrition, Design a program → /me/workouts/programs/chat, Connect a scale → /me/profile). Mirror on Android PhoneTodayScreen fallbacks."

### PROD-003 — No live barcode scanning; backend lookup endpoint already exists
- severity: medium · confidence: [Certain] absence; market claim grounded
- evidence: `GET /api/foods/barcode/{code}` + `BarcodeLookup` port + `OpenFoodFactsClient`/`DumpParser` (backend); zero ML Kit/zxing/camera-scanner hits in android/; current path is photo-of-barcode → Gemini
- impact: table stakes — MacroFactor leads with barcode scan among logging methods (https://macrofactor.com/); Gemini round-trip is slower/costlier than an on-device decode + catalog hit
- effort_days: 5–8 (ML Kit BarcodeScanning in the existing capture screen → call existing endpoint → existing confirm pane)
- autonomy: high
- null_option_cost: every packaged-food log pays AI latency+cost for what a free on-device decoder does instantly
- prompt: "Add ML Kit barcode scanning as a mode in NutritionCaptureScreen: on decode, call GET /api/foods/barcode/{code}; on hit show existing BarcodeFoodPane; on miss fall back to current photo→Gemini path."

### PROD-004 — Web can't log a meal by photo (and has no offline)
- severity: medium · confidence: [Certain]
- evidence: `web/app/me/nutrition/page.tsx:137-153` (name/foodId entry only); backend capture endpoints exist and serve Android; no service worker/PWA manifest in web/
- impact: flagship capability is Android-only; desktop/iPhone-browser users get the weakest version of the core loop (relevant given no iOS app)
- effort_days: 3–5 for photo upload reusing backend capture + describe-async; skip offline (accept as native-only)
- autonomy: high
- null_option_cost: iPhone users (browser is their only door) never see the product's differentiator
- prompt: "Add a photo-upload meal capture to web /me/nutrition using the existing backend capture/describe-async endpoints and the async review pattern from Android; file input + mobile camera capture attribute, no PWA work."

### PROD-005 — No user data export/import of any kind
- severity: medium · confidence: [Certain] absence; market comparison fetched
- evidence: only import surface is admin bulk-equipment CSV (`docs/reference/api-surface.md:118-127`); no export/takeout endpoint anywhere in backend api
- impact: table stakes for trust in a *health-data* product — Hevy exposes a public API/export (https://www.hevyapp.com/features/), MacroFactor advertises export (https://macrofactor.com/); also the practical answer to data-portability requests; no migration path *in* from MyFitnessPal/Strong either
- effort_days: 8–12 (JSON takeout of the per-user tree via async job + signed URL)
- autonomy: high
- null_option_cost: adoption objection ("can I get my data out?") unanswerable; regulatory-request handling is manual Firestore surgery
- prompt: "Add GET /api/me/export → async Cloud Tasks job serializing the user's Firestore tree to JSON in GCS with a signed URL; surface under web Settings. Reuse the nutrition-jobs task rail."

### PROD-006 — Wear OS app is a shipped hello-world
- severity: low · confidence: [Certain]
- evidence: `android/wear/.../MainActivity.kt:19-44` ("tesseta / Signed in"); unused `health.services.client` dep in `android/wear/build.gradle.kts`
- impact: maintenance + build surface for zero user value; sets wrong expectations if ever installed
- effort_days: 0.5 to mothball (exclude from release + drop dep); ~10+ to do Phase 8 properly
- autonomy: high (mothball)
- null_option_cost: ongoing dep/build upkeep on a module no one can use
- prompt: "Exclude :wear from release assembly and remove the unused health.services.client dependency; leave the module in-tree with a README pointing at Phase 8."

### PROD-007 — Placeholder privacy/terms URLs behind About, with signup open
- severity: medium · confidence: [Certain]
- evidence: `android/feature-settings/.../about/AboutSection.kt:49-50` (`placeholder.tesseta.app/privacy|terms`)
- impact: health app collecting labs/meds/body data with no reachable privacy policy — Play Store listing requires one, and it's a trust breaker; website/ is the natural host and already deployed
- effort_days: 1
- autonomy: medium (policy text needs founder sign-off)
- null_option_cost: blocks any store distribution; legal exposure grows with every real user
- prompt: "Draft privacy + terms pages for website/public/, deploy, and point AboutSection.kt at https://tesseta.com/privacy|/terms."

### PROD-008 — OAuth platform API (~8KL) has zero consumers: launch-ready shelfware with real attack surface
- severity: low · confidence: [Certain] on zero in-repo registrations; [Likely] zero prod registrations (registration is admin-manual, no seeds)
- evidence: `backend/.../api/platform/` (6 controllers), `FirestoreOAuthClientStore`/`GrantStore`, `web/app/admin/oauth/page.tsx`, consent page, published spec `website/public/api/tesseta-platform-v1.yaml`; no seed/fixture client anywhere
- impact: a second token family + authz server is prime attack surface (D2's problem) and CVE/maintenance load (Trivy gate already bites deploys) serving nobody
- effort_days: 1 to mothball behind a single `platform.enabled` flag defaulting off
- autonomy: high
- null_option_cost: perpetual security review burden on unused endpoints; keeps eating deploy-gate incidents
- prompt: "Add app.platform.enabled=false gating /oauth/**, /v1/**, and the admin OAuth page; document one-line re-enable. Do not delete."

### PROD-009 — Readiness fixture tile + dead DashboardFlags linger post-cleanup
- severity: low · confidence: [Certain]
- evidence: `Fallbacks.kt:165-171` (em-dash Readiness), dead `showVitalsFixtures`/`showTodayCardFixtures` `Fallbacks.kt:114,120`
- impact: a permanently blank tile on the home screen reads as breakage; inputs for a real readiness score (sleep, HRV, RHR) are already live via `/api/me/daily-metrics` — whoop/oura made this the product [Guessing on market framing]
- effort_days: 0.5 to delete; ~5 to compute a simple readiness composite server-side
- autonomy: high
- null_option_cost: daily-glance surface permanently shows a dead tile
- prompt: "Either remove the Readiness vital from DashboardFallbacks and delete the two dead DashboardFlags, or add a backend readiness composite off existing daily-metrics and wire both clients."

### PROD-010 — PRs/records surface missing despite shipped e1RM engine
- severity: low · confidence: [Certain]
- evidence: `backend/.../api/progression/ProgressionController.java:51-58` (per-lift e1RM/trend live), `web/app/me/workouts/progression/page.tsx`; no PR/record card anywhere
- impact: PR notifications/records are the emotional hook of every workout tracker (Hevy: live PR notifications, records — https://www.hevyapp.com/features/); the data is already computed, only the celebratory surface is missing
- effort_days: 1–2
- autonomy: high
- null_option_cost: guided-logging sessions end with no payoff moment
- prompt: "Add a PRs card (per-lift best e1RM + date, 'new PR' badge on session summary) to the progression console and Android session-complete screen, reading the existing /api/me/progression/strength payload."

### PROD-011 — Withings connect button live on web with unset prod secrets
- severity: low · confidence: [Certain] code; [Likely] prod-unset (env defaults empty; memory notes prerequisites not wired)
- evidence: `application.yml:175-194` (all `WITHINGS_*` default empty), reachable UI `web/app/me/profile/page.tsx:262-326`
- impact: visible button that errors on click for any user until partner-app/secrets/redirect-URI are configured
- effort_days: 0.5 (hide when backend reports unconfigured) or ops-wire the secrets
- autonomy: high (gating); low (Withings partner setup)
- null_option_cost: another "app is broken" signal on the profile page
- prompt: "Have /api/me/withings/status report configured:false when client-id is blank and hide the profile section (or show 'coming soon') in that case."

### PROD-012 — Founder-email feature gates block anyone else from the demo-frame QA loop
- severity: low · confidence: [Certain]
- evidence: `ExerciseController.java:114-115`, `WorkoutSessionViewModel.kt:508`
- impact: exercise-media quality control is structurally single-person; also a client-side gate on Android is decorative (backend gate is the real one — fine, but duplicated hardcode drifts)
- effort_days: 1
- autonomy: high
- null_option_cost: none today (single user); becomes a papercut with any second admin
- prompt: "Replace both OWNER_EMAILS lists with the backend app.admin.emails mechanism; expose an isOwner flag via /me profile for the Android affordance."

---

## Architecture-resistance table

| Plausible next feature | Verdict | One-line architectural reason (evidence) |
|---|---|---|
| Social / sharing | **Hard** | Everything is `users/{uid}/...`; `SecurityContextCurrentUserProvider.java:22-45` yields uid-only identity — no role/org/share entity exists anywhere |
| Coach / trainer multi-tenancy | **Hard** | Same: no role model, 15+ repositories all scope to `currentUser.userId()`; cross-user read paths would touch every repo |
| iOS client | **Hard** | No KMP (`android/build.gradle.kts` — Android plugins only); all offline-first logic (Room+SQLCipher SyncEngine, outbox) is platform-locked; backend REST/SSE is iOS-ready, clients are not |
| Cross-domain trend analytics | **Hard** | No warehouse/BQ export/ETL anywhere; charts are per-request Firestore reads computed client-side (`web/lib/chart.ts`, `workout-dashboard.ts`) |
| Another wearable (Oura/Garmin) | **Medium/Near** | Withings proved the pattern at ~500 LOC (vs Google Health ~1.4K): OAuth client + mapper + webhook, hand-rolled but well-templated — ~2-3 wks each |
| Meal-plan generation | **Near** | All rails exist: `MacroTargetService`, food catalog search, Gemini tool-use pattern in `GeminiWorkoutProgramChatClient` — ~1 wk of wiring |
| Data export/takeout | **Medium** | No rails, but per-user tree makes serialization trivially scoped; async-job rail (Cloud Tasks) already exists |
| Live barcode scan | **Near** | `GET /api/foods/barcode/{code}` + OpenFoodFacts already live; only the on-device decoder is missing |
| PR/records surface | **Near** | ProgressionEngine e1RM already computed and served |

## Table-stakes gaps ranked (user value × proximity)

1. Barcode scan (PROD-003) — MacroFactor table stakes (fetched), backend done.
2. PR celebration/records (PROD-010) — Hevy table stakes (fetched), 1-2 days.
3. First-run/onboarding empty states (PROD-002) — precondition to any growth.
4. Data export (PROD-005) — trust stakes for health data; both comparators have it.
5. Web photo logging (PROD-004) — only door for iPhone users.
6. Apple Health / iOS — absent by architecture (Hard); a strategic decision, not a backlog item. [Guessing] that iPhone share of the fitness-app market makes this the largest long-term ceiling.
7. Readiness/recovery score (PROD-009) — inputs live; whoop/oura category framing [Guessing].
8. Recipe builder — partially covered by SavedMeal + describe-a-meal rails; low urgency.

## HYPOTHESES

- H1 [Likely]: The OAuth platform (ADR-0020) was built as portfolio/optionality, not demand-driven — zero registration seeds and admin-manual onboarding suggest no committed consumer. Mothballing behind a flag loses nothing.
- H2 [Guessing]: Browser-on-iPhone is (or will be) the de-facto iOS strategy; if so, web photo capture (PROD-004) is quietly the highest-leverage parity item on this list.
- H3 [Likely]: Fixing FCM (ops-only) + CTA empty states + barcode + PR card ≈ two weeks of work that moves the product from "impressive demo for one user" to "handable to a friend" — nothing on that path requires new architecture.
- H4 [Guessing]: If coach/trainer sharing is ever the monetization path, the uid==self assumption is the single most expensive thing in the codebase to unwind; deciding this early is worth more than any feature on the backlog.

```json
[
  {"id":"PROD-001","title":"Push-delivered product moments degraded/dead in prod; reconnect alerts have no fallback","severity":"high","confidence":"certain-code/likely-prod","evidence":["backend/src/main/java/com/gte619n/healthfitness/api/nutrition/AdjustReviewNotifier.java:44","backend/src/main/java/com/gte619n/healthfitness/api/googlehealth/GoogleHealthReconnectNotifier.java:37","android/core-data/src/main/java/com/gte619n/healthfitness/data/sync/SyncWorkers.kt:126"],"impact":"Async adjust/leftover 'ready' notifications lost (6h sync floor); GH/Withings disconnects invisible","effort_days":2.5,"autonomy":"medium","null_option_cost":"Differentiator flows feel broken to every future user; wearable data silently rots","prompt":"Add non-push connection-broken banners off status endpoints; register signing-cert SHAs in Firebase and E2E-verify one push."},
  {"id":"PROD-002","title":"No first-run experience: open signup, zero onboarding, error-styled empty dashboards","severity":"high","confidence":"certain","evidence":["web/components/dashboard/NutritionCard.tsx:61","web/components/dashboard/WorkoutCard.tsx:40","android/app/src/main/java/com/gte619n/healthfitness/mobile/MainActivity.kt:322"],"impact":"User #2 sees a broken-looking app with no activation path","effort_days":4,"autonomy":"high","null_option_cost":"Zero conversion from any acquisition; marketing site traffic wasted","prompt":"Split empty-vs-error states on web dashboard cards; add CTA empty states on both clients reusing Android EmptyState pattern."},
  {"id":"PROD-003","title":"No live barcode scanning despite existing backend barcode endpoint","severity":"medium","confidence":"certain","evidence":["docs/reference/api-surface.md:116","backend/src/main/java/com/gte619n/healthfitness/core/nutrition/BarcodeLookup.java"],"impact":"Packaged-food logging pays Gemini latency/cost for what an on-device decoder does free","effort_days":6,"autonomy":"high","null_option_cost":"Table-stakes gap vs MacroFactor (fetched); slower core loop","prompt":"Add ML Kit barcode mode to NutritionCaptureScreen calling GET /api/foods/barcode/{code}, fallback to photo path."},
  {"id":"PROD-004","title":"Web cannot log a meal by photo; no offline","severity":"medium","confidence":"certain","evidence":["web/app/me/nutrition/page.tsx:137-153"],"impact":"Flagship capability Android-only; iPhone-browser users get the weakest core loop","effort_days":4,"autonomy":"high","null_option_cost":"iOS-less product also cripples its only iPhone-accessible surface","prompt":"Add photo-upload capture to web nutrition reusing backend capture/describe-async endpoints."},
  {"id":"PROD-005","title":"No user data export or third-party import","severity":"medium","confidence":"certain","evidence":["docs/reference/api-surface.md:118-127"],"impact":"Health-data trust/portability objection unanswerable; comparators (Hevy, MacroFactor) advertise export","effort_days":10,"autonomy":"high","null_option_cost":"Data requests require manual Firestore surgery; adoption objection stands","prompt":"Async JSON takeout job (Cloud Tasks + GCS signed URL) surfaced in web settings."},
  {"id":"PROD-006","title":"Wear OS app is a shipped hello-world","severity":"low","confidence":"certain","evidence":["android/wear/src/main/java/com/gte619n/healthfitness/wear/MainActivity.kt:19-44"],"impact":"Build/dep maintenance for zero user value","effort_days":0.5,"autonomy":"high","null_option_cost":"Ongoing upkeep of an unusable module","prompt":"Exclude :wear from release, drop unused health.services.client dep."},
  {"id":"PROD-007","title":"Placeholder privacy/terms URLs while signup is open","severity":"medium","confidence":"certain","evidence":["android/feature-settings/src/main/java/com/gte619n/healthfitness/feature/settings/about/AboutSection.kt:49-50"],"impact":"Health app with no privacy policy; blocks store distribution","effort_days":1,"autonomy":"medium","null_option_cost":"Legal/trust exposure grows with every user","prompt":"Publish privacy+terms on website/, point AboutSection at real URLs."},
  {"id":"PROD-008","title":"OAuth platform API (~8KL) has zero consumers — mothball behind a flag","severity":"low","confidence":"certain-repo/likely-prod","evidence":["backend/src/main/java/com/gte619n/healthfitness/api/platform/","web/app/admin/oauth/page.tsx"],"impact":"Second token family + authz server as pure attack/maintenance surface","effort_days":1,"autonomy":"high","null_option_cost":"Perpetual security-review and CVE-gate burden on unused endpoints","prompt":"Gate /oauth/**, /v1/**, admin OAuth page behind app.platform.enabled=false."},
  {"id":"PROD-009","title":"Readiness fixture tile + dead DashboardFlags linger","severity":"low","confidence":"certain","evidence":["android/app/src/main/java/com/gte619n/healthfitness/mobile/dashboard/Fallbacks.kt:114","android/app/src/main/java/com/gte619n/healthfitness/mobile/dashboard/Fallbacks.kt:165-171"],"impact":"Permanently blank home tile reads as breakage","effort_days":0.5,"autonomy":"high","null_option_cost":"Daily-glance surface shows a dead tile forever","prompt":"Delete Readiness fixture + dead flags, or compute a readiness composite off live daily-metrics."},
  {"id":"PROD-010","title":"No PRs/records surface despite shipped e1RM engine","severity":"low","confidence":"certain","evidence":["backend/src/main/java/com/gte619n/healthfitness/api/progression/ProgressionController.java:51-58"],"impact":"Sessions end without the category's emotional payoff (Hevy table stakes, fetched)","effort_days":2,"autonomy":"high","null_option_cost":"Retention hook left on the table with data already computed","prompt":"Add PR card + session-complete 'new PR' badge off /api/me/progression/strength."},
  {"id":"PROD-011","title":"Withings connect button reachable with unset prod secrets","severity":"low","confidence":"certain-code/likely-prod","evidence":["backend/src/main/resources/application.yml:175-194","web/app/me/profile/page.tsx:262-326"],"impact":"Visible button errors on click until partner app + secrets wired","effort_days":0.5,"autonomy":"high","null_option_cost":"Another broken-app signal on profile page","prompt":"Report configured:false from withings/status when client-id blank; hide section."},
  {"id":"PROD-012","title":"Founder-email hardcode gates the demo-frame QA loop on both platforms","severity":"low","confidence":"certain","evidence":["backend/src/main/java/com/gte619n/healthfitness/api/exercise/ExerciseController.java:114-115","android/feature-workouts/src/main/java/com/gte619n/healthfitness/feature/workouts/session/WorkoutSessionViewModel.kt:508"],"impact":"Media QA structurally single-person; duplicated hardcodes drift","effort_days":1,"autonomy":"high","null_option_cost":"None today; papercut with any second admin","prompt":"Fold OWNER_EMAILS into app.admin.emails; expose isOwner via /me."}
]
```
