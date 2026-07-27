# ADR-0020 Implementation Decision Log

Running log of decisions made while implementing the third-party OAuth platform
API (ADR-0020) autonomously. Each entry: what was decided, why, and the
alternative not taken. Review after implementation to tweak anything.

Status legend: ✅ done · 🔶 deferred · ⚠️ needs review

---

## Scope & sequencing

- **D1 ✅ Phase 1 (OAuth core) already shipped** in the prior turn: authorization
  server, RS256 token family, PKCE, client registration, Connected Apps API,
  `PlatformAudienceFilter`. This log covers Phase 2 (the `/v1` read API), the web
  UI (consent + Connected Apps), and Phase 3 polish (rate limiting, ETags, audit
  log).

- **D2 🔶 Webhooks deferred.** ADR-0020 frames push/webhooks as a *Revisit when*
  future item, not core. It needs a whole delivery subsystem (subscription store,
  signed delivery, ret/backoff, replay protection). Decided to deliver a complete,
  verified *pull* API + UI + polish first and defer webhooks to a follow-up, rather
  than ship a half-built push path. Logged for review — easy to pick up next.

- **D3 Read-model strategy: map existing `core/*` entities to versioned `v1`
  DTOs, reusing existing repositories.** Never expose domain entities on the wire
  (ADR-0020). Where a repository lacks an `updatedSince`/cursor query, filter and
  page in the service layer over the existing per-user list read. Acceptable at
  current per-user data volumes; a repository-level indexed query is the later
  optimization. Logged so we revisit if any domain's per-user set is large.

## /v1 read API

- **D4 Incremental params by entity shape.** Entities with an `updatedAt` Instant
  (programs, food entries, daily logs, medications, blood, dexa, body-comp,
  daily-metrics, user) support precise `updatedSince=<ISO instant>`. Entities keyed
  only by date with no `updatedAt` (scheduled workouts, adherence logs) support
  `from`/`to` (LocalDate) and, if `updatedSince` is passed, apply it against the
  record's date.

- **D5 Cursor pagination.** Opaque base64 cursor encoding `<sortEpochMillis>:<id>`;
  lists are sorted newest-first by their natural sort instant/date, keyset-paged
  (stable under concurrent inserts, unlike offset). Envelope
  `{data, nextCursor, hasMore}`. Page size default 50, max 200 (`limit` param).

- **D6 ETags via ShallowEtagHeaderFilter** mapped to `/v1/*` only — every GET gets a
  content ETag + `If-None-Match` → 304, no per-endpoint code. Scoped so the
  first-party `/api` surface is untouched.

- **D7 Rate limiting: in-memory fixed-window** filter on `/v1/*`, keyed by
  (clientId, userId), emitting `RateLimit-Limit/Remaining/Reset` and 429 on exceed.
  Per-instance (not distributed) — acceptable for phase 1; a shared store (Redis) is
  the later upgrade. Default 600 req / 5 min per client+user.

- **D8 Audit log.** Every `/v1` request emits a structured log line (clientId,
  userId, scope, method, path, status) AND a best-effort async Firestore append to
  `platformAuditLog` (fire-and-forget on a virtual thread; a failed write never
  fails the request).

- **D9 Workout session detail uses a composite path**
  `/v1/workouts/{programId}/{scheduledId}` instead of the ADR's `/v1/workouts/{id}`
  — scheduledId is unique only within a program. `/v1/programs/{id}` stays as
  written.

- **D10 Extracted `TodaysDosesService`** from `TodaysDosesController` so `/v1/doses`
  and the first-party endpoint compute "today's scheduled doses" from one source of
  truth. Existing controller refactored to delegate; its tests must stay green.

- **D11 Scope enforcement** via `@PreAuthorize("hasAuthority('SCOPE_<scope>')")` per
  controller — the platform token's `scope` claim maps to `SCOPE_*` authorities via
  Spring's default converter.

- **D12 v1 verification strategy.** Unit tests for the cursor codec + pagination,
  per-domain mapper unit tests, a reflection test asserting every v1 handler carries
  the correct `SCOPE_` @PreAuthorize, and a standalone MockMvc test for the
  pagination envelope + RFC 7807 problem+json. Full cross-repository e2e over the
  Firestore emulator is deferred (would need in-memory fakes for every domain repo).

- **D13 🔶 OpenAPI spec deferred.** ADR-0020 lists a published OpenAPI 3 doc as a
  convention. `springdoc-openapi` would auto-generate it from the controllers, but
  it pulls swagger-core/swagger-ui — a transitive-CVE surface that the image scan
  gate (which blocks HIGH/CRITICAL) could reject, and this is an autonomous,
  unreviewed change. Deferred rather than risk breaking the build. The `/v1`
  endpoints + params are documented in the ADR and in each controller's header
  comment; adding springdoc is a clean fast-follow once a human can watch the scan.

## Web UI

- **D14 Consent flow is API-driven, not a backend redirect.** The Next.js
  `/oauth/authorize` page (behind the first-party session) calls the backend
  `GET /oauth/authorize` for consent metadata and `POST /oauth/authorize/consent`
  to approve/deny, then `redirect()`s the browser to the returned app URL. This
  fits the existing app (Auth.js session + `apiFetch` bearer) far better than a
  server-rendered consent page on the JSON-only backend, and matches how the ADR
  framed the web app as the authorization endpoint. Connected Apps lives at
  `/me/connected-apps` with a discoverability link from `/me/profile`.

