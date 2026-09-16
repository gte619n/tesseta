# OpenAPI → TS codegen for the internal API — feasibility (investigation only)

Requested after DEC-21 ("reconsider dropping" the web type codegen). No code
changed; this is the cost/options assessment to decide with.

## What exists today

- **springdoc is already a dependency** (`springdoc-openapi-starter-webmvc-ui:2.8.9`)
  but **scoped to `/v1/**`** via `springdoc.paths-to-match: /v1/**`
  (application.yml). Only the third-party platform API is specced; the internal
  `/api/me/**` surface is deliberately excluded.
- The `/v1` spec is **hand-annotated**: the 5 `V1*Controller`s carry `@Operation`
  / `@Schema`, and `PlatformOpenApiConfig` adds ~180 lines of customization (RFC
  7807 error schemas, response wiring) to make the output publishable.
- **Internal surface size:** 63 controllers, 185 files under `api/`, **~236 DTO
  records**, ~270 endpoints (132 mutating). Web hand-maintains **13 type files,
  ~2,016 LOC**.

## Options

**A. Full: spec the whole `/api/me` surface + TS codegen.**
Widen `paths-to-match` to `/api/**`, then run `openapi-typescript` (or
`orval`) in the web build to generate `lib/types` from the fetched spec.
- Cost: **large (multi-day) + ongoing.** springdoc infers schemas from record
  shapes, but without `@Schema`/`@Operation` the output is low-fidelity for this
  codebase: nullability isn't captured from Java types, the pervasive
  `WriteResult<T>` / `Optional` / polymorphic payloads (e.g. nutrition entry vs
  composite meal, leftover state) and the SSE/multipart endpoints need
  hand-annotation to produce correct TS. Expect to annotate a large fraction of
  236 DTOs, then keep annotating forever. Risk: generated types silently diverge
  in shape from the current hand-written ones (a migration hazard for every
  consuming component).

**B. Targeted: codegen only the synced-entity DTOs** (the ~23 collections that
actually cross the sync boundary), leaving the rest hand-written.
- Cost: **medium.** Still needs a spec source for those DTOs (annotate ~40–60
  records) + a codegen step scoped to them. Narrower blast radius than A.

**C. Keep hand-maintained types (status quo + slice 10).** No codegen.
- Cost: **zero.** The recurring *silent* drift bug (a backend collection the
  client drops) was the collection-**routing** contract — already closed by slice
  10's enforced fixture. The residual DTO-shape drift (a field renamed on the
  backend, not in web types) surfaces as a **web `tsc` failure at the call site**
  — loud and build-blocking, not a silent runtime bug.

## Recommendation

**Option C, unless you specifically want typed internal clients.** The
highest-value drift (silent collection-routing loss, which caused real prod
incidents) is already enforced by slice 10. The remaining web/backend DTO drift
is caught by the web typecheck, so it's a compile error, not a runtime hazard.
Option A's cost (days of annotation + permanent maintenance + a risky one-time
type migration) buys mostly a class of bug the compiler already catches. If a
subset is worth typing, **Option B on the synced entities** is the sweet spot —
it aligns with the sync contract we already enforce — but it's still net-new
annotation work for a modest gain.

**Not recommended to build now.** If you want to proceed, B is the one to scope.
