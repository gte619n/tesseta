# ADR-0020: Third-party OAuth platform API

- Status: Proposed
- Date: 2026-07-27

## Context

Everything Tesseta knows about a user — training schedule, nutrition, medication
adherence, labs, body composition — lives behind clients that authenticate *as
that user*: web on Google ID tokens ([ADR-0002](ADR-0002-google-id-tokens-as-auth.md)),
native on backend-issued session tokens ([ADR-0010](ADR-0010-backend-session-tokens-for-native-clients.md)).
Both families answer one question: "is this the account owner?" Neither can
answer "may *this other application* read *this slice* of the owner's data, and
for how long?"

We want to let external applications — a coaching dashboard, a clinician's
monitoring tool, a personal-analytics aggregator — **pull** a user's data on that
user's behalf. The emphasis is read/monitoring, not write-back. That is a
different trust model from anything we have:

- The caller is a *third party*, not the user. It must never see the user's
  Google credentials or a first-party session token.
- Access is *delegated and partial*: the user consents to specific domains
  (share workouts, withhold labs) and can revoke at any time.
- Access is *long-lived but bounded*: a monitor polls for weeks without
  re-prompting, yet every grant is individually killable.

The existing token families are the wrong tool. A Google ID token is an identity
assertion with no scope concept and no per-application revocation. A first-party
session token grants *everything* the owner can see — handing one to a third
party is total account access. Stretching either to carry a `client_id` and a
scope set would entangle the public trust boundary with the app's own auth and
force the resource server to reason about "is this a real user or a delegated
app?" on every request.

Separately, we must not expose the `core/*` domain entities directly. They churn
with product work (`ScheduledWorkout`, `FoodEntry`, `Medication`,
`BloodReading`); a third-party contract that moves with them breaks integrators
on every refactor.

## Decision

Stand Tesseta up as a first-class **OAuth 2.0 authorization server + resource
server** for third-party apps, on a **new token family and a new versioned API
surface**, both isolated from the first-party clients.

### Grant model — Authorization Code + PKCE only

User-delegated **Authorization Code with PKCE** (OAuth 2.1 baseline) is the sole
interactive flow. The user authenticates to Tesseta as they already do, sees a
consent screen, and the third party receives a code it exchanges for tokens. PKCE
is mandatory for every client (no `client_secret`-only path), redirect URIs are
matched exactly, and the implicit flow is not offered.

Deliberately out of scope for v1: Client Credentials (no user context to
delegate), the Device Authorization Grant (no TV/limited-input client yet), and
any server-to-server partner-key path that reads across users without a
per-user grant. Each is a larger trust escalation; noted under *Revisit when*.

### Scopes — read-only, domain-grained

Scopes are `domain:access`. v1 ships read-only — matching the monitoring intent —
but the `:write` half of the namespace is reserved so a future write grant does
not force a scope rename.

| Scope | Grants (backed by) |
| --- | --- |
| `profile:read` | name, height (`User`) |
| `workouts:read` | programs, scheduled/completed sessions, logged sets (`WorkoutProgram`, `ScheduledWorkout`, `LoggedSet`) |
| `nutrition:read` | food entries, macros, daily totals vs targets (`FoodEntry`, `NutritionDailyLog`, `MacroTarget`) |
| `medications:read` | meds, schedules, today's doses, **adherence** (`Medication`, `TodaysDose`, `AdherenceLog`) |
| `labs:read` | blood readings, DEXA, body composition (`BloodReading`, `DexaScan`, `BodyCompositionMeasurement`, `DailyMetric`) |
| `offline_access` | issue a rotating refresh token for background polling |

`labs:read` is split from the rest because clinical data is the most sensitive
slice; a user can share training without exposing lab work, and consent lists it
on its own line. `medications:read` deliberately includes adherence — the
taken/skipped signal is the highest-value monitoring output and belongs with the
medication grant, not behind a separate scope.

### Token family — separate issuer, RS256/JWKS

Platform access tokens are a **third** family, distinct from Google and
`tesseta-backend`:

