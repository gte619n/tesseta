# IMPL-PROG-02 — Prediction Display, RIR Capture & Bodyweight Correctness

**Status:** 🟡 Draft v1 — plan ratified via interview (2026-09-07); no code yet.
**Branch:** `workout-prediction-improvements`
**Author / PM:** Evan Ruff (interview) + engineering
**Created:** 2026-09-07
**Predecessor:** [`IMPL-PROG-01-autoregulated-progression-engine.md`](./IMPL-PROG-01-autoregulated-progression-engine.md) — the engine that produces the predictions this plan *displays, explains, and corrects*.
**Scope:** Close the loop between the shipped Progression Engine and the athlete during a **live guided workout**. This is a UX-correctness + capture-quality effort, not an algorithm rewrite — with two targeted engine-behavior changes (rep-band reset on load jump; first-time seed weight).

---

## 0. How to read this document

This is both the **implementation plan** and the **living progress ledger**. Two sections are the control surface:

- **§6 Phased build plan** — the ordered phases, each with deliverables and *exit criteria*.
- **§7 Progress dashboard** — the single source of truth for what is Implemented / Tested / Pushed. Update it as work lands. Nothing is "done" until it clears the **§9 Definition of Done** and the **§10 agent verification protocol** — code compiling is not "done."

Everything above §6 is the contract (problem, decisions, data model, designs). Read it once; it changes only if a decision is revisited (record that in §3 with a new decision row).

---

## 1. Problem statement and framing

The Progression Engine (IMPL-PROG-01) now produces per-exercise predictions — a `targetWeightLbs`, a rep band (`repsMin..repsMax`), and a rich `rationale` (path, direction, confidence, `deltaLbs`, `deltaReps`). The **first real workout** driven by it surfaced six defects, all at the *seam between the prediction and the athlete*: what they see, what they hear, when they're asked for signal, and how the app behaves on an exercise the engine has never seen.

### 1.1 The six defects (source: owner feedback, 2026-09-07)

| # | Defect | Root cause (from code audit) |
|---|---|---|
| F1 | Weight/rep adjustments aren't visibly called out; no way to see *why* | No "adjusted vs last time" indicator anywhere in the set UI; `rationale`/`loadBasis` reach the client but are never rendered. |
| F2 | Announcement may have spoken a **stale** weight | `coachAnnouncement()` uses the prefill precedence (below), which is overridden by `lastSets` loaded *async after* the screen renders. |
| F3 | Start-of-workout notification lacked the newest weights; "switched a second later" | `WorkoutSessionNotificationContent.loadLabel()` reads `previous?.weightLbs ?: prescription.targetWeightLbs` off an immutable session snapshot, and the async `lastSets` update re-posts a different number a beat later. |
| F4 | RIR is asked **after** the last set is checked complete, forcing the user to go back | `RirChipRow` renders only when `allSetsDone == true` — a per-exercise afterthought row, not part of the final set's input. |
| F5 | Unclear whether increasing weight lowers reps to stay achievable | Kalman path aims for band **mid** and only adds a rep when it can't add load; double-progression resets toward the band bottom. Behavior is inconsistent and the *displayed* target reps don't reliably drop on a load jump. |
| F6 | First-time exercise (cable pushdown) announced "body weight" | Client infers bodyweight purely from `weightLbs == 0.0`. A weighted lift with no prediction yet resolves to 0 → the app calls it bodyweight, which is impossible. |

### 1.2 What this is / is NOT

- **Is:** display of adjustments + on-demand rationale; correct, race-free weight sourcing in voice + notification; RIR capture moved into the final set as a hard gate; a true-bodyweight signal; a seeded first-time default weight; a small rep-band-reset engine rule.
- **Is NOT:** changes to the Kalman/e1RM belief model, block/week loops, program design, or the RIR→e1RM math. We change *when/what* RIR is captured and *what number* is shown — not how the engine reasons about it.

### 1.3 Prefill precedence (the shared root of F2/F3/F6)

