# IMPL-AND-20: Android Offline-First Sync Engine

**Date:** 2026-06-02
**Updated:** 2026-06-02
**Status:** 📋 Planning — not started. This is a living document; the
[Status Tracking](#status-tracking) table is the source of truth for what is
implemented / tested / pushed.

Offline-first is a cross-cutting initiative spanning **android/** and
**backend/**. It is numbered in the `IMPL-AND` series because the user-visible
deliverable is on the phone, but it requires a coordinated backend change
(sync contract, soft-delete, FCM fan-out) documented here in full.

---

## Goal

Make the Android phone app **offline-first**: a local, encrypted database is
the source of truth for the UI, a background sync engine reconciles it with the
backend continuously, edits made on the phone are pushed up immediately when
online (and queued when not), and the backend notifies the phone via a silent
push when new data is available to pull. The app must work identically whether
the device is online or offline for every non-AI feature.

---

## Scope

In scope (phase-tracked, delivered as one cohesive effort — see
[Delivery model](#delivery-model)):

- **Backend sync contract:** a universal `lastUpdate` + `status`
  (`ACTIVE`/`ARCHIVED`) model across all per-user collections, with hard
  `DELETE`s converted to soft-deletes (tombstones).
- **Backend delta-read API:** a single unified `GET /api/me/sync?since=<cursor>`
  endpoint returning all changed docs (including tombstones) across in-scope
  collections, with cursor-based pagination.
- **Backend idempotent writes:** create/update/delete accept client-minted
  UUIDs and an idempotency key; safe to replay.
- **Backend push:** a per-user FCM device-token registry and a server-side
  fan-out that emits a silent data message on every write (including the
  Google Health webhook path), with origin-device suppression.
- **Android local store:** Room + SQLCipher, wiped on sign-out, mirroring all
  in-scope collections.
- **Android sync engine:** delta pull + cursor, an outbox for offline writes
  (client UUIDs, idempotency, per-entity ordering, exponential backoff),
  document-level last-write-wins conflict resolution on the server clock, and
  WorkManager-driven triggers (FCM + periodic floor + foreground + connectivity).
- **Android read-path refactor:** in-scope ViewModels read **only** from Room
  (reactive Flows); the network layer's only job is to fill/refresh the DB.
- **Android sync UX:** a global sync-state indicator, per-row pending/failed
  badges, pull-to-refresh, and manual retry.
- **First-run UX:** a brief blocking initial sync (CRUD domains in full; heavy
  time-series limited to the **last 14 days**) then lazy backfill of older
  historical time-series.

Out of scope (deferred, by decision):

- **Wear OS offline-first.** Wear stays a read-only token relay; a separate
  spec will address it if needed.
- **Offline AI / upload flows.** PDF/DEXA upload, meal-photo capture, drug
  lookup, and goals chat are inherently online + streaming. When offline their
  entry points are **disabled** with a "needs connection" affordance; nothing
  is queued. The CRUD records they eventually produce are fully offline-capable.
- **Field-level merge / manual conflict prompts.** Conflicts resolve silently
  via document-level LWW.
- **TTL purge of tombstones.** Archived rows are retained indefinitely (a
  purge job is explicitly not part of this scope).
- **Shared catalogs as writable offline data.** `drugs`, `foodCatalog`,
  `equipment` keep their existing alias-pointer scheme and are mirrored
  read-only on device.

---

## Decisions

Captured from the planning interview. Each row is binding for this spec.

| # | Topic | Decision |
|---|---|---|
| D1 | Push channel | **FCM silent data messages.** Backend emits "collection changed → pull"; WorkManager performs the actual REST pull. Preserves ADR-0001 (clients read through the backend, not Firestore). |
| D2 | Deletions | **Soft-delete all per-user data.** `status` ∈ {ACTIVE, ARCHIVED}; `DELETE` → archive. Delta sync returns archived rows as tombstones. Existing `MedicationStatus`/`GoalStatus` enums are reconciled into the scheme; shared catalogs keep alias-pointers (read-only on device). No TTL purge. |
| D3 | Conflicts | **Document-level last-write-wins on the server clock.** Backend stamps `lastUpdate` via `FieldValue.serverTimestamp()` on every write; client clocks are never trusted for ordering. |
| D4 | Phase-1 breadth | **Everything with a non-AI write path** is offline-capable. AI/upload (SSE/multipart) flows stay strictly online. |
| D5 | Local store | **Room + SQLCipher**, key in Android Keystore, **DB file deleted on sign-out** (PHI hygiene). |
| D6 | Sync API shape | **Unified `GET /api/me/sync?since=<cursor>`** returning all changed in-scope docs + tombstones, with `nextCursor` + `hasMore` pagination. One persisted cursor on the client. |
| D7 | Outbox semantics | **Client-minted UUIDs + idempotency keys**, mutations replay **in order per entity**, offline create→edit→delete collapses to the net effect, exponential backoff on failure. |
| D8 | Read path | **Room is the sole source of truth for the UI.** In-scope ViewModels observe Room Flows; the network layer only fills/refreshes the DB. |
| D9 | Derived data | **Server-computed data is a read-only mirror, never in the outbox** (goal step done/`doneAt`, `nutritionDailyLogs` aggregates, `weeklyWorkoutAggregates`, `correlatedMarkers`, Google-Health-sourced metrics). Manual goal-step toggles go up as an explicit intent, not a raw doc write. |
| D10 | FCM reliability | **FCM + periodic WorkManager floor (~6h, network-required) + sync-on-foreground + sync-on-connectivity-regained.** Outbox drains immediately when connectivity returns. Heavy syncs may additionally prefer unmetered/charging. |
| D11 | Sync UX | **Global sync-state indicator + per-row pending/failed badges**, pull-to-refresh, manual retry. Conflicts resolve silently (LWW); a lightweight "updated elsewhere" note appears if a visible item changes underneath the user. |
| D12 | Wear | **Deferred entirely.** |
| D13 | Cutover & governance | **Lazy default** (`status` missing ⇒ treated as `ACTIVE` on read; stamped going forward) + **`schemaVersion`** in the sync protocol (bump ⇒ client wipes Room and full-resyncs) + a **remote kill-switch** flag to force any client back to live-network mode. |
| D14 | First-run | **Brief blocking first-sync** (recent window: CRUD domains fully, heavy time-series **last 14 days**) then **lazy backfill** of older history. |
| D15 | Test bar | **Full pyramid + functional E2E gates per phase.** Unit (resolver, outbox reducer, cursor math) + Room DAO instrumented + MockWebServer protocol (tombstones, pagination) + scripted functional E2E (airplane-mode create/edit → reconnect → assert server state; FCM-triggered pull; two-client convergence). |
| D16 | Agent verification | **Per-phase Verification block with evidence required.** Each phase lists exact commands, expected green output, and an observable functional assertion. A phase flips to ✅ only when every check passes and evidence is pasted; partial ⇒ ⏳ with a note. |
| D17 | Offline AI flows | **Disabled offline** with a clear "needs connection" affordance; no deferred-intent queue. |
| D18 | FCM addressing | **Per-user device-token registry** (new `fcmTokens` subcollection); register/refresh token after sign-in, **delete on sign-out**. Fan-out to the user's tokens with **origin-device suppression** via a client-supplied `originDeviceId`. |
| D19 | Delivery model | **One cohesive delivery** (no artificially gated releases between domains), **documented as ordered, individually-verifiable phases** with a live status table. |

---

## Delivery model

Per **D19**, the engine is built as a single coherent effort rather than a
series of separately-shipped increments. The phases below exist for
**tracking and verification**, not as release boundaries: each phase has its
own evidence gate (D16) used as an internal checkpoint, and the
[Status Tracking](#status-tracking) table records implemented / tested / pushed
state per phase so progress is auditable at any moment.

---

## Architecture overview

```
┌──────────────────────── Android (phone) ────────────────────────┐
│  Compose UI  ──observes──►  Room Flows (SQLCipher, source of truth) │
│                                  ▲              ▲                   │
│                          (pull writes DB)   (writes via outbox)     │
│                                  │              │                   │
│        SyncEngine ── delta pull ─┘     OutboxRepository ── drains ──►│
│         ▲   ▲   ▲   ▲                                                │
│   WorkManager triggers:                                             │
│   FCM data msg │ ~6h floor │ foreground │ connectivity-regained     │
└───────────────┼──────────────────────────────┼────────────────────┘
                │ silent FCM "pull"             │ REST (idempotent writes)
                ▼                               ▼
┌──────────────────────── backend (Spring Boot) ─────────────────────┐
│  /api/me/sync?since=<cursor>  ──► delta of changed+archived docs     │
│  CRUD controllers ── stamp lastUpdate (serverTimestamp), status      │
│  SyncChangePublisher ── on every write ──► FcmFanoutService          │
│  Google Health webhook ── same publisher path                        │
│  fcmTokens registry  ──►  FCM (origin-device suppressed)             │
│                         Firestore (owner of all state)               │
└─────────────────────────────────────────────────────────────────────┘
```

**Invariants:**

1. The phone never reads Firestore directly (ADR-0001 preserved).
2. The UI reads only from Room for in-scope domains (D8).
3. `lastUpdate` is always a **server** timestamp; it is the sole ordering
   authority for conflict resolution (D3) and the sync cursor (D6).
4. Derived/server-computed fields are never written from the client (D9).

---

## Data Model Changes

### Backend — universal sync metadata (D2, D3, D13)

Every in-scope per-user record gains/normalizes two fields. `createdAt`/
`updatedAt` already exist on all records; `updatedAt` is **renamed in intent**
to the canonical sync cursor field `lastUpdate` (kept as the existing
server-timestamp write) and a `status` enum is added.

```java
// Applied to every in-scope per-user collection
public enum SyncStatus { ACTIVE, ARCHIVED }

// lastUpdate  : Instant  — FieldValue.serverTimestamp() on every write
// status      : SyncStatus — defaults to ACTIVE when absent on read (D13 lazy default)
```

Reconciliation of existing status concepts (no data migration; read-time
mapping per D13):

| Collection | Existing concept | Sync `status` mapping |
|---|---|---|
| `medications` | `MedicationStatus{ACTIVE,DISCONTINUED}` | `DISCONTINUED` stays a domain field; sync `status=ARCHIVED` only on delete. Discontinue ≠ delete. |
| `goals` | `GoalStatus{ACTIVE,COMPLETED,ARCHIVED}` | Domain `ARCHIVED` and sync `ARCHIVED` unified; `COMPLETED` is a live (non-tombstone) state. |
| `phases`/`steps` | `PhaseStatus`, `done` | Domain status retained; sync `status=ARCHIVED` only on delete. |
| catalogs (`drugs`,`foodCatalog`,`equipment`) | alias-pointers / `EquipmentStatus` | Unchanged; read-only on device, not part of the per-user sync set. |
| all others | (none) | New `status` field, default `ACTIVE`. |

In-scope per-user collections (the sync set): `bodyComposition`,
`bloodReadings`, `bloodTestReports`, `medications` (+ `adherence`, `history`),
`protocols`, `goals` (+ `phases`, `steps`), `goalChatThreads` (+ `messages`),
`nutritionDailyLogs`, `nutritionDays/entries`, `nutritionTargets`, `locations`,
`dailyMetrics`, `deviceSyncs`, `dexaScans`, `weeklyWorkoutAggregates`,
`workouts` (when implemented), and the user profile (`users/{uid}`).

### Android — Room schema (D5, D8)

A single SQLCipher-encrypted database `hf-offline.db`. Two structural tables
plus one mirror table per in-scope collection.

```kotlin
// Per-collection mirror row (example: medications)
@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String,            // = backend UUID (client-minted on create)
    val payloadJson: String,               // Moshi-serialized domain model
    val lastUpdate: Long,                   // server epoch millis (cursor + LWW key)
    val status: String,                     // ACTIVE | ARCHIVED (tombstone)
    val dirty: Boolean,                     // has an unsynced local mutation
    val syncState: String,                  // SYNCED | PENDING | FAILED  (drives D11 badges)
)

// Sync cursor (one row)
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: String?,                    // opaque server cursor (D6)
    val schemaVersion: Int,                 // protocol version (D13)
    val lastFullSyncAt: Long?,
)

// Outbox (D7)
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val mutationId: String,     // = idempotency key (client UUID)
    val entityTable: String,
    val entityId: String,
    val op: String,                         // CREATE | UPDATE | DELETE
    val payloadJson: String?,               // null for DELETE
    val originDeviceId: String,             // D18 suppression
    val seq: Long,                          // per-entity ordering (D7)
    val attempts: Int,
    val nextAttemptAt: Long,                // exponential backoff (D7)
    val createdAt: Long,
)
```

**Outbox reducer (D7):** before drain, mutations for the same `entityId`
collapse — `CREATE`+`UPDATE` ⇒ single `CREATE` with merged payload;
`CREATE`+…+`DELETE` ⇒ no-op (nothing ever reached the server); `UPDATE`+`DELETE`
⇒ `DELETE`. This is a pure function and the primary unit-test target.

---

## API Contracts

### `GET /api/me/sync` — unified delta read (D6)

Request:

```
GET /api/me/sync?since=<cursor>&limit=500&schemaVersion=1
Authorization: Bearer <id-token>
```

Response:

```json
{
  "schemaVersion": 1,
  "serverTime": "2026-06-02T18:04:11.482Z",
  "changes": [
    {
      "collection": "medications",
      "id": "8f3c…",
      "status": "ACTIVE",
      "lastUpdate": "2026-06-02T18:03:55.120Z",
      "doc": { "...": "full domain payload" }
    },
    {
      "collection": "bloodReadings",
      "id": "12ab…",
      "status": "ARCHIVED",            // tombstone — delete locally
      "lastUpdate": "2026-06-02T18:02:10.004Z",
      "doc": null
    }
  ],
  "nextCursor": "eyJ0cyI6MTc…",
  "hasMore": true,
  "killSwitch": false                   // D13: true ⇒ client drops to live-network mode
}
```

- `since` omitted ⇒ initial full sync (D14 windows the heavy series).
- `nextCursor` is opaque (encodes server timestamp + tiebreaker doc path).
- If the server's `schemaVersion` differs from the request, the client wipes
  Room and restarts from `since` empty (D13).

### Idempotent writes (D7)

All existing mutating endpoints accept the contract additions:

```
POST/PUT/PATCH/DELETE /api/me/...
Idempotency-Key: <mutationId>        // safe to replay; server upserts
X-HF-Origin-Device: <originDeviceId> // D18 fan-out suppression
Body includes client-minted "id" (UUID) on create
```

Server behavior: a write with a previously-seen `Idempotency-Key` is a no-op
returning the current state (keys retained in a short-lived store, e.g. 7 days).
DELETE sets `status=ARCHIVED` + bumps `lastUpdate` (never hard-deletes).

### FCM token registry (D18)

```
PUT    /api/me/devices/fcm    { "token": "...", "deviceId": "..." }   // register/refresh
DELETE /api/me/devices/fcm    { "deviceId": "..." }                    // on sign-out
```

Stored at `users/{uid}/fcmTokens/{deviceId}`. On any write the
`SyncChangePublisher` fans out a **data-only** message
`{ "type": "sync", "collections": ["medications"] }` to every token for the
user **except** the `originDeviceId` that produced the change.

---

## Sync engine design

**Pull (delta):** `SyncEngine.pull()` loops `GET /api/me/sync?since=cursor`
until `hasMore=false`, applying each change in a Room transaction — upsert for
`ACTIVE`, delete for `ARCHIVED` — but **only if** the incoming `lastUpdate` ≥ the
local row's `lastUpdate` AND the local row is not `dirty` with a higher-priority
pending mutation (LWW, D3). Persists `nextCursor` last.

**Push (outbox drain):** `OutboxRepository.drain()` runs the reducer, then
replays surviving mutations in `seq` order, attaching `Idempotency-Key` and
`X-HF-Origin-Device`. On success the mirror row flips `dirty=false`,
`syncState=SYNCED`, and adopts the server-returned `lastUpdate`. On failure it
sets `syncState=FAILED`, increments `attempts`, schedules `nextAttemptAt` with
exponential backoff. Drain is triggered immediately on connectivity-regained
(D10) and after every local write.

**Triggers (WorkManager, D10):**

| Trigger | Worker | Network constraint |
|---|---|---|
| FCM data message | `SyncWorker` (expedited) | connected |
| Periodic floor (~6h) | `PeriodicSyncWorker` | connected; prefers unmetered for heavy backfill |
| App foreground | direct `SyncEngine.pull()` | connected |
| Connectivity regained | `OutboxDrainWorker` + pull | connected |

**Conflict resolution (D3):** document-level LWW keyed on server `lastUpdate`.
A local dirty edit that loses to a newer server doc is discarded and the UI
shows the "updated elsewhere" note (D11). Derived fields never participate —
those collections are pull-only (D9).

---

## Implementation Phases

> Delivered as one effort (D19); phases are tracking + verification units (D16).
> Each phase's **Verification** block lists the exact commands, expected output,
> and the observable functional assertion required to flip its
> [status](#status-tracking) to ✅. Evidence (pasted test output / observed
> state) is mandatory.

### Phase 0 — Backend sync contract & soft-delete

| File | Action | Description |
|---|---|---|
| `backend/core/.../sync/SyncStatus.java` | Create | `ACTIVE`/`ARCHIVED` enum |
| `backend/core/.../**/*.java` (in-scope records) | Modify | Add `status`; treat missing as `ACTIVE` on read (D13) |
| `backend/core/.../**/*Service.java` (in-scope) | Modify | `delete()` ⇒ set `status=ARCHIVED` + `serverTimestamp()`; never hard-delete |
| `backend/persistence/.../FirestoreMapper.java` | Modify | Map `status`; default-to-ACTIVE on absent field |

**Verification (Phase 0):**
1. `./gradlew :backend:core:test :backend:persistence:test` → all green.
2. New unit test: deleting a record sets `status=ARCHIVED` and bumps
   `lastUpdate`; a record with no `status` field reads back as `ACTIVE`.
3. Functional: via REST, create then DELETE a blood reading; assert the doc
   still exists in Firestore with `status=ARCHIVED` (paste the document).

### Phase 1 — Backend delta API & idempotent writes

| File | Action | Description |
|---|---|---|
| `backend/api/.../sync/SyncController.java` | Create | `GET /api/me/sync` with cursor + pagination + `killSwitch` + `schemaVersion` |
| `backend/core/.../sync/SyncService.java` | Create | Cross-collection change query ordered by `lastUpdate`; tombstone emission; cursor encode/decode |
| `backend/api/.../**/*Controller.java` (in-scope) | Modify | Accept client `id` on create; honor `Idempotency-Key`; record `X-HF-Origin-Device` |
| `backend/core/.../sync/IdempotencyStore.java` | Create | Short-TTL replay guard |
| `backend/app/.../firestore.indexes` | Modify | Composite indexes on `lastUpdate` per collection |

**Verification (Phase 1):**
1. `./gradlew :backend:api:test :backend:core:test` → green.
2. MockMvc test: write N docs, call `/api/me/sync` with empty cursor, page
   through to `hasMore=false`, assert the full set incl. one tombstone returns
   exactly once.
3. Replay test: POST the same `Idempotency-Key` twice ⇒ single document, second
   call returns current state.

### Phase 2 — Backend FCM registry & fan-out

| File | Action | Description |
|---|---|---|
| `backend/api/.../devices/DeviceController.java` | Create | `PUT/DELETE /api/me/devices/fcm` |
| `backend/core/.../push/FcmTokenRepository.java` | Create | `users/{uid}/fcmTokens` CRUD |
| `backend/core/.../push/SyncChangePublisher.java` | Create | App-event on every in-scope write (incl. Google Health webhook) |
| `backend/integrations/.../push/FcmFanoutService.java` | Create | Send data-only message to user tokens minus `originDeviceId` |
| `backend/app/build.gradle.kts` | Modify | Add `firebase-admin` |

**Verification (Phase 2):**
1. `./gradlew :backend:integrations:test` → green (fake FCM transport).
2. Test: a write with `X-HF-Origin-Device=A` fans out to tokens B,C but **not**
   A; assert the captured message list.

### Phase 3 — Android local store (Room + SQLCipher)

| File | Action | Description |
|---|---|---|
| `android/core-data/.../db/HfDatabase.kt` | Create | Room DB, SQLCipher `SupportFactory`, Keystore key |
| `android/core-data/.../db/*Dao.kt`, `*Entity.kt` | Create | Mirror tables + `sync_state` + `outbox` |
| `android/core-data/.../db/DbWipe.kt` | Create | Delete DB file on sign-out (D5) |
| `android/core-data/.../di/DbModule.kt` | Create | Hilt provisioning |

**Verification (Phase 3):**
1. `./gradlew :android:core-data:testDebugUnitTest` → green.
2. Instrumented DAO test (`connectedDebugAndroidTest`): upsert/query/tombstone
   delete round-trips; DB opens only with the Keystore key.
3. Functional: sign out ⇒ assert `hf-offline.db` file is gone.

### Phase 4 — Android sync engine (pull + outbox + conflict + triggers)

| File | Action | Description |
|---|---|---|
| `android/core-data/.../sync/SyncEngine.kt` | Create | Delta pull loop, cursor persistence, LWW apply, schemaVersion/kill-switch handling |
| `android/core-data/.../sync/OutboxRepository.kt` | Create | Reducer + ordered drain + backoff (D7) |
| `android/core-data/.../sync/ConflictResolver.kt` | Create | Document LWW on server `lastUpdate` (D3) |
| `android/core-data/.../sync/SyncWorker.kt` etc. | Create | WorkManager workers + triggers (D10) |
| `android/core-data/.../net/SyncApi.kt` | Create | Retrofit binding for `/api/me/sync` + device endpoints |

**Verification (Phase 4):**
1. `./gradlew :android:core-data:testDebugUnitTest` → green; includes
   reducer collapse cases (create→edit→delete) and cursor math.
2. MockWebServer test: paginated delta with a tombstone applies correctly;
   schemaVersion bump triggers wipe+resync; `killSwitch=true` disables DB-of-truth.
3. Functional (instrumented, airplane mode simulated): enqueue an offline
   create, restore network, assert it reaches the (mock) server with its
   `Idempotency-Key`.

### Phase 5 — Android read-path refactor (Room as source of truth)

| File | Action | Description |
|---|---|---|
| `android/core-data/.../**/*RepositoryImpl.kt` (in-scope) | Modify | Reads return Room Flows; writes go through outbox + optimistic local upsert |
| `android/feature-*/.../*ViewModel.kt` (in-scope) | Modify | Observe repository Flows; remove direct network reads |

**Verification (Phase 5):**
1. `./gradlew :android:testDebugUnitTest` → green across feature modules.
2. Functional: with network fully disabled from cold start (after one prior
   online sync), every in-scope screen renders its data from Room; an edit
   appears instantly and is marked PENDING.

### Phase 6 — Android FCM client + first-run + sync UX

| File | Action | Description |
|---|---|---|
| `android/app/.../push/HfMessagingService.kt` | Create | FirebaseMessagingService → expedited `SyncWorker`; token register/refresh |
| `android/app/.../push/TokenRegistration.kt` | Create | `PUT` on sign-in, `DELETE` on sign-out (D18) |
| `android/app/.../sync/FirstSyncGate.kt` | Create | Brief blocking initial sync (heavy series = last 14 days) + lazy backfill (D14) |
| `android/core-ui/.../SyncStatusBar.kt`, badges | Create | Global indicator + per-row PENDING/FAILED badges + retry (D11) |
| `android/app/google-services.json` | Add | Firebase config (public identifiers only — safe to commit; restrict the API key). Backend Admin SA JSON stays in Secret Manager. |

**Verification (Phase 6):**
1. `./gradlew :android:assembleDebug` → builds.
2. Functional: backend write on device A triggers an FCM pull on device B that
   surfaces the change within ~30s (paste logs/observation).
3. Functional: fresh sign-in shows the blocking first-sync screen, then the
   dashboard; old history fills in afterward.

### Phase 7 — Hardening, full E2E & convergence

| File | Action | Description |
|---|---|---|
| `android/.../androidTest/SyncE2ETest.kt` | Create | Airplane-mode CRUD → reconnect → assert server state |
| `android/.../androidTest/ConvergenceTest.kt` | Create | Two-client edit → both converge under LWW |
| `backend/.../SyncContractIntegrationTest.java` | Create | End-to-end delta + idempotency + fan-out |
| `docs/decisions/ADR-0007-android-offline-first-sync.md` | ✅ Created | Records the architecture decision set (D1–D19) |

**Verification (Phase 7):**
1. Full suites green on backend + Android (unit + instrumented).
2. Convergence: two clients edit the same record offline; after both sync, both
   show the higher-`lastUpdate` value and no duplicate/ghost rows.
3. Kill-switch drill: flip `killSwitch=true`, assert clients fall back to
   live-network mode without crashing.

---

## Testing strategy (D15)

| Layer | What | Where |
|---|---|---|
| Unit (JVM) | Conflict/LWW resolver; outbox reducer (create→edit→delete collapse); cursor encode/decode | `core-data` unit tests; `backend:core` tests |
| DAO (instrumented) | Room upsert/query/tombstone; SQLCipher open-with-key; sign-out wipe | `core-data` `androidTest` |
| Protocol (integration) | MockWebServer delta incl. tombstones + pagination; idempotent replay; schemaVersion/kill-switch | `core-data` tests; backend MockMvc |
| Push | Fan-out with origin suppression (fake FCM transport) | `backend:integrations` |
| Functional E2E | Airplane-mode create/edit → reconnect → server state; FCM-triggered pull; two-client convergence; first-run gate | Android `androidTest` + backend integration |

A phase is **not done** until its listed tests are green **and** its functional
scenario is demonstrably verified with pasted evidence (D16).

---

## Definition of Done

The feature is done when **all** hold:

1. Every in-scope screen renders entirely from Room and is fully usable with
   the network disabled (after one prior online sync).
2. An edit/create/delete made offline is durably queued, survives app restart,
   and reaches the backend automatically on reconnect (verified end-to-end).
3. A backend change (incl. a Google Health webhook write) reaches a backgrounded
   second device via FCM and appears within ~30s; the originating device does
   not redundantly self-pull.
4. Deleting on one device removes the row on another via tombstone, even if the
   second device was offline at delete time.
5. Two devices editing the same record converge deterministically on the
   higher server `lastUpdate`; no ghost rows, no clobbered server-derived data.
6. Sign-out wipes the encrypted DB and deletes the device's FCM token.
7. The kill-switch reliably reverts clients to live-network mode.
8. All test layers in the strategy table are green in CI.
9. ADR recorded; `android-web-parity-roadmap.md` updated; this document's status
   table reflects reality.

---

## Status Tracking

Legend: ✅ done · ⏳ in progress / partial · ❌ not started.
"Pushed" = merged/pushed to the working branch.

| Phase | Implemented | Tested | Pushed | Note |
|---|---|---|---|---|
| 0 — Backend sync contract & soft-delete | ❌ | ❌ | ❌ | |
| 1 — Backend delta API & idempotent writes | ❌ | ❌ | ❌ | |
| 2 — Backend FCM registry & fan-out | ❌ | ❌ | ❌ | |
| 3 — Android local store (Room+SQLCipher) | ❌ | ❌ | ❌ | |
| 4 — Android sync engine | ❌ | ❌ | ❌ | |
| 5 — Android read-path refactor | ❌ | ❌ | ❌ | |
| 6 — Android FCM + first-run + sync UX | ❌ | ❌ | ❌ | |
| 7 — Hardening, E2E & convergence | ❌ | ❌ | ❌ | |

---

## Risks & open questions

- **Initial-sync payload size.** The eager first-sync window for heavy
  time-series is fixed at **14 days** (D14); CRUD domains sync in full. Older
  history pages in lazily. Revisit only if even 14 days proves heavy on real
  volumes.
- **FCM on non-Play devices.** Data messages won't arrive; the periodic floor
  (D10) is the only backstop there — acceptable per decision, flagged for QA.
- **Firestore index/cost.** A cross-collection `lastUpdate`-ordered sync query
  needs per-collection composite indexes and may increase read cost; monitor.
- **Firebase credentials.** `google-services.json` (Android) contains only
  public identifiers (project number/sender ID, app ID, package name,
  restrictable Android API key) — **safe to commit**, with the API key
  restricted by package name + signing SHA-1 in GCP. The **secret** is the
  backend Firebase Admin **service-account JSON** used by `FcmFanoutService`;
  it must NOT be committed (project Never rules) and is injected via Secret
  Manager like other backend credentials.
- **Tombstone growth.** No TTL purge (D2) means archived rows accumulate
  forever; revisit if Firestore storage/read costs grow.

## Related decisions

- ADR-0001 (three-component architecture) — preserved: clients read through the
  backend, not Firestore.
- ADR-0002 (Google ID tokens as auth) — FCM token registration reuses the same
  bearer auth.
- ADR-0004 (KMS envelope encryption) — server-side; unaffected, but reinforces
  why the phone must not read Firestore directly (D1).
- ADR-0007 (Android offline-first sync) — records this decision set (D1–D19).
