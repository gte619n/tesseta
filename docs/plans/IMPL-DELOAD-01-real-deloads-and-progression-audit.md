# IMPL-DELOAD-01 — Real deload weeks + target retention + progression audit

> Status: **planned** · Created 2026-09-22 · Source: owner report — the 09-21
> "Push" session's shoulder-press progression "seemed super off"; prod
> investigation found (1) the scheduled deload week is a **complete no-op**,
> (2) session completion **destroys** the engine's targets/rationale (which also
> silently neuters IMPL-PROG-LOAD-01's demonstrated-override in prod), and
> (3) there is **no deload indication** anywhere in coaching or on web.
> Owner directive: fix the deload, indicate deload weeks during coaching, make
> all progression data (target vs achieved, how targets were determined)
> visible in the web UI, and store it durably.
>
> Product decisions D1–D4 locked in a 1-round owner interview on 2026-09-22.
> Implementation-time decisions go to `IMPL-DELOAD-01-decision-log.md`
> (create on first deviation), per house convention.

---

## 1. Root causes (verified 2026-09-22, prod data + code trace)

### RC-1 — The scheduled deload week does nothing

- `ScheduledWorkout.isDeload` is set once at materialization
  (`WorkoutScheduleService` L50: `phase.deloadWeekIndex() == week`) and after
  that is **pass-through only** — every reader just copies it into rebuilt
  records or serializes it. No code path reduces sets or load because of it.
- `DeloadModifier(setsMultiplier, intensityDelta)` is parsed from the AI
  program generator (`GeminiWorkoutProgramChatClient` L214), persisted,
  round-tripped through every record rebuild — and **never applied** anywhere.
- `ProgressionWriteback.applyNextPrescription` stamps the full progression
  target onto **all** future PLANNED sessions including deload-week ones, so
  even a pre-lightened deload session would be overwritten.
- The only live deload mechanism is the *fatigue-driven* engine deload
  (`SessionLoop.weeklyTargetSets` halves sets when
  `WeekParameters.isDeloadActive(pattern)`, stored at
  `users/{uid}/progressionWeek/current`) — a **different feature** (WeekLoop
  trend analysis) that currently never triggers (RIR sparsity → all trends
  UNKNOWN, see workout-coach-progression-diagnosis).

**Prod evidence (user 102934494306972576484, program wp_66c2e1b6-593):**
09-21 Push had `isDeload=true` (week 4 of phase ph_5801ec2b) yet vs 09-14:
DB Bench 60→60, Cable Chest 35→40 **↑**, Lateral Raise 20→25 **↑**, Tricep
Pushdown 40→50 **↑**, sets unchanged. Nothing deloaded. The Dumbbell Overhead
Press was the lone drop (40→35); band evidence (raised lifts carried tightened
8–9 bands = engine UP; OHP kept the full 8–12 band = engine HOLD at 40) says
the engine prescribed 40 and the athlete lifted 35 — unprovable post-hoc
because of RC-2.

### RC-2 — Completion destroys targets (and quietly breaks the engine)

`WorkoutSessionCompletionService.withLoggedSets` (L324-328) rebuilds every
prescription through the legacy **12-arg `Prescription` constructor**, which
nulls `targetWeightLbs`, `loadBasis`, and `rationale`. The stripped snapshot is
both **persisted** and **published** as the `SessionCompletedEvent`. Three
consequences:

1. **Audit impossible** — completed docs carry `target=null`, so
   target-vs-achieved cannot be answered after the fact (this is exactly what
   blocked the 09-21 investigation).
2. **IMPL-PROG-LOAD-01's demonstrated-override is dead in prod** —
   `SessionLoop.derivePrescription` computes
   `lastPrescribed = completedRx.targetWeightLbs() != null ? … : workingLoad`,
   which is **always** the fallback, so `outperformed = workingLoad >
   lastPrescribed` is **always false**, and the speculative cap has always been
   keyed off performed load rather than the prescription.
3. **PredictionLog corrupted** — `logPredictions`' `prescribedLoad` takes the
   same fallback, so the shadow-model audit trail records performed load as
   "prescribed".

### RC-3 — No deload indication anywhere

