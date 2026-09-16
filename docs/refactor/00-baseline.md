# Phase 0 — Baseline: architecture as it actually is

Date: 2026-09-16. Branch `performance-refactor-fable` off `origin/main` (8c5cbf61).
All findings below were derived fresh from this checkout, its CI configuration,
7 days of production Cloud Run request logs (2026-09-09 → 2026-09-16), and
builds/tests executed in this worktree. Claims marked **[verified]** were
hand-checked at the cited file/line; the rest come from a systematic read sweep.

---

## 1. Data-loss risks (read this section first)

**DL-1 — Web loses any mutation on network failure. Severity: highest.**
The web app has no offline handling at all: no service worker, no IndexedDB, no
outbox, no retry. Every write is a Next.js server action → `apiFetch` → backend
(`web/lib/api.ts:107-121`); on failure the action throws, the client shows a
generic toast (`components/nutrition/AddFoodModal.tsx:258`), and the mutation is
gone. Web also sends no `Idempotency-Key`, so a user-level retry (double
submit) can duplicate entries on endpoints that aren't set-semantics.
localStorage is used only for unit prefs and an admin view toggle. **[verified:
no SW/IndexedDB anywhere under web/]**

**DL-2 — Concurrent cross-device edits silently drop data (whole-document LWW).**
Server conflict handling is document-level last-writer-wins on
`updatedAt: serverTimestamp()` with `SetOptions.merge()`; there is no per-field
merge and no versioning (e.g. `persistence/goals/FirestoreGoalRepository.java:68-84`).
If two devices complete the same workout session with different logged sets, the
later PUT replaces the earlier actuals wholesale
(`api/workoutprogram/WorkoutProgramController.java:217-244` — `logged` is a full
replacement list). Append-only data (sets, meals, readings) is not modeled as
append-only at the conflict layer. ADR-0007 documents this as an accepted
trade-off; it directly violates the target offline contract.

**DL-3 — Android destructive migration fallback can wipe the outbox.**
`HfDatabase` v7 has additive migrations 3→7 but also
`fallbackToDestructiveMigration()` (`android/core-data/.../db/HfDatabase.kt:276`)
**[verified]**. The mirror refills from the server after a wipe, but the
**outbox** and **workoutSessionDrafts** tables hold user data that exists
nowhere else — a schema bump without a hand-written migration destroys queued
unsent mutations and in-progress workout drafts. There are no Room migration
tests (forward or backward) anywhere in `core-data`. **[verified: no
MigrationTest in src/test or src/androidTest]**

**DL-4 — Draft/finish race window with the 24h stale-draft sweep (Android).**
Between enqueueing a session completion to the outbox and deleting the local
draft (`WorkoutSessionRepository.kt` finish path), a process death leaves the
draft behind. The stale-draft sweep finalizes abandoned drafts to COMPLETED
after 24h idle, which can re-submit a completion the server already accepted.
Mitigated (not eliminated) by the idempotent PUT + deterministic
`{date}_{dayId}` session id.

**DL-5 — Terminally rejected mutations strand silently (Android).**
A deterministic 4xx parks the outbox row at `PARKED_NEXT_ATTEMPT = Long.MAX_VALUE`
(`OutboxRepository.kt:220,283`) **[verified]**. Recovery requires the user to
notice a banner and act; there is no server-side visibility and no aging alert.
Data isn't destroyed, but it is stranded on one device indefinitely.

**DL-6 — The tests that would catch sync/concurrency regressions never run.**
Backend has 8 emulator-backed test classes tagged `firestore-emulator` —
including `RefreshTokenRotationConcurrencyTest`, `AdherenceSameDayConcurrencyTest`,
`NutritionRollupConcurrencyTest`, and repository scoping tests. `tasks.test`
excludes the tag (`backend/build.gradle.kts:115-118`) and the `integrationTest`
task that should run them was registered without `testClassesDirs`/`classpath`,
so it resolves to **NO-SOURCE and silently skips — locally and in CI**
(`backend/build.gradle.kts:133-145`; reproduced: `./gradlew integrationTest --info`
→ "Skipping task ':integrationTest' as it has no source files"). The
`firestore.emulator.required=true` CI guard never evaluates because the task
skips before running. **[verified]** Not data loss by itself, but it removes the
safety net over exactly the code paths this refactor touches.

