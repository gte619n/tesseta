# Cross-cutting patterns

The mechanisms below span all three components. Read the relevant one before
touching auth, ingestion, goals, LLM calls, streaming, or caching — each has a
load-bearing shape that is easy to break.

## Auth

End-to-end the contract is: **every `/api/me/**` request carries a verified
Google ID token; the backend is a stateless OAuth2 JWT resource server.**

- **Backend** (`SecurityConfig`): `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")`
  + `GoogleAudienceValidator` accepts a token whose `aud` is one of the
  configured client IDs (web, Android phone, Wear). `UserProvisioningFilter`
  runs after the bearer filter and calls `UserService.provisionIfAbsent` so
  `users/{sub}` always exists. `SecurityContextCurrentUserProvider`
  (`CurrentUserProvider`) maps the `Authentication` → `CurrentUser` from the
  JWT claims. `DevHeaderAuthFilter` (`app.auth.dev-mode=true`, tests only)
  reads `X-Dev-User` and overrides `shouldNotFilterAsyncDispatch()` so SSE
  works under dev auth. Admin endpoints are gated by `@AdminOnly` — a composed
  `@PreAuthorize("@adminAuthorizer.isAdmin()")` annotation (`@EnableMethodSecurity`
  in `SecurityConfig`); `AdminAuthorizer` checks the caller's email against the
  `app.admin.emails` allow-list (`ADMIN_EMAILS` env).
- **Web** (`web/auth.ts`): Auth.js v5, Google provider only, JWT session
  (no DB), `maxAge` 1 year. The `jwt` callback rotates the Google `id_token`
  when within 60s of expiry; failure sets `error: "RefreshAccessTokenError"`.
  `web/middleware.ts` redirects any unauthenticated request (outside
  `api/auth` and `/auth/signin`) to sign-in. `isAdmin()`/`requireAdmin()`
  in `lib/admin.ts` gate `/admin`.
- **Android** (`core-data/.../auth`): Credential Manager + `GetGoogleIdOption`
  with the **web OAuth client ID** as `serverClientId` (so the backend
  audience matches). `interactiveSignIn()` vs `silentRefresh()`. `IdTokenCache`
  (DataStore `hf-auth`) holds the token; `AuthInterceptor` attaches the bearer
  and `TokenAuthenticator` does **silent-refresh-on-401** (rewrites the
  request, tags `X-HF-Auth-Retry: 1` to avoid loops).
- **Wear**: no independent sign-in. The phone publishes every issued/refreshed
  token over the Wearable Data Layer; `PhoneTokenSyncService` on the watch
  writes it to `WearIdTokenCache`.

## Google Health ingestion

Health data flows **device → Google Health API → backend webhook → Firestore →
clients**. Clients never sync from Google Health directly.

`GoogleHealthWebhookController` verifies the shared secret, answers
domain-verification probes, and hands UPSERT/DELETE notifications to
`WebhookHandlerService` (and `DailyMetricWebhookHandler` for daily metrics),
which resolves the user by `healthUserId`, **hydrates the record over REST via
`GoogleHealthClient`**, writes Firestore, refreshes `DeviceSync`, and publishes
metric events (below). Initial connect stores the refresh token and triggers
`BackfillService.scheduleBackfill` (async on a virtual thread; default
~1460-day window in 365-day chunks). `AccessTokenService` decrypts the refresh
token and exchanges it for a short-lived access token, cached in-process ~1h.

**Refresh-token encryption (ADR-0004).** `KmsTokenCipher` (in `integrations`)
does envelope encryption: `AES-256-GCM(refreshToken, DEK)` →
`refreshTokenCiphertext` (12-byte GCM nonce prefix); `KMS.encrypt(DEK, KEK)` →
`dekCiphertext`; both stored as Firestore `Blob`s on
`users/{id}.googleHealth`. The plaintext refresh token is never persisted.

## Goals metric-event system

This is the engine that makes Goals auto-evaluate as real health data lands.
**Do not** add ad-hoc "check the goal" code in feature services — publish a
metric event and let the evaluator react.