Nothing in the Android active-workout UI, voice coaching, notification, or web
shows that a session is a deload. (`isDeload` already flows to both clients:
`WorkoutProgramAssembler` L164 → Android `WorkoutProgramDto.kt` / web
`workout-program.ts` — so this is UI work, not sync plumbing.)

---

## 2. Decision log (owner interview, 2026-09-22)

| # | Topic | Decision |
|---|-------|----------|
| D1 | Deload dose | **Halve sets + ~10% lighter.** Sets × 0.5 (min 1) and target load × 0.90, snapped **down** to the exercise's real increment (reuses IMPL-PROG-LOAD-01's equipment-aware step). The AI-authored `DeloadModifier` wins when present (`setsMultiplier`, `intensityDelta`); these are the defaults when it's null (as it is throughout the current program). |
| D2 | Post-deload resumption | **Resume pre-deload trajectory.** Deload performance can never lower (or raise) the earned targets. Design: a deload session's completion runs **no prescription writeback at all** — post-deload sessions already carry the trajectory target stamped by the last non-deload completion (§4.2). |
| D3 | Belief update on deload | **Skip the Kalman correction; keep the observations.** Deload sets are recorded as `SetObservation`s tagged with a new `ContextFlag.DELOAD`; the belief only grows uncertainty with elapsed time (same as the no-RIR path). |
| D4 | Web audit scope | **Full audit.** (a) Session detail shows target-vs-achieved per exercise + deload badge; (b) history/heatmap rows get deload badges; (c) new per-lift **Progression log** drilldown: date, path (warmup/Kalman/DP/deload), target vs achieved, direction, plain-English rationale. |

Derived implementation decisions (flag to owner if changed):

| # | Decision |
|---|----------|
| DD-1 | **Target retention rule:** completion preserves `targetWeightLbs`/`loadBasis`/`rationale` on the completed snapshot — **except** when the exercise was substituted mid-session (`substituteByKey` hit), where they are nulled because they describe the originally-designed movement, not the one performed. |
| DD-2 | **Deload transform chokepoint:** applied inside `ProgressionWriteback` per target session (it already receives each `ScheduledWorkout` and is the single place targets are stamped): `isDeload` sessions get the transformed prescription, others the full one. Needs the exercise's increment → thread `loadIncrementLbs` into the writeback call (SessionLoop already holds the resolved profile). |
| DD-3 | **New `ProgressionPath.DELOAD`** rationale path with inputs like `"deload week → −10% load, half sets"` so the existing Android RationaleStrip and web rationale rendering explain it for free. ⚠️ Compat gate: verify both clients tolerate an unknown path string before shipping (Android enum mapping must not crash). |
| DD-4 | **Deload-session completion in `SessionLoop.processExercise`:** record observations (flagged `DELOAD`), skip belief correction (sigma-growth-only branch), skip `logPredictions`, skip `derivePrescription`/writeback entirely (D2). |
| DD-5 | **Materialization also pre-lightens** deload sessions' `sets` (× modifier) at schedule time so a deload week is visibly lighter even before the engine has ever stamped a target (cold-start programs). Load stays engine-owned. |
| DD-6 | **No backfill.** Historical completed docs were stripped (RC-2); the destroyed targets are unrecoverable (PredictionLog's copy took the same fallback). The audit is rich going forward only. |
| DD-7 | **Progression log endpoint** `GET /api/me/progression/log?exerciseId=` is *derived, not stored*: assembled from completed `ScheduledWorkout`s (retained target/basis/rationale + top logged set + `isDeload`) — newest-first, no new collection. |

---

## 3. Goal and non-goals

**Goal.** A scheduled deload week actually deloads (D1), never damages the
earned progression trajectory (D2/D3), is clearly indicated during coaching on
the phone (RC-3), and every prescription's target, provenance (path +
rationale), and achieved result is durably stored and visible in a web audit
UI (D4). Side effect of the foundation: IMPL-PROG-LOAD-01's
demonstrated-override starts actually working in prod (RC-2 fix).

**Non-goals:**
- No change to the *fatigue-driven* WeekLoop deload (separate mechanism; it
  keeps its sets-halving behavior). A deload badge in the web week-review is a
  stretch item only.