- **Access token** — short-lived (~15 min) JWT, `iss=tesseta-platform`,
  `aud=tesseta-platform-api`, carrying `sub` (userId), `client_id`, and `scope`.
  Signed **RS256** with a key published at `/.well-known/jwks.json`, *unlike* the
  HS256 first-party token. The reasoning in ADR-0010 for HS256 ("the backend is
  the only issuer and validator") no longer holds: a public API benefits from
  key rotation and from third parties being *able* to validate offline.
- **Refresh token** — opaque, rotating, single-use, stored only as a SHA-256
  hash, **reusing the successor-chain design of
  [ADR-0019](ADR-0019-successor-chain-refresh-token-rotation.md)** in a new
  `platformRefreshTokens/` collection. Issued only when `offline_access` is
  granted. Its lifetime is bounded and it is revoked when the user removes the
  app.
- **`SecurityConfig`** gains a third decoder branch keyed on the `iss` peek
  (`tesseta-platform` → the RS256/JWKS decoder), and a converter that maps the
  `scope` claim to Spring authorities so resource endpoints enforce
  `@PreAuthorize("hasAuthority('SCOPE_workouts:read')")`. The `/oauth/**` and
  `/v1/**` matchers are added to the filter chain; platform tokens are rejected
  on `/api/**` and first-party tokens on `/v1/**`, so the two boundaries never
  cross.

### Endpoints

Authorization server:

```
GET  /oauth/authorize     consent + PKCE challenge, exact redirect match
POST /oauth/token         code→tokens, refresh→tokens (rotating)
POST /oauth/revoke        RFC 7009
GET  /oauth/userinfo      granted user's identity
GET  /.well-known/oauth-authorization-server   RFC 8414 discovery
GET  /.well-known/jwks.json
```

Resource API, versioned under `/v1` (distinct from the app's `/api/me`), all four
v1 domains:

```
GET /v1/user
GET /v1/workouts        /v1/workouts/{id}     /v1/programs   /v1/programs/{id}
GET /v1/nutrition/entries    /v1/nutrition/days/{date}
GET /v1/medications     /v1/medications/{id}  /v1/doses      /v1/adherence
GET /v1/labs/blood      /v1/labs/dexa         /v1/metrics/daily   /v1/metrics/body-composition
```

Cross-cutting conventions, uniform across every collection:

- **Incremental pull** — `updatedSince` (and/or an opaque sync cursor) on every
  list endpoint, so a monitor fetches deltas, not the world. The existing
  `updatedAt` / `date` / `sampleDate` fields already support this.
- **Cursor pagination** — `{ data, nextCursor, hasMore }` everywhere.
- **RFC 7807 `application/problem+json`** for errors.
- **Per-client and per-user rate limits**, surfaced via `RateLimit-*` headers.
- **Versioned `v1` DTOs mapped from `core/*`** — never the domain entities on the
  wire. The public contract is frozen independently of internal schema churn.
- A published **OpenAPI 3** document generated from the `/v1` controllers.

### Client & consent state

Two new Firestore collections: `oauthClients/{clientId}` (the confidential-client
secret stored only as a **SHA-256 hash** — shown to the developer once at
registration and never recoverable, the same treatment as refresh tokens and
stronger than storing it encrypted for redisplay — plus exact `redirectUris`,
`allowedScopes`, display name/logo) and `oauthGrants/{userId}/{clientId}`
(granted scopes, timestamp).
The grant collection backs a first-party **Connected Apps** screen — list and
one-tap revoke — which is table stakes for a health app and the user-facing half
of `/oauth/revoke`.

Client onboarding starts as admin-only registration (reusing the `@AdminOnly`
method security already guarding the catalog endpoints); a self-serve developer
portal is deferred.

### Phasing

1. **OAuth core** — client registration, `/oauth/authorize|token|revoke`, PKCE,
   consent screen, scope→authority mapping, Connected Apps UI.
2. **Read API v1** — all four domains with pagination + `updatedSince` +
   published OpenAPI. Medications/adherence and workouts first (highest
   monitoring value), nutrition and labs alongside.
3. **Polish** — rate limiting, `ETag`/`If-None-Match` on daily aggregates,
   per-access audit log.

## Consequences

### Positive

- Third parties get delegated, per-scope, individually revocable access without
  ever touching the user's Google credentials or a first-party session — the
  trust boundary the existing token families cannot express.
- The platform token family is fully isolated: scope enforcement lives on
  `/v1/**` only, and the `iss`-routed decoder keeps first-party auth untouched
  (same pattern ADR-0010 established for two families, now three).
- The `v1` DTO layer decouples the public contract from `core/*`, so internal
  refactors stop being integrator-breaking changes.
- Refresh-token security reuses a design we have already reasoned about and
  tested (ADR-0019), rather than inventing a second rotation scheme.

### Negative

- A third token family and a second decoder branch add real surface area to
  `SecurityConfig`; the "two families to reason about" cost ADR-0010 accepted
  becomes three, and a mis-scoped endpoint now leaks data to an external party,
  not just an over-permissioned owner. Scope enforcement needs test coverage per
  endpoint, not per domain.
- Tesseta becomes a *published* API with an availability and versioning
  obligation to outside integrators — a support surface the app has never had.
- RS256/JWKS introduces asymmetric key management (rotation, `kid`) that the
  HS256 first-party path avoided.
- The successor-chain rotation inherits ADR-0019's explicit trade-off — a benign
  replay is honoured over strict theft detection — now extended to third-party
  tokens. Acceptable for read-only scopes; must be revisited before any
  `:write` scope ships.

### Security posture

Health data raises the stakes over a generic API: mandatory PKCE, exact
redirect-URI matching, ~15 min access-token TTL, rotating refresh tokens,
hashed client secrets, and a per-access audit log (`clientId`, `userId`,
scope, endpoint) so third-party reads of `labs`/`medications` are attributable.
The consent screen must enumerate exactly what each scope exposes.

## Revisit when

- A write use-case appears — `:write` scopes exist in the namespace, but shipping
  one forces a fresh look at the ADR-0019 replay trade-off and at idempotency.
- A limited-input client (TV, smart display) needs access — add the Device
  Authorization Grant at the token endpoint.
- A trusted partner needs to read across many users without a per-user
  interactive grant (e.g. a clinic backend) — the largest trust escalation here;
  it needs its own ADR, not a scope.
- Third parties want push instead of poll — a webhook/subscription surface
  (`dose.logged`, `workout.completed`, `lab.added`), signed with the ECDSA/Tink
  pattern `GoogleHealthWebhookController` already uses to *verify* Google Health's
  webhook ingress, inverted to *sign* ours.
- `platformRefreshTokens/` or `oauthGrants/` outgrow flat collections and need a
  cleanup/TTL job — the same scaling caveat ADR-0010 flagged for `refreshTokens/`.