1. **Publish.** After a write that changes a bindable metric, the writer
   publishes `MetricChangedEvent(userId, metricKey, occurredAt)` via
   `MetricChangedPublisher` (a thin wrapper over `ApplicationEventPublisher`,
   published *after* save). Publishers: `BloodController`,
   `AdherenceController`, `WebhookHandlerService`, `DailyMetricWebhookHandler`,
   `NutritionService`, `MacroTargetService`, `WorkoutRepository`,
   `DailyMetricRepository`.
2. **Consume.** `StepEvaluationService.on(@EventListener)` runs **synchronously
   on the writer's thread** (try/catch isolates goal failures from the write).
   It maps the dotted key (`MetricKey.fromKey`), finds bound steps
   (`StepRepository.findByMetricKey`), reads the current value via
   `FirestoreMetricResolver` (one `switch` branch per closed-enum `MetricKey`
   over body / blood / vitals / workouts / nutrition-7d-avg /
   meds-adherence-30d), and applies a **notify-never-undo ratchet**:
   undone → done only, never reversed, respecting `manualOverride`; all
   transitions go through `GoalService.markStepDone`. Regression is surfaced as
   a transient read-time flag, never written.
3. **SUSTAINED steps** need a *window* re-check, so they can't be event-driven.
   `ReevaluateSustainedJob` (`@Profile("job-sustained")`, `CommandLineRunner`)
   runs **daily as a Cloud Run Job** on the same image
   (`SPRING_PROFILES_ACTIVE=job-sustained`), iterating all users and calling
   `StepEvaluationService.reevaluateAllSustained()`.

A metric the resolver can't compute returns `MetricValue.unavailable()`
("no change"), so missing data never falsely completes a step.

## Gemini usage

All Gemini clients live in the backend `integrations` module (Google GenAI SDK,
`GEMINI_API_KEY`). The project rule (root `CLAUDE.md`): general work uses
**`gemini-3.5-flash`**, image generation uses **`gemini-3.1-flash-image-preview`**,
and no other model without an ADR.

The feature services below share **one** `Client` bean (`GeminiConfig`,
conditional on `app.gemini.api-key` so it's absent in tests; missing key in prod
→ fail-fast) and select their model per-feature via `@Value`. The two equipment
services keep their own client (they wire in test contexts where the shared bean
is intentionally absent).

| Job | Client | Model |
|---|---|---|
| Drug lookup (grounded) | `DrugLookupService` | `gemini-3.5-flash` |
| Blood-test extraction | `BloodTestExtractor` | `gemini-3.5-flash` |
| DEXA extraction | `DexaExtractor` | `gemini-3.5-flash` |
| Nutrition label / meal photo | `NutritionLabelExtractor`, `MealPhotoExtractor` | `gemini-3.5-flash` |
| Equipment parsing | `EquipmentParserService` | `gemini-3.5-flash` |
| Drug / food / equipment images | `DrugImageGenerator`, `GeminiFoodImageGenerator`, `EquipmentImageService` | `gemini-3.1-flash-image-preview` |
| **Goal chat** | `GeminiGoalChatClient` | **`gemini-3.1-pro-preview`** — the *one* documented exception (ADR-0005), via env `GOALS_GEMINI_MODEL`. (A stale constructor default string `gemini-3.5-pro` exists but is overridden by `application.yml`.) |

## Streaming (SSE) & multipart

LLM streaming uses **Server-Sent Events**, never WebSockets. The backend emits
`SseEmitter` frames (`event:NAME\ndata:PAYLOAD\n\n`, multi-line data allowed).

- **Web** can't use the native `EventSource` (GET-only) for these POST streams,
  so each browser request hits a Next API route under `app/api/**` that
  re-attaches the bearer and pipes the raw upstream straight through. The route
  handlers share `proxySseStream` (`lib/api.ts`), which sets the streaming
  headers (`text/event-stream`, `X-Accel-Buffering: no`) once. On the client,
  `lib/sse-client.ts` `readSseStream(body, onEvent, signal)` parses the body;
  the two chat surfaces share `consumeChatStream` (`lib/chat-stream.ts`) and the
  blood/DEXA uploads share `<PdfUploadDropzone>`. Flows: drug lookup, goal chat,
  workout-program chat, blood upload, DEXA upload.
