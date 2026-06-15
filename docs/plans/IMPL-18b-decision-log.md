# IMPL-18b — Conversational active-program editing: implementation decision log

Working log of decisions made while implementing
[IMPL-18b](../specs/IMPL-18b-conversational-active-program-editing.md) and
[ADR-0016](../decisions/ADR-0016-active-program-in-place-edit.md). Each entry
records the question, the decision taken, and the rationale.

## Locked with the user before implementation

- **Edit in place**, not supersede-with-new-program (E1 / ADR-0016).
- **Today forward, free rewrite** — frozen completed weeks, free restructure from
  today onward (E2).
- **All three components** (backend + web + Android) in one change, like IMPL-18
  (E3).

## D1 — One endpoint, branch on thread binding (no new endpoint/DTO)
**Question:** Add a dedicated edit endpoint (e.g. `/programs/{id}/edit` +
`/rematerialize`), or reuse the chat commit?
**Decision:** Reuse `POST /api/me/workout-programs/chat/{threadId}/commit`. It
branches on `thread.programId()`: bound → `update()` + `activate()` → **200**;
unbound → `create()` → **201**. No new DTO (reuse `CreateProgramRequest`,
`WorkoutProgramDeepResponse`).
**Rationale:** The designer already produces the exact edited tree the commit
needs; the only difference is destination. The thread already carries the form
context, so binding it to a program is the smallest seam. Clients already treat
any 2xx as success (web `res.ok`, Android `isSuccessful`), so the 200/201 split
needed no client change.

## D2 — Bind the program on the THREAD, not just the commit body
**Question:** Carry `programId` only on the commit request, or persist it on the
chat thread?
**Decision:** Persist a nullable `programId` on `WorkoutProgramChatThread`
(additive field + Firestore mapper), seeded on the first turn.
**Rationale:** The edit-mode **context** (existing structure + freeze rule) must
be injected on *every* turn, not just at commit — and turns 2+ send only
`{threadId, message}`. The server therefore needs the binding to live on the
thread it loads by id. It also makes the binding survive thread resume.

## D3 — Keep `startDate` and `status` on edit-commit; anchor the timeline
**Decision:** The edit branch calls `service.update(... startDate = null,
status = null, phases)` — null means "leave unchanged." `WorkoutProgramService`
re-normalizes phase target dates from the existing `startDate`, and `activate()`
materializes by date, skipping anything before today.
**Rationale:** Holding the start anchor fixed is what guarantees completed past
dates stay in the past after re-normalization; moving it would risk
re-materializing over performed work. Status stays `ACTIVE` (and the service's
sticky-COMPLETED guard still protects a completed program).

## D4 — Freeze boundary is a PROMPT rule, enforced by date in the materializer
**Decision:** The model is *told* (today's date, completed count, "weeks before
today are immutable; only change from today forward; keep elapsed weeks intact").
The hard guarantee is in `activate()`/`deletePlannedFrom()`, which only ever
clear/rewrite future PLANNED sessions.
**Rationale:** Two layers: the prompt keeps the model from proposing nonsense
about the past; the materializer makes it *impossible* to rewrite completed work
regardless of what the model returns. The validator (unchanged) still blocks
impossible edits.

## D5 — Entry point: "Refine with AI" on the active-program detail, ACTIVE only
**Decision:** Web `ProgramDetailActions` and Android `ProgramDetailScreen` show a
"Refine with AI" affordance gated to `status == ACTIVE`. DRAFT programs use the
normal designer/commit flow; ARCHIVED/COMPLETED are not editable here.
**Rationale:** The detail page is where a user deciding to change their plan
already is. Gating to ACTIVE matches the feature's scope and avoids implying you
can "edit" a finished or tombstoned program.

## D6 — Reconstruct the chat schedule from the program (web), seed from repo (Android)
**Decision:** Web rebuilds `WorkoutProgramChatSchedule` (training days + gym per
day) from the deep program's `trainingDays` + per-day `locationId` and passes it
as `editProgram`. Android loads the domain program via `WorkoutProgramRepository`
and seeds the setup state, entering edit mode (skips the setup form).
**Rationale:** Edit mode must skip the setup form, but the proposal card + commit
still need the schedule (gym-scoped exercise pickers, commit body). The program
itself is the source of truth for both, so we derive rather than re-ask.

## Tests
- `WorkoutScheduleServiceTest.reactivatePreservesCompletedSessionsAndRewritesOnlyFuturePlanned`
  — the core guarantee: re-activate keeps COMPLETED sessions and only rewrites
  future PLANNED.
- `WorkoutProgramChatCommitControllerTest` — a program-bound thread's commit
  returns 200, keeps the `programId` + ACTIVE status, re-materializes; an
  invalid edit still 422s.