- No backfill of destroyed historical targets (DD-6).
- No new Android screens — indication only (chip, voice line, notification
  suffix); the RationaleStrip already renders rationale.
- No change to how deload weeks are *scheduled* (phase `deloadWeekIndex` stays
  the source; program-editing UX is out of scope).
- No offline/outbox work; web audit surfaces are read-only force-dynamic.

---

## 4. Design

### 4.1 P0 — Target retention through completion (foundation)

`WorkoutSessionCompletionService.withLoggedSets` switches to the full
15-arg constructor, carrying `targetWeightLbs`, `loadBasis`, `rationale`
through the completed snapshot (DD-1 substitution exception). Firestore
serialization already handles these fields (planned docs carry them today).

Unlocked immediately, no further change needed:
- `SessionLoop` receives the real `lastPrescribed` → the speculative cap is
  keyed correctly and `outperformed` can fire (IMPL-PROG-LOAD-01 D13/D14 goes
  live in prod).
- `PredictionLog.prescribedLoad` records the true prescription.
- Completed docs become auditable (feeds P2).

### 4.2 P1 — Real scheduled deloads in the engine

**Stamping (DD-2/DD-3).** `ProgressionWriteback.rewriteDay` gains the deload
transform for target sessions with `isDeload=true`:

```
deloadSets  = max(1, round(sets × (modifier.setsMultiplier ?? 0.5)))
deloadLoad  = floorToIncrement(target × (1 + (modifier.intensityDelta ?? -0.10)), increment)
rationale   = PATH=DELOAD, dir=DOWN, inputs=["deload week → −10% load, half sets",
              "resumes <target> after deload"]
```

Non-deload future sessions keep the untransformed target — so the trajectory
is always physically present on the post-deload sessions (this is what makes
D2 free).

**Completion of a deload session (DD-4).** In `SessionLoop.processExercise`,
when `completed.isDeload()`: record observations with `ContextFlag.DELOAD`,
take the sigma-growth-only belief branch, skip prediction logging, and return
before `derivePrescription` — no writeback. Post-deload sessions keep the
targets stamped by the last real session.

**Materialization (DD-5).** `WorkoutScheduleService` applies the sets
multiplier to deload-week sessions at schedule time.

### 4.3 P2 — Web progression audit (D4)

- **Session detail:** each exercise row shows `target <T> lb · <basis>` beside
  the logged sets (and a delta marker when achieved ≠ target); page header gets
  a `Deload` badge when `isDeload` (field already in
  `ScheduledWorkoutResponse`).
- **History + heatmap:** `Deload` badge on rows/tooltips.
- **Progression log (DD-7):** new backend endpoint + web drilldown reachable
  from the strength card / trend chart: table of
  `date · path · target → achieved (top set) · direction · rationale inputs ·
  deload?`. Per-hand lifts render totals per IMPL-PROG-LOAD-01 D9 (reuse
  `lib/per-hand.ts`; endpoint carries `loadFactor`).

### 4.4 P3 — Coaching indication (Android)

- `Deload week` chip in the active-workout header (bind existing
  `isDeload` from the session DTO; use settings-ux primitives where they fit).
- Voice coach prepends a one-liner at session start: “Deload week — lighter on
  purpose. Targets are reduced; the plan resumes next session.”
- Ongoing-notification title gets a `· deload` suffix.
- RationaleStrip renders the DELOAD rationale with no changes (DD-3), pending
  the enum-compat gate.

---

## 5. Affected files (initial map — confirm during implementation)

**Backend**
- `core/workoutprogram/WorkoutSessionCompletionService.java` (P0 retention).
- `core/progression/ProgressionWriteback.java` (+ increment param, deload
  transform), `SessionLoop.java` (deload branch; pass increment),
  `ContextFlag.java` (+`DELOAD`), `ProgressionPath.java` (+`DELOAD`).
- `core/workoutprogram/WorkoutScheduleService.java` (DD-5 sets pre-lightening).
- `api/progression/ProgressionController.java` (+`/log` endpoint + DTO).

**Web**
- `components/workouts/SessionDetail.tsx` (+target chip, deload badge),
  history/heatmap components (+badge), new `ProgressionLog` view + route +
  types, `lib/progression-api.ts`.