Cross-cutting correctness note (pre-existing audit finding XPLAT-001, still
open): "today" is computed three different ways (web UTC / Android device-local /
backend server-zone), so evening web logs can land on the wrong day. Tracked in
`docs/audit/2026-09-13/`; it interacts with any sync/conflict work.

---

## 2. Repo map

Monorepo, three deployables plus infra:

| Component | Stack | Build | Modules |
|---|---|---|---|
| `backend/` | Spring Boot 3.5, Java 21 (virtual threads), Firestore native | Gradle KTS, single module | 60 controllers, 269 endpoint mappings; packages `api/` (controllers+DTOs), `core/` (services, repo interfaces), `persistence/` (Firestore impls), `auth/`, `config/`, `observability/` |
| `android/` | Kotlin 2.0, Compose M3, Hilt, Room+SQLCipher, Retrofit/Moshi, WorkManager, FCM | Gradle KTS + `build-logic/` convention plugins + version catalog | `:app`, `:core-domain` (pure Kotlin), `:core-data` (DB/sync/network), `:core-ui`, `:core-chat`, 7 `:feature-*`, `:wear` |
| `web/` | Next.js 15 App Router, TS strict, Tailwind v4, Auth.js v5 | pnpm 10.32.1 | ~55 routes (all but 5 `force-dynamic`), 32 API route handlers, `lib/` data layer (~50 modules), 118 `"use client"` files |

Dependencies point inward on Android (`app → feature-* → core-data/domain/ui`);
`:wear` depends only on `core-domain`/`core-ui`, never on `app`. Backend is
single-module by design (collapsed from 5). Deploys: merge to `main` → Cloud
Build per-component (path-filtered); backend deploy includes Trivy gate, canary
+ promote, and updates 4 Cloud Run Jobs. `main` is ruleset-protected requiring
6 green checks.

### Build & test suite status (executed in this worktree, cache-bypassed)

| Suite | Result |
|---|---|
| Backend `./gradlew build test --rerun` | **865 tests, 0 failures, 19 skipped** — but see DL-6: 8 emulator-tagged classes additionally never execute |
| Android `./gradlew testDebugUnitTest --rerun` + duplicate-class checks | **584 tests, 0 failures** (app 43, core-data 249, core-domain 57, core-ui 14, blood 17, body-comp 13, goals 4, medical 9, nutrition 43, settings 19, workouts 116; wear 0) |
| Android `:app:assembleRelease` (R8) | Green |
| Web `pnpm typecheck && test:coverage && build` | **60 tests / 12 files, 0 failures**; typecheck clean; production build green |
| Web e2e | 1 Playwright smoke spec only (unauthenticated page + security headers) |

Nothing fails. The gaps are what *doesn't run*: backend emulator suite (DL-6),
zero Android instrumentation tests in CI, zero coverage gates anywhere, and no
perf tests of any kind (no Macrobenchmark, no baseline profiles, no Lighthouse
CI, no k6).

---

## 3. Data path traces

### Android — write (logging a set)
Compose logger → `WorkoutSessionViewModel.logSet()` (feature-workouts
`session/WorkoutSessionViewModel.kt:206`) → `WorkoutSessionRepository.updateSets()`
(core-data `.../session/WorkoutSessionRepository.kt:151-179`) → Room
`workoutSessionDrafts` upsert on SQLCipher (device-local draft, not synced).
Each set edit is durable locally and survives process death. On
`confirmFinish()` → `uploadOutcome()` builds `CompleteSessionRequest`, applies
it optimistically to the mirror, and enqueues to the **outbox**
(`OutboxEntity`: `mutationId` UUID = replay `Idempotency-Key`, per-entity `seq`,
`originDeviceId`, `attempts`, `nextAttemptAt`). Drain
(`OutboxDrainWorker` → `OutboxRepository.drain()`, mutex-protected, collapses
per-entity mutations, replays in seq order via `OutboxReplayClient` with
`Idempotency-Key` + `X-HF-Origin-Device` headers) → backend
`PUT /api/me/workout-programs/{pid}/sessions/{sid}` → idempotent upsert of
`users/{uid}/workoutPrograms/{pid}/scheduledWorkouts/{date}_{dayId}` + fan-out
(Workout doc, weekly aggregate recompute, metric events, best-effort Gemini
recap) → FCM `syncNotifier.changed()` wakes other devices to pull.

