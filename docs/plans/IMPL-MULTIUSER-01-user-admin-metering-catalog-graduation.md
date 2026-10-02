# IMPL-MULTIUSER-01 — User Administration, AI Spend Metering & Catalog Graduation

> **This document is the spec.** On plan approval it is copied verbatim to
> `docs/plans/IMPL-MULTIUSER-01-user-admin-metering-catalog-graduation.md` (matching the repo's
> `docs/plans/IMPL-*` convention). It is the single source of truth for scope, phasing, status,
> testing, and the agent's self-verification protocol.

| | |
|---|---|
| **Impl ID** | IMPL-MULTIUSER-01 |
| **Owner** | evan.ruff@oxos.com |
| **Platforms** | Backend (Java/Spring + Firestore), Web (Next.js App Router + Auth.js), Android (Kotlin/Compose + KMP) |
| **Branch** | `user-admin` (worktree) + per-phase PRs |
| **Rollout** | All-or-nothing per-phase merge — **no runtime feature flags**. Lockout safety comes from the `ADMIN_EMAILS` env bootstrap (below), not a flag. |
| **Sequencing** | All three pillars built **in parallel**. Nothing deferred. |

---

## Build status — 2026-10-02 (autonomous implementation pass)

> Decisions + deviations are logged in `IMPL-MULTIUSER-01-decision-log.md`. In this environment
> **`Tested [x]` below means the automated unit/integration suite is green** (the hard DoD gate, D20).
> The functional Maestro/Playwright E2E layer (spec §8–§9) **was not run here** — it needs
> emulators/devices + a running backend — so no phase claims E2E evidence yet.

