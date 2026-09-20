# IMPL-WEB-WORKOUT-01 — Implementation Decision Log

> Companion to [`IMPL-WEB-WORKOUT-01-workout-tab-redesign.md`](IMPL-WEB-WORKOUT-01-workout-tab-redesign.md).
> Every non-obvious implementation decision made while building the feature is
> recorded here (with rationale) so it can be reviewed and tweaked afterward.
> Decisions here are *implementation* choices; the *product* decisions D1–D23
> live in §2 of the spec and are not repeated.
>
> Format: `IL-<n> · <area> · <decision> — <why>`. Append-only.

## Phase 1 — Backend stats API

- **IL-1 · packages** · Core logic lives in a new package
  `core.workoutstats` (`WorkoutStatsService`, `WorkoutStatsCacheEvictor`) and
  the HTTP surface in `api.workoutstats` (`WorkoutStatsController` + DTOs),
  mirroring the existing `core.workoutprogram` / `api.workoutprogram` split.
  — Keeps the new read-model self-contained and out of the already-large
  workoutprogram package.

- **IL-2 · cache = cached raw scan, not computed payload** · `WorkoutStatsService`
  caches the per-user *session scan* (`@Cacheable` on `scan(userId)`), and
  derives streak/series/heatmap/PRs per-request from that cached scan — exactly
  as `ExercisePerformanceDigestService` caches its scan and derives digests.
  — A single cache key (`userId`) means eviction is a clean one-liner, and the
  per-request derivation can honor the request's timezone + `weeks` without
  multiplying cache entries. The Firestore fan-in (the expensive part) is what
  gets cached.

- **IL-3 · cache TTL = 60s, not the spec's 5 min** · The new `workoutStats`
  Caffeine cache uses a 60s write-TTL, matching its sibling `exerciseDigest`
  rather than the spec §5.6's "~5 min". — 60s bounds staleness for the paths
  that don't fire an explicit eviction (e.g. un-completing a session, which
  does not publish `SessionCompletedEvent`) while the explicit eviction on
  completion still makes the common case instant. Deviates from spec D16/§5.6;
  flagging for review. Functionally satisfies BT-12 (no multi-minute wait).

- **IL-4 · invalidation via event listener, not a completion-service dep** ·
  Eviction is a `WorkoutStatsCacheEvictor` `@EventListener(SessionCompletedEvent)`
  (the event the completion service already publishes on COMPLETED), not a new
  constructor dependency on `WorkoutSessionCompletionService`. — Avoids
  breaking that service's many existing test constructors and keeps the stats
  module decoupled from completion. Trade-off: un-complete (COMPLETED→PLANNED)
  fires no event, so its staleness is bounded by the 60s TTL (IL-3) rather
  than evicted instantly — acceptable for a rare correction action.

- **IL-5 · single-session GET lives on WorkoutProgramController** · The new
  `GET /api/me/workout-programs/{programId}/sessions/{scheduledId}` is added to
  the existing `WorkoutProgramController` (next to the `recap` and `last-sets`
  session sub-routes), returning a `SessionDetailResponse` that wraps the
  existing `ScheduledWorkoutResponse` (which already carries programTitle +
  phaseTitle) and adds `prSetKeys` + `neighbors`. — Reuses the assembler and
  keeps all `/sessions/{id}` routes on one controller.

