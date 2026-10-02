package com.gte619n.healthfitness.api.catalog;

import com.gte619n.healthfitness.api.adhoc.AdHocWorkoutResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramAssembler;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.catalog.AdHocCatalogService;
import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkout;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-MULTIUSER-01 P3.3 — authed (non-admin) catalog surface for shared ad-hoc
 * workout templates. Parallels {@link ProgramCatalogController}.
 */
@RestController
public class AdHocCatalogController {

    private final CurrentUserProvider currentUser;
    private final AdHocCatalogService service;
    private final WorkoutProgramAssembler assembler;

    public AdHocCatalogController(
        CurrentUserProvider currentUser,
        AdHocCatalogService service,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.assembler = assembler;
    }

    @PostMapping("/api/me/adhoc-workouts/{id}/submit-for-promotion")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogAdHocResponse submit(@PathVariable String id) {
        String userId = currentUser.get().userId();
        try {
            return CatalogAdHocResponse.from(service.submitForPromotion(userId, id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/api/adhoc-catalog")
    public List<CatalogAdHocResponse> browse() {
        return service.listPublished().stream().map(CatalogAdHocResponse::from).toList();
    }

    @GetMapping("/api/adhoc-catalog/{id}")
    public CatalogAdHocResponse one(@PathVariable String id) {
        CatalogAdHocWorkout c = service.findPublished(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return CatalogAdHocResponse.from(c);
    }

    @PostMapping("/api/adhoc-catalog/{id}/adopt")
    @ResponseStatus(HttpStatus.CREATED)
    public AdHocWorkoutResponse adopt(@PathVariable String id) {
        String userId = currentUser.get().userId();
        try {
            AdHocWorkout adopted = service.adopt(userId, id);
            return AdHocWorkoutResponse.from(adopted, assembler.day(userId, adopted.day()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
