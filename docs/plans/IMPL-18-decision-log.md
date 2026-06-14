# IMPL-18 — History-grounded conversational program designer: implementation decision log

> **Review outcome (2026-06-14):** all decisions below reviewed with the user and
> ratified as implemented. Eight reviewed explicitly: **D4** (keep `loadBasis` as a
> dedicated field), **D6** (two-tier issues-block / warnings-advisory as built),
> **D7** (weight>e1RM hard block with 5% slack), **D8** (accept the MEV/MAV/MRV
> defaults — they only warn), TRT danger thresholds (accepted as encoded), the
> curated TRT KB (accept the seeded Endocrine-Society/AUA KB as owner-maintained),
> the ~20-exercise per-turn digest budget (kept), and the Android `warnings`
> parity gap → **closed now** (Android proposal card renders the advisory
> warnings; hard issues now red/alert, soft warnings amber/warn, matching web).
> No further changes requested.

Working log of decisions made while implementing
[IMPL-18](../specs/IMPL-18-conversational-program-designer.md),
[ADR-0014](../decisions/ADR-0014-designer-medical-context-scope.md), and
[ADR-0015](../decisions/ADR-0015-trt-decision-support.md). Each entry records the
question, the decision taken so work could proceed, and the rationale. Items
marked **OPEN** want a human call after merge.

## Orchestrator decisions (made up front, before agent fan-out)

### D0 — Specs lived only on `feature/workout_llm`; no implementation existed
**Question:** The request named "IMPL-18 and ADR-15" but neither the spec nor the
ADRs existed on `main`/`origin/main` — only on the unmerged `feature/workout_llm`
branch (docs only, no code). ADR numbering on main stopped at ADR-0013.
**Decision:** Branched `feat/IMPL-18-conversational-program-designer` from
`origin/main` and brought the three doc files (IMPL-18 spec, ADR-0014, ADR-0015)
over via `git checkout feature/workout_llm -- <docs>`. Implementing against those
specs verbatim. ADR-0014 is created in superseded state (as written) with ADR-0015
as the accepted, active policy.
**Rationale:** The specs are detailed and self-consistent; they are the contract.

### D1 — Build order: backend contract first, then web + Android in parallel
**Decision:** Implement the backend (data model → digest → tools → prompt →
validator → TRT) as one coordinated effort since the files interrelate, then fan
out web and Android (independent directories, no file overlap) concurrently
against the now-fixed wire contract.
**Rationale:** Avoids parallel agents colliding on shared backend files
(Prescription, the Gemini client, the validator, Firestore mappers) while still
parallelizing the genuinely independent client work.

### D2 — Additive record fields via convenience constructors
**Question:** `Prescription`, `ProgramPhase`, `WorkoutProgram` are constructed in
~25 places (services, importer, splitter, Firestore mapper, tests). Adding fields
to a Java record changes the canonical constructor and breaks every call site.
**Decision:** Append the new components at the END of each record and add a
secondary constructor with the OLD signature that delegates with the new fields
= null. Only the call sites that genuinely produce the new data (Gemini parse,
Firestore read, assembler, `WorkoutProgramService` normalization, chat controller
re-stamp) are updated to set them; everything else (tests, importer, splitter,
completion service) compiles unchanged through the convenience constructor.
**Rationale:** Minimal, low-risk blast radius for a purely additive change; keeps
imported/old data valid (spec "Data model changes: additive, nullable").

### D3 — `NutritionGuidance` shape (locked, exact wire contract)
`NutritionGuidance(Integer kcal, Integer proteinG, Integer carbsG, Integer fatG,
String note)` — per the IMPL-18 spec verbatim. Lives on `ProgramPhase`
(per-phase) with a program-level fallback on `WorkoutProgram`. All fields
nullable. JSON keys: `kcal`, `proteinG`, `carbsG`, `fatG`, `note`. Web + Android
DTOs mirror this exactly.

