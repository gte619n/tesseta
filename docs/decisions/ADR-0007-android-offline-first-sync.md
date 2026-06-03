# ADR-0007: Android offline-first via a local encrypted store, delta sync, and FCM push

- Status: Accepted
- Date: 2026-06-02

## Context

The Android app today is **backend-driven with no on-device system of
record**: every screen reads live from Retrofit repositories, there is no
background sync, and there are no push notifications. Room is a declared but
entirely unused dependency. This makes the app unusable offline and laggy on
poor connections, and it gives the user no way to capture data (a dose taken,
a blood reading) without a live network.

We want the phone to be **offline-first**: a local database is the source of
truth for the UI, a sync engine reconciles it with the backend continuously,
local edits push up immediately when online and queue when not, and the backend
notifies the phone when new data is available to pull. The full implementation
plan lives in
[`IMPL-AND-20`](../plans/IMPL-AND-20-offline-first-sync.md); this ADR records
the architectural decisions that constrain it.

Several properties of the existing system shape the decision:

- **ADR-0001** mandates that clients read through the backend, **not Firestore
  directly**. A large amount of business logic — KMS decryption of Google
  Health credentials (ADR-0004), server-derived metrics, the goals
  auto-evaluation engine, nutrition aggregates — lives server-side and is not
  reproducible on the client.
- The backend has **no change-feed and no push infrastructure**. Every per-user
  record already carries server `createdAt`/`updatedAt` timestamps, but status
  is modeled inconsistently (domain enums, catalog alias-pointers, hard
  deletes), and there is no way for a client to learn that a record was deleted
  while it was offline.
- A meaningful share of write paths are **inherently online**: PDF/DEXA upload,
  meal-photo capture, drug lookup, and goals chat are all SSE/multipart AI
  operations that cannot run without connectivity.
- The data is **PHI-grade** (blood panels, medications, DEXA scans), so any
  on-device store must be encrypted and disposable on sign-out.

Three broad strategies were considered for the "notify and read" path:

1. **Direct Firestore listeners on the client.** Attach the Firestore SDK to
   the phone and listen to the user's subtree live. Gives real-time updates
   essentially for free, but **breaks ADR-0001** — it bypasses all server-side
   business logic, exposes the raw Firestore schema and security rules to the
   client, and cannot deliver server-derived data correctly.
2. **Poll-only.** WorkManager polls a delta endpoint on an interval. Simple, no
   Firebase, but not near-real-time and wasteful of battery/requests.
3. **Backend-mediated delta sync + FCM silent push.** The client reads a
   backend delta API; the backend emits a silent FCM data message ("collection
   changed → pull") that wakes a sync job. Preserves the backend boundary at the
   cost of building a change-feed and a push fan-out.

Constraints that influenced the choice:

- **Architecture boundary.** Preserving ADR-0001 is non-negotiable; the client
  must not become a second reader/writer of Firestore semantics.
- **Deletion visibility.** An offline device must be able to learn about
  deletions, which a hard-delete model cannot express in a delta feed.
- **Conflict simplicity.** Multi-device editing must converge deterministically
  without a heavyweight merge engine or user-facing conflict prompts.
- **PHI hygiene.** Health data at rest on a phone must be encrypted and removed
  when the user signs out.
- **Push is best-effort.** FCM data messages are dropped under Doze, on
  force-stopped apps, and on devices without Play Services, so push can never be
  the *only* freshness mechanism.

## Decision

Adopt **backend-mediated delta sync with FCM silent push** (option 3), with a
local encrypted store as the UI source of truth.

- **Local store.** Room encrypted with **SQLCipher** (key in the Android
  Keystore); the database file is **deleted on sign-out**. In-scope ViewModels
  read **only** from Room (reactive Flows); the network layer's sole job is to
  fill/refresh the DB.
- **Sync contract.** Every in-scope per-user collection carries a universal
  `lastUpdate` (server timestamp) and `status` ∈ {`ACTIVE`, `ARCHIVED`}.
  **Hard deletes become soft-deletes**: a delete sets `status=ARCHIVED` and
  bumps `lastUpdate`, so the delta feed can surface tombstones to offline
  devices. Existing domain enums (`MedicationStatus`, `GoalStatus`) are
  reconciled into this scheme; shared catalogs keep their alias-pointer scheme
  and are mirrored read-only. There is **no TTL purge** of tombstones.
- **Delta read.** A single unified `GET /api/me/sync?since=<cursor>` returns all
  changed in-scope docs (including tombstones) with cursor-based pagination,
  a protocol `schemaVersion`, and a `killSwitch` flag.
- **Writes.** The phone **mints client UUIDs** and an idempotency key per
  mutation; the backend upserts safely on replay. Offline mutations queue in an
  **outbox**, replay **in order per entity** with exponential backoff, and an
  offline create→edit→delete collapses to its net effect before drain.
- **Conflicts.** **Document-level last-write-wins keyed on the server clock**
  (`FieldValue.serverTimestamp()`); client clocks are never trusted for
  ordering. Resolution is silent (no prompts). **Server-computed data is a
  read-only mirror** and never enters the outbox, so the client cannot clobber
  goal-step evaluation, aggregates, or Google-Health-sourced metrics.
- **Push.** A per-user FCM **device-token registry**
  (`users/{uid}/fcmTokens/{deviceId}`); the app registers on sign-in and deletes
  on sign-out. On any write the backend fans out a silent data message to the
  user's tokens **except the originating device** (`originDeviceId`
  suppression). FCM only *triggers* a pull — it never carries the data itself,
  preserving the backend boundary.
