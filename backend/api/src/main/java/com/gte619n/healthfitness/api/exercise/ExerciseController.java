package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public (authenticated) read access to the exercise catalog. */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseService service;
    private final ExerciseAvailabilityService availability;
    private final CurrentUserProvider currentUser;

    public ExerciseController(
        ExerciseService service,
        ExerciseAvailabilityService availability,
        CurrentUserProvider currentUser
    ) {
        this.service = service;
        this.availability = availability;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ExerciseResponse> list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) MovementPattern pattern,
        @RequestParam(required = false) BlockType block,
        @RequestParam(required = false) String muscle
    ) {
        return service.listPublished(search, pattern, block, muscle).stream()
            .map(ExerciseResponse::from)
            .toList();
    }

    @GetMapping("/available")
    public List<ExerciseResponse> available(@RequestParam String locationId) {
        String userId = currentUser.get().userId();
        return availability.executableAt(userId, locationId).stream()
            .map(ExerciseResponse::from)
            .toList();
    }

    @GetMapping("/{exerciseId}")
    public ResponseEntity<ExerciseResponse> getById(@PathVariable String exerciseId) {
        return service.findById(exerciseId)
            .map(ExerciseResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