### D4 — `Prescription` gains `targetWeightLbs` AND `loadBasis`
**Question:** Spec adds `targetWeightLbs: Double?`. Interview R6 also wants a
"why" affordance per set revealing the history basis (e1RM, last done, ramp
discount). The data-model section only lists `targetWeightLbs`.
**Decision:** Add two additive nullable fields to `Prescription`:
`targetWeightLbs: Double` (the prescribed load) and `loadBasis: String` (a short
model-written one-line rationale, e.g. "e1RM 205 from 185×5 ~8wk ago, −10%
ease-in"). The validator's weight-sanity check reads `targetWeightLbs`; clients
render `loadBasis` on tap (R6).
**Rationale:** Realizes the "why" affordance honestly and minimally without
overloading `notes` or inventing a structured basis object. Still additive/nullable.

## Wire contract for client agents (web + Android code against this)

Deep-response additions (`WorkoutProgramDeepResponse`):
- `PrescriptionResponse.targetWeightLbs: number|null`
- `PrescriptionResponse.loadBasis: string|null`
- `PhaseResponse.nutritionGuidance: NutritionGuidance|null`
- top-level `WorkoutProgramDeepResponse.nutritionGuidance: NutritionGuidance|null` (program-level fallback)
- `NutritionGuidance = { kcal:number|null, proteinG:number|null, carbsG:number|null, fatG:number|null, note:string|null }`

Commit request (`CreateProgramRequest`) accepts the same new fields on its
prescription/phase/program shapes (round-trips edits).

TRT/labs surface (new endpoint): `GET /api/me/workout-programs/chat/trt-context`
→ `TrtContext { onTrt: boolean, markers: TrtMarker[], dangerFlags: DangerFlag[] }`
where `TrtMarker { name, label, value, unit, refLow, refHigh, sampleDate, trend, status }`
(`trend` ∈ RISING|FALLING|STABLE|UNKNOWN, `status` ∈ LOW|IN_RANGE|HIGH|WATCH|UNKNOWN)
and `DangerFlag { marker, severity (WARNING|DANGER), message }`. The core `TrtContext`
record is serialized directly (no separate api DTO).

## Backend wiring decisions (made while building the shared spine)

### D5 — Agentic tool loop for the read-only data tools
**Question:** The IMPL-15 `streamChat` did a single streaming pass and only
captured the terminal `propose_workout_program` call; it never fed tool results
back. IMPL-18 needs `get_exercise_history` / `get_lab_history` to round-trip.
**Decision:** Restructured `GeminiWorkoutProgramChatClient.streamChat` into a
bounded agentic loop (`MAX_TOOL_ROUNDS = 6`): each round streams text to the
user, and on a data-tool call it appends the model's function-call turn + a
function-response turn (resolved via a new `ToolResolver` the controller binds to
the user's digest/TRT services) and re-invokes. `propose_workout_program` stays
terminal. Added `ToolResolver` to the `WorkoutProgramChatClient` interface (5th
param); updated the two test/UAT fakes.
**Rationale:** Minimal, self-contained change that realizes S2's "drill-in tool"
without a parallel client; the cap prevents runaway loops.

### D6 — Warnings are additive, not blocking (honoring R1 without breaking the contract)
**Question:** R1 wants "block only impossible; warn-and-override for volume-past-
MRV / missing deload / fast ramp." The commit path blocks on any non-empty
`issues` list, and the locked SSE payload is `{program, issues}`.
**Decision:** `validate()` keeps returning hard ERRORS only (now incl.
weight > e1RM) — these still block commit. Added `validator.warnings()` for the
soft advisories and an ADDITIVE `warnings: string[]` field on the proposal SSE
payload. Commit is unaffected (blocks on errors only). Clients may render
`warnings`; ignoring it is safe (web renders issues today; warnings is a
fast-follow if not yet shown).
**Rationale:** Satisfies R1's two-tier severity while keeping `issues` semantics
and the client contract additive.

### D7 — Weight-sanity uses a 5% e1RM slack
A prescribed `targetWeightLbs` is a hard error only when it exceeds the digest
e1RM × 1.05, so floating-point e1RM (Epley) doesn't reject a legitimate top set
equal to the estimate.

### D8 — Volume landmarks live in code (`TrainingScienceScaffold`)
One `@Component` holds the MEV/MAV/MRV table + ramp/deload constants and renders
the prompt scaffold; the validator reads the same table. Muscle names matched via
a synonym-folding `normalize()` against `Exercise.primaryMuscles` (plain strings,
IMPL-14). Numbers are middle-of-the-road RP-style defaults — tune freely; they
only warn (D6). **OPEN:** the exact MEV/MAV/MRV numbers and the synonym coverage
are tuning, not load-bearing — worth a coach review.

### D9 — TRT agent added a few extra brand synonyms
The TRT subagent added `xyosted`, `aveed`, `depo-testosterone` to the on-TRT
name match beyond the spec's listed synonyms. Kept — they are real testosterone
products; harmless to detection.

</content>
</invoke>
<invoke name="Read">
<parameter name="file_path">/Users/gte619n/Development/tesseta/backend/src/main/java/com/gte619n/healthfitness/core/workoutprogram/Prescription.java