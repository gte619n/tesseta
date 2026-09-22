# IMPL-PROG-LOAD-01 — Per-hand load reporting & realistic-jump progression

> Status: **planned** · Created 2026-09-22 · Branch: `progression-jumps` ·
> Source: owner request — (1) progression weight jumps are too big, especially
> for dumbbells; they should step by the smallest *real* increment the equipment
> allows; (2) dumbbell and dual-cable exercises are logged **per hand**, so the
> progress dashboard should express them as **total** load (a 60 lb DB bench is
> 120 lb total) to line up with traditional strength metrics; (3) when the lifter
> demonstrably beats the prescription with reps in reserve, the engine must carry
> that forward to the next session.
>
> All product/technical decisions in §2 were locked in a 4-round owner interview
> on 2026-09-22. Do not re-litigate a decision without flagging it to the owner
> first.
>
> **Progress at a glance:** update the status table in §6 every time a phase
> moves. A phase is only **Done** when its checklist in §6 *and* its verification
> gate in §7 both pass. The agent MUST run the gate commands and paste evidence
> into §9 before flipping a phase to Done.

---

## 1. Goal and non-goals

**Goal.** Two connected outcomes:

1. **Realistic progression.** The engine stops prescribing weight steps the
   lifter can't physically load. It resolves each exercise's *real* increment
   from the user's actual gym equipment (dumbbell pair steps, plate
   denominations, cable stack steps) and never speculatively jumps more than one
   such increment per session — **unless** the lifter has already demonstrated
   more (see below).

2. **Per-hand → total reporting.** The web workout dashboard expresses
   dumbbell and dual-cable lifts as **total** load (per-hand × 2), so e1RM,
   tonnage, PRs, and the strength trend are comparable to barbell lifts and match
   "traditional metrics". Per-hand stays visible as a secondary label.

Plus one behavioral rule that cuts across both: **demonstrated performance
overrides the speculative jump cap.** If the lifter completes a heavier load than
prescribed with reps in reserve, next session's target matches what they
actually lifted (snapped to a real increment), not the old prescription.

**Non-goals (explicitly out of scope):**

- **No change to how weights are entered.** The lifter keeps logging **per hand**
  on Android (a pair of 60s is entered as `60`). We are not migrating stored
  data to "total", and we are not touching the Android logging UI. The engine
  keeps its belief in the logged (per-hand) space; the ×2 is applied only at the
  reporting boundary. (Owner: "It's for reporting in the workout dashboard.")
- **No Android dashboard/display changes** in this effort. Android may later
  reuse the new `loadFactor` the API exposes; not here.
- **No kg-native storage.** Canonical storage stays pounds; existing
  `web/lib/units.ts` handles the lb/kg display toggle unchanged.
- **No new charting dependency.** Extend existing web components only.
- **No symmetric down-regulation** of the target when the lifter *under*-performs
  (logs lighter / grinds at RIR 0). The existing hold/deload logic handles the
  downside. This effort is **up-only** (D15).
- **No feature flags / parallel old path.** Phased, independently-shippable PRs,
  backend-first. Merge to main = prod deploy.

---

## 2. Decision log (owner interview, 2026-09-22)

