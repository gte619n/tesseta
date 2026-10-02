package com.gte619n.healthfitness.core.workoutprogram.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.testsupport.catalog.InMemoryCatalogWorkoutProgramRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P3.2 — promotion copy, visibility, approve/reject, adopt. */
class ProgramCatalogServiceTest {

    private InMemoryWorkoutProgramRepository userPrograms;
    private InMemoryCatalogWorkoutProgramRepository catalog;
    private WorkoutProgramService programService;
    private ProgramCatalogService service;

    private static final String USER = "user-1";
    private static final String ADMIN = "admin-9";

    @BeforeEach
    void setUp() {
        userPrograms = new InMemoryWorkoutProgramRepository();
        catalog = new InMemoryCatalogWorkoutProgramRepository();
        programService = new WorkoutProgramService(userPrograms);
        service = new ProgramCatalogService(catalog, programService);
    }

    private WorkoutProgram seedUserProgram() {
        Prescription rx = new Prescription(
            "ex-squat", 0, 3, 8, 10, null,
            new Intensity(IntensityKind.NONE, null), 120, null,
            "private note", null, null, 185.0, "last done");
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, "gym-xyz", 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        ProgramPhase phase = new ProgramPhase("ph1", "Base", "Strength", 0,
            ProgramPhaseStatus.ACTIVE, 4, null, null, null, null, List.of(day));
        WorkoutProgram input = new WorkoutProgram(
            USER, null, "My 5x5", "A strength block", "goal-42",
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, LocalDate.of(2026, 1, 1),
            null, List.of(), List.of(phase), null, null, Instant.now());
        return programService.create(input);
    }

    @Test
    void submitStripsPerUserFieldsAndGeneralizesLoads() {
        WorkoutProgram src = seedUserProgram();
        var copy = service.submitForPromotion(USER, src.programId());

        assertThat(copy.status()).isEqualTo(CatalogStatus.PENDING_REVIEW);
        assertThat(copy.provenance().contributorId()).isEqualTo(USER);
        assertThat(copy.title()).isEqualTo("My 5x5");

        // generalized load: no absolute weight, %1RM present, note stripped
        Prescription rx = copy.phases().get(0).days().get(0).blocks().get(0).prescriptions().get(0);
        assertThat(rx.targetWeightLbs()).isNull();
        assertThat(rx.notes()).isNull();
        assertThat(rx.intensity().kind()).isEqualTo(IntensityKind.PERCENT_1RM);

        // the source user program is untouched
        WorkoutProgram reloaded = userPrograms.findById(USER, src.programId()).orElseThrow();
        Prescription srcRx = reloaded.phases().get(0).days().get(0).blocks().get(0).prescriptions().get(0);
        assertThat(srcRx.targetWeightLbs()).isEqualTo(185.0);
        assertThat(srcRx.notes()).isEqualTo("private note");
    }

    @Test
    void pendingIsHiddenFromBrowseUntilApproved() {
        WorkoutProgram src = seedUserProgram();
        var copy = service.submitForPromotion(USER, src.programId());

        assertThat(service.listPublished()).isEmpty();

        service.approve(copy.catalogId(), ADMIN);
        assertThat(service.listPublished()).extracting("catalogId").containsExactly(copy.catalogId());

        var published = catalog.findById(copy.catalogId()).orElseThrow();
        assertThat(published.status()).isEqualTo(CatalogStatus.PUBLISHED);
        assertThat(published.provenance().promotedBy()).isEqualTo(ADMIN);
        assertThat(published.provenance().promotedAt()).isNotNull();
    }

    @Test
    void rejectHidesFromBrowseAndStampsReason() {
        WorkoutProgram src = seedUserProgram();
        var copy = service.submitForPromotion(USER, src.programId());

        service.reject(copy.catalogId(), ADMIN, "too niche");
        assertThat(service.listPublished()).isEmpty();
        var rejected = catalog.findById(copy.catalogId()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(CatalogStatus.REJECTED);
        assertThat(rejected.provenance().rejectedReason()).isEqualTo("too niche");
    }

    @Test
    void adoptCreatesIndependentSnapshot() {
        WorkoutProgram src = seedUserProgram();
        var copy = service.submitForPromotion(USER, src.programId());
        service.approve(copy.catalogId(), ADMIN);

        String adopter = "user-2";
        WorkoutProgram adopted = service.adopt(adopter, copy.catalogId());

        assertThat(adopted.userId()).isEqualTo(adopter);
        assertThat(adopted.programId()).isNotEqualTo(src.programId());
        assertThat(adopted.title()).isEqualTo("My 5x5");
        assertThat(adopted.goalId()).isNull();                 // not linked on adopt
        assertThat(adopted.status()).isEqualTo(ProgramStatus.DRAFT);

        // edit the SOURCE catalog row → adopted copy is unchanged (D11 snapshot)
        var edited = new com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram(
            copy.catalogId(), "RENAMED", copy.description(), copy.schedule(),
            copy.phaseOrder(), copy.phases(), CatalogStatus.PUBLISHED, copy.provenance(),
            copy.createdAt(), Instant.now());
        catalog.save(edited);

        WorkoutProgram adoptedReloaded = userPrograms.findById(adopter, adopted.programId()).orElseThrow();
        assertThat(adoptedReloaded.title()).isEqualTo("My 5x5");  // not "RENAMED"
    }
}
