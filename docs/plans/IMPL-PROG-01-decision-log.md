# IMPL-PROG-01 — Progression Engine implementation decision log

> Working log of decisions taken **during implementation** so work could proceed
> without stopping to ask. Each entry: the question, the decision, the rationale.
> Review these after implementation to decide what to tweak. Interview-locked
> decisions live in the [plan](./IMPL-PROG-01-autoregulated-progression-engine.md)
> (D1–D25); this log records the finer calls made while writing code.

**Branch:** `workout-progression-system`
**Started:** 2026-09-05

---

## Meta / scope decisions

### M1 — Backend-first, fully verified; clients staged behind the locked contract
**Question:** The feature spans backend (Java/Spring/Firestore), Android (Compose), and web (Next.js). What is the verifiable unit of delivery?
**Decision:** Build the **backend progression engine** as the primary, fully-unit-tested deliverable (it is the wire contract and holds all the logic), verified with `./gradlew test`. Client (Android/web) RIR relabel + UI are implemented against the locked backend contract afterward; their build/verification is called out honestly in the dashboard rather than claimed as green if not run here.
**Rationale:** The engine is the substance and the only part with non-trivial logic to get right; it is verifiable in this environment. Clients are largely mechanical wiring/relabel once the contract is fixed.

### M2 — RIR added additively; RPE retained as legacy with on-read conversion (refines D1)
**Question:** D1 says "remove RPE." Physically deleting `rpe` from `LoggedSet` touches ~25 backend call sites (importer, digest, completion, validator, tests) plus Android + web + Firestore mappers — a large, risky, build-breaking change.
**Decision:** Add `rir` + `rirSource` to `LoggedSet` **additively** (appended, convenience constructors preserved). Keep `rpe` as a legacy nullable field. The engine consumes `LoggedSet.effectiveRir()` = `rir` if present, else `10 − rpe` (the D1 backfill formula applied lazily on read). New logging writes `rir`; the RPE→RIR *UI* relabel happens on the clients. A destructive `rpe` column drop is deferred one release (per plan §13).
**Rationale:** Delivers the engine's RIR currency and the exact `10−rpe` semantics with zero blast radius on existing code/data. Faithful to D1's intent (engine runs on RIR, history converts) without a risky big-bang migration. **Flag for review:** if you want the physical `rpe` removal now, it's a follow-up sweep.

### M3 — Maintenance cold-start: Mifflin-St Jeor ×1.5 when profile allows, heuristic fallback (REVISED after review 2026-09-05)
**Question:** D7 names Mifflin-St Jeor as the cold-start, but the `User` record had only `heightCm` — no age or sex.
**Original decision (build time):** dependency-free `15 kcal × bodyweight_lb` heuristic; adaptive-from-weight-trend overwrites within ~2–3 weeks.
**Revised decision (owner review):** Add `biologicalSex` (MALE/FEMALE) + `dateOfBirth` to the User profile and compute the cold-start with **Mifflin-St Jeor × 1.5** (moderately-active multiplier) when sex + DOB + weight + height are all present; fall back to the `15 kcal/lb` heuristic when any is missing. Adaptive-from-weight-trend still overwrites once enough data accrues. Profile settings gain a sex + birthdate input (web + Android).
**Rationale:** Owner chose the more accurate first-2–3-week cold-start; the heuristic remains as a safe fallback so users who don't fill the profile still get a sane number.

---

## Verification checkpoints