| # | Topic | Decision |
|---|-------|----------|
| D1 | Logging convention (as-is) | The lifter logs **per hand** today (enters `60` for a pair of 60s). Confirmed. This stays; no entry-side change. |
| D2 | Scope | **Backend engine fix + web dashboard reporting.** No Android changes. Per-hand→total is "for reporting in the workout dashboard." |
| D3 | Canonical metric value | For dumbbell / dual-cable lifts, **total load (2× per-hand)** is the canonical value that drives e1RM, tonnage, PRs, and the strength trend. Per-hand is shown as a secondary label. |
| D4 | Jump size target | **Smallest real-world jump** — progress by the smallest increment that physically exists for that equipment; never prescribe an unachievable load (e.g. 62.5/hand). |
| D5 | Cable doubling | **Only dual / functional trainers double** (each hand pulls its own independent stack). Single-stack cables (lat pulldown, cable row, pushdown, single-pin) are logged at the pin/stack weight = already total; **not** doubled. |
| D6 | Single-implement / one-sided moves | Use an **explicit per-exercise load convention** (×1 vs ×2), not blanket equipment inference. DB bench = ×2; goblet squat / single-arm row = ×1. |
| D7 | Increment source of truth | **The user's actual gym equipment.** Resolve real increments from the session's location → equipment specs (dumbbell steps, plate denominations, stack increments), which the backend already models. |
| D8 | Where doubling is computed | **Backend exposes both.** Stats/progression DTOs carry a per-exercise `loadFactor` (1 or 2) *and* a pre-doubled `displayTotalLbs`; web just renders. Keeps the math server-side and testable; Android can reuse later. |
| D9 | Dashboard display format | **Total primary, per-hand secondary** — e.g. `120 lb total · 60/hand`. Total leads (drives the metrics); per-hand shown as a chip/subtitle. |
| D10 | Doubling reach | **Whole dashboard, consistently** — strength trend, e1RM, weekly tonnage/volume, and recent PRs all use total for per-hand exercises. Accept that DB tonnage roughly doubles vs today (uniform, since data is uniformly per-hand). |
| D11 | Load-convention backfill | **Auto-derive then allow override.** Seed ×2 for (Dumbbells + BILATERAL) and dual-cable; ×1 for everything else. Expose an override to fix the handful the rule gets wrong. |
| D12 | Increment fallback | **Sane defaults + cap 1 step.** When gym equipment is unknown / an exercise has no matching spec, fall back to DB +5/hand, barbell +5, machine +10, **and** never *speculatively* prescribe more than one real increment per session (this kills the Kalman overshoot "big jumps"). |
| D13 | Cap vs. demonstrated | **Demonstrated overrides the cap.** The 1-step cap limits only speculative extrapolation. If the lifter actually lifted heavier at positive RIR, the engine trusts that real data and may jump multiple increments to match proven capacity. |
| D14 | Outperformance → next target | **Match what you lifted, no add.** Re-prescribe the heavier weight actually completed (snapped to a real increment); let normal double-progression take it up from there. Do not add an extra increment on top. |
| D15 | Downside symmetry | **Up-only for now.** Only make the engine more responsive to outperformance; leave existing hold/deload logic for under-performance. |

---

## 3. Current state (verified 2026-09-22 by codebase sweep)

### 3.1 Progression engine (`backend/.../core/progression/`)

- **Weights are stored/modeled as TOTAL by convention (decision L2), single
  state per exercise, no per-hand/per-side split** — `ProgressionState.java`
  lines 13-14, `LoadingProfileResolver.java` lines 18-23. **But the owner logs
  per hand (D1), so in practice the engine's belief is in per-hand space for DB /
  dual-cable lifts.** e1RM for those lifts is therefore computed at ~half the
  real load today.
- **The engine already updates belief from *actual* logged performance**, not
  the prescription:
  - `SessionLoop.java` L113-120 reads `rx.loggedSets()`, then
    `SessionAnalysis.workingLoad(sets)` (heaviest actual set) and
    `observedE1rm(lastSet, offset)`.
  - `DoubleProgression.next(...)` (L29-66) keys `+increment` off `lastLoad` =
    the **actual** `workingLoad`, not the prescribed target.
  - Kalman observation (`SessionAnalysis.observedE1rm`, L61-67) is built from
    `lastSet.weightLbs()` + reps + `effectiveRir()` via Epley.
- **The jump cap is the blocker for D13/D14.** `PrescriptionCalculator.JUMP_CAP
  = 1.05` (L20-21) is applied against **`lastPrescribedLoad`**, not the
  demonstrated `workingLoad` (`SessionLoop.java` L230-233,
  `PrescriptionCalculator.java` L53-57). So if you were prescribed 140 but
  actually pressed 190 at RIR 2, next prescription is capped to 147 — it
  suppresses exactly the feedback the owner wants carried forward.
- **Increments are derived from the exercise NAME string, not equipment** —
  `LoadingProfileResolver.incrementFor()` (L71-77): `dumbbell/db → 5`,
  `machine/cable/pulldown/… → 10`, `barbell/bench/squat/deadlift → 5`, else `5`.
  `floorToIncrement(rawLoad, increment)` (`ProgressionMath.java` L43-47) snaps
  the final load down to that step. **No gym/location/equipment consultation.**
