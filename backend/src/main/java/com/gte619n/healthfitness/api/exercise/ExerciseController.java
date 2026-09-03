package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.exercise.ExerciseSuggestionService;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public (authenticated) read access to the exercise catalog. */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseService service;
    private final ExerciseAvailabilityService availability;
    private final ExerciseSuggestionService suggestions;
    private final CurrentUserProvider currentUser;

    public ExerciseController(
        ExerciseService service,
        ExerciseAvailabilityService availability,
        ExerciseSuggestionService suggestions,
        CurrentUserProvider currentUser
    ) {
        this.service = service;
        this.availability = availability;
        this.suggestions = suggestions;
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

    /**
     * The in-workout swap picker (#4): the movements executable at {@code
     * locationId}, ranked by muscle/movement similarity to {@code similarTo}
     * (the prescribed exercise) so same-muscle alternatives come first, and
     * optionally narrowed by a name/alias {@code search}. Unlike {@link
     * #available} this ranks rather than just filters, and excludes the
     * reference exercise itself.
     */
    @GetMapping("/suggestions")
    public List<ExerciseResponse> suggestions(
        @RequestParam String locationId,
        @RequestParam(required = false) String similarTo,
        @RequestParam(required = false) String search
    ) {
        String userId = currentUser.get().userId();
        return suggestions.rankedFor(userId, locationId, similarTo, search).stream()
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

    /**
     * Flag a demo frame as bad (#9). Owner-only: this is the app's owner tooling
     * for pulling anatomically-wrong demo media out of the served pool and back
     * into review — not something ordinary users should reach, so it is gated to
     * {@link #OWNER_EMAIL} (a 403 otherwise), in addition to the client hiding it.
     */
    @PostMapping("/{exerciseId}/flag-frame")
    public ResponseEntity<Void> flagFrame(
        @PathVariable String exerciseId,
        @RequestBody FlagFrameRequest body
    ) {
        CurrentUser user = currentUser.get();
        boolean owner = user.email() != null
            && OWNER_EMAILS.stream().anyMatch(user.email()::equalsIgnoreCase);
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted.");
        }
        service.flagFrame(exerciseId, body.frameKey(), body.note());
        return ResponseEntity.noContent().build();
    }

    /** The app owner accounts — the only ones allowed to flag demo media (#9). */
    private static final List<String> OWNER_EMAILS =
        List.of("evan.ruff@gmail.com", "evan.ruff@oxos.com");
}
