# ADR-0015: Grounded TRT decision-support in the program designer

- Status: Accepted
- Date: 2026-06-14
- Supersedes: [ADR-0014](ADR-0014-designer-medical-context-scope.md)

## Context

ADR-0014 (accepted earlier the same day) drew the IMPL-18 designer's boundary at
"performance science yes, medical advice no" — TRT could shape *training*
variables but the designer would refuse dosing, protocol, bloodwork, and
side-effect questions and defer them to a clinician.

The product owner reversed that: they want the designer to give **dosing,
protocol, bloodwork-interpretation, and side-effect guidance** for their own
testosterone-replacement therapy. The relevant facts that make this reasonable
to build:

- **Single-user, personal health-management app.** The user owns the instance
  and the data; this is not a multi-tenant clinical product giving advice to
  strangers.
- **TRT is legitimate prescribed therapy**, and the inputs already live in the
  app — bloodwork (`BloodTestReport`/readings), medications, body composition.
  The designer is interpreting the user's *own* real data, not inventing a
  clinical picture.
- The user is actively managing this therapy and wants their own data turned
  into specific, actionable guidance rather than a deferral.

The genuine risk is not "talking about dosing"; it is an LLM emitting a
**confident, subtly-wrong specific** from parametric memory — a dose, a
threshold, a titration step — where the error has real consequences
(polycythemia, estradiol mismanagement, lipids, PSA, fertility). The mitigation
is **grounding and danger-flagging**, not silence. ADR-0014's prohibition traded
away real, requested value to avoid a risk that grounding addresses more
honestly.

## Decision

1. **Enable grounded TRT decision-support in the workout designer.** The dosing/
   protocol/bloodwork/side-effect capability is **folded into the existing
   designer chat** (not a separate surface), per the IMPL-18 design interview.
   The ADR-0014 refusal rule and dosing/medical-intent backstop are removed.

2. **Grounded in the user's labs + a curated, cited guideline knowledge base.**
   Every clinical claim reasons from the user's actual bloodwork/meds in the app
   **and** a version-controlled knowledge base distilled from authoritative
   clinical guidelines (e.g. Endocrine Society TRT guidance). Claims **cite their
   source** from the KB. The model does not free-associate specifics from
   parametric memory; un-grounded clinical specifics are out.

3. **Specific decision-support framing — not watered down, not a black box.**
   It gives concrete numbers, protocols, and titration logic tied to the user's
   data ("trough total-T 350 ng/dL on 100 mg/wk; guidelines target X; hematocrit
   52% is approaching the 54% watch line"), presented as informed guidance the
   user acts on. It does not hide behind blanket "see your doctor" deferrals, and
   it does not assert authority it lacks — it shows its data and sources so the
   user (and their prescriber) can verify.

4. **Scope: TRT and its monitoring panel.** Testosterone protocol/dosing/
   titration plus the governing labs — estradiol, hematocrit/hemoglobin, lipids,
   PSA — and side-effect surveillance. Other medications/supplements are out of
   scope for now (a future ADR can widen it).

5. **Danger-flag alerts are mandatory.** When a lab crosses a known risk
   threshold (e.g. hematocrit > 54%, a notable PSA rise), the designer raises a
   hard alert urging prompt clinician contact, independent of what the user asked
   — the one non-negotiable safety rail.

6. **Performance-science framing carries over from ADR-0014.** Using TRT/recovery
   context to shape training (ramp rate, volume tolerance, deloads, the phased
   recovery curve, IMPL-18 R7) is unchanged and continues.

7. **No new model or provider.** Runs on the existing `gemini-3.1-pro-preview`
   designer ([ADR-0013](ADR-0013-workout-program-design-gemini-pro.md)) with the
   KB as grounding context. No model change; the flash-only rule is unaffected.

## Consequences

- The designer now gives medical/pharmacological guidance for TRT — a deliberate
  product stance for a personal, single-user, owner-operated health app, not a
  general-audience clinical service.
- A **curated TRT guideline KB** becomes a maintained artifact (version-controlled,
  citable, reviewed) — new build + ongoing upkeep, and the quality ceiling of the
  feature.
- Accuracy rests on grounding: answers cite the user's labs + the KB; danger-flag
  thresholds are an explicit, testable rule set. IMPL-18 acceptance gains tests
  that (a) clinical claims are sourced, (b) danger thresholds fire, (c) guidance
  reflects the user's actual lab values.
- ADR-0014 is superseded; its refusal guardrail and the "defer medical questions"
  UX are removed from IMPL-18.
- Widening beyond TRT (other meds, full polypharmacy/interaction checking) or
  exposing this in a multi-user context would each need a new ADR — the enabled
  scope here is intentionally TRT-only and personal.
- **Clinician handoff export** and **always-on titration-range presentation**
  were considered as additional rails but not adopted now; they remain easy
  follow-ons if wanted.