- **A per-user, per-exercise override store already exists but has no API/UI** —
  `ExerciseLoadingProfile{loadIncrementLbs, loadOffsetLbs, progressionEligible}`,
  `ExerciseLoadingProfileRepository.find/save`, persisted at
  `users/{userId}/exerciseLoadingProfiles/{exerciseId}`
  (`FirestoreExerciseLoadingProfileRepository.java` L28-30). No controller reads
  or writes it — it is programmatic-only today.
- **Existing "lifted heavier" detection is only a sanity band** —
  `SessionLoop.SANITY_BAND = 0.10` (L42): if the Kalman prescription diverges
  >±10% from `workingLoad`, it falls back to double progression. It is reactive,
  not a proactive "trust the demonstrated load" rule.

### 3.2 Gym / equipment model (already exists on backend)

- `core/equipment/Equipment.java` (mirror of `web/lib/types/gym.ts`):
  `category`, `subcategory`, `specSchema: SpecSchema`, `specs: Map`.
- `SpecSchema`: `SELECTORIZED, PLATE_LOADED, BODYWEIGHT, CABLE, CARDIO,
  WEIGHT_SET`.
- Specs contain the increment data we need: SELECTORIZED `{minWeight, maxWeight,
  increment}`, PLATE_LOADED `{barWeight, availablePlates[]}`, CABLE
  `{weightStack, numStations}`, WEIGHT_SET (dumbbells/kettlebells) available
  weights.
- `core/location/Location.java`: `equipmentIds: List<String>` +
  `equipmentSpecs: Map<String, Map<String,Object>>` (per-location overrides).
  `LocationRepository.findByUser / findById`.
- **The session already carries the location**: `Workout.locationId`,
  `ScheduledWorkout.locationId` (passed through `ProgressionWriteback` L83 but
  never used to influence increments). This is the hook D7 needs.
- `Exercise.java` has `laterality (BILATERAL|UNILATERAL)`, `mechanic
  (COMPOUND|ISOLATION)`, and `requiredEquipment: List<EquipmentRequirement>`
  (any-of catalog IDs). **There is no load-convention / per-hand / load-multiplier
  field** — this effort must add it (D6/D11).

### 3.3 Web dashboard (`web/`)

- `components/workouts/StrengthTrendChart.tsx` (e1RM trend + searchable lift
  picker), `ProgressionConsole.tsx` (strength card), `WeeklyVolumeChart.tsx`
  (tonnage bars), `RecentPrsCard.tsx` — all render **raw logged pounds**, no
  per-hand/total awareness.
- Stats/progression DTOs consumed by web carry **no equipment field and no
  factor**: `ExerciseStrength{exerciseId,name,movementPattern,e1rmLbs,confidence,
  observationCount}`, `E1rmPoint{date,e1rmLbs,weightLbs,reps,lowConfidence}`,
  `PrPoint{…,e1rmLbs,weightLbs,reps,…}`, `WeekPoint` (tonnage).
- API surface: `GET /api/me/workout-stats`, `.../e1rm-history?exerciseId=`,
  `GET /api/me/progression/{week-review,strength,block-parameters,energy-balance}`.

---

## 4. Design

### 4.1 Load convention (the ×2 factor) — D3/D5/D6/D8/D11

Add a **`LoadConvention`** to the exercise catalog describing how logged weight
maps to *total* load for reporting:

```
enum LoadConvention { TOTAL, PER_HAND }   // factor = TOTAL→1, PER_HAND→2
```

- Stored on `Exercise` (new nullable field; null ⇒ treat as `TOTAL` for back-compat).
- **Derivation / backfill (D11):** a pure function
  `deriveLoadConvention(exercise, equipmentCatalog)`:
  - `PER_HAND` when the required equipment resolves to **Dumbbells** and
    `laterality == BILATERAL`; **or** the equipment is a **dual / functional
    cable trainer** (each hand its own stack — D5).
  - `TOTAL` otherwise (single-implement DB moves, single-stack cables, barbell,
    machines, bodyweight).
