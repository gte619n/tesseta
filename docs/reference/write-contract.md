# The offline write contract

Every mutating endpoint (POST/PUT/PATCH/DELETE) must be **safe to replay**,
because an offline client queues mutations in a local outbox and replays them on
reconnect (Android today; web as of the web-outbox slice). A blind replay must
never create a duplicate or corrupt state.

This is enforced, not documented-and-hoped: `WriteContractTest` enumerates the
live mutating surface from Spring's `RequestMappingHandlerMapping` and asserts
every endpoint is classified in `backend/src/test/resources/write-contract.txt`
and that none is `NEEDS_FIX`. A new mutating endpoint that isn't classified
fails the build. (The `/v1` platform and `/oauth` endpoints are pinned
separately by `V1OpenApiSnapshotTest`; they're behind `app.platform.enabled`,
off in the test profile.)

## Categories

| Category | Meaning | Why replay is safe |
|---|---|---|
| `KEY_GUARDED` | A create routed through `SyncWriteContext.idempotentCreate(scope, userId, create, loadById)`. | The `Idempotency-Key` header dedupes: the first call creates and records `(user, scope, key) → id`; a replay re-loads that id instead of creating again. |
| `DETERMINISTIC_ID` | A create/upsert whose document id is client-supplied or deterministically derived (e.g. workout session `{date}_{dayId}`, FCM token keyed by `deviceId`, nutrition day keyed by date). | Replay upserts the same document. |
| `SET_SEMANTICS` | A PUT/PATCH (or a set-toggle POST like `confirm`/`archive`/`activate`) that sets fields on an already-identified resource. | Replay converges to the same field values. |
| `IDEMPOTENT_DELETE` | A DELETE. | Deleting an already-deleted resource is a no-op. |
| `NON_PERSISTING_POST` | A POST that persists no durable user document — search, lookup, SSE chat, preview/dry-run, file-scan setup, auth token exchange, connect/disconnect toggles. | Nothing to duplicate. |
| `EXEMPT` | Genuinely non-idempotent but accepted: admin catalog/config (not offline-outbox user data), AI-generation-with-cost, and webhooks/internal handlers that carry their own dedup. | Out of the offline-outbox threat model, or self-deduping. |
| `NEEDS_FIX` | A create that mints its id server-side with no key path and no deterministic id. | **A build failure.** Route it through `idempotentCreate` or give it a deterministic id. |

## What this slice closed

The audit found six creates that minted a server-side id with no replay
protection — a blind outbox replay would duplicate them. All six were routed
through `idempotentCreate`:

- `POST /api/me/nutrition/{date}/capture-meal` — placeholder photo entry
- `POST /api/me/nutrition/{date}/relog` — one-tap re-log copy
- `POST /api/me/goals/chat/{threadId}/commit` — the Goal→Phase→Step aggregate
- `POST /api/me/workout-programs` — program create
- `POST /api/me/workout-programs/chat/{threadId}/commit` — new-thread program
- `POST /api/me/equipment` — equipment submission

## Adding an endpoint

1. Write the handler. Prefer a client-minted/deterministic id, or wrap a
   server-minted create in `idempotentCreate`.
2. Add one line to `write-contract.txt`: `CATEGORY|METHOD path`.
3. `WriteContractTest` fails if you skip step 2, and fails if the category is
   `NEEDS_FIX`.

## Known follow-ups (tracked, not yet closed)

`MedicationController.update` and `changeDose` are `SET_SEMANTICS` for the
medication document itself, but each **appends** a UUID-keyed `MedicationHistory`
row with no dedupe, so a replayed PUT/POST duplicates the history log. Tracked in
`docs/refactor/bugs.md` (BUG-02); the fix is a deterministic history-row id
derived from the mutation, out of scope for this slice.
