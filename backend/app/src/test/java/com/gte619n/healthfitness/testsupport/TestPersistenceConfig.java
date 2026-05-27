package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentParser;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.StepRepository;
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
import java.util.Collection;
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

    // ---- empty no-op stubs to satisfy app context wiring ----

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

    @Bean
    EquipmentRepository equipmentRepository() {
        return new EquipmentRepository() {
            @Override public Optional<Equipment> findById(String equipmentId) { return Optional.empty(); }
            @Override public List<Equipment> findByIds(Collection<String> equipmentIds) { return List.of(); }
            @Override public List<Equipment> findCatalog(String search, String category, String subcategory) { return List.of(); }
            @Override public List<Equipment> findByOwner(String ownerId) { return List.of(); }
            @Override public List<Equipment> findPendingReview() { return List.of(); }
            @Override public void save(Equipment equipment) {}
            @Override public void delete(String equipmentId) {}
        };
    }

    @Bean
    EquipmentParser equipmentParser() {
        return rawText -> List.of();
    }
}