`prefillFor()` and the notification/announcement builders share this order:

1. previous set logged **in this session**
2. **last-session actual** (`/last-sets`, loaded async)
3. designed/predicted `targetWeightLbs`

The prediction sits *below* last-session actual, and step 2 arrives late → the "switched a second later" race and the stale voice. **D1 reorders this.**

---

## 2. Where this fits the existing codebase

All integration points below were confirmed by direct code audit on 2026-09-07.

### 2.1 Backend (Kotlin/Java)

| Concern | Existing code | Path |
|---|---|---|
| Prediction transport (adds fields) | `Prescription` record (`targetWeightLbs`, `loadBasis`, `rationale`) | `backend/.../core/workoutprogram/Prescription.java` |
| Bodyweight determination (surface it) | `LoadingProfileResolver.isBodyweightLoaded()` | `backend/.../core/progression/LoadingProfileResolver.java` |
| Rep-band + load derivation (F5) | `PrescriptionCalculator`, `DoubleProgression`, `RepBand` | `backend/.../core/progression/` |
| First-time seed (F6) | `SessionLoop.seed()`, `ExerciseDigest` | `backend/.../core/progression/SessionLoop.java` |
| Exercise catalog (adds seed + loadMode) | `Exercise` record | `backend/.../core/exercise/Exercise.java` |
| API DTO out | `WorkoutProgramDeepResponse.PrescriptionResponse` | `backend/.../api/workoutprogram/WorkoutProgramDeepResponse.java` |

### 2.2 Android (Kotlin/Compose)

| Concern | Existing code | Path |
|---|---|---|
| Domain model mirror (adds fields) | `Prescription`, `PrescriptionRationale` | `android/core-domain/.../workouts/program/WorkoutProgram.kt` |
| Prefill + outcome + announcement text (F1/F2/F5/F6) | `prefillFor()`, `coachAnnouncement()`, `outcomeColor()`, `RIR_CHOICES`, `inferredRir()` | `android/feature-workouts/.../session/SessionFormat.kt` |
| Set cards, RIR row, page (F1/F4) | `ActiveRepCard`, `CompletedRepRow`, `RirChipRow`, `ExercisePage` | `android/feature-workouts/.../session/WorkoutSessionScreen.kt` |
| Session VM + async lastSets race (F2/F3) | `WorkoutSessionViewModel` init | `android/feature-workouts/.../session/WorkoutSessionViewModel.kt` |
| TTS engine (F2) | `CoachAnnouncer.speak()` | `android/feature-workouts/.../session/CoachAnnouncer.kt` |
| Foreground notification (F3) | `WorkoutSessionService.buildNotification()`, `WorkoutSessionNotificationContent.loadLabel()` | `android/app/.../mobile/workouts/` |
| Weight/reps pickers (F6) | `WeightPickerDialog`, `RepsPickerDialog` | `android/feature-workouts/.../session/WorkoutSetPickers.kt` |

---

## 3. Decision log (ratified in interview 2026-09-07)

Each row is binding. Revisit only by adding a superseding row.

