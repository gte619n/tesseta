# ADR-0014: The program designer leans into performance science but gives no medical advice

- Status: Superseded by [ADR-0015](ADR-0015-trt-decision-support.md) (2026-06-14)
- Date: 2026-06-14

> **Superseded the same day.** The "no dosing/medical advice" boundary below was
> reversed by ADR-0015, which enables grounded TRT decision-support (dosing,
> protocol, bloodwork, side-effects) inside the designer. The reasoning here is
> retained for context; the *performance-science* framing (use TRT/recovery to
> shape training variables) carries forward into ADR-0015 unchanged — only the
> medical-advice prohibition is lifted.

## Context

The IMPL-18 history-grounded program designer is a Gemini-3.1-Pro chat
([ADR-0013](ADR-0013-workout-program-design-gemini-pro.md)) that designs
training programs from a natural-language brief. It is fed the user's health
snapshot via `UserHealthSnapshotService` — which **includes active medications**
— so a user "restarting TRT" surfaces that to the model, and IMPL-18's design
interview explicitly chose to **lean into performance science** (S6/R7): early
phases should assume blunted recovery/work capacity and ramp volume as TRT takes
effect.

This puts an LLM at the boundary of health guidance. Two failure modes bracket
the decision:

- **Too timid** — ignore hormonal/recovery context entirely and the
  personalization the user asked for (and paid the Pro-model cost for)
  evaporates; the designer is no better than a generic template.
- **Too far** — the model drifts into **dosing, protocol, or medical
  guidance** ("bump your test to X", "you can train fasted on TRT because…"),
  which is medical advice the product is not qualified or licensed to give,
  carries real user-safety and liability risk, and is not what was asked for.

The designer is also not a one-shot: it is a multi-turn chat where the user can
ask anything, including direct medical questions ("how much testosterone should
I take?"). A prompt instruction alone is a soft control; the boundary needs to
be a recorded, enforced policy rather than a sentence buried in a template that
any later prompt edit could erode.

Related precedent: the existing designer already consumes meds/blood/DEXA as
*context* without commenting on them medically; this ADR makes that boundary
explicit now that IMPL-18 deliberately reasons **from** that context.

## Decision

1. **Hormonal/recovery status informs training decisions, not medical ones.**
   The designer may use TRT (and other recovery-relevant context: sleep, HRV,
   training age, layoff) to shape **programming variables only** — ramp rate,
   weekly volume tolerance, frequency, deload cadence, the phased recovery curve
   (IMPL-18 R7). It reasons about *how the body will adapt and recover*, not
   about *what to take or how to dose*.

2. **No dosing, protocol, or medical advice — ever.** The designer must not
   recommend, adjust, evaluate, or speculate about medication dose, timing,
   stacks, bloodwork targets, side-effect management, or any clinical decision.
   This holds whether the user asks directly or the model would volunteer it.

3. **Direct medical questions get a brief, non-evasive deferral.** When asked a
   dosing/medical question the designer declines in one line and points to a
   qualified clinician, then continues to help with training. It does not
   lecture, moralize, or refuse the *training* conversation.

4. **Enforced in depth, not just prompted.** The boundary lives in (a) the
   system prompt (positive framing of what to use + an explicit refusal rule)
   **and** (b) a lightweight guardrail in the chat loop that catches
   dosing/medical intent and forces the deferral, so a future prompt edit cannot
   silently remove the floor. The guardrail is a backstop, not the primary UX.

5. **Performance/exercise/nutrition science is in scope.** Evidence-based
   training and nutrition reasoning (periodization, volume landmarks, protein
   targets, recovery, the IMPL-18 per-phase nutrition guidance) is encouraged —
   that is the product's value. The line is *medical/pharmacological* advice,
   not *fitness* science.

6. **No new model or provider.** This is a scope/policy decision layered on the
   existing Gemini-Pro designer (ADR-0013); it introduces no model change and is
   unaffected by the flash-only rule.

## Consequences

- The IMPL-18 prompt carries both a positive instruction (use TRT/recovery
  context to set ramp/volume) and a refusal rule (no dosing/medical), and the
  chat loop gains a dosing/medical-intent backstop with a canned deferral.
- The designer delivers the personalization the user asked for — a TRT-aware,
  ease-in ramp — without the product asserting medical authority it lacks.
- Users asking medical questions are deferred to a clinician rather than
  answered, which is a deliberate, recorded UX choice (not a model lapse).
- The boundary is testable: a fixture suite asserts that dosing/medical prompts
  are deferred while training adapts to TRT context. This is part of IMPL-18
  acceptance.
- If a future feature genuinely needs to surface clinical information (e.g. a
  clinician-facing mode), it requires its own ADR superseding this scope — the
  default for the consumer designer stays "no medical advice."
