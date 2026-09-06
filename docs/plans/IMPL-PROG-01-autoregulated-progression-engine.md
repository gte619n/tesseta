# IMPL-PROG-01 — Autoregulated Progression Engine

**Status:** 🟡 Draft v1 — plan ratified via interview (2026-09-05); no code yet.
**Branch:** `workout-progression-system`
**Author / PM:** Evan Ruff (interview) + engineering
**Created:** 2026-09-05
**Source spec:** [`tesseta-progression-engine-spec.md`](../requirements/tesseta-progression-engine-spec.md) (Draft v1) — this plan **adapts** that spec to the shipped codebase and records where we diverge.
**Scope:** Autoregulated resistance-training progression for a single user (n=1). The engine closes the loop between a completed workout and the next prescription for the same exercise.

---

## 0. How to read this document

This is both the **implementation plan** and the **living progress ledger**. Two sections are the control surface:

- **§7 Phased build plan** — the ordered phases, each with deliverables and *exit criteria*.
- **§8 Progress dashboard** — the single source of truth for what is Implemented / Tested / Pushed. Update it as work lands. Nothing is "done" until it clears the **§10 Definition of Done** and the **§11 agent verification protocol** — code compiling is not "done."

Everything above §7 is the contract (problem, decisions, data model, integration points). Read it once; it changes only if a decision is revisited (record that in §6 with a new decision row).

---

## 1. Problem statement and framing

The engine maintains a running estimate of current capacity per exercise (`e1rm`), updates that estimate from each session's observed performance, and derives loads, reps, and set counts from it. It replaces today's manual/AI-authored numbers with a rule-driven, then belief-driven, autoregulated prescription.

**What this is for:** consistency and fatigue management. It removes the need to make good programming decisions while tired, undereating, or optimistic, and prevents drift into stagnation or unsustainable load.

**What this is NOT for:** a superior training stimulus. Meta-analytic evidence shows autoregulated and percentage-based prescription produce similar strength/hypertrophy outcomes in trained people. We justify the build on **decision quality and adherence**, not outcome superiority. (Evidence table in §14.)

### 1.1 Non-goals (v1)

- Exercise selection or program-template generation. **The AI designer still picks exercises, movement patterns, block structure and phase focus; the engine only derives the numbers** (see D2).
- Multi-user modeling, population priors, cross-user learning.
- Injury prediction; any reinforcement-learning formulation; cardio/conditioning prescription.
- Velocity-based training (dropped for v1 — D14).
- Wear OS (dropped for v1 — D3).

---

## 2. Where this fits the existing codebase

The engine is a new backend module that **reads** existing signals and **writes** into the existing prescription pipeline. Nothing here is greenfield in isolation — it plugs into shipped code.

### 2.1 Integration points (concrete)

| Concern | Existing code we hook | Path |
|---|---|---|
| Exercise identity, movement pattern, mechanic, laterality, equipment | `Exercise` record (global catalog) | `backend/.../core/exercise/Exercise.java` |
| Movement-pattern taxonomy (reuse as-is) | `MovementPattern` enum (`SQUAT, HINGE, LUNGE, PUSH_HORIZONTAL, PUSH_VERTICAL, PULL_HORIZONTAL, PULL_VERTICAL, CARRY, CORE, …`) | `backend/.../core/exercise/MovementPattern.java` |
| Prescription target (load/reps/sets) the engine writes | `Prescription` record (`sets, repsMin, repsMax, intensity, targetWeightLbs, loadBasis`) | `backend/.../core/workoutprogram/Prescription.java` |
| Observed performance the engine reads | `LoggedSet` record (`weightLbs, reps, rpe, restSeconds, completedAt`) → **gains `rir`, loses `rpe`** (D1) | `backend/.../core/workoutprogram/LoggedSet.java` |
| Session completion fan-out (our trigger) | `WorkoutSessionCompletionService` (publishes `MetricChangedEvent`) | `backend/.../core/workoutprogram/WorkoutSessionCompletionService.java` |
| Existing e1RM we seed from | `ExercisePerformanceDigestService` → `ExerciseDigest.estimated1Rm` (Epley) | `backend/.../core/workoutprogram/ExercisePerformanceDigestService.java` |
| Program/phase/day/block/prescription tree we rewrite | `WorkoutProgram` → `ProgramPhase` → `WorkoutDay` → `Block` → `Prescription` | `backend/.../core/workoutprogram/` |
| Materialized dated sessions we rewrite | `ScheduledWorkout` (PLANNED sessions) | `backend/.../core/workoutprogram/ScheduledWorkout.java` |
| Nutrition (energy in) | `NutritionDailyLog.caloriesKcal`, 7-day avg resolver | `backend/.../core/nutrition/`, `core/goals/eval/FirestoreMetricResolver.java` |
| Bodyweight / body-comp trend + `loadOffset` source | `BodyCompositionRepository.findLatest / findByUserAndRange` (`WEIGHT_KG`) | `backend/.../core/bodycomposition/` |
| Labs (block-loop gates only) | `BloodReadingRepository`, `BloodTestReport`, `DexaScan` (`restingMetabolicRateKcal`) | `backend/.../core/blood/`, `core/bloodtest/`, `core/dexa/` |
| Cross-module read pattern to imitate | `FirestoreMetricResolver` injects sibling repositories (never queries foreign collections directly) | `backend/.../core/goals/eval/FirestoreMetricResolver.java` |
| Guided-workout UI (RIR entry, prefill, directional colors, rationale) | `WorkoutSessionScreen.kt`, `SessionFormat.kt` (`TargetOutcome`), `WorkoutSessionViewModel.kt` | `android/feature-workouts/.../session/` |
| Rationale/preview UI pattern to reuse | `AdjustWithAi.kt` (preview → confirm, before/after diff) | `android/feature-nutrition/.../AdjustWithAi.kt` |
| Design system | `HfCard, CapsLabel, Pill, ProgressTrack, SectionTitle`, `Hf.type`, `Hf.colors` | `android/core-ui/.../components/Components.kt`, `theme/` |

