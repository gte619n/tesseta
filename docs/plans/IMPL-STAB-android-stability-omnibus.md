# IMPL-STAB — Android Stability & Feature-Completion Omnibus

Status: **Plan / not started** · Owner: TBD · Created: 2026-06-15

This plan addresses a batch of reported issues spanning the exercise library,
Android offline-first launch, reactive UI updates, sync reliability, media
loading, medication reminders, and workouts. It is the result of a code-level
investigation; each item lists the **diagnosed root cause**, the **fix**, and
**how to verify**.

The issues fall into two buckets:
- **Operational** (no app code change): the exercise library and some media just
  need the existing pipeline *run*.
- **Code** (Android-heavy, some backend): launch/auth, reactivity, sync error
  handling, reminders firing, workout flows.

---

## Workstream A — Exercise library: activate + media (OPERATIONAL, do first)

**Reported:** "Exercises have no media, and some are not activated."

**Diagnosis (not a code bug — the pipeline was never run).** IMPL-14 is fully
built: 30 seed exercises (`ExerciseSeedData.java`), a Gemini media pipeline
(`GeminiExerciseMediaService`, `gemini-3.1-flash-image-preview`), a Cloud Run
backfill job (`BackfillExerciseMediaJob`), and a full web admin
review/catalog UI (`/admin/exercises`). The public API filters to
`status=PUBLISHED` **and** `mediaStatus=APPROVED` because
`app.exercises.require-approved-media=true` (`application.yml`). Today: media
generated/approved ≈ 0, so the user-facing library is empty.

**Fix — run the existing pipeline:**
1. `POST /api/admin/exercises/seed` (idempotent) → 30 exercises, `PUBLISHED`,
   `mediaStatus=NONE`.
2. Deploy + run the backfill job:
   `bash infra/scripts/deploy-exercise-media-job.sh` then
   `gcloud run jobs execute … --wait` (batches of ≤50). Repeat until no `NONE`
   remain. Exercises land `NEEDS_REVIEW`.
3. Review + approve at `/admin/exercises/review` (regenerate any anatomically
   wrong frames first).

**Verify:** `GET /api/exercises` returns approved exercises with `demoFrames`;
Android workout-day cards render frames (`WorkoutDayComponents.kt`).

**Note / small code follow-up (optional):** add a bulk **batch-approve** action
to the review UI so 30+ exercises don't have to be approved one-by-one (IMPL-14
already lists an AI bulk-import endpoint as future work).

---

## Workstream B — Observability first: stop swallowing errors (do before C–G)

**Reported:** "When there's a server error, is it persisted down to the app and
shown? It feels swallowed — a blank no-op."

This is the **highest-leverage** fix because it makes every other Android issue
diagnosable. Confirmed swallow points:

- `SyncWorkers.kt` — all three workers do `runCatching { … }.fold(success, { Result.retry() })`
  with **no logging** and no transient-vs-permanent distinction.
- `OutboxRepository.kt:153-166` — replay failures update row state only; the
  `Throwable` is never logged or surfaced.
- Repository `refresh()` callers wrap in `runCatching { … }` and drop the error
  (e.g. `DashboardBodyCompositionRepositoryImpl`, blood/nutrition refreshes).
- `SyncStatusViewModel.retry()` — `runCatching { outbox.rearmFailed() }` swallows.

**Fix:**
1. Add structured logging (Timber or equivalent) at every catch in the sync path
   (worker, outbox replay, repo refresh) — log HTTP code, entity, mutationId.
2. Capture the **last sync error** in a small store and surface it: the sync
   banner's detail should show *why* (e.g. "Server rejected 3 changes (422)")
   not just "Some changes didn't sync."
3. Backend already returns error bodies; thread the server message through
   `OutboxReplayHttpException` so the app can show "Server: <message>" for
   terminal (parked) failures.
4. Add a debug-only "Sync log" screen behind the existing More/dev hub for field
   diagnosis.

**Verify:** Force a 422 (e.g. invalid payload), confirm logcat shows it, the
banner explains it, and a parked row's reason is visible.

---

## Workstream C — Instant launch: offline-first auth/launch rearchitecture