- **Override (D11):** an explicit per-exercise value wins over the derivation.
  Reuse the existing exercise catalog write path (admin edit) — add the field to
  the exercise DTO/editor. No new store needed for the convention itself.
- **Reporting factor** `loadFactor = convention == PER_HAND ? 2 : 1`. The engine
  does **not** use this — it is applied only when building reporting DTOs (§4.3).

> Rationale for keeping the engine in per-hand space: the lifter logs per hand,
> the belief is self-consistent (per-hand in, per-hand out), and doubling only at
> the reporting boundary avoids a data migration (D2). `displayTotalLbs =
> rawLbs × loadFactor`.

### 4.2 Realistic increments + speculative one-step cap — D4/D7/D12/D13/D14

**Increment resolution (D7).** Extend `LoadingProfileResolver` to resolve
`loadIncrementLbs` from the **session's location equipment**, in priority order:

1. Explicit `ExerciseLoadingProfile` override (existing store) — wins.
2. The user's location for that session (`ScheduledWorkout.locationId` →
   `Location.equipmentIds`/`equipmentSpecs` → the exercise's `requiredEquipment`
   match) → derive the smallest real step:
   - **WEIGHT_SET / dumbbells:** smallest gap between consecutive available
     dumbbell weights (per-hand, matching logged space).
   - **PLATE_LOADED / barbell:** `2 × smallest available plate` (a plate per
     side) — never below one usable step.
   - **SELECTORIZED / CABLE:** the stack `increment`.
3. **Fallback (D12):** sane per-equipment defaults from the name heuristic
   (DB +5, barbell +5, machine/cable +10). Log when the fallback is used.

**Speculative one-step cap (D12/D13/D14).** Replace the flat `JUMP_CAP = 1.05`
with an increment-based, demonstration-aware bound in `derivePrescription`:

```
step        = resolved loadIncrementLbs
demonstrated = workingLoad                       // heaviest actual set this session
lastRx       = completedRx.targetWeightLbs (or workingLoad if null)

// D13: demonstrated performance raises the ceiling; only *speculation* is capped
ceiling = max(lastRx, demonstrated) + step        // at most one real step past what's proven

// D14: if they beat the target with reps in reserve, floor the next target to
// what they actually did (snap to a real increment), no add on top
outperformed = demonstrated > lastRx && lastSet.effectiveRir() != null
               && lastSet.effectiveRir() >= OUTPERFORM_RIR_MIN   // e.g. >= 1
floor        = outperformed ? floorToIncrement(demonstrated, step) : 0

target = clamp(rawKalmanLoad, floor, ceiling)
target = floorToIncrement(target, step)           // never an unachievable load (D4)
```

- When the lifter is on-plan, `demonstrated ≈ lastRx`, so `ceiling = lastRx +
  step` → **at most one real increment per session** (kills the overshoot).
- When they demonstrably beat plan at positive RIR, `floor` pulls the target up
  to what they actually lifted, and `ceiling` allows a multi-increment jump
  because it is grounded in real data, not extrapolation (D13/D14).
- Down-regulation is untouched (D15): existing hold/deload/`anyBelowBottom`
  paths in `DoubleProgression`/`SessionLoop` still run.

> The Kalman belief already moves toward the observed (demonstrated) e1RM
> (§3.1); the only real code change to enable D13/D14 is the cap basis
> (`lastRx` → `max(lastRx, demonstrated)`) plus the outperformance floor. That is
> the crux fix.

### 4.3 Reporting DTOs + web display — D8/D9/D10

**Backend (D8/D10).** Add to the per-exercise reporting DTOs a `loadFactor`
(1|2) and pre-doubled totals; apply the factor server-side wherever a total is
aggregated:

- `ExerciseStrength`: `+ loadFactor`, `+ e1rmTotalLbs` (= `e1rmLbs × factor`).
- `E1rmHistory`/`E1rmPoint`: `+ loadFactor`; expose `e1rmTotalLbs`,
  `weightTotalLbs` per point (raw kept for debugging).
