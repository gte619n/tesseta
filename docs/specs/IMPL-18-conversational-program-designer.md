# IMPL-18: History-Grounded Conversational Program Designer

> **Status (2026-06-14): Implemented.** Backend (data model, digest service,
> agentic `get_exercise_history` / `get_lab_history` tools, science-scaffold +
> validator guardrails, TRT decision-support + KB + danger flags, `trt-context`
> endpoint), web (weights + "why" + per-phase nutrition strip + TRT/labs panel),
> and Android (IMPL-AND-18 designer chat) all landed on
> `feat/IMPL-18-conversational-program-designer`. Implementation decisions and a
> few tuning OPENs are in
> [`docs/plans/IMPL-18-decision-log.md`](../plans/IMPL-18-decision-log.md).

## Goal of this spec

Turn the existing IMPL-15 program-designer chat into a **history-grounded,
science-informed conversational coach** that designs (and iteratively refines)
a periodized program from a natural-language brief like:

> *"I need a program for five weeks, three days per week for the first two
> weeks then four days per week. I'm getting back into lifting and want to ease
> in. I'm restarting TRT. Design it around my home gym."*

It already knows the user's health snapshot (DEXA, blood, vitals, **meds — so
TRT shows**) and constrains every exercise to the chosen gym's equipment
(IMPL-14/15). This spec adds the four things it lacks:

1. **Workout-history grounding** — read what the user *actually lifted*
   (IMPL-17 logged sets + the ADR-0008 imported history) and prescribe concrete
   weights/sets/reps from it, discounted for layoff/ease-in.
2. **Curated training science** — periodization, per-muscle volume landmarks
   (MEV/MAV/MRV), frequency, deload cadence, ramp-rate limits — baked into the
   prompt and enforced by the validator.
3. **Performance-science context incl. TRT, plus grounded TRT
   decision-support** — lean into how the user's hormonal/recovery status
   informs programming, **and** give grounded dosing/protocol/bloodwork/
   side-effect guidance for the user's own TRT (folded into this same chat). See
   [ADR-0015](../decisions/ADR-0015-trt-decision-support.md), which supersedes
   ADR-0014.
4. **Nutrition targets alongside the plan** — per-phase calorie/macro guidance
   for the goal, written with the program (does **not** read/write the
   nutrition module — [IMPL-16] stays the source of truth for actual logging).

