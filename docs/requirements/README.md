# Requirements

This directory is the home for **product and non-functional requirements** — the
"what and how-well," as opposed to the reference docs' "what exists." It was
created to close a gap: requirements were previously implicit (scattered across
retired `IMPL-*` specs and the feature catalog) with no single statement of
goals, acceptance criteria, or non-functional targets, and — for a health app —
no documented privacy/compliance posture.

> Status: **initial draft.** Sections marked _(required)_ describe intended
> behaviour that may not be fully implemented or verified yet; treat them as the
> target, not a claim of current compliance.

## Contents

- [**privacy-and-compliance.md**](privacy-and-compliance.md) — PHI-grade data
  handling: classification, retention, consent, deletion/export, sub-processors,
  encryption. **Start here** — it's the most load-bearing gap for a health app.

## Product goals

Tesseta is a personal health & fitness platform. The product goal is to give one
user a single, trustworthy view of their own health signals (activity, blood
markers, body composition, medications & adherence, nutrition, AI-planned goals)
across a phone, a watch, and the web, with the backend as the source of truth.

Success is measured by: data correctness (a value shown always matches what was
ingested/entered), timely sync across devices, and never losing user data.

## Functional requirements

Per-feature functional state (shipped / deferred / fixture, per platform) lives
in [`../reference/feature-catalog.md`](../reference/feature-catalog.md); the API
contract in [`../reference/api-surface.md`](../reference/api-surface.md); data
shapes in [`../reference/data-model.md`](../reference/data-model.md). New
features should land with **acceptance criteria** (Given/When/Then) in their PR
or an `IMPL-*` spec, and a test that encodes each criterion.

## Non-functional requirements

- **Security & privacy** — see [privacy-and-compliance.md](privacy-and-compliance.md).
  Summary: authenticated access only (Google ID tokens; deny-by-default routing),
  strict per-user data scoping (no IDOR), envelope-encrypted secrets at rest
  (KMS), TLS in transit, SQLCipher-encrypted on-device mirror.
- **Correctness under concurrency** _(required)_ — money-path writes that
  read-modify-write a shared document (token rotation, dose logging, daily
  rollups) MUST be atomic (Firestore transactions), so concurrent requests never
  lose or double-apply a write.
- **Performance** _(required)_ — per-user reads MUST scale with the user's own
  data, not the total userbase (no unbounded collection-group scans on hot
  paths); list reads are `.limit()`-bounded; hot reference reads are cached.
  Target: dashboard hydrate < 1s p95 at expected load. (No formal load test yet.)
- **Availability** — both Cloud Run services scale to zero and up on demand;
  there is currently **no staging environment and no canary/rollback automation**
  _(required, tracked in the CI/CD workstream)_.
- **Reliability of clients** — the web app degrades gracefully on backend errors
  (route-segment error boundaries); the Android app is offline-first against the
  backend (Room/SQLCipher mirror + outbox replay, ADR-0007).
- **Accessibility** _(required)_ — interactive surfaces meet WCAG 2.1 AA basics:
  dialogs expose role/aria-modal + keyboard operation; charts expose a text
  alternative to assistive tech.
