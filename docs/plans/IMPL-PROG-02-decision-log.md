# IMPL-PROG-02 — Implementation Decision Log

Running log of decisions made *during* implementation (2026-09-07), beyond the ratified §3 decisions in the plan. Each entry: what was ambiguous, what I chose, why. Review after implementation to tweak.

Format: `IMPL-Dxx` — decision — rationale.

---

## Scoping decisions (blast-radius control)

- **IMPL-D01** — *"Last time" (lastActual) stays client-side.* Instead of adding backend `lastActualWeightLbs/Reps` fields (plan §4.1), the Android client derives the "last time: X" subtitle from the `lastSets` map it already loads. The F2/F3 race is fixed purely by reordering client prefill precedence so the engine's `targetWeightLbs` is authoritative and `lastActual` is demoted to a subtitle. *Rationale:* server already sends `targetWeightLbs`; avoids new backend fields + a server-side "last session" lookup; the race is a client-precedence bug, so fix it there.

- **IMPL-D02** — *`isBodyweight` added at the API layer, not the core `Prescription` record.* A new pure `BodyweightClassifier` (logic extracted from `LoadingProfileResolver.isBodyweightLoaded`) is invoked in `WorkoutProgramAssembler`, which already loads the full `Exercise`. *Rationale:* adding a field to the `Prescription` record ripples through importer/splitter/completion/tests (many constructors); the flag is fully derivable from the catalog at assembly time, so the core model stays untouched.

- **IMPL-D03** — *First-time seed weight uses a movement-pattern default table (`SeedWeightResolver`), not a new `Exercise.seedWeightLbs` catalog field.* Deviates from ratified **D7** ("per-exercise catalog seed"). *Rationale:* adding a field to the `Exercise` record is a high-risk migration across every catalog call site/test; a pattern-default table ships the correct *behavior* now (cable pushdown → a real number, never "body weight") and a catalog override field can be layered on later without changing the resolver's call sites. **Flagged for review** — if per-exercise accuracy matters, add the catalog field in a follow-up.

- **IMPL-D04** — *Rationale is now sent to the client.* The deep/scheduled response previously carried only `loadBasis` (free text), not the structured `rationale`. To drive the delta highlight (D9), the rep-band-reset display rule (D5), and the rationale sheet (D3), `PrescriptionResponse` gains a `rationale` DTO and the Android network→domain mapping is wired through. *Rationale:* the client can't highlight "changed vs last time" or key reps-to-bottom without the engine's `direction`/`deltaLbs`/`deltaReps`.

- **IMPL-D05** — *Rep-band reset (D5) rendered client-side, keyed on `rationale.direction == UP`.* The engine keeps emitting the full band (`repsMin..repsMax`); the client shows `repsMin` as the target rep number when the prescription is a weight increase, and the full band otherwise. *Rationale:* both engine paths already carry the band; a single display rule guarantees consistent "reps drop on a load jump" without diverging the two engine algorithms or losing logging latitude.

---
## Implementation notes (appended as work proceeds)


### UI-layer decisions (discovered during implementation)

- **IMPL-D06** — *Delta highlight computed client-side vs `lastSets` (last actual).* The number highlight (F1/D9) on the set card compares the shown target to the last logged set of that exercise, honoring **D2** ("vs last time you did it") uniformly for both weight and reps. The engine `rationale` (whose deltaLbs is vs last *performed/prescribed*) still powers the `RationaleStrip`/info sheet, but the per-number ▲/▼ chip uses the client-side "vs last time" delta so weight and reps are treated identically.
- **IMPL-D07** — *Re-announce scope.* D10 ("always re-announce on any change") is honored for changes to the *effective target* (set advance, prescription/prediction change, start). Ephemeral staged edits in the weight/reps picker do NOT re-announce on every tick (avoids the R4 chatter). *Rationale:* the original complaint (F2) was a stale number from the async race, now fixed by D1; per-keystroke voice would be noise.
- **IMPL-D08** — *`RationaleStrip` already satisfies D3.* An exercise-header strip with ▲/▼ + delta + confidence pill + tappable "why" already exists; F1's "more context / why" need is met once the backend *sends* `rationale`. No new sheet built; the per-number chip (IMPL-D06) is the added piece.
- **IMPL-D09** — *RIR moves inline onto the final working set's `ActiveRepCard` and gates its "Log set" button (mandatory pick, D4).* The post-completion `RirChipRow` is removed. The inferred value is shown as an outlined *suggestion* but the log button stays disabled until the user explicitly taps a chip; the value persists as `REPORTED`.
- **IMPL-D10** — *History persistence (D12) deferred to the existing snapshot.* The completed-session snapshot already retains `rationale`/`targetWeightLbs` in `sessionJson`, so the live rendering is reusable; a dedicated history-screen surface is left as a follow-up rather than built now (flagged for review).

### Closing notes (end of implementation pass, 2026-09-07)