- `PrPoint`: `+ loadFactor`, `+ e1rmTotalLbs`, `+ weightTotalLbs`.
- **Weekly tonnage (`WeekPoint`)**: multiply each contributing set's weight by
  its exercise's `loadFactor` **during aggregation** so DB tonnage reflects total
  load (D10). This is a backend aggregation change, not a display multiply.

**Web (D9).** Consume `displayTotal*` + `loadFactor`; render **total primary,
per-hand secondary**:

- A small formatter, e.g. `formatPerHand(totalLbs, factor, unit)` →
  `"120 lb total · 60/hand"` when `factor === 2`, else just the value.
- Apply in `StrengthTrendChart` (headline + axis in total; per-hand chip),
  `ProgressionConsole` strength card, `WeeklyVolumeChart` (already-doubled
  tonnage), `RecentPrsCard`.
- Respect the existing lb/kg toggle (`useUnits()`); the ×2 is unit-independent
  (applied before the unit conversion).

---

## 5. Affected files (initial map — confirm during implementation)

**Backend — data model / convention**
- `core/exercise/Exercise.java` (+ `LoadConvention`), new
  `core/exercise/LoadConvention.java`, `LoadConventionDeriver` (pure).
- Exercise catalog DTO + admin edit path (add the override field).

**Backend — engine**
- `core/progression/LoadingProfileResolver.java` (equipment-aware increment).
- `core/progression/PrescriptionCalculator.java` +
  `core/progression/SessionLoop.java` (cap basis + outperformance floor).
- New helper to map exercise `requiredEquipment` + `Location` specs → real step.

**Backend — reporting**
- `WorkoutStatsService.java` + its DTOs (`ExerciseStrength`, `E1rmHistory`/
  `E1rmPoint`, `PrPoint`, `WeekPoint`) — add factor/totals; double tonnage.
- Progression `strength` endpoint DTO.

**Web**
- `lib/types/{workout-stats,progression}.ts` (new fields).
- `lib/units.ts` or a new `lib/perHand.ts` (formatter).
- `components/workouts/{StrengthTrendChart,ProgressionConsole,WeeklyVolumeChart,RecentPrsCard}.tsx`.

---

## 6. Phases & status tracking

Legend: **Impl** = code written · **Tested** = phase tests written & green ·
**Pushed** = merged to `main` (prod). Do not check **Tested** without the §7
gate passing; do not check **Done** without both Impl+Tested and the gate
evidence pasted into §9.

| Phase | Deliverable | Impl | Tested | Pushed | Done |
|-------|-------------|:----:|:------:|:------:|:----:|
| **P0** | `LoadConvention` model + derivation + override field | ✅ | ✅ | ☐ | ✅ |
| **P1** | Backend reporting: `loadFactor` + `*TotalLbs` on DTOs; tonnage doubled | ✅ | ✅ | ☐ | ✅ |
| **P2** | Web dashboard: total-primary / per-hand-secondary across all cards | ✅ | ✅ | ☐ | ✅ |
| **P3** | Engine: equipment-aware real increments + speculative one-step cap + fallback | ✅ | ✅ | ☐ | ✅ |
| **P4** | Engine: demonstrated-performance override (match-what-you-lifted, up-only) | ✅ | ✅ | ☐ | ✅ |
| **P5** *(stretch)* | User-facing per-exercise increment/convention override UI | ☐ | ☐ | ☐ | ☐ (deferred, see IL-1/IL-2 — data/logic layer done, UI out of scope) |

> **Pushed** is unchecked for all phases: the branch `progression-jumps` is not
> yet merged to `main`. Owner to merge (DoD-6). Everything else — code + tests +
> gates — is complete and green (see §9). P5 (stretch, not part of DoD-6) is
> intentionally deferred; the override *mechanism* exists (`ExerciseLoadingProfile
> .loadConventionOverride`), only the editing UI is out of scope.

**Sequencing rationale:** backend-first, most-visible-win-first. P0 is the shared
foundation. P1→P2 ship the owner's headline ask (per-hand→total reporting) and
are independent of the engine work. P3→P4 fix progression jumps; P4 depends on
P3's real `step`. P5 is optional polish. Each phase is independently shippable;
merge to main = prod deploy.