- **Freshness backstop.** Because FCM is best-effort, the client also syncs on a
  **periodic WorkManager floor (~6h)**, on **app foreground**, and on
  **connectivity-regained** (which also drains the outbox immediately).
- **Online-only AI flows** (PDF/DEXA upload, meal-photo capture, drug lookup,
  goals chat) are **disabled offline** with a clear "needs connection"
  affordance; nothing is queued.
- **Cutover & governance.** No data migration: a missing `status` reads as
  `ACTIVE` and is stamped going forward. A `schemaVersion` bump forces the
  client to wipe Room and full-resync; a remote **kill-switch** reverts any
  client to live-network mode if the engine misbehaves in production.
- **Scope.** All per-user collections with a non-AI write path are
  offline-capable. **Wear OS is deferred** (stays a read-only token relay).

## Consequences

Positive:

- The app works identically online and offline for every non-AI feature; edits
  are durable the moment they're made.
- ADR-0001 is preserved — the phone never reads or writes Firestore directly,
  and all server-side logic (KMS, derived metrics, goal evaluation) stays
  authoritative. Server-derived data cannot be clobbered by the client.
- Deletions propagate to offline devices via tombstones; multi-device editing
  converges deterministically on the server clock with no merge engine and no
  user-facing conflict prompts.
- PHI is encrypted at rest and wiped on sign-out; FCM carries no health data.
- The kill-switch and `schemaVersion` give a safe runtime escape hatch and a
  clean protocol-evolution path, de-risking the rollout.

Negative:

- Significant new surface: a backend change-feed, soft-delete across all
  collections, an idempotency store, a Firebase Admin dependency and token
  registry, plus a client sync engine, outbox, and a read-path refactor of
  ~30 ViewModels onto Room.
- A new hard dependency on **Firebase/FCM** and on **SQLCipher**. The backend
  Admin service-account credential becomes sensitive state to manage via Secret
  Manager (the `google-services.json` client config is public and committable).
- The cross-collection `lastUpdate`-ordered delta query needs per-collection
  composite indexes and may raise Firestore read cost.
- Tombstones accumulate indefinitely (no TTL purge), growing storage over time.
- FCM is best-effort; on Doze/force-stopped/non-Play devices freshness falls
  back to the ~6h floor, so "near-real-time" is not guaranteed everywhere.
- Document-level LWW can drop a concurrent edit to a *different field* of the
  same document; accepted as a deliberate simplicity trade over field-level
  merge.

## Revisit when

- Tombstone volume or the delta query's read cost becomes material — at which
  point a TTL purge (with a periodic full-resync safety net) is warranted.
- Document-level LWW produces real user-visible data loss on multi-field
  documents (e.g. medications), justifying field-level merge for specific
  collections.
- Wear OS needs its own offline capability, requiring a dedicated store/sync
  path and its own ADR.
- A first-party Firestore-offline or sync product emerges that could deliver the
  same properties without bypassing the backend boundary.
