package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.catalog.CatalogAdHocResponse;
import com.gte619n.healthfitness.api.catalog.CatalogProgramResponse;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.adhoc.catalog.AdHocCatalogService;
import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkout;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.catalog.CatalogStatusMapping;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.catalog.ProgramCatalogService;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-MULTIUSER-01 P3.5 (D7/D8) — the single admin "Curation" console surface.
 *
 * <p>{@code GET /queue} aggregates every {@code PENDING_REVIEW} item across the
 * heterogeneous catalogs (programs, ad-hoc, equipment, exercises) into one
 * {@link CatalogStatus}-normalized list. The approve/reject mutations for the
 * two NEW catalog types (programs, ad-hoc) are owned here. For the pre-existing
 * equipment/exercise/food catalogs the queue only READS their pending lists —
 * their approve/reject is delegated to their established admin controllers
 * ({@code AdminEquipmentController}, {@code AdminExerciseController}, the food
 * promote path) so mutation logic is never duplicated.
 */
@RestController
@RequestMapping("/api/admin/curation")
@AdminOnly
public class AdminCurationController {

    private final CurrentUserProvider currentUser;
    private final ProgramCatalogService programs;
    private final AdHocCatalogService adhoc;
    private final EquipmentService equipment;
    private final ExerciseService exercises;
    private final FoodCatalogService foods;

    public AdminCurationController(
        CurrentUserProvider currentUser,
        ProgramCatalogService programs,
        AdHocCatalogService adhoc,
        EquipmentService equipment,
        ExerciseService exercises,
        FoodCatalogService foods
    ) {
        this.currentUser = currentUser;
        this.programs = programs;
        this.adhoc = adhoc;
        this.equipment = equipment;
        this.exercises = exercises;
        this.foods = foods;
    }

    @GetMapping("/queue")
    public List<CurationQueueItem> queue() {
        List<CurationQueueItem> out = new ArrayList<>();

        for (CatalogWorkoutProgram p : programs.listPendingReview()) {
            CatalogProvenance pr = p.provenance();
            out.add(new CurationQueueItem("program", p.catalogId(), p.title(),
                p.status(), pr == null ? null : pr.contributorId(), p.createdAt()));
        }
        for (CatalogAdHocWorkout a : adhoc.listPendingReview()) {
            CatalogProvenance pr = a.provenance();
            out.add(new CurationQueueItem("adhoc", a.catalogId(), a.title(),
                a.status(), pr == null ? null : pr.contributorId(), a.createdAt()));
        }
        // Pre-existing catalogs: READ-ONLY aggregation (mutation delegated).
        equipment.findPendingSubmissions().forEach(e -> out.add(new CurationQueueItem(
            "equipment", e.equipmentId(), e.name(),
            CatalogStatusMapping.fromEquipment(e.status()), e.contributorId(), e.createdAt())));
        exercises.findReviewQueue().forEach(e -> out.add(new CurationQueueItem(
            "exercise", e.exerciseId(), e.name(),
            CatalogStatusMapping.fromExercise(e.status()), e.contributorId(), e.createdAt())));
        // Foods: user-sourced UNVERIFIED foods awaiting promotion to VERIFIED
        // (D7 reconciliation). contributorId = the user who created the food.
        for (CatalogFood f : foods.listPendingVerification()) {
            out.add(new CurationQueueItem("food", f.foodId(), f.name(),
                CatalogStatusMapping.fromFood(f.status()), f.createdBy(), f.createdAt()));
        }

        return out;
    }

    // ---- food promote-to-VERIFIED (D7 reconciliation; owned here) ----

    @PostMapping("/foods/{id}/verify")
    public CurationQueueItem verifyFood(@PathVariable String id) {
        try {
            CatalogFood f = foods.promoteToVerified(id);
            return new CurationQueueItem("food", f.foodId(), f.name(),
                CatalogStatusMapping.fromFood(f.status()), f.createdBy(), f.createdAt());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    // ---- program approve/reject (owned here) ----

    @PostMapping("/programs/{id}/approve")
    public CatalogProgramResponse approveProgram(@PathVariable String id) {
        try {
            return CatalogProgramResponse.from(programs.approve(id, adminId()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/programs/{id}/reject")
    public CatalogProgramResponse rejectProgram(
        @PathVariable String id, @RequestBody RejectRequest req) {
        try {
            return CatalogProgramResponse.from(programs.reject(id, adminId(), req.reason()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    // ---- ad-hoc approve/reject (owned here) ----

    @PostMapping("/adhoc/{id}/approve")
    public CatalogAdHocResponse approveAdHoc(@PathVariable String id) {
        try {
            return CatalogAdHocResponse.from(adhoc.approve(id, adminId()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/adhoc/{id}/reject")
    public CatalogAdHocResponse rejectAdHoc(
        @PathVariable String id, @RequestBody RejectRequest req) {
        try {
            return CatalogAdHocResponse.from(adhoc.reject(id, adminId(), req.reason()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    private String adminId() {
        return currentUser.get().userId();
    }

    public record RejectRequest(String reason) {}
}
