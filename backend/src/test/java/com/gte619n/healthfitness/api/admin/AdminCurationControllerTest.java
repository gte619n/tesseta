package com.gte619n.healthfitness.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutService;
import com.gte619n.healthfitness.core.adhoc.EquipmentContext;
import com.gte619n.healthfitness.core.adhoc.catalog.AdHocCatalogService;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogRepository;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.catalog.ProgramCatalogService;
import com.gte619n.healthfitness.testsupport.adhoc.InMemoryAdHocWorkoutRepository;
import com.gte619n.healthfitness.testsupport.catalog.InMemoryCatalogAdHocWorkoutRepository;
import com.gte619n.healthfitness.testsupport.catalog.InMemoryCatalogWorkoutProgramRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P3.5 — curation queue aggregates PENDING across types. */
class AdminCurationControllerTest {

    private ProgramCatalogService programCatalog;
    private AdHocCatalogService adhocCatalog;
    private WorkoutProgramService programService;
    private AdHocWorkoutService adhocService;
    private FoodCatalogService foodCatalog;
    private FakeFoodRepo foodRepo;
    private AdminCurationController controller;

    private static final String USER = "user-1";

    /** Minimal in-memory food catalog repo with the pending-verification query. */
    private static final class FakeFoodRepo implements FoodCatalogRepository {
        final java.util.Map<String, CatalogFood> byId = new java.util.LinkedHashMap<>();
        @Override public java.util.Optional<CatalogFood> findById(String id) {
            return java.util.Optional.ofNullable(byId.get(id));
        }
        @Override public List<CatalogFood> searchByNamePrefix(String p, int n) { return List.of(); }
        @Override public List<CatalogFood> searchByTokens(List<String> w, int n) { return List.of(); }
        @Override public java.util.Optional<CatalogFood> findByBarcode(String c) {
            return java.util.Optional.empty();
        }
        @Override public List<CatalogFood> findByImageStatus(FoodImageStatus s, int n) { return List.of(); }
        @Override public List<CatalogFood> findPendingVerification(int limit) {
            return byId.values().stream()
                .filter(f -> f.status() == FoodStatus.UNVERIFIED)
                .filter(f -> f.createdBy() != null && !f.createdBy().isBlank())
                .filter(f -> !f.isArchived() && !f.isDrink())
                .limit(limit)
                .toList();
        }
        @Override public void save(CatalogFood f) { byId.put(f.foodId(), f); }
        @Override public void saveConfirmation(String id, String u) {}
        @Override public int countConfirmations(String id) { return 0; }
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new IllegalStateException("no bean"); }
            @Override public T getObject() { throw new IllegalStateException("no bean"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    private CatalogFood userFood(String id, String name, FoodStatus status) {
        return new CatalogFood(
            id, name, name.toLowerCase(), null, null, "meal",
            new Macros(100.0, 10.0, 5.0, 2.0, null, null), List.of(), 0,
            FoodSource.GEMINI_PHOTO, null, status, 0, null, null, FoodImageStatus.NONE,
            USER, java.time.Instant.now(), null, null, null);
    }

    @BeforeEach
    void setUp() {
        var userPrograms = new InMemoryWorkoutProgramRepository();
        var userAdhoc = new InMemoryAdHocWorkoutRepository();
        programService = new WorkoutProgramService(userPrograms);
        adhocService = new AdHocWorkoutService(userAdhoc);
        programCatalog = new ProgramCatalogService(
            new InMemoryCatalogWorkoutProgramRepository(), programService);
        adhocCatalog = new AdHocCatalogService(
            new InMemoryCatalogAdHocWorkoutRepository(), adhocService);

        EquipmentService equipment = mock(EquipmentService.class);
        when(equipment.findPendingSubmissions()).thenReturn(List.of());
        ExerciseService exercises = mock(ExerciseService.class);
        when(exercises.findReviewQueue()).thenReturn(List.of());

        foodRepo = new FakeFoodRepo();
        foodCatalog = new FoodCatalogService(foodRepo, 1, emptyProvider(), emptyProvider());

        CurrentUserProvider currentUser = () -> new CurrentUser("admin-9", "a@b.c", "Admin", null);
        controller = new AdminCurationController(
            currentUser, programCatalog, adhocCatalog, equipment, exercises, foodCatalog);
    }

    private void seedPendingProgram() {
        Prescription rx = new Prescription("ex", 0, 3, 8, 8, null, null, 120, null, null, null, null);
        WorkoutDay day = new WorkoutDay("d", "D", null, null, 0,
            List.of(new Block("b", BlockType.MAIN, "M", 0, List.of(rx))));
        ProgramPhase phase = new ProgramPhase("ph", "Base", "Str", 0,
            ProgramPhaseStatus.ACTIVE, 4, null, null, null, null, List.of(day));
        WorkoutProgram p = programService.create(new WorkoutProgram(
            USER, null, "Prog", "d", null, ProgramStatus.ACTIVE, ProgramSource.MANUAL,
            null, null, List.of(), List.of(phase), null, null, null));
        programCatalog.submitForPromotion(USER, p.programId());
    }

    private void seedPendingAdhoc() {
        Prescription rx = new Prescription("ex", 0, 3, 8, 8, null, null, 120, null, null, null, null);
        WorkoutDay day = new WorkoutDay("d", "D", null, null, 0,
            List.of(new Block("b", BlockType.MAIN, "M", 0, List.of(rx))));
        AdHocWorkout a = adhocService.create(new AdHocWorkout(
            USER, null, "Adhoc", "s", AdHocSource.MANUAL, null, EquipmentContext.empty(),
            null, null, List.of(), false, day, 0, null, null, null));
        adhocCatalog.submitForPromotion(USER, a.adhocId());
    }

    @Test
    void queueAggregatesProgramsAndAdhoc() {
        seedPendingProgram();
        seedPendingAdhoc();

        List<CurationQueueItem> queue = controller.queue();

        assertThat(queue).extracting(CurationQueueItem::type)
            .containsExactlyInAnyOrder("program", "adhoc");
        assertThat(queue).allMatch(i -> i.status() == CatalogStatus.PENDING_REVIEW);
        assertThat(queue).allMatch(i -> USER.equals(i.contributorId()));
    }

    @Test
    void queueIncludesPendingUnverifiedUserFoodsButNotVerified() {
        foodRepo.save(userFood("f-pending", "Grilled Chicken", FoodStatus.UNVERIFIED));
        foodRepo.save(userFood("f-verified", "Verified Rice", FoodStatus.VERIFIED));

        List<CurationQueueItem> queue = controller.queue();

        assertThat(queue).filteredOn(i -> "food".equals(i.type()))
            .extracting(CurationQueueItem::id)
            .containsExactly("f-pending");
    }

    @Test
    void verifyFoodFlipsUnverifiedToPublishedAndLeavesTheQueue() {
        foodRepo.save(userFood("f1", "Grilled Chicken", FoodStatus.UNVERIFIED));

        CurationQueueItem result = controller.verifyFood("f1");

        assertThat(result.status()).isEqualTo(CatalogStatus.PUBLISHED);
        assertThat(foodRepo.byId.get("f1").status()).isEqualTo(FoodStatus.VERIFIED);
        assertThat(controller.queue()).filteredOn(i -> "food".equals(i.type())).isEmpty();
    }

    @Test
    void approveProgramPublishesAndLeavesQueue() {
        seedPendingProgram();
        String id = controller.queue().get(0).id();

        controller.approveProgram(id);

        assertThat(controller.queue()).isEmpty();
        assertThat(programCatalog.listPublished()).hasSize(1);
    }
}
