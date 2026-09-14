# D5 — Data Lifecycle & Integrity Audit (DATA)

Date: 2026-09-13. Auditor: D5. Repo: tesseta monorepo (worktree). REGULATED MODE: privacy scope.
Live-config probes were READ-ONLY REST GETs via ADC (`x-goog-user-project: health-fitness-160`); no mutations performed.

## BLUF

The production Firestore database (`projects/health-fitness-160/databases/production`) has **zero backup schedules, PITR disabled (1-hour version retention only), and delete-protection disabled** — verified live via the Firestore Admin API on 2026-09-13. RPO for the sole health-data store is effectively unbounded; the only "restore" tooling is a pair of ad-hoc export/import copy scripts. Compounding this, the one retention control that exists in code (idempotencyKeys TTL) is declared in Terraform against the **`(default)` database, not `production`**, and the live field config confirms no TTL is active on prod. Account deletion remains unbuilt (privacy doc admits it); this report enumerates the full deletion surface as an artifact — 20+ per-user subcollections, 4 top-level token/grant collections, 4+ GCS buckets (two of them **public-read**), and third-party processors. Integrity posture is otherwise better than expected: refresh-token rotation and nutrition day-rollup recompute are properly transactional; Android LWW uses server timestamps; the past sync-alias bug class is fixed and unit-tested — but unknown sync collections are still **silently** skipped, and write-path invariant validation (portion > 0, non-negative macros, exerciseId existence) is largely absent.

---

## Findings

