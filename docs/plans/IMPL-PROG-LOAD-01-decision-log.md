# IMPL-PROG-LOAD-01 — Implementation decision log

> Companion to `IMPL-PROG-LOAD-01-per-hand-load-and-realistic-progression.md`.
> Every non-obvious implementation decision made autonomously during the build is
> recorded here for owner review. Decisions are numbered `IL-#`. The owner
> interview decisions (D1–D15) live in the spec; these are the *implementation*
> choices made to realize them.

Legend: ✅ locked & implemented · ⚠️ deviation from spec (needs owner review) ·
🔵 informational.

---

## IL-1 ✅ — `LoadConvention` is NOT a field on the `Exercise` record

**Spec intent (D6/D11):** an explicit per-exercise ×1/×2 load convention,
auto-derived then override-able.

**Decision:** Do **not** add a field to the `Exercise` record. There are 32
`new Exercise(...)` call sites (19 in test files); adding a field there is a
large, error-prone blast radius for a value that is fully derivable. Instead:

1. New enum `core/exercise/LoadConvention { TOTAL, PER_HAND }` with `factor()`.
2. New pure service `core/progression/LoadConventionResolver` that **derives** the
   convention from `Exercise.laterality()` + the exercise's equipment
   category/subcategory (looked up in the Equipment catalog), and honors an
   **override** stored on the existing `ExerciseLoadingProfile`.

**Why acceptable:** the derivation is the source of truth the spec asked for
(Dumbbells+BILATERAL / dual-cable → PER_HAND); the override mechanism still
exists (IL-2). No behavior is lost; the catalog record stays stable.

## IL-2 ✅ — Convention override lives on `ExerciseLoadingProfile`

**Decision:** Add a nullable `loadConventionOverride` to `ExerciseLoadingProfile`
(the existing per-(user,exercise) override store, only 3 construction sites)
rather than a new store. `LoadConventionResolver` checks this override first.

**Consequence:** the override is per-user, not global-catalog. For a
single-primary-user app this is fine and arguably better (a user can correct
their own edge cases). No write UI ships in v1 (that is P5, stretch) — the
mechanism exists at the data/logic layer, satisfying "allow override".

## IL-3 ✅ — Reporting factor is applied at the DTO boundary only; engine stays per-hand

Per spec D8, the progression **engine belief is untouched** (stays in the logged
per-hand space). `loadFactor`/`displayTotal*` are computed only when building
reporting DTOs (`WorkoutStatsService`, `ProgressionController.strength`). No data
migration.

## IL-4 ✅ — Dual-cable detection rule

An equipment counts as **dual-cable** (→ PER_HAND) when its `specSchema == CABLE`
**and** its `subcategory` (case-insensitive) contains "dual" or "multi" /
"functional", **or** its name contains "functional trainer"/"dual". Single-stack
cables (subcategory "Single Cable", lat pulldown, etc.) stay TOTAL. If the
catalog doesn't tag cables distinctly, cables default to TOTAL (safe; D5).

## IL-5 ✅ — Dumbbell detection rule

An exercise is **per-hand dumbbell** when any required-equipment item has
subcategory containing "dumbbell" (or `specSchema == WEIGHT_SET` with a
dumbbell-ish name) **and** `Exercise.laterality() == BILATERAL`. UNILATERAL
(single-arm) → TOTAL. This is the documented D6 derivation; single-implement
BILATERAL moves (goblet squat) are the known edge, correctable via the IL-2
override.

## IL-6 ✅ — Real-increment resolution priority (D7/D12)

`LoadingProfileResolver.resolve(userId, exerciseId, locationId)`:
1. Stored `ExerciseLoadingProfile` override (its `loadIncrementLbs`) — wins.
2. The session's `Location` equipment specs for the exercise's required
   equipment:
   - `WEIGHT_SET` (dumbbells): `increment` spec key; else derived from
     `minWeight`/`maxWeight`/`weights`. This is the per-hand step (matches logged
     space).
   - `PLATE_LOADED` (barbell): `2 × smallest available plate` from
     `availablePlates`/`plates`/`weights`; else default.
   - `SELECTORIZED`/`CABLE`: `increment` spec key; else default.
3. Fallback (D12): the existing name-based defaults (DB 5 / machine·cable 10 /
   barbell 5 / default 5). Spec-map parsing is fully defensive: any missing or
   malformed key falls through to the fallback.

## IL-7 ✅ — Speculative one-increment cap replaces the flat +5% `JUMP_CAP` (D12/D13/D14)

In `PrescriptionCalculator.calculate` (the Kalman/live path):
- Cap basis changes from `lastPrescribedLoad` to `max(lastPrescribedLoad,
  lastPerformedLoad)`. The speculative ceiling is `capBasis + incrementLbs` — at
  most **one real increment** past the higher of prescribed/performed.
- **Demonstrated floor:** a new `outperformed` flag (computed in `SessionLoop`:
  `workingLoad > lastPrescribed` AND `effectiveRir >= OUTPERFORM_RIR_MIN`) floors
  the raw load to `floorToIncrement(lastPerformedLoad, increment)` — "match what
  you lifted, no add" (D14). Because the floor uses the *demonstrated* load, a
  multi-increment jump is allowed when it is grounded in a real set (D13).
- The old `JUMP_CAP = 1.05` constant is retained but no longer applied (kept to
  avoid breaking any external references; the +5% behavior is gone).

## IL-8 ✅ — `OUTPERFORM_RIR_MIN = 1.0`

The demonstrated-floor triggers only when the lifter left **≥1 rep in reserve**
on the last working set. RIR 0 (grinding) is not "outperformance with reserve",
so it does not force the target up (D14/D15 up-only). Tunable; flagged for owner.

## IL-9 ✅ — Warm-up / cold-start path already honors demonstrated load

`DoubleProgression.next(...)` keys off `workingLoad` (the actual load) as its
base, so during warm-up/cold-start a heavier-than-prescribed session already
carries forward (holds at the demonstrated load, or +increment if all sets hit
top). No change needed there; the D13/D14 fix is scoped to the Kalman path only.

## IL-10 ✅ — Tonnage doubling is computed inside the cached scan

`WorkoutStatsService.scan` resolves a per-exercise `loadFactor` map once and
multiplies each set's `weight × reps × factor` into session tonnage, so weekly
volume reflects total load (D10). The factor is baked into the ~5-min cache;
convention changes take effect on the next cache refresh. Acceptable given the
short TTL.

## IL-11 ✅ — Web display: total primary, per-hand secondary (D9)

Backend sends `loadFactor` + pre-doubled `*TotalLbs`; web renders the total as
the primary number and, when `loadFactor === 2`, appends a per-hand secondary
(e.g. `120 lb total · 60/hand`). A single formatter `perHandLabel()` in
`web/lib/per-hand.ts` centralizes this. Existing lb-only rendering is preserved
for TOTAL lifts (factor 1).

## IL-12 ✅ — Two constructors on `LoadingProfileResolver` need `@Autowired`

Adding the equipment/location deps gave `LoadingProfileResolver` two public
constructors (5-arg primary + 3-arg back-compat for tests). Spring can't choose
between them and failed to build the context (166 context-load test failures on
the first full run). Fixed by annotating the 5-arg constructor `@Autowired`.
Caught and resolved by running the full backend suite. 🔵 Note: multi-constructor
beans always need this.

## IL-13 ⚠️ — P5 (override editing UI) deferred

Per the spec, P5 is a stretch phase and not part of DoD-6. The override
*mechanism* is fully implemented and persisted
(`ExerciseLoadingProfile.loadConventionOverride`, Firestore read/write, resolver
honors it, tested in `LoadConventionResolverTest.explicitOverrideBeatsDerivation`),
so the owner can correct the goblet-squat-style edge cases via a data write today.
Only the user-facing editing screen is out of scope. Flagged for owner: decide
whether a settings UI is wanted.

## IL-14 🔵 — Android is unaffected (additive DTO fields only)

All backend changes to reporting DTOs (`ExerciseStrengthDto`, `PrPoint`,
`E1rmHistory`/`Point`) are strictly additive JSON fields on `/api/me/...`
endpoints the web consumes; no existing field changed type or meaning. Android
deserializers ignore unknown fields, and `ExerciseLoadingProfile` is
backend-internal (not synced). So the D2 "no Android changes" scope holds with no
risk of breaking the phone client.

## IL-15 🔵 — Web dependencies were not installed in the worktree

`web/node_modules` had only 21 entries; `pnpm install` (with `CI=true` for the
no-TTY sandbox) was required before typecheck/tests could run. No code impact;
recorded so a reviewer re-running the gates knows to install first.

## IL-16 ⚠️ — `OUTPERFORM_RIR_MIN` and other tunables to review

Values chosen pragmatically, all easy to change and flagged for owner review:
- `OUTPERFORM_RIR_MIN = 1.0` (IL-8) — reps-in-reserve threshold for "outperformed".
- Speculative ceiling = `max(lastPrescribed, lastPerformed) + 1 increment` (IL-7).
- Name-based increment fallbacks unchanged (DB 5 / machine·cable 10 / barbell 5).
- Dumbbell "increment" spec key is read as **per-hand** (matches logged space).
