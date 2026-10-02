# IMPL-MULTIUSER-01 — Decision Log

Running log of implementation decisions made autonomously during the build (per the owner's
"implement everything, don't stop to ask, log every decision" directive, 2026-10-02). Each entry:
what was decided, why, and any alternative rejected. Review these after implementation to decide
what to tweak.

Legend: **DL-n** = decision number. Phase refs are to
`IMPL-MULTIUSER-01-user-admin-metering-catalog-graduation.md`.

---

## Build / process

### DL-1 — Backend-first, verify with `./gradlew test`
The backend is the linchpin (web + Android consume its APIs). Implement and compile/test the backend
before the clients. Each pillar is compiled and its unit tests run before moving on. Alternative
rejected: implement all three platforms per phase in lockstep — too much context thrash, and clients
can't be meaningfully built before the API contract is real.

### DL-2 — Decision log lives at `docs/plans/IMPL-MULTIUSER-01-decision-log.md`
Matches the repo's existing `IMPL-*-decision-log.md` convention (e.g. `IMPL-DELOAD-01-decision-log.md`).

---

## Pillar 1 — User Administration  (implemented + full-suite green)

- **DL-P1-1 Role model = `Set<UserRole>` on the `User` record** (D18), default `{USER}`, with
  backfill-on-read (legacy docs → `{USER}`/`ACTIVE` via the compact constructor). Roles/status are
  written **only on create** (mirroring `createdAt`) so a routine `save()` upsert can never clobber an
  admin's role or a suspension; admin mutations go through dedicated `updateRoles`/`updateStatus`.
- **DL-P1-2 New `UserRepository` port methods are `default`**, not abstract. Many test fakes implement
  the port; defaulting (`updateStatus`/`updateRoles` throw unsupported, `search`/`findAdmins` return
  empty) keeps them compiling. The Firestore impl and `InMemoryUserRepository` override with real logic.
- **DL-P1-3 `AdminAuthorizer` is role-driven OR env-bootstrap.** `isAdmin()` = user has ADMIN role **or**
  verified email ∈ `ADMIN_EMAILS` (`AdminBootstrap`). The `email_verified` guard and the
  `@AdminOnly → @PreAuthorize("@adminAuthorizer.isAdmin()")` seam are unchanged, so all five existing
  admin controllers work with zero edits.
- **DL-P1-4 `UserStatus = {PENDING_APPROVAL, ACTIVE, SUSPENDED, DISABLED}`.** `PENDING_APPROVAL` folds the
  invite gate + "who's knocking" (D2/D3) into the status model — no separate pending store. A
  non-allowlisted Google sign-in is provisioned `PENDING_APPROVAL` and appears in the admin pending queue.
- **DL-P1-5 Invite gate = email allowlist** (`allowlist/{emailLower}` docs). Provisioning computes status
  from `allowlist ∪ bootstrap`; non-allowlisted → `PENDING_APPROVAL` (no app data).
- **DL-P1-6 ⚠️ DEVIATION FROM SPEC D14 (hybrid) — chose cache-backed read-through.** `AccountStatusFilter`
  resolves status via `UserRepository.findById`, which is `@Cacheable` by userId and **evicted on any
  status change**. So enforcement is a cache hit on the hot path yet bites on the *very next* request after
  suspension — i.e. "instant" without the token-claim staleness the spec's hybrid contemplated. The
  `status` claim is still stamped on access tokens for a future claim-only fast path. **Simpler + stronger
  than the spec's hybrid; flagged for your review.**
- **DL-P1-7 Bootstrap admin is lockout-proof.** `AccountStatusFilter` short-circuits (allows) any
  `ADMIN_EMAILS` email regardless of stored status, and last-admin protection is disabled whenever any env
  bootstrap admin exists (the env admin can always get back in). This is the no-feature-flags rollout
  safety net.
