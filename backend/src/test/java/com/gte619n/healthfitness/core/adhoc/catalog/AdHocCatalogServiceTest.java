package com.gte619n.healthfitness.core.adhoc.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutService;
import com.gte619n.healthfitness.core.adhoc.EquipmentContext;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.testsupport.adhoc.InMemoryAdHocWorkoutRepository;
import com.gte619n.healthfitness.testsupport.catalog.InMemoryCatalogAdHocWorkoutRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P3.3 — ad-hoc promotion copy, visibility, approve, adopt. */
class AdHocCatalogServiceTest {

    private InMemoryAdHocWorkoutRepository userAdhoc;
    private InMemoryCatalogAdHocWorkoutRepository catalog;
    private AdHocWorkoutService adhocService;
    private AdHocCatalogService service;

    private static final String USER = "user-1";
    private static final String ADMIN = "admin-9";

    @BeforeEach
    void setUp() {
        userAdhoc = new InMemoryAdHocWorkoutRepository();
        catalog = new InMemoryCatalogAdHocWorkoutRepository();
        adhocService = new AdHocWorkoutService(userAdhoc);
        service = new AdHocCatalogService(catalog, adhocService);
    }

    private AdHocWorkout seedUserAdhoc() {
        Prescription rx = new Prescription(
            "ex-kb-swing", 0, 4, 12, 12, null,
            new Intensity(IntensityKind.NONE, null), 60, null, "my note", null, null, 53.0, "last done");
        WorkoutDay day = new WorkoutDay("d", "Conditioning", null, "gym-1", 0,
            List.of(new Block("b", BlockType.MAIN, "Main", 0, List.of(rx))));
        AdHocWorkout input = new AdHocWorkout(
            USER, null, "KB Blast", "fast conditioning", AdHocSource.MANUAL, "swing prompt",
            EquipmentContext.empty(), 20, null, List.of("conditioning"), true, day,
            0, null, null, null);
        return adhocService.create(input);
    }

    @Test
    void submitStripsPerUserFieldsAndGeneralizes() {
        AdHocWorkout src = seedUserAdhoc();
        var copy = service.submitForPromotion(USER, src.adhocId());

        assertThat(copy.status()).isEqualTo(CatalogStatus.PENDING_REVIEW);
        assertThat(copy.provenance().contributorId()).isEqualTo(USER);
        Prescription rx = copy.day().blocks().get(0).prescriptions().get(0);
        assertThat(rx.targetWeightLbs()).isNull();
        assertThat(rx.notes()).isNull();
        assertThat(rx.intensity().kind()).isEqualTo(IntensityKind.PERCENT_1RM);
        assertThat(copy.day().locationId()).isNull();
    }

    @Test
    void pendingHiddenUntilApproved() {
        AdHocWorkout src = seedUserAdhoc();
        var copy = service.submitForPromotion(USER, src.adhocId());
        assertThat(service.listPublished()).isEmpty();
        service.approve(copy.catalogId(), ADMIN);
        assertThat(service.listPublished()).hasSize(1);
    }

    @Test
    void adoptCreatesIndependentCopy() {
        AdHocWorkout src = seedUserAdhoc();
        var copy = service.submitForPromotion(USER, src.adhocId());
        service.approve(copy.catalogId(), ADMIN);

        AdHocWorkout adopted = service.adopt("user-2", copy.catalogId());
        assertThat(adopted.userId()).isEqualTo("user-2");
        assertThat(adopted.adhocId()).isNotEqualTo(src.adhocId());
        assertThat(adopted.title()).isEqualTo("KB Blast");
        assertThat(adopted.runCount()).isZero();
    }
}
