# IMPL-18b: Conversationally editing an already-active program

> **Status (2026-06-15): Implemented.** Backend (thread↔program binding,
> edit-mode context, in-place edit-commit + forward re-materialization), web
> ("Refine with AI" entry point + edit-mode chat), and Android (IMPL-AND-18b:
> active-program edit entry + edit-mode designer) all landed on
> `feat/IMPL-18-conversational-program-designer`. Governed by
> [ADR-0016](../decisions/ADR-0016-active-program-in-place-edit.md).

## Goal

IMPL-18 ([spec](IMPL-18-conversational-program-designer.md)) shipped the
history-grounded conversational program **designer** for *new* programs. Its S4
decision deferred one capability to this follow-up:

> *Conversationally editing an **already-active** program — live re-materialization
> without rewriting completed sessions.*

This spec adds exactly that. From an active program a user opens the same
designer chat, describes a change ("make next week lighter", "swap front squats
for goblet squats", "add a fourth day from week 3"), reviews the edited proposal,
and commits. The program is updated **in place** and its **future** schedule is
re-materialized; **already-completed sessions are frozen** and stay continuous in
history.

## Decisions locked

| # | Decision | Choice |
|---|----------|--------|
| E1 | Program identity on commit | **Edit in place** — same `programId`, status stays `ACTIVE`, completed sessions stay attached. (Not: supersede-with-new-program.) |
| E2 | Edit boundary | **Today forward, free rewrite** — the model is told which weeks are completed (frozen by date) and may restructure everything from today onward. |
| E3 | Surfaces | **Backend + web + Android together**, mirroring how IMPL-18 shipped. |
| E4 | Mechanism | Reuse `WorkoutScheduleService.activate()` (clears only future PLANNED, skips past dates) + `WorkoutProgramService.update()`; **no new endpoint or DTO** — the existing `/chat/{threadId}/commit` branches on whether the thread is program-bound. |

See [ADR-0016](../decisions/ADR-0016-active-program-in-place-edit.md) for the
rationale (esp. why in-place over supersede, and how the past stays immutable).

## Key insight: the re-materialization machinery already exists

`WorkoutScheduleService.activate(userId, programId)` already does forward-only
re-materialization, and IMPL-18b reuses it unchanged:

- `clearFrom = max(startDate, today)`; `scheduled.deletePlannedFrom(...)` removes
  **only PLANNED** sessions from `clearFrom` — COMPLETED/SKIPPED are never touched.
- It regenerates sessions from `program.phases()` and **skips any date before
  `clearFrom`** (`if (date.isBefore(clearFrom)) continue`), so the past is
  preserved by date.
- It flips/keeps the program `ACTIVE`.

So 18b is wiring, not new scheduling logic: (1) bind a chat thread to a program,
(2) feed the existing program + progress into the prompt, (3) branch commit to
`update()` + `activate()`.

## Backend — Spring

- **`WorkoutProgramChatThread` gains a nullable `programId`** (additive; the
  Firestore chat-thread mapper reads/writes it). Set → the thread edits that
  program; null → design-a-new-program (unchanged).
- **`ChatRequest.programId`** on the first message. When present, the thread is
  seeded from the existing program (schedule + goal come from the program; the
  form schedule is ignored) and bound to its `programId`.
- **Edit-mode context** (`buildContext` / `renderExistingProgram`): when the
  thread is program-bound, the prompt leads with the program's current
  phase→day→exercise structure and a **freeze rule** (today's date, program
  start, completed-session count + most-recent date, "every session before today
  is done and immutable — only change things from today forward; keep elapsed
  weeks intact so dates stay anchored"). Digest / science / TRT context unchanged.
- **`/chat/{threadId}/commit` branches** on `thread.programId()`:
  - **null →** unchanged: `service.create(input)` → **201**.
  - **set →** validate the edited tree under the existing identity; then
    `service.update(userId, programId, …, startDate = null /* keep anchor */,
    status = null /* keep ACTIVE */, phases)`; then
    `scheduleService.activate(userId, programId)`; return **200** + deep program.
- **Validator** unchanged — the same equipment/weight hard-blocks (IMPL-18 R1)
  and MRV/deload/ramp advisories apply to the edited proposal.

## Web — Next.js

- **Entry point**: a **"Refine with AI"** action on `ProgramDetailActions`
  (shown for `ACTIVE` programs) → `/me/workouts/programs/chat?programId={id}`.
- **Chat page** reads `?programId`, fetches the deep program, reconstructs the
  chat schedule from it, and passes an `editProgram` context to the chat.
- **Chat component** in edit mode skips the setup form (schedule/goal come from
  the program) and sends `programId` on the first turn; later turns are
  unchanged. The proposal card relabels its action to **"Update program"** and
  notes that completed sessions are preserved. Commit reuses
  `commitProgramProposal` (already accepts 200 or 201).

## Android — IMPL-AND-18b

- **Entry point**: a **"Refine with AI"** row on `ProgramDetailScreen` (shown for
  `ACTIVE` programs) → the designer chat route with an optional
  `?programId={id}` argument.
- **`WorkoutDesignerViewModel`** reads `programId` from `SavedStateHandle`; when
  present it loads the program (`WorkoutProgramRepository.get`), seeds the setup
  (training days + gym per day + goal), enters **edit mode** (skips the setup
  form), and sends `programId` on the first turn.
- **`ProgramChatRequest.programId`** (additive); the SSE client sends it on the
  first turn. Commit reuses `WorkoutProgramChatRepository.commit` (already keys
  on `isSuccessful`, so a 200 is success). The proposal card relabels to
  **"Update program"** and surfaces the "completed sessions preserved" note. The
  updated program + re-materialized schedule arrive via the normal sync mirror.

## Acceptance criteria

- From an active program, "Refine with AI" opens the designer chat seeded with
  that program's schedule/goal and structure, with no setup form.
- Asking to change upcoming work (lighten a week, swap an exercise, add/drop a
  day from a future week) yields an edited proposal; committing keeps the **same
  `programId`**, leaves status `ACTIVE`, and the program detail shows the change
  for future sessions.
- **Already-completed sessions are unchanged** after an edit-commit; only future
  PLANNED sessions are re-materialized (verified by `WorkoutScheduleServiceTest`).
- An impossible edit (exercise not at the gym, weight > e1RM) still 422s with
  inline issues, exactly like the designer.
- The full edit + commit flow works on **web and Android**.

## Out of scope

- Moving the program's `startDate` / shifting the whole plan in time (the anchor
  is intentionally fixed; the model keeps elapsed weeks intact).
- Auto-progression that rewrites future weeks **from logged performance** — the
  ADR-0012 / IMPL-15 deferred autoregulation, still separate from this.
- Editing completed sessions themselves (that is the IMPL-17 completion-upsert
  path, not the designer).