- **DL-P1-8 ✅ RATIFIED (owner, 2026-10-02) — SUSPENDED also burns the refresh-token family** (extends
  spec D15, which was DISABLED-only). A suspension kills live sessions immediately rather than waiting out
  the access-token TTL. Both go through `RefreshTokenStore.revokeAllForUser`. **Kept by owner decision.**
- **DL-P1-9 Refresh/exchange deny non-active with 403 (not 401).** `AccountNotActiveException` → 403 +
  `X-Account-Status` header, so clients route the lockout (pending screen vs suspended page) instead of
  looping on interactive sign-in (which a 401 would trigger on Android).
- **DL-P1-10 Foot-gun guards.** `AdminUserController` refuses an admin suspending/disabling *their own*
  account; `UserService` refuses deactivating/un-admining the last active admin (`LastAdminException` →
  409). dev-login synthetic identities with no user doc are treated ACTIVE so UAT is unaffected.
- **DL-P1-11 `flag-frame` → `@AdminOnly`** (removed `ExerciseController.OWNER_EMAILS`); `WhoAmIResponse`
  gained `isAdmin`, computed via the shared `AdminAuthorizer` — one source of truth for admin affordances
  on web + Android.
- **DL-P1-12 `AccountStatusFilter` fails OPEN** on an unresolvable/missing user doc (blocks only on an
  explicit non-active status) to avoid accidental mass lockouts if provisioning ever races.
- **DL-P1-6 (impersonation + audit) ✅ DONE.** `ImpersonationService` mints short-TTL (15 min) **opaque,
  read-only** grants `{adminId,targetUserId,expiresAt}`; `ImpersonationController` (`@AdminOnly`) exposes
  `POST /start`, `GET /view/users/{id}` (token via `X-Impersonation-Token`), `GET /audit`, and a
  `POST /write-probe` that always rejects (makes the read-only invariant testable). Every access writes a
  durable `auditLog/{id}` row (START/READ). **Read-only is structural** — only GET endpoints accept the
  token; no servlet filter, so `SecurityConfig` is untouched. ⚠️ **Limitation:** grants are process-local
  (a `ConcurrentHashMap`) — correct for the single Cloud Run deployable, but multi-instance would need
  Firestore/Redis; the *audit log* is durable regardless, so the compliance record never depends on the map.
- **DL-P1-7 (export-then-delete, D13) ✅ DONE.** `User` gained nullable `deletionScheduledAt` (14-arg
  canonical + 13-arg delegate; backfill-null). `UserService.scheduleDeletion` → DISABLED + now+30d + token
  burn (last-admin guarded); `setStatus(ACTIVE)` clears a pending schedule (reactivation within the window);
  `purgeDue(now)` hard-deletes due users, driven by a `@Profile("job-data-purge")` `DataPurgeJob`
  CommandLineRunner (mirrors the existing refresh jobs). `DataLifecycleController` (`@AdminOnly`):
  `POST .../export` + `POST .../schedule-deletion`. ⚠️ **Limitations (flagged):** (a) the purge deletes the
  `users/{uid}` doc only — **no recursive subcollection cascade** (needs the Firestore Admin bulk-delete
  API); (b) the export bundle currently covers profile (connection *presence* only, never tokens) + Goals,
  with every other subcollection listed in an explicit `omitted` field rather than silently dropped. Both
  are deliberate scope cuts, not oversights — owner to decide whether to expand before beta offboarding.

## Pillar 2 — AI Metering  (infra + tests green; rail wired for meal-photo)