- **Android** parses SSE in `core-data/.../net/Sse.kt` (read/call timeouts set
  to 0 for long server work). `SseClient` handles JSON-POST streams
  (`DrugLookupStreamClient`, `ChatSseClient` in `core-chat`); `MultipartSseClient`
  handles multipart-upload-then-stream (blood/DEXA). Plain (non-SSE) multipart
  uploads (gym cover photo) use `MultipartUploadClient`.

Uploads (PDFs, photos) are `multipart/form-data`; the extraction ones combine
multipart upload **and** an SSE response on the same request.

## Web data-fetching & mutation (server-action-as-prop)

- `web/lib/api.ts` `apiFetch`/`apiJson` are **server-only** — they read
  `process.env.BACKEND_URL` and the Auth.js session and attach the bearer.
  `getSession = cache(async () => auth())` (React `cache()`) dedupes the JWT
  lookup to **once per render** (an IMPL-20 perf fix). These helpers and the
  `lib/*-api.ts` domain wrappers **must never be imported from a `"use client"`
  file** — `BACKEND_URL` is undefined in the browser bundle.
- **Reads:** prefetch server-side and pass data down as props.
- **Mutations:** define an inline `"use server"` action in the server page and
  pass it to the client component as a callback prop (then `revalidatePath`).
  Do **not** put `"use server"` atop a `lib/*-api.ts` file. Canonical example:
  `app/page.tsx` → `logDose` → `<TodaysDosesCard logDose=…>`.

See `web/CLAUDE.md` for the toast/confirm hooks and the modal-backdrop
text-selection-safe close pattern.

## Backend caching (IMPL-20)

Caffeine via `CacheConfig` (`@EnableCaching`, `expireAfterWrite`). Every write
path that feeds a cache must `@CacheEvict`:

| Cache | TTL | Notes |
|---|---|---|
| `drugById` / `drugCatalog` | 5 min | evicted on drug save/delete |
| `userById` | 5 min | every `UserRepository` mutator is `@CacheEvict`; batch `findByIds` (chunked `whereIn`, ≤10) is deliberately uncached |
| `userHealthSnapshot` | 60 s | the ~24-metric Goals-chat snapshot; short TTL so fresh writes surface within a minute |

Pair caching with the indexed `findLatest*` reads in
[data-model.md](data-model.md#latest-value-reads-findlatest).

## Batched Firestore writes

When a single logical operation writes or deletes **more than one** document,
commit a `WriteBatch` (`firestore.batch()` → `batch.set`/`batch.delete` →
`batch.commit()`) instead of awaiting one round-trip per doc. Firestore caps a
batch at **500 ops**, so chunk: `for (int start = 0; start < items.size();
start += 500) { … }`. This is the write-side companion to the IMPL-20 read
patterns (N+1 batching, indexed `findLatest`).

- **Materialization / re-write** — `WorkoutScheduleService.activate` builds all
  sessions then calls `ScheduledWorkoutRepository.saveAll`; the Firestore impl
  commits in ≤500-op batches (the in-memory test impl keeps the per-doc default).
- **Cascade delete** — Firestore doesn't cascade subcollections, so deleting a
  parent means deleting its children first. Batch those deletes
  (`FirestoreWorkoutProgramChatRepository.deleteThread`,
  `LocationRepository.setDefault`).

Single-document `save`/`delete` stays a plain `await(...set/delete)` — don't
wrap one write in a batch. Writes that must go through service-layer business
logic per item (e.g. `ExerciseCatalogSeeder` → `ExerciseService.create`) are
not batch candidates.

Every Firestore repo blocks on the `ApiFuture` through the shared
`FirestoreSupport.await(...)` helper (`persistence`), which converts gRPC
failures/interruptions into a typed `FirestoreAccessException` (`core.error`).
`GlobalExceptionHandler` switches on that exception (and its gRPC status) for a
clean HTTP mapping — don't string-match Firestore error messages, and don't
re-implement the future-await/try-catch per repo.
