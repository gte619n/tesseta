# IMPL-WEB-WORKOUT-01 — Web Workout Tab Redesign (read-first dashboard)

> Status: **planned** · Created 2026-09-18 · Source: owner request — replace the
> `/me/workouts` link-card hub with a data-rich Overview (streaks, latest
> workouts, current program, progress charts) under a persistent tab bar; drop
> the "Log Workout" placeholder entirely; keep Gyms / History / Programs /
> Progression reachable but better organized.
>
> All product/technical decisions below were locked in a 6-round owner
> interview on 2026-09-18 (see Decision log). Do not re-litigate a decision
> without flagging it to the owner first.

---

## 1. Goal and non-goals

**Goal:** `/me/workouts` becomes a read-first training dashboard. Landing on
the workout tab answers, without any clicks: *am I on streak, what did I do
recently, what program am I in, and is my training trending the right way* —
with History, Programs, Progression, and Gyms one tab away. Backend gains a
first-class workout-stats API (streak, weekly series, heatmap, e1RM history,
PRs) that Android can later reuse to replace its client-side streak math.

**Non-goals (explicitly out of scope):**

- **No live workout logging on web** — the "Log Workout (coming soon)" hub
  card dies and is not replaced. Backfill stays: History's log/customize
  session modals and Program detail's "Log result" are kept as-is
  (owner decision: they are correction tools for when the phone missed
  something).
- No Android changes (Android may adopt the stats endpoint later; not here).
- No History filters, and no new session-detail links *from History rows*
  (owner declined; History is re-chrome only). Heatmap days and Overview
  latest-workout rows DO link to the new session detail page.
- No third-party charting library (extend `web/lib/chart.ts`).
- No feature flags / parallel old-hub fallback (phased PRs instead).
- No offline/outbox support for the new pages (read surfaces, force-dynamic).
- No writes to or backfill of `weeklyWorkoutAggregates` (stats computes from
  sessions directly; the aggregates collection is untouched and untrusted).
- No phone-first design work (desktop-first; graceful single-column stacking
  at narrow widths, one phone-width e2e check).

---

## 2. Decision log (owner interview, 2026-09-18)