- **IL-6 · prSetKeys computed by the stats service** · The single-session GET
  asks `WorkoutStatsService` for the PR set-keys of that session (format
  `"<exerciseId>:<setIndex>"`, setIndex into the prescription's loggedSets),
  so PR logic lives in exactly one place. — Avoids duplicating the
  chronological-best-e1RM walk in the controller.

- **IL-7 · neighbors = adjacent COMPLETED sessions across all programs by date**
  · `prev`/`next` are the immediately-older / -newer COMPLETED sessions over
  the union of all programs, ordered by (date, completedAt). Ties broken by
  scheduledId for determinism. — Matches the History view's cross-program
  newest-first ordering so prev/next feels continuous with the list.

- **IL-8 · PR set key = `blockId:orderIndex:setIndex`, not `exerciseId:setIndex`**
  · The spec §5.5 sketched `"<exerciseId>:<setIndex>"`, but the web renders the
  session as block → prescription → sets, so a key carrying blockId +
  prescription orderIndex + set index maps 1:1 to the render tree with no
  per-exercise running counter. — Unambiguous and trivial for the detail page
  to match. Deviates from the spec sketch (internal format only, no API-shape
  impact beyond the key string).

## Phase 2 — Nav shell + Overview + session detail

- **IL-9 · functional gate realized as RTL component tests, not Playwright**
  · The spec §7.2 mandates fixture-driven Playwright e2e for the functional
  gate. That harness does not exist and can't be built cheaply: (a) the web
  app has no authenticated-e2e path (Auth.js needs Google OAuth; the existing
  smoke spec is unauthenticated-only), and (b) the Overview's data is fetched
  in Next.js **server components**, which Playwright's `page.route` cannot
  intercept (it only mocks browser requests). So the fixture datasets + their
  hand-computed expected values (user-alpha: streak 7 / longest 9 / 2-of-4 /
  2 known PRs; user-empty) are preserved verbatim, but the assertions run as
  Vitest + RTL tests that render the REAL presentational components with the
  fixtures and check exact rendered strings — which is precisely the "prove
  the UI shows TRUE values" intent. Playwright keeps the unauthenticated
  nav-shell smoke. DEVIATES from D18/§7.2; flagged for owner review.
  Consequence: E2E-1…E2E-12 are implemented as component tests with the same
  IDs in their describe/test names for traceability.

- **IL-10 · Overview = thin server page + pure presentational components** ·
  `page.tsx` only fetches (stats, active program, latest history) and hands
  plain props to `StreakHero`, `ConsistencyHeatmap`, `CurrentProgramCard`,
  `LatestWorkouts`, etc. — so every rendered value is unit-testable with a
  fixture and no network/auth. — Also gives clean per-card empty states (D12).

- **IL-11 · all stats consumers tolerate a 404/throw from the endpoint** ·
  The Overview and the `/me` WorkoutCard fetch `workout-stats` with a
  `.catch(() => null)` and render a degraded-but-complete state when it's
  absent. — Lets PR 2 (web) ship and render even if PR 1 (backend) isn't
  deployed yet; belt-and-braces per spec §6/§8.

- **IL-12 · tab bar in a route-group-free `layout.tsx`; sub-pages re-chromed
  in Phase 4** · Phase 2 adds `app/me/workouts/layout.tsx` with the persistent
  `WorkoutTabs`; the existing sub-pages keep their own headers/back-links for
  now (they simply render below the tab bar) and get de-duplicated in Phase 4
  (task 4.2). — Keeps Phase 2 focused on Overview + nav + session detail
  without a sweeping restyle of History/Programs/Gyms yet.

## Phase 4 — Progression refresh + re-chrome + acceptance

- **IL-13 · WeeklyVolumeChart = tonnage bars tinted by target, no dual axis** ·
  The spec asked for "tonnage bars + session-count markers + streak-target
  line" (§4). Mixing tonnage (bars) and session count (target line) on one
  chart means two y-scales — confusing. Instead: tonnage is the bar height
  (primary volume metric), each bar is tinted accent when that week met the
  session target and muted otherwise, and the hover tooltip carries the exact
  tonnage **and** session count. — Conveys "did I hit my sessions" per week
  without a second axis; still satisfies E2E-2 (tooltip shows exact tonnage).

- **IL-14 · Progression "visual refresh" = chrome only; console already on the
  new card language** · `ProgressionConsole` already wraps its sections in the
  exact target style (`rounded-[14px] border-[0.5px] border-border-default
  bg-surface`), so task 4.1 needed no component restyle — only placing the page
  under the new tab chrome and de-duplicating its header. — Deliberately avoids
  touching the interactive mode-selector / its server action (D22: "zero
  behavior change"), which a speculative restyle would have risked.

- **IL-15 · re-chrome scope = the five tab-target pages only** · The five
  top-level tab destinations (History, Programs, Gyms, Preferences,
  Progression) had their redundant "← Workouts" back-links removed (the tab bar
  + layout "← Dashboard" now own navigation) and their `<main>` aligned to the
  Overview's. Deep detail pages (programs/[id], programs/chat,
  gyms/[locationId], gyms/new/edit) keep their *contextual* back-links (e.g.
  "← Programs") — those aren't redundant with the tabs and genuinely aid
  drilling back up. — Keeps the change surface tight (D22 "re-chrome only").
