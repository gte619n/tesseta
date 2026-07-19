# Data model (Firestore)

The backend owns the only connection to Cloud Firestore (native mode) and is
the source of truth for all persisted state. Clients never read Firestore
directly — they call the backend REST API ([api-surface.md](api-surface.md)).

**Database id.** `FirestoreConfig` builds `FirestoreOptions` with
`projectId = ${app.gcp.project-id}` and
`databaseId = ${app.gcp.firestore-database-id:(default)}` (env
`FIRESTORE_DATABASE_ID`). Local dev hits `(default)`; deployed environments
route to a named database (e.g. `production`).

**Shape conventions.** Domain types are Java **records** in the backend `core`
package. Repository *interfaces* live in `core`; Firestore *implementations*
live in `persistence` and are gated
`@ConditionalOnProperty("app.persistence.firestore-enabled", matchIfMissing=true)`
so tests swap in-memory fakes. Mapping is hand-written
(`FirestoreMapper`, `serverTimestamp()`, `toInstant()`); there is no JPA/ORM.

## Collection layout

These top-level collections are **shared/global**: `users`, `drugs`,
`foodCatalog`, `mealCatalog`, `equipment`, `exercises`, and `refreshTokens`.
Everything else is a **subcollection of `users/{userId}`** (per-user,
multi-tenant) — including `workoutPrograms`, despite the name.

