# IMPL-20: Performance Audit & Remediation Plan

## Goal
The web app feels slow and sluggish. This spec captures the findings of a
read-only performance audit of `web/` and `backend/`, and a phased plan to make
the app faster. No code is changed by this document — it is the plan to review
before implementation.

## Root cause (one sentence)
Caching is disabled at every layer: every page is `force-dynamic` (21 of them),
every backend call is `cache: "no-store"` and re-validates the session, and the
backend re-reads whole Firestore collections per request with no cache — so each
navigation pays full network + Firestore latency, multiplied by Cloud Run cold
starts.

## Cache stance decision
**Balanced** (chosen 2026-05-30). Read-mostly pages get `revalidate = 60`.
Pages whose data must be live (medication adherence, goal/step status) stay
fully dynamic. Mutations invalidate via `revalidatePath` / tag invalidation.
No blanket 5-minute caching.

---

## Findings (verified against source)

### High impact
| # | Finding | Location | Fix |
|---|---------|----------|-----|
| 1 | Every backend fetch sets `cache: "no-store"` — no dedup, no reuse | `web/lib/api.ts:45` | Remove blanket `no-store`; set per-call on mutations only |
| 2 | `await auth()` runs on *every* `apiFetch` — JWT validated N× per page | `web/lib/api.ts:27` | Wrap session in React `cache()`, resolved once per render |
| 3 | 21 pages forced `force-dynamic` — no ISR, full re-render every visit | `web/app/**` | `revalidate = 60` on read-mostly pages; dynamic only where live |
| 4 | MedicationCard polls `/api/drugs/{id}` every 3s ×20 (~100 req/min for 5 meds) | `web/components/medications/MedicationCard.tsx:25` | Remove auto-poll; revalidate on image-gen completion or manual check |
| 5 | Chat builds full health snapshot per message — loops all 24 `MetricKey`s, each a "latest value" read (see #6); ~24+ Firestore round-trips/msg | `backend/.../goals/chat/UserHealthSnapshotService.java:326` | Cache snapshot ~60s/user; batch-resolve metrics |
| 6 | "Latest value" reads fetch many docs to return one — `latestBodyComposition`/`latestBloodMarker`/`latestBloodReading` call `findByUser` (limit-capped at 200–500) then pick the latest in Java. Multiplied per `MetricKey` (#5) and per bound step on metric change (#1 below). | `backend/.../goals/eval/FirestoreMetricResolver.java:195,220,242`; `StepEvaluationService.java:95` | Add `findLatestByMetric` = `whereEqualTo(metric) + orderBy(... DESC).limit(1)` (the indexed shape already exists in `BodyCompositionRepository.findByUserAndRange:60`); add request-scoped metric cache |

### Medium impact
- Goal-deep read re-reads steps twice (`FirestoreStepRepository`); a few subcollection queries lack an explicit `.limit()` fail-safe (most repos already cap at 200–500). De-duplicate the double read; add `.limit()` where missing.
- No backend cache layer — add Spring `@Cacheable` (Caffeine) for reference reads (drugs, users) and metric snapshots.
- Over-fetching — `/me/meds` pulls the entire drug catalog for lookup (`web/app/me/meds/page.tsx:19`); goals deep-read returns whole phase/step tree, no pagination.
- Redundant `/api/me` calls on pages that already hold the session JWT.
- Raw `<img>` (11+ spots) instead of `next/image` — full-res, no lazy-load/WebP.

### Low impact / polish
- No `Suspense` boundaries on dashboard — blocks on slowest of 4 endpoints instead of streaming.
- Icon fonts loaded render-blocking from CDN (not preloaded).
- Backend JAR not layered → slower Cloud Run cold starts (`bootJar { layered }`).
- Heavy client components re-render per keystroke (admin equipment/drug modals).

---

## Phase 1 — Web caching (low risk, est. 40–60% navigation latency cut)
Targets findings #1–#4. Config/wrapper changes, no business-logic change.

1. **`web/lib/api.ts`**
   - Remove the blanket `cache: "no-store"` from `apiFetch`. Add an explicit
     `no-store` only on mutation helpers (POST/PUT/PATCH/DELETE paths).
   - Wrap the session lookup in React `cache()` so `await auth()` resolves once
     per server render instead of once per call.
2. **Pages (`web/app/**`)**
   - Replace `export const dynamic = "force-dynamic"` with
     `export const revalidate = 60` on read-mostly pages.
   - Keep `force-dynamic` on live pages: medication adherence/doses, goal/step
     status, anything reflecting a just-completed mutation.
   - Audit each of the 21 pages individually; classify live vs read-mostly.
3. **`web/components/medications/MedicationCard.tsx`**
   - Remove the 3s polling loop. Replace with revalidation triggered when image
     generation completes (server action + `revalidatePath`/tag), or a single
     user-initiated "check for image" action.

**Acceptance:** `grep -rn "no-store" web/lib web/app` shows only mutation paths;
`grep -rl force-dynamic web/app` only lists live pages; MedicationCard issues no
background polling. `pnpm build` succeeds; manual nav between dashboard ↔ meds ↔
goals shows reduced duplicate backend calls (verify via network panel / backend
logs).

## Phase 2 — Backend Firestore + caching (est. ~70% fewer reads on hot paths)
Targets findings #5, #6, and the medium-impact Firestore items.

1. **Metric resolution** (`FirestoreMetricResolver`, `StepEvaluationService`)
   - Replace the fetch-200/500-then-pick-latest-in-Java pattern
     (`latestBodyComposition:195`, `latestBloodMarker:220`, `latestBloodReading:242`)
     with a `findLatestByMetric` query: `whereEqualTo(metric) + orderBy(... DESC)
     .limit(1)`. The indexed query shape already exists at
     `BodyCompositionRepository.findByUserAndRange:60` — generalize it.
   - Add a request-scoped cache so one metric change doesn't re-read the same
     subcollection per bound step.
2. **Health snapshot** (`UserHealthSnapshotService`)
   - Cache the per-user snapshot ~60s.
   - Batch-resolve the 24 metric keys in one pass rather than 24 sequential
     `resolve()` calls.
3. **Query safety** — de-duplicate the double step read in goal-deep; add a
   `.limit()` fail-safe to the few subcollection queries that lack one (most
   repos already cap at 200–500).
4. **Cache layer** — introduce Caffeine + Spring `@Cacheable` on reference reads
   (drugs, users) and metric snapshots, with short TTLs (1–5 min).

**Acceptance:** instrument Firestore read count on a chat request and a
metric-change event before/after; expect a large drop. Backend tests green.

## Phase 3 — Polish
- Convert `<img>` → `next/image` across the 11+ call sites.
- Add `Suspense` boundaries so the dashboard streams instead of blocking on the
  slowest endpoint.
- Preload icon fonts in `<head>`.
- Enable `bootJar { layered }` + layer-cache the backend Dockerfile.
- Trim/paginate large payloads (`/me/meds` catalog, goals deep-read).

---

## Risks
- **Stale data** is the main Phase-1 risk. Mitigation: balanced TTLs, keep live
  pages dynamic, and ensure every mutation calls `revalidatePath`/tag.
- **Cache invalidation correctness** on the backend — start with short TTLs and
  reference data (low churn) before caching user-specific reads.

## Out of scope
- Android client performance (separate audit if needed).
- Infra/Cloud Run scaling config (min instances, concurrency) — note only.
