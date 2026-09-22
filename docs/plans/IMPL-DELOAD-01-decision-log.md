# IMPL-DELOAD-01 — Implementation decision log

> Companion to `IMPL-DELOAD-01-real-deloads-and-progression-audit.md`. Every
> non-obvious implementation decision made autonomously during the build is
> recorded here for owner review, numbered `DL-#`. Owner-locked product
> decisions (D1–D4) and plan-time derived decisions (DD-1…DD-7) live in the
> spec §2.

Legend: ✅ locked & implemented · ⚠️ deviation/judgment call needing owner review
· 🔵 informational.

---

## DL-1 ✅ — Writeback keeps a back-compat 4-arg overload

`ProgressionWriteback.applyNextPrescription(user, exercise, load, afterDate)`
now delegates with `incrementLbs=0` (deload load then floors with increment 0 =
raw ×0.9, no snapping). Production always calls the 5-arg form (SessionLoop
passes `profile.loadIncrementLbs()`); the overload exists only so pre-existing
tests compile unchanged.

## DL-2 ✅ — Deload observations still feed `priorSessionFailedBottom`

The two-consecutive-failures rule scans all observations, including flagged
DELOAD ones. Accepted: deload work is light/high-rep, so it reads as in-range,
never as a bottom-miss — it cannot trigger a spurious −increment. Not worth a
special case.

## DL-3 ✅ — A deload completion writes NO ProgressionState doc at all

D3 said "uncertainty grows with elapsed time". Implemented by not touching the
state doc on deload sessions: the *next real* update computes drift from the
pre-deload `lastObservedAt`, so the deload gap's sigma growth lands then. Avoids
a doc write and a version bump for a no-op correction.

## DL-4 ✅ — DD-5 set-cut scope

Materialization halves sets only for MAIN/ACCESSORY/CORE blocks (mirrors the
engine's D21 eligible set), clamps to min 1, skips set-less (timed/reps-null)
prescriptions, and honors a per-prescription `DeloadModifier.setsMultiplier`.

## DL-6 ✅ — Session-detail target-vs-achieved is a delta line, not a redesign

The detail page already rendered `@ <target> lb` in the prescribed summary. P2
adds (a) a `did X vs target Y (±Z lb)` line only when they differ, (b) the
`loadBasis` provenance line, (c) the Deload badge — reusing ProgramThisWeek's
warn-badge style. No layout overhaul.

## DL-7 ✅ — Heatmap deload marking is data-attribute + tooltip, not a new color

`HeatmapDay` gained `isDeload` (backend `Scan.CompletedSession` carries
`sw.isDeload()`; the day takes its representative session's flag). The web cell
gets `data-deload` + a "· deload" tooltip/aria suffix — intensity tinting is
untouched (a deload day is still a workout day).

## DL-8 ✅ — Progression log drilldown location + navigation

New route `/me/workouts/progression/log/[exerciseId]` (server component, force-
dynamic) rendering a presentational `ProgressionLogTable` (RTL-testable, per
IL-9 house pattern). Entry point: the progression console's strength-card rows
are now links (`data-testid="strength-row-link"`). Per-hand lifts render
`total (X/hand)` via the existing `lib/per-hand.ts`.

## DL-9 ✅ — DD-3 compat gate PASSES: P1 can ship without waiting for an app release

Verified in code + test: Android's `PrescriptionRationale.path` is a raw
pass-through `String?` and `direction`/`confidence` decode via
`parseEnum(…, fallback)` — `WorkoutProgramMapperTest."deload rationale path
passes through and unknown enums fall back"` proves a `DELOAD` (or entirely
unknown) path can never crash an older app. No release-ordering constraint.

## DL-10 ✅ — Deload voice cue is a code constant, not a string resource

`DELOAD_START_CUE` ("Deload week. Today is lighter on purpose — your plan
resumes next session.") lives in WorkoutSessionScreen.kt as an internal const:
the announcer speaks it verbatim and tests assert it; it is not rendered UI
text. The visible banner/pill strings ARE resources
(`workout_session_deload_pill` / `workout_session_deload_banner`).

## DL-11 ✅ — Deload cue speaks only on the Start-workout tap

The cue rides the existing session-start announcement (the "Start workout"
button), leading before the opening exercise cue. Resuming a session mid-way
does not re-announce (same policy as the exercise cues). The persistent banner
covers re-entry.

## DL-12 🔵 — Android build in this worktree needed local bootstrap

Worktree gotcha (gitignored files): created `android/local.properties` with
`sdk.dir` + `webOauthClientId` (extracted from the checked-in
`google-services.json` client_type-3 entry, since headless gcloud user auth
can't hit Secret Manager). No code impact; recorded for reviewers re-running
the P3 gates.

## DL-5 ✅ — Progression log endpoint semantics

`GET /api/me/progression/log?exerciseId=` scans programs **including archived**
(performed history is history), keeps rep-based prescriptions only
(`durationSeconds == null`), returns newest-first, capped at 100 rows. Rows
carry nullable target fields (pre-P0 history) and per-hand totals
(`targetTotalLbs`/`topSetTotalLbs`) per IMPL-PROG-LOAD-01 D9. Derived on read —
no new storage, no cache (the underlying repo scan is the same one History
already does; add caching later if it shows up in latency).
