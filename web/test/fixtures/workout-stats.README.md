# Workout-stats fixtures — hand-computed expected values

Backs the Overview functional tests (IMPL-WEB-WORKOUT-01 §7.2). These are the
numbers the component tests assert, derived by hand so a test failure means the
UI is wrong, not the fixture.

## `user-alpha`

- **Reference dates.** Fixture "today" = `2026-09-16` (Wednesday). Its ISO week
  (Monday-start) begins `2026-09-14` = the current week.
- **Streak.** `weeklyStreakTarget = 4`. Seven fully-elapsed weeks before the
  current one each met the target → `current = 7`. The current week has 2 of 4
  completed → `thisWeekCompleted = 2`, and 2 < 4 so it does not extend the
  count. A 9-week qualifying run earlier in history (before a gap) is the
  all-time best → `longest = 9`.
- **Weekly series.** 26 weeks, oldest→newest, current week last. Zero except the
  spot weeks the chart test hovers:
  - `2026-07-06`: 51,000 lb / 5 sessions
  - `2026-08-31`: 38,000 lb / 4 sessions
  - `2026-09-07`: 42,150 lb / 4 sessions
  - `2026-09-14`: 21,075 lb / 2 sessions (current, in progress)
- **Heatmap.** Two workout days: `2026-09-12` (→ session `2026-09-12_d1`) and
  `2026-08-30` (→ `2026-08-30_d3`). Every other day in the 26-week grid is a
  rest day (inert cell, no link).
- **Recent PRs (exactly 2).**
  - Bench `2026-09-12`: 225 × 5 → e1RM `225 × (1 + 5/30) = 262.5`.
  - RDL `2026-08-30`: 315 × 5 → e1RM `315 × (1 + 5/30) = 367.5`.
- **Bench e1RM curve.** 6 points; the earliest (`2026-05-04`, 205 lb) is
  weight-only → `lowConfidence = true`. Current Kalman belief = 265 lb.
- **Squat curve** (loaded when the picker switches to Back Squat): current
  belief 405 lb — the value E2E-6 asserts after the switch.
- **Session `2026-09-12_d1`.** Bench, prescribed 3 × 5 @ 215 lb. Logged
  185 × 5 then 225 × 5; the 225 × 5 (block `b1`, prescription `0`, set index
  `1`) is the PR set → `prSetKeys = ["b1:0:1"]`. Prev = `2026-08-30_d3`; no next.
- **Latest 5 sessions.** Newest first: 09-12 Push A (2 sets), 09-10 Legs (3),
  09-08 Pull A (4), 09-05 Push B (2), 08-30 Pull B (5).

## `user-empty`

Brand-new user: zero history, no programs, default target 4. Every card must
render its empty state and the page must not blank (streak shows 0, program card
shows the "Design a program" CTA).

## Test ID mapping (E2E-n → file)

E2E-1 StreakHero · E2E-2 WeeklyVolumeChart · E2E-3 ConsistencyHeatmap ·
E2E-4 LatestWorkouts · E2E-5 SessionDetail · E2E-6 StrengthTrendChart ·
E2E-7 RecentPrsCard · E2E-8 empty-state suite · E2E-9 WorkoutTabs ·
E2E-12 WorkoutCard. E2E-10 (no "Log Workout" card) is a grep guard in the phase
gate; E2E-11 (narrow viewport) is covered by the responsive `grid-cols-1
lg:grid-cols-2` construction. See decision log IL-9.