**Android**
- Active-workout header (chip), session-start announcement, notification
  content builder, rationale-path DTO mapping tolerance check.

---

## 6. Phases & status tracking

Legend: **Impl** · **Tested** (phase gate green) · **Pushed** (merged to main).
A phase is **Done** only with Impl+Tested and gate evidence pasted into §8.

| Phase | Deliverable | Impl | Tested | Pushed | Done |
|-------|-------------|:----:|:------:|:------:|:----:|
| **P0** | Completion retains target/basis/rationale (substitution exception); engine sees real `lastPrescribed` | ✅ | ✅ | ☐ | ✅ |
| **P1** | Deload transform in writeback + DELOAD path/flag + no-writeback deload completion + materialization sets cut | ✅ | ✅ | ☐ | ✅ |
| **P2** | Web audit: session-detail target-vs-achieved + deload badges + progression-log endpoint & view | ✅ | ✅ | ☐ | ✅ |
| **P3** | Android coaching indication (banner+pill, voice line, notification suffix, enum-compat gate) | ✅ | ✅ | ☐ | ✅ |
| **P4** *(stretch)* | Strength-trend deload annotations; week-review deload visibility | ☐ | ☐ | ☐ | ☐ (deferred — stretch, not in DoD) |

> **Pushed** is unchecked everywhere: the branch is not yet merged to `main`
> (owner decision, DoD-6). All engineering gates are green — evidence in §8.
> Implementation decisions DL-1…DL-12 in `IMPL-DELOAD-01-decision-log.md`;
> note DL-9: the DD-3 compat gate PASSED, so P1 has no app-release ordering
> constraint.

**Sequencing:** P0 first — it is tiny, unblocks everything, and independently
fixes a live engine defect (RC-2). P1 depends on P0 (resumption needs retained
targets). P2 depends on P0 (data) and renders P1's rationale when present.
P3 is independent of P1/P2 after the DD-3 enum lands. Each phase is an
independently shippable PR; merge to main = prod deploy.

---

## 7. Testing approach & verification gates

Backend: Gradle unit suites (existing in-memory fakes). Web: vitest RTL with
hand-computed fixtures (house pattern IL-9). Android: unit tests via the
feature module's test task. Every functional gate asserts exact numbers; the
agent must paste expected-vs-actual into §8 before checking Done.

### P0 — Target retention
- **Technical:** completing a session through
  `WorkoutSessionCompletionService` preserves `targetWeightLbs`/`loadBasis`/
  `rationale` on the saved doc and on the published event; a substituted
  exercise slot has them nulled (DD-1); SKIPPED/un-complete still clears
  actuals.
- **Functional (the RC-2 kill-shot):** end-to-end through the completion
  service (not a hand-built event): planned target 140, athlete logs 190×8 @
  RIR 2 → next planned target **190** (proves the demonstrated-override now
  fires with the real `lastPrescribed`); and `PredictionLog.prescribedLoad ==
  140` (not 190).
- **Gate:** new + full progression/completion suites green with those numeric
  asserts.

### P1 — Real deloads
- **Functional scenario (mirrors the prod incident):** week-3 session
  completes at 40×8,8,8 (band 8-12, increment 5) with week-4 flagged
  `isDeload` and week-5 normal, both PLANNED. Assert: week-4 stamped
  `sets 3→2 (×0.5, min 1)`, `target 40→36→**35** (floorToIncrement(36,5))`,
  `rationale.path=DELOAD`; week-5 stamped **40**. Then complete week-4 at
  35×12 @ RIR 0. Assert: week-5 target **still 40** (no writeback), belief
  e1RM unchanged (sigma may grow), observations carry `ContextFlag.DELOAD`,
  no PredictionLog rows for the deload session.
- **Technical:** `DeloadModifier(0.6, -0.15)` overrides the defaults; sets
  min-clamps to 1; materialization pre-cuts sets on deload sessions.
- **Gate:** the scenario asserts every number above exactly.