- **DL-P2-1 Sink, not AOP.** `backend/build.gradle.kts` has no `spring-boot-starter-aop`/aspectjweaver and
  no `@EnableAspectJAutoProxy`. Per the no-unjustified-deps rule I did **not** add AOP. The design is a
  shared `GeminiCallRecorder` sink + `GeminiUsageExtractor` helper, invoked by a one-line hook per service.
  (Also: the services return domain objects, not the raw `GenerateContentResponse`, so an around-advice
  couldn't read `usageMetadata` anyway — the explicit hook is necessary regardless.)
- **DL-P2-2 ✅ COMPLETE — all Gemini services wired** (success + error), verified by the full suite. Sync
  extractors: MEAL_PHOTO (analyze+adjust), NUTRITION_LABEL, MEAL_DESCRIBE (extract+match), DRINK,
  SERVING_HINT, LEFTOVERS. **Streaming:** GOAL_CHAT (last non-null `usageMetadata` across the stream),
  WORKOUT_PROGRAM_CHAT (sums per-round terminal usage across the agentic loop, records once). **Image:**
  FOOD_IMAGE_GEN, EXERCISE_MEDIA, EQUIPMENT_IMAGE, DRUG_IMAGE (images=1), EQUIPMENT_PARSE (own `Client`),
  plus ADHOC_GEN (explicit-userId attribution) and EXERCISE_ENRICH. The 6 direct test constructions were
  updated. No streaming client needed the tokens=0 fallback — `GeminiUsageExtractor` last-chunk extraction
  was clean.
- **DL-P2-3** Pricing uses longest-prefix model-family match; unknown/blank model → cost 0 (never throws —
  metering must never break an AI call). Images priced per-image, added to token cost.
- **DL-P2-4** Firestore: `aiUsageEvents/{id}` (raw) + `users/{uid}/aiUsageMonthly/{yyyy-MM}` +
  `aiUsageMonthlyGlobal/{yyyy-MM}`, atomic `FieldValue.increment` on nested `features.<F>.*` counters;
  3 independent writes per event (no cross-doc txn — matches the project's existing increment rollups).
  `SYSTEM` bucket when no current user (D5). `topSpenders` via `collectionGroup("aiUsageMonthly")`.
- **DL-P2-5 OPS FOLLOW-UPS (not code):** (a) Firestore TTL policy on `aiUsageEvents` = 90 days; (b) a
  composite index `aiUsageMonthly (yearMonth ASC, totalCostUsd DESC)` for `topSpenders`; (c) optional
  `app.ai.pricing.*` overrides (defaults ship in code).

## Pillar 3 — Catalog Graduation  (backend + 17 tests green)

- **DL-P3-1 Map, don't migrate (D8).** `CatalogStatusMapping` is the only translation seam; native enums
  (`EquipmentStatus`/`ExerciseStatus`/`FoodStatus`/`ProgramStatus`) are untouched. Asymmetries (e.g.
  Equipment has no PRIVATE/ARCHIVED, Exercise has no REJECTED, Food only UNVERIFIED/VERIFIED) collapse to
  the nearest safe non-public state.
- **DL-P3-2 ✅ RATIFIED (owner, 2026-10-02) — absolute load → %1RM via Epley-inverse of the rep target.**
  Author 1RM is unknown, so the rep target is used as the implicit intensity:
  `%1RM = 100/(1 + reps/30)`, clamped [40,100] (10 reps→75%, 5→86%, 1→97%). Existing RPE/%1RM preserved;
  timed/hold → NONE. **Kept by owner decision.**
- **DL-P3-3** Generalization strips notes/loadBasis/rationale/loggedSets/dates/`locationId` (D10).
  Promotion is a copy, source untouched (D9); adopt is a snapshot via the service's own `create` (D11);
  provenance is stored but omitted from public response DTOs (D12).
- **DL-P3-4 Curation queue reads across types, mutates only what it owns** (program + adhoc approve/reject).
  Equipment/exercise approve/reject stay in their existing admin controllers (no duplication).
- **DL-P3-5 ✅ DONE — foods now in the curation queue (P3.4).** Added
  `FoodCatalogRepository.findPendingVerification` (UNVERIFIED, user-sourced, non-archived, non-drink) +
  `FoodCatalogService.listPendingVerification`/`promoteToVerified` (idempotent, stamps `verifiedAt`);
  `AdminCurationController` shows "food" rows + `POST /api/admin/curation/foods/{id}/verify`. Foods stay
  instant-live UNVERIFIED for their creator (D7 holds); promotion to the app-wide VERIFIED tier is the
  admin-gated action.
- **DL-P3-adopt** `submit-for-promotion` and `adopt` are classified **EXEMPT** in `write-contract.txt`:
  they are online-only (need the network to reach the shared catalog), so the offline outbox never replays
  them; a duplicate pending submission is harmless (admin dedupes). Adopt is non-idempotent (double-adopt
  → two copies) — acceptable for an online action, hardenable later.

## Web client (P1.3/P1.4/P1.5, P2.3, P3.5)  (typecheck + lint + unit green)

- **DL-WEB-1** Lockout keyed on the `X-Account-Status` response header (authoritative per the backend
  contract, no body read on the hot path). `apiFetch` detects 403 + that header and uses Next's
  `redirect()` — `account-pending` → `/auth/pending`, `account-suspended`/`account-disabled` →
  `/auth/suspended` — so every server component gets lockout handling for free. `AccountLockedError` is
  still exported for non-redirecting callers.
- **DL-WEB-2** One suspended screen covers suspended+disabled (neither self-recovers); pending gets its own
  "request received" screen (D3).
- **DL-WEB-3** Curation UI exposes approve/reject only for `program`/`adhoc` (what the backend
  `AdminCurationController` owns); equipment/exercise rows deep-link to their existing review consoles
  rather than duplicate mutations. Reject reason via `window.prompt` (kept lean; modal is a trivial later swap).
- **DL-WEB-4** AI-usage dashboard is server-rendered, read-only, current-UTC-month (no picker yet).
- **Files:** `web/lib/{user-admin-api,ai-usage-admin-api,curation-admin-api}.ts`; `web/app/admin/users/*`,
  `web/app/admin/ai-usage/page.tsx`, `web/app/admin/curation/page.tsx`, `web/app/auth/{pending,suspended}/page.tsx`;
  components under `web/components/admin/*`; edits to `web/lib/api.ts`, `web/app/admin/page.tsx`, `AdminSubNav.tsx`.
- **VERIFY:** `npm run typecheck` PASS, `npm run lint` PASS (0 errors in new files), `vitest run
  lib/api.test.ts lib/admin.test.ts` 9/9 PASS. (The corrupt symlinked `web/node_modules` was replaced with a
  fresh `pnpm install` in the worktree — the recurring anvil-worktree gotcha.)

## Android client (P1.3/P1.4/P1.8, D16)  (touched modules compile; touched tests green)

- **DL-AND-1** 403 lockout detected via an OkHttp **application Interceptor** (`AccountStatusInterceptor`),
  not `TokenAuthenticator` — OkHttp only invokes the authenticator on 401, so a 403 can never trigger a
  refresh loop; `TokenAuthenticator` is unchanged. An app-scoped `AccountStatusSignal` SharedFlow bridges
  HTTP→UI (avoids a Hilt cycle; mirrors the existing `SyncEngine` signal pattern).
- **DL-AND-2** New `AuthState.Pending` (no wipe — no PHI yet, tokens kept so a later approval resolves) and
  `AuthState.Suspended(disabled)` (collapses suspended+disabled; both `wipeLocalData()`). New
  `PendingApprovalScreen`/`SuspendedScreen` in `:app` (mirrors the real `SignInScreen`; the spec's
  `SignInRequiredScreen` does not exist). `LastAccountStore` left intact so the account-switch wipe guard
  still trips later.
- **DL-AND-3 (P1.8)** `isAdmin` added to the existing `/api/me` `ProfileDto` + `Profile` domain model
  (nullable DTO field → backward-compatible decode), threaded through `ProfileRepository`;
  `WorkoutSessionViewModel.isOwner` now derives from `profile.isAdmin` and `OWNER_EMAILS` is **deleted**.
- **DL-AND-4 ⭐ BUG FOUND + FIXED (D16).** The outbox drain classified the account-status **403 as terminal**
  (403 ∉ the retryable set), so a non-`WORKOUT_SCHEDULED` queued row was **silently dropped/self-healed**
  on suspension — direct data loss violating D16. Fixed in `OutboxReplayClient`: when the response carries
  `X-Account-Status`, `isTerminal` returns false so the row is preserved (backed off) not discarded.
  Regression test added. (Ordering itself was already correct: `OutboxDrainWorker` drains fully before
  `syncEngine.pull()`.)
- **DL-AND-5 ✅ CLOSED (owner-directed, 2026-10-02).** New `OutboxRepository.awaitDrainIdle()` (acquires +
  immediately releases the existing `drainMutex`) is called at the top of `SignOutSideEffects.wipeLocalData()`
  — the single shared wipe entry point (covers lockout AND account-switch) — before `DbWipe.wipe()`, so the
  wipe serializes *after* any in-flight drain. `OutboxRepository` injected as `dagger.Lazy` to break the
  OkHttp→TokenAuthenticator→GoogleAuthRepository→SignOutSideEffects Hilt cycle; barrier is best-effort
  (`runCatching`) so it never blocks the PHI wipe; deadlock-free (mutex never held across the wipe). The
  D16 account-status-403 row-preservation is unchanged. Verified: `:core-data`+`:app` compile (incl. KSP),
  `OutboxDrainTest` 23/23 (+2), `AuthCoordinatorTest` 14/14, new `SignOutSideEffectsTest` 2/2.
- **VERIFY:** `./gradlew :core-domain:compileDebugKotlin :core-data:compileDebugKotlin :app:compileDebugKotlin
  :feature-workouts:compileDebugKotlin` → BUILD SUCCESSFUL; `WorkoutSessionViewModelTest`, `OutboxDrainTest`
  (+ new D16 test), `AuthCoordinatorTest` (+3 lockout tests), `ProfileViewModelTest`, full
  `:core-data:testDebugUnitTest` → all green. (Worktree gotchas handled: wrote gitignored
  `android/local.properties`; `:app` compile needs `-PwebOauthClientId=dummy...`.)

## Integration + verification (orchestrator)

- **DL-INT-1** Root-caused the initial 167 full-context test failures: the new `@Service` beans
  (`GeminiCallRecorder`, `ProgramCatalogService`, `AdHocCatalogService`) and `EmailAllowlistService` need
  their repos, which are Firestore-only (off in tests). Fix: in-memory `AiUsageStore`,
  `CatalogWorkoutProgramRepository`, `CatalogAdHocWorkoutRepository`, `EmailAllowlistRepository` beans in
  `TestPersistenceConfig`.
- **DL-INT-2** Classified all 13 new mutating endpoints in `write-contract.txt` (admin-user + curation →
  SET_SEMANTICS; allowlist DELETE → IDEMPOTENT_DELETE; submit/adopt → EXEMPT) to satisfy `WriteContractTest`.
- **DL-INT-3** `WhoAmIControllerTest` (a `@WebMvcTest` slice) got a mocked `AdminAuthorizer` bean.
- **VERIFICATION (final):** `cd backend && ./gradlew test --rerun-tasks --no-build-cache` → **BUILD
  SUCCESSFUL, 1043 tests, 0 failed, 19 skipped** (independently re-run after all four late workstreams).
  New Pillar-1: `AdminAuthorizerTest` (+2), `UserServiceTest` (11), `AccountStatusFilterTest` (7), plus
  impersonation + offboarding suites. Pillar-2: 20. Pillar-3: 17 + food-verify. Web: `tsc`/`eslint`/vitest
  green. Android: touched modules compile + `OutboxDrainTest` 23/23, `AuthCoordinatorTest` 14/14,
  `SignOutSideEffectsTest` 2/2, `WorkoutSessionViewModelTest`, `:core-data:testDebugUnitTest` green.
  **E2E (Maestro/Playwright) per the spec DoD is NOT run in this environment** — no phase claims E2E
  evidence; `Tested [x]` = automated suite green (D20's hard gate).