This is an **enhancement of IMPL-15 in place** (reuses the chat loop, SSE
framing, proposal card, commit path, equipment constraint). Conversational
refinement targets the **draft before commit** first; conversationally editing
an already-active program is a follow-up (IMPL-18b). The chat ships on **web
and Android together** ([IMPL-AND-18](#android--impl-and-18)); the backend
contract is client-agnostic.

## Decisions locked (from the design interview)

| # | Decision | Choice |
|---|----------|--------|
| S1 | Architecture | **Enhance IMPL-15 designer in place** — no parallel/rebuilt designer |
| S2 | History access | **Per-exercise digest injected each turn + a `get_exercise_history` tool** the model can call to drill in |
| S3 | Nutrition | **Output per-phase calorie/macro targets alongside the plan**; do not couple to the nutrition module's stored targets |
| S4 | Conversational scope | **Draft-refine first** (multi-turn design before commit); live/active-program editing is IMPL-18b |
| S5 | Load prescription | **Absolute weights from history (e1RM, layoff-discounted); RPE/target-rep fallback** for un-logged exercises |
| S6 | Medical/TRT | **Lean into performance science** AND give **grounded TRT decision-support** (dosing, protocol, bloodwork, side-effects) — ADR-0015 supersedes ADR-0014 |
| S6a | TRT placement | **Folded into the workout-designer chat** (one surface, not a separate advisor) |
| S6b | TRT framing | **Grounded, specific decision-support** — concrete numbers/titration tied to the user's labs + cited KB; not a black box, not deferral-only |
| S6c | TRT grounding | **User's labs + a curated, cited guideline KB**; every clinical claim cites its source |
| S6d | TRT scope | **TRT + monitoring panel** — testosterone protocol/titration, estradiol, hematocrit/Hgb, lipids, PSA, side-effect surveillance |
| S6e | TRT safety rail | **Danger-flag alerts** (e.g. hematocrit > 54%, PSA rise) — mandatory hard alerts urging clinician contact |
| S7 | Clients | **Web + Android in this effort**; backend contract client-agnostic |
| S8 | Science encoding | **Curated scaffolds in the prompt + hard guardrails in the validator** (volume landmarks, frequency, deload cadence, ramp-rate) |
| Model | Gemini | `gemini-3.1-pro-preview` (unchanged, per ADR-0013) — no new model, no model ADR |

## Concepts

**Exercise performance digest** — a compact, per-exercise rollup of what the
user has actually done: last-performed date (→ *staleness*), best recent set
(weight×reps), **estimated 1RM** (Epley: `w·(1+reps/30)`), typical working-set
RPE, the rep/set ranges used, and a trailing-4-week volume trend. Computed from
every `COMPLETED` `ScheduledWorkout.loggedSets` across all of the user's
programs **including** `imported-history` (ADR-0008). Imported rows are
weight-only (reps null) → e1RM falls back to the top weight with a low-confidence
flag.

**Staleness / ease-in discount** — weeks since the exercise (or the user) last
trained, bucketed (e.g. <2w none, 2–6w −10%, 6–12w −20%, >12w −30% + start
sub-maximal). The chat intent ("ease in") compounds the discount and caps early
ramp rate. Surfaced to the model in the digest and enforced by the ramp-rate
guardrail.

**Volume landmarks** — per-muscle weekly hard-set ranges (MEV/MAV/MRV). The
designer keeps planned weekly sets per primary muscle within MEV→MAV for accumulation
phases and flags MRV breaches. Muscle mapping comes from `Exercise.primaryMuscles`
(IMPL-14).

**Per-phase nutrition guidance** — a non-binding calorie/macro recommendation
attached to each phase (e.g. accumulation = slight surplus, deload = maintenance),
with a one-line rationale. Display-only; the user still logs food in the
nutrition module.

## Data model changes

Additive, nullable — imported/old programs stay valid.

- **`Prescription.targetWeightLbs: Double?`** (backend record + both client
  DTOs). The concrete prescribed load when history supports it; null → fall back
  to `intensity` (RPE/%1RM) as today. *(Alternative considered: a new
  `IntensityKind.ABSOLUTE`; rejected — a dedicated nullable field is clearer for
  clients and doesn't overload the intensity union.)*
- **`ProgramPhase.nutritionGuidance: NutritionGuidance?`** where
  `NutritionGuidance(kcal: Int?, proteinG: Int?, carbsG: Int?, fatG: Int?, note: String?)`.
  Program-level fallback `WorkoutProgram.nutritionGuidance` for plans without
  phase variation.
- No change to `LoggedSet` (IMPL-17 already carries weight/reps/rpe/rest/completedAt).

## Backend — Spring

### New: `ExercisePerformanceDigestService` (core)
Pure/testable. `digest(userId, exerciseIds): Map<String, ExerciseDigest>` and
`digestAll(userId): ...` scanning `ScheduledWorkoutRepository` COMPLETED
sessions (all programs incl. archived/imported — reuse the
`findByUserIncludingArchived` seam from IMPL-17) → per-exercise rollup above.
Cached per-user (short TTL, invalidated on session completion via the existing
`MetricChangedEvent`/completion path). Bounded: digest only exercises executable
at the chosen gyms ∪ recently trained, to cap tokens.

### Designer context (extend `WorkoutProgramChatController` context builder)
Append after the health snapshot + gym allow-list:
- the **digest** for the candidate exercise set (staleness, e1RM, RPE, trend),
- the user's **training age / recent adherence** summary (from aggregates),
- the **science scaffold** preamble (landmarks, ramp rules, deload cadence),
- TRT/recovery framing per ADR-0014.

### New Gemini tool: `get_exercise_history`
`get_exercise_history(exerciseId?, exerciseName?, limit=5)` → recent sessions'
logged sets (date, weight×reps×rpe). Registered alongside
`propose_workout_program` in `GeminiWorkoutProgramChatClient`; the loop already
handles tool calls mid-stream — add a second tool handler that resolves against
the digest service and feeds results back into the turn.

### Prompt (extend the IMPL-15 system prompt)
Add: history-grounded load selection (use e1RM × target-%; respect
staleness/ease-in), volume-landmark adherence, deload rules, TRT-aware recovery
framing, the nutrition-guidance output contract, and an explicit **refusal rule
for dosing/medical questions** (ADR-0014).

### Validator guardrails (extend `WorkoutProgramValidator`)
Beyond the existing equipment + structure checks, add (as errors vs. warnings —
TBD per open-Q):
- weekly hard-sets per primary muscle within MEV..MRV,
- session/weekly frequency sanity per muscle,
- deload present when a phase ≥ N weeks,
- **ramp-rate cap** (week-over-week volume/intensity increase ≤ X%, tighter when
  ease-in/high-staleness),
- `targetWeightLbs` sanity vs. e1RM (no prescription above ~e1RM without RPE).
Issues stream back inline exactly like today's equipment violations.

### Endpoints
Reuse `/api/workout-programs/chat` (SSE) and `/commit`. The proposal payload
gains `targetWeightLbs` + `nutritionGuidance` (additive to
`WorkoutProgramDeepResponse`). No new top-level endpoints for draft-refine.

## Web UI — Next.js

Reuse `WorkoutProgramChat.tsx` / `WorkoutProgramProposalCard.tsx` /
`chat-stream.ts`. Changes:
- Proposal card shows **prescribed weight** (or RPE fallback) per set and a
  small **"why" affordance** surfacing the history basis (e1RM, last done, ramp).
- A **per-phase nutrition strip** (kcal/macros + rationale), display-only.
- Setup form unchanged (training days, gym per day, goal) — already collects
  what the brief needs; the rest is conversational.

## Android — IMPL-AND-18

Net-new conversational surface in `feature-workouts` (today read-only,
IMPL-AND-15). Reuses `core-data/net/Sse.kt` (the POST SSE consumer) and the
existing chat patterns from `feature-goals`.
- **`WorkoutProgramChatClient`** (core-data) → POST `/chat`, Flow<SseEvent>,
  mapping token/proposal/error/done (mirror `lib/chat-stream.ts`).
- **Chat screen + editable proposal card** (Compose) with the setup form
  (training days, gym per day, goal), streaming tokens, the proposal with
  weights + nutrition strip, edit + **commit**.
- **Commit** goes through the normal program-create path; chat itself is online
  (SSE), so no outbox for the conversation, but the resulting program is read by
  the existing mirror/sync once created.
- Moshi DTOs gain `targetWeightLbs` + `nutritionGuidance` (additive).

## TRT decision-support (folded into the designer)

Grounded medical decision-support for the user's own TRT, in the same chat
(S6a). Governed by [ADR-0015](../decisions/ADR-0015-trt-decision-support.md).

### Curated guideline KB (new artifact)
A version-controlled, citable knowledge base distilled from authoritative
clinical guidelines (e.g. Endocrine Society TRT guidance): testosterone
titration logic, target/reference ranges, side-effect profiles, and the
monitoring panel (estradiol, hematocrit/Hgb, lipids, PSA). Lives in the repo,
reviewed like code; each entry carries a source citation. This is the quality
ceiling of the feature and an ongoing maintenance artifact.

### Backend
- **`TrtAdvisorContextService`** (or fold into the designer context builder):
  assembles the user's relevant labs (latest + trend for total/free-T,
  estradiol, hematocrit/Hgb, lipids, PSA) from `BloodTestReport`/readings, the
  current TRT med/dose, and the matching KB entries — injected into the designer
  turn alongside the training context.
- **`get_lab_history` tool** (sibling to `get_exercise_history`): lets the model
  pull a specific marker's history (value, date, reference range) on demand.
- **Danger-flag rule set** (S6e): a small, explicit, testable table of
  risk thresholds (e.g. hematocrit > 54%, PSA delta) evaluated each turn; a
  breach raises a hard alert in the response regardless of the question asked.
- **Citations**: clinical claims carry a KB source ref, surfaced in the response.

### Prompt
Reverses the ADR-0014 refusal. New framing: give specific, grounded dosing/
protocol/bloodwork/side-effect guidance for TRT, citing the KB and the user's
labs; show titration reasoning; never assert un-grounded specifics; always
surface danger-flags. No clinician-deferral wall — the user acts on the guidance
with their prescriber, and the data + citations make it verifiable.

### Clients
- **Web**: a TRT/labs panel in the chat (current markers vs. range, trend,
  danger-flag banner) and inline cited guidance in the conversation.
- **Android (IMPL-AND-18)**: same surfaced in the chat; reuses the labs the app
  already syncs.

> Out of scope for now (each needs its own ADR to widen): non-TRT meds /
> supplements, full interaction checking, multi-user exposure, clinician-handoff
> export, always-on titration-range UI.

## Build sequence

1. **Data model**: `targetWeightLbs`, `NutritionGuidance` (records + Firestore
   mappers + deep-response DTO + both client DTOs). Keep IMPL-17/ADR-0008 data
   valid.
2. **`ExercisePerformanceDigestService`** + tests (real imported-history fixture:
   staleness, e1RM, weight-only fallback, trend).
3. **`get_exercise_history` tool** + digest injection in the context builder.
4. **Prompt + validator guardrails** (landmarks, ramp-rate, deload, weight
   sanity).
5. **TRT decision-support**: curated guideline KB + `TrtAdvisorContextService`
   + `get_lab_history` tool + danger-flag rule set + cited-guidance prompt
   (ADR-0015).
6. **Web**: weights + "why" + nutrition strip in the proposal card; TRT/labs
   panel + danger-flag banner + cited guidance in chat.
7. **Android (IMPL-AND-18)**: chat client + chat screen + proposal card + commit
   + the TRT/labs surface.
8. **End-to-end**: the headline brief → 5-week, 3→4 day, ease-in, TRT-aware
   program grounded in real lifts, with per-phase nutrition AND grounded TRT
   dosing/bloodwork guidance with danger-flags, committed and materialized; same
   flow on phone.

## Out of scope (→ IMPL-18b / later)

- Conversationally editing an **already-active** program (live re-materialization
  without rewriting completed sessions) — S4. **→ implemented in
  [IMPL-18b](IMPL-18b-conversational-active-program-editing.md)
  ([ADR-0016](../decisions/ADR-0016-active-program-in-place-edit.md)).**
- Writing into the **nutrition module's** stored macro targets — S3 (display-only
  here).
- Auto-progression that rewrites future weeks from logged performance (the
  IMPL-15/ADR-0012 deferred autoregulation).
- Medical decision-support **beyond TRT** — non-TRT meds/supplements, full
  drug-interaction checking, multi-user exposure, clinician-handoff export (each
  needs its own ADR to widen ADR-0015).

## Acceptance criteria

- The headline brief yields a 5-week program, 3 days/wk wks 1–2 then 4 days/wk,
  every exercise executable at the chosen gym, with **concrete weights grounded
  in the user's logged/imported history** (RPE fallback where no history), an
  **ease-in ramp** reflecting layoff + intent, and **per-phase nutrition
  targets**.
- The model can call `get_exercise_history` and visibly use the result (e.g.
  "your last bench top set was 185×5 ~8 weeks ago, so we start at …").
- Validator rejects an inline edit that pushes weekly volume past MRV, omits a
  deload on a long phase, or ramps faster than the cap — surfaced like today's
  equipment violations.
- Asking a TRT dosing/protocol/bloodwork/side-effect question yields **grounded,
  cited guidance** reasoned from the user's actual labs (not a deferral, not an
  un-sourced specific), and training still adapts to TRT/recovery context
  (ADR-0015).