### Android — read (workout history)
`WorkoutHistoryViewModel.load()` → `WorkoutProgramRepository.workoutHistoryPage()`
(core-data `.../program/WorkoutProgramRepository.kt:186-189`) → **direct network
call**, page size 25, no Room mirror, no cache, no offline fallback — error UI
with manual retry. This screen violates the app's own ADR-0018 cache-first
rule. (Contrast: the program calendar *is* mirror-backed via
`scheduledDao.observeActive()` and works offline.)

### Web — write (logging a food entry)
`AddFoodModal` (client) → server action `addEntry` prop
(`app/(me)/nutrition/page.tsx:141-157`) → `lib/nutrition-api.ts` →
`apiFetch` POST `/api/me/nutrition/{date}/entries` → on success
`revalidatePath("/me/nutrition")`; on failure, toast and nothing else (DL-1).
No optimistic UI, no queue, no idempotency key.

### Web — read (dashboard `/`)
`force-dynamic` server component. `await auth()` then 8 Suspense sections fetch
in parallel from the backend per request (weight, vitals, body-comp, nutrition,
workout, blood, doses, recent feed). `loadBodyCompositionCached` is deduped via
React `cache()`; `loadHiddenBiometrics` is fetched by two sections without
explicit dedupe. Every page nav = SSR round-trip + N backend calls; first paint
is bound by backend latency (see §5) plus Cloud Run cold starts on *both*
services.

### Backend — sync read path
`GET /api/me/sync?since=<cursor>&limit=500&schemaVersion=1[&recentSince=date]`
(`api/sync/SyncController.java:38-65`). Cursor = base64
`"<millis>|<collection>|<id>"` with (lastUpdate, collection, id) tiebreak
ordering — no-skip/no-dup paging (`core/sync/SyncCursor.java`). Covers 15
top-level collections + 6 subcollection families; tombstones are
`syncStatus: ARCHIVED` soft-deletes emitted as status-only changes. Per page,
`FirestoreSyncChangeReader` re-enumerates subcollection parent frontiers;
enumeration is cursor-pruned for date-keyed (`nutritionDays`) and
updatedAt-keyed (`goalChatThreads`) shapes but full `listDocuments()` for the
rest **[verified at FirestoreSyncChangeReader.java:274-433]** — and every
covered collection is scanned on every page regardless. This is the slowest
hot endpoint in production (§5).

---

## 4. The offline model, precisely

| Question | Android | Web | Wear |
|---|---|---|---|
| Local store | Room `hf-offline.db`, SQLCipher-encrypted (Keystore passphrase), schema v7: 23 mirror tables (payload JSON + `lastUpdate` + `status` + `dirty` + `syncState`) + `outbox`, `sync_state`, device-local `workoutSessionDrafts`, `catalog_cache`, `nutritionOps` | None (localStorage for unit prefs only) | None (read-only REST; token relayed from phone via Wearable Data Layer) |
| UI reads | Mirror-first per ADR-0018 for most screens; **exceptions**: workout history, food catalog search (network-only) | Network-only, SSR per request | Network-only |
| Mutation queue | Outbox: UUID `mutationId` (= Idempotency-Key), per-entity monotonic `seq`, `originDeviceId`, payload = exact wire body; plus a separate Room-backed `nutritionOps` rail for multi-step AI food ops | None (DL-1) | No writes |
| Sync trigger | Drain after local write; WorkManager on connectivity-regained; periodic ~6h floor; FCM silent push-to-pull; first-run gate does a 14-day-windowed initial pull | n/a | n/a |
| Pull model | Delta via server cursor, 500/page, cursor persisted after each page (resumable); schema-version mismatch → wipe + full resync | Full SSR refetch every request | n/a |
| Backoff | Exponential 30 s → 6 h cap, **no jitter** (`OutboxRepository.kt:286-289`) **[verified]**; terminal 4xx parks forever (DL-5) | None | n/a |
| Conflicts | Client: whole-record LWW on server `lastUpdate` (`ConflictResolver`; dirty-local-newer wins locally until replay). Server: whole-document LWW on `serverTimestamp()` (DL-2). No per-field rules; no append-only modeling | Last write to server wins; no detection | n/a |
| App kill mid-sync | Outbox rows and cursor are durable; each pulled page commits in one Room transaction; drain resumes on next trigger. Draft/finish race exists (DL-4) | In-flight server action lost (DL-1) | n/a |
| Auth expiry offline | Mutations queue without auth; token acquired at replay; OkHttp authenticator refreshes on 401 via ADR-0019 successor-chain rotation (with reuse-grace); outbox survives re-auth; `FirstSyncGate.resyncAfterReauth()` re-pulls | Auth.js JWT rotates near expiry; offline writes are lost anyway | Phone owns refresh; wear goes stale silently |
| Schema migrations | Room additive migrations 3→7 **plus destructive fallback (DL-3)**; no migration tests | n/a | n/a |
| Server idempotency | `SyncWriteContext` (`Idempotency-Key` → `users/{uid}/idempotencyKeys/{scope#key}`, 7-day TTL) used by 15 controllers **[verified]**; other writes rely on set-semantics PUT/PATCH or deterministic ids (workout completion). Coverage is per-endpoint, not uniform | Web sends no keys | n/a |