### P2 — Web audit
- **Functional (fixtures):** session-detail fixture with `target=40`,
  achieved top set 35, `isDeload=true` → renders the target chip, an
  achieved-vs-target delta, and the Deload badge; non-deload barbell session
  renders no badge. Progression-log fixture (3 rows incl. one DELOAD row and
  one PER_HAND lift) renders path, `target → achieved`, rationale text, and
  doubled totals for the per-hand row.
- **Technical:** endpoint unit test — log rows derived correctly from
  completed sessions (order, top-set selection, nulls for pre-retention
  history rows tolerated).
- **Gate:** `tsc --noEmit` clean + vitest green with the fixture asserts;
  backend endpoint test green.

### P3 — Android indication
- **Technical:** notification-content test asserts the `· deload` suffix;
  header-chip state test; **enum-compat test proving an unknown/`DELOAD`
  rationale path string does not crash DTO mapping** (DD-3 gate — must pass
  BEFORE P1 deploys if Android releases lag).
- **Functional:** session-start announcement string includes the deload line
  when `isDeload`.
- **Gate:** affected Android module unit-test tasks green.

### Cross-cutting
- Full backend suite green every phase; IMPL-PROG-LOAD-01 suites
  (`PrescriptionCalculatorTest`, `SessionLoopTest`) stay green — P0
  *strengthens* them by adding the through-completion variant.

---

## 8. Definition of Done + verification log

**DoD:**
1. **DoD-1 (Deloads are real):** P1 scenario green — a deload week is stamped
   lighter (D1 dose), and completing it cannot move the post-deload targets or
   the belief (D2/D3).
2. **DoD-2 (Targets survive):** P0 gates green; a freshly completed prod
   session shows non-null `targetWeightLbs`+`rationale` (spot-check via
   Firestore REST after deploy).
3. **DoD-3 (Override live):** P0 functional gate green (190-vs-140 through the
   completion service) — closes the RC-2 regression on IMPL-PROG-LOAD-01.
4. **DoD-4 (Web audit):** P2 gates green; owner can answer "what did the
   engine want vs what did I do, and why" for any post-deploy session from the
   web UI alone.
5. **DoD-5 (Coaching indication):** P3 gates green; the next real deload week
   shows the chip + voice line + notification suffix on the phone.
6. **DoD-6 (Shipped):** P0–P3 merged to main, `deploy-*-on-main` checks green.
7. **DoD-7 (Owner acceptance, owner-only):** verified on the next scheduled
   deload week (phase calendar: next is ~4 weeks out — an earlier synthetic
   check on UAT is acceptable).

**Agent protocol:** identical to IMPL-PROG-LOAD-01 §8 — run each gate, paste
command + numeric expected-vs-actual evidence below, only then check §6; DoD-7
is never self-certified.

### Verification log

```
[P0-P3][2026-09-22] gate: full backend suite (regression + all new gates)
cmd: cd backend && ./gradlew test   (JAVA_HOME=~/.sdkman/candidates/java/current)
result: PASS — 951 tests, 0 failures, 0 errors, 19 skipped
evidence: BUILD SUCCESSFUL; aggregated from build/test-results/test/*.xml
```

```
[P0][2026-09-22] gate: target retention + RC-2 kill-shot
cmd: ./gradlew test --tests '*WorkoutSessionCompletionServiceTest' --tests '*CompletionProgressionFlowTest'
result: PASS — 23/23 + 1/1.
  Retention: completed doc keeps target=140.0, basis="engine", rationale.path=KALMAN
  (asserted on the return value AND the repo read); substituted slot → all three null.
  Kill-shot (end-to-end through the completion service): prescribed 140, logged
  190×8,8,8 @ RIR2 → next planned target = 190.0 (NOT the stripped-fallback 147);
  every PredictionLog.prescribedLoad == 140.0 (not 190).
evidence: WorkoutSessionCompletionServiceTest.xml tests="23" failures="0";
  CompletionProgressionFlowTest.xml tests="1" failures="0"
```