| Path | Record (`core.…`) | Key fields |
|---|---|---|
| `users/{userId}` | `user.User` | userId, email, displayName, heightCm, `googleHealth` (embedded), createdAt/updatedAt |
| ↳ embedded `.googleHealth` | `user.GoogleHealthConnection` | healthUserId, `refreshTokenCiphertext`:Blob, `dekCiphertext`:Blob, connectedAt — queried via `whereEqualTo("googleHealth.healthUserId", …)` |
| `refreshTokens/{tokenId}` **(shared, ADR-0010 / ADR-0019)** | `auth.RefreshTokenStore.StoredRefreshToken` | tokenId, userId, `tokenHash` (SHA-256 of the secret — the secret itself is never stored), createdAt, expiresAt, revoked, `rotatedAt`, `replacedBy` (successor-chain pointer, ADR-0019). Native-client session refresh tokens; single-use rotation walks the `replacedBy` chain to the live tip, `whereEqualTo("userId", …)` for theft-response family revocation |
| `users/{u}/bodyComposition/{metric__recordId}` | `bodycomposition.BodyCompositionMeasurement` | metric:`{WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI}`, value, sampleTime, sourcePlatform, recordingMethod. Doc id = `metric + "__" + recordId`. Composite index `metric ASC, sampleTime DESC` |
| `users/{u}/bloodReadings/{readingId}` | `blood.BloodReading` | marker:`BloodMarker`, value, unit, sampleDate, labSource, notes |
| `users/{u}/bloodTestReports/{reportId}` | `bloodtest.BloodTestReport` | sampleDate, labSource, pdfStoragePath, contentHash (SHA-256, dedupe), markers:`List<ExtractedMarker>{name,value,unit,refRangeLow,refRangeHigh,flag}` |
| `users/{u}/medications/{medId}` | `medication.Medication` | drugId, customName, status, dose, unit, frequency:`FrequencyConfig`, timeSlots, protocolId, startDate/endDate, discontinueReason, discontinueNotes, notes, prescribedBy, `dosagePeriods:List<DosagePeriod>`, correlatedMarkers |
| ↳ embedded `DosagePeriod` | `medication.DosagePeriod` | dose, unit, startDate, endDate (null = active). Exactly one open period, non-overlapping; `validate()` enforces it |
| `users/{u}/medications/{medId}/adherence/{date}` | `medication.AdherenceLog` | date (doc id = ISO date), doses:`List<DoseLog>{window,takenAt,dose}` |
| `users/{u}/medications/{medId}/history/{id}` | `medication.MedicationHistory` | changeType, previousValue (JSON), newValue (JSON), changedAt |
| `users/{u}/settings/medicationReminders` | `medication.ReminderSettings` | singleton doc: enabled, `windowTimes:Map<TimeWindow,LocalTime>` (default slot per window), `perMedication:Map<medId,MedicationOverride{enabled,times}>`. Scheduling is on-device; the backend only stores this doc (IMPL-16) |
| `users/{u}/protocols/{protocolId}` | `medication.Protocol` | name, description, medicationIds |
| `drugs/{drugId}` **(shared)** | `medication.Drug` | name, aliases, category, form, defaultUnit, commonDoses, description, imageUrl, imageCandidates, imageFallback, suggestedMarkers, `aliasOfDrugId` (non-null = alias row, hidden from search) |
| `users/{u}/goals/{goalId}` | `goals.Goal` | title, description, domain, status:`{ACTIVE,COMPLETED,ARCHIVED}`, startDate, targetDate, `phaseOrder:List<phaseId>` (sequence source of truth), source |
| `users/{u}/goals/{g}/phases/{phaseId}` | `goals.Phase` | title, orderIndex, status:`{LOCKED,ACTIVE,COMPLETED}`, targetStart/End, `stepOrder:List<stepId>` |
| `users/{u}/goals/{g}/phases/{p}/steps/{stepId}` | `goals.Step` | title, orderIndex, kind:`{MANUAL,THRESHOLD,SUSTAINED,COUNT}`, done, manualOverride, metric:`StepMetricBinding` (null for MANUAL) |
| ↳ embedded `StepMetricBinding` | `goals.StepMetricBinding` | metricKey (dotted, e.g. `blood.ldl`), comparator:`{LT,LTE,GT,GTE,EQ}`, targetValue, windowDays (SUSTAINED), countFrom (COUNT) |
| `users/{u}/goalChatThreads/{threadId}` | `goals.chat.GoalChatThread` | title, createdAt/updatedAt |
| `users/{u}/goalChatThreads/{t}/messages/{id}` | `goals.chat.GoalChatMessage` | role, content, proposalJson (validated `GoalProposalDto`, ASSISTANT only) |
| `users/{u}/nutritionDailyLogs/{date}` | `nutrition.NutritionDailyLog` | proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, caloriesKcal — denormalized daily roll-up |
| `users/{u}/nutritionDays/{date}/entries/{id}` | `nutrition.FoodEntry` | meal:`{BREAKFAST,LUNCH,DINNER,SNACK}`, foodId, foodName, servingLabel, servingGrams, quantity, macros (frozen snapshot), photoRef, contentHash (photo dedupe), source:`{MANUAL,CATALOG,BARCODE,LABEL,PHOTO}`, ingredients:`List<CompositeIngredient>` (composite/photo meals), mealImageUrl, mealImageStatus, analysisStatus:`{NONE,ANALYZING,READY}` (fire-and-forget describe placeholder) |
| `users/{u}/nutritionTargets/{id}` | `nutrition.MacroTarget` | macros, effectiveFrom (active = greatest ≤ today) |
| `foodCatalog/{foodId}` **(shared)** | `nutrition.CatalogFood` | name, nameLower, brand, barcode, category, macrosPer100g, servingSizes, defaultServingIndex, source:`{USDA,OPEN_FOOD_FACTS,USER,GEMINI_PHOTO,GEMINI_LABEL,GEMINI_DESCRIPTION}`, sourceRef, status:`{UNVERIFIED,VERIFIED}`, confirmationCount, verifiedAt, imageUrl, imageStatus, createdBy |
| `mealCatalog/{mealId}` **(shared)** | `nutrition.SavedMeal` | reusable named meal behind "describe a meal": name, nameLower, createdBy, ingredients (full breakdown), totalGrams, macros (per-serving), source:`GEMINI_DESCRIPTION`, imageUrl, imageStatus — name-prefix searched (user's own first) to reuse a previous meal |
| `users/{u}/locations/{locationId}` | `location.Location` | name, address, coverPhotoUrl, is24Hours, hours:`Map<DayOfWeek,HoursSlot>`, amenities, equipmentIds, equipmentSpecs:`Map<equipmentId,Map>`, isDefault, isActive |
| `equipment/{equipmentId}` **(shared catalog)** | `equipment.Equipment` | name, category, subcategory, specSchema, specs, imageUrl, imageCandidates, imageStatus:`{PENDING,GENERATED,FAILED}`, ownerId, status:`{ACTIVE,PENDING_REVIEW,REJECTED}`, contributorId, exerciseCount, `aliasOfEquipmentId` (non-null = alias, hidden) |
| `users/{u}/dailyMetrics/{date}` | `metric.DailyMetric` | steps, restingHeartRate, sleepMinutes, hrvMs, sleepScore |
| `users/{u}/deviceSyncs/{platform}` | `device.DeviceSync` | platform (doc id, e.g. `FITBIT`), lastSyncedAt — refreshed on every ingestion |
| `users/{u}/dexaScans/{scanId}` | `dexa.DexaScan` | measuredOn, sourceFacility, pdfStoragePath, contentHash, whole-body + per-region (`DexaRegion`) masses (lbs), bmdTScore/ZScore, restingMetabolicRateKcal |
| `users/{u}/weeklyWorkoutAggregates/{weekStart}` | `workoutaggregate.WeeklyWorkoutAggregate` | weekStart, totalTonnage, sessionCount |
| `users/{u}/workouts/{workoutId}` | `workout.Workout` | **session header only** — userId, activityType, locationId, startTime, endTime, source, timestamps. The per-set detail lives on the `ScheduledWorkout` below, not here. Written by the ADR-0012 active-workout-logging completion via `persistence.workout.FirestoreWorkoutRepository` |
| `users/{u}/workoutPrograms/{programId}` | `workoutprogram.WorkoutProgram` | title, description, goalId (nullable), status, source, startDate, schedule, `phaseOrder`, phases:`List<ProgramPhase>` (each with days → `Prescription`s), nutritionGuidance (IMPL-18). Despite the name, this is a **per-user subcollection** |
| `users/{u}/workoutPrograms/{p}/scheduled/{scheduledId}` | `workoutprogram.ScheduledWorkout` | materialized dated session: date, phaseId, dayId, weekIndexInPhase, isDeload, locationId, status, `session:WorkoutDay` (snapshot), completedAt, durationSeconds |
| ↳ embedded `Prescription` (in `session`) | `workoutprogram.Prescription` | exerciseId, orderIndex, sets, repsMin/Max, durationSeconds, intensity, restSeconds, tempo, deloadModifier, `loggedSets:List<LoggedSet>` (actuals), targetWeightLbs + loadBasis (IMPL-18 history-grounded load) |
| ↳ embedded `LoggedSet` | `workoutprogram.LoggedSet` | weightLbs, reps, rpe, restSeconds, completedAt, durationSeconds — the set-by-set actuals the coach writes |
| `users/{u}/workoutProgramChatThreads/{t}` + `/messages/{id}` | `workoutprogram.chat` | designer chat threads/messages (mirrors the goal-chat shape; `FirestoreWorkoutProgramChatRepository`) |
| `exercises/{exerciseId}` **(shared catalog)** | `exercise.Exercise` | name, nameLower, aliases, movementPattern, primary/secondaryMuscles, laterality, mechanic, requiredEquipment, suitableBlockTypes, `demoFrames:List<DemoFrame>` + `demoPlan` (IMPL-19 dynamic frames), mediaStatus/planStatus, status, reviewed, `aliasOfExerciseId` |
| `users/{u}/fcmTokens/{deviceId}` | `push.FcmToken` | per-device push token for sync push-to-pull; register/delete via `/api/me/devices/fcm` |
| `users/{u}/idempotency/{key}` | sync outbox replay guard | dedupe store so a replayed offline write applies once (`persistence.sync.FirestoreIdempotencyStore`) |

## Blood markers

`BloodMarker` ∈ `{ TOTAL_CHOLESTEROL, LDL, HDL, TRIGLYCERIDES, APO_B, HBA1C,
FASTING_GLUCOSE, HS_CRP, TESTOSTERONE }`. Reference ranges and display metadata
live in `BloodReferenceRanges` (backend) and `MarkerCatalog` (Android).

## Latest-value reads (`findLatest*`)

Hot reads for "current" values (e.g. `BodyCompositionRepository.findLatest`,
`findLatestByMarker`, `findLatestDailyMetric`) use indexed single-doc queries —
`whereEqualTo(field, …).orderBy(sortField, DESC).limit(1)` backed by composite
indexes in `infra/firestore/firestore.indexes.json` — rather than paging a
collection. This is the IMPL-20 backend performance pattern; preserve it when
adding new "latest X" lookups.
