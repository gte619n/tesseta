# Conflict resolution — the rule per entity type

When two devices change data while one (or both) is offline, sync must converge
deterministically. This documents the rule for every synced entity, per the
offline contract ("Conflict resolution is deterministic and server-authoritative
… with explicit exceptions for append-only data which never conflict. Document
the rule per entity type.").

## The two rules

**Append-only** — each record is its own Firestore document with a
**client-minted (or deterministic) id**. Two devices adding data create
*different* documents, so they can never collide; a delete is a tombstone
(`syncStatus = ARCHIVED`), never a destructive removal. Editing the *same*
existing record resolves by the LWW rule below, but that is rare for append-only
data (you add readings/entries/sets, you don't co-edit one). This is why the
data-loss-sensitive, high-churn data is safe by construction — it was the whole
point of client-minted ids (enforced by the write contract, see
`write-contract.md`).

**Mutable, LWW** — a small set of long-lived records that are genuinely edited
in place. Resolution is **last-writer-wins keyed on the server receipt clock**
(`updatedAt = FieldValue.serverTimestamp()`), never the device clock. The server
is authoritative: the client `ConflictResolver`
(`core-data/.../sync/ConflictResolver.kt`) compares the incoming server
`lastUpdate` against the local row and, if the server doc is newer, applies it
and surfaces an "updated elsewhere" signal. Ordering uses the server clock so two
devices with skewed clocks still converge to the same winner.

## Per-entity classification

| Entity (synced collection) | Class | Id source | Notes |
|---|---|---|---|
| `bloodReadings` | Append-only | client (`KEY_GUARDED`) | one reading per doc; delete = tombstone |
| `bloodTestReports` | Append-only | client | uploaded report per doc |
| `bodyComposition` | Append-only | deterministic (metric+date) | one measurement per metric per day |
| `dailyMetrics` | Append-only | deterministic (date) | one row per day; re-ingest upserts |
| `dexaScans` | Append-only | client | one scan per doc |
| `weeklyWorkoutAggregates` | Append-only | deterministic (ISO week) | recomputed per week |
| `deviceSyncs` | Append-only | deterministic (deviceId) | last-sync marker per device |
| `nutritionDays/entries` | Append-only | client (`KEY_GUARDED`) | one logged food per doc; the churn hot path |
| `medications/adherence` | Append-only | deterministic (med,date,window) | one dose event per window |
| `medications/history` | Append-only | server UUID | audit log; **see BUG-02** (replay can dup a history row) |
| `goalChatThreads/messages` | Append-only | server | chat log; online-only write path |
| `workoutPrograms/scheduled` | Append-only (per session) + set-merge | deterministic (`{date}_{dayId}`) | see "Logged sets" below |
| `medications` | Mutable, LWW | client | name/dose/schedule edited in place |
| `protocols` | Mutable, LWW | client | edited in place |
| `goals` | Mutable, LWW | client | title/target/status edited in place |
| `goals/phases` | Mutable, LWW | client | edited in place |
| `goals/phases/steps` | Mutable, LWW | client | done-state + fields edited in place |
| `nutritionTargets` | Mutable, LWW | single per user | one active target, replaced |
| `nutritionDailyLogs` | Mutable, LWW | deterministic (date) | day totals, recomputed |
| `locations` | Mutable, LWW | client | gym edited in place |
| `workoutPrograms` | Mutable, LWW | client (`KEY_GUARDED`) | program definition edited in place |
| `goalChatThreads` | Mutable, LWW | server | thread metadata |
| user profile doc | Mutable, LWW | the user | height/sex/DOB edited in place |

## Logged sets (workout sessions) — append-only within a session

A session document (`workoutPrograms/scheduled/{date}_{dayId}`) is itself
append-only by deterministic id (two devices completing the same session upsert
the *same* doc). Its `logged` sets are the finest-grained data. The completion
`PUT` currently replaces the whole `logged` list from the submitting device;
merging by set key `(blockId, orderIndex)` so two devices' sets both survive is
the one open per-field refinement — tracked below.

## What this slice locked in vs. deferred

- **Locked in:** the classification above, and an invariant test
  (`WriteContractTest` already forbids a server-minted create for the churn
  entities; `ConflictContractTest` asserts every append-only collection here is
  backed by a client-minted/deterministic id in the write contract, so two
  devices can never write the same append-only document).
- **Deferred (DEC-18), with rationale:** true per-field merge for the *mutable*
  entities (today they resolve by document-level LWW: a concurrent edit to a
  different field on two devices keeps the later writer's whole document). This
  needs either the client `ConflictResolver` to field-diff payloads or every
  mutable PATCH to send only changed fields through `SetOptions.merge()` — a
  change to the core sync engine + every mutable write path, out of bounds for an
  incremental behaviour-preserving refactor without human review. The
  append-only-by-construction rule already protects all the high-churn,
  data-loss-sensitive data; the residual is a rare "both devices edited the same
  goal's different fields at once" case that loses one field edit, not data at
  rest. The set-merge refinement above is the highest-value next step.
