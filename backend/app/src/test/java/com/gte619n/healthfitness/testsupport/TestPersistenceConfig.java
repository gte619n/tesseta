package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
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
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

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

    @Bean
    UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    BodyCompositionRepository bodyCompositionRepository() {
        return new InMemoryBodyCompositionRepository();
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

    @Bean
    NutritionDailyLogRepository nutritionDailyLogRepository() {
        return new InMemoryNutritionDailyLogRepository();
    }

    @Bean
    WeeklyWorkoutAggregateRepository weeklyWorkoutAggregateRepository() {
        return new InMemoryWeeklyWorkoutAggregateRepository();
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
        return new FoodEntryRepository() {
            @Override public List<FoodEntry> findByDate(String userId, LocalDate date) { return List.of(); }
            @Override public Optional<FoodEntry> findById(String userId, LocalDate date, String entryId) { return Optional.empty(); }
            @Override public void save(FoodEntry entry) {}
            @Override public void delete(String userId, LocalDate date, String entryId) {}
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
