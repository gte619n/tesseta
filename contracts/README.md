# Golden wire-contract fixtures (IMPL-IOS-01, Phase 0A)

These JSON files are the **canonical wire shapes** the mobile clients bind to.
They are the anti-drift keystone: the backend and the shared KMP client both
have tests that fail the moment a DTO drifts from the shape captured here.

## Why this exists

The repo already has a narrower version of this idea for *collection names*:

- `docs/reference/sync-emitted-collections.txt` — the one canonical list of every
  `collection` string the delta reader can stamp on a change.
- `backend/.../persistence/sync/SyncEmittedCollectionsContractTest.java` — asserts
  `FirestoreSyncChangeReader.emittedCollectionNames()` equals that file, and the
  Android `CollectionRegistryContractTest` proves the client routes each one.
  That closed the "backend emits a collection the client silently drops" bug
  class from *convention* to *enforced*.

This directory **generalizes that pattern from collection names to full
payloads**. Instead of just proving both sides agree on *which* collections
exist, these fixtures prove both sides agree on the *exact field-level JSON* of
every synced document and every shared envelope.

## The two binding tests

1. **Backend serialize test** — serializes the real production DTOs/records
   (e.g. `SyncResponse`, `WriteResult`, `TokenResponse`, `ErrorResponse`, and one
   representative document per collection) with the backend's Jackson
   `ObjectMapper` and asserts the output equals the fixture JSON here. If a
   backend field is renamed, added, removed, or its type/format changes, this
   test fails.

2. **Shared KMP `commonTest` deserialize test** (the D3 codec-swap proof) —
   deserializes these same fixtures through the mobile client's
   `kotlinx.serialization` codecs. This proves the KMP data classes + serializers
   can round-trip the backend's real wire shape, i.e. that swapping the transport
   codec (Jackson on the server, kotlinx on the client) does **not** change the
   contract. If a KMP `@Serializable` model drifts from the backend shape, this
   test fails.

Because both tests point at the *same* files, the fixtures are the single source
of truth. Neither side can drift silently: one of the two tests will go red.

## Workflow: changing a DTO

1. Change the backend DTO/record (add a field, rename, change a type/format).
2. The **backend serialize test** goes red — its output no longer matches the
   fixture.
3. Update the fixture JSON here to the new intended shape.
4. The **shared KMP deserialize test** now goes red — the kotlinx codec doesn't
   understand the new shape yet.
5. Update the KMP `@Serializable` model + serializer to match.
6. Both tests green again → the contract, the backend codec, and the client
   codec are provably back in sync.

In short: **change a DTO → both tests fail → update the fixture + both codecs.**
There is no path that updates one side without the other test catching it.

## Layout

```
contracts/
  README.md                        ← this file
  fixtures/
    MANIFEST.md                    ← every fixture → backend DTO → collection/table
    sync/
      sync-response.json           ← GET /api/me/sync delta page envelope
      write-result.json            ← WriteResult<T> (JsonUnwrapped body + lastUpdate)
    auth/
      token-response.json          ← POST /api/auth/{exchange,refresh,dev-login}
    errors/
      error-envelope.json          ← GlobalExceptionHandler.ErrorResponse
    collections/
      <collection>.json            ← one representative document per synced collection (25)
```

## Conventions captured here

- **Timestamps** are ISO-8601 instant strings with millis + `Z`
  (e.g. `2026-06-02T18:04:11.482Z`) — the backend serializes `java.time.Instant`
  this way. `LocalDate` fields are `YYYY-MM-DD`; `LocalTime` is `HH:MM:SS`.
- **Token expiries** are epoch-millis **numbers**, not ISO strings — this matches
  `TokenResponse` (`accessTokenExpiresAt` / `refreshTokenExpiresAt` are `long`).
- **`WriteResult`** flattens its wrapped DTO's fields to the top level
  (`@JsonUnwrapped`) and adds a sibling `lastUpdate` — so `write-result.json` is a
  bloodReadings DTO's fields verbatim *plus* `lastUpdate`.
- **Tombstones**: an `ARCHIVED` change in a sync page carries `doc: null`.
- **Collection documents** each carry an `id`-style field (name is the domain
  id — `medicationId`, `readingId`, `entryId`, …; some are path-keyed like
  `date`/`weekStart`), an ISO timestamp (`updatedAt` and/or `lastUpdate`), and a
  `status`. The document fixtures are grounded in the real backend/domain models;
  see `fixtures/MANIFEST.md` for the source file backing each one.