- **2026-09-05 (M3 change)** — `./gradlew test` full suite **BUILD SUCCESSFUL** after the Mifflin cold-start change: added `BiologicalSex` + `dateOfBirth` to `User` (additive ctor), Firestore mapper read/write + `updateDemographics`, in-memory fake, profile API (`WhoAmI` GET/PATCH now accept/return sex+DOB), `MaintenanceCalorieEstimator` Mifflin-St Jeor ×1.5 (heuristic fallback), new `MaintenanceCalorieEstimatorTest` (Mifflin value + heuristic fallback). Fixed a `WhoAmIController` PATCH regression (echo intended state instead of read-back, since the in-memory fake no-ops on an absent user doc).
- **2026-09-05 (M3 clients)** — **Web** sex+birthdate profile inputs: `pnpm run typecheck` (tsc --noEmit) exit 0, independently re-run — PASS (new `BodyDetailsForm` + `saveBodyDetails` server action PATCHing `/api/me`). **Android** sex(FilterChip)+birthdate(DatePicker) profile inputs: `./gradlew :core-data :core-domain :feature-settings :compileDebugKotlin` **BUILD SUCCESSFUL** (independently re-run) + existing `ProfileViewModelTest` passes. Both wire into the existing profile PATCH mirror/outbox path.
- **2026-09-05** — `./gradlew test` (full backend suite) **BUILD SUCCESSFUL**; the 25 progression golden/integration tests pass (ProgressionMathTest, DoubleProgressionTest, PrescriptionCalculatorTest, BlockAndWeekLogicTest, SessionLoopTest). No regressions in the existing suite after the additive `LoggedSet.rir`/`Prescription.rationale` changes and the completion-service event wiring. Fixed one real robustness bug found by the integration test: `SessionAnalysis.lastWorkingSet` now resolves tied/absent `completedAt` to the later list position (sets are logged in performed order).

## Owner review (2026-09-05) — ratifications