**Remaining after all phases:** Android reuse of `loadFactor` (future, out of
scope), and any per-exercise convention corrections surfaced by real usage.

---

## 7. Testing approach & verification gates

Every phase has **(a) technical** tests (does the code do what the code says)
and **(b) functional** tests (does the *feature* behave as the owner asked),
plus an **agent-runnable gate** with an unambiguous pass condition. The agent
MUST run the gate and paste evidence into §9 before flipping **Done**.

Backend runs with the project's Gradle test task; web with its unit runner +
Playwright e2e (fixture-driven, per IMPL-WEB-WORKOUT-01 D18 precedent). Discover
the exact commands from the repo (`backend/` Gradle wrapper; `web/package.json`).

### P0 — Load convention
- **Technical:** unit tests for `LoadConventionDeriver`: DB-bench (Dumbbells +
  BILATERAL) → `PER_HAND`; single-arm DB row (UNILATERAL) → `TOTAL`; goblet
  squat (one DB) → `TOTAL` via override or the BILATERAL edge (assert the
  documented behavior); dual-cable fly → `PER_HAND`; lat pulldown (single stack)
  → `TOTAL`; barbell/machine/bodyweight → `TOTAL`. Explicit override beats
  derivation.
- **Functional:** a seeded catalog backfill run produces the expected
  convention for a fixed sample of ≥10 named exercises (table in test).
- **Gate:** backend progression+exercise test suites green; a printed
  before/after convention table for the sample matches the expected table exactly.

### P1 — Backend reporting factor
- **Technical:** DTO tests — a PER_HAND exercise returns `loadFactor=2`,
  `e1rmTotalLbs == 2 × e1rmLbs`, `weightTotalLbs == 2 × weightLbs`; a TOTAL
  exercise returns `loadFactor=1` and equal raw/total.
- **Functional:** weekly tonnage for a fixture week containing one DB lift
  (per-hand 60 × N sets/reps) and one barbell lift equals the **hand-computed**
  total-load tonnage (DB contribution doubled). Assert the exact number.
- **Gate:** `WorkoutStatsServiceTest` (extended) green; the hand-computed
  tonnage assertion passes with the numeric expected value in the test.

### P2 — Web display
- **Technical:** component/unit test of `formatPerHand`: `(120,2,'lb') →
  "120 lb total · 60/hand"`; `(135,1,'lb') → "135 lb"`; kg toggle converts the
  total, not a doubled-twice value.
- **Functional (owner-mandated gate):** Playwright e2e against a stub backend
  returning one PER_HAND and one TOTAL lift. Assert the dashboard shows total as
  the primary number and per-hand as the secondary for the DB lift, and a plain
  number for the barbell lift, on: strength trend, strength card, weekly volume,
  recent PRs. One phone-width check.
- **Gate:** web unit + the e2e spec green; screenshot/DOM assertion evidence in §9.

### P3 — Realistic increments + speculative cap
- **Technical:** `LoadingProfileResolver` tests — given a location whose
  dumbbells step by 5/hand, a DB lift resolves `step=5`; plate-loaded with 2.5 lb
  smallest plate → `step=5`; selectorized stack `increment=15` → `step=15`;
  missing location/spec → documented fallback (5/5/10) and a log line.
  `floorToIncrement` never yields an unachievable load.
