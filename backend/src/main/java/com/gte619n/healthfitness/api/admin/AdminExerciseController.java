package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.exercise.CreateExerciseRequest;
import com.gte619n.healthfitness.api.exercise.ExerciseResponse;
import com.gte619n.healthfitness.api.exercise.ExerciseSummaryResponse;
import com.gte619n.healthfitness.api.exercise.GroundingRequest;
import com.gte619n.healthfitness.api.exercise.SetReviewedRequest;
import com.gte619n.healthfitness.api.exercise.FrameRequest;
import com.gte619n.healthfitness.api.exercise.PlanResponse;
import com.gte619n.healthfitness.api.exercise.RegenerateMediaRequest;
import com.gte619n.healthfitness.api.exercise.SavePlanRequest;
import com.gte619n.healthfitness.api.exercise.UpdateExerciseRequest;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseFramePlanner;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaGenerator;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseCatalogSeeder;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaUploader;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/exercises")
@AdminOnly
public class AdminExerciseController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final ExerciseService service;
    private final ExerciseCatalogSeeder seeder;
    private final CurrentUserProvider currentUser;
    private final Optional<ExerciseMediaGenerator> mediaGenerator;
    private final Optional<ExerciseMediaUploader> mediaUploader;
    private final Optional<ExerciseFramePlanner> framePlanner;

    public AdminExerciseController(
        ExerciseService service,
        ExerciseCatalogSeeder seeder,
        CurrentUserProvider currentUser,
        Optional<ExerciseMediaGenerator> mediaGenerator,
        Optional<ExerciseMediaUploader> mediaUploader,
        Optional<ExerciseFramePlanner> framePlanner
    ) {
        this.service = service;
        this.seeder = seeder;
        this.currentUser = currentUser;
        this.mediaGenerator = mediaGenerator;
        this.mediaUploader = mediaUploader;
        this.framePlanner = framePlanner;
    }

    /** Seed the catalog from the built-in starter set (idempotent). */
    @PostMapping("/seed")
    public ExerciseCatalogSeeder.SeedResult seed() {
        return seeder.seed();
    }

    /**
     * Full catalog by default; pass {@code ?view=summary} for the slim IMPL-20
     * projection used by the admin list/grid (image-thin, 352-item friendly).
     */
    @GetMapping("/catalog")
    public List<?> catalog(@RequestParam(name = "view", required = false) String view) {
        if ("summary".equalsIgnoreCase(view)) {
            return service.listCatalog().stream().map(ExerciseSummaryResponse::from).toList();
        }
        return service.listCatalog().stream().map(ExerciseResponse::fromAdmin).toList();
    }

    @GetMapping("/review")
    public List<ExerciseResponse> review() {
        return service.findReviewQueue().stream().map(ExerciseResponse::fromAdmin).toList();
    }

    @PostMapping
    public ExerciseResponse create(@RequestBody CreateExerciseRequest body) {
        String contributorId = currentUser.get().userId();
        return ExerciseResponse.fromAdmin(service.create(body.toEdit(), contributorId));
    }

    @PatchMapping("/{exerciseId}")
    public ExerciseResponse update(@PathVariable String exerciseId, @RequestBody UpdateExerciseRequest body) {
        return ExerciseResponse.fromAdmin(service.update(exerciseId, body.toEdit()));
    }

    @PostMapping("/{exerciseId}/publish")
    public ExerciseResponse publish(@PathVariable String exerciseId) {
        return ExerciseResponse.fromAdmin(service.publish(exerciseId));
    }

    @PostMapping("/{exerciseId}/archive")
    public ExerciseResponse archive(@PathVariable String exerciseId) {
        return ExerciseResponse.fromAdmin(service.archive(exerciseId));
    }

    @PostMapping("/{exerciseId}/approve-media")
    public ExerciseResponse approveMedia(@PathVariable String exerciseId) {
        return ExerciseResponse.fromAdmin(service.approveMedia(exerciseId));
    }

    // ---- IMPL-19: frame plan ----

    /** Current frame plan + its review status. */
    @GetMapping("/{exerciseId}/plan")
    public PlanResponse plan(@PathVariable String exerciseId) {
        return PlanResponse.from(require(exerciseId));
    }

    /** Run the planner and save the result as {@code NEEDS_REVIEW}. */
    @PostMapping("/{exerciseId}/regenerate-plan")
    public PlanResponse regeneratePlan(
        @PathVariable String exerciseId,
        @RequestBody(required = false) RegenerateMediaRequest body
    ) {
        Exercise exercise = require(exerciseId);
        if (framePlanner.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Frame planner is not configured");
        }
        service.updatePlanStatus(exerciseId, ExerciseMediaStatus.PENDING);
        String override = body == null ? null : body.promptOverride();
        List<FrameSpec> frames = framePlanner.get().plan(exercise, override);
        return PlanResponse.from(service.savePlan(exerciseId, frames));
    }

    /** Admin edits the plan (add/remove/reorder, edit caption/positionPrompt). */
    @PutMapping("/{exerciseId}/plan")
    public PlanResponse savePlan(@PathVariable String exerciseId, @RequestBody SavePlanRequest body) {
        List<FrameSpec> frames = body == null ? List.of() : body.frames();
        return PlanResponse.from(service.savePlan(exerciseId, frames));
    }

    /** Approve the plan: {@code NEEDS_REVIEW} → {@code APPROVED}. */
    @PostMapping("/{exerciseId}/approve-plan")
    public PlanResponse approvePlan(@PathVariable String exerciseId) {
        return PlanResponse.from(service.approvePlan(exerciseId));
    }

    /**
     * The exact composed image prompt for a single frame {@code key} (IMPL-19),
     * so an admin can preview/edit it before regenerating. The key is a plan
     * {@link FrameSpec#key()}; legacy {@code start}/{@code mid}/{@code end} keys
     * are accepted for plan-less exercises.
     */
    @GetMapping("/{exerciseId}/demo-prompt")
    public ImagePromptResponse demoPrompt(
        @PathVariable String exerciseId,
        @RequestParam(defaultValue = "start") String key
    ) {
        Exercise exercise = require(exerciseId);
        if (mediaGenerator.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media generation is not configured");
        }
        try {
            return new ImagePromptResponse(mediaGenerator.get().promptFor(exercise, key));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{exerciseId}/regenerate-media")
    public void regenerateMedia(
        @PathVariable String exerciseId,
        @RequestBody(required = false) RegenerateMediaRequest body
    ) {
        Exercise exercise = require(exerciseId);
        service.updateMediaStatus(exerciseId, ExerciseMediaStatus.PENDING);
        String override = body == null ? null : body.promptOverride();
        // key == null means "all frames in the plan" (or all legacy phases when
        // the exercise has no plan). A specific key regenerates that one frame.
        String key = body == null ? null : body.key();
        // IMPL-20: optional per-regen grounding override. null ⇒ the generator
        // uses the persisted groundingImageUrls; an empty list ⇒ explicitly none;
        // a non-null list ⇒ exactly those URLs. Selection is persisted separately
        // via PUT /grounding — resolved bytes are never stored.
        List<String> referenceImageUrls = body == null ? null : body.referenceImageUrls();
        mediaGenerator.ifPresent(g -> {
            if (key == null) {
                g.generateDemoAsync(exercise, override, referenceImageUrls);
            } else {
                g.generateFrameAsync(exercise, key, override, referenceImageUrls);
            }
        });
    }

    // ---- IMPL-20: reviewed sign-off + grounding image set ----

    /** Set the human {@code reviewed} sign-off (independent of media/plan status). */
    @PostMapping("/{exerciseId}/reviewed")
    public ExerciseResponse setReviewed(
        @PathVariable String exerciseId,
        @RequestBody SetReviewedRequest body
    ) {
        boolean reviewed = body != null && body.reviewed();
        return ExerciseResponse.fromAdmin(service.setReviewed(exerciseId, reviewed));
    }

    /**
     * Persist the grounding image set — the URLs (own GCS candidates and/or
     * external reference URLs) regeneration uses as pose references.
     */
    @PutMapping("/{exerciseId}/grounding")
    public ExerciseResponse setGrounding(
        @PathVariable String exerciseId,
        @RequestBody GroundingRequest body
    ) {
        List<String> imageUrls = body == null ? List.of() : body.imageUrls();
        return ExerciseResponse.fromAdmin(service.setGroundingImageUrls(exerciseId, imageUrls));
    }

    @PostMapping(value = "/{exerciseId}/upload-frame", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExerciseResponse uploadFrame(
        @PathVariable String exerciseId,
        @RequestParam("key") String key,
        @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds 10 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected image file");
        }
        if (mediaUploader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media upload is not configured");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file", e);
        }
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Frame key is required");
        }
        // Key-based upload (IMPL-19) — works for arbitrary plan keys.
        return ExerciseResponse.fromAdmin(mediaUploader.get().uploadFrame(exerciseId, key, bytes, contentType));
    }

    @PostMapping("/{exerciseId}/select-frame")
    public ExerciseResponse selectFrame(@PathVariable String exerciseId, @RequestBody FrameRequest body) {
        return ExerciseResponse.fromAdmin(service.selectFrame(exerciseId, body.key(), body.imageUrl()));
    }

    @PostMapping("/{exerciseId}/delete-frame")
    public ExerciseResponse deleteFrame(@PathVariable String exerciseId, @RequestBody FrameRequest body) {
        if (mediaUploader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media upload is not configured");
        }
        if (body.key() == null || body.key().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Frame key is required");
        }
        // Key-based delete (IMPL-19) — works for arbitrary plan keys.
        return ExerciseResponse.fromAdmin(mediaUploader.get().deleteFrame(exerciseId, body.key(), body.imageUrl()));
    }

    @PostMapping("/{sourceId}/merge-into/{targetId}")
    public ExerciseResponse merge(@PathVariable String sourceId, @PathVariable String targetId) {
        return ExerciseResponse.fromAdmin(service.mergeInto(sourceId, targetId));
    }

    private Exercise require(String exerciseId) {
        return service.findById(exerciseId)
            .orElseThrow(() -> new IllegalArgumentException("Exercise not found: " + exerciseId));
    }
}