Interviewed the owner on the flagged decisions. **Ratified as-is:** M2 (keep RIR additive, rpe as legacy — the owner's earlier "remove RPE" is satisfied by the UI relabel + one-release-later column drop), B1 (lab gates stay deferred), P2 (keep the eager deload trigger, no two-week confirmation), L1/L2 (keep name-based increment heuristic + per-user override), P1 (Kalman prescribes toward the band midpoint). **Changed:** M3 (see revised entry above — add sex+DOB+Mifflin cold-start).

## Implementation decisions

### L1 — Equipment classification from exercise NAME, not equipment ids
**Question:** Deriving `loadIncrement`/`loadOffset` needs to know the implement, but `Exercise.requiredEquipment` holds opaque equipment-catalog ids at runtime (resolved from names only at seed time). Reading the Equipment catalog to reverse them would couple the engine to another module and add lookups.
**Decision:** Classify off `Exercise.nameLower` substrings ("barbell", "dumbbell", "machine", "cable", "leg press", …) plus `isTimed`/`movementPattern`. Names in this catalog are descriptive and reliable.
**Rationale:** Zero cross-module coupling, robust for the seeded catalog. **Flag:** a user-added exercise with an unconventional name falls to the 5 lb default; the per-user override (D8) is the escape hatch.

### L2 — Load offset is 0 for external-load lifts; bodyweight only for BW movements
**Question:** The spec's `loadOffset` = bar weight / machine baseline. But does this app log total load or added load?
**Decision:** This app logs the TOTAL external weight (`weightLbs`), so barbell/dumbbell/machine `loadOffset = 0`. Only bodyweight-loaded movements (pull-ups, dips, push-ups) get `loadOffset = current bodyweight` (from body-comp, KG→LB), so their effective load and bodyweight changes flow into e1RM automatically (§4.4). Unknown bodyweight → 175 lb default.
**Rationale:** Matches how the logger already records weight; avoids double-counting the bar. Keeps the one concrete body-comp win the spec calls out.

### L3 — `progressionEligible` = not timed and not MOBILITY/STRETCH/CARDIO; block-type gating separate
**Question:** D21 excludes warm-up/timed/skill work. Where is that decided?
**Decision:** Two layers. (a) Per-exercise: `LoadingProfileResolver` sets `progressionEligible = !isTimed && movementPattern ∉ {MOBILITY,STRETCH,CARDIO}`. (b) Per-block: the session loop additionally skips prescriptions in `WARMUP/MOBILITY/CARDIO/COOLDOWN/STRETCH` blocks (only `MAIN/ACCESSORY/CORE` progress), because the same exercise can appear as a warm-up or a working set.
**Rationale:** Eligibility is a property of both the movement and its role in the day; both gates are cheap and independent.

### P1 — Kalman prescribes toward the rep-band MIDPOINT; shadow predictions are single-phase
**Decision:** `PrescriptionCalculator` computes load for `band.mid()` reps at the target RIR (heavy enough to progress, light enough to complete the range). PredictionLog is written at completion (single-phase, retrospective): for the just-completed exercise it records what each model predicted the user would achieve at the load that WAS prescribed vs. what actually happened — kalman from the prior belief, double-progression as naive band-midpoint persistence. This yields real MAE evidence without a two-phase predict-then-reconcile flow.
**Rationale:** Simpler, and matches §10's framing ("predicts the reps the user will achieve at the load the deterministic path prescribed").

### P2 — Deload trigger fires on FALLING, or FLAT+rising-fatigue; "two consecutive weeks" deferred
**Decision:** The week loop deloads when trend is FALLING, or when FLAT with a rising fatigue index — suppressed on FLAT when the block success criterion is HOLD_LOAD_AT_LOWER_RIR (deficit; flat = success, D11). FALLING still deloads even in a deficit (genuine regression). The spec's "two consecutive weeks" refinement needs stored trend history and is deferred.
**Rationale:** Captures the high-value behavior (deload on real stalls, never through a cut) without adding trend-history storage in v1. **Flag:** slightly more eager than the two-week rule; revisit with the replay harness.

### P3 — Block/week params recomputed opportunistically on completion, not via a cross-user @Scheduled sweep
**Question:** D22 proposed an in-process `@Scheduled` sweep for the week/block loops, but a time-based sweep needs to enumerate all users, and the per-user repositories don't support that cheaply (n=1 app).
**Decision:** Recompute block params (if stale >7 days) and week params (if stale >3 days) opportunistically at the start of processing a completed session, inside the `ProgressionEngine` facade. Idempotent and always fresh; no scheduler, no user enumeration.
**Rationale:** Simpler and correct for n=1. **Flag:** if this grows to many users, move to a real scheduled sweep keyed off a user index (the loops are already separable).

### L4 — Kalman process variance as a fraction of e1RM² per day
**Decision:** `process_variance = 0.0004 · e1rm² · days` (≈2%/day sd growth at the belief scale). A starting value to tune against the replay harness (§10); it sets how fast layoffs decay confidence so missed weeks self-heal (§6.2).
**Rationale:** Spec leaves the constant open ([Guessing]); this magnitude re-converges in ~2–3 sessions after a multi-week gap, matching the design intent.

### A1 — API under `/api/me/progression`, not the spec's `/api/v1/progression`
**Decision:** The engine's own read/override endpoints use the app's `/api/me/...` convention (state, week-review, block-parameters GET/PUT, reset). The spec's `/api/v1/...` is the THIRD-PARTY read API (a separate surface); the first-party app uses `/api/me`.
**Rationale:** Matches every existing first-party controller (`/api/me/workout-programs`, `/api/me/nutrition`, …) and the `CurrentUserProvider` auth pattern.

### B1 — Lab/body-composition gates (§8.3) deferred; hook present, no markers read in v1
**Question:** The block loop is where labs enter, as gates only. But there is no validated marker→action mapping, and DailyMetric reads are partially stubbed.
**Decision:** Ship the block loop WITHOUT concrete lab gates in v1. The structure is in place (block loop is the only place they'd enter) but it reads no blood/DEXA/HRV markers yet. Energy-balance-driven mode selection is fully implemented.
**Rationale:** The spec itself says labs are gates with no validated numeric mapping [Certain]; wiring a concrete gate needs product-chosen thresholds. Deferring avoids inventing thresholds. **Flag:** add a RECOVERY-forcing gate (e.g., sustained elevated RHR or an illness flag) when thresholds are agreed.

### M4 — Client work delegated + verification honesty
**Decision:** Backend is the fully-verified deliverable (`./gradlew test` green). The Android + web client changes (RPE→RIR relabel, last-set RIR entry, rationale/confidence/arrow display, review/block screens) are implemented against the locked backend contract via subagents; web is typecheck-verifiable in-repo, Android compile depends on SDK/network availability in this environment. Any client workstream not observed to build is marked 🟨 (not ✅) in the dashboard — never reported as done without verification.
**Rationale:** Honors the "verify before marking done" instruction; the backend contract is stable so client wiring is low-risk mechanical work.
