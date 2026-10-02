package com.gte619n.healthfitness.api.catalog;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramAssembler;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.catalog.ProgramCatalogService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-MULTIUSER-01 P3.2 — the authed (non-admin) catalog surface for shared
 * workout-program templates:
 * <ul>
 *   <li>{@code POST /api/me/workout-programs/{id}/submit-for-promotion} — copy the
 *       caller's program into the catalog as PENDING_REVIEW, generalized (D9/D10).</li>
 *   <li>{@code GET /api/programs} — browse PUBLISHED-only, non-aliased templates.</li>
 *   <li>{@code GET /api/programs/{id}} — one PUBLISHED template.</li>
 *   <li>{@code POST /api/programs/{id}/adopt} — snapshot-copy into the caller's own
 *       programs (D11), returning the new per-user program (deep view).</li>
 * </ul>
 * Admin approve/reject lives on {@code AdminCurationController}.
 */
@RestController
public class ProgramCatalogController {

    private final CurrentUserProvider currentUser;
    private final ProgramCatalogService service;
    private final WorkoutProgramAssembler assembler;

    public ProgramCatalogController(
        CurrentUserProvider currentUser,
        ProgramCatalogService service,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.assembler = assembler;
    }

    @PostMapping("/api/me/workout-programs/{id}/submit-for-promotion")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public CatalogProgramResponse submit(@PathVariable String id) {
        String userId = currentUser.get().userId();
        try {
            return CatalogProgramResponse.from(service.submitForPromotion(userId, id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/api/programs")
    public List<CatalogProgramResponse> browse() {
        return service.listPublished().stream().map(CatalogProgramResponse::from).toList();
    }

    @GetMapping("/api/programs/{id}")
    public CatalogProgramResponse one(@PathVariable String id) {
        CatalogWorkoutProgram c = service.findPublished(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return CatalogProgramResponse.from(c);
    }

    @PostMapping("/api/programs/{id}/adopt")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public WorkoutProgramDeepResponse adopt(@PathVariable String id) {
        String userId = currentUser.get().userId();
        try {
            WorkoutProgram adopted = service.adopt(userId, id);
            return assembler.deep(adopted);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