| ID | Decision | Rationale |
|---|---|---|
| **D1** | **Prediction is authoritative; last-session actual is context.** Show the engine's `targetWeightLbs` as THE target for the upcoming set; render last-session actual only as a small "last time: X" subtitle. Reorder prefill so prediction wins over `/last-sets` for prediction-enabled lifts. | Fixes F2/F3 at the root — a predicted workout should never be silently overwritten by a late async fetch of what you happened to lift last time. |
| **D2** | **"Changed" is measured vs *what you actually lifted last time*.** The highlight and its explanation compare the shown target to last-session actual load/reps. | Most intuitive frame for a lifter mid-set; matches how the engine already narrates `deltaLbs`/`deltaReps` when last-performed data exists. |
| **D3** | **Rationale is compact-by-default, expandable via an info affordance** to the full detail (confidence, path, e1RM estimate, load basis). | Keeps the live screen clean; gives power users (and debugging) the full "why" on demand. |
| **D4** | **RIR is a hard gate on the final working set.** The last working set cannot be marked done until an explicit RIR value is chosen — captured *in* that set's input, not after. Mandatory explicit pick (no silent default accepted). | F4. RIR from the last set is the single most load-bearing signal for the engine; capturing it inline (not after the set closes) removes the "go back" friction and raises data quality by forcing an intentional answer. |
| **D5** | **On a weight increase, target reps reset to the band bottom** (`repsMin`). Applies across both Kalman and double-progression paths so the *displayed* target drops in lock-step with a load jump; the athlete works reps back up before the next jump. | F5. Makes an increased load immediately achievable and consistent regardless of engine path. |
| **D6** | **Bodyweight is an explicit flag on the prescription**, sourced from `LoadingProfileResolver`. `weightLbs == 0` no longer implies bodyweight; a missing weight on a non-bodyweight lift is treated as *unknown*, not bodyweight. | F6. Removes the impossible "body weight" call on cable pushdowns and any other loaded lift lacking a prediction. |
| **D7** | **First-time non-bodyweight lift announces a conservative default weight** sourced from a per-exercise **catalog seed**. | F6. Gives a real, tailored starting number instead of a fake "body weight" or a blank; the engine then seeds its belief from the athlete's first logged set. |
| **D8** | **Voice states the correct target numbers only** — no delta narration. Correctness (the number is the prediction) is the fix, not verbosity. | Directly answers F2: the complaint was a *stale* number, not a missing delta. Delta lives in the visual highlight (D9). |
| **D9** | **Adjusted numbers use a neutral accent + ▲/▼ delta indicator**, visually distinct from the existing green/red HIT/MISS outcome coloring. | F1. Prevents confusing "the engine changed this" with "you hit/missed this." |
| **D10** | **Re-announce on any change** to the upcoming set's numbers (after the initial settled announcement). | With D1 removing the async override, the value is stable at first announce; re-announce then only fires on genuine user edits — which the athlete *wants* to hear confirmed. |
| **D11** | **Ship to everyone** (no cohort/owner gate). | The engine is already the source of these numbers for enabled users; the display/capture fixes are strictly better behavior with no risky new surface. |
| **D12** | **Adjustment highlight + rationale persist into session history**, not just live. | Lets the athlete review "why did it push me here" after the fact; reuses the same rendering. |
| **D13** | **Functional verification via automated UI tests where feasible** (Compose UI + instrumentation), with a documented manual walkthrough reserved only for the inherently un-automatable (TTS audio, system notification rendering). | Raises the floor on "done" beyond unit tests; see §8/§10. |

---

## 4. Data model changes

Additive only; legacy documents/JSON default safely.

### 4.1 Backend

**`Exercise` (catalog)** — add:
- `LoadMode loadMode` — new enum `{ WEIGHTED, BODYWEIGHT, BODYWEIGHT_LOADED, TIMED }`. Authoritative source for D6. Backfilled from `LoadingProfileResolver.isBodyweightLoaded()` + `isTimed`. Legacy docs → derive on read until backfilled.
- `Double seedWeightLbs` (nullable) — per-exercise conservative starting load for D7. Null → fall back to movement-pattern default, then a low fixed floor.

**`Prescription`** — add:
- `boolean isBodyweight` — resolved from `Exercise.loadMode`, stamped at writeback. Client trusts this over `weightLbs == 0`.
- `Double lastActualWeightLbs` / `Integer lastActualReps` (nullable) — the "last time" context number (D1/D2), computed server-side so the client no longer needs the racy `/last-sets` merge for display. (Client `/last-sets` may remain for other uses; it is no longer the display source of truth.)
- (already present) `targetWeightLbs`, `loadBasis`, `rationale` — now actually rendered.

**`WorkoutProgramDeepResponse.PrescriptionResponse`** — mirror the three additive fields above.

### 4.2 Android domain