**Reported:** "Has to log in every time… should pop up instantly and show offline
data, doing sync/login in the background."

**Diagnosis.** The architecture is *mostly* right (tokens cached in DataStore
via `IdTokenCache`; Room is the read source; `bootstrap()` is fire-and-forget).
But two things gate the UI:
1. `AuthCoordinator.bootstrap()` — when the access token is stale it sets
   `AuthState.Loading` and **awaits** `silentRefresh()` (a network call) before
   showing the app. On a cold/slow network this reads as "stuck / logging in."
2. If the refresh token is expired (60-day, ADR-0010) or `silentRefresh()` hits a
   transient error, it can drop to `SignedOut`/`Failed` → the sign-in screen,
   even though valid **local** data exists to show.

**Fix — render local-first, authenticate in background:**
1. If `hasSignedIn == true`, go **straight to the app shell** backed by Room,
   regardless of access-token freshness. Do **not** block on `silentRefresh()`.
2. Run `silentRefresh()` in the background; on success the auth interceptor
   simply starts attaching the new token to subsequent calls.
3. Only force the sign-in screen on a **definitive** refresh-token rejection
   (401 on `/api/auth/refresh`), not on transient/offline errors. Offline =
   stay in the app on cached data; show a subtle "offline" indicator.
4. Keep `FirstSyncGate` blocking **only** for genuine first sign-in
   (`lastFullSyncAt == null`); returning users never see "Setting up…".

**Verify:** Airplane mode + cold start → app shows cached dashboard instantly, no
sign-in screen. Expire access token (not refresh) → app opens instantly, refresh
happens silently. Revoke refresh token server-side → next launch still opens on
cached data, then prompts re-auth only when the server rejects the refresh.

---

## Workstream D — Reactive updates ("event bus") so writes reflect instantly

**Reported:** "No event bus — when I log something, the recents list doesn't
update instantly. Examples all over the app."

**Diagnosis.** There *is* a reactive pattern for migrated features: Room is the
single source of truth and DAOs expose `observeActive()` Flows
(`MirrorRepositorySupport`, blood/medications). The breakage is in features that
**bypass the mirror**:
- **Recent activity** (`DashboardData.kt:518`) is a one-shot network fetch into a
  DataStore cache, TTL-gated 30s, loaded on `ON_RESUME` — never a Flow. Logging
  food/dose can't push into it.
- **Nutrition & goals** read directly over Retrofit (per `android/CLAUDE.md`),
  so they don't participate in mirror reactivity either.

**Fix (prefer the existing pattern over a new bus):**
1. Make "recents" derive from the **mirror**: expose a combined Flow over the
   mirrored entity tables (doses, food, blood, weight, etc.) and map to
   `RecentActivityEntry`, so any local write recomposes it immediately. Keep the
   server aggregate only as a cold backfill for entity types not yet mirrored.
2. For genuinely non-mirrored, server-derived aggregates, add a lightweight
   **invalidation signal** (a shared `MutableSharedFlow<Unit>` injected via Hilt)
   that local writes emit and dashboard collectors re-fetch on — a minimal "event
   bus" scoped to cache invalidation, not a general pub/sub.
3. Continue migrating `feature-nutrition` onto the mirror (ties into Workstream E).

**Verify:** Log a dose/food → recents updates with no manual refresh, offline,
within one frame.

---

## Workstream E — Food pictures missing

**Reported:** "Food pictures not always found/loaded, even after logout/login."

**Diagnosis (revised after verification).** It is **not** an auth/transport
problem: the Coil `ImageLoader` in `AppModule.kt:48-62` uses its **own** OkHttp
(no `AuthInterceptor`), and the GCS URLs are permanent/public/immutable
(`FoodImageStorage`, `MealPhotoStorage` — `max-age=31536000, immutable`). The
real causes:
1. **Async generation lifecycle.** `FoodEntry.mealImageUrl` is populated only
   after async generation flips `mealImageStatus` NONE→PENDING→READY
   (`FoodEntryImageService`); `CatalogFood.imageUrl` likewise. If generation is
   slow, fails (→ no FAILED handling shown to user), or the entry is read before
   READY, the URL is null → blank. Re-login doesn't help because the **server
   field is still null**.
