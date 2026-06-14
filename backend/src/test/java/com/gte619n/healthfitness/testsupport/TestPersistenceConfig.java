package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.config.SseStreamer;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.goals.chat.GoalChatRepository;
import com.gte619n.healthfitness.integrations.goals.GoalChatClient;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogRepository;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import com.gte619n.healthfitness.testsupport.nutrition.InMemoryNutritionDailyLogRepository;
import com.gte619n.healthfitness.testsupport.workoutaggregate.InMemoryWeeklyWorkoutAggregateRepository;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationHistory;
import com.gte619n.healthfitness.core.medication.MedicationHistoryRepository;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;
import com.gte619n.healthfitness.core.medication.Protocol;
import com.gte619n.healthfitness.core.medication.ProtocolRepository;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.testsupport.push.InMemoryFcmTokenRepository;
import com.gte619n.healthfitness.testsupport.push.RecordingFcmSender;
import com.gte619n.healthfitness.testsupport.sync.InMemorySyncChangeReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// Replaces the Firestore-backed persistence beans with in-memory fakes for
// unit tests that don't need real Firestore. Wired by @Import on each test
// class that needs it.
//
// Beans below come in two flavors:
//  - Full in-memory impl (InMemory*Repository) for repos exercised by tests.
//  - Empty stub (lambdas/anonymous) for repos that exist only so the
//    ApplicationContext can satisfy dependency injection; calling these
//    methods from a test will return empty/no-op without failing.
@TestConfiguration
public class TestPersistenceConfig {

    // Run SSE streaming bodies inline instead of on a virtual thread. Under
    // MockMvc the streaming thread races asyncDispatch's Spring Security header
    // writers over the non-thread-safe MockHttpServletResponse; streaming
    // synchronously makes the body finish before the dispatch, so the async
    // SSE controller tests (GoalChat*) are deterministic.
    @Bean
    @Primary
    SseStreamer synchronousSseStreamer() {
        return new SseStreamer() {
            @Override
            public void stream(Runnable task) {
                task.run();
            }
        };
    }

