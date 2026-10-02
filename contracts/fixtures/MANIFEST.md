# Fixture manifest

Every golden fixture, the backend DTO/record it mirrors (with source path,
relative to `backend/src/main/java/com/gte619n/healthfitness/`), and the
collection/table it maps to (per `docs/reference/sync-emitted-collections.txt`).

## Envelopes

| Fixture | Backend DTO / record | Source path | Maps to |
| --- | --- | --- | --- |
| `sync/sync-response.json` | `SyncResponse` (+ nested `SyncResponse.ChangeDto`) | `api/sync/SyncResponse.java` | `GET /api/me/sync` delta page |
| `sync/write-result.json` | `WriteResult<T>` wrapping `BloodReadingResponse` | `api/sync/WriteResult.java`; body = `api/blood/BloodReadingResponse.java` (core `core/blood/BloodReading.java`) | write-path envelope (all in-scope writes) |
| `auth/token-response.json` | `AuthController.TokenResponse` | `api/auth/AuthController.java` | `POST /api/auth/{exchange,refresh,dev-login}` |
| `errors/error-envelope.json` | `GlobalExceptionHandler.ErrorResponse` | `api/error/GlobalExceptionHandler.java` | any error response |

## Collection documents

| Fixture | Backend model / DTO | Source path | Collection (emitted name) |
| --- | --- | --- | --- |
| `collections/bodyComposition.json` | `BodyCompositionMeasurement` | `core/bodycomposition/BodyCompositionMeasurement.java` | `bodyComposition` |
| `collections/bloodReadings.json` | `BloodReading` / `BloodReadingResponse` | `core/blood/BloodReading.java` | `bloodReadings` |
| `collections/bloodTestReports.json` | `BloodTestReport` (+ `ExtractedMarker`) | `core/bloodtest/BloodTestReport.java` | `bloodTestReports` |
| `collections/medications.json` | `Medication` (+ `FrequencyConfig`, `TimeSlot`, `DosagePeriod`) | `core/medication/Medication.java` | `medications` |
| `collections/medicationAdherence.json` | `AdherenceLog` (+ `DoseLog`) | `core/medication/AdherenceLog.java` | `medications/adherence` |
| `collections/medicationHistory.json` | `MedicationHistory` | `core/medication/MedicationHistory.java` | `medications/history` |
| `collections/protocols.json` | `Protocol` | `core/medication/Protocol.java` | `protocols` |
| `collections/goals.json` | `Goal` / `GoalResponse` | `core/goals/Goal.java` | `goals` |
| `collections/goalPhases.json` | `Phase` / `PhaseResponse` | `core/goals/Phase.java` | `goals/phases` |
| `collections/goalSteps.json` | `Step` / `StepResponse` (+ metric binding) | `core/goals/Step.java` | `goals/phases/steps` |
| `collections/goalChatThreads.json` | `GoalChatThread` | `core/goals/chat/GoalChatThread.java` | `goalChatThreads` |
| `collections/goalChatMessages.json` | `GoalChatMessage` | `core/goals/chat/GoalChatMessage.java` | `goalChatThreads/messages` |
| `collections/nutritionDailyLogs.json` | `NutritionDailyLog` | `core/nutrition/NutritionDailyLog.java` | `nutritionDailyLogs` |
| `collections/nutritionEntries.json` | `FoodEntry` / `EntryResponse` (+ `Macros`) | `core/nutrition/FoodEntry.java`; DTO `api/nutrition/EntryResponse.java`; macros `core/nutrition/Macros.java` | `nutritionDays/entries` |
| `collections/nutritionTargets.json` | `MacroTarget` (+ `Macros`) | `core/nutrition/MacroTarget.java`; macros `core/nutrition/Macros.java` | `nutritionTargets` |
| `collections/locations.json` | `Location` | `core/location/Location.java` | `locations` |
| `collections/dailyMetrics.json` | `DailyMetric` | `core/metric/DailyMetric.java` | `dailyMetrics` |
| `collections/deviceSyncs.json` | `Device` / `DeviceResponse` | `core/device/Device.java` | `deviceSyncs` |
| `collections/dexaScans.json` | `DexaScan` (+ `DexaRegion`) | `core/dexa/DexaScan.java` | `dexaScans` |
| `collections/weeklyWorkoutAggregates.json` | `WeeklyWorkoutAggregate` | `core/workoutaggregate/WeeklyWorkoutAggregate.java` | `weeklyWorkoutAggregates` |
| `collections/workoutPrograms.json` | `WorkoutProgram` | `core/workoutprogram/WorkoutProgram.java` | `workoutPrograms` |
| `collections/workoutScheduled.json` | `ScheduledWorkout` | `core/workoutprogram/ScheduledWorkout.java` | `workoutPrograms/scheduled` |
| `collections/adhocWorkouts.json` | `AdHocWorkout` (+ `EquipmentContext`) | `core/adhoc/AdHocWorkout.java` | `adhocWorkouts` |
| `collections/adhocSessions.json` | `ScheduledWorkout` (reused per AdHocSessionRepository) | `core/workoutprogram/ScheduledWorkout.java`; store `core/adhoc/AdHocSessionRepository.java` | `adhocWorkouts/sessions` |
| `collections/userProfile.json` | `User` | `core/user/User.java` | `users` |

## Notes on faithfulness

- `deviceSyncs` (`Device`) has **no** `updatedAt` in the model — `lastSyncedAt`
  is the natural timestamp; an `updatedAt` mirror is included so every fixture
  carries a uniform ISO timestamp, but the load-bearing field is `lastSyncedAt`.
- `medicationAdherence` (`AdherenceLog`), `medicationHistory`, `goalPhases`,
  `goalSteps`, `nutritionEntries`, `workoutScheduled`, `adhocSessions` do **not**
  all carry a server `updatedAt` on the record; their `WriteResult` `lastUpdate`
  is the controller-stamped write instant (see `WriteResult` javadoc). The
  fixtures include a representative timestamp for the deserialize test.
- `nutritionDailyLogs`, `dailyMetrics`, `weeklyWorkoutAggregates`,
  `userProfile`, `workoutScheduled` are **path-keyed** (id derived from the
  Firestore doc id: `date`, `weekStart`, `userId`, `{date}_{dayId}`), not a
  body id field — the fixture surfaces that key field.
- `adhocSessions` reuses the `ScheduledWorkout` record shape (ADR-04); its
  fixture mirrors `workoutScheduled` at the ad-hoc subcollection path.