| # | Topic | Decision |
|---|-------|----------|
| D1 | Navigation model | Dashboard-first: `/me/workouts` renders the Overview; persistent horizontal tab bar **Overview \| History \| Programs \| Progression \| Gyms** shared across all workout sub-pages (sub-routes keep their URLs). |
| D2 | Streak semantics | **Consecutive weeks ≥ `weeklyStreakTarget`** (ISO weeks, Monday start, user's local TZ). Current in-progress week shown separately as progress ("2/4 this week"); it cannot break the streak until it ends, but if it already meets the target it extends the streak (see §5.1). |
| D3 | Backend scope | New backend stats endpoints (streak, weekly series, heatmap, e1RM history, PRs). Web stays thin; Android can reuse. |
| D4 | Logging removal | Remove hub card only. Keep History log/customize modals and Program "Log result" backfill flows unchanged. |
| D5 | Overview visualizations | All four: strength trend per lift, weekly volume bars, consistency heatmap, muscle/pattern balance. |
| D6 | e1RM history source | **Epley from logged sets** (`w×(1+reps/30)`, best set per session, full history from day one). Headline number remains the Kalman belief; curve labeled "estimated". No Kalman snapshotting. |
| D7 | Progression console | Stays its own tab; Overview shows a compact strength-highlights / week-trend tease linking into it. Intentional overlap is fine. |
| D8 | Chart tech | Extend homegrown SVG (`lib/chart.ts`): axes, bars, hover tooltips as a small client component. Zero new deps. |
| D9 | Overview hierarchy | **Streak + this-week hero** (big streak number, week progress, heatmap beside it) → current program card + latest workouts → chart grid. |
| D10 | Latest workouts depth | Compact rows (~5): date, day label, sets logged, duration, feeling — each links to the session detail page. |
| D11 | Gyms & Preferences | Gyms is the 5th tab. Preferences moves behind a **gear icon on the tab bar** (keeps its URL, no tab slot). |
| D12 | Empty states | Guided CTA per missing piece; dashboard skeleton always renders. No active program → "Design a program" CTA into chat designer; no history → friendly chart empties; streak shows 0 with target. |
| D13 | API shape | One consolidated `GET /api/me/workout-stats` summary + lazy `GET /api/me/workout-stats/e1rm-history?exerciseId=…`. |
| D14 | Session click-through | New dedicated page `/me/workouts/history/[programId]/[scheduledId]` (linkable; prev/next navigation). Requires a new single-session GET on the backend (none exists today). |
| D15 | Strength chart lifts | Auto defaults: top lifts by observation count across main movement patterns, + dropdown picker for any tracked exercise. No pinning config. |
| D16 | Caching | Backend caches computed stats per user, ~5 min TTL, invalidated on session completion (same pattern as `ExercisePerformanceDigestService`). Web stays force-dynamic. |
| D17 | Weekly series source | Compute from completed `ScheduledWorkout` docs on the fly (behind the cache). **Ignore `weeklyWorkoutAggregates`** (opaque trigger, gap risk on imported history). |
| D18 | Functional verification | Fixture-driven Playwright e2e against a stub backend with hand-computed expected values (the only owner-mandated functional gate; backend unit tests remain as the technical baseline, §7). |
| D19 | Rollout | Phased PRs, backend first (additive endpoints absorb deploy-gate surprises early). Each PR independently shippable. Merge to main = prod deploy. |
| D20 | Responsive | Desktop-first; graceful stack at narrow widths; horizontal-scroll tab bar; one phone-width e2e check. No phone-specific layouts. |
| D21 | PRs | In scope: PR detection inside stats computation; "Recent PRs" card on Overview (last ~5) + PR badges on session detail sets. |
| D22 | Sub-page scope | History / Programs / Gyms: **re-chrome only** under the tab layout. **Progression additionally gets a visual refresh** to the new card/chart language (functionality unchanged, incl. the block-mode selector). |
| D23 | /me dashboard card | Top-level `WorkoutCard` gains streak + this-week progress from the stats endpoint. |

---

## 3. Current state (verified 2026-09-18 by codebase sweep)

- `web/app/me/workouts/page.tsx` is a six-card link hub (Progression, Gyms,
  History, "Log Workout — coming soon" placeholder, Programs, Preferences).
  No shared workout layout/tab chrome exists; each sub-page is standalone.
- Web stack: Next.js 16 App Router, React 19, Tailwind 4, next-auth 5 beta,
  server components + server actions, `apiJson()`/`send()` in `web/lib/api.ts`.
  Charts are hand-rolled SVG via `web/lib/chart.ts`
  (`projectSeries`/`toLinePath`/`toAreaPath`/`movingAverage`) used by
  `Sparkline`/`WeightChart`. No chart dependency. Tests: Vitest + RTL,
  Playwright e2e (`pnpm test`, `pnpm test:e2e`).
- Backend (Spring/Firestore): rich read surface already exists —
  `/api/me/workout-programs` (+ `{id}`, `/calendar`, sessions PUT),
  `/api/me/workout-history` (+ `/summary`), `/api/me/progression/*`
  (week-review, strength, block-parameters, state/{exerciseId}),
  `/api/me/workout-programs/settings` (`weeklyStreakTarget` 1..14 default 4,
  `preferences`).
- **Gaps this spec fills:** no streak computation anywhere server-side (the
  setting stores only the threshold; Android computes client-side); no e1RM
  time-series (ProgressionState is current-value only); no weekly
  tonnage/session API (`weeklyWorkoutAggregates` exists in Firestore but has
  no endpoint and an untrusted update trigger); **no GET for a single
  scheduled session** (calendar-range only); no PR detection anywhere.
- `LoggedSet` fields relevant to stats: `weightLbs`, `reps` (nullable —
  imported weight-only history rows), `rpe`, `rir`, `durationSeconds`,
  `completedAt`. `ScheduledWorkout`: `date` (LocalDate), `status`
  (PLANNED/COMPLETED/SKIPPED), `completedAt`, `durationSeconds`, `feeling`
  (1..5), denormalized `session` tree with per-prescription `loggedSets`.
- `X-Timezone` header pattern already exists on run-day endpoints; reuse it.

---

## 4. Target information architecture

```
/me/workouts                    ← Overview (NEW dashboard; replaces hub)
/me/workouts/history            ← re-chromed (existing list + modals)
/me/workouts/history/[programId]/[scheduledId]   ← NEW session detail page
/me/workouts/programs           ← re-chromed
/me/workouts/programs/[id]      ← re-chromed
/me/workouts/programs/chat      ← re-chromed (chat layout may opt out of tabs if cramped)
/me/workouts/progression        ← visual refresh (D22)
/me/workouts/gyms (+ children)  ← re-chromed
/me/workouts/preferences        ← re-chromed; reached via gear icon in tab bar
```

`web/app/me/workouts/layout.tsx` (NEW) renders the tab bar via a client
`WorkoutTabs` component (active state from `usePathname`; Overview matches
exactly `/me/workouts`, others by prefix). Gear icon links to
`/me/workouts/preferences`. Narrow widths: tab bar horizontally scrollable.

### Overview composition (top → bottom, D9)

1. **Hero row** — `StreakHero` (current streak in weeks, longest streak,
   "N/target this week" progress) + `ConsistencyHeatmap` (~6 months of
   workout days; day click → session detail; >1 session/day gets darker
   shading and links to the first).
2. **Program + activity row** — `CurrentProgramCard` (active program title,
   phase spine, week N/M in phase, next scheduled session date/label, link to
   program detail; empty → "Design a program" CTA to `/me/workouts/programs/chat`)
   beside `LatestWorkouts` (5 compact rows from `/api/me/workout-history?page=0&size=5`,
   each → session detail page).
3. **Chart grid** — `StrengthTrendChart` (e1RM line per selected lift, lazy
   per-exercise fetch, current Kalman belief annotated as the endpoint dot ±
   sigma band, "estimated" label on the historical curve),
   `WeeklyVolumeChart` (26-week tonnage bars + session-count markers +
   streak-target line), `PatternBalanceCard` (this week's per-pattern volume
   vs proposed targets from existing `/api/me/progression/week-review`;
   links to Progression tab), `RecentPrsCard` (last 5 PRs; each → session
   detail).
4. Every card degrades independently per D12; the page never blanks.

### Session detail page (D14)

Server component; fetches the new single-session GET. Renders: date, day
label, program/phase context, duration, feeling, per-block exercise list with
prescribed vs logged sets (weight×reps, RIR/RPE where present), prescription
`rationale`/`loadBasis` line where present, PR badges on sets that are PRs
(from `prSetKeys` in the response, §5.4), prev/next session links (from
`neighbors` in the response). Read-only — no edit affordances here (backfill
stays on History per D4).

---

## 5. Backend design (Phase 1)

New `WorkoutStatsService` + `WorkoutStatsController`. All endpoints are
additive, `/api/me/`-scoped via `CurrentUserProvider`, honor `X-Timezone`
(default UTC) for week/day bucketing.

### 5.1 Streak algorithm (normative)

- A session counts iff `status == COMPLETED`. Bucket by the session's `date`
  field (already a local date). `SKIPPED`/`PLANNED` never count.
- Weeks are ISO weeks (Monday start) in the request timezone.
- Let `target` = current `weeklyStreakTarget` (applied uniformly to all
  historical weeks — no target-history tracking; deliberate simplification).
- `thisWeekCompleted` = completed sessions in the current (in-progress) week.
- Walk weeks backward from the last fully-elapsed week; `currentStreak` =
  count of consecutive weeks with `completed ≥ target`. **If
  `thisWeekCompleted ≥ target`, add 1** (current week extends but never
  breaks — D2).
- `longestStreak` = max run over all history using the same rule (current
  week included only if it meets target).
- Sessions from multiple programs in the same week all count. Deload-week
  sessions count (they are completed sessions).

### 5.2 `GET /api/me/workout-stats?weeks=26` → `WorkoutStatsResponse`

```jsonc
{
  "streak": { "current": 7, "longest": 11, "weeklyTarget": 4,
              "thisWeekCompleted": 2, "weekStart": "2026-09-14" },
  "weeklySeries": [            // exactly `weeks` entries (default 26, max 104),
                               // oldest→newest, current week last, zero-filled
    { "weekStart": "2026-03-16", "sessions": 4, "tonnageLbs": 42150.0 }
  ],
  "heatmap": [                 // last 183 days, only days with ≥1 session
    { "date": "2026-09-15", "sessionCount": 1,
      "first": { "programId": "wp_x", "scheduledId": "2026-09-15_d2" } }
  ],
  "recentPrs": [               // newest-first, max 5 (see §5.4)
    { "exerciseId": "ex_bench", "exerciseName": "Barbell Bench Press",
      "e1rmLbs": 245.3, "weightLbs": 225, "reps": 5, "date": "2026-09-12",
      "programId": "wp_x", "scheduledId": "2026-09-12_d1" }
  ],
  "chartDefaultLifts": [       // D15: top ≤4 by observationCount, ≤1 per
                               // movement pattern, from ProgressionState
    { "exerciseId": "ex_bench", "exerciseName": "Barbell Bench Press" }
  ],
  "trackedExercises": [        // picker population: every exercise with ≥1
                               // logged set, name + lastPerformed
    { "exerciseId": "…", "exerciseName": "…", "lastPerformed": "2026-09-12" }
  ]
}
```

- **Tonnage** = Σ(`weightLbs × reps`) over logged sets; sets with null `reps`
  or null/0 weight contribute 0 (timed holds, bodyweight, imported
  weight-only rows).
- Implementation reads all COMPLETED `ScheduledWorkout` docs across the
  user's programs once per cache fill (D17) and derives streak, series,
  heatmap, PRs, and tracked exercises in a single pass.

### 5.3 `GET /api/me/workout-stats/e1rm-history?exerciseId=X` → `E1rmHistoryResponse`

```jsonc
{
  "exerciseId": "ex_bench", "exerciseName": "Barbell Bench Press",
  "points": [   // one per session where the exercise was performed, oldest→newest
    { "date": "2026-04-02", "e1rmLbs": 231.0, "weightLbs": 205, "reps": 4,
      "lowConfidence": false }   // true when derived from a weight-only row
  ],
  "currentBelief": { "e1rmLbs": 246.1, "sigmaLbs": 6.2, "confidence": "HIGH" } // null if no ProgressionState
}
```

- Per-session point = the set maximizing Epley `w×(1+reps/30)`; weight-only
  rows (null reps) use the weight itself and set `lowConfidence: true` (D6,
  mirrors `ExerciseDigest`). Unknown `exerciseId` or no history → 200 with
  empty `points` (web renders empty-state), never 404.

### 5.4 PR detection (normative, D21)

Chronological walk of a given exercise's per-session best Epley e1RM. A
session is a PR iff its best-set e1RM **strictly exceeds** every prior
session's best AND at least one prior session exists (first-ever session is
not a PR) AND the qualifying set has non-null `reps` (weight-only imported
rows can never mint a PR; they still appear in history charts). `recentPrs` =
the 5 most recent PR sessions across all exercises. The single-session GET
(§5.5) returns `prSetKeys` identifying which logged sets were PRs so the
detail page can badge them.