## Verification (all green)

- Backend after interview round: `./gradlew test` — **620 test cases pass**, every
  `@SpringBootTest` context loads (springdoc on the classpath, platform/webhooks
  flags off). New this round: WebhookSignerTest, WebhookEventTypeTest,
  WebhookEventCollectorTest, PlatformKeysTest (fail-closed), and
  InMemoryPlatformRateLimitStoreTest.
- Backend (phase 1–3): `./gradlew test` — **603 test cases pass**, every `@SpringBootTest`
  context loads. New suites: CursorCodecTest, V1PageTest, V1ParamsTest,
  V1ScopeEnforcementTest, V1WorkoutsControllerWebTest, TodaysDosesServiceTest
  (plus the Phase-1 OAuth/PKCE/audience suites).
- Web: `tsc --noEmit` reports **zero errors in the new/changed files** (the only
  failures are pre-existing missing test-tooling deps in this environment's
  node_modules, unrelated to this change); `eslint` clean on all new files;
  `check:titles` passes.

## Interview outcomes (post-implementation review with user)

- **D15 ✅→build Webhooks now** (was D2 deferred). Push delivery for all four event
  families: `dose.logged` / `medication.changed`, `workout.completed`,
  `lab.added` / `dexa.added` / `daily-metric.updated`, `nutrition.day.updated`.
  Delivery model: an **outbox poller** (D19) rather than hooking every domain
  write path — reuses the existing repositories' `updatedAt`, zero coupling to
  write code. Flag-gated `app.platform.webhooks.enabled` (default off).

- **D16 ✅ Add springdoc** for a live OpenAPI 3 spec + Swagger UI, scoped to a
  `/v1` group. User accepted the transitive-dep/CVE-gate trade-off. `/v3/api-docs`
  + `/swagger-ui` are permitted unauthenticated (public API docs).

- **D17 ✅ Client secret stays hashed** (SHA-256, unrecoverable). ADR-0020 body
  updated to state hashing rather than KMS-encryption (hashing is stronger for a
  shown-once secret; consistent with refresh tokens).

- **D18 ✅ Distributed rate limiting via Firestore counters.** Atomic
  transactional counter docs keyed by (client,user,window) in
  `platformRateLimits`; exact cross-instance limits. Falls back to the in-memory
  limiter when Firestore is absent (tests/local). No Redis added.

- **D19 Webhook delivery = server-side outbox poller.** A scheduled job walks
  each active subscription, finds records changed since the subscription's
  per-user checkpoint (via the same repositories `/v1` reads), and POSTs a signed
  batch; advances the checkpoint on 2xx, retries with backoff otherwise. Chosen
  over `ApplicationEvent` hooks in every domain service to avoid invasive,
  risky edits to write paths. Latency is poll-interval-bounded (fine for
  monitoring), not sub-second.

- **D20 Webhook signature = HMAC-SHA256** per-subscription secret
  (`X-Tesseta-Signature: sha256=<hex>` over the raw body), *deviating* from the
  ADR's offhand "ECDSA/Tink" suggestion. Rationale: HMAC is the de-facto outbound
  webhook standard (Stripe/GitHub/Shopify) and trivially verifiable by any
  integrator with a shared secret; the Tink/ECDSA code in the repo is for
  *verifying inbound* Google webhooks and would impose asymmetric-verify on every
  integrator. Flagged for review.

- **D21 Webhook subscriptions are admin-managed** (consistent with admin-only
  client onboarding, D-onboarding). `POST /api/admin/oauth-clients/{id}/webhook`
  sets URL + event types and returns the signing secret once. Avoids the SSRF
  surface of client self-registration for now.

- **D22 Platform key is fail-closed by default in deployed config.** `PlatformKeys`
  generates an ephemeral key only when `app.platform.allow-ephemeral-key=true`
  (code default true so unit tests work); `application.yml` sets it from
  `${PLATFORM_ALLOW_EPHEMERAL_KEY:false}`, so a deployed instance with the platform
  on but no `PLATFORM_RSA_KEY` fails fast instead of silently running an
  ephemeral key that resets on restart.

- **D23 Grant types: PKCE only** (no Device grant, no partner server-to-server) —
  confirmed. **Onboarding stays admin-only.** **ADR status stays `Proposed`**
  pending final review.

## Open items for review

- 🔶 D2 webhooks, 🔶 D13 OpenAPI — deferred fast-follows.
- ⚠️ D7 rate limiting is per-instance (in-memory); revisit for multi-instance.
- ⚠️ Client secret is hashed, not KMS-encrypted (stronger, but deviates from the
  ADR wording — see Phase-1 notes). Worth confirming the ADR text is updated.
- ⚠️ D3 in-service pagination reads the full per-user list per request; fine now,
  revisit with repository-level indexed queries if any per-user set grows large.
- ⚠️ Verification boundary: the unit/slice suite runs with `app.platform.enabled=false`
  (like all existing tests), and platform beans/controllers are unit-tested by direct
  construction. A full boot with `platform.enabled=true` needs Firestore + Google OIDC
  discovery (network), so it isn't a unit test — recommend a CI smoke test against the
  Firestore emulator with platform on before merge. Note the invariant: enabling the
  platform in an environment requires Firestore enabled (the /v1 controllers depend on
  the firestore-gated domain repositories).