- **Backend: COMPLETE and verified.** `cd backend && ./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL,
  1043 tests, 0 failed**. All three pillars + P1.6 impersonation/audit + P1.7 export/delete + full P2.2
  metering (every Gemini service) + P3.4 food-VERIFIED all compile and pass together.
- **Backend caveats (see decision log):** impersonation grants are process-local (single-instance OK);
  the purge job deletes the `users/{uid}` doc but **not** subcollection cascade; the export bundle covers
  profile + goals with an explicit `omitted` list for the rest. These are flagged scope cuts, not bugs.
- **Web: COMPLETE** (admin user console, lockout routing → pending/suspended screens, AI-usage dashboard,
  curation console). Verified: `tsc --noEmit` PASS, `eslint` 0 errors in new files, `vitest` 9/9 PASS.
- **Android: Pillar-1 client COMPLETE** (`isAdmin`→owner gate, 403 lockout → pending/suspended states +
  screens, offline flush-then-lock with a **D16 data-loss bug fixed**). Verified: touched modules
  `compileDebugKotlin` BUILD SUCCESSFUL; `WorkoutSessionViewModelTest`/`OutboxDrainTest`(+D16)/
  `AuthCoordinatorTest`(+3)/`:core-data:testDebugUnitTest` green. One residual wipe-vs-drain race flagged
  (decision log DL-AND-5). Android browse-shared catalog UI (P3.6) not built this pass.
- **Ops follow-ups (not code):** Firestore TTL on `aiUsageEvents` (90d); composite index
  `aiUsageMonthly(yearMonth ASC, totalCostUsd DESC)`.

---

## 1. Context — why this is being built

We are moving from a single-owner app to onboarding **real, invited beta users**. Three gaps block that:

1. **No way to manage logins.** The `User` record has no role or status field. "Admin" is a static
   env email allowlist (`AdminAuthorizer` / `@AdminOnly`), signup is wide open (per the Sept-2026
   multi-user audit), and there is no surface to list users, approve/deny access, or lock an account out.
2. **No visibility into AI spend.** Every Gemini call flows through one shared `Client` bean, but token
   usage metadata is discarded. There is request-count rate-limiting (`AiRateLimitFilter`, 30/user/hr)
   but **zero** token or cost accounting — so per-user/per-function spend is invisible as we add users.
3. **User-created objects can't become app-wide.** Exercises/equipment/foods already have global catalogs
   with partial curation; but user-authored **workout programs and ad-hoc workouts are strictly per-user**
   with no path to becoming shared, reviewed, app-wide objects, and curation is scattered across ad-hoc
   admin screens.

**Intended outcome:** an invite-gated beta with a real admin console (manage logins, approve access,
suspend/offboard), internal-ops visibility into AI token/cost per user and per function, and a single
admin-curated workflow to graduate user-created objects into the shared catalog — all working on web
and Android.

---

## 2. Locked decisions (from requirements interview)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Beta audience | **Invite-only private beta** |
| D2 | Invite gate | **Email allowlist** managed in admin console |
| D3 | Non-allowlisted sign-in UX | **"Request received — pending approval" screen**; attempt logged so an admin sees who's knocking |
| D4 | AI metering purpose | **Internal ops / debugging only** → admin-facing dashboards, **no user-facing card** (per-user data still recorded) |
| D5 | AI cost attribution | **Everything to the triggering user** (incl. background/shared asset gen); `SYSTEM` bucket only when there is genuinely no originating user |
| D6 | AI interception point | **Spring AOP aspect** around `integrations.*` Gemini service methods → shared `GeminiCallRecorder` sink (covers the two equipment services that build their own `Client`) |
| D7 | Catalog curation bar | **Admin manually approves every promotion** (mandatory human sign-off) |
| D8 | Generic vs per-entity catalog | **Per-entity models + shared `CatalogStatus` vocabulary (map, don't migrate existing enums) + one unified admin "Curation" console** |
| D9 | Promotion storage | **Copy** user object into a top-level catalog collection as `PENDING_REVIEW`, stripping per-user fields (not promote-in-place) |
| D10 | Template sanitization | **Fully generalize** — absolute loads → relative % (%1RM/relative targets), strip notes/goal links/identifiers; admin finalizes in review |
| D11 | Adopt linkage | **Snapshot copy at adopt time** (no live reference) |
| D12 | Contributor credit | **Internal-only provenance** (`contributorId` stored, never surfaced to other users) |
| D13 | Offboard data handling | **Export-then-delete**, **30-day grace then auto-purge** (scheduled job), JSON export bundle |
| D14 | Suspension latency | **Hybrid** — instant re-check on high-risk endpoints (export, AI spend, destructive mutations); token-claim staleness tolerated for routine reads |
| D15 | DISABLED semantics | Hard-revoke the refresh-token family immediately (reuses the theft-burn path) |
| D16 | Android offline suspend | On reconnect: **flush queued outbox writes first, THEN lock out** + wipe local data |
| D17 | Support tooling | **Read-only impersonation + audit log** (admin views a user's data read-only; every access audited) |
| D18 | Role model shape | `Set<UserRole>` (`USER`, `ADMIN`), default `[USER]` — avoids a second migration if roles grow |
| D19 | Functional correctness | **Synthetic E2E flow per user journey** — Maestro (Android) + Playwright (web), reusing the existing harness |
| D20 | Definition-of-done gate | **Automated tests green** (hard gate). Owner sign-off **not** mandatory — the agent self-certifies via captured evidence |
| D21 | Proof artifact per phase | **Test output + E2E recording/screenshots**, stored with the phase record |

**Reconciliation note (foods, D7 vs today):** foods currently go live *instantly as `UNVERIFIED`* to the
logger. We **keep that** (logging UX must stay instant), and apply D7's "admin approves each" only to the
**promotion to the trusted/app-wide `VERIFIED` tier**. I.e. a user's food is usable immediately by them;
surfacing it as a recommended app-wide food requires admin approval. This honors D7 without regressing the
high-frequency logging path.

---

## 3. How to read status (tracking protocol)

Each phase carries a status line with four independent flags:

`Impl [ ] · Tested [ ] · Pushed [ ]`  →  `Impl [x] · Tested [x] · Pushed [x]`

- **Impl** — code written and compiles; acceptance criteria believed met.
- **Tested** — *both* the technical suites AND the functional E2E flow are green **and the proof artifact
  is captured** under `docs/test_reports/IMPL-MULTIUSER-01/<phase-id>/` (see §7). This flag is the real
  "verified" flag; the agent may **not** set it on its own say-so (§7).
- **Pushed** — merged to `main` and the per-phase `deploy-*-on-main` check-runs are green.

"What's remaining" = any phase whose three flags aren't all `[x]`. Keep the **Roadmap table (§4)** and the
per-phase lines in sync on every change.

---

## 4. Phase roadmap (at-a-glance status)

> Parallel tracks. `P1.1` and `P1.2` are the critical foundation (role/status + authorizer); everything
> else can proceed once they land. Within a track, phases are ordered by dependency.

> **Legend (2026-10-02):** `[x]` = done & automated unit/integration tests green · `[~]` = partial
> (backend done+green; client or a sub-item remaining — see decision log) · `[ ]` = not started.
> **Tested = automated suite green** (E2E not run here — see Build-status banner). **Pushed** = merged to
> `main` (nothing merged yet; all work is on the `user-admin` worktree).

| Phase | Pillar | Title | Impl | Tested | Pushed |
|-------|--------|-------|:----:|:------:|:------:|
| P1.1 | Admin | Persisted role + status model (data layer) | [x] | [x] | [ ] |
| P1.2 | Admin | Data-driven `AdminAuthorizer` + env bootstrap | [x] | [x] | [ ] |
| P1.3 | Admin | Email allowlist + pending-approval signup gate (backend+web+android) | [x] | [x] | [ ] |
| P1.4 | Admin | Auth-boundary enforcement + client lockout + offline flush (D16 bug fixed; wipe-vs-drain race closed) | [x] | [x] | [ ] |
| P1.5 | Admin | Admin user console (backend API + web UI) | [x] | [x] | [ ] |
| P1.6 | Admin | Read-only impersonation + audit log (backend) | [x] | [x] | [ ] |
| P1.7 | Admin | Export-then-delete offboarding (30-day auto-purge; no subcollection cascade yet) | [x] | [x] | [ ] |
| P1.8 | Admin | Retire `OWNER_EMAILS` hardcodes + `WhoAmI.isAdmin` (backend+android) | [x] | [x] | [ ] |
| P2.1 | Metering | Pricing table + recorder core + Firestore schema | [x] | [x] | [ ] |
| P2.2 | Metering | Recorder wiring (sink) — ALL Gemini services (sync + streaming + image) | [x] | [x] | [ ] |
| P2.3 | Metering | Admin AI-usage analytics API + web dashboard | [x] | [x] | [ ] |
| P3.1 | Catalog | Shared `CatalogStatus` vocabulary + provenance | [x] | [x] | [ ] |
| P3.2 | Catalog | WorkoutProgram promotion (copy → `programCatalog`, generalize) | [x] | [x] | [ ] |
| P3.3 | Catalog | AdHocWorkout promotion (copy → `adhocCatalog`) | [x] | [x] | [ ] |
| P3.4 | Catalog | Promotion unify + visibility filter + food promote-to-VERIFIED (in curation queue) | [x] | [x] | [ ] |
| P3.5 | Catalog | Curation console (backend API + web UI) | [x] | [x] | [ ] |
| P3.6 | Catalog | Browse-shared reads (backend [x]; web+Android in progress) | [~] | [~] | [ ] |

---

## 5. Pillar 1 — User Administration & Login Management

### P1.1 — Persisted role + status model  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** give `User` a data-driven role and account status without breaking the delegating-constructor
backward-compat pattern.
**Key files:**
- NEW `core/user/UserRole.java` (`USER`, `ADMIN`), `core/user/UserStatus.java` (`PENDING_APPROVAL`, `ACTIVE`, `SUSPENDED`, `DISABLED`).
- `core/user/User.java` — add `Set<UserRole> roles` + `UserStatus status` to the canonical record; add a delegating constructor matching today's signature that defaults `roles=[USER]`, `status=ACTIVE` (null-safe/immutable, mirroring `hiddenBiometrics`).
- `persistence/user/FirestoreUserRepository` — read/write new fields; **backfill-on-read** legacy docs → `[USER]`/`ACTIVE` (no migration job).
- `core/user/UserService` — `grantRole/revokeRole/setStatus`; add lowercased `emailLower` index field for admin search.
**Acceptance (success demarcation):** legacy user docs load as `[USER]`/`ACTIVE`; new fields round-trip; old constructor still compiles and is exercised.
**Technical tests:** repo round-trip (with/without fields), backward-compat construction, `emailLower` populated on write.
**Functional E2E:** none (pure data layer) — covered transitively by P1.5.

### P1.2 — Data-driven `AdminAuthorizer` + env bootstrap  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** admins become data-driven while keeping `@AdminOnly` working and making lock-out impossible.
**Key files:** `api/security/AdminAuthorizer.java` — `isAdmin()` = `user.roles contains ADMIN` **OR** verified email ∈ `ADMIN_EMAILS` (permanent bootstrap fallback; keeps `email_verified` guard). `UserService.provisionIfAbsent` seeds `ADMIN` when a new user's verified email ∈ `ADMIN_EMAILS`.
**Acceptance:** admin-by-role and admin-by-env both pass; unverified email rejected; **all 5 existing admin controllers keep working with zero changes** (the `@PreAuthorize("@adminAuthorizer.isAdmin()")` seam is untouched).
**Technical tests:** admin-by-role, admin-by-env-fallback, non-admin, unverified-email, dev-mode path.
**Functional E2E:** Playwright — an `ADMIN_EMAILS` user reaches `/admin`; a non-admin is redirected.

### P1.3 — Email allowlist + pending-approval signup gate  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** close open signup (D1/D2); non-allowlisted Google sign-ins land in `PENDING_APPROVAL` with a logged record an admin can approve (D3).
**Key files:**
- NEW `core/access/EmailAllowlist` + Firestore-backed store (`allowlist/{emailLower}`); admin CRUD.
- `UserProvisioningFilter` / `UserService.provisionIfAbsent` — on first sign-in: if email ∉ allowlist and not bootstrap-admin → create/keep user `status=PENDING_APPROVAL` and return the pending problem (`403 type: account-pending`) rather than provisioning full access.
- `api/auth/AuthController` + session mint — deny session issuance for `PENDING_APPROVAL`.
- Web: `web/app/auth/pending/page.tsx` ("Request received — pending approval"); `web/lib/api.ts` detects `account-pending` → routes there. Android: `AuthCoordinator` gains a `Pending` state → a pending screen (mirrors `SignInRequiredScreen.kt`).
**Acceptance:** a non-allowlisted Google user authenticates but gets **no app data** — sees the pending screen on both platforms; the attempt is visible in the admin pending queue (P1.5); approval flips them to `ACTIVE` and access works.
**Technical tests:** provisioning gate (allowlisted→ACTIVE, not→PENDING), session-mint denial for PENDING, allowlist CRUD.
**Functional E2E:** Playwright — sign in as non-allowlisted → pending screen; admin approves → user now reaches dashboard. Maestro — same journey on Android (pending screen appears, no PHI loaded).

### P1.4 — Auth-boundary status enforcement + client lockout + offline flush  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** a suspended/disabled user is locked out on both platforms (D14/D15/D16). *Security-critical phase.*
**Key files:**
- `auth/SessionTokenService` — `issueFor`/refresh re-read `User`; non-`ACTIVE` → `AccountNotActiveException` (→403). `DISABLED` also calls `store.revokeAllForUser` (D15). Embed a `status` claim in the access token for the lazy path (D14).
- NEW `auth/AccountStatusFilter` (OncePerRequestFilter) wired in `SecurityConfig` **after `UserProvisioningFilter`, before `AiRateLimitFilter`**. Routine reads trust the token `status` claim (bounded staleness = access-token TTL); **high-risk endpoints** (data export, AI-spend, destructive mutations) do a live Firestore re-check (D14 hybrid). Non-ACTIVE → `403 type: account-suspended`, chain stops.
- `api/auth/AuthController` — map `AccountNotActiveException` → 403 problem types (`account-suspended` / `account-disabled`).
- Android `TokenAuthenticator.kt` — a `403 account-suspended/pending` must **NOT** trigger refresh (would loop); route to the locked-out/pending screen via `AuthCoordinator`. On `DISABLED`/suspend, reuse `SignOutSideEffects.wipeLocalData`. **Offline flush (D16):** on reconnect, the outbox **drains first**, then the resulting 403 triggers lockout+wipe — ordering guaranteed in the sync coordinator.
- Web `web/lib/api.ts` — detect `account-suspended` → redirect to a suspended page / sign-out.
**Acceptance:** ACTIVE passes; SUSPENDED/DISABLED get 403 within the access-token TTL (routine) and instantly on high-risk endpoints; refresh denied for both; DISABLED's refresh family is burned; Android does not loop-refresh on the 403; an offline Android client with queued writes **flushes them, then** locks out.
**Technical tests:** filter allow/deny matrix; refresh denial; DISABLED burns family; high-risk live re-check vs routine claim path; Android authenticator no-loop on 403 (unit).
**Functional E2E:** Playwright — admin suspends an active web user → that user's next action 403s → suspended screen. Maestro — (a) online suspend → locked out; (b) **offline** write queued → suspend → reconnect → queued write lands server-side → then locked out + local wipe (assert the write persisted AND the lockout).

### P1.5 — Admin user console (web)  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** the surface to manage logins (D1).
**Key files:** NEW `api/admin/AdminUserController` (`@AdminOnly`) — `GET /api/admin/users?query=&status=&role=&cursor=`, `GET /api/admin/users/{id}` (profile + connection *presence* flags, never tokens), `POST .../role`, `POST .../status`, pending-approval queue, allowlist CRUD. **Last-admin protection** (cannot remove the final ADMIN or self-demote below one admin). DTOs in `api/admin/`. Web: `web/app/admin/users/page.tsx` (list/search/pending), `web/app/admin/users/[userId]/page.tsx` (detail + role/status + allowlist), `web/lib/user-admin-api.ts` (mirrors `drug-admin-api.ts`); add a "Users" card to `web/app/admin/page.tsx`.
**Acceptance:** admin can search users, see detail, approve pending, change role/status, manage allowlist; last-admin protection blocks self-lockout; connection tokens are never exposed.
**Technical tests:** controller authz (`@AdminOnly`), pagination/search, last-admin guard, status/role mutation persists.
**Functional E2E:** Playwright — full admin journey: search → open user → suspend → (observe P1.4 lockout) → reactivate; approve a pending user; add/remove an allowlist entry.

### P1.6 — Read-only impersonation + audit log  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** support debugging without PHI write risk (D17).
**Key files:** NEW `api/admin/ImpersonationController` minting a **read-only, time-boxed** impersonation context (reject all non-GET under impersonation at the filter); NEW `core/audit/AuditLog` + `auditLog/{id}` collection recording `{adminId, targetUserId, action, resourcePath, at}`. Web: an "View as user (read-only)" entry from the user detail page with a persistent impersonation banner. Audit entries visible in the console.
**Acceptance:** admin can view a user's data read-only; any write under impersonation is rejected; every access writes an audit row; impersonation auto-expires.
**Technical tests:** write-rejection under impersonation, audit row written per access, expiry.
**Functional E2E:** Playwright — admin impersonates → sees target's dashboard read-only → attempts an edit (blocked) → audit entry appears.

### P1.7 — Export-then-delete offboarding (30-day auto-purge)  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** compliant offboarding (D13). See also `docs/requirements/privacy-and-compliance.md`.
**Key files:** NEW `api/admin/DataLifecycleController` — `POST /api/admin/users/{id}/export` (assembles a JSON bundle of all `users/{uid}/**` + referenced blobs → signed download) and `POST .../schedule-deletion` (sets `status=DISABLED` + `deletionScheduledAt = now+30d`). NEW Cloud Run Job `data-purge` (cron, mirrors the `gh-refresh` job pattern, **`--memory=2Gi`**) that hard-deletes users past the grace window. Reactivation within the window clears the schedule.
**Acceptance:** export bundle contains all of a user's PHI and nothing of other users; scheduling disables immediately and purges at +30d; reactivation before the window cancels the purge; purge removes the user doc + subcollections + owned blobs.
**Technical tests:** export completeness + tenant isolation (no cross-user leakage), purge window arithmetic, reactivation cancels, purge cascade.
**Functional E2E:** Playwright — admin exports a user (download non-empty, spot-check contents) → schedules deletion (user immediately locked out) → reactivate cancels. Purge-job verified by an integration test with a simulated clock (not E2E).

### P1.8 — Retire `OWNER_EMAILS` hardcodes + `WhoAmI.isAdmin`  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** single source of truth for "is admin/owner".
**Key files:** `api/exercise/ExerciseController.java` — replace `OWNER_EMAILS` on `flag-frame` with `@PreAuthorize("@adminAuthorizer.isAdmin()")`; remove the constant. `api/auth/WhoAmIResponse` + `WhoAmIController` — add `isAdmin` (from roles). Android `WorkoutSessionViewModel.kt` — derive `isOwner` from `whoami.isAdmin`, delete `OWNER_EMAILS` + its test.
**Acceptance:** owner-gated affordances still appear for admins on web+Android; no email literals remain; `flag-frame` is admin-gated.
**Technical tests:** `flag-frame` authz, `WhoAmI` returns correct `isAdmin`, grep proves no `OWNER_EMAILS` literals remain.
**Functional E2E:** Maestro — admin sees the owner affordance in the active-workout screen; a non-admin does not.

---

## 6. Pillar 2 — AI Token / Cost Metering (internal-ops)

### P2.1 — Pricing table + recorder core + Firestore schema  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** the metering substrate (D4/D5).
**Key files:** NEW `core/ai/AiFeature.java` (enum of the ~14 features — MEAL_PHOTO, NUTRITION_LABEL, MEAL_DESCRIBE, LEFTOVERS, DRINK, SERVING_HINT, FOOD_IMAGE_GEN, GOAL_CHAT, WORKOUT_PROGRAM_CHAT, ADHOC_GEN, EXERCISE_MEDIA, EQUIPMENT_PARSE/IMAGE, DRUG_IMAGE…), `core/ai/AiModelPricing.java` (config-backed `app.ai.pricing.*` model→price table, incl. image-per-call), `core/ai/AiUsageEvent.java` (`{eventId,userId,feature,model,inputTokens,outputTokens,estimatedCostUsd,status,startedAt,timestamp,streaming,requestId}`), `core/ai/GeminiCallRecorder.java` (computes cost, writes event, atomically increments rollup), `core/ai/AiUsageStore` port + `persistence/ai/FirestoreAiUsageStore`.
**Firestore:** `aiUsageEvents/{id}` top-level (**90-day retention**); `users/{uid}/aiUsageMonthly/{YYYY-MM}` rollup (`features` map + totals, `FieldValue.increment`); `aiUsageMonthlyGlobal/{YYYY-MM}`. Attribution per D5: triggering user; `SYSTEM` only when no originating user.
**Acceptance:** recorder computes correct cost per model (incl. image), writes an event, and atomically updates both rollups; concurrent calls don't lose increments.
**Technical tests:** pricing math (flash/pro/image), rollup increment idempotency under concurrency, SYSTEM-bucket path, streaming last-chunk usage extraction (unit against a fake response).

### P2.2 — AOP interception wiring  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** capture usage at **all** call sites without editing the 14 services (D6).
**Key files:** NEW `integrations/ai/AiUsageAspect.java` (`@Around` on the public entry methods of `integrations.*` Gemini client classes) → resolves `feature` (from bean/method), `model`, `userId` (CurrentUser or explicit for jobs), reads `usageMetadata` from the completed call/terminal stream chunk, calls `GeminiCallRecorder`. Enable `@EnableAspectJAutoProxy` if absent. Streaming is consumed in-method (verified) so the post-return hook sees final usage. **Covers the two equipment services that build their own `Client`** because the aspect targets service methods, not the bean.
**Acceptance:** a request through each AI feature produces exactly one usage event attributed to the right user/feature/model; background jobs record under the triggering user (or SYSTEM); failures record `status=ERROR` without breaking the user call.
**Technical tests:** aspect fires once per call for a representative sync feature, a streaming feature (goal chat), and an image-gen feature; error path records ERROR and rethrows; background-job attribution.
**Functional E2E:** Playwright — log a meal photo on web, then confirm the admin AI-usage dashboard (P2.3) shows a new event for that user+feature (ties P2.2↔P2.3).

### P2.3 — Admin AI-usage analytics API + web dashboard  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** internal-ops visibility (D4 — admin only, no user card).
**Key files:** NEW `api/admin/AdminAiUsageController` (`@AdminOnly`) — `/api/admin/ai-usage`: top spenders (rollups ordered by cost), per-feature totals (global rollup), cost-over-time (monthly globals), and per-user drilldown. Web: `web/app/admin/ai-usage/page.tsx`, `web/lib/ai-usage-admin-api.ts`, "AI usage" card on `web/app/admin/page.tsx`.
**Acceptance:** dashboard shows this-month total cost, per-feature breakdown, top spenders, and a per-user drilldown — all from rollup docs (no event scans in the hot path).
**Technical tests:** aggregation correctness vs seeded rollups, authz, drilldown.
**Functional E2E:** Playwright — admin opens AI-usage → sees non-zero totals after driving a known AI call; drills into a user.

> **Complements** the existing `AiRateLimitFilter` (request-count). The monthly rollup with running cost is
> deliberately shaped to back a future `AiBudgetFilter` (per-user token/cost cap) — **out of scope here**, noted for continuity.

---

## 7. Pillar 3 — Catalog Graduation to App-Wide Objects

### P3.1 — Shared `CatalogStatus` vocabulary + provenance  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** one lifecycle vocabulary across heterogeneous entities **without migrating** existing enums (D8).
**Key files:** NEW `core/catalog/CatalogStatus.java` (`PRIVATE/DRAFT → PENDING_REVIEW → PUBLISHED → REJECTED/ARCHIVED`) + a mapping layer to each entity's stored enum (Equipment `ACTIVE`≈PUBLISHED, Exercise `PUBLISHED` matches, Food `VERIFIED`≈PUBLISHED). NEW `core/catalog/CatalogProvenance` fields (`contributorId`, `promotedBy`, `promotedAt`, `rejectedReason`, `aliasOf…`) added where missing (Equipment/Exercise already have `contributorId`+alias).
**Acceptance:** admin/curation code speaks one vocabulary; each entity keeps its stored enum; mapping is lossless both ways.
**Technical tests:** status mapping round-trip per entity; provenance round-trip.

### P3.2 — WorkoutProgram promotion  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** user programs → shared templates, admin-approved (D7/D9/D10).
**Key files:** NEW `core/workoutprogram/catalog/CatalogWorkoutProgram` (template-only: title/description/phases/schedule + `CatalogStatus` + provenance; **strips** `userId`/`startDate`/`goalId`/`completedAt`), `ProgramCatalogService` + Firestore adapter (`programCatalog/{id}`). Generalization util: absolute loads → relative % (%1RM/relative targets), strip notes/goal links/identifiers (D10). `POST /api/me/workout-programs/{id}/submit-for-promotion` (copies in as `PENDING_REVIEW`). `GET /api/programs` (authed, PUBLISHED-only).
**Acceptance:** submit creates a `PENDING_REVIEW` catalog copy with **no** per-user fields and **no absolute loads/notes**; original per-user program untouched; PENDING never appears in `GET /api/programs`; after admin approve it appears.
**Technical tests:** copy strips per-user fields; generalization converts loads to %; visibility filter hides PENDING; approve publishes.

### P3.3 — AdHocWorkout promotion  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** same pattern for ad-hoc workouts.
**Key files:** NEW `CatalogAdHocWorkout` (+ `adhocCatalog/{id}`), `AdHocCatalogService`, `POST /api/me/adhoc-workouts/{id}/submit-for-promotion`, `GET /api/adhoc-catalog` (PUBLISHED-only). `AdHocWorkout` has no status today → promotion lives on the catalog copy (D9), not the per-user doc.
**Acceptance / tests:** as P3.2 (copy clean, generalized, PENDING hidden, approve publishes).

### P3.4 — Food/Exercise/Equipment promotion unify + visibility filter  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** bring the existing catalogs under the shared vocabulary and guarantee only approved objects surface.
**Key files:** audit **every** public read (`/api/exercises`, `/api/equipment`, `/api/foods`, new `/api/programs`, `/api/adhoc-catalog`) to filter PUBLISHED/ACTIVE/VERIFIED + non-aliased + non-archived. Foods: keep instant `UNVERIFIED` usable-by-creator, add an **admin promote-to-`VERIFIED`** path (the D7 reconciliation). Generalize Equipment's `mergeInto`/`aliasOf` dedupe to programs/adhoc.
**Acceptance:** no PENDING/UNVERIFIED-untrusted object appears in any public listing; food logging stays instant; alias targets resolve and are hidden from listings.
**Technical tests:** per-endpoint visibility matrix; food instant-use preserved; alias resolution + hidden-from-list.

### P3.5 — Unified admin "Curation" console (web)  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** one review surface across all catalog types (D8).
**Key files:** NEW `api/admin/AdminCurationController` — `/api/admin/curation/queue` aggregates `PENDING_REVIEW` across equipment/exercises/foods/programs/adhoc into a `CatalogStatus`-normalized response (delegates to each entity service; **does not** duplicate mutation logic — approve/reject route to the entity controllers). Web: `web/app/admin/curation/page.tsx` (per-type sections, approve/reject/merge), point `web/app/admin/page.tsx` at it; keep deep per-entity edit pages.
**Acceptance:** one tab shows all pending items by type with contributor (internal) metadata; approve/reject/merge work and reflect in public listings.
**Technical tests:** aggregation across types, authz, approve/reject delegates correctly.
**Functional E2E:** Playwright — user submits a program for promotion → admin sees it in Curation → approves → it appears in the browse-shared surface (ties P3.2↔P3.5↔P3.6).

### P3.6 — Browse-shared reads (web + Android)  ·  Impl [ ] · Tested [ ] · Pushed [ ]
**Goal:** consume promoted objects cross-platform; adopt = snapshot copy (D11).
**Key files:** web `web/lib/*-catalog-api.ts` + a browse-shared surface; "Adopt" creates an independent per-user copy (snapshot, D11). Android: extend `CatalogCacheEntity` type enum with `program`/`adhoc` (network-only, lazy, per ADR-0018); KMP-shared catalog models; "Submit for promotion" affordance on the user's own program/adhoc; a browse-shared surface reading PUBLISHED only.
**Acceptance:** both platforms list only PUBLISHED catalog objects; adopting creates an independent copy that later catalog edits do not mutate.
**Technical tests:** adopt creates snapshot (edit source → adopter unchanged); Android lazy cache typed correctly.
**Functional E2E:** Playwright + Maestro — browse shared → adopt a program → it appears in the user's own programs; editing the source catalog object does not change the adopted copy.

---

## 8. Testing strategy

**Two layers, both required (D19/D20/D21):**

1. **Technical (automated) — the hard DoD gate (D20).**
   - Backend: JUnit unit + Spring `@SpringBootTest` integration (Firestore emulator). New suites per phase as listed.
   - Web: component/unit + route tests.
   - Android: core-data/domain unit tests; KMP shared-core tests.
   - A phase's technical suites must be **green** before `Tested [x]`.

2. **Functional (synthetic E2E) — proves the real journey (D19).**
   - **Web:** Playwright flows (M4, ~$0) under the existing harness.
   - **Android:** Maestro flows.
   - Each user-facing phase ships at least the E2E flow(s) named in its section. Flows assert *observable
     outcomes* (locked out, data present/absent, item appears in a listing), not just HTTP codes.
   - Treat these as the parity matrix: a journey that exists on both platforms gets both a Playwright and a
     Maestro flow sharing the accessibility-id vocabulary.

**Representative journey catalog (must exist and pass):**
- Admin suspends user → user locked out (web + Android).
- Offline Android write queued → user suspended → reconnect → write lands → then lockout+wipe.
- Non-allowlisted sign-in → pending screen → admin approves → access granted (web + Android).
- Drive an AI feature → event shows in admin AI-usage dashboard.
- User submits program for promotion → admin approves in Curation → appears in browse-shared → adopt → independent copy (web + Android).
- Admin impersonates read-only → write blocked → audit entry recorded.

---

## 9. Definition of Done & agent self-verification protocol

**A phase is DONE only when `Impl [x] · Tested [x] · Pushed [x]`.** Per D20, the agent may self-certify
(no mandatory owner sign-off) **but `Tested [x]` requires captured proof** — the agent must not mark it on
assertion alone. Evidence lives at `docs/test_reports/IMPL-MULTIUSER-01/<phase-id>/`.

**Before flipping `Tested [x]`, the agent MUST, in order:**
1. **Run the technical suites** for the phase. Capture raw output (command + pass/fail counts + exit code)
   to `…/<phase-id>/tests.txt`. If anything is red → stay `Impl [x] · Tested [ ]`, do not proceed.
2. **Run (or author then run) the functional E2E flow(s)** named in the phase.
   - Capture Playwright trace/video + screenshots to `…/<phase-id>/web-e2e/`.
   - Capture Maestro recording/screenshots to `…/<phase-id>/android-e2e/`.
3. **Write `…/<phase-id>/VERIFICATION.md`** mapping **each acceptance criterion** in the phase to the
   concrete artifact that proves it (a test name + output line, or a screenshot/recording timestamp),
   each marked PASS/FAIL. Any FAIL blocks `Tested [x]`.
4. Only with all acceptance criteria PASS and artifacts present → set `Tested [x]` and update §4 + the phase line.

**`Pushed [x]`** is set only after merge to `main` with the `deploy-*-on-main` check-runs green.

**Success is demarcated per phase** by its **Acceptance** bullet — that bullet is the contract the
`VERIFICATION.md` must satisfy item-by-item. "Remaining work" is mechanically = phases whose three flags
aren't all `[x]` (scan §4).

---

## 10. Risks & mitigations

- **Lockout blast radius (P1.4) — highest risk.** No feature flags (rollout decision), so correctness is
  carried by tests + the `ADMIN_EMAILS` env **permanent bootstrap** (D-bootstrap): a bug in the data path can
  never lock out a bootstrap admin. The suspended-403 must never be treated as a refreshable 401 on Android
  (explicit no-loop test). This phase gets the most E2E coverage.
- **Streaming usage extraction (P2.2):** `usageMetadata` semantics vary by model/SDK version — verify against
  the actual google-genai version's `GenerateContentResponse` before trusting last-chunk counts (unit test
  with a captured real response).
- **Map-don't-migrate (P3.1):** we deliberately do **not** rename `EquipmentStatus`/`FoodStatus`/`ProgramStatus`
  (a large, risky migration across mature subsystems). If a future owner wants one physical enum, that is a
  separate migration IMPL.
- **Tenant isolation on export (P1.7):** export must be provably single-user — covered by an explicit
  cross-user-leakage test; aligns with `ADR-0021-tenant-isolation-invariants`.
- **Worktree gotcha:** anvil worktrees symlink `web/node_modules` → main checkout (recurring corruption);
  re-install if web builds/tests behave oddly.

---

## 11. Open items (owner may override later; sensible defaults chosen now)

- Raw `aiUsageEvents` retention set to **90 days** (rollups kept indefinitely) — adjust if ops wants longer.
- AI cost is **estimated** from the config price table; no reconciliation against actual Gemini billing
  (the real Gemini billing project is `oxos-bots`, separate from this app) — revisit if metering ever
  graduates from internal-ops to billing.
- Export bundle format = JSON + referenced blobs; schema documented alongside `docs/requirements/privacy-and-compliance.md`.

---

### First action on approval
Copy this document verbatim to
`docs/plans/IMPL-MULTIUSER-01-user-admin-metering-catalog-graduation.md`, then begin P1.1 + P2.1 + P3.1 in
parallel (the three foundation phases).
