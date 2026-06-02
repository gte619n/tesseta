# IMPL-AND-20 — Outstanding Questions for Review

These surfaced during implementation and were resolved with a best-judgement
default (noted) so development could continue. Please confirm or redirect each.
Numbered for easy reference.

> Status legend: 🟡 awaiting your decision · ✅ confirmed · 🔁 changed per your note

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

8. 🔁 **Idempotency + client-minted id fast-follow LANDED.** Resolves the
   open follow-up. `SyncWriteContext` + `SyncChangeNotifier` are now wired on all
   remaining in-scope JSON write paths: goal **phases** POST, goal **steps** POST,
   **nutrition composite-meal** POST, **macro-target** PUT, **medication
   adherence** log POST, and **medication dosage / discontinue / reactivate**.
   The medication PUT/transition writes and goal/phase/step updates/deletes/
   reorders also now fan out. **Deliberately skipped (with reasons):**
   `bloodTestReports` and `dexaScans` (multipart + SSE Gemini upload — create is
   online-only per D17; the CRUD records stay read-relevant), `goalChatThreads`/
   `messages` (the chat POST is an SSE Gemini stream, and `commit` reuses the
   goal/phase/step write paths that already fan out — there is no plain JSON
   thread/message create endpoint to convert), `protocols` (no controller exists
   in the codebase), and `bodyComposition` / `dailyMetrics` (GET-only controllers,
   Google-Health-sourced, pull-only — no manual create/write path). See new
   items #31–#35 for the judgement calls this surfaced.

9. 🟡 **`FirestoreIdempotencyStore` needs a Firestore TTL policy** on
   `users/{uid}/idempotencyKeys.expiresAt` declared in console/Terraform (the
   in-app TTL check covers the unreaped window). Needs an infra change before
   prod.

10. 🟡 **Delta subcollection queries use `collectionGroup` scoped to the user by
    document-path prefix in app code** (reads across users before filtering).
    Correct at current volumes; revisit if it becomes a cost/latency issue.

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

24. 🟡 **`medications` adherence (`logDose`/`undoDose`) left on the network.**
    Adherence logging is a user intent, but the API returns no entity/id to mirror
    (it's `POST .../adherence` returning `Unit`, and the `today` checklist is a
    server-derived projection of it). Mirroring it as an outbox write would need a
    synthetic adherence-row id + a derived-checklist recompute that the server owns;
    out of proportion for this phase. The dose checklist still renders from the live
    `today` endpoint. Flagged: offline adherence logging is a fast-follow.

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

26. 🟡 **`goals` — list Room-backed; deep aggregate + step intents on network.**
    `goals()` serves the `goals` mirror (full `Goal` payload). `goalDeep(id)`
    (nested phases + steps) and the step intents (`setStepDone`/`resetStepToAuto`/
    `reevaluate`) stay on the network by design (D9): the step `done`/`doneAt` are
    **server-derived**, and a manual toggle is already an explicit `PATCH
    .../steps/{sid}` **intent** that the server re-evaluates — so it is deliberately
    NOT routed through the outbox (it must not replay as a raw derived-doc write).
    After any intent the deep goal is re-fetched and the goal's list mirror row
    refreshed. Reassembling `GoalDeep` from the flat `goalPhases`/`goalSteps` mirror
    tables (keeping the cascade correct offline) is left as a follow-up.

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

30. 🟡 **`bodyComposition.pointsInRange` and the dashboard weight/blood summaries
    stay network-served.** These are windowed/aggregated derived reads the flat
    `observeActive()` mirror does not index; they tolerate a live fetch (consistent
    with the prior agent's #19 note). Reads that matter for offline rendering (the
    snapshot, the records lists) are Room-backed; the windowed analytics are a
    documented follow-up to back with a date-ranged Room query.

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

37. 🟡 **First-run "14-day window" is approximated with a client-side PAGE BUDGET,
    not a true date window — server support would be needed for the exact D14
    behaviour.** The delta API (`GET /api/me/sync`) is cursor-ordered by
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

39. 🟡 **Global `SyncStatusBar` maps the outbox's single `pendingCount()` to a
    coarse PENDING/OFFLINE/IDLE state; it does not distinguish FAILED at the global
    level.** `OutboxRepository` exposes one `pendingCount(): Flow<Int>` (PENDING and
    FAILED rows are both "not yet synced"); the bar therefore shows OFFLINE >
    PENDING > IDLE and reserves the FAILED indicator for the **per-row `SyncBadge`**
    (which reads each row's precise `syncState`). The `syncUiStateOf` mapping does
    accept a `failedCount`/`syncing` input (unit-tested) so a future
    `observeFailedCount()` / active-drain signal can light the global FAILED/SYNCING
    state without a UI change. Acceptable, or should the outbox expose a separate
    failed-count flow now?

40. 🟡 **Per-row PENDING/FAILED badges: reusable component shipped + unit-tested,
    but NOT yet wired into the blood/medications/nutrition list ROWS — surfacing a
    row's `syncState` through each domain model is a deferred follow-up.** `SyncBadge`
    + the pure `badgeSpecOf` mapping live in `core-ui` and are tested. Wiring them
    onto actual rows needs the per-row `syncState` threaded from the Room mirror
    through the **domain model → ViewModel → row composable** for each domain (the
    domain `BloodReading`/`BloodTestReport`/`Medication`/nutrition-entry models do
    not currently carry `syncState`) — the same multi-layer change Phase 5
    deliberately staged (#16/#17). Given badges also need a device to verify
    visually, the component ships now and the per-domain `syncState` surfacing +
    row wiring is a fast-follow. **Pull-to-refresh IS wired** (reference: Blood
    overview, via `PullToRefreshBox` → `repository.refresh()` → mirror fill).
    Confirm staging the row-level badge wiring as a follow-up.

41. 🟡 **Offline-AI affordance (D17) wired on PDF upload, goals chat, and
    meal-photo capture; drug lookup is the noted follow-up.** A reactive
    `Connectivity.isOnline` signal (a `ConnectivityManager` network-callback
    `StateFlow` in `core-data`) gates: **blood-report PDF upload** (picker only
    opens online; offline shows `OfflineNotice`), **goals chat** (composer sends
    dropped offline + banner), and **meal-photo/label capture** (whole capture pane
    replaced with the affordance offline). Nothing is queued. **NOT yet gated: the
    medication drug-lookup entry point** (`AddMedicationViewModel`) and the **DEXA
    PDF upload** — same `OfflineGate`/`Connectivity` pattern, deferred as a
    mechanical follow-up. Confirm the three reference gates suffice for this phase.

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