Documented intent (ADR-0007/0018) matches the Android implementation closely.
The web app has no documented or actual offline story (a PWA plan exists in
`docs/plans/IMPL-PWA-01` but nothing is built).

---

## 5. Baseline numbers

### Backend — production Cloud Run request logs, 7 days (9,495 requests, 264 cold starts)

Warm percentiles (first request per instance excluded); top endpoints by volume:

| Endpoint | n | p50 | p95 |
|---|---|---|---|
| `GET /api/me/sync` | 344 | **1,756 ms** | **4,126 ms** |
| `GET /api/me/workout-programs/{id}` | 484 | 133 ms | 316 ms |
| `GET /api/me/blood` | 351 | 30 ms | 165 ms |
| `GET /api/me/recent-activity` | 312 | **609 ms** | 1,580 ms |
| `GET /api/me/body-composition` | 304 | 117 ms | 646 ms |
| `GET /api/me/daily-metrics` | 303 | 46 ms | 292 ms |
| `GET /api/me/nutrition/{date}` | 248 | 165 ms | 869 ms |
| `GET /api/me/medications/today` | 243 | **381 ms** | 1,786 ms |
| `GET /api/me/workout-programs/imported-history` | 164 | **1,942 ms** | 3,776 ms |
| `POST /api/auth/refresh` | 67 | **1,267 ms** | 1,905 ms |
| `POST .../sessions/{id}` (log workout) | 46 | 378 ms | 1,789 ms |
| `POST .../last-sets` | 49 | 599 ms | 1,959 ms |

Cold starts: p50 **12.9 s**, p95 **19.0 s** per first request; 264 occurrences
in 7 days *despite* `--min-instances=1` (applied 2026-09-13; the window
straddles it — post-change cold starts continue on revision rollouts and
concurrent-instance scale-up). Against targets (reads ≤150 ms p95, writes
≤250 ms p95): `blood`, `daily-metrics`, program-by-id are close or passing;
`sync`, `recent-activity`, `imported-history`, `medications/today`, and all
write endpoints miss, most by 5–25×.

### Android

- Unit suites: see §2. No instrumentation, no Macrobenchmark, no baseline profile.
- Cold start, emulator (`hf_test`, arm64 host), unauthenticated → sign-in
  frame, `am start -W`, 5 runs each after force-stop:
  - **Release (R8)**: median **~428 ms** (499/311/430/428/389)
  - Debug: median ~1,190 ms (1301/1192/535/1190/1140)

  Caveats: emulator on an M-series host is faster than a mid-range device;
  the authenticated dashboard cold start (mirror reads + first frame of real
  content) is the number that matters for the 800 ms target and needs a
  Macrobenchmark harness on device (does not exist today). The release number
  suggests the target is plausibly already met or near-met on real hardware —
  to be confirmed, not assumed.

### Web