- **Functional (the owner's core complaint):** a scenario test — lifter on-plan
  at the top of the rep band with a resolved `step` produces a next target
  exactly `+step` (not a 5%/overshoot value); assert it is **≤ one real
  increment**. Include a machine case to prove the old flat `+10`/overshoot is
  gone where the real step is smaller.
- **Gate:** progression suite green; the on-plan scenario asserts
  `nextTarget - lastTarget == step` for DB, barbell, and machine fixtures.

### P4 — Demonstrated override
- **Functional (the owner's follow-up):** scenario — prescribed 140, lifter
  logs 190 × reps at RIR 2. Assert next target ≥ `floorToIncrement(190, step)`
  (i.e. it "matches what you lifted", **not** capped to 147) and has **no**
  extra increment added beyond a real step ceiling (D14). Second scenario:
  on-plan session → still capped to one step (proves the override is gated on
  actual outperformance, not always-on). Third: under-performance (RIR 0 / missed
  reps) → existing hold/deload unchanged (D15 regression guard).
- **Technical:** unit test that the cap basis is `max(lastRx, workingLoad)` and
  the outperformance floor triggers only when `effectiveRir() >=
  OUTPERFORM_RIR_MIN`.
- **Gate:** the three scenarios above green, with the numeric next-target
  asserted in each.

### P5 — Override UI (stretch)
- **Technical + Functional:** editing an exercise's convention/increment persists
  and changes the next prescription/report; e2e of the edit flow.
- **Gate:** save round-trips and a follow-up read reflects the override.

### Cross-cutting regression
- Full backend progression suite (`DoubleProgressionTest`,
  `PrescriptionCalculator`, `SessionLoop`, `WorkoutStatsServiceTest`) stays green
  every phase — no silent behavior change for TOTAL/barbell lifts.

---

## 8. Definition of Done

The feature is **Done** when all of the following hold and are evidenced in §9:

1. **DoD-1 (Realistic jumps).** For an on-plan session, the next prescribed load
   moves by exactly the equipment's real increment and never a value the lifter
   can't load. *Verify:* P3 functional gate green (DB/barbell/machine).
2. **DoD-2 (Demonstrated carry-forward).** A session where the lifter beats the
   target with reps in reserve yields a next target that matches what they
   actually lifted (snapped), not the old cap. *Verify:* P4 functional gate green.
3. **DoD-3 (No down-side regression).** Under-performance behavior is unchanged.
   *Verify:* P4 downside regression scenario green + full progression suite green.
4. **DoD-4 (Total reporting).** On the web dashboard, dumbbell and dual-cable
   lifts show **total** load as the primary metric with per-hand secondary, and
   e1RM / tonnage / PRs / strength trend all reflect total, consistently.
   *Verify:* P2 e2e gate green; P1 tonnage math assertion green.
5. **DoD-5 (Correct classification).** The load convention is right for the
   sampled catalog (incl. single-arm and single-DB edge cases via override).
   *Verify:* P0 gate table matches.
6. **DoD-6 (Shipped).** All non-stretch phases (P0–P4) merged to main and the
   relevant `deploy-*-on-main` check-runs green.
7. **DoD-7 (Owner acceptance).** Owner confirms, on their real data, that (a)
   dumbbell jumps feel right / are loadable, (b) the dashboard totals look
   correct for a known lift (e.g. their DB bench reads ~120, not ~60), and
   (c) a deliberately-heavy logged set carries forward next session.

**Agent verification protocol (do this before marking any phase or the feature
Done):**
1. Run the phase's §7 gate command(s). Paste the exact command and the
   pass/fail tail of the output into §9.
2. For functional gates, paste the asserted expected-vs-actual numbers (tonnage,
   next-target) or the e2e DOM/screenshot assertion — not just "tests passed".
3. Confirm the cross-cutting progression suite is still green.
4. Only then check the phase's **Done** box in §6. If a gate cannot be run,
   record why in §9 and leave the box unchecked.
5. DoD-7 is **owner-only** — the agent may not self-certify acceptance; request
   owner sign-off and record it in §9.

---

## 9. Verification log (agent fills in as phases complete)

> Append one block per gate run. Template:
>
> ```
> [P#][YYYY-MM-DD] gate: <name>
> cmd: <exact command>
> result: PASS/FAIL — <key assertion: expected X, got X>
> evidence: <output tail / test name / screenshot path>
> ```

```
[P0-P4][2026-09-22] gate: full backend test suite (regression + all new gates)
cmd: ./gradlew test   (JAVA_HOME=~/.sdkman/candidates/java/current)
result: PASS — 944 tests, 0 failures, 0 errors, 19 skipped
evidence: BUILD SUCCESSFUL; aggregated from build/test-results/test/*.xml
```

```
[P0][2026-09-22] gate: LoadConvention derivation + override
cmd: ./gradlew test --tests '*.LoadConventionResolverTest'
result: PASS — 9/9. Bilateral DB→PER_HAND; single-arm DB→TOTAL; dual-cable→PER_HAND;
  single-stack cable→TOTAL; barbell/machine/bodyweight→TOTAL; explicit override wins
  (goblet-squat edge corrected TOTAL); batch factors {DB:2, barbell:1, unknown:1}.
evidence: LoadConventionResolverTest.xml tests="9" failures="0"
```

```
[P1][2026-09-22] gate: reporting factor + doubled tonnage (hand-computed)
cmd: ./gradlew test --tests '*.WorkoutStatsServiceTest'
result: PASS — 15/15 incl. perHandDumbbellLiftReportsTotalLoad:
  week tonnage = (60×10+65×10)×2 [DB] + 100×5 [barbell] = 2500+500 = 3000.0 (asserted exact);
  PR loadFactor=2, e1rmTotal=2×e1rm, weightTotal=130 (65/hand→130); e1rmHistory loadFactor=2,
  point weightTotal=120 (60/hand→120).
evidence: WorkoutStatsServiceTest.xml tests="15" failures="0"
```

```
[P2][2026-09-22] gate: web per-hand display (RTL stand-in for e2e, IL-9)
cmd: cd web && npx tsc --noEmit && CI=true npx vitest run
result: PASS — tsc clean; 118/118 vitest incl. "PROG-LOAD P2 · per-hand → total display":
  RecentPrsCard DB row shows "210 lb" total + "180 × 5" + "90/hand", barbell row plain (no /hand);
  StrengthTrendChart headline "212" total + "106/hand" secondary; barbell headline "265" no /hand.
evidence: vitest "Test Files 18 passed (18) / Tests 118 passed (118)"
```

```
[P3][2026-09-22] gate: equipment-aware real increments + fallback
cmd: ./gradlew test --tests '*.LoadingProfileResolverTest'
result: PASS — 6/6. Gym-driven: DB WEIGHT_SET increment 2.5 (not name-default 5); barbell
  step = 2×min plate 1.25 = 2.5; selectorized stack 15 (not machine-default 10); weights list
  gap = 5. Fallback: no location → DB name-default 5; unusable gym spec → machine-default 10.
evidence: LoadingProfileResolverTest.xml tests="6" failures="0"
```

```
[P4][2026-09-22] gate: demonstrated override (unit + end-to-end)
cmd: ./gradlew test --tests '*.PrescriptionCalculatorTest' --tests '*.SessionLoopTest'
result: PASS — PrescriptionCalculatorTest 6/6: on-plan capped to +1 increment (100→105);
  outperformed 190@reserve → matched 190 (NOT +5% cap 147); grinding (RIR0) 190 → <190 (not matched).
  SessionLoopTest 3/3 incl. demonstratedHeavierSessionWithReserveCarriesForward: end-to-end
  Kalman path, prescribed 140 + logged 190×8 @ RIR2 → next target 190.0 (>147).
evidence: PrescriptionCalculatorTest.xml tests="6"; SessionLoopTest.xml tests="3" failures="0"
```

**Outstanding for DoD:** DoD-6 (merge to `main` + green deploy checks) and DoD-7
(owner acceptance on real data) — both require the owner. All engineering gates
(DoD-1…DoD-5) are green above.

---

## 10. Open questions / risks

- **Location on the session may be null** for older/imported sessions or ad-hoc
  workouts → increment falls back to defaults (D12). Acceptable; logged.
- **`requiredEquipment` any-of ambiguity:** an exercise doable with DB *or*
  barbell has two possible conventions/increments. Resolve by the equipment the
  session's location actually has; if both/neither, prefer the derivation default
  and allow override (D6/D11).
- **Dual-cable detection (D5)** depends on equipment `subcategory`
  (`Cable Systems` → `Dual Cable`/`Multi-Station`). Confirm the catalog tags
  these distinctly; if not, treat all cables as `TOTAL` (safe) and override the
  known dual-cable moves.
- **`OUTPERFORM_RIR_MIN` threshold (D14)** — start at `>= 1` (at least one rep in
  reserve). Tune with owner after real sessions.
- **Historical dashboard shift (D10):** existing DB numbers roughly double on
  first render. Uniform (data is uniformly per-hand), so no per-series step, but
  worth a one-line release note so it isn't read as a data bug.