2. **Nutrition isn't mirrored** (`android/CLAUDE.md`), so the app doesn't re-pull
   the entry when status later flips to READY — it shows whatever it had at fetch
   time.
3. Raw `photoRef` upload failures aren't surfaced.

**Fix:**
1. Surface image **status** in the UI: show a "generating…" placeholder for
   PENDING and a retry affordance for FAILED instead of a silent blank.
2. Add backend **retry/regenerate** for failed/stuck food images (mirror the
   exercise-media regenerate pattern) and make sure FAILED is a real terminal
   state the client can act on.
3. Have the nutrition feed re-fetch when image status is non-terminal (poll on
   resume, or include food in the sync mirror so status flips propagate). Folding
   nutrition into the mirror (Workstream C/D) fixes this structurally.
4. Verify GCS bucket objects are actually `public-read` and the generation job
   has write perms (ops check).

**Verify:** Log a meal with a photo → placeholder while PENDING → image appears
when READY without re-login. Kill generation → FAILED shows retry, retry works.

---

## Workstream F — Reminders: make them fire + close UX gaps

**Reported:** "Reminders don't work." Plus desired UX: global default times,
per-drug enable/disable, per-drug custom time (fallback to global), dose/drug
edits on phone.

**Diagnosis.** IMPL-16 is architecturally complete: `ReminderEngine` (single
chained exact alarm), `ReminderPlanner` (tested), boot/timezone receivers, 12h
WorkManager safety-net, settings screen with master toggle + 4 default window
times + per-med mute + per-med custom times. The **UX items requested are
largely already built** on `ReminderSettingsScreen.kt`. Why they "don't work":

1. **`replan()` is not called on medication CRUD.** Add/edit/discontinue a med
   updates the Room mirror but does **not** re-arm alarms — only app-start, save-
   settings, alarm-fire, boot, or the 12h worker do. New meds don't get reminders
   until restart. **(Primary cause.)**
2. **No FCM/sync-triggered replan** — settings/med changes on another device don't
   re-arm here until app open or 12h (deferred A2-6).
3. **Permissions.** POST_NOTIFICATIONS (13+) / SCHEDULE_EXACT_ALARM (12+) may be
   ungranted; banners exist but are dismissible. Without exact-alarm it silently
   degrades to a 15-min window.
4. **No deep link** — tapping a notification opens home, not the dose checklist
   (deferred A2-7).
5. Possible **silent failure** in `goAsync()` receivers (ties to Workstream B).

**Fix:**
1. Call `reminderEngine.replan()` whenever the medication mirror changes (observe
   the meds Flow in the engine, or trigger from the med repository after
   create/update/delete/changeDose).
2. Subscribe `ReminderSettingsRepository`/engine to the sync push signal and
   replan on the `medicationReminderSettings` + `medications` tags.
3. Strengthen permission onboarding: a blocking-but-skippable first-run prompt and
   a persistent "reminders won't fire" warning when permission is missing.
4. Add the deferred **deep link** so the notification opens the dose checklist.
5. UX gap to close: **dose/drug edits on the phone** — confirm
   `AddMedicationScreen`/`MedicationDetailScreen` support full dose + drug edits
   (audit found edit exists but no inline reminder controls). Add an inline
   "Remind at…" per-slot control + per-med toggle in the add/edit flow so users
   don't have to detour to the reminders screen (spec A3).

**Verify:** Add a med → reminder fires at the next due window without restart.
Change a default time → next alarm reflects it. Revoke exact-alarm → user is
warned. Tap notification → lands on dose checklist. Mark "Took it" → adherence
logs and notification updates.

---

## Workstream G — Workouts: activate, log, edit

**Reported:** activate doesn't work; can't see how to log; can't edit a program.

**Diagnosis.**
- **Activate:** flow is wired end-to-end (`ProgramDetailViewModel.activate()` →
  `…/activate` → `WorkoutScheduleService.activate()`). Failure modes: (a) backend
  validates first and returns **422** with issues, but Android shows a generic
  "Couldn't activate" and **hides the issue list** (`ProgramDetailViewModel.kt:96`);
  (b) **empty phases** → 200 but zero materialized sessions → empty "This week";
  (c) post-activate mirror refresh is best-effort `runCatching` and silently
  no-ops offline.