- Shared first-load JS (root main chunks): **127 KB gzipped** (target ≤200 KB
  initial — page chunks add to this per route; total client JS across all
  routes 1.8 MB uncompressed).
- LCP: **not measurable in this environment** (all meaningful pages are
  auth-gated `force-dynamic` SSR; no Lighthouse/web-vitals harness exists in the
  repo). Structural expectation: LCP ≈ TTFB (Cloud Run + SSR + backend fan-out)
  + render; with backend §5 latencies, a throttled-4G LCP under 1.5 s is not
  plausible today on data-heavy pages. Needs an authenticated Lighthouse run
  (session-cookie injection) as part of a perf harness.
- Prior internal audit (2026-09-13) recorded web service p50 228 ms / startup 1.7 s.

### Not yet measurable (needs harness — candidate slice 0 of the plan)

- **Full sync after 24 h offline with a year of data**: requires a seeded
  fixture user (~730 nutrition days + workouts) and a device-side timer around
  `SyncEngine.pull()`. Server-side hint: each sync page costs ~1.8 s p50 today.
- **Local DB query times for heaviest screens**: requires instrumented
  benchmarks (none exist).
- **Authenticated Android cold start / web LCP**: as above.

---

## 6. Ranked problems (evidence in §§1–5)

1. **Web mutation loss** (DL-1) — the only place in the system where user data
   is routinely destroyed by ordinary conditions. Goal 1.
2. **`GET /api/me/sync` is the slowest hot path** (1.8 s p50 / 4.1 s p95 warm)
   — it gates every device's convergence and the FCM push-to-pull loop;
   per-page frontier re-enumeration + full scan of all 21 collection families
   per page. Goals 1+2.
3. **Whole-document LWW with no append-only modeling** (DL-2) — deterministic
   but destructive; violates the target conflict contract for sets/meals/readings.
   Goal 1.
4. **Dead backend integration suite** (DL-6) — restores the safety net needed
   before touching sync; trivially fixable (wire `testClassesDirs`), then keep
   it green in CI. Goal 1 enabler.
5. **Android destructive migration fallback + no migration tests** (DL-3) —
   one careless schema bump from wiping outboxes in the field. Goal 1.
6. **Backend cold start ~13 s p50** hitting real users 264×/week — Spring
   startup weight; CDS planned (audit PERF-002) but not shipped; also inflates
   web TTFB when web SSR hits a cold backend. Goal 2.
7. **Web is 100% force-dynamic SSR with per-request backend fan-out** — every
   nav pays full network round-trips; no read-through cache layer; duplicate
   `loadHiddenBiometrics` fetch on dashboard. Goal 2.
8. **Slow secondary endpoints**: `recent-activity` (N+1: programs × calendar +
   day-by-day food reads — `api/dashboard/RecentActivityService.java:114-172`),
   `imported-history` (1.9 s p50), `medications/today`, `auth/refresh` (1.3 s
   p50 on a hot path). Goal 2.
9. **Outbox backoff has no jitter** (thundering-herd on reconnect; all devices
   also share fixed 30 s→6 h ladder) and **terminal-parked rows have no aging
   telemetry** (DL-5). Goal 1.
10. **Android read-path exceptions to cache-first** (workout history
    network-only) and **no jittered retry on reads**; history is also the
    screen backed by the slow `imported-history` endpoint. Goals 1+2.
11. **No perf measurement anywhere** — no Macrobenchmark/baseline profiles, no
    Lighthouse/web-vitals, no k6, no backend endpoint SLO dashboards beyond raw
    Cloud Monitoring; and no coverage gates (backend/web coverage produced but
    unenforced; Android not measured). Goal 3 enabler; prerequisite to proving
    any perf slice.
12. **Structural hygiene**: 2 field-injection violations
    (`GoogleHealthSignatureVerifier`, `WithingsWebhookController`); wear module
    has zero tests; web has one e2e smoke test; `docs` claim vs code drift is
    minor (ADR-0007/0018 are accurate).

Existing repo plans overlap parts of this (audit 2026-09-13: PERF-001/002,
XPLAT-001, ARCH-002; `docs/plans/IMPL-PERF-01`, `IMPL-PWA-01`). The Phase 1
plan should subsume rather than duplicate them.