### DATA-001 — No backups, no PITR, no delete protection on the production Firestore database
- **Severity:** critical | **Confidence:** [Certain]
- **Evidence:**
  - Live REST GET `…/databases/production/backupSchedules` (2026-09-13) returned `{}` — **no backup schedules exist**.
  - Live REST GET `…/databases/production` (2026-09-13): `"pointInTimeRecoveryEnablement": "POINT_IN_TIME_RECOVERY_DISABLED"`, `"versionRetentionPeriod": "3600s"`, `"deleteProtectionState": "DELETE_PROTECTION_DISABLED"`.
  - No `google_firestore_backup_schedule` anywhere in Terraform: `infra/terraform/` contains only `backend.tf, ci_iam.tf, firestore_ttl.tf, staging.tf, variables.tf, versions.tf`; grep for `backup|point_in_time|pitr` across `*.tf` returned nothing.
  - GCS buckets are created without versioning or lifecycle rules: `infra/scripts/bootstrap-gcp.sh:72-75` — `gcloud storage buckets create "$NUTRITION_BUCKET" --location="$REGION" --uniform-bucket-level-access` (no `--versioning`, no lifecycle); same for the exercise-media bucket at :88.
  - No restore runbook found in `docs/` (grep `restore|RTO|RPO|runbook` hits only unrelated plan docs). The nearest thing to a rehearsed restore is `infra/scripts/copy-default-to-production-firestore.sh:88-93` (`gcloud firestore export` → `gcloud firestore import` via bucket `health-fitness-160-firestore-exports`), used for the original prod cutover — an import has therefore been exercised at least once, but never from a scheduled backup, and never time-travel.
  - Currency (fetched 2026-09-13, https://docs.cloud.google.com/firestore/docs/backups): backups are **not enabled by default**; retention configurable "up to 14 weeks (`14w`)"; "A restore operation writes the data from a backup to a new Firestore database."
- **Impact:** A fat-fingered `gcloud firestore databases delete`, a buggy future deletion job, or a bad bulk write is unrecoverable after 1 hour (the non-PITR version window). All health data (nutrition, medications/adherence, blood readings, workouts, DEXA) lives here. RPO: unbounded. RTO: undefined. Android mirrors are partial caches, not restores (and get wiped on schema bump).
- **Effort:** 0.5–1 day (enable delete protection + PITR + daily backup schedule w/ e.g. 8-week retention via Terraform or gcloud; write a 1-page restore runbook and rehearse once into a scratch database).
- **Autonomy:** high for the Terraform/gcloud change; restore rehearsal needs operator to eyeball the restored DB.
- **Null option cost:** every day at ~$0 saved vs. total-loss exposure of the entire user health record; backup pricing is per-GiB on a small DB — negligible.
- **Prompt (self-contained):** "In tesseta repo `infra/terraform/`, add: (1) `delete_protection_state = DELETE_PROTECTION_ENABLED` and `point_in_time_recovery_enablement = POINT_IN_TIME_RECOVERY_ENABLED` for database `production` in project `health-fitness-160` (import the existing `google_firestore_database` resource rather than recreating), (2) a `google_firestore_backup_schedule` (daily, retention 8w) on database `production`. Terraform may not be applied in this project (see `infra/terraform/README.md` — bootstrap is shell-scripted); mirror the equivalent one-time `gcloud firestore …` commands in `infra/terraform/README.md` as `firestore_ttl.tf` already does. Then write `docs/runbooks/firestore-restore.md`: restore goes to a NEW database → repoint `app.persistence` database name or re-import; note backups exclude TTL policies and rules."

### DATA-002 — Retention controls misconfigured: idempotencyKeys TTL targets the wrong database; token/grant collections never purged
- **Severity:** high | **Confidence:** [Certain]
- **Evidence:**
  - `infra/terraform/firestore_ttl.tf:33` — `database = "(default)"` — but prod traffic is on database `production` (shared audit context; confirmed live: the `production` DB exists, created 2026-05-22).
  - Live REST GET `…/databases/production/collectionGroups/idempotencyKeys/fields/expiresAt` (2026-09-13) returns **only** `indexConfig` — no `ttlConfig` block → TTL is NOT active on prod.
  - The store expects the reaper: `backend/src/main/java/com/gte619n/healthfitness/persistence/sync/FirestoreIdempotencyStore.java:20-23` — "a `Firestore TTL policy` on that field reaps expired records automatically". Correctness is saved by the in-app re-check at :47-49 ("Expired but not yet reaped — treat as absent"), so this is unbounded storage growth, not a dup-write bug.
  - No TTL/cleanup for `refreshTokens`: `persistence/auth/FirestoreRefreshTokenStore.java:100-105` (`markRevoked`) and :108-119 (`revokeAllForUser`) only set `revoked=true`; nothing ever deletes expired/revoked docs. Same pattern for `platformRefreshTokens`, `oauthCodes`, `platformRateLimits` (constants at `persistence/platform/*`); the only Terraform TTL in the repo is the idempotencyKeys one.
  - `docs/requirements/privacy-and-compliance.md:72-73`: "**Retention:** … Not yet defined; today data is retained indefinitely until deletion."
- **Impact:** Idempotency docs accrue one per client mutation forever on prod (cost + collection-scan bloat); dead token docs accrue per login/rotation. Also a compliance gap: the documented retention control that supposedly exists is a no-op in prod.
- **Effort:** 0.5 day. **Autonomy:** high. **Null option cost:** slow unbounded growth; single-user scale makes this cheap today, but it silently falsifies the infra-as-code record.
- **Prompt:** "Fix `infra/terraform/firestore_ttl.tf` to target database `production` (or parameterize database and declare both), and run/document the equivalent `gcloud firestore fields ttls update expiresAt --collection-group=idempotencyKeys --database=production` per the README convention. Add TTL or a scheduled purge for `refreshTokens`/`platformRefreshTokens`/`oauthCodes` expired docs (e.g. TTL on `expiresAt`), and write the retention schedule table privacy-and-compliance.md §5 says is missing."

### DATA-003 — Account deletion unbuilt; full deletion surface enumerated (artifact below)
- **Severity:** high (privacy-regulated context) | **Confidence:** [Certain] on absence; checklist [Certain] per-item from code constants
- **Evidence:** `docs/requirements/privacy-and-compliance.md:67-70` — "Server-side full-account deletion is not yet built"; :71 "**Export:** … Not yet built." grep for `deleteAccount|account deletion` across `api/` and `core/` returned nothing. Collection inventory verified from `persistence/**` constants (grep `SUBCOLLECTION = |COLLECTION = `) and `persistence/sync/FirestoreSyncChangeReader.java:94-150` (emitted collections list).
- **Impact:** A deletion request today requires manual, error-prone Firestore console surgery across 20+ subcollections, 4 buckets, and token stores — with public-URL meal photos left orphaned. Known context deepened as required; the artifact is the checklist.
- **Effort:** 3–5 days (recursive delete job + GCS sweep + token revocation + verification query). **Autonomy:** medium (needs operator decision on catalog-food anonymize-vs-delete and processor policy).
- **Null option cost:** acceptable only while the sole user is the operator; blocks any external launch (the doc's own §"before external launch" list).
- **Prompt:** "Implement `DELETE /api/me` in the tesseta backend as an idempotent job that executes the checklist in docs/audit/2026-09-13/domains/05-data-lifecycle.md §Deletion checklist, using Firestore recursive delete (BulkWriter) per subcollection, then verifies zero remaining docs via collection-group queries filtered on the uid, then deletes GCS objects by prefix, then revokes tokens. Firestore doc deletion does NOT delete subcollections — deleting `users/{sub}` alone strands everything beneath it."

#### ARTIFACT — Full account-deletion checklist (what a deletion job must touch)
All paths verified against `backend/src/main/java/com/gte619n/healthfitness/persistence/**` constants unless marked.

**A. Firestore, under `users/{sub}` (delete subcollections BEFORE the parent doc — Firestore does not cascade):**
1. `users/{sub}` document itself — contains KMS-envelope-encrypted Google Health / Withings OAuth tokens (stripped from sync at `FirestoreSyncChangeReader.java:83`); deleting the doc destroys ciphertext (KMS key wraps remain, acceptable).
2. `bloodReadings`
3. `bloodTestReports` + `bloodTestDedup`
4. `bodyComposition`
5. `dailyMetrics`
6. `deviceSyncs`
7. `dexaScans`
8. `fcmTokens` (per-device, `persistence/push/FirestoreFcmTokenRepository.java:21`)
9. `goals` → `goals/{g}/phases` → `phases/{p}/steps` (3 levels)
10. `goalChatThreads` → `…/messages`
11. `idempotencyKeys`
12. `locations`
13. `medications` → `medications/{m}/adherence` + `medications/{m}/history`
14. `nutritionDailyLogs`
15. `nutritionDays/{date}/entries` (day docs + entries subcollection)
16. `nutritionTargets`
17. `protocols`
18. `weeklyWorkoutAggregates`
19. `workouts`
20. `workoutPrograms` → `…/scheduled`
21. `progressionState`, `progressionBlock`, `progressionWeek`, `progressionObservations`, `progressionPredictions`, `exerciseLoadingProfiles` (progression repos)
22. savedMeals / catalog cache subcollections if user-scoped (`FirestoreSavedMealRepository` — verify scope at implementation time)

**B. Firestore, top-level collections queried by userId:**
23. `refreshTokens` where `userId == sub` (`FirestoreRefreshTokenStore.java:109-111` shows the query pattern) — delete, not just revoke.
24. `platformRefreshTokens`, `oauthGrants`, `oauthCodes`, `platformRateLimits` (third-party platform, ADR-0020) — by user.
25. `webhookSubscriptions` / `webhookCheckpoints` entries tied to the user (Google Health / Withings) + remote unsubscribe at the provider.
26. Global catalogs: `foodCatalog` / `mealCatalog` docs with `createdBy == sub` (`FirestoreFoodCatalogRepository.java:112` `whereEqualTo("createdBy", userId)`) — decide anonymize (null the createdBy) vs delete (may orphan other users' entries in a multi-user future; entries are macro-self-contained so delete is read-safe, see DATA-004 notes).

**C. GCS objects:**
27. `gs://health-fitness-160-nutrition-photos/**` for the user — **PUBLIC bucket** (`bootstrap-gcp.sh:77-79` grants `allUsers:objectViewer`); meal photos are user health data behind unauthenticated URLs → deletion must actually remove objects, CDN-cached copies expire on their own.
28. Private DEXA-PDF and blood-test-PDF buckets ("created by their own IMPL flows", `bootstrap-gcp.sh:66-68`).
29. `health-fitness-160-firestore-exports` — old export snapshots contain the user's data too.

**D. Third parties / device:**
30. FCM: delete Firebase Installations for registered tokens (delete of `fcmTokens` docs alone leaves live FCM registrations).
31. Google Health / Withings: revoke the stored OAuth grants server-side at the provider.
32. Gemini-submitted photos/prompts: covered by Google API data-use terms, not deletable per-user — document as processor disclosure, not a deletion step.
33. On-device Room mirror: wiped on sign-out per ADR-0007 (`privacy-and-compliance.md:68-70` claims implemented; DbWiper.clearAllTables verified in code).
34. Backups (once DATA-001 lands): deleted user persists in backups until retention lapses — state this in the privacy doc.

### DATA-004 — Write-path invariants largely unenforced (Firestore has none; controllers add little)
- **Severity:** medium | **Confidence:** [Certain]
- **Evidence (three invariants traced end-to-end):**
  1. **Portion/quantity > 0:** NOT enforced. `core/nutrition/NutritionService.java:212-217` validates only `meal` and `foodName` ("meal is required" / "foodName is required"); `servingGrams`, `quantity` accepted null/zero/negative. Negative macros also accepted (`Macros.withDerivedCalories()` takes any Double). No `@Valid` in `api/nutrition/NutritionController.java`.
  2. **Adherence references a real medication:** ENFORCED — `api/medication/AdherenceController.java:87-89` `medications.findById(userId, medicationId).orElseThrow(… "Medication not found")`. But active-status is not checked (:93 uses `med.dose()` regardless), and dose value is unvalidated.
  3. **Workout set references a real exercise:** NOT enforced — `core/workoutprogram/WorkoutSessionCompletionService.java:306` accepts any `exerciseId`; :259-273 validates set numerics (weight/reps/RPE ranges) but never catalog existence.
  - Bean validation exists but idles: `build.gradle.kts:57` pulls `spring-boot-starter-validation`; ~13 `@Valid` uses total, all in location/equipment/bulk-import; zero `@Validated` controllers; none in nutrition/medication/workout.
  - Entry↔day consistency IS structural (entries keyed `users/{u}/nutritionDays/{date}/entries`, PATCH scoped by `(userId,date,entryId)`), so that invariant holds by construction.
  - **Orphan read-paths verified null-safe (both picks):** backend — entries carry denormalized macros; a dangling `catalogFoodId` just yields no image (`NutritionController.java:973-975` `foodCatalog.find(foodId).ifPresent(…)`); adherence is self-contained (`AdherenceRepositoryImpl.java:46-52`). Android — `NutritionRepository.kt` treats `foodId` as nullable with name fallback; `AdherenceRepository.kt:152-200` drops unparseable rows via `mapNotNull`.
- **Impact:** Garbage-in persists silently (0-gram servings, negative macros, phantom exerciseIds) and syncs to all devices; the composite-portion incident class (memory, [Likely]) shows scaling math trusts these fields. Reads won't crash (verified), but analytics/rollups are poisoned.
- **Effort:** 1–2 days. **Autonomy:** high. **Null option cost:** occasional data-quality incidents in a single-user app; rises sharply with any second client or LLM-generated writes (Adjust-with-AI writes macros).
- **Prompt:** "Add validation to the tesseta backend nutrition/medication/workout write paths: `servingGrams`/`quantity` must be > 0 when present, macro grams/calories >= 0 (exempting the DRINKS alcohol-calorie freeze at NutritionService:224-227), adherence dose > 0, and exerciseId existence check in WorkoutSessionCompletionService (cache the exercises catalog). Prefer explicit service-layer checks matching the existing IllegalArgumentException style over annotation sprinkle; add unit tests per rule."

### DATA-005 — No Firestore security rules deployed (and none in repo); defense-in-depth absent
- **Severity:** medium | **Confidence:** [Certain] that no rules exist; [Likely] that effective client access is deny
- **Evidence:** `infra/firestore/` contains only `firestore.indexes.json` — no `.rules` file anywhere in the repo. Live REST GET `https://firebaserules.googleapis.com/v1/projects/health-fitness-160/releases` (2026-09-13) returned `{}` — zero rules releases in the project. Only the backend service account connects (shared context; clients use REST via backend). Fetched https://firebase.google.com/docs/firestore/security/get-started (2026-09-13): the page does not explicitly state the default for gcloud-created databases, hence [Likely] not [Certain] on effective deny for mobile/web SDK requests.
- **Impact:** If any client SDK path is ever enabled (the Android app already carries Firebase for FCM/Installations), data exposure hinges on an undocumented default instead of an explicit deny. One 6-line deny-all release converts this to [Certain].
- **Effort:** 0.25 day. **Autonomy:** high. **Null option cost:** near-zero today; nonzero and invisible the day someone adds a Firebase SDK feature.
- **Prompt:** "Add `infra/firestore/firestore.rules` with `match /{document=**} { allow read, write: if false; }` (rules_version = '2'), deploy it as a release for BOTH databases (`(default)` and `production` — rules releases are per-database: `projects/health-fitness-160/databases/production/documents`), and wire deployment into `infra/scripts/deploy-firestore-indexes.sh` or a sibling script."

### DATA-006 — Multi-document mutation atomicity: mostly good; two sequential seams remain
- **Severity:** low-medium | **Confidence:** [Certain]
- **Evidence:**
  - **Good (transactional):** refresh-token rotation `FirestoreRefreshTokenStore.java:69-89` — check-and-set in `runTransaction`, "of N racing refreshes exactly one observes the live token"; successor stamped in the same commit. Nutrition day rollup `FirestoreNutritionDailyLogRepository.java:72-96` — reads entries + writes rollup in ONE transaction, self-describing as retry-safe. Adherence upserts (`AdherenceRepositoryImpl.java:107,124`), platform code/token stores, blood-test report writes — all `runTransaction`. Multi-doc writes elsewhere use atomic `WriteBatch` (11 sites).
  - **Seam 1:** entry write then rollup are two separate commits — `NutritionService.java:236-237` `entries.save(entry); recomputeDay(userId, date);` (same pattern at :276, :341, :484, :513, :625, :681). Backend crash between the two leaves a stale day rollup until the next entry mutation heals it. Bounded staleness, self-healing; dashboards read the rollup.
  - **Seam 2:** idempotency is check-then-act, not atomic — `FirestoreIdempotencyStore.java:41-52` (`findResult`) and :55-60 (`record`) are separate ops with no transaction; two truly concurrent identical retries can both miss and both execute. Client replay is serial (Android outbox replays one chain at a time, `OutboxRepository.kt`), so exposure is Cloud-Tasks double-delivery timing only.
- **Impact:** rare stale rollup; rare duplicate side-effect under concurrent idempotent retries. **Effort:** 0.5–1 day (make `record` a `txn.create()` that fails on exists, or accept). **Autonomy:** high. **Null option cost:** low — both seams are self-limiting; do-nothing is defensible (R6): document them instead.

### DATA-007 — Android: schema-version bump / destructive fallback silently discards pending local writes
- **Severity:** medium | **Confidence:** [Certain]
- **Evidence:** `android/core-data/src/main/java/com/gte619n/healthfitness/data/db/HfDatabase.kt:276` `.fallbackToDestructiveMigration()` (explicit migrations 3→7 are all additive; fallback is the wedge-safety net, comment :273-275). Sync-protocol bumps wipe deliberately: `SyncEngine.kt:110-114` — on `resp.schemaVersion != SYNC_SCHEMA_VERSION` → `dbWiper.wipeMirrors()` + cursor null; `DbWiper.kt:31-33` `database.clearAllTables()` — which also truncates the **outbox**, so journaled-but-unreplayed user writes (a meal logged offline, a dose marked taken) are destroyed, not replayed. Full re-pull restores server truth only.
- **Impact:** On every future sync schemaVersion bump (or Room downgrade/corruption), any offline-authored health data not yet replayed is lost with no user-visible signal. Probability is low per event but the payload is exactly the data the offline-first design promises to keep.
- **Effort:** 1 day (drain/flush outbox before wipe when network available; else snapshot outbox rows and re-enqueue post-wipe; log/toast when rows are dropped). **Autonomy:** high. **Null option cost:** acceptable only if schemaVersion bumps are always shipped alongside forced-foreground sync; nothing enforces that today.

### DATA-008 — Unknown sync collections are still skipped silently (the exact past bug class), no contract test against backend-emitted names
- **Severity:** medium | **Confidence:** [Certain]
- **Evidence:** `SyncEngine.kt:159` — `val table = CollectionRegistry.tableFor(change.collection) ?: return ApplyOutcome.SKIPPED` — no warning log, no metric. The historical fix is in and tested (`CollectionRegistry.kt:75-77` registers `"entries"`, `"nutritionDays.entries"`, `"nutritionDays/entries"`; `CollectionRegistryTest.kt:28-47`), but the test only pins the aliases someone remembered to add. The backend's emitted set lives independently at `FirestoreSyncChangeReader.java:94-150` (15 top-level + 7 slash-form subcollections); nothing asserts registry ⊇ emitted-set, so the next backend subcollection (e.g. a future `drinkSessions`) reproduces "logged on web, never reaches phone" with zero signal. Past incident context [Likely→verified: alias table and test exist on main].
- **Impact:** Recurrence of a proven silent-data-divergence bug class on every schema addition. **Effort:** 0.5 day. **Autonomy:** high. **Null option cost:** you pay it the next time a subcollection ships — historically that took weeks to notice.
- **Prompt:** "In tesseta: (1) add a WARN log + counter when SyncEngine.kt:159 skips an unknown collection; (2) create a shared contract fixture — either extract the emitted-collection list in FirestoreSyncChangeReader into a constants file mirrored to a JSON asset consumed by a CollectionRegistryTest case, or a checked-in list both a backend test and the Android test assert against — so adding a backend collection without a registry alias fails CI."

### DATA-009 — No immutable audit trail for health-data edits (only medications keep history)
- **Severity:** low | **Confidence:** [Certain]
- **Evidence:** Only medication changes are historied: `persistence/medication/MedicationHistoryRepositoryImpl.java` (subcollection `medications/{m}/history`, also emitted in sync at `FirestoreSyncChangeReader.java:138-150`). Nutrition entries, workouts, blood readings, body composition all mutate in place with `updatedAt` server timestamps and LWW (`FirestoreNutritionDailyLogRepository.java:112`); soft-delete tombstones (`syncStatus ARCHIVED`) preserve existence but not prior values. No generic change-log/event collection exists in `persistence/`.
- **Impact:** Under the current single-operator privacy posture this is acceptable — data-subject rights (GDPR-style) do not require edit history, and LWW plus tombstones give minimal forensics. It matters only if clinical-grade provenance or dispute resolution is ever claimed. Recommendation: explicitly record "no audit trail, by design" in privacy-and-compliance.md rather than build one (do-nothing is the right option here, R6).
- **Effort:** 0.1 day (doc note). **Autonomy:** high. **Null option cost:** none at present posture.

### DATA-010 — Schemaless shape-change strategy is wipe-and-resync + ad-hoc tolerant readers; no versioned readers or data migration scripts
- **Severity:** low | **Confidence:** [Certain]
- **Evidence:** The sanctioned mechanism is the sync protocol version: `api/sync/SyncController.java:33-34` ("When the requested schemaVersion differs … the client can wipe") pairing with the Android wipe (DATA-007). Server-side readers tolerate missing fields ad hoc — e.g. `FirestoreNutritionDailyLogRepository.java:119-129` uses nullable `snapshot.getDouble(…)`, but `LocalDate.parse(snapshot.getString("date"))` at :122 NPEs/throws on a missing `date`. No migration scripts exist for reshaping stored docs (`scripts/` has only `check-docs.mjs`; `infra/scripts/` are bootstrap/deploy plus the two copy scripts); historical reshapes were one-off jobs (`deploy-split-workout-blocks-job.sh` is the pattern). Firestore-side field TTL/indexes are the only declarative schema artifacts (`infra/firestore/firestore.indexes.json`, `firestore_ttl.tf`).
- **Impact:** Workable for additive change; any field rename/retype requires a bespoke job with no template, and one hard-parsed field (`date`) can poison a whole range read. **Effort:** 0.5 day to null-guard hard parses + write a "reshape job" template doc. **Autonomy:** high. **Null option cost:** low; pay-per-reshape.

---

## Verified-good (no finding)
- **LWW clock source is server-side:** Android compares server `lastUpdate` epoch millis, never client wall clock (`SyncEngine.kt:160`, `ConflictResolver.kt:54-61`: server-newer wins; dirty local discarded with an "updated elsewhere" signal). [Certain]
- **Tombstone hygiene:** `MirrorRow.status ACTIVE|ARCHIVED` (`MirrorRow.kt:29-40`); all 23 `observeActive()` DAOs filter `status != 'ARCHIVED'`; the one bypass (`MedicationAdherenceDao.observeAll()`, MirrorDaos.kt:172) is deliberate for the checklist overlay. The memory-flagged ARCHIVED-vs-domain-status trap is a diagnosis hazard, not a live bug: domain status enums are separate from sync status and not used for delete filtering. [Certain]
- **Outbox terminal-4xx self-heal:** doomed chains dropped and mirror converged to server truth (`OutboxRepository.kt:193-237`), `Idempotency-Key` header per mutation with the deterministic `(med,date)` adherence key (`RestOutboxReplayClient.kt:116`, `OutboxEndpointRegistry.kt:113-117`). [Certain]

## HYPOTHESES (unverified — confirmation method attached)
- **H1 [Guessing]:** The `(default)` database still holds a stale pre-cutover copy of user health data (the copy scripts imply it was the original home). Confirm: REST GET `…/databases/(default)` then a collection list; if populated, it is an unmanaged second copy that deletion (DATA-003) and backups (DATA-001) both ignore.
- **H2 [Guessing]:** Old Firestore export snapshots persist in `gs://health-fitness-160-firestore-exports` with no lifecycle rule, containing full health-data copies. Confirm: `gcloud storage ls -r gs://health-fitness-160-firestore-exports` (read-only).
- **H3 [Likely]:** WORKOUT_SCHEDULED parked outbox rows (`PARKED_NEXT_ATTEMPT = Long.MAX_VALUE`, `OutboxRepository.kt:283`) can accumulate forever if the bespoke restore flow is never triggered. Confirm: trace `rearmFailed()` call sites in the Android UI layer.
- **H4 [Guessing]:** The public nutrition-photos bucket serves images under guessable object names, making "deleted" meals fetchable post-deletion. Confirm: read the upload path in the backend nutrition image service for object-name construction (UUID vs derived).

```json
[
  {"id":"DATA-001","title":"No backups, PITR disabled, delete-protection disabled on prod Firestore","severity":"critical","confidence":"certain","evidence":["REST GET /databases/production/backupSchedules => {} (2026-09-13)","REST GET /databases/production: POINT_IN_TIME_RECOVERY_DISABLED, versionRetentionPeriod 3600s, DELETE_PROTECTION_DISABLED","infra/terraform/*.tf: no google_firestore_backup_schedule","infra/scripts/bootstrap-gcp.sh:72-88 buckets created without versioning/lifecycle"],"impact":"Unbounded RPO for sole health-data store; unrecoverable after 1h","effort_days":1,"autonomy":"high","null_option_cost":"total-loss exposure vs ~$0 savings"},
  {"id":"DATA-002","title":"idempotencyKeys TTL declared on (default) not production; token collections never purged; retention undefined","severity":"high","confidence":"certain","evidence":["infra/terraform/firestore_ttl.tf:33 database=\"(default)\"","REST GET production/.../fields/expiresAt has no ttlConfig","FirestoreRefreshTokenStore.java:100-119 revoke-only, no delete","privacy-and-compliance.md:72-73"],"impact":"Unbounded growth; infra-as-code falsified; compliance gap","effort_days":0.5,"autonomy":"high","null_option_cost":"slow cost growth + stale doc"},
  {"id":"DATA-003","title":"Account deletion unbuilt; full deletion surface enumerated as artifact","severity":"high","confidence":"certain","evidence":["privacy-and-compliance.md:67-71","no deleteAccount in api/ or core/","collection inventory from persistence constants + FirestoreSyncChangeReader.java:94-150","bootstrap-gcp.sh:77-79 public nutrition-photos bucket"],"impact":"Deletion requires manual surgery across 20+ subcollections, 4 buckets, token stores","effort_days":4,"autonomy":"medium","null_option_cost":"acceptable solo; blocks external launch"},
  {"id":"DATA-004","title":"Write-path invariants unenforced (portion>0, macros>=0, exerciseId existence)","severity":"medium","confidence":"certain","evidence":["NutritionService.java:212-217 only meal/foodName checked","WorkoutSessionCompletionService.java:306 no exercise existence check","AdherenceController.java:87-93 existence yes, active/dose no","zero @Validated controllers; 13 @Valid all outside nutrition/med/workout"],"impact":"Garbage persists and syncs; rollups/analytics poisoned; reads verified null-safe","effort_days":1.5,"autonomy":"high","null_option_cost":"data-quality incidents, worse with AI-generated writes"},
  {"id":"DATA-005","title":"No Firestore security rules deployed or in repo","severity":"medium","confidence":"certain-absence/likely-deny","evidence":["infra/firestore/ has only firestore.indexes.json","REST GET firebaserules releases => {} (2026-09-13)","firebase.google.com/docs/firestore/security/get-started fetched 2026-09-13: default not explicitly documented"],"impact":"Client-SDK exposure hinges on undocumented default","effort_days":0.25,"autonomy":"high","null_option_cost":"near-zero today, invisible risk on first Firebase SDK feature"},
  {"id":"DATA-006","title":"Two non-transactional seams: entry-save→rollup sequential; idempotency check-then-act","severity":"low-medium","confidence":"certain","evidence":["NutritionService.java:236-237 save then recomputeDay (separate commits)","FirestoreIdempotencyStore.java:41-60 findResult/record not atomic","token rotation and rollup recompute ARE transactional (FirestoreRefreshTokenStore.java:77, FirestoreNutritionDailyLogRepository.java:83)"],"impact":"Rare stale rollup (self-healing); rare dup side-effect on concurrent retries","effort_days":0.75,"autonomy":"high","null_option_cost":"low; do-nothing defensible"},
  {"id":"DATA-007","title":"Android schema wipe / destructive fallback discards pending outbox writes","severity":"medium","confidence":"certain","evidence":["HfDatabase.kt:276 fallbackToDestructiveMigration()","SyncEngine.kt:110-114 wipe on schemaVersion mismatch","DbWiper.kt:31-33 clearAllTables() truncates outbox too"],"impact":"Offline-authored health data lost silently on protocol bumps","effort_days":1,"autonomy":"high","null_option_cost":"data loss on every future schemaVersion bump"},
  {"id":"DATA-008","title":"Unknown sync collections still silently SKIPPED; no backend/Android contract test","severity":"medium","confidence":"certain","evidence":["SyncEngine.kt:159 tableFor(...) ?: return ApplyOutcome.SKIPPED (no log/metric)","CollectionRegistryTest.kt pins current aliases only","FirestoreSyncChangeReader.java:94-150 emitted set uncoupled from registry"],"impact":"Proven silent-divergence bug class recurs on next schema addition","effort_days":0.5,"autonomy":"high","null_option_cost":"repeat of weeks-to-notice sync loss"},
  {"id":"DATA-009","title":"No audit trail for health-data edits except medications/history","severity":"low","confidence":"certain","evidence":["MedicationHistoryRepositoryImpl.java exists","nutrition/workout/blood repos mutate in place with updatedAt+LWW"],"impact":"Acceptable at current privacy posture; document as by-design","effort_days":0.1,"autonomy":"high","null_option_cost":"none at present posture"},
  {"id":"DATA-010","title":"Shape changes = wipe-and-resync + ad-hoc tolerant readers; no reshape-job template; date field hard-parsed","severity":"low","confidence":"certain","evidence":["SyncController.java:33-34 protocol-version wipe","FirestoreNutritionDailyLogRepository.java:122 LocalDate.parse on getString(\"date\")","no migration scripts beyond one-off deploy-split-workout-blocks-job.sh"],"impact":"Renames/retypes need bespoke jobs; one bad doc can poison a range read","effort_days":0.5,"autonomy":"high","null_option_cost":"pay-per-reshape"}
]
```