**`Prescription` (WorkoutProgram.kt)** — mirror `isBodyweight`, `lastActualWeightLbs`, `lastActualReps`. Ensure `rationale` (already mapped) carries `path`, `direction`, `confidence`, `deltaLbs`, `deltaReps`, `loadBasis`, and an e1RM estimate for the D3 detail view.

---

## 5. Feature designs (per defect)

### 5.1 F1 + F2 + F3 — Correct, authoritative weight everywhere (D1/D8/D9/D10)

- **Source of truth:** the upcoming set's displayed weight/reps = `prescription.targetWeightLbs` / rep band. `lastActualWeightLbs/Reps` render as a muted "last time: 45 lb × 12" subtitle.
- **Prefill reorder** (`prefillFor`, `SessionFormat.kt`): for prediction-enabled prescriptions, precedence becomes *previous set in session → prediction → (legacy last-actual only if no prediction)*. This single change removes the F2/F3 race because the display no longer depends on the async `lastSets` arrival.
- **Highlight (D9):** when `target != lastActual`, render the target number with the neutral accent + ▲/▼ + delta chip (e.g. `+5 lb`). Distinct token from `Hf.colors.good/alert`. Applies to both weight and reps independently.
- **Announcement (D8/D10):** `coachAnnouncement()` speaks the target numbers. Initial announce fires only once data is settled (with D1 it is settled at render). Any subsequent change to the upcoming set's numbers re-announces (`QUEUE_FLUSH` already in `CoachAnnouncer`).
- **Notification (D1):** `loadLabel()` reads the same authoritative target; remove dependence on the mutable snapshot re-post that caused the "switched a second later." The notification content is derived from the same resolved target the screen shows.

### 5.2 F4 — RIR gate on the final working set (D4)

- Move RIR out of the post-completion `RirChipRow`. On the **last working set's** `ActiveRepCard`, add a required RIR selector alongside weight/reps.
- The set's "done"/check action is **disabled** until an explicit RIR is chosen (mandatory pick — no auto-accepted `inferredRir()` default; the inferred value may pre-*highlight* a suggestion but the user must confirm a choice). Persist as `RIR_SOURCE_REPORTED`.
- Non-final sets: unchanged (no RIR prompt).
- Edge cases: added/removed sets change which set is "last" → the gate must track the current final working set reactively; an AMRAP/failure last set still requires RIR (0 is a valid explicit pick).

### 5.3 F5 — Rep-band reset on load jump (D5)

- Engine: when the derived direction is a **weight increase**, the emitted prescription's target reps present the **band bottom** (`repsMin`). Unify Kalman (`PrescriptionCalculator`) and `DoubleProgression` so both express "load up ⇒ reps reset to bottom." Rep *band* (min..max) still travels for logging latitude; the *shown target* is the bottom on a jump.
- Client simply renders `repsMin` as the target when `rationale.direction == UP` (belt-and-suspenders with the engine value).

### 5.4 F6 — True bodyweight vs unknown weight (D6/D7)

- **Bodyweight decision:** client reads `prescription.isBodyweight`. Only `true` → announce/label "body weight." Backfill `Exercise.loadMode` from `LoadingProfileResolver`; stamp `isBodyweight` at writeback.
- **First-time weighted, no prediction:** engine seeds `targetWeightLbs` from `Exercise.seedWeightLbs` (→ movement-pattern default → low fixed floor), with `loadBasis = "catalog seed"`. Announced as a normal conservative number; user adjusts via the picker and the first logged set seeds the belief (`SessionLoop.seed()`).
- **Result on cable pushdown:** `isBodyweight=false`, `targetWeightLbs≈40–50` from seed → announces "Cable pushdown, 45 pounds, 12 reps." Never "body weight."

### 5.5 F1 + F12 — Rationale surface (D3/D12)

- Info affordance on the set/exercise header opens a compact rationale sheet: direction + delta ("+5 lb, +0 reps vs last time"), `loadBasis` in plain language, confidence, engine path, e1RM estimate.
- The same sheet + delta highlight are reachable from the **post-workout session history** view (D12), reusing the composables.

---

## 6. Phased build plan