    @Bean
    UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    // ADR-0010: in-memory refresh-token store (Firestore is off in tests).
    @Bean
    com.gte619n.healthfitness.core.auth.RefreshTokenStore refreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }

    // IMPL-AND-20 Phase 1: in-memory delta-read change source. Tests seed it
    // with created/archived docs and page through GET /api/me/sync. Exposed as
    // the concrete type so tests can @Autowired it directly; it also satisfies
    // the SyncChangeReader dependency of SyncService.
    @Bean
    InMemorySyncChangeReader syncChangeReader() {
        return new InMemorySyncChangeReader();
    }

    @Bean
    BodyCompositionRepository bodyCompositionRepository() {
        return new InMemoryBodyCompositionRepository();
    }

    @Bean
    DailyMetricRepository dailyMetricRepository() {
        return new InMemoryDailyMetricRepository();
    }

    @Bean
    DeviceSyncRepository deviceSyncRepository() {
        return new InMemoryDeviceSyncRepository();
    }

    // IMPL-AND-20 Phase 2: in-memory FCM token registry + capturing transport.
    // Exposed as concrete types so fan-out tests can @Autowired them directly to
    // seed tokens and assert recipients/payload. RecordingFcmSender is @Primary
    // so it wins over the production NoOpFcmSender default.
    @Bean
    InMemoryFcmTokenRepository fcmTokenRepository() {
        return new InMemoryFcmTokenRepository();
    }

    @Bean
    @Primary
    RecordingFcmSender recordingFcmSender() {
        return new RecordingFcmSender();
    }

    @Bean
    BloodReadingRepository bloodReadingRepository() {
        return new InMemoryBloodReadingRepository();
    }

    @Bean
    LocationRepository locationRepository() {
        return new InMemoryLocationRepository();
    }

    @Bean
    EquipmentRepository equipmentRepository() {
        return new InMemoryEquipmentRepository();
    }

    @Bean
    com.gte619n.healthfitness.core.exercise.ExerciseRepository exerciseRepository() {
        return new InMemoryExerciseRepository();
    }

    // IMPL-15: the live GeminiExerciseMetadataEnricher is gated off in tests
    // (app.exercises.enrich-enabled = false). Provide a deterministic fake so
    // WorkoutHistoryImporter (a core @Service) can wire and the seed-mapping
    // test can produce meaningful preview output offline.
    @Bean
    com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher exerciseMetadataEnricher() {
        return new com.gte619n.healthfitness.testsupport.exercise.FakeExerciseMetadataEnricher();
    }

    @Bean
    com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository workoutProgramRepository() {
        return new com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository();
    }

    @Bean
    com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository scheduledWorkoutRepository() {
        return new com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository();
    }

    @Bean
    com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository workoutProgramChatRepository() {
        return new com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramChatRepository();
    }

    @Bean
    GoalRepository goalRepository() {
        return new InMemoryGoalRepository();
    }

    @Bean
    PhaseRepository phaseRepository() {
        return new InMemoryPhaseRepository();
    }

    @Bean
    StepRepository stepRepository() {
        return new InMemoryStepRepository();
    }

    @Bean
    GoalChatRepository goalChatRepository() {
        return new InMemoryGoalChatRepository();
    }

    // The live GeminiGoalChatClient is gated off in tests (app.goals.enabled
    // = false) because it requires a real API key. Provide a deterministic
    // fake so the GoalChatController can wire and so chat tests can assert on
    // streamed tokens + proposals. The default fake echoes a short reply and
    // emits no proposal; GoalChatControllerTest installs its own richer fake.
    @Bean
    GoalChatClient goalChatClient() {
        return (history, userMessage, healthContext, onToken) -> {
            String reply = "Thanks, let me think about that.";
            for (String word : reply.split(" ")) {
                onToken.accept(word + " ");
            }
            return new GoalChatClient.StreamResult(reply, null);
        };
    }

    // IMPL-15: the live GeminiWorkoutProgramChatClient is gated off in tests
    // (app.workout-programs.enabled = false). Provide a deterministic fake so
    // the WorkoutProgramChatController can wire; it echoes a reply and emits no
    // proposal (chat tests can install a richer fake).
    @Bean
    com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient workoutProgramChatClient() {
        return (history, userMessage, context, onToken) -> {
            String reply = "Let me design that program.";
            for (String word : reply.split(" ")) {
                onToken.accept(word + " ");
            }
            return new com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient
                .StreamResult(reply, null);
        };
    }

    @Bean
    NutritionDailyLogRepository nutritionDailyLogRepository() {
        return new InMemoryNutritionDailyLogRepository();
    }

    @Bean
    WeeklyWorkoutAggregateRepository weeklyWorkoutAggregateRepository() {
        return new InMemoryWeeklyWorkoutAggregateRepository();
    }

    // ADR-0012: the Firestore-backed workout repo is gated off with the rest
    // of persistence; the completion fan-out and the workouts.count metric
    // need a real in-memory store so logged sessions round-trip in tests.
    @Bean
    com.gte619n.healthfitness.core.workout.WorkoutRepository workoutRepository() {
        return new com.gte619n.healthfitness.testsupport.workout.InMemoryWorkoutRepository();
    }

    // ---- empty no-op stubs to satisfy app context wiring ----

    // IMPL-13 nutrition repos: the FoodCatalogService / FoodController and the
    // entry/target services are component-scanned into the app context, so the
    // context needs these beans to wire even in tests that don't exercise them.
    @Bean
    FoodCatalogRepository foodCatalogRepository() {
        return new FoodCatalogRepository() {
            @Override public Optional<CatalogFood> findById(String foodId) { return Optional.empty(); }
            @Override public List<CatalogFood> searchByNamePrefix(String prefixLower, int limit) { return List.of(); }
            @Override public Optional<CatalogFood> findByBarcode(String code) { return Optional.empty(); }
            @Override public List<CatalogFood> findByImageStatus(
                com.gte619n.healthfitness.core.nutrition.FoodImageStatus status, int limit) { return List.of(); }
            @Override public void save(CatalogFood food) {}
            @Override public void saveConfirmation(String foodId, String userId) {}
            @Override public int countConfirmations(String foodId) { return 0; }
        };
    }

    @Bean
    FoodEntryRepository foodEntryRepository() {
        // Real in-memory map (keyed by userId|date|entryId) so entry CRUD round-
        // trips in MockMvc tests — the unified delta + idempotent-replay paths
        // (IMPL-AND-20 #8) depend on findById returning the originally-saved
        // entry on an Idempotency-Key replay.
        return new FoodEntryRepository() {
            private final java.util.Map<String, FoodEntry> store =
                new java.util.concurrent.ConcurrentHashMap<>();

            private String key(String userId, LocalDate date, String entryId) {
                return userId + "|" + date + "|" + entryId;
            }

            @Override public List<FoodEntry> findByDate(String userId, LocalDate date) {
                return store.values().stream()
                    .filter(e -> e.userId().equals(userId) && e.date().equals(date))
                    .toList();
            }

            @Override public Optional<FoodEntry> findById(String userId, LocalDate date, String entryId) {
                return Optional.ofNullable(store.get(key(userId, date, entryId)));
            }

            @Override public Optional<FoodEntry> findByContentHash(String userId, LocalDate date, String contentHash) {
                return store.values().stream()
                    .filter(e -> e.userId().equals(userId) && e.date().equals(date)
                        && contentHash != null && contentHash.equals(e.contentHash()))
                    .findFirst();
            }

            @Override public void save(FoodEntry entry) {
                store.put(key(entry.userId(), entry.date(), entry.entryId()), entry);
            }

            @Override public void delete(String userId, LocalDate date, String entryId) {
                store.remove(key(userId, date, entryId));
            }
        };
    }

    @Bean
    com.gte619n.healthfitness.core.nutrition.SavedMealRepository savedMealRepository() {
        // Real in-memory map so describe-meal resolve/log round-trips in MockMvc
        // tests (find a previous meal, then log it by id).
        return new com.gte619n.healthfitness.core.nutrition.SavedMealRepository() {
            private final java.util.Map<String, com.gte619n.healthfitness.core.nutrition.SavedMeal> store =
                new java.util.concurrent.ConcurrentHashMap<>();

            @Override public Optional<com.gte619n.healthfitness.core.nutrition.SavedMeal> findById(String mealId) {
                return Optional.ofNullable(store.get(mealId));
            }

            @Override public List<com.gte619n.healthfitness.core.nutrition.SavedMeal> searchByNamePrefix(
                String prefixLower, int limit) {
                return store.values().stream()
                    .filter(m -> m.nameLower() != null && m.nameLower().startsWith(prefixLower))
                    .limit(limit)
                    .toList();
            }

            @Override public void save(com.gte619n.healthfitness.core.nutrition.SavedMeal meal) {
                store.put(meal.mealId(), meal);
            }

            @Override public List<com.gte619n.healthfitness.core.nutrition.SavedMeal> findByImageStatus(
                com.gte619n.healthfitness.core.nutrition.FoodImageStatus status, int limit) {
                return store.values().stream()
                    .filter(m -> m.imageStatus() == status)
                    .limit(limit)
                    .toList();
            }
        };
    }

    @Bean
    MacroTargetRepository macroTargetRepository() {
        return new MacroTargetRepository() {
            @Override public Optional<MacroTarget> findActive(String userId) { return Optional.empty(); }
            @Override public void save(MacroTarget target) {}
            @Override public List<MacroTarget> findAll(String userId) { return List.of(); }
        };
    }

    @Bean
    DrugRepository drugRepository() {
        return new DrugRepository() {
            @Override public Optional<Drug> findById(String drugId) { return Optional.empty(); }
            @Override public List<Drug> findAll() { return List.of(); }
            @Override public List<Drug> search(String query) { return List.of(); }
            @Override public Optional<Drug> findByNameIgnoreCase(String name) { return Optional.empty(); }
            @Override public void save(Drug drug) {}
            @Override public void delete(String drugId) {}
        };
    }

    @Bean
    MedicationRepository medicationRepository() {
        return new MedicationRepository() {
            @Override public Optional<Medication> findById(String userId, String medicationId) { return Optional.empty(); }
            @Override public List<Medication> findByUser(String userId) { return List.of(); }
            @Override public List<Medication> findByUserAndStatus(String userId, MedicationStatus status) { return List.of(); }
            @Override public List<Medication> findByProtocol(String userId, String protocolId) { return List.of(); }
            @Override public void save(Medication medication) {}
            @Override public void delete(String userId, String medicationId) {}
            @Override public List<Medication> findAllReferencingDrug(String drugId) { return List.of(); }
        };
    }

    @Bean
    ProtocolRepository protocolRepository() {
        return new ProtocolRepository() {
            @Override public Optional<Protocol> findById(String userId, String protocolId) { return Optional.empty(); }
            @Override public List<Protocol> findByUser(String userId) { return List.of(); }
            @Override public void save(Protocol protocol) {}
            @Override public void delete(String userId, String protocolId) {}
        };
    }

    @Bean
    AdherenceRepository adherenceRepository() {
        return new AdherenceRepository() {
            @Override public Optional<AdherenceLog> findByDate(String userId, String medicationId, LocalDate date) { return Optional.empty(); }
            @Override public List<AdherenceLog> findByDateRange(String userId, String medicationId, LocalDate from, LocalDate to) { return List.of(); }
            @Override public List<AdherenceLog> findByUserAndDateRange(String userId, LocalDate from, LocalDate to) { return List.of(); }
            @Override public void save(AdherenceLog log) {}
            @Override public void deleteByDate(String userId, String medicationId, LocalDate date) {}
        };
    }

    @Bean
    com.gte619n.healthfitness.core.medication.ReminderSettingsRepository reminderSettingsRepository() {
        return new com.gte619n.healthfitness.core.medication.ReminderSettingsRepository() {
            private final java.util.Map<String, com.gte619n.healthfitness.core.medication.ReminderSettings> rows =
                new java.util.concurrent.ConcurrentHashMap<>();
            @Override public Optional<com.gte619n.healthfitness.core.medication.ReminderSettings> find(String userId) {
                return Optional.ofNullable(rows.get(userId));
            }
            @Override public void save(com.gte619n.healthfitness.core.medication.ReminderSettings settings) {
                rows.put(settings.userId(), settings);
            }
        };
    }

    @Bean
    MedicationHistoryRepository medicationHistoryRepository() {
        return new MedicationHistoryRepository() {
            @Override public List<MedicationHistory> findByMedication(String userId, String medicationId) { return List.of(); }
            @Override public void save(MedicationHistory history) {}
        };
    }

    @Bean
    BloodTestReportRepository bloodTestReportRepository() {
        return new BloodTestReportRepository() {
            @Override public void save(BloodTestReport report) {}
            @Override public Optional<BloodTestReport> findById(String userId, String reportId) { return Optional.empty(); }
            @Override public List<BloodTestReport> findByUser(String userId) { return List.of(); }
            @Override public Optional<BloodTestReport> findByContentHash(String userId, String contentHash) { return Optional.empty(); }
            @Override public void delete(String userId, String reportId) {}
        };
    }

    @Bean
    DexaScanRepository dexaScanRepository() {
        return new DexaScanRepository() {
            @Override public void save(DexaScan scan) {}
            @Override public Optional<DexaScan> findById(String userId, String scanId) { return Optional.empty(); }
            @Override public List<DexaScan> findByUser(String userId) { return List.of(); }
            @Override public Optional<DexaScan> findByContentHash(String userId, String contentHash) { return Optional.empty(); }
            @Override public void delete(String userId, String scanId) {}
        };
    }
}