- A lab crossing a danger threshold (e.g. hematocrit > 54%) **always** raises a
  hard alert urging clinician contact, regardless of the question asked.
- The full design + edit + commit flow, and the TRT/labs guidance, work on
  **web and Android**.

## Resolved in design interview (round 2)

| # | Question | Decision |
|---|----------|----------|
| R1 | Guardrail severity | **Block only impossible** (exercise not at the gym, weight > e1RM); **warn-and-override** for volume-past-MRV, missing deload, fast ramp. |
| R2 | e1RM formula + weight-only rows | **Epley** (`w·(1+reps/30)`); weight-only imported rows = **conservative low-confidence floor** (informs, never anchors loads). |
| R3 | History token budget | **~20 exercises** by relevance/recency injected per turn; `get_exercise_history` tool for anything deeper. |
| R4 | Nutrition granularity | **Per-phase targets + a one-line program-level summary.** |
| R5 | Android commit path | **Direct online create** (chat is online over SSE); the new program flows into the mirror via normal sync. |
| R6 | Prescription "why" UX | **Per-set basis on tap** — weight shown by default; tap reveals last-done / e1RM / ramp-discount. Same on web + Android. |
| R7 | TRT framing (training) | **Phased recovery ramp** — early weeks assume blunted recovery/work capacity, ramping volume as TRT takes effect across the program (compounds the ease-in). |
| R8 | TRT medical guidance | **Enabled** — grounded dosing/protocol/bloodwork/side-effect decision-support folded into the chat (ADR-0015 supersedes ADR-0014): cited KB + user labs, TRT + monitoring panel, mandatory danger-flag alerts. |

These supersede the earlier open list; remaining build-time details (exact MEV/MAV/MRV
tables, ramp-rate %, staleness buckets, relevance scoring for the top-20) are
implementation tuning, not open product decisions.