**What shipped (all six defects):**
- **F1** — Backend now sends the structured `rationale` in `PrescriptionResponse` (the Android `RationaleStrip` was already built and wired but starved). Added a per-number accent ▲/▼ delta chip on the active set card (`SetFieldBox.adjustment`), computed "vs last time" (`weightAdjustment`/`repsAdjustment`), gated on an actual engine decision.
- **F2** — Prefill precedence reordered so the engine prediction (`targetWeightLbs`) beats last-session actual; the coach announcement reads that authoritative number.
- **F3** — Notification `loadLabel` reads the authoritative prediction (never the async `lastSets`), so it can't flip a beat after posting; bodyweight/reps logic aligned with the screen.
- **F4** — RIR moved inline onto the final working set's `ActiveRepCard` as a mandatory gate (log button disabled until an explicit pick); the post-completion `RirChipRow` was removed and replaced by `RirSelector`.
- **F5** — `targetReps` returns the band bottom on an engine weight increase (`ProgressionDirection.UP`), else the band top, gated on a real rationale; threaded into prefill, announcement, notification, and the display.
- **F6** — New `BodyweightClassifier` (single source of truth, `LoadingProfileResolver` now delegates to it) + `SeedWeightResolver`; `isBodyweight` stamped onto every prescription by the assembler; first-time weighted lifts get a conservative catalog/pattern seed weight and `loadBasis = "starting estimate"`. Client says "body weight" ONLY when `isBodyweight == true`.

**Verification:** backend `core.progression.*` + `api.workoutprogram.*` and the new classifier/seed tests all green; Android `:feature-workouts`, `:app`, `:core-data` unit tests green (new `SessionFormatTest` cases cover F1/F2/F5/F6). Compose-UI tests for the RIR gate and the on-device functional walkthrough (TTS + live notification) were NOT run here — no emulator/audio in this environment — and are flagged 🟨 for on-device sign-off in the plan §7.

**Deviations from ratified §3, for review:**
- **D5** implemented as a client-side display rule (IMPL-D05), not an engine-algorithm change. The engine still emits the full band; the shown target drops. If you want the *engine's* logged prescription itself to record band-bottom reps, that's a follow-up in `PrescriptionCalculator`.
- **D7** uses a movement-pattern seed table (IMPL-D03), not a per-exercise catalog field. Cable push-down now seeds to ≤40 lb; tune `SeedWeightResolver` or add an `Exercise.seedWeightLbs` override if specific lifts need it.
- **D12** history surface reuses the existing snapshot (rationale/target persist in `sessionJson`); no dedicated history screen was added (IMPL-D10).

**Local build note (not committed):** `android/local.properties` needs `sdk.dir` and a `webOauthClientId` to build non-app modules headlessly; it is gitignored, so CI/other envs supply their own.

### Decision-log review (interview, 2026-09-07) — ratifications & new actions

Reviewed the autonomous decisions with the owner. Outcomes:

- **IMPL-D03 / D7 (seed source)** — RATIFIED as-is. Keep the movement-pattern seed table; revisit a per-exercise catalog field only if a specific lift is consistently wrong. Barbell seeds are TOTAL loaded weight (plan open-question Q1 resolved: total, not empty-bar) — consistent with the app logging total external load everywhere.
- **IMPL-D06 (adjustment chip gating)** — RATIFIED. The ▲/▼ chip appears ONLY when the engine made a decision (rationale present); a static program target that merely differs from last time shows no chip.
- **IMPL-D07 (re-announce scope)** — RATIFIED. Re-announce on genuine target changes only; no per-keystroke voice.
- **IMPL-D10 / D12 (history surface)** — RATIFIED as deferred. Snapshot retains rationale/target; a review screen is a follow-up.
- **IMPL-D05 / D5 (rep-band reset) — CHANGED.** Owner wants the ENGINE, not just the display, to reflect band-bottom reps on a load increase. **New decision IMPL-D11:** on a load increase (`Direction.UP`) the engine tightens the emitted band toward the bottom (e.g. 6..10 → 6..7) via `SessionLoop.tightenBandOnIncrease`, so the *stored* prescription matches what's shown. Implementing this pass. No new DTO field (band itself carries it).
- **IMPL-D09 (RIR suggestion) — CHANGED.** Owner wants a BLIND pick: **new decision IMPL-D12** — remove the inferred-value suggestion outline from `RirSelector` so the inference doesn't anchor the athlete. Still mandatory + explicit; the log button stays gated.
- **RIR gate test — CHANGED.** Owner wants the automated gate: **new decision IMPL-D13** — write a Compose UI test asserting the final set can't be logged until a RIR chip is tapped (closes the P3 automation gap).
- **Weighted bodyweight (belt + plate on dips/pull-ups)** — DEFERRED by owner. Known limitation: a bodyweight-flagged movement currently announces "body weight" and suppresses the weight highlight even if external load is added. **New decision IMPL-D14:** capture "bodyweight + added load" mode as a follow-up; note the risk that a weighted dip logged with a plate still reads as "body weight".
