# ADR-0007: Workout program design uses gemini-3.1-pro-preview (second exception to the flash-only rule)

- Status: Accepted
- Date: 2026-06-01

## Context

The project convention (root `CLAUDE.md`, "AI Models") is that all general
AI work runs on **`gemini-3.5-flash`**, with `gemini-3.1-flash-image-preview`
reserved for image generation, and any other model disallowed without an ADR.
[ADR-0005](ADR-0005-goals-chat-gemini-pro.md) carved out the first exception —
Goals chat — and closed with: *"A second feature wants a non-flash model —
that warrants its own ADR rather than widening this one."* This record is that
second ADR.

[IMPL-15](../specs/IMPL-15-workout-programs.md) ships AI workout-program
design: the user describes what they want, and Gemini designs a complete
**Program → Phase → Workout Day → Block → Exercise** structure — a periodized
mesocycle with sequenced phases (e.g. an accumulation block followed by a
deload week), a weekly microcycle of training days, and per-day blocks
(warm-up, main work, accessory, cardio, cool-down, stretch) — returned via the
`propose_workout_program` tool call for the user to review and edit.

This is the same *class* of problem ADR-0005 identified, at higher difficulty:

- **It is reasoning over constraints, not extraction.** The model must
  periodize across multiple weeks (progressive overload, then deload),
  sequence phases so each builds on the last, balance weekly volume across
  muscle groups and training days, and respect the user's available training
  days. This is genuine planning, not document-to-field mapping.

- **It reasons over a full health snapshot.** The request appends the same
  `UserHealthSnapshotService` plain-text snapshot Goals uses — current body
  composition / DEXA, blood panel, vitals, medications, goals — so the program
  is grounded in the user's real starting point, recovery capacity, and
  objectives.

- **It must respect a hard equipment constraint.** Each training day is pinned
  to a specific gym (`Location`), and the model is given only the exercises
  executable with that gym's equipment (see `ExerciseAvailabilityService` in
  IMPL-15). It must select exclusively from that allowed set. `gemini-3.5-flash`
  is materially worse at honoring a long allow-list under simultaneous
  periodization constraints — it hallucinates exercises the gym can't support,
  which the validator then has to reject.

As with Goals, cost is not a concern: program design is **low-volume and
user-initiated** (a handful of design conversations per user, not a
per-document or per-webhook pipeline), so the Pro premium is negligible.

## Decision

Workout-program design uses **`gemini-3.1-pro-preview`** as a deliberate,
documented exception to the `gemini-3.5-flash`-only convention — the same model
ADR-0005 selected for Goals chat (there is no `gemini-3.5-pro`; the 3.5
generation ships flash only).

- Scope is **workout-program design ONLY**. Exercise-catalog parsing and any
  other extraction stay on `gemini-3.5-flash`. Exercise demo-media generation
  stays on the image model `gemini-3.1-flash-image-preview`
  ([IMPL-14](../specs/IMPL-14-exercise-library.md)). No other feature changes
  model.

- The model is configured on its **own** environment variable,
  `WORKOUT_PROGRAM_GEMINI_MODEL`, independent of the shared `GEMINI_MODEL` and
  of the Goals `GOALS_GEMINI_MODEL`:

  ```yaml
  app:
    workout-programs:
      gemini-model: ${WORKOUT_PROGRAM_GEMINI_MODEL:gemini-3.1-pro-preview}
  ```

  This keeps the exception explicit in config and lets us retune the
  workout-program model (cost test back to flash, or forward to a newer Pro
  revision) without touching Goals or the flash modules.

## Consequences

Positive:

- Better program quality: coherent periodization, deloads placed sensibly,
  weekly volume balanced, and — critically — far fewer equipment-constraint
  violations the validator must reject.
- The exception is contained and visible — one env var, one config line, one
  ADR — consistent with how ADR-0005 handled Goals.
- Easy to revert: set `WORKOUT_PROGRAM_GEMINI_MODEL=gemini-3.5-flash`.

Negative:

- Higher per-call cost and latency than flash. Acceptable given the low,
  user-initiated volume.
- The codebase now runs the Pro model for **two** features. Anyone reasoning
  about AI behavior or cost must remember that both Goals chat and
  workout-program design are the outliers; ADR-0005, this ADR, and the
  `application.yml` comments are the breadcrumbs. If a third feature wants a
  non-flash model, consider whether a shared "planning model" abstraction is
  warranted rather than a third per-feature env var.

## Revisit when

- A future flash revision closes the planning-quality gap, at which point both
  Goals chat and workout-program design should fold back onto the shared model.
- Workout-program volume grows enough that the Pro premium becomes material.
- Video demo generation lands (deferred in IMPL-14): adding Google Veo or any
  other video model is a separate model introduction and **requires its own
  ADR** — it is explicitly not covered here.