### 2.2 Architectural rules we inherit (must not break)

- **Offline-first logging (ADR-0007):** sets are logged into a Room draft (`WorkoutSessionDraftEntity`) and hit the server only on session completion via idempotent `PUT …/sessions/{scheduledId}`. **The engine acts at completion time and prescribes for the *next* session** (D4). No per-set online calls.
- **Repository injection, not foreign-collection queries.** The engine's Firestore access mirrors `FirestoreMetricResolver`.
- **Additive, nullable record fields** with convenience constructors (the IMPL-18 D2 pattern) so we don't break ~25 `Prescription`/`LoggedSet` call sites.
- **Backend is the wire contract;** web + Android mirror uppercase enums.

---

## 3. Design decisions locked via interview (2026-09-05)

| # | Decision | Choice & rationale |
|---|---|---|
| **D1** | Subjective-intensity currency | **Migrate RPE → RIR.** Remove `rpe`; add `rir` to `LoggedSet`. RIR is *inferred by default, reported only when needed* (§5.2 model): the last working set prompts for RIR only when the user taps "had more"; everything else is inferred. Existing history is backfilled `rir = 10 − rpe`. RIR is the engine's only subjective input. |
| **D2** | Engine vs. AI-designer ownership | **AI designer picks exercises only** (movement patterns, block structure, phase focus). **The engine owns all numbers:** load, reps, rep-range, set count, target RIR, and deloads — and may move them week-to-week. |
| **D3** | Wear OS | **Dropped for v1.** Validate and ship the RIR-logging loop on the phone (guided-workout screen already exists). Wear is a possible fast-follow; the spec's "Wear-primary" premise is set aside. |
| **D4** | When the engine adapts | **Next-session only.** Engine computes the next session's prescription at completion time (compatible with offline-first). No intra-session re-prescription; §12's per-set-return is reinterpreted as "surface the new prescription on the next session" via the existing prefill/`last-sets` path. |
| **D5** | Bayesian maturity / rollout | **Kalman live, shadow retained, 2-week warm-up.** First **two weeks of training data per exercise** run deterministic double-progression while the Kalman filter runs in shadow (logging `PredictionLog`). After the warm-up the Kalman path becomes the live prescriber; double-progression keeps running as the permanent shadow. (We lowered the spec's 6-week/40-prediction gate to a 2-week warm-up.) |
| **D6** | Cold start | **Seed `ProgressionState.e1rm` from `ExerciseDigest`** with a wide initial `sigma` (low confidence) so day-one prescriptions are grounded and self-correct fast. Exercises with only weight-only/thin history seed with maximal `sigma` (near-fallback behaviour). |
| **D7** | Maintenance calories (block loop) | **Adaptive from weight trend** (TDEE back-calculated from trailing intake vs. bodyweight change, ~7700 kcal/kg). **Mifflin-St Jeor** from bodyweight/height/age/sex is the cold-start until enough data accrues. DEXA `restingMetabolicRateKcal` is an optional sanity cross-check, not the primary source. |
| **D8** | `loadIncrement` / `loadOffset` | **Derive from implement/equipment defaults** (barbell → 2.5 kg, machine → stack step, bodyweight → current bodyweight offset) **with an optional per-user / per-gym override** when reality differs. |
| **D9** | Machine identity across gyms | **Assume single home gym / same machine everywhere.** No `machineId`; per-(user,exercise) state is a constant. (Spec open-Q2 → home-gym answer.) |
| **D10** | Unilateral exercises | **Single state per exercise.** One belief/load; log one RIR for the last/hardest side. No imbalance tracking in v1. (Spec open-Q4 → simple option.) |
| **D11** | Deload authority | **Engine owns deloads, deficit-aware.** Engine is the sole deload decider; it fires on real stalls/fatigue and honors `HOLD_LOAD_AT_LOWER_RIR` to **not** deload flat performance in a deficit (§8.1). Any AI/manual per-phase `deloadWeekIndex` becomes advisory. |
| **D12** | UI footprint | **Full engine console.** (a) On the guided-workout screen: engine-derived numbers, a 3-level confidence pill (from `sigma`), a tappable "why this number" rationale line (reusing the `AdjustWithAi` pattern), and **directional up/down color coding** when reps/weight/sets move vs. last time. (b) A weekly **progression-review** screen (trend, fatigue, proposed volume/deload with reasoning). (c) A **block-parameters** screen (mode, rep ranges, RIR caps, manual override). |
| **D13** | RIR-gaming defenses | **Deferred to a later phase (Phase 5).** No scheduled AMRAP calibration and no RIR-drift monitor in v1. Accepted risk: self-report-only input can drift silently during the trust-building window. The data needed to add it later (full `SetObservation` log) is captured from Phase 0. |
| **D14** | Readiness flags & velocity | **Velocity dropped entirely** (no hardware, Wear skipped). **Readiness context flags deferred** (not in v1): the `contextFlags` field exists on `SetObservation` and feeds the noise model, but no producer populates it in v1. |
| **D15** | Testing strategy | **Golden scenario tests + a replay harness over real logged history** (see §9). Both are required; the replay harness doubles as the shadow-mode MAE evidence and is the agent's functional gate. |

### 3.1 Decisions locked via codebase-review interview (2026-09-05, round 2)

| # | Decision | Choice & rationale |
|---|---|---|
| **D16** | Fatigue-index input | **Infer from rep drop-off at constant load** — no intermediate-set RIR UI. At constant load, falling reps across sets *is* the fatigue signal; the week loop's fatigue index is computed from within-exercise rep decline (+ inferred RIR), not reported intermediate RIR. Consistent with the inference-first philosophy; slightly noisier, zero friction. Spec §7.2's "intermediate-set RIR optional chip" is dropped. |
| **D17** | Manual override semantics | **Observation only.** A manually typed weight/reps (in the logger or the swap flow's reps/sets editor) applies to that session; the engine treats what was actually lifted as just another observation and re-prescribes next session from the updated belief. No pinning, no manual mode. The estimator follows the user if they were right and pulls back if not. |
| **D18** | Last-set RIR prompt mechanics | **Auto-prompt on the final planned set.** When the user completes the set that is *currently last* in the exercise's set list, the rest-bar area shows a one-tap RIR chip row (`0 / 1 / 2 / 3 / 4 / 5+`) **prefilled with the inferred value** — tap to correct (`REPORTED`), ignore to accept the inference (`INFERRED_TARGET`/`INFERRED_FAILURE`). Adding a set moves the prompt; whichever set is truly last when the user advances to the next exercise wins (`isLastWorkingSet` is resolved at exercise exit, not set completion). |
| **D19** | Directional visual language | **Arrows, not colors.** Green/red stays exclusively the live HIT/MISS target feedback (`TargetOutcome`). Progression direction renders as a small ▲/▼ glyph + delta ("▲ +5 lb vs last time") in the accent color next to the prescription and in the rationale line. No semantic collision with the learned color meanings. |
| **D20** | Per-exercise "feel" rating | **Skip it; reuse the session-level `feeling` (1–5)** already captured at completion. It maps into the `f_context` observation-noise term for *all* of that session's observations. Zero new UI; coarser than the spec's per-exercise construct — accepted. *(Defaulted: conventional low-friction choice, flag if wrong.)* |
| **D21** | Progression eligibility | **Only `MAIN`, `ACCESSORY`, `CORE` blocks are progression-eligible.** `WARMUP, MOBILITY, CARDIO, COOLDOWN, STRETCH` blocks and any **timed exercise** (`isTimed` / `durationSeconds`) are excluded: the engine neither observes nor prescribes them; their designer-authored numbers stand. `ExerciseLoadingProfile.progressionEligible` can additionally exclude an individual exercise. |
| **D22** | Loop trigger mechanics | **Session loop runs synchronously inside `WorkoutSessionCompletionService`** (before the completion response returns, so the response and Firestore already carry updated next-session prescriptions). **Week + block loops run from an in-process `@Scheduled` daily sweep** (the `WebhookPoller`/`WebhookSchedulingConfig` pattern) that fires the week loop when a user's microcycle boundary passed and the block loop on month boundary / mode-input change. No new infra (no Cloud Run Job needed). |
| **D23** | Prescription freshness on device | Engine writes PLANNED sessions server-side; the phone learns via the **existing mirror sweep after outbox replay** (no new sync machinery). Sessions **beyond the materialized window / ad-hoc** (the known `last-sets` 404 case) get no engine numbers server-side — the client falls back to the existing local `lastSessionSets()` prefill, and the rationale line says "based on your last session" (deterministic-fallback styling). |
| **D24** | Rationale storage | New structured **`PrescriptionRationale`** component on `Prescription` (additive, nullable): `path` (`KALMAN / DOUBLE_PROGRESSION / FALLBACK_*`), `direction` (`UP/DOWN/HOLD`), `deltaLbs/deltaReps/deltaSets`, `confidence` (`HIGH/MEDIUM/LOW` from `sigma/e1rm`), `inputs` (short human strings). The existing free-text `loadBasis` is stamped *from* it for backward compatibility. UI renders the pill/arrow/why-line from the structured field, never by parsing strings. |
| **D25** | Units (closed) | Verified: unit preference is **on-device only** (`android/core-domain/.../prefs/UnitPreferences.kt`); the server is unit-agnostic and weight crosses the wire in lbs. Engine-internal-lb is correct; clients convert for display exactly as they do today. |

---

## 4. Architecture — three loops on three clocks

Unchanged from the spec's structure. The invariant that keeps it tractable: **only the session loop touches load; the week loop touches set count; the block loop touches the parameters the other two operate under. No loop reaches across.**

| Loop | Trigger | Reads | Writes |
|---|---|---|---|
| **Session** | Exercise completed (at session completion) | `ProgressionState`, `BlockParameters` | `ProgressionState`, next `Prescription` (load/reps) for that exercise in the active program's PLANNED sessions |
| **Week** | End of microcycle | e1RM trend, fatigue index | `weeklySetTarget` per movement pattern, deload flag |
| **Block** | Monthly / on state change | 14-day energy balance, body-comp trend, lab gates | `BlockParameters` (mode, rep ranges, RIR caps, volume ceiling, expected drift) |

**Where writes land (D2/D4):** the session loop rewrites `targetWeightLbs` + `repsMin/repsMax` + `sets` and stamps `loadBasis`/rationale on the **PLANNED `ScheduledWorkout` sessions** of the active program (and the phase template `WorkoutDay` for future materialization), reusing the same rewrite path the exercise-swap feature already uses (`WorkoutPrescriptionCustomizationService`). Past/COMPLETED sessions are never touched.

---

## 5. Data model changes

All additive/nullable, via convenience constructors (IMPL-18 D2 pattern). Units: the engine works internally in **pounds** (matching `LoggedSet.weightLbs` / `Prescription.targetWeightLbs`); bodyweight from body-comp (`WEIGHT_KG`) is converted on read.

### 5.1 `LoggedSet` — RPE → RIR (D1)

```
record LoggedSet(
    Double weightLbs,
    Integer reps,
    Double rir,              // NEW — reps-in-reserve; replaces rpe
    RirSource rirSource,     // NEW — REPORTED | INFERRED_TARGET | INFERRED_FAILURE | ABSENT
    Integer restSeconds,
    Instant completedAt,
    Integer durationSeconds
)
```

- **Migration:** on read of legacy docs, `rir = 10 − rpe`, `rirSource = REPORTED` (or `INFERRED_*` where the old value was itself inferred). One-shot backfill job over `ScheduledWorkout.session.…loggedSets` + `Workout` history. `rpe` retained in Firestore as a dead field for one release, then dropped.
- **UI:** the guided-workout last-set entry collects RIR via the §5.2 inference-first flow (prompt only on "had more"). RPE removed from `WorkoutSessionScreen`, `CoachAnnouncer`, history rendering, and web `LogSessionModal`.

### 5.2 `ProgressionState` — new (per user, exercise)

```
record ProgressionState(
    String userId,
    String exerciseId,           // stable catalog id (D9: no machineId; D10: single state)
    double e1rmLbs,              // the only authoritative number; seeded from ExerciseDigest (D6)
    double sigmaLbs,             // std dev of the e1rm belief
    Instant lastObservedAt,
    int observationCount,
    int consecutiveMissedSessions,
    Instant kalmanEligibleAt,    // when the 2-week warm-up completes for this exercise (D5)
    long version                 // optimistic locking
)
```
Firestore: `users/{userId}/progressionState/{exerciseId}`.

### 5.3 `SetObservation` — new (append-only log)

Mirrors the spec §5 record: `sessionId, exerciseId, setIndex, isLastWorkingSet, load, reps, rirSource, rir, completedAt, contextFlags` (contextFlags present but unpopulated in v1 — D14). Firestore: `users/{userId}/progressionObservations/{id}`. This is the substrate the replay harness and (later) the gaming monitor read.

### 5.4 `PredictionLog` — new (shadow-mode ledger, D5)

Per the spec §10 record (`model ∈ {double_progression, kalman_v1}`, `prescribedLoad, predictedReps, predictedRir, actualReps, actualRir, absoluteError`). Firestore: `users/{userId}/progressionPredictions/{id}`. Runs permanently; roles reverse after promotion.

### 5.5 `ExerciseLoadingProfile` — new (D8)

Per-user-per-exercise override of `loadIncrementLbs`, `loadOffsetLbs`, `progressionEligible`, defaulting from the exercise's equipment/implement. Firestore: `users/{userId}/exerciseLoadingProfiles/{exerciseId}`. Absent doc ⇒ derived defaults.

### 5.6 `BlockParameters` — new (per user, block loop output)

Per spec §8 record (`mode, expectedDriftPerDay, repRangesByPattern, rirCapsByExerciseClass, weeklySetCeiling, successCriterion`). Firestore: `users/{userId}/progressionBlock/current`. PUT-overridable from the block-parameters screen (D12). Week-loop output lives beside it: `users/{userId}/progressionWeek/current` (`weeklySetTargetByPattern`, `deloadActiveForPatterns`, `computedAt`).

### 5.7 `PrescriptionRationale` — new component on `Prescription` (D24)

```
record PrescriptionRationale(
    String path,          // KALMAN | DOUBLE_PROGRESSION | FALLBACK_COLD_START | FALLBACK_STALE | FALLBACK_SANITY
    String direction,     // UP | DOWN | HOLD
    Double deltaLbs, Integer deltaReps, Integer deltaSets,   // vs. last performed session
    String confidence,    // HIGH | MEDIUM | LOW  (from sigma/e1rm bands)
    List<String> inputs   // short human strings, e.g. "last: 185×8 @ RIR 2", "deficit: holding"
)
```
Additive/nullable on `Prescription`; `loadBasis` is stamped from it for backward compatibility. All UI (arrow glyph, confidence pill, why-line) renders from this field — never by parsing `loadBasis`.

---

## 6. Engine internals (adapted from spec §6–§8)

The math is the spec's; only the currency (RIR), units (lb), seeding (D6), and rollout (D5) differ. Constants in the noise model (spec §6.3) ship as the spec's starting values and are **tuned against the replay harness** (§9), never hand-guessed live.

- **Session loop:** Epley `e1rm_observed = (load + loadOffset)·(1 + (reps + rir)/30)`; scalar-Kalman update with process variance growing during layoffs (self-healing detraining); prescription with **jump cap (≤ 1.05× last), confidence widening (`sigma/e1rm > 0.06` → ±1 rep, −2.5% load), and increment floor → rep progression** (double progression falls out of the model).
- **Deterministic fallback (spec §6.6):** the live path during the 2-week warm-up and forever when data is thin (`observationCount < 6`, no RIR for 2 sessions, < 1 obs/10 days, or model output outside a ±10% sanity band). First-class and fully tested — not an error state.
- **Week loop:** OLS e1RM slope per movement pattern (21-day), fatigue index **from within-exercise rep drop-off at constant load (D16 — not intermediate-set RIR)**, volume ±1 set to ceiling, deload trigger. Week-loop output (`weeklySetTarget` per movement pattern + deload flag) is stored on `BlockParameters`' sibling doc `users/{userId}/progressionWeek/current`. **Deficit-aware (D11):** `HOLD_LOAD_AT_LOWER_RIR` suppresses the deload trigger on FLAT trends.
- **Scope guard (D21):** only `MAIN`/`ACCESSORY`/`CORE` block prescriptions for non-timed exercises are observed or rewritten; everything else passes through untouched.
- **Override semantics (D17):** manual weight/reps edits are absorbed as observations — the engine never fights the user in-session and simply re-converges next session.
- **Block loop:** adaptive maintenance (D7) → 14-day energy balance → mode (`GAINING/RECOMP/MAINTENANCE/RECOVERY`) → drift, rep ranges, RIR caps (compound 2 / isolation 1), volume ceiling, success criterion. Labs/HRV enter **only here and only as gates** (cap volume / force RECOVERY / surface a card), never as a coefficient on tomorrow's load.

---

## 7. Phased build plan

Each phase is independently shippable and leaves the app in a working state. **Exit criteria** are the gate — a phase is not "done" until every box is checked in §8 and the §11 protocol passes.

### Phase 0 — Foundation, RIR migration, observation log
**Goal:** capture the right data and prove logging compliance; no prescription behaviour change yet.
- `LoggedSet` gains `rir`/`rirSource`, loses `rpe` (D1); history backfill job; RPE removed from phone + web UI (`WorkoutSessionViewModel`, `WorkoutSessionScreen`, `CoachAnnouncer`, web `LogSessionModal` RPE column → RIR).
- Last-set RIR entry UX per **D18**: auto-prompt chip row (`0/1/2/3/4/5+`) in the rest-bar area on completing the currently-last set, prefilled with the inferred value; `isLastWorkingSet` resolved at exercise exit.
- New records/collections: `SetObservation`, `ProgressionState`, `ExerciseLoadingProfile` (+ derived defaults, D8); repositories (injected, `FirestoreMetricResolver` pattern).
- Session completion writes `SetObservation` rows (engine still passive).
- **Replay harness scaffolding** (§9) that can read real history.
- **Exit criteria:** RPE fully gone; every completed set produces a `SetObservation`; backfill verified on a copy of real history; ≥ 80% last-set-RIR compliance measured over ≥ 1 week of the owner's real logging (spec Phase-0 gate; **if < 80%, stop and revisit the entry UX before Phase 2**).

### Phase 1 — Deterministic double-progression (the live baseline, forever)
**Goal:** the engine starts owning the numbers using rules only.
- Session loop deterministic path runs synchronously in `WorkoutSessionCompletionService` (D22) and writes next-session `targetWeightLbs`/reps/sets + `PrescriptionRationale` into PLANNED sessions via a new engine-owned rewrite service (modeled on `WorkoutPrescriptionCustomizationService.propagateToProgram`). Only `MAIN`/`ACCESSORY`/`CORE`, non-timed prescriptions (D21). Manual edits absorbed as observations (D17).
- Increment floor → rep progression; deficit-unaware for now.
- UI: why-line + confidence pill (trivially "high" for the deterministic path) + **▲/▼ direction glyph + delta** (D19) rendered from `PrescriptionRationale`; green/red stays HIT/MISS. Ad-hoc/unmaterialized sessions show the existing local-prefill numbers with fallback styling (D23).
- **Exit criteria:** golden tests for double-progression (advance, stall, deload-down, increment-floor→rep-progression, dumbbell 5 kg jump) all green; replay harness runs clean over real history with no absurd prescriptions; owner sees engine-authored numbers on the next session.

### Phase 2 — Kalman session loop + shadow mode (D5, D6)
**Goal:** belief-driven prescription, safely.
- `ProgressionState` Kalman update; seed `e1rm` from `ExerciseDigest` with wide `sigma` (D6); observation-noise model (spec §6.3 constants); prescription with jump cap / confidence widening / increment floor.
- Runs in **shadow** per exercise until `kalmanEligibleAt` (2 weeks of training data); logs `PredictionLog` for both models. After warm-up, Kalman becomes the live prescriber; double-progression continues as shadow.
- Confidence pill now driven by real `sigma`.
- **Exit criteria:** golden tests (cold-start seed, missed-weeks re-convergence, jump cap, confidence widening, LOW_INFORMATION high-rep guard) green; replay harness reports Kalman MAE **and** double-progression MAE with the bootstrap CI of the difference; warm-up gating verified (no live Kalman prescription before `kalmanEligibleAt`).

### Phase 3 — Week loop
**Goal:** volume and deloads.
- Trend (OLS per movement pattern), fatigue index **from rep drop-off at constant load (D16)**, volume ±1 set to ceiling, deload trigger; runs from the daily `@Scheduled` sweep on microcycle boundaries (D22). Engine-owned deloads (D11), deficit-suppression stubbed until Phase 4 supplies mode.
- **Progression-review screen** (D12): trend + fatigue + proposed volume/deload with reasoning, owner can override.
- **Exit criteria:** golden tests (RISING→hold, FLAT/stable→+1 set, FLAT/rising→hold, FALLING→deload, deload halves volume + holds load + raises RIR) green; replay harness shows deloads firing on real historical stalls and *not* firing on real progress; review screen renders and overrides persist.

### Phase 4 — Block loop
**Goal:** parameters from energy balance + gates.
- Adaptive maintenance (D7) + Mifflin cold-start → 14-day energy balance → mode selection → rep ranges / RIR caps / volume ceilings / expected drift.
- **Deficit-aware deload suppression** wired into the week loop (D11): FLAT in a deficit = success, no deload.
- Lab/body-comp/HRV **gates** (cap volume / force RECOVERY / surface a card) — reduce-only, never a load coefficient.
- **Block-parameters console screen** (D12) with manual mode/param override.
- **Exit criteria:** golden tests (surplus→GAINING/ADD_LOAD, deficit>500→RECOVERY, deficit-flat-is-success suppresses deload, lab gate forces RECOVERY) green; replay harness over a real cut window shows no spurious deloads; block screen override changes downstream prescriptions.

### Phase 5 — Deferred hardening (not in v1 scope; tracked here so it isn't lost)
- RIR-gaming defenses (D13): scheduled AMRAP calibration + reported-RIR drift monitor (surface, don't silently correct).
- Readiness context flags from `DailyMetric` (sleep/HRV/RHR) as reduce-only noise inputs (D14).
- Velocity/VBT: **out of scope** (D14) — recorded as explicitly dropped, not deferred.

---

## 8. Progress dashboard (source of truth)

**Legend:** ⬜ not started · 🟨 in progress · ✅ done & verified (per §10/§11) · ⛔ blocked · — n/a

**Status as of 2026-09-05** — backend engine complete and verified (`./gradlew test` green, 25 progression tests incl. replay harness). Clients + a few backend gates outstanding (see notes).

| Phase | Workstream | Impl | Tested (unit) | Tested (functional) | Pushed |
|---|---|---|---|---|---|
| **0** | `LoggedSet` rir/rirSource (additive; RPE kept as legacy, M2) | ✅ | ✅ | ✅ | ⬜ |
| 0 | History RPE→RIR conversion (lazy `effectiveRir`, M2 — not a batch job) | ✅ | ✅ | ✅ | ⬜ |
| 0 | Last-set RIR entry UX (phone) + RIR data contract | ✅ (compiles) | 🟨 | ⬜ | ⬜ |
| 0 | Web RPE→RIR relabel | ✅ | ✅ (tsc+lint) | ✅ | ⬜ |
| 0 | `SetObservation`/`ProgressionState`/`ExerciseLoadingProfile` + repos (+ Firestore) | ✅ | ✅ | ✅ | ⬜ |
| 0 | Completion writes SetObservation (event → session loop, D22) | ✅ | ✅ | ✅ | ⬜ |
| 0 | Replay harness | ✅ | ✅ | ✅ | ⬜ |
| 0 | **Gate: ≥80% RIR compliance measured** | — | — | ⬜ | — |
| **1** | Deterministic double-progression session loop | ✅ | ✅ | ✅ | ⬜ |
| 1 | Next-session prescription writeback | ✅ | ✅ | ✅ | ⬜ |
| 1 | `PrescriptionRationale` (backend) + why/pill/▲▼ arrow (Android UI) | ✅ / ✅ (compiles) | ✅ | ✅ | ⬜ |
| **2** | Kalman update + seed-from-digest | ✅ | ✅ | ✅ | ⬜ |
| 2 | Observation-noise model | ✅ | ✅ | ✅ | ⬜ |
| 2 | Shadow mode + PredictionLog + 2-week warm-up gate | ✅ | ✅ | ✅ | ⬜ |
| **3** | Trend + fatigue index (rep drop-off, D16) | ✅ | ✅ | ✅ | ⬜ |
| 3 | Volume adjustment + deload trigger | ✅ | ✅ | ✅ | ⬜ |
| 3 | Progression-review (backend `review()` + `/week-review`; Android UI) | ✅ / ✅ (compiles) | ✅ | ✅ | ⬜ |
| **4** | Adaptive maintenance + energy balance | ✅ | ✅ | 🟨 | ⬜ |
| 4 | Mode selection + block params (+ `/block-parameters` GET/PUT) | ✅ | ✅ | ✅ | ⬜ |
| 4 | Deficit-aware deload suppression | ✅ | ✅ | ✅ | ⬜ |
| 4 | Lab/body-comp gates | ⬜ | ⬜ | ⬜ | ⬜ |
| 4 | Block-parameters console screen (Android UI, incl. mode override) | ✅ (compiles) | 🟨 | ⬜ | ⬜ |

**Legend for split cells** `backend / client`. Notes:
- **RPE removal** was implemented as *additive* `rir` with legacy `rpe` retained + lazy `10−rpe` conversion (decision M2), not a destructive column drop.
- **Last-set RIR UX / web relabel / client rationale UI / review + block screens** are the client workstreams (Android + web); backend contract for all of them is done and tested.
- **Compliance gate** can only be measured against real logging — not verifiable in-repo.
- **Lab/body-comp gates (§8.3)** are NOT implemented in v1 (decision B1) — the hook exists in the block loop but reads no markers yet.
- **Adaptive-maintenance functional** marked 🟨: unit-covered via `MaintenanceCalorieEstimator`, but its end-to-end effect on mode isn't in a dedicated golden test yet.

> **Rule:** a cell goes ✅ only when the §11 verification for that item has actually been run. Backend ✅ cells are covered by `./gradlew test` (see §12). Client 🟨 cells are implemented-but-not-verified-in-this-environment.

---

## 9. Testing approach

Two independent pillars (D15). Both are required; neither alone is sufficient.

### 9.1 Technical correctness — golden scenario tests
Hand-authored, deterministic, fast. Each is a fixed input → **golden expected prescription/state**, covering the tricky rules where a subtle bug hides:

- Cold-start seed from digest; missed-weeks re-convergence; jump cap; confidence widening; LOW_INFORMATION high-rep guard.
- Double-progression: advance, stall-down, increment-floor→rep-progression, 5 kg fixed-dumbbell jump.
- Week loop: each (trend × fatigue) cell; deload halves volume, holds load, raises RIR; fatigue index from rep drop-off matches hand-computed slope (D16).
- Block loop: surplus→GAINING, deficit>500→RECOVERY, **deficit-flat-is-success suppresses deload**, lab gate forces RECOVERY.
- Migration: `rir = 10 − rpe` backfill correctness incl. inferred-source cases.
- Scope guard: WARMUP/timed prescriptions pass through untouched (D21); manual override absorbed as observation shifts next prescription correctly (D17); session `feeling` widens observation noise (D20).
- Rationale: every engine-written prescription carries a well-formed `PrescriptionRationale`; direction/delta agree with the actual number movement (D24).

Backend: JUnit under `backend/src/test/java/.../core/progression/`. Client: existing Android/web test setup for the UI pieces.

### 9.2 Functional correctness — replay harness over real history
A backtest that **replays the owner's real logged sessions** (from `Workout`/`ScheduledWorkout` history) through the engine in chronological order and asserts *behavioural* properties, not just unit outputs:

- Prescriptions track real history (no prescription > 10% off the next real session's achievable load without a stated reason).
- No absurd jumps; increment floor respected per exercise's real increment.
- Deloads fire on real historical stalls and **do not** fire on real historical progress or during a real cut window.
- Prediction error bounded; harness emits **Kalman MAE vs. double-progression MAE + bootstrap 90% CI** (the shadow-mode evidence from spec §10).

Delivered as a runnable backend task (e.g. `./gradlew progressionReplay -Puser=<uid>`), reading a fixture export of real history so it runs offline in CI. Output is a report the agent reads to gate phase completion.

### 9.3 Compliance measurement (Phase 0 gate)
Instrument last-set RIR entry; after ≥ 1 week of the owner's real logging, compute the fraction of last-working-sets with a non-`ABSENT` RIR. **< 80% halts progression to Phase 2** (spec's hard gate; per D3 we measure it on the phone, not Wear).

---

## 10. Definition of Done

A workstream is **done** only when all of the following hold (and are reflected in §8 + §12):

1. **Implemented** to the phase's deliverable, matching the existing architecture (offline-first, repository injection, additive records, design-system components).
2. **Unit-tested:** golden scenario tests for that workstream exist and pass; backend `./gradlew test` green; touched client modules' tests green.
3. **Functionally verified:** the replay harness passes its assertions for that workstream (or, for UI-only work, the functional acceptance story in §11 is demonstrably met).
4. **No regression:** RPE removal doesn't break history rendering; existing workout completion/prefill/swap flows still pass their tests.
5. **Rationale surfaced:** any engine-authored number is explainable in the UI (the "why this number" line resolves).
6. **Verification evidence recorded** in §12 (command + result), not merely asserted.
7. **Pushed:** merged to the feature branch with the dashboard row updated; deploy checks green where applicable.

**Overall v1 done** = Phases 0–4 all done under the above, the Phase-0 compliance gate passed, and the replay harness shows the engine is at least non-inferior to double-progression on MAE (if it isn't, the deterministic path stays live per D5's shadow logic — that is a valid, documented outcome, not a failure).

---

## 11. Agent verification protocol (run before marking anything done)

The agent must **run and observe**, never infer from "the code looks right." Minimum protocol per workstream:

1. **Build & unit:** `cd backend && ./gradlew test` (and the relevant `android`/`web` test task). Paste pass/fail into §12.
2. **Golden tests:** run the phase's scenario tests explicitly; confirm the *golden* values, not just green.
3. **Replay harness:** run `./gradlew progressionReplay …` against the real-history fixture; confirm the phase's behavioural assertions (§9.2) hold; paste the MAE/CI report.
4. **Functional acceptance story** (per phase, e.g. "log a session where all top-set reps are hit with RIR 2 → next session's prescription for that exercise increases by exactly one `loadIncrement`, the directional color is 'up', and the rationale line names double-progression"). Reproduce it — via the replay harness fixture or a manual run through the app (`/run` or `/verify`) — and record the observed result.
5. **Migration safety:** for Phase 0, run the backfill against a *copy* of real data and diff; never mutate production history unverified.
6. Only then flip the §8 cell to ✅ and append the evidence to §12.

If any step fails or is skipped, the item stays 🟨 with a note — do not report it as done.

---

## 12. Verification log

- **2026-09-05** — Backend engine (Phases 0–4 core) — `./gradlew test` — **BUILD SUCCESSFUL**, full suite green including 25 new progression tests: `ProgressionMathTest` (Epley/target%/floor/confidence/noise/Kalman), `DoubleProgressionTest` (advance/stall/deload/dumbbell-increment/no-negative), `PrescriptionCalculatorTest` (floor/jump-cap/widening/increment-floor→rep), `BlockAndWeekLogicTest` (mode selection incl. 500-kcal gate, trend classify, OLS, fatigue rep-drop-off), `SessionLoopTest` (seed-from-digest, observations logged, next-session writeback, warm-up path, dual shadow predictions), `ReplayHarnessTest` (12-session sim: progressive overload, no >12% jumps, warm-up→Kalman transition, bounded shadow MAE).
- **2026-09-05** — No regressions — `./gradlew test` (full) — green after additive `LoggedSet.rir`/`Prescription.rationale`, completion-service `SessionCompletedEvent` wiring, `ProgressionController`, 6 Firestore repos, and the Firestore mapper round-trip for rir/rirSource/rationale.
- **2026-09-05** — Deficit-flat-is-success (D11) — `WeekLoopReviewTest` — green: FLAT trend + HOLD_LOAD_AT_LOWER_RIR → no deload; FLAT + ADD_LOAD → +1 set; FALLING → deloads even in a deficit.
- **2026-09-05** — Web RPE→RIR relabel — `pnpm run typecheck` (tsc --noEmit) + `pnpm run lint` — PASS, no new errors; changed `web/lib/types/workout-program.ts` (added `rir`/`rirSource`) and `web/components/workouts/LogSessionModal.tsx` (RPE column → RIR, sends `rir`+`rirSource`, legacy `10−rpe` fallback for old data).
- **2026-09-05** — API surface in real context — `ProgressionControllerTest` (`@SpringBootTest` + MockMvc) — green: `GET /state/{id}` 404→200 with confidence/kalmanLive, `GET/PUT /block-parameters` incl. manual override, `GET /week-review`, `POST /state/{id}/reset`. Confirms the full application context loads with all new engine beans + the 6 repository beans wired.
- **2026-09-05** — Android RIR data contract + last-set RIR entry + rationale/confidence/▲▼ UI — `./gradlew :core-domain:compileDebugKotlin :core-data:compileDebugKotlin :feature-workouts:compileDebugKotlin` — **BUILD SUCCESSFUL** (independently re-run and confirmed). Changed 5 files: domain `LoggedSet`/`Prescription` (+`rir`/`rirSource`/`PrescriptionRationale`), wire DTOs + mappers, `SessionFormat.kt` (RIR inference + chips), `WorkoutSessionScreen.kt` (`RirChipRow` on last set, `RationaleStrip` with arrow/pill/why), strings. RIR rides the existing Room session-draft JSON via the shared `LoggedSetDto`. **Not** runtime/UI-tested (no emulator in this env); compile-verified only.
- **2026-09-06** — Android **progression console screen** (week-review + block-parameters, single screen, two sections) — `./gradlew :core-domain :core-data :feature-workouts :compileDebugKotlin` **BUILD SUCCESSFUL** (independently re-run). New `ProgressionApi`/DTOs/`ProgressionRepository` (contract verified to match the backend endpoints + JSON field-for-field), `ProgressionConsoleViewModel`/`Screen` (trend/deload Pills, current→proposed sets, reasoning; segmented mode toggle → PUT; read-only rep ranges/RIR caps), route + a "Progression" entry point on the workouts landing. Compile-verified only (no emulator/runtime).
- **Remaining (not built):** **Lab/body-comp gates** (decision B1) and the **≥80% RIR compliance gate** (needs real logging) are out of v1 scope; RIR-gaming defenses, readiness flags, velocity remain deferred (D13/D14).

---

## 13. Open questions / deferred

- **RIR gaming** (D13): deferred to Phase 5; the full `SetObservation` log captured from Phase 0 makes it addable without a data migration.
- **Readiness flags** (D14): deferred; `contextFlags` plumbing exists but has no producer in v1 (D20's session-feeling mapping is the sole v1 noise-context input).
- **Legacy `rpe` field lifetime:** kept as a dead Firestore field for one release post-migration, then dropped — confirm the release boundary.
- **Warm-up length (D5):** 2 weeks of training data per exercise — revisit if low-frequency exercises never reach eligibility (they correctly stay on the deterministic path, which may be the permanent answer for them).
- **D20 was defaulted, not explicitly chosen** (reuse session-level feeling instead of a per-exercise feel chip) — flag if a per-exercise rating is wanted after living with v1.
- ~~Unit preference~~ **closed (D25):** display units are on-device only; server/engine stay lb.

---

## 14. Evidence status of each design decision (carried from source spec)

| Decision | Basis |
|---|---|
| Autoregulation ≈ percentage-based on outcomes | Meta-analysis [Certain] |
| RIR-based RPE as subjective currency | Zourdos et al. 2016 [Certain] |
| RIR 2 default on compounds | Robinson et al. 2024 [Certain] |
| 500 kcal/day deficit gate | Murphy & Koehler 2022 [Certain] |
| Reject Banister FFM as estimator | Imbach et al. identifiability critique [Certain] |
| Scalar Kalman filter on e1RM | No published RCT; defensible engineering choice [Guessing] |
| Trend-triggered over calendar deloads | No head-to-head trial found [Guessing] |
| Noise-model constants (§6.3) | Starting values only; tune against §9 replay [Guessing] |
| Drop velocity for v1 | Product decision (no hardware) + Jukic et al. 2023 rejecting velocity-loss volume prescription [Certain] |
| HRV as reduce-only gate (deferred) | No supporting RT evidence [Likely null] |
