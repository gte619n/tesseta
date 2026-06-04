package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.exercise.CreateExerciseRequest;
import com.gte619n.healthfitness.api.exercise.ExerciseResponse;
import com.gte619n.healthfitness.api.exercise.FrameRequest;
import com.gte619n.healthfitness.api.exercise.RegenerateMediaRequest;
import com.gte619n.healthfitness.api.exercise.UpdateExerciseRequest;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.DemoPhase;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaGenerator;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseCatalogSeeder;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaUploader;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AdminExerciseController(
        ExerciseService service,
        ExerciseCatalogSeeder seeder,
        CurrentUserProvider currentUser,
        Optional<ExerciseMediaGenerator> mediaGenerator,
        Optional<ExerciseMediaUploader> mediaUploader
    ) {
        this.service = service;
        this.seeder = seeder;
        this.currentUser = currentUser;
        this.mediaGenerator = mediaGenerator;
        this.mediaUploader = mediaUploader;
    }

    /** Seed the catalog from the built-in starter set (idempotent). */
    @PostMapping("/seed")
    public ExerciseCatalogSeeder.SeedResult seed() {
        return seeder.seed();
    }

    @GetMapping("/catalog")
    public List<ExerciseResponse> catalog() {
        return service.listCatalog().stream().map(ExerciseResponse::from).toList();
    }

    @GetMapping("/review")
    public List<ExerciseResponse> review() {
        return service.findReviewQueue().stream().map(ExerciseResponse::from).toList();
    }

    @PostMapping
    public ExerciseResponse create(@RequestBody CreateExerciseRequest body) {
        String contributorId = currentUser.get().userId();
        return ExerciseResponse.from(service.create(body.toEdit(), contributorId));
    }

    @PatchMapping("/{exerciseId}")
    public ExerciseResponse update(@PathVariable String exerciseId, @RequestBody UpdateExerciseRequest body) {
        return ExerciseResponse.from(service.update(exerciseId, body.toEdit()));
    }

    @PostMapping("/{exerciseId}/publish")
    public ExerciseResponse publish(@PathVariable String exerciseId) {
        return ExerciseResponse.from(service.publish(exerciseId));
    }

    @PostMapping("/{exerciseId}/archive")
    public ExerciseResponse archive(@PathVariable String exerciseId) {
        return ExerciseResponse.from(service.archive(exerciseId));
    }

    @PostMapping("/{exerciseId}/approve-media")
    public ExerciseResponse approveMedia(@PathVariable String exerciseId) {
        return ExerciseResponse.from(service.approveMedia(exerciseId));
    }

    @GetMapping("/{exerciseId}/demo-prompt")
    public ImagePromptResponse demoPrompt(
        @PathVariable String exerciseId,
        @RequestParam(defaultValue = "START") DemoPhase phase
    ) {
        Exercise exercise = require(exerciseId);
        String prompt = mediaGenerator.map(g -> g.defaultPrompt(exercise, phase)).orElse("");
        return new ImagePromptResponse(prompt);
    }

    @PostMapping("/{exerciseId}/regenerate-media")
    public void regenerateMedia(
        @PathVariable String exerciseId,
        @RequestBody(required = false) RegenerateMediaRequest body
    ) {
        Exercise exercise = require(exerciseId);
        service.updateMediaStatus(exerciseId, ExerciseMediaStatus.PENDING);
        String override = body == null ? null : body.promptOverride();
        DemoPhase phase = body == null ? null : body.phase();
        mediaGenerator.ifPresent(g -> {
            if (phase == null) {
                g.generateDemoAsync(exercise, override);
            } else {
                g.generatePhaseAsync(exercise, phase, override);
            }
        });
    }

    @PostMapping(value = "/{exerciseId}/upload-frame", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExerciseResponse uploadFrame(
        @PathVariable String exerciseId,
        @RequestParam("phase") DemoPhase phase,
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
        return ExerciseResponse.from(mediaUploader.get().uploadFrame(exerciseId, phase, bytes, contentType));
    }

    @PostMapping("/{exerciseId}/select-frame")
    public ExerciseResponse selectFrame(@PathVariable String exerciseId, @RequestBody FrameRequest body) {
        return ExerciseResponse.from(service.selectFrame(exerciseId, body.phase(), body.imageUrl()));
    }

    @PostMapping("/{exerciseId}/delete-frame")
    public ExerciseResponse deleteFrame(@PathVariable String exerciseId, @RequestBody FrameRequest body) {
        if (mediaUploader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media upload is not configured");
        }
        return ExerciseResponse.from(mediaUploader.get().deleteFrame(exerciseId, body.phase(), body.imageUrl()));
    }

    @PostMapping("/{sourceId}/merge-into/{targetId}")
    public ExerciseResponse merge(@PathVariable String sourceId, @PathVariable String targetId) {
        return ExerciseResponse.from(service.mergeInto(sourceId, targetId));
    }

    private Exercise require(String exerciseId) {
        return service.findById(exerciseId)
            .orElseThrow(() -> new IllegalArgumentException("Exercise not found: " + exerciseId));
    }
}