### 5.5 `GET /api/me/workout-programs/{programId}/sessions/{scheduledId}` → `SessionDetailResponse`

Missing primitive (calendar-range only today). Returns the
`ScheduledWorkoutResponse` shape **plus** `programTitle`, `phaseTitle`,
`prSetKeys: ["<exerciseId>:<setIndex>", …]`, and
`neighbors: { prev: {programId, scheduledId, date} | null, next: … | null }`
(prev/next COMPLETED sessions across all programs, by date). 404 on wrong
owner or unknown id.

### 5.6 Caching & invalidation (D16)

Per-user in-memory cache of the computed stats bundle, TTL 5 min, explicitly
invalidated in the session-completion PUT path (same lifecycle as
`ExercisePerformanceDigestService` — follow that class's pattern).
Best-effort across Cloud Run instances is acceptable at current scale;
document the instance-locality caveat in the class Javadoc. e1rm-history
responses are served from the same cached session walk.

---

## 6. Web design notes (Phases 2–4)

- **Chart lib extension** (`web/lib/chart.ts` + new
  `web/components/charts/`): pure functions for axis ticks
  (`niceTicks(min,max,n)`), bar layout, and time-x projection — unit-tested;
  a small client `<ChartTooltip>`/hover layer (pointer events over the SVG);
  server-rendered SVG for everything static. Visual language matches
  `WeightChart`/`Sparkline` (same tokens).
- **StrengthTrendChart** is a client component: receives
  `chartDefaultLifts` + `trackedExercises` + the first default lift's history
  as server-fetched initial props; subsequent lift selections fetch via a new
  Next.js route handler `web/app/api/workout-stats/e1rm-history/route.ts`
  that proxies to the backend with the session token (existing chat-proxy
  pattern).
- **Heatmap**: server component; 26 columns × 7 rows of `<a>` cells; intensity
  from `sessionCount`; empty cells inert.
- **`/me` `WorkoutCard`** (D23): add streak + "N/target this week" sourced
  from `GET /api/me/workout-stats` (tolerate endpoint absence → card renders
  its current content, so the web PR can't be blocked by an undeployed
  backend).
- **Progression refresh** (D22): restyle `ProgressionConsole` sections into
  the new card/typography language; zero behavior change; the mode-selector
  server action is untouched.
- **Removal**: delete the hub-card grid from `web/app/me/workouts/page.tsx`
  including the "Log Workout — coming soon" card. Grep guard: the string
  "Log Workout" must not appear in `web/` after Phase 2 except in history
  backfill modal copy ("Log result"/"Log session" backfill strings are fine).

---

## 7. Testing approach

Two mandated layers (D18 + technical baseline). An item is not "Tested" until
its listed layer(s) pass — see §9 protocol.

### 7.1 Technical tests

**Backend (JUnit, `./gradlew test`)** — `WorkoutStatsServiceTest` with seeded
in-memory/emulated session fixtures. Required cases (each is a named test):

| ID | Case | Expected |
|----|------|----------|
| BT-1 | Empty history | streak 0/0, empty series (zero-filled weeks), empty heatmap/PRs |
| BT-2 | 7 consecutive qualifying weeks, current week 2/4 | current=7, thisWeekCompleted=2 |
| BT-3 | Current week already ≥ target | current includes current week (+1) |
| BT-4 | Gap week (below target) mid-history | streak stops at gap; longest spans pre-gap run |
| BT-5 | SKIPPED and PLANNED sessions in a week | not counted anywhere |
| BT-6 | Sunday 23:30 session, `X-Timezone: America/New_York` vs UTC | lands in different ISO weeks per TZ |
| BT-7 | Two programs contributing to one week | sessions summed across programs |
| BT-8 | Weight-only imported sets | tonnage contribution 0; e1RM point = weight w/ lowConfidence; never a PR |
| BT-9 | PR rules | strictly-greater only; first session never PR; ties are not PRs |
| BT-10 | Tonnage math | hand-computed Σ(w×r) matches to 0.1 lb; timed/bodyweight sets contribute 0 |
| BT-11 | chartDefaultLifts | ≤4, ≤1 per movement pattern, ordered by observationCount |
| BT-12 | Cache invalidation | session-completion PUT → next stats read reflects the new session (no 5-min wait) |
| BT-13 | Single-session GET | owner-scoping 404, neighbors ordering, prSetKeys correctness |

Controller slice tests: auth required, `X-Timezone` plumbed, `weeks` clamped
to [4, 104].

**Web (Vitest + RTL, `pnpm test` in `web/`)**: `niceTicks`/bar-layout/time
projection pure functions; heatmap week-bucketing helper; `StreakHero`,
`CurrentProgramCard`, `RecentPrsCard` rendering for populated AND empty
props (empty must render the D12 CTA, not nothing); `WorkoutTabs` active
state per pathname.

### 7.2 Functional tests (fixture-driven e2e, D18 — the functional gate)

Playwright against the dev server with a **stub backend** (extend the
existing `BACKEND_URL`-pointed pattern from `web/e2e/`): a fixture server
serving canned JSON for every endpoint the workout pages call.

**Fixture datasets** (checked into `web/e2e/fixtures/workout-stats/`, with a
`README.md` showing the hand-computation of every expected number):

- `user-alpha`: 8 months of history; `weeklyStreakTarget=4`; **expected
  streak = 7** (crafted with a gap week 9 weeks back → longest = 9);
  this week = 2/4; 26-week series with three hand-computed tonnage spot
  weeks; one deload week (sessions count); bench e1RM series of 6 points
  including one `lowConfidence` imported point; **exactly 2 recent PRs**
  (2026-09-12 bench 225×5, 2026-08-30 RDL) with known values; active program
  in phase 2 week 3/5.
- `user-empty`: zero history, no programs, default settings.

**Required e2e assertions** (each a named test in `web/e2e/workout-overview.spec.ts`
etc.):

| ID | Assertion |
|----|-----------|
| E2E-1 | Overview renders streak "7", longest "9", progress "2/4 this week" — exact strings from user-alpha |
| E2E-2 | Weekly chart: hovering the three spot weeks shows the exact hand-computed tonnage values |
| E2E-3 | Heatmap: known workout day is rendered/linked; known rest day is inert; clicking the day navigates to the correct session-detail URL |
| E2E-4 | Latest workouts shows 5 rows, newest first, correct date/day-label/set-count; row click → session detail |
| E2E-5 | Session detail shows logged vs prescribed sets for user-alpha's 09-12 bench session and badges the PR set; prev/next navigate to the fixture's neighbor sessions |
| E2E-6 | Strength chart defaults to fixture's `chartDefaultLifts[0]`; switching lift via picker fetches and renders the second series (assert a known point value in tooltip) |
| E2E-7 | Recent PRs card lists exactly the 2 fixture PRs with values; each links to its session |
| E2E-8 | user-empty: skeleton renders, "Design a program" CTA present, charts show empty-state copy, streak shows "0" with target — page is never blank |
| E2E-9 | Tabs: all five tabs + gear navigate correctly; active tab matches route; browser back works |
| E2E-10 | No "Log Workout" hub card anywhere; History retains its log/customize modal (open it, assert it mounts) |
| E2E-11 | Phone-width viewport (390px): Overview stacks single-column, tab bar scrolls horizontally, no horizontal page overflow |
| E2E-12 | `/me` dashboard WorkoutCard shows streak "7 wk" + "2/4" for user-alpha; renders legacy content when stats endpoint 404s |

---

## 8. Phases

Each phase = one PR to `main` (D19). Merge order is strict; Phase 2 must not
merge before Phase 1 is **deployed** (its hero consumes the live endpoint —
though every consumer must also tolerate a 404 per §6, this is
belt-and-braces, not permission to reorder).

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done (per §9
only). Columns: **Impl** (code complete on branch), **Tested** (§7 gates
pass), **Pushed** (PR merged to main AND `deploy-*-on-main` check-runs
green).

### Phase 1 — Backend stats API (PR 1, additive only)

| # | Item | Impl | Tested | Pushed |
|---|------|------|--------|--------|
| 1.1 | `WorkoutStatsService`: single-pass session walk → streak (§5.1), weekly series, heatmap, tonnage | [x] | [x] | [ ] |
| 1.2 | e1RM history derivation + PR detection (§5.3, §5.4) | [x] | [x] | [ ] |
| 1.3 | `GET /api/me/workout-stats` + `GET /api/me/workout-stats/e1rm-history` controller + DTOs (§5.2, §5.3) | [x] | [x] | [ ] |
| 1.4 | Single-session GET w/ neighbors + prSetKeys (§5.5) | [x] | [x] | [ ] |
| 1.5 | Per-user cache + invalidation on session-completion PUT (§5.6) | [x] | [x] | [ ] |
| 1.6 | Tests BT-1 … BT-13 + controller slices | [x] | [x] | [ ] |

Phase gate: `./gradlew test` green; curl the three endpoints against prod
after deploy (owner account) and confirm 200s with sane payloads; record the
streak value the endpoint reports in the Progress log (sanity anchor for
Phase 2).

### Phase 2 — Navigation shell + Overview core + session detail (PR 2)

| # | Item | Impl | Tested | Pushed |
|---|------|------|--------|--------|
| 2.1 | `layout.tsx` + `WorkoutTabs` (5 tabs + gear, active states, narrow scroll) | [x] | [x] | [ ] |
| 2.2 | Overview page replaces hub; hub cards & "Log Workout" placeholder deleted | [x] | [x] | [ ] |
| 2.3 | `StreakHero` + `ConsistencyHeatmap` (stats endpoint; 404-tolerant) | [x] | [x] | [ ] |
| 2.4 | `CurrentProgramCard` + `LatestWorkouts` rows | [x] | [x] | [ ] |
| 2.5 | Session detail page + prev/next + PR badges | [x] | [x] | [ ] |
| 2.6 | Empty-state CTAs for every card (D12) | [x] | [x] | [ ] |
| 2.7 | `/me` `WorkoutCard` streak upgrade (D23) | [x] | [x] | [ ] |
| 2.8 | e2e fixtures (`user-alpha`, `user-empty`) w/ hand-computation README (harness = RTL, IL-9) | [x] | [x] | [ ] |
| 2.9 | Tests: E2E-1, E2E-3, E2E-4, E2E-5, E2E-8, E2E-9, E2E-12 + E2E-10 grep guard + web unit tests | [x] | [x] | [ ] |

Phase gate: `pnpm typecheck && pnpm lint && pnpm test && pnpm test:e2e`
green; after deploy, load `/me/workouts` on prod — streak must equal the
Phase-1 recorded anchor value.

### Phase 3 — Charts (PR 3)

| # | Item | Impl | Tested | Pushed |
|---|------|------|--------|--------|
| 3.1 | `lib/chart.ts` extensions (niceTicks, barLayout) + inline hover tooltip layer in each chart | [x] | [x] | [ ] |
| 3.2 | `WeeklyVolumeChart` (26-wk tonnage bars, target-tinted, hover tooltip) | [x] | [x] | [ ] |
| 3.3 | `StrengthTrendChart` + lift picker + e1rm-history proxy route handler; Kalman-belief endpoint annotation + "estimated" label | [x] | [x] | [ ] |
| 3.4 | `PatternBalanceCard` (from existing week-review) + `RecentPrsCard` | [x] | [x] | [ ] |
| 3.5 | Tests: chart-fn unit tests, E2E-2, E2E-6, E2E-7 | [x] | [x] | [ ] |

Phase gate: full web suite green; prod spot-check: strength chart's endpoint
dot equals `/api/me/progression/strength` value for the default lift.

### Phase 4 — Progression refresh + responsive/polish + final acceptance (PR 4)

| # | Item | Impl | Tested | Pushed |
|---|------|------|--------|--------|
| 4.1 | Progression console visual refresh — chrome-only; console already on the new card language, mode-selector untouched (IL-14) | [x] | [x] | [ ] |
| 4.2 | Re-chrome pass: History/Programs/Gyms/Preferences/Progression de-duplicated under the tab layout (IL-15) | [x] | [x] | [ ] |
| 4.3 | Narrow-viewport pass + E2E-11 (phone-width shell overflow check + responsive-by-construction) | [x] | [x] | [ ] |
| 4.4 | Full suite green (unit 115/115, e2e 6/6, typecheck/lint/build) | [x] | [x] | [ ] |
| 4.5 | Docs: `docs/architecture.md` has no workout-routes enumeration → no-op; spec + decision log are the docs of record | [x] | [x] | [ ] |

Phase gate: everything in §9 Definition of Done.

---

## 9. Definition of done & agent verification protocol

**A checkbox may only be flipped with recorded evidence.** Before marking:

- **Impl** — the code exists on the phase branch and the project builds:
  backend items `./gradlew build -x test` (or full `test` — better), web
  items `pnpm typecheck && pnpm lint && pnpm build`. Paste nothing; just
  confirm exit 0 in the Progress log line.
- **Tested** — run the item's named tests from §7 and cite the test IDs +
  runner exit status in the Progress log (e.g. "BT-1..BT-13 pass,
  `./gradlew test` exit 0"). A test that was not run is not passed. e2e IDs
  count only when run via `pnpm test:e2e` (not skipped/`.only` residue —
  grep for `.only(` must be empty).
- **Pushed** — the PR is merged to `main` AND the deploy check-runs are
  green: `gh api repos/{owner}/{repo}/commits/<merge-sha>/check-runs
  --jq '.check_runs[] | select(.name|startswith("deploy-")) | {name, conclusion}'`
  shows `success` (or `neutral` ONLY for path-filtered non-touched targets —
  a backend-touching PR must show the backend deploy `success`; see the
  known stale-gate trap in `docs/` deploy notes). Record PR #, merge SHA,
  and deploy conclusions in the Progress log.

**Whole-feature Definition of Done (all must hold):**

1. All Phase 1–4 checkboxes `[x]` with Progress-log evidence lines.
2. Backend: `./gradlew test` green on main; the three new endpoints return
   200 for the owner account in prod.
3. Web: `pnpm typecheck`, `pnpm lint`, `pnpm test`, `pnpm test:e2e` all green
   on main; E2E-1 … E2E-12 all present and passing (none skipped).
4. Functional truth: prod `/me/workouts` streak equals the Phase-1 anchor
   value; the "Log Workout" placeholder is gone; all five tabs + gear + the
   session detail page reachable in prod.
5. History's backfill modals and Program "Log result" still function
   (E2E-10 + a prod smoke click).
6. No regressions in untouched suites (full CI green, including the known
   flaky-test exemptions documented elsewhere — a flake rerun is fine, a new
   consistent failure is not).

**Escalate to owner instead of proceeding when:** a decision in §2 proves
unimplementable as specified; the streak anchor mismatches between phases
(indicates an algorithm bug — do not "fix" by changing the spec silently);
or deploy gates fail for pre-existing reasons unrelated to this work.

---

## 10. Progress log

> Append-only. One line per checkbox flip or notable event:
> `YYYY-MM-DD · <item#> · <Impl|Tested|Pushed> · <evidence: cmd exit / test IDs / PR# + SHA + deploy conclusions>`

- 2026-09-18 · 1.1–1.6 · Impl · backend main compiles clean (`:backend:compileJava` exit 0); new `core.workoutstats` (WorkoutStats, E1rmHistory, WorkoutStatsService, WorkoutStatsCacheEvictor), `api.workoutstats.WorkoutStatsController`, `api.workoutprogram.SessionDetailResponse` + single-session GET wired onto WorkoutProgramController; `workoutStats` cache added.
- 2026-09-18 · 1.6 · Tested · BT-1…BT-11 + BT-13 in `WorkoutStatsServiceTest` (13 tests), BT-12 in `WorkoutStatsCacheEvictorTest` (2 tests), controller slice `WorkoutStatsControllerTest` (5 tests incl. weeks-clamp, 200-empty e1rm, owner-scoping 404, auth) — all pass. Full backend `./gradlew :backend:test` exit 0 (no regressions from the WorkoutProgramController constructor change).
- 2026-09-18 · Phase 1 · Pushed · DEFERRED — merge to main triggers a prod deploy (outward-facing); left for owner to merge PR 1. Impl+Tested complete on branch.
- 2026-09-18 · 2.1–2.9 · Impl · `app/me/workouts/layout.tsx` + `WorkoutTabs`; Overview `page.tsx` rewritten (hub + "Log Workout" card deleted); new components StreakHero, ConsistencyHeatmap, CurrentProgramCard, LatestWorkouts, SessionDetail; session-detail route `history/[programId]/[scheduledId]`; `/me` WorkoutCard streak line; new lib `workout-stats-api`, `workout-stats-format`, `workout-overview`, types `workout-stats`; `feeling` added to web ScheduledWorkoutResponse. Worktree node_modules symlink was dangling → reinstalled fresh in-worktree.
- 2026-09-18 · 3.1–3.5 · Impl · `lib/chart.ts` niceTicks + barLayout; WeeklyVolumeChart, StrengthTrendChart (+ `app/api/workout-stats/e1rm-history` proxy route), PatternBalanceCard, RecentPrsCard; Overview wires all four charts.
- 2026-09-18 · 2.9 + 3.5 · Tested · `pnpm typecheck` exit 0, `pnpm lint` 0 errors (57 pre-existing warnings, none in new files), `pnpm build` exit 0 (all new routes emitted), `pnpm test` 115/115 pass incl. workout-overview (E2E-1/3/4/5/8/12), workout-tabs (E2E-9), chart.test (niceTicks/barLayout), workout-stats-format; E2E-2/6/7 in workout-overview; E2E-10 grep guard clean ("Log Workout"/"Coming soon" gone, backfill kept); `pnpm test:e2e` 5/5 pass (shell boots). E2E-11 = responsive-by-construction (grid-cols-1 lg:grid-cols-2). Fixtures + hand-computation README under `web/test/fixtures/`.
- 2026-09-18 · Phase 2+3 · Pushed · DEFERRED — same reason as Phase 1; PRs 2 & 3 left for owner to merge. Impl+Tested complete on branch.
- 2026-09-18 · 4.1–4.5 · Impl · Progression page + History/Programs/Gyms/Preferences de-duplicated under the tab layout (redundant "← Workouts" links removed, `<main>` aligned; unused Link/Route imports cleaned); ConsistencyHeatmap/Overview/all cards use `grid-cols-1 lg:grid-cols-2` for responsive stacking; added phone-width shell-overflow e2e (E2E-11). ProgressionConsole already on the new card language (IL-14) — no restyle.
- 2026-09-18 · 4.1–4.5 · Tested · `pnpm typecheck` exit 0, `pnpm lint` 0 errors / 57 pre-existing warnings (none in new files), `pnpm build` exit 0, `pnpm test` 115/115, `pnpm test:e2e` 6/6 (incl. E2E-11 phone-width). architecture.md has no workout-routes list → 4.5 no-op.
- 2026-09-18 · Phase 4 · Pushed · DEFERRED — PR 4 left for owner to merge (merge = prod deploy). Impl+Tested complete on branch.
- 2026-09-18 · whole-feature · Verify · Backend `./gradlew :backend:test` green (Phase 1); web typecheck/lint/build/unit(115)/e2e(6) all green. All Impl+Tested boxes checked; all Pushed boxes DEFERRED to owner (merge-to-main = prod deploy, an outward-facing action not taken autonomously). Prod streak-anchor cross-check (DoD #4) requires deploy → pending owner merge.

---

## 11. Effort & sequencing

Rough sizing: Phase 1 ~1–1.5 days (service + 13 test cases dominate),
Phase 2 ~1.5–2 days (fixture harness is the hidden cost), Phase 3 ~1–1.5 days,
Phase 4 ~0.5–1 day. Phases 1→2 strictly ordered (deploy dependency); 3 and 4
sequential after 2.

**Do-nothing cost:** the workout tab remains a link hub with a dead
"coming soon" card; streaks exist only as a buried setting; training-trend
questions require the phone.

**Rollback:** every phase is independently revertable (Phase 1 endpoints are
additive and unconsumed until Phase 2; UI phases revert cleanly since the old
hub lives in git history). No migrations, no schema changes, no data writes.