- **Log a workout:** logging is reachable **only** from the "This week" strip
  (current-week PLANNED sessions). DRAFT programs (no sessions) and ACTIVE
  programs with an empty/!current week show **no log affordance**. No "log a past
  session" path exists though the backend accepts historical `completedAt`.
- **Edit a program:** title/description edit exists on **web only**
  (`ProgramEditModal.tsx`); Android has none. Structural edits go only through the
  conversational "Refine with AI", which is wired for **ACTIVE** programs only —
  DRAFT programs can't be refined without activating first. REST `PATCH` is
  metadata-only (no phases).

**Fix:**
1. **Surface activation validation issues** inline on Android (parse the 422 body
   into the actionable list the web shows).
2. **Guard empty phases**: block activation with a clear message, or auto-offer
   the conversational designer.
3. Make the mirror refresh after activate robust (not silent on failure; rely on
   sync as fallback) — ties to Workstream B/D.
4. **Expose logging beyond "this week":** add a "Log a workout" entry on program
   detail and a date-picker "log past session" form (backend already supports it).
5. **Add an Android edit modal** for title/description (web parity), and **allow
   conversational refine for DRAFT** programs, not just ACTIVE.
6. Add a link from program detail to that program's logged-session history.

**Verify:** Activate a valid program → "This week" populates; activate an invalid
one → see the specific issues. From detail, log today's and a past session.
Edit a draft's title and refine its structure without activating.

---

## Recommended sequencing

| Phase | Workstream | Why this order |
|------|------------|----------------|
| 0 | **A** (exercise content) | Pure ops, zero code risk, immediate visible win. Can run today, in parallel with everything. |
| 1 | **B** (error surfacing) | Unblocks diagnosis of C–G. Small, cross-cutting, low-risk. |
| 1 | **C/D** (reactivity + sync reliability) | Foundational; many "doesn't update / sync failed / retry no-op" symptoms collapse into these. Do together (same files). |
| 2 | **C-launch (B-auth)** i.e. Workstream **C launch/auth** | Instant-launch rearchitecture; depends on confidence that local-first reads are solid (Workstream D). |
| 3 | **F** (reminders firing) | High user value; mostly wiring `replan()` triggers + permissions on top of a complete feature. |
| 3 | **E** (food pictures) | Benefits from nutrition→mirror migration started in D. |
| 4 | **G** (workouts) | Largest UI surface; least foundational. Do after the platform is stable and observable. |

(Phases 1 and 3 each bundle independent workstreams that can run in parallel by
different people; Phase 0 runs alongside all of it.)

---

## Cross-cutting testing & verification strategy

- **Unit:** `ReminderPlanner` already has tests — extend for replan-on-med-change.
  Add outbox replay tests asserting errors are logged and parked correctly. Add
  a launch/auth state-machine test (cached token stale → app shown, not Loading).
- **Instrumentation (Espresso/Compose):** cold-start-offline shows dashboard;
  log-dose updates recents; activate-invalid-program shows issues.
- **Manual device matrix:** Android 13/14/15 for notification + exact-alarm
  permission behavior; reboot test for reminder re-arm; airplane-mode launch.
- **Backend:** existing controller/service tests cover activate validation,
  reminder settings roundtrip; add food-image FAILED/regenerate coverage.
- **Observability as a test tool:** ship Workstream B first so the rest can be
  verified from logs/sync-log screen rather than guesswork.

---

## Open questions for the user

1. Exercise media: OK to **batch-approve** generated frames, or must each be
   visually reviewed for anatomical correctness first?
2. Reminders multi-device freshness (FCM-triggered replan) — worth doing now, or
   is app-open + 12h acceptable as already decided in IMPL-16 (A2-6)?
3. Workout program structural editing — full structural editor, or is
   "conversational refine for drafts too" sufficient?
4. Any of these need to ship to production first (hotfix), or all batched?