```
[P1][2026-09-22] gate: prod-incident replay (real deloads)
cmd: ./gradlew test --tests '*DeloadWeekTest' --tests '*WorkoutScheduleServiceTest'
result: PASS — 2/2 + 6/6.
  Wk3 completes 40×8,8,8 (band 8-12, DB increment 5) →
    wk4 (isDeload) stamped: sets 3→2, target 40→floor(36,5)=35.0, path=DELOAD, dir=DOWN;
    wk5 stamped: target 40.0, 3 sets (trajectory intact).
  Wk4 deload completes 35×12,12 @ RIR0 →
    wk5 STILL 40.0/3 sets (no writeback); belief e1rm + obsCount unchanged;
    both wk4 observations carry ContextFlag.DELOAD; prediction count unchanged.
  Authored DeloadModifier(0.6, −0.20) overrides defaults: target 30.0, sets 2.
  DD-5: materialized deload week pre-cuts MAIN 4→2, clamps 1-set accessory to 1,
  warm-up block untouched; normal week untouched.
evidence: DeloadWeekTest.xml tests="2" failures="0"; WorkoutScheduleServiceTest.xml tests="6" failures="0"
```

```
[P2][2026-09-22] gate: web audit (RTL fixtures, IL-9 pattern) + endpoint
cmd: cd web && npx tsc --noEmit && CI=true npx vitest run;
     cd backend && ./gradlew test --tests '*ProgressionControllerTest'
result: PASS — tsc clean; 122/122 vitest incl. "DELOAD P2 · progression audit surfaces":
  SessionDetail deload fixture → Deload badge + "did 35 vs target 36 (-1 lb)" +
  basis "deload week · resumes 40 lb next week"; normal session → no badge.
  Heatmap 2026-08-30 cell data-deload="true" + "deload" in tooltip; 09-12 false.
  ProgressionLogTable (per-hand ×2): DELOAD row "target 70 (35/hand)" + rationale;
  WARMUP row "target 80 (40/hand)" / "did 80 (40/hand) × 8"; pre-retention row
  degrades to "target —". Endpoint: /log?exerciseId=ohp → 1 row {date 2026-09-21,
  path DELOAD, dir DOWN, target 35.0, top 35.0×12, count 2, isDeload true} (4/4 controller tests).
evidence: vitest "Tests 122 passed (122)"; ProgressionControllerTest.xml tests="4" failures="0"
```

```
[P3][2026-09-22] gate: Android indication + DD-3 compat
cmd: cd android && ./gradlew :core-data:testDebugUnitTest :feature-workouts:testDebugUnitTest :app:testDebugUnitTest
result: PASS — core-data 269, feature-workouts 120, app 50 (439 total, 0 failures).
  Notification: deload draft → title "Push Day · deload" (WorkoutSessionNotificationContentTest, 22/22).
  DD-3 compat: PrescriptionRationaleDto(path="DELOAD").toDomain() passes path through;
  unknown path/direction/confidence → path kept, HOLD/LOW fallbacks, NO crash
  (WorkoutProgramMapperTest, 9/9) → P1 needs no app-release ordering.
  Banner+pill+voice cue (DELOAD_START_CUE) compile-verified in :feature-workouts.
evidence: module test-results XMLs aggregated 439/0/0
```

**Outstanding for DoD:** DoD-2's post-deploy prod spot-check, DoD-6 (merge to
`main` + green deploy checks) and DoD-7 (owner acceptance on the next deload
week) — all owner-gated. Engineering DoD-1…DoD-5 gates are green above.

---

## 9. Risks / open questions

- **DD-3 client compat** is the one ordering hazard: if Android's rationale
  path mapping is a strict enum `valueOf`, shipping `DELOAD` from the backend
  before an app update could crash sync/rendering. The P3 compat test decides
  whether P1 must wait for an app release or can ship immediately.
- **Feeling-driven load cuts vs deload:** `ContextFlag.ROUGH_SESSION` interacts
  with the skip-derivation rule only trivially (deload sessions derive
  nothing), but confirm no other consumer assumes every completion writes a
  prescription.
- **`weekIndexInPhase` drift:** deload identification trusts `isDeload` on the
  scheduled doc; re-activation/re-materialization edge cases should re-stamp it
  (existing behavior — verify, don't change).
- **Old history renders sparsely** in the audit views (targets destroyed
  pre-P0, DD-6) — the UI must degrade gracefully (`—` for missing targets),
  asserted in the P2 fixture test.
