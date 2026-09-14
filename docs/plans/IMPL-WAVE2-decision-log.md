# IMPL-WAVE2 — Decision Log

> Second remediation wave (operator request): the last open critical (XPLAT-001)
> plus the observability and multi-user-gate high clusters. Branch
> `feature/audit-wave-2` off `main`. Cross-platform contracts are specified once
> here and given identically to the backend/android/web agents so they align.

## Scope
- **XPLAT-001** (critical) — one canonical "today".
- **Observability cluster** — OBS-003 (structured backend logs), OBS-002 (client
  crash reporting), OBS-005 (sync SKIPPED counter), OBS-006 (FCM empty/batch).
- **Multi-user gate cluster** — SEC-001 (Gemini rate-limit/quota), SEC-012 /
  IMPL-SEC-01 (private meal photos), TEST-001 (cross-user isolation tests),
  COMP-001/002 (privacy policy truth-up + in-product links).

## Cross-platform contracts (binding on all agents)

**XPLAT-001 — "today" day-key.** The user's timezone is the single source of
truth. Clients send `X-Timezone` (IANA, e.g. `America/New_York`) on every
request. Backend nutrition day endpoints accept optional `?date=YYYY-MM-DD`; when
present use it, else derive "today" from `X-Timezone` (fallback UTC only if the
header is missing) — never server-zone `LocalDate.now()`. Mirror the existing
`TodaysDosesController` pattern. Web computes the local date in the user's tz
(stop using `toISOString()` UTC) and passes `?date=`. Android sends `X-Timezone`
+ device-local `?date=`.

**SEC-012 — meal-photo serving (Phase 1+2 only; the live bucket flip is
DEFERRED).** Backend adds `GET /api/me/nutrition/photo/{entryId}` →
authorize the entry belongs to the caller (ADR-0021) → 302 redirect to a V4
read-signed URL (TTL 15m) for the entry's stored `photoRef` object (new
`SignedUrlService`). Nutrition entry DTOs gain a `photoUrl` field pointing at
that endpoint; `photoRef` stays internal. Web + Android render the meal photo via
`photoUrl`. **DEC-W2-1 — do NOT flip the bucket to private in this branch**
(Phase 3): flipping before clients ship breaks image loading. The IAM flip is a
documented post-deploy follow-up in IMPL-SEC-01 (one terraform/gcloud change
after web+android deploy and photo rendering is confirmed).

**OBS-002 — crash reporting.** Android + Wear: Firebase Crashlytics (Firebase
BoM already present for FCM). Web: `app/global-error.tsx` + `error.tsx` reporting
to a same-app route handler that logs structured JSON (→ Cloud Logging); no
external DSN / Sentry account. **DEC-W2-2** — Crashlytics adds a Google
sub-processor; recorded in the privacy posture doc (COMP §4).

## Agent partition (disjoint trees; one builder per build system)
- Agent A (backend): XPLAT-001 backend, SEC-001, SEC-012 serving + `photoUrl`,
  OBS-003, OBS-005 (backend emit), OBS-006, TEST-001.
- Agent B (android+wear): XPLAT-001 client, OBS-002 Crashlytics, OBS-005
  (SyncEngine SKIPPED counter), SEC-012 `photoUrl` switch, COMP-002 privacy link.
- Agent C (web): XPLAT-001 client, OBS-002 global-error + log route, SEC-012
  `photoUrl` switch, COMP-002 privacy links.
- Lead (docs): COMP-001 privacy posture truth-up, IMPL-SEC-01 Phase-3 follow-up,
  findings.json update (last).

## Decisions (consolidated after completion)

**Backend (all 7 items done; 861 tests pass):**
- XPLAT-001: NutritionController `list`/`today`/`recentMeals` derive today from
  `?date=` else `X-Timezone` (UTC fallback), mirroring TodaysDosesController. New
  NutritionTodayTimezoneTest.
- SEC-001: `AiRateLimitFilter` reuses the /v1 `PlatformRateLimitStore`; **30
  req/user/hour** default (config `app.gemini.rate-limit.*`), 429+Retry-After,
  fail-open on store errors, matches only Gemini-triggering POSTs.
- SEC-012: `SignedUrlService` (V4, 15-min) + `GET /api/me/nutrition/photo/{entryId}?date=`
  (per-user 404) + `EntryResponse.photoUrl`. **`?date=` scopes the lookup** (ADR-0021,
  no collectionGroup) — this is the seam the web proxy had to forward (fixed by lead).
- OBS-003: Boot 3.5 has no built-in `gcp` structured format, so a custom
  `GcpStructuredLogFormatter` + `StructuredLogEncoder` (JSON in deployed profiles,
  plain text local/test) + `RequestCorrelationFilter` (requestId + X-Cloud-Trace-Context → MDC).
- OBS-005: `sync.changes.dropped` (WARN+counter) vs `sync.changes.window_filtered`
  (count-only) — only unroutable = data-loss class.
- OBS-006: zero-token fan-out now WARN + `fcm.fanout.no_tokens`; batch failures debug→WARN + counter.
- TEST-001: `CrossUserIsolationTest` (two subjects; B can't read A's nutrition/blood/workout).
  Medication case dropped (feature disabled in test profile) — covered by the structurally identical workout case.

**Android (all 5 done; assembleDebug green):**
- XPLAT-001 already satisfied (TimeZoneInterceptor sends X-Timezone + device-local date) — verified, no change.
- OBS-002 Crashlytics on `:app` only; **`:wear` excluded** (no google-services.json — would break the build). Wear crash coverage needs a wear Firebase app first (follow-up).
- OBS-005 SyncEngine SKIPPED → WARN log + counter + `skippedChanges` flow (+`skipped` on PullResult).
- SEC-012 `photoUrl` resolved in the data layer (folded into `imageUrl`) so all Coil consumers benefit; null → raw fallback.

**Web (all 4 done; build/tsc/lint/test green):**
- XPLAT-001 `tz-date.ts` derives local today from the `tz` cookie (== X-Timezone), UTC fallback.
- OBS-002 `error.tsx`/`global-error.tsx` → `/api/log-client-error` (structured JSON, PII-scrubbed, no DSN).
- SEC-012 `photoUrl?` on Entry + same-origin proxy route + `photoUrl ?? imageUrl` at 5 sites.
- COMP-002 privacy/terms links on the profile page → tesseta.com.

**Lead (integration + docs):**
- **DEC-W2-3 — fixed a cross-agent seam:** the web photo proxy route
  (`app/api/me/nutrition/photo/[entryId]/route.ts`) did NOT forward the `?date=`
  query the backend requires, so web meal photos would 400→placeholder. Now
  forwards `new URL(request.url).search`. Caught by integrated review, not the
  agents (each was individually correct against its own spec).
- COMP-001: privacy posture doc updated (Crashlytics sub-processor; SEC-012
  mitigation-in-progress; structured-logging note). Published policy still
  over-promises deletion until DATA-003 ships — kept as an open item.
- SEC-012 bucket-flip (Phase 3) deferred (DEC-W2-1); recorded in IMPL-SEC-01.
