# IMPL-AND-20 — Outstanding Questions for Review

These surfaced during implementation and were resolved with a best-judgement
default (noted) so development could continue. Please confirm or redirect each.
Numbered for easy reference.

> Status legend: 🟡 awaiting your decision · ✅ confirmed · 🔁 changed per your note

## Review resolutions (2026-06-02 interview)

Decisions captured as the user reviewed each item. Work items generated here are
tracked as "queued (this branch)" or "fast-follow / CI ticket".

| # | Resolution |
|---|---|
| 3, 4, 6 | ✅ Confirmed as-is (syncStatus key; goal delete=tombstone vs archive=live; user doc emitted but reads unfiltered). |
| 8 | ✅ **done:** fan-out/idempotency now on all remaining in-scope JSON write paths — DEXA + blood-test-report `field`-PATCH & delete, goals-chat `commit` (goal+phases+steps) & thread delete, nutrition entry PATCH + ingredient re-portion; skipped (with reason): all multipart/SSE AI uploads (D17) and `protocols` (no controller). New `IdempotentNestedWriteControllerTest` proves replay=single doc for phases/steps/nutrition-entries. |
| 24, 26 | ✅ **done** — offline medication-adherence logging and offline goals nested-aggregate/phase-step editing implemented on this branch (see #24/#26 below). |
| 1, 2, 43–46 | 🔁 **Defer to a CI ticket** — add a `connectedDebugAndroidTest` emulator job + a deployed-environment FCM smoke drill; branch merges on the green unit/MockMvc gates. |
| 7, 15 | ✅ Confirmed — store the full GET DTO as mirror `payloadJson`; placeholder-then-pull for optimistic creates. No screen depends on a doc-absent computed field. |
| 32 | ✅ Confirmed — write-response `lastUpdate` stays the controller write-instant (monotonic LWW key); the pull reconciles to the true server timestamp. No read-after-write. |
| 5 | ✅ Confirmed — keep hard-delete on the Google-Health re-hydration path; body-comp is pull-only on the client. |
| 34 | ✅ **done:** nutrition entry/composite-meal/update/delete fan-out renamed from `nutritionEntries` to `nutritionDays/entries`, matching the delta feed's emitted collection exactly. No test asserted the old name. |
| 9 | ✅ **done:** TTL declared as infra-as-code in `infra/terraform/firestore_ttl.tf` (`google_firestore_field` + `ttl_config` on collection-group `idempotencyKeys`, field `expiresAt`) with a `versions.tf` provider pin; gcloud-equivalent + verify commands documented in `infra/README.md`. `FirestoreIdempotencyStore` confirmed writing `expiresAt` as a Firestore `Timestamp`. |
| 10 | ✅ **done:** `FirestoreSyncChangeReader` now enumerates each subcollection by walking down from `users/{uid}` (list the user's own medications/goals/goalChatThreads/nutritionDays/phases, query each child collection directly) — no `collectionGroup` query remains in the delta path, so it never touches another user's data. Removed the now-unused `adherence/history/phases/steps/messages/entries` COLLECTION_GROUP `updatedAt` index overrides (kept the unrelated steps-metric/kind, medications.drugId, locations.equipmentIds ones). Cursor/order/tombstone semantics unchanged; sync tests green. |
| 33 | ✅ Confirmed — adherence keyed by `(med, date)` with replay guard; transitions idempotent + fan-out. Offline adherence (#24) builds on this keying. |
| 37 | ✅ **done (server):** `GET /api/me/sync?recentSince=<ISO date>` bounds the heavy time-series (bloodReadings, bodyComposition, dailyMetrics, nutritionDailyLogs, nutritionDays/entries, weeklyWorkoutAggregates) to docs on/after the date via a pure emission filter (`SyncRecentWindow`); CRUD domains always full. Cursor key `(updatedAt, collection, id)` is untouched, so the later unbounded backfill (client omits the param) surfaces the older heavy docs with no skip/dup (re-apply is an idempotent LWW no-op). Tombstones always propagate. `SyncRecentWindowControllerTest` + `SyncRecentWindowTest` green. **Android:** pass `recentSince = today−14d` on first sync only, then omit for backfill. |
| 40 | ✅ **done** — per-row `syncState` threaded through medication/blood/nutrition models; `SyncBadge` rendered on the medications + nutrition rows. |
| 41 | ✅ **done** — drug-lookup and DEXA-upload now gated with the offline affordance (`Connectivity`/`OfflineGate`). |
| 12, 38 | ✅ Confirmed — one consolidated sign-out hook (FCM delete + Room wipe) on the real sign-out path; PHI always wiped. |
| 39 | ✅ **done** — distinct global FAILED "changes failed — retry" state from `OutboxRepository.failedCount()` in the global bar, with retry re-drain. |
| 13 | ✅ Confirmed — `fallbackToDestructiveMigration()`; local DB is a disposable cache, wipe-and-resync on schemaVersion bump. |
| 36 | ✅ Confirmed — FCM silent data-only; no `POST_NOTIFICATIONS`. User-facing reminders tracked separately (Phase 9). |
| 30 | ✅ **done** — date-ranged Room queries back body-comp `pointsInRange` + the dashboard weight/blood trend summaries (live fallback under kill-switch). |
| 11, 14, 16–23, 25, 27–29, 31, 35, 42 | ✅ Acknowledged as-recorded (implementation-detail notes; no change). #16/#17 superseded by the #24/#26 offline-completion work below. |

### Work queued on this branch (from the interview)

A second implementation pass, all verified before re-push:
1. ✅ **#8** — idempotency + client-minted id on every remaining in-scope write endpoint (backend).
2. ✅ **#24** — offline medication-adherence logging (client mints/merges the `(med,date)` row, optimistic + outbox).
3. ✅ **#26** — offline goals editing: assemble the nested aggregate from the `goalPhases`/`goalSteps` mirror tables; phase/step edits optimistic + outbox (step done/doneAt stays a server-evaluated intent, never a raw derived write).
4. ✅ **#34** — rename nutrition-entry fan-out to `nutritionDays/entries`.
5. ✅ **#9** — Firestore TTL policy on `idempotencyKeys.expiresAt` in infra-as-code.
6. ✅ **#10** — replace cross-user `collectionGroup` subcollection scan with per-user enumeration.
7. ✅ **#37** — true server-side 14-day initial-sync window (per-collection recent bound) + client wiring.
8. ✅ **#40** — wire per-row PENDING/FAILED badges into in-scope list UIs.
9. ✅ **#41** — offline "needs connection" affordance on drug-lookup + DEXA-upload.
10. ✅ **#39** — distinct global FAILED state in the sync bar.
11. ✅ **#30** — date-ranged Room queries for windowed/trend reads (body-comp range, dashboard summaries).

## Environment / verification limits

1. 🟡 **Instrumented Android tests cannot be executed here.** The repo has no
   `androidTest` source set today and this environment has no running emulator /
   physical device. Room DAO instrumented tests, the airplane-mode E2E, the
   two-client convergence test, and the live FCM delivery check (Phases 3/6/7
   verification) are **authored but not run**. They compile against the SDK; they
   need a device/emulator in CI to execute. OK to defer execution to CI, or do
   you want an emulator step added to the build now?

2. 🟡 **Live FCM fan-out is not exercised locally.** Per the plan's risk note,
   `FcmFanoutService` is a no-op unless ADC can `cloudmessaging.messages.create`.
   Backend tests use a fake transport (origin-suppression asserted there). The
   real device-to-device push is verified only in a deployed run. Acceptable?

## Decisions taken by best judgement (please confirm)

### Sync contract / data model

3. 🟡 **Sync status uses a dedicated `syncStatus` Firestore key, not `status`.**
   `medications` and `goals` already store their domain enums under `status` and
   *query* on it, so reusing `status` would have clobbered those queries. The
   universal tombstone field is therefore `syncStatus` ∈ {ACTIVE, ARCHIVED};
   `lastUpdate` maps to the existing `updatedAt` server timestamp. Confirm naming.

4. 🟡 **Goal `delete` (tombstone) vs goal `archive` (domain) are distinct.**
   `GoalService.archive` keeps the goal live/listable with domain
   `GoalStatus.ARCHIVED`; a goal **delete** sets `syncStatus=ARCHIVED` (true,
   hidden tombstone). The delta feed treats only the latter as a tombstone.
   Matches the spec's "unified" note as we read it — confirm.

5. 🟡 **`BodyCompositionRepository.deleteByUserMetricAndRange` stays a HARD
   delete.** It is an internal Google-Health re-hydration path (delete-then-
   rewrite a metric/time window), not a user-facing delete. Tombstoning it would
   leave stale rows the backfill then duplicates. Should re-hydration instead
   tombstone-then-recreate so offline devices see the churn, or is hard-replace
   acceptable for derived GH data (which is pull-only on the client anyway)?

6. 🟡 **`users/{uid}` is emitted in the delta but its reads are NOT
   archive-filtered** (auth-critical, users are never soft-deleted in-app).

### Delta API payload shape

7. 🟡 **Delta `doc` is the sanitized raw Firestore field map, not the
   per-collection GET-endpoint DTO.** Internal `syncStatus` is stripped, Firestore
   `Timestamp`→ISO string, and binary `Blob`s are dropped (so the KMS-encrypted
   Google-Health refresh token never leaves the backend). Consequence: a few
   server-*computed* fields that the GET DTOs synthesize are absent from the raw
   doc (e.g. blood's computed `reference` range block). The Android client must
   compute/derive those client-side or fetch on demand. **Flagged for Android
   read-path (Phase 5): confirm no in-scope screen depends on a computed field
   that only exists in the DTO, not the stored doc.**

8. ✅ **RESOLVED — every remaining in-scope JSON write path now carries the
   contract.** The earlier fast-follow wired goal **phases** POST, goal **steps**
   POST, **nutrition composite-meal** POST, **macro-target** PUT, **medication
   adherence** log POST, and **medication dosage / discontinue / reactivate**
   (plus medication PUT/transition + goal/phase/step update/delete/reorder
   fan-out). This follow-up closes the last gaps that *do* have a plain-JSON
   write: **DEXA** `PATCH .../field` + delete (→ `dexaScans` fan-out),
   **blood-test-report** `PATCH .../field` + delete (→ `bloodTestReports`),
   **goals-chat `commit`** (mints Goal+Phases+Steps via the same repos the
   manual CRUD uses → fans out `goals`,`goals/phases`,`goals/phases/steps`) +
   chat-thread delete (→ `goalChatThreads`), and **nutrition entry PATCH +
   ingredient re-portion PATCH**. New `IdempotentNestedWriteControllerTest`
   proves `Idempotency-Key` replay = a single document for phases / steps /
   nutrition entries. **Still deliberately skipped (D17):** the DEXA/blood-test
   **multipart+SSE PDF uploads** and the **goals-chat SSE stream** (AI,
   online-only — the durable CRUD records they produce *are* now covered);
   `protocols` (no controller in the codebase); `bodyComposition` /
   `dailyMetrics` (GET-only, Google-Health-sourced, pull-only). See #31–#35 for
   the envelope/granularity judgement calls.

9. ✅ **RESOLVED — Firestore TTL declared as infra-as-code.**
   `infra/terraform/firestore_ttl.tf` declares a `google_firestore_field` with a
   `ttl_config` block on collection-group `idempotencyKeys`, field `expiresAt`
   (plus `versions.tf` pinning the google provider). The gcloud-equivalent
   (`gcloud firestore fields ttls update expiresAt --collection-group=…
   --enable-ttl`) and a verify command are documented in `infra/README.md` for
   the period before Terraform is applied. `FirestoreIdempotencyStore` writes
   `expiresAt` as a `com.google.cloud.Timestamp` (the type a `ttl_config` acts
   on); the in-app TTL re-check still covers the unreaped window.

10. ✅ **RESOLVED — delta subcollection queries are now strictly per-user.**
    `FirestoreSyncChangeReader` enumerates the user's own parent docs under
    `users/{uid}` (medications, goals, goalChatThreads, nutritionDays, and
    goals/phases for the two-level steps) and queries each child subcollection
    directly with a COLLECTION-scoped `orderBy(updatedAt)` — no `collectionGroup`
    query remains in the delta path, so it never reads another user's documents.
    The previously-required `adherence/history/phases/steps/messages/entries`
    COLLECTION_GROUP `updatedAt` index overrides were removed from
    `firestore.indexes.json`. N+1 read pattern bounded by the user's own doc
    counts; cursor/order/tombstone semantics unchanged.

### Android client

11. 🟡 **Outbox replay uses a generic `api/me/<table>` REST shape.** Real
    endpoints differ per domain (e.g. blood is `/api/me/blood`, medications
    `/api/me/medications`). Phase 5 refines the per-domain replay path as each
    repository adopts the outbox. Also: the client adopts the server's
    post-write `lastUpdate` from a top-level `lastUpdate` field in the write
    response, falling back to device wall-clock if absent — backend write
    responses should include `lastUpdate` for correctness.

12. 🟡 **DB wipe on sign-out is wired only on the settings-screen DI instance of
    `GoogleAuthRepository`.** Other manual constructions (MainActivity bootstrap,
    Wear refresh service) don't sign out, so they keep the no-op default. Phase 6
    consolidates sign-out side-effects (DB wipe + FCM token delete) — confirm the
    single consolidated hook is acceptable.

13. 🟡 **Room uses `fallbackToDestructiveMigration()`** (no hand-written
    migrations): a `schemaVersion` bump wipes + full-resyncs at the sync layer,
    so a destructive DB fallback can never wedge the app. Intentional per D13.

### Phase 5 — read-path refactor (Room as source of truth)

14. ✅ **Per-domain outbox→endpoint map landed** (resolves #11). The replay path
    now routes each table to its real controller via
    `core-data/.../sync/OutboxEndpointRegistry.kt` (blood → `api/me/blood`,
    medications → `api/me/medications`, nutrition entries →
    `api/me/nutrition/{date}/entries`, goal steps →
    `api/me/goals/{id}/steps/{sid}`, profile → `api/me/profile`, locations →
    `api/me/locations`, body-composition → `api/me/body-composition`, nutrition
    targets → `api/me/nutrition/targets`). Unmapped tables keep the Phase-4
    generic `api/me/<table>` fallback. **All mutation→endpoint mapping lives in
    that one file.**

15. ✅ **Computed-field gap (#7) handled by storing the full DTO as the mirror
    `payloadJson` on refresh** (not the sanitized delta `doc`). Concretely, a
    blood reading's server-computed `reference` block round-trips because the
    repository's `refresh()` persists the GET-endpoint DTO the screen consumes.
    For an *optimistic offline create* (no server `reference` yet) we synthesize a
    wide placeholder `ReferenceRange` that the next pull overwrites with the
    authoritative value. Verified by
    `BloodReadingRepositoryImplTest.refresh fills the mirror …` asserting
    `reference.goodThreshold` survives Room.

16. 🟡 **Only the genuinely Flow-based domains were fully converted to
    Room-as-source-of-truth this phase: `bloodReadings` and `bodyComposition`.**
    Rationale: their domain repository interfaces already exposed reactive Flows
    (`observeReadings()` / `observeSnapshot()`), so the swap is a pure
    `RepositoryImpl` change with zero ViewModel/interface churn and no build risk.
    `bodyComposition` is pull-only (D9, Google-Health-sourced) so it never touches
    the outbox; `bloodReadings` is full optimistic-write + outbox.

17. 🔁 **RESOLVED — the suspend-`Result`/suspend-list domains were converted in
    this Phase-5 follow-up without any domain-interface→Flow churn.** Rather than
    flip every interface to a `Flow` and rework every ViewModel, the chosen
    approach keeps the existing suspend signatures and makes the `RepositoryImpl`
    read from the Room mirror (filling on a cold miss), which still satisfies D8
    (the network's only job is to fill the DB; the read returns the mirror row, not
    a live call). See #21–#28 for the per-domain decisions. Original note retained
    below for context. Reason: their domain repository interfaces are
    **suspend `Result<…>` / suspend-list pass-throughs, not Flows** (e.g.
    `LocationRepository.list(): Result<List<Location>>`,
    `GoalsRepository.goals(): List<Goal>`,
    `NutritionRepository.day(date): NutritionDay`). Making these Room-backed
    requires changing the **domain interface to a Flow** and reworking every
    consuming ViewModel (which today calls the suspend method inside `refresh()`
    and holds its own `MutableStateFlow`). That is a large, multi-screen change
    that risks the build and was out of proportion to a single phase. Additional
    nuances that argue for doing these carefully rather than mechanically:
    - **Nutrition** is **date-keyed** (`day(date)`) and its `rollup` is
      server-derived (D9). A Room-backed read needs a date-filtered mirror query,
      not the flat global `observeActive()`.
    - **Goals** read a **nested aggregate** (`GoalDeep` = phases + steps); step
      `done`/`doneAt` are pull-only and a manual toggle must go up as an explicit
      intent without clobbering server-derived fields (D9). The flat mirror tables
      exist but assembling the nested shape from them is non-trivial.
    - **Blood test reports** are coupled to the SSE upload stream (`refresh()` is
      driven off an `UploadEvent.Complete`); offline create is out of scope (D17).
    **Question:** confirm the staged approach — land `bloodReadings` +
    `bodyComposition` now, convert the suspend-`Result` domains in a fast-follow
    that also migrates their ViewModels to observe Flows — or do you want the
    domain-interface + ViewModel churn pulled into this phase?

18. 🟡 **Reusable read/write engine added:** `MirrorRepositorySupport` (observe
    Room Flow with kill-switch live-fallback; optimistic create/update/delete +
    outbox enqueue + drain trigger; `refreshInto` that won't clobber dirty rows).
    It depends on two narrow seams — `KillSwitchGate` (bound to `SyncFlags`) and
    `DrainTrigger` (bound to `SyncScheduler`) — so repositories stay unit-testable
    on the pure JVM. New in-scope repositories should be built on this helper.

19. 🟡 **Kill-switch fallback (D13) is implemented per-read:** when
    `SyncFlags.killSwitch` is latched, `MirrorRepositorySupport.observe` serves a
    one-shot live-network fetch instead of the Room Flow, and `refresh()` is a
    no-op (the engine owns live mode). This keeps a forced-to-live client
    rendering without crashing. (Range/aggregate reads like
    `bodyComposition.pointsInRange` already go straight to network.)

### Phase 6 hand-off — sync-state signals already exposed

20. ℹ️ **Per-row signal:** every mirror row carries `syncState`
    (`SYNCED|PENDING|FAILED`) and `dirty`; an optimistic write sets
    `PENDING`+`dirty`, the outbox drain flips it to `SYNCED` or `FAILED`. Phase 6
    renders the D11 badges directly off `syncState` (the DAO `observeActive()`
    rows expose it).
    **Global signal:** `OutboxRepository.pendingCount(): Flow<Int>` already feeds
    a global "syncing/pending" indicator, and `SyncEngine.updatedElsewhere:
    SharedFlow<UpdatedElsewhere>` emits the lightweight "updated elsewhere" note.
    Phase 6 wires these into `SyncStatusBar`.

### Phase 5 — remaining-domain conversion (this follow-up)

The approach for every domain below: **keep the existing domain
interface/ViewModel contract**, make the `RepositoryImpl` serve reads from the
Room mirror (one-shot network fill on a cold miss) and route the primary CRUD
writes through `MirrorRepositorySupport` (optimistic + outbox). This satisfies D8
with **zero ViewModel/test churn** for the suspend-`Result`/suspend-list domains,
which is why it was preferred over flipping interfaces to `Flow` (#17). All eight
in-scope feature modules + `:app:assembleDebug` build green (161 unit tests, 0
failures).

21. 🟡 **`profile` — fully Room-backed (reads + write).** `get()` serves the
    `userProfile` mirror (fills once from `GET /api/me` on a cold miss); a height
    edit is optimistic + outbox, carrying the full mirrored DTO so an offline edit
    shows every field. `ProfileViewModel`/test unchanged. The outbox replays the
    height edit to the registry's `PUT api/me/profile`; the live PATCH path is
    `PATCH /api/me` — flagged: the backend needs a `PUT /api/me/profile` (or the
    registry remapped) for the replay to land. Listed for Phase 6/backend.

22. 🟡 **`locations` — reads + primary CRUD Room-backed; sub-mutations on
    network.** `list()`/`get()` serve the `locations` mirror; `create`/`update`/
    `delete` are optimistic + outbox to `api/me/gyms` (registry corrected from the
    stale `api/me/locations`). `setDefault`, equipment add/remove/specs and the
    multipart cover-photo upload stay on the network (server-evaluated /
    online-only per D17) and refresh the affected mirror row after. Six gym
    ViewModels + 4 tests unchanged.

23. 🟡 **`medications` — reads + create/update/delete Room-backed; state
    transitions on network.** `list()` serves the `medications` mirror (full
    `MedicationDto` payload incl. embedded adherence/dosage-period/correlated-marker
    fields, so the list & detail round-trip, #15). `create`/`update`/`delete` are
    optimistic + outbox. `changeDose`/`discontinue`/`reactivate` stay on the network
    — they are **server-evaluated transitions** that close/open dated dosage periods
    and append `medicationHistory` (D9); replaying them as raw doc writes would
    clobber the server's history math. `get()` (detail incl. pull-only `history`)
    and `todaysDoses()` (adherence-computed checklist) read live, falling back to the
    mirror offline. `MedicationRepositoryTest` rewritten to the Room-backed contract.

24. ✅ **done:** offline medication-adherence logging shipped. `logDose`/`undoDose`
    now write an optimistic `medicationAdherence` mirror row keyed by a composite
    `"<med>/<date>/<window>"` id (the `(med,date)` keying the backend uses) +
    enqueue an outbox mutation: a log is a CREATE → `POST .../adherence`, an undo is
    a DELETE → `DELETE .../adherence/{date}/{window}`. The replay sends a
    deterministic `(med,date)`-derived `Idempotency-Key` (`adherence:<med>:<date>`,
    via `OutboxEndpointRegistry.idempotencyKey`) + `X-HF-Origin-Device`. The `today`
    checklist overlays the mirror (`MedicationAdherenceDao.observeAll`, tombstone ⇒
    undone) so an offline log/undo shows instantly; the server projection reconciles
    on pull. `DefaultAdherenceRepository`/`DefaultMedicationRepository` rewritten;
    `AdherenceRepositoryTest` + a `MedicationRepositoryTest` overlay test prove the
    offline log shows immediately + enqueues. *(originally: left on the network.)*

25. 🟡 **`nutrition` — entries Room-backed (optimistic + outbox); aggregates +
    AI flows pull/network.** Entries are mirrored per-entry into `nutritionEntries`
    under a composite id `"<date>/<entryId>"`; `day(date)` reassembles the
    `NutritionDay` from the mirrored entries for that date, **re-deriving meal-group
    subtotals + day totals client-side** (a straight sum of each entry's frozen
    macro snapshot — the same arithmetic the backend does). `addEntry`/`patchEntry`/
    `deleteEntry` are optimistic + outbox to `api/me/nutrition/{date}/entries`. The
    macro `target` is mirrored (`nutritionTargets`, singleton `PUT
    api/me/nutrition/target` — registry corrected from the stale plural `targets`)
    and its write is optimistic. The `range` daily rollups (`nutritionDailyLogs`,
    D9) stay pull-only. The composite-meal create + per-ingredient re-portion drive
    AI image generation (D17) — they stay on the network and refresh the date's
    mirror after. `NutritionTodayViewModel` unchanged (its write-then-`day()` reload
    now reflects the optimistic mirror).

26. ✅ **done:** offline goals nested-aggregate editing shipped. `goalDeep(id)` is
    now **assembled offline** from the flat `goals`/`goalPhases`/`goalSteps` mirror
    tables (`GoalsRepository.assembleDeep`, preserving the `phaseOrder`/`stepOrder`
    cascade), with a live `refreshDeep` fan-out into the three tables when online.
    Structural phase/step CRUD (`createPhase`/`updatePhase`/`deletePhase`,
    `createStep`/`updateStep`/`deleteStep`) is optimistic + outbox: composite ids
    `"<goal>/<phase>"` / `"<goal>/<phase>/<step>"` replay to the real phase/step
    controllers (`POST/PATCH/DELETE`, mapped in `OutboxEndpointRegistry`). **D9
    boundary held:** step `done`/`doneAt` is server-derived — a manual toggle stays
    an explicit `PATCH .../steps/{sid}` **intent** (`setStepDone`/`resetStepToAuto`,
    network) NEVER enqueued through the structural-edit outbox, and `updateStep`
    carries the existing done/doneAt verbatim so a replay can't clobber it;
    `reevaluate` stays online. New `GoalsRepositoryTest` proves offline assembly +
    PENDING phase/step edits + done preserved. *(originally: deep aggregate +
    assembly left as a follow-up.)*

27. 🟡 **`daily-metrics` — fully Room-backed, pull-only (D9).** The dashboard's
    30-day `loadRecent()` fills the `dailyMetrics` mirror from the network window
    and serves the rows from Room (keyed by date, full `DailyMetricDto` payload).
    Google-Health-sourced, so it **never enqueues to the outbox**.

28. 🟡 **`dexa` records list + `bloodTestReports` list — Room-backed reads; AI
    uploads on network.** Both already exposed Flow reads (`observeScans()` /
    `observeReports()`), so the swap is a pure `RepositoryImpl` change. `refresh*()`
    fills the mirror (`dexaScans` stores the full `DexaScanSummaryDto`;
    `bloodTestReports` the full `BloodTestReportDto`); the list observes Room with a
    kill-switch live fallback. The **PDF uploads stay on the network** (multipart
    SSE, online-only AI per D17): a blood-report upload refreshes the mirror on
    `UploadEvent.Complete`; DEXA detail/`patchField`/PDF-download stay live and
    refresh the list after a mutating call. Neither upload is enqueued.

29. ℹ️ **`protocols` has no standalone client repository to convert.** On Android,
    a protocol is only referenced as `Medication.protocolId`; there is no
    `ProtocolRepository`/screen in the app module set, so there was nothing to make
    Room-backed. The `protocols` mirror table + sync engine handle it on the pull
    side; a future protocols UI would build on `MirrorRepositorySupport` like the
    others. (Backend create idempotency for protocols remains the #8 fast-follow.)

30. ✅ **done:** windowed/trend reads now Room-backed via date-ranged DAO queries.
    Added `BodyCompositionDao.pointsInRange(from,to)` and
    `BloodReadingDao.readingsInRange(from,to)` (both filtered on the indexed
    `lastUpdate` = sample epoch millis). `bodyComposition.pointsInRange` reads the
    range from Room (cold-miss fill), and the dashboard weight summary
    (`DashboardBodyCompositionRepositoryImpl`, ~120d window) + blood-marker trend
    (`DashboardBloodMarkerRepositoryImpl`, 365d window) now decode the mirror
    payloads over the ranged query instead of calling the network. Each keeps a live
    fallback under the kill-switch (D13). *(originally: stayed network-served.)*

### Backend fast-follow (#8 completion + #11 + Phase 7 E2E — this follow-up)

31. 🟡 **Top-level `lastUpdate` (#11) is delivered via a `@JsonUnwrapped`
    envelope, not a per-DTO field.** A new `api/.../sync/WriteResult<T>` wraps the
    existing response DTO with `@JsonUnwrapped` (so the DTO's fields stay verbatim
    at top level) plus a sibling `lastUpdate` (an `Instant`, ISO-8601 — same
    encoding as the delta feed's `lastUpdate`). Existing read-path consumers that
    read the DTO's own fields are unaffected; the Android outbox reads the new
    top-level **`lastUpdate`** (confirmed serializing as a sibling, not nested,
    against Spring's real Jackson in `WriteResultSerializationTest`). **The field
    name the client adopts is `lastUpdate`, identical across every in-scope write
    response.** Confirm this envelope approach over editing ~8 DTO records.

32. 🟡 **`lastUpdate` value source: server `updatedAt` where the entity carries
    one, else the controller's post-write `Instant.now()`.** `Goal`, `Location`,
    `Medication`, `MacroTarget` etc. carry an `updatedAt`, but at the controller
    the freshly-built record's `updatedAt` is null (the Firestore repo stamps the
    authoritative server timestamp on `save`). To return a non-null `lastUpdate`
    today, the controller stamps a single `Instant.now()` into the record AND
    returns it as `lastUpdate` — so the value the client adopts is the controller's
    write instant, not the Firestore server-write instant (they differ by the write
    RTT, sub-second). The next delta pull overwrites the row with the true server
    `updatedAt`. **Acceptable** as an LWW ordering key (monotonic per device, and
    the pull reconciles), but flagging that the write-response `lastUpdate` is
    *not* byte-identical to the eventual delta `lastUpdate` for the same write.
    Alternative would be re-reading the doc post-save (an extra Firestore read per
    write) — deemed not worth it.

33. 🟡 **Adherence / dosage / discontinue / reactivate are upserts and state
    transitions, not id-minting creates — wired for fan-out + `lastUpdate`, and
    adherence also for `Idempotency-Key` replay, but NOT client-minted id.**
    Adherence is keyed by `(medication, date)` (no generated doc id to honor), so
    its idempotency scope is `medicationAdherence:log:{med}:{date}` and a replay
    returns the current day's log. The dose/discontinue/reactivate transitions are
    naturally idempotent state changes (no `Idempotency-Key` guard added) but now
    fan out. Confirm this is the intended granularity.

34. 🟡 **Fan-out collection name vs delta collection name mismatch for nutrition
    entries (pre-existing, carried forward).** The entry/composite-meal writes fan
    out under `"nutritionEntries"` (the Phase-1 choice already merged), but the
    delta feed emits entries under `"nutritionDays/entries"` and targets under
    `"nutritionTargets"` (which the macro-target PUT now fans out under). The
    fan-out name is only a wakeup hint (the client then pulls the full delta), so a
    mismatch is harmless today, but the Android FCM handler should treat
    `"nutritionEntries"` and `"nutritionDays/entries"` as the same wakeup. Confirm,
    or should the entry fan-out be renamed to `"nutritionDays/entries"` to match
    the delta exactly? (Left as-is to avoid changing already-merged Phase-1/2
    behaviour.)

35. 🟡 **Phase 7 E2E (`SyncContractIntegrationTest`) projects controller writes
    into the in-memory `SyncChangeReader`; the live Firestore capture is NOT
    exercised on the JVM.** The test drives the real controllers (idempotency,
    client id, fan-out via `RecordingFcmSender`, top-level `lastUpdate`) and the
    real `SyncController` read path (cursor paging, tombstones, canonical order, no
    skip/dup across pages), but because there is no Firestore on the JVM it mirrors
    each write into `InMemorySyncChangeReader` using the response's id + lastUpdate
    (the exact contract the Android engine consumes). **Still needs the Firestore
    emulator (out of scope here):** the live `FirestoreSyncChangeReader`, its
    `collectionGroup` subcollection enumeration + index-backed ordering, and the
    `idempotencyKeys` `expiresAt` TTL reaper (also #9). Confirm emulator coverage
    is deferred to a CI integration job.

### Phase 6 — FCM client, first-run gate, sync UX (this phase)

36. 🟡 **No `POST_NOTIFICATIONS` permission requested.** The FCM messages are
    silent **data-only** `{type:sync}` wakeups whose only effect is enqueuing a
    background WorkManager pull (`HfMessagingService`). We post no user-facing
    notification, so the runtime notification permission is deliberately NOT
    requested. If a future feature posts a visible notification, add it then.
    Confirm silent-only is the intended UX.

37. ✅ **done (client):** `SyncApi.delta` + `SyncEngine.pull` now carry a
    `recentSince` ISO-8601 query param; `FirstSyncGate.runInitialSync` sends
    `now − 14d` (`RECENT_WINDOW_DAYS`) for the blocking first window and
    `scheduleBackfill` runs the unbounded `recentSince = null` backfill on the same
    persisted cursor. The page-budget approximation (`INITIAL_PAGE_BUDGET`,
    `pull(maxPages)`) is removed; `FirstSyncGateTest` asserts the 14-day window.
    **Param name wired against: `recentSince`** — matches the server resolution
    below. ✅ **RESOLVED (server) — true date-bounded first window shipped.** The sync
    endpoint now accepts an optional `recentSince=<ISO date>` query param that
    bounds the heavy time-series collections (bloodReadings, bodyComposition,
    dailyMetrics, nutritionDailyLogs, nutritionDays/entries,
    weeklyWorkoutAggregates) to docs whose sample/effective date is on/after the
    date, while CRUD domains always return in full (`SyncRecentWindow` is a pure
    emission filter that never changes the `(updatedAt, collection, id)` cursor
    key). The Android client passes `recentSince = today − 14d` on its first sync
    and omits it for the later unbounded backfill; the backfill re-enumerates
    from an empty cursor and surfaces the older heavy docs the window skipped,
    with re-applied rows an idempotent LWW no-op (no skip/dup). Tombstones always
    propagate regardless of the window. Original page-budget note retained below
    for context.

    *(Original note:)* **First-run "14-day window" is approximated with a
    client-side PAGE BUDGET, not a true date window — server support would be
    needed for the exact D14 behaviour.** The delta API (`GET /api/me/sync`) is
    cursor-ordered by
    `lastUpdate` with an opaque cursor; the client cannot request "only the last
    14 days of heavy time-series" without a server hint. `FirstSyncGate` instead
    does a **bounded initial pull** (`SyncEngine.pull(maxPages = 3)`, ~the most
    recently-updated 1500 docs at the 500/page default) to release the UI fast,
    then a background unbounded pull + the ~6h periodic floor backfill the rest.
    This satisfies the *intent* (brief blocking first sync → lazy backfill) but is
    recency-ordered, not date-windowed. **A true `since=14d` first window needs a
    server-side parameter on the sync endpoint** — flagged. Confirm the page-budget
    approximation is acceptable, or schedule the server hint.

38. 🟡 **Consolidated sign-out hook lives on the single application-scoped
    `GoogleAuthRepository` (`SettingsAppModule`), resolving #12.** `SignOutSideEffects`
    does, in order: best-effort FCM token DELETE, then the encrypted-DB wipe
    (`DbWipe`). It is wired via the existing `onSignOut` callback, so every sign-out
    through the DI-provided repository (today only the Settings path — the only real
    sign-out caller; `AuthCoordinator.signOut()` is currently unused) both
    deregisters and wipes PHI. `MainActivity`'s manually-constructed repo is for
    *interactive sign-in only* (needs the Activity context) and never signs out, so
    it keeps the no-op default. `TokenRegistration` is injected as `dagger.Lazy` to
    break a Hilt dependency cycle (TokenRegistration → SyncApi → OkHttp →
    TokenAuthenticator → GoogleAuthRepository). The DB wipe always runs even if the
    token DELETE throws / the network is down. Confirm this single hook is the
    intended consolidation.

39. ✅ **done:** distinct global FAILED state shipped. Added
    `OutboxRepository.failedCount(): Flow<Int>` (DAO `observeFailedCount` = rows with
    `attempts > 0`, i.e. failed a replay and now in backoff). `SyncStatusViewModel`
    now combines pending + failed: it maps failed rows to the FAILED "changes failed
    — retry" bar state (the already-present `syncUiStateOf` priority puts FAILED
    above PENDING) and subtracts them from the pending count so the bar shows
    "failed" not "waiting". `retry()` re-drains the outbox. *(originally: coarse
    pending-only mapping, FAILED reserved for the per-row badge.)*

40. ✅ **done:** per-row badges wired. Added a defaulted `syncState: String?` to the
    `Medication`, `BloodReading`, and nutrition `Entry` domain models and a
    `MirrorRepositorySupport.observeWithState` overload that hands the mirror row's
    `syncState` to the decoder; repositories now thread it onto the model
    (`DefaultMedicationRepository.list`, `BloodReadingRepositoryImpl.observeReadings`,
    `NutritionRepository.entriesForDate`). `SyncBadge(row.syncState)` is rendered on
    the **medications grid card** (`MedicationCard`) and the **nutrition entry rows**
    (`NutritionTodayScreen.EntryRow`). Blood's `syncState` is surfaced on the model,
    but the marker-detail rows render a date-collapsed `LatestMarkers.derive`
    projection (shared with web-parity tests), so the badge on the blood list is a
    documented residual follow-up (model-level done; UI projection collapses
    per-reading identity). *(originally: component shipped, row wiring deferred.)*
    **needs a device:** badge visual rendering is unverified here (no emulator).

41. ✅ **done:** the last two AI entry points are now gated too. **Drug lookup**
    (`AddMedicationViewModel`/`AddMedicationScreen`) — the VM exposes
    `Connectivity.isOnline`, never fires the SSE+image lookup offline, and the search
    step shows an `OfflineNotice` when there's no local catalog match offline (the
    local catalog filter + manual entry stay usable). **DEXA PDF upload**
    (`UploadDexaViewModel`/`UploadDexaScreen`) — the idle picker is wrapped in
    `OfflineGate(online)` like the blood-report upload. Nothing is queued (D17). VM
    tests updated to supply `Connectivity`. *(originally: these two deferred as a
    mechanical follow-up.)* **needs a device:** the offline visual affordance is
    unverified here (no emulator).

42. 🟡 **Global `SyncStatusBar` is rendered once above the app NavHost
    (`AppNavHost`), not inside each feature screen.** It self-hides in the steady
    (online + all-synced) state, so it adds no chrome normally; it sits below the
    status-bar inset and above the nav graph. This gives every in-scope screen the
    indicator for free without per-screen wiring. Acceptable placement, or do you
    want it embedded per-screen (e.g. under each screen's header)?

### Phase 7 — hardening, E2E & convergence (instrumented tests authored)

43. 🟡 **The Phase 7 instrumented E2E + convergence tests are authored & COMPILE
    but cannot be EXECUTED here (no device/emulator).** `SyncE2ETest`,
    `ConvergenceTest`, and `TombstoneApplyTest` live in
    `core-data/src/androidTest/.../sync/` and use a **real** SQLCipher-encrypted
    `HfDatabase` + real `MirrorStore`/`OutboxRepository`/`SyncEngine` against a
    `MockWebServer`. They are proven-by-compile only
    (`:core-data:compileDebugAndroidTestKotlin` green); proving-by-execution needs
    `./gradlew :core-data:connectedDebugAndroidTest` on an API-29+ emulator/device
    in CI. This extends #1. OK to gate execution on the CI emulator step?

44. 🟡 **DoD #3 (FCM cross-device pull within ~30s; originating device does not
    self-pull) is NOT expressible as a self-contained instrumented test in
    `core-data`.** It requires the real Firebase push transport, a backend
    `SyncChangePublisher`/`FcmFanoutService` round-trip, and two physical devices
    (or a device + a server emitting a live data message) — the very path the
    plan's risk note says is "verified in deployed/integration runs," not unit/
    instrumented ones. The client half (FCM message → expedited `SyncWorker` →
    `SyncEngine.pull()`; origin-device suppression via `X-HF-Origin-Device`) is
    covered by Phase 6 wiring + the header assertions in `SyncE2ETest`, but the
    end-to-end ~30s delivery is left to a manual/staged two-device drill (DoD #3,
    Phase 6 verification 2). Confirm this stays a deployed-environment check.

45. 🟡 **DoD #7 (kill-switch reverts clients to live-network mode) is verified by
    the pure-JVM `SyncEnginePullTest.killSwitch…` + the per-read fallback in
    `MirrorRepositorySupport.observe` (#19), not by a new instrumented test.** The
    kill-switch latch is a `SyncFlags` DataStore boolean the engine sets on a
    `killSwitch=true` delta; the read path then serves a live fetch. A device-level
    "flip the flag, assert every screen renders from live network without crashing"
    drill (Phase 7 verification 3) is a functional/manual check rather than an
    instrumented unit, because it spans the whole repository + ViewModel + Compose
    read path. Confirm the JVM + manual coverage suffices, or do you want a
    dedicated instrumented kill-switch-fallback test in a feature module?

46. 🟡 **The convergence test models "apply the other client's change" via a
    `MockWebServer` delta per client, with each client holding an OWN in-memory
    `SyncStateDao` cursor** (`InMemorySyncStateDao`) so two logical clients can run
    in one process against two separate encrypted DB files
    (`buildNamedDb`/`deleteDbFiles`). It does NOT stand up a real shared backend
    between the two clients (that is `backend/.../SyncContractIntegrationTest`'s
    job). The convergence assertion (both land on the higher server `lastUpdate`;
    derived field survives; no ghost rows) is therefore proven against the
    *client-side* LWW apply, with the backend's serverTimestamp ordering assumed
    from the Phase 1 contract. Acceptable split of responsibility?

### Catch-up: features merged from `main` after the branch point (this follow-up)

Workout programs (IMPL-14/15) and async meal-photo capture landed on `main`
while this branch was in flight; both were folded into the offline contract when
`main` was merged in. Decisions taken:

47. 🟡 **Workout programs are now an offline collection set; workout-program chat
    is not.** Backend: `workoutPrograms` (top-level) + the materialized
    `scheduled` sessions subcollection register in `FirestoreSyncChangeReader`;
    the program `delete` is now a soft-delete tombstone (was a hard delete) with
    `syncStatus` stamped on save and archive-filtered reads; create/update/
    archive/activate fan out (activate also fans out `workoutPrograms/scheduled`).
    A `scheduled` `updatedAt` `COLLECTION_GROUP` index override was added. The
    program **chat** (threads/messages) stays online-only (web-driven SSE) and is
    **not** synced. Confirm chat-stays-online is intended.

48. 🟡 **Shared `collectionGroup` leaf guard.** Workout-program chat reuses the
    `messages` leaf already used by goal chat, so a `collectionGroup("messages")`
    delta scan would otherwise misroute workout-chat messages into
    `goalChatThreads/messages`. The reader now requires a doc's top-level
    collection to equal the routing name's first segment, excluding the
    (un-synced) workout-chat messages. Confirm this guard over renaming the leaf.

49. 🟡 **Android workout-program reads are Room-backed (read-only, no outbox).**
    New `workoutPrograms` + `workoutScheduled` mirror tables/DAOs/`MirrorStore`
    adapters/`CollectionRegistry` aliases; `HfDatabase` version 1→2 (destructive
    fallback per #13). `WorkoutProgramRepositoryImpl.list()` serves the mirror
    (fills on a cold miss); `get()` (deep) and `calendar()` are Room-first with a
    network fill. **Nuance (the #7 raw-doc-vs-DTO gap):** the delta pull writes the
    raw Firestore doc as `payloadJson`, which may not decode to the deep
    `WorkoutProgramDeepDto`/`ScheduledWorkoutDto`; when it doesn't, the deep read
    self-heals from the network while online. Offline, the deep view/calendar show
    whatever a prior fetch stored. Same staging as goals-deep (#26). Confirm.

50. 🟡 **Async meal-capture (`POST .../capture-meal`) stays online-only (D17) — the
    multipart photo is never queued — but now fans out `nutritionEntries` when the
    ANALYZING placeholder is created and again (origin=null) when the background
    analysis flips it to READY/FAILED, so other devices pull the filled-in entry.
    FOLLOW-UP:** the *capturing* device is origin-suppressed from its own fan-out,
    so the ANALYZING placeholder is not guaranteed to appear on it immediately on
    return to the day view (it lands on the next foreground/periodic pull). An
    optimistic mirror insert (or a post-capture pull trigger) on the capture
    screen is a small deferred refinement. Confirm acceptable for now.
