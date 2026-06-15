# ADR-0016: Conversationally editing an active program — in-place, forward-only re-materialization

- Status: Accepted
- Date: 2026-06-15
- Relates to: [IMPL-18b](../specs/IMPL-18b-conversational-active-program-editing.md),
  [ADR-0012](ADR-0012-active-workout-logging.md) (completed sessions are history),
  [IMPL-18 spec §Out of scope](../specs/IMPL-18-conversational-program-designer.md)

## Context

IMPL-18 shipped the history-grounded conversational program **designer**: a user
designs a *new* program in chat, edits the proposal, and commits — which creates
a fresh `DRAFT`. IMPL-18 explicitly deferred one capability (its S4 decision and
"Out of scope" list):

> Conversationally editing an **already-active** program (live re-materialization
> without rewriting completed sessions).

IMPL-18b implements that. Once a program is active, the user has performed
sessions against it (IMPL-17 logged sets; ADR-0008 imported history). Those
completed sessions are durable history that aggregates, goals, and the workout
log already depend on. So "let the user revise an active program by chatting"
raises a real question: **what happens to the program's identity and to the work
already done?**

Two shapes were considered:

1. **In-place edit.** The same `programId` is updated; only future PLANNED
   sessions are re-materialized; completed sessions stay attached and unchanged.
2. **Supersede.** Commit creates a successor program and archives the original;
   completed sessions remain under the archived program.

## Decision

**Edit the active program in place, re-materializing forward only.**

- A chat thread may be **bound to a `programId`** (the program being edited).
  Committing a program-bound thread calls `WorkoutProgramService.update(...)`
  (same `programId`, status stays `ACTIVE`, `startDate` unchanged so the
  week→date timeline stays anchored) and then re-runs
  `WorkoutScheduleService.activate(...)`.
- `activate()` already clears only **future PLANNED** sessions
  (`deletePlannedFrom(clearFrom = max(startDate, today))`) and skips any date
  before today. **COMPLETED / SKIPPED sessions are immutable** and are never
  touched by a re-materialization.
- The model is given the current program structure plus a **freeze boundary**
  (today's date, completed-session count, "weeks before today are done — only
  change things from today forward"). The user may otherwise restructure the
  remaining weeks/phases freely ("today forward, free rewrite").
- The same validator guardrails as the designer apply (IMPL-18 R1): impossible
  edits (exercise not at the gym, weight > e1RM) hard-block; volume/deload/ramp
  advisories warn.

No new endpoint, DTO, or program record: the existing
`POST /chat/{threadId}/commit` branches on whether the thread carries a
`programId`, returning **200** for an in-place edit (vs **201** for a new draft).

## Rationale

- **Completed work is history (ADR-0012).** Re-materializing forward and freezing
  the past preserves logged sets, weekly aggregates, and goal-metric fan-out
  exactly. The existing `activate()` machinery already guarantees this by date —
  in-place edit reuses it instead of inventing a parallel path.
- **Continuity.** Roadmap, history, and "this week" stay on one continuous
  program. Superseding would split a single training block across an
  archived-program / successor-program pair for no user-visible benefit, and
  would orphan the chat/goal links.
- **Minimal blast radius.** Reusing `update()` + `activate()` and branching one
  endpoint keeps the change additive and consistent across backend/web/Android
  (mirrors how IMPL-18 shipped). The thread gains one nullable `programId` field.

## Consequences

- The `startDate` anchor is intentionally **not** moved on edit; the model is
  told to keep elapsed weeks intact so future weeks map to the right calendar
  dates. A future "shift the whole plan out by N weeks" gesture would need an
  explicit re-anchor, out of scope here.
- Editing the *currently in-progress* week is allowed (its remaining sessions are
  PLANNED), but any session already COMPLETED that week stays as performed.
- Scope stays at **draft-refine + active-edit before/around the current week**.
  Auto-progression that rewrites future weeks *from logged performance* remains
  the separate ADR-0012/IMPL-15 deferred autoregulation, not this.

## Alternatives considered

- **Supersede with a new program** (rejected): cleaner audit trail, but splits a
  single training block across two program records, orphans chat/goal links, and
  needs cross-program stitching in every view that reads "the program."
- **A dedicated `/programs/{id}/edit` + `/rematerialize` endpoint pair**
  (rejected): more surface area for the same effect the existing chat-commit +
  `activate()` already provide; the thread-bound commit is the smaller seam.