Each phase has explicit **exit criteria**. A phase is not "done" until §9 DoD + §10 verification pass for its items.

### Phase 0 — Data plumbing (backend + domain)
- Add `LoadMode` + `seedWeightLbs` to `Exercise`; `isBodyweight`, `lastActualWeightLbs/Reps` to `Prescription` + DTO; mirror on Android domain. Backfill `loadMode` from resolver.
- **Exit:** new fields serialize/deserialize round-trip; legacy docs default safely; existing progression + deep-response tests green; Android model maps all fields.

### Phase 1 — Authoritative weight sourcing (F2/F3, D1/D8)
- Reorder `prefillFor`; repoint `coachAnnouncement` and `WorkoutSessionNotificationContent.loadLabel` to the prediction; remove the async-`lastSets` display dependency.
- **Exit:** at session start, screen + notification + first announcement all show the predicted number with zero post-render "switch"; unit tests assert precedence; instrumentation test asserts no notification content change within 2s of start when only `lastSets` resolves late.

### Phase 2 — Adjustment highlight + rationale (F1, D2/D3/D9/D12)
- Delta highlight (accent + ▲/▼ + chip) on target weight/reps; info-affordance rationale sheet; wire into live screen and history.
- **Exit:** UI test shows highlight iff `target != lastActual`, and never collides with HIT/MISS coloring; rationale sheet renders all fields; history shows the same.

### Phase 3 — RIR gate on final set (F4, D4)
- Inline RIR selector on the final working set's card; disable completion until explicit pick; remove post-completion `RirChipRow`.
- **Exit:** UI test proves the last set cannot be completed without a RIR pick; value persists as `REPORTED`; adding/removing a set re-targets the gate; non-final sets never prompt.

### Phase 4 — Engine rep-band reset (F5, D5)
- Unify load-up ⇒ reps-to-`repsMin` across `PrescriptionCalculator` + `DoubleProgression`; client renders `repsMin` on `direction == UP`.
- **Exit:** engine unit tests: a weight increase yields displayed target reps == band bottom on both paths; no regression to existing progression tests.

### Phase 5 — Bodyweight vs unknown + seed weight (F6, D6/D7)
- Client trusts `isBodyweight`; engine seeds first-time `targetWeightLbs` from catalog seed → pattern default → floor; backfill seed weights for the common weighted machines (incl. cable pushdown).
- **Exit:** cable-pushdown first-time announces a numeric seed, never "body weight"; a true bodyweight lift (dip/pull-up) still announces "body weight"; unit tests cover both branches.

### Phase 6 — Full regression + functional walkthrough
- End-to-end guided-workout pass; §10 protocol; update §7 ledger; open PR.
- **Exit:** all DoD boxes checked; walkthrough recorded; CI green.

---

## 7. Progress dashboard  *(single source of truth — update as work lands)*

Legend: ⬜ not started · 🟨 in progress / needs on-device sign-off · ✅ done & verified

| Phase / item | Impl | Unit test | UI/instr test | Functional walkthrough | Pushed |
|---|---|---|---|---|---|
| P0 — data plumbing (backend/domain) | ✅ | ✅ | n/a | n/a | ⬜ |
| P1 — authoritative weight sourcing (F2/F3) | ✅ | ✅ | 🟨 | 🟨 (start-of-workout voice + notif) | ⬜ |
| P2 — adjustment highlight + rationale (F1) | ✅ | ✅ | 🟨 | 🟨 (highlight not confused w/ HIT/MISS) | ⬜ |
| P3 — RIR gate on final set (F4) | ✅ | ✅ | ✅¹ | 🟨 (can't finish set w/o RIR) | ⬜ |
| P4 — rep-band reset (F5): client display + engine (IMPL-D11) | ✅ | ✅ | n/a | 🟨 (target reps drop on load jump) | ⬜ |
| P5 — bodyweight vs unknown + seed (F6) | ✅ | ✅ | 🟨 | 🟨 (cable pushdown ≠ "body weight") | ⬜ |
| P6 — regression (touched modules green) | ✅ | ✅ | ✅ | 🟨 | ⬜ |

¹ P3's gate is covered by a **Robolectric Compose UI test** (`ActiveRepCardRirGateTest`) that runs on the JVM in `testDebugUnitTest` — asserts the final set can't be logged until a RIR chip is tapped, and that a non-final set logs with no gate. 2 tests, 0 skipped, 0 failures.

**Verification run (2026-09-07, headless JVM):**
- Backend: `BodyweightClassifierTest`, `SeedWeightResolverTest`, `BandTightenOnIncreaseTest`, `WorkoutSessionCoachTest`, plus full `core.progression.*` and `api.workoutprogram.*` packages → **BUILD SUCCESSFUL** (no regressions from the `LoadingProfileResolver` refactor, the `PrescriptionResponse` field additions, or the `SessionLoop` band-tighten).
- Android: `:feature-workouts:testDebugUnitTest` incl. `SessionFormatTest` (new F1/F2/F5/F6 cases) and the Robolectric `ActiveRepCardRirGateTest` (2 tests, F4 gate) → **SUCCESSFUL**; `:app:testDebugUnitTest` incl. `WorkoutSessionNotificationContentTest` → **SUCCESSFUL**; `:core-data:testDebugUnitTest` (DTO/mapper) → **SUCCESSFUL**. All touched modules compile (Compose screen included).
- **Still owner/on-device sign-off (needs a real device + audio):** the on-device functional walkthrough steps 1–6 (§8.3) — TTS audio, the live system notification, and the highlight-vs-outcome-colour distinction as seen on screen. These remain 🟨 per §10.5.

**Decision-log review (2026-09-07):** deviations D5→engine change and D9→blind RIR pick were re-decided with the owner and implemented this pass (IMPL-D11/D12); the RIR-gate Compose test was added (IMPL-D13); weighted-bodyweight (belt+plate) was deferred (IMPL-D14). See `IMPL-PROG-02-decision-log.md`.

**Feedback → phase traceability:** F1→P2 · F2→P1 · F3→P1 · F4→P3 · F5→P4 · F6→P5.

---

## 8. Testing approach

Testing must prove both the **technical** implementation (the code does what the design says) and the **functional** implementation (the athlete's experience is correct). Per D13, automate wherever feasible.

### 8.1 Unit tests (logic)
- **Prefill precedence (D1):** prediction beats late `lastSets`; last-actual used only when no prediction.
- **Announcement text (D8):** built from target, not last-actual; "body weight" only when `isBodyweight == true`; numeric seed for first-time weighted.
- **Delta computation (D2/D9):** highlight fires iff `target != lastActual`; correct ▲/▼ and magnitude for weight and reps independently.
- **Engine rep-band reset (D5):** `PrescriptionCalculator` + `DoubleProgression` both yield displayed target reps == `repsMin` on a load increase; no regression to existing PROG-01 tests.
- **Seed weight (D7):** first-time weighted resolves catalog seed → pattern default → floor, `loadBasis == "catalog seed"`.
- **Bodyweight flag (D6):** `loadMode`/`isBodyweight` derived correctly for dips/pull-ups (BW) vs cable pushdown/machine (weighted).

### 8.2 Compose UI / instrumentation tests (behavior)
- **RIR gate (D4):** final set's done action disabled until an explicit RIR pick; enabled after; persists `REPORTED`; re-targets when sets are added/removed; non-final sets show no RIR.
- **Highlight distinctness (D9):** adjusted number uses the accent token, never `good/alert`; both can co-exist (a HIT on an adjusted weight shows outcome color on achieved + accent on target).
- **No start-race (F3):** with a delayed fake `lastSets` source, notification + screen content do not change within 2s of session start.
- **Rationale sheet (D3/D12):** opens from live screen and history; renders direction/delta/basis/confidence/path/e1RM.

### 8.3 Functional walkthrough (manual, only where un-automatable)
Documented script, run on device/emulator, each step with an **observable expected outcome** the agent/owner checks off:
1. Start a workout with an increased-weight lift → screen shows accent ▲ `+5 lb`, reps show band bottom, notification shows the same number immediately (no switch), voice says the predicted number once.
2. Edit the upcoming weight → voice re-announces the new number.
3. Reach the final working set → cannot check it done until a RIR chip is explicitly chosen.
4. Do cable pushdowns (first time) → voice says a numeric seed (e.g. "45 pounds"), **not** "body weight."
5. Do dips/pull-ups → voice says "body weight."
6. Open the rationale info → plain-language why; open session history afterward → same adjustment + rationale visible.

---

## 9. Definition of Done

An item is **Done** only when ALL hold:
1. Code implements the design in §5 and honors every relevant D-row in §3.
2. Unit tests in §8.1 for the item exist and pass.
3. UI/instrumentation tests in §8.2 for the item exist and pass (or the item is explicitly TTS/notification-audio and covered by §8.3 with owner sign-off).
4. The relevant §8.3 walkthrough step passes with its observable outcome recorded.
5. No regression: full backend + Android test suites green; existing IMPL-PROG-01 progression tests unchanged/green.
6. §7 dashboard updated; change pushed to `workout-prediction-improvements`.
7. Legacy data (old prescriptions/exercises without new fields) behaves safely — verified, not assumed.

"Code compiles" and "it looked right once" are **not** Done.

---

## 10. Agent verification protocol

Before marking any phase ✅ in §7, the agent MUST, and record the result inline:

1. **Build:** backend `./gradlew build` (or module test task) and Android `./gradlew :feature-workouts:testDebugUnitTest` (+ affected modules) — paste pass/fail summary.
2. **Targeted tests:** run the specific §8.1/§8.2 tests for the phase by name; paste the green result. A phase with no passing test for its core behavior cannot be ✅.
3. **Race assertion (P1/P3):** run the instrumentation test that injects a delayed `lastSets` and asserts no content flip; confirm it fails on the pre-change code and passes after (guard against a vacuous test).
4. **Bodyweight branch (P5):** assert both branches with a real weighted exercise id (cable pushdown) and a real bodyweight id (dip) — not a synthetic stub.
5. **Functional step:** for items with a §8.3 step, either drive it via the `run`/`verify` skill on an emulator and capture the observable outcome, or flag it for owner sign-off if it is audio-only. Never self-certify a walkthrough that wasn't actually executed.
6. **Regression gate:** confirm IMPL-PROG-01 progression test count is unchanged and all green before ✅ on P4.

If any step is skipped, the item stays 🟨 and the reason is written into §7.

---

## 11. Risks & open questions

- **R1 — Prefill reorder blast radius:** `prefillFor` also feeds non-prediction/legacy programs. Guard the reorder on "prediction present" so classic programs still prefill from last-actual. *(Verify no legacy program regresses in P1.)*
- **R2 — Seed weight coverage:** catalog `seedWeightLbs` is unpopulated at launch; the pattern-default/floor fallback must be sane for every movement pattern so no first-time lift is absurd. Backfill the common machines first.
- **R3 — RIR hard gate friction:** mandatory pick could annoy on quick sessions. Mitigation: inferred value pre-highlighted so it's a one-tap confirm, but still an explicit action (D4). Watch for complaints post-ship.
- **R4 — Re-announce chatter (D10):** rapid edits could cause repeated speech. `QUEUE_FLUSH` collapses overlaps; consider a short debounce if it feels noisy in the P1 walkthrough.
- **R5 — `loadMode` backfill correctness:** a mislabeled exercise flips the bodyweight branch. Backfill is code-derived from the resolver, then spot-checked against the walkthrough set (dip, pull-up, push-up, cable pushdown, machine press).
- **Q1 — Barbell bar weight:** does a "45 lb" seed include the empty bar for barbell lifts? Assumed the seed is total load; confirm during P5 backfill.

---

*End IMPL-PROG-02 v1. Update §7 as the control surface; amend §3 to change any decision.*
