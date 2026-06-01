package com.gte619n.healthfitness.integrations.exercise;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.exercise.DemoPhase;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaGenerator;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaUploader;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Generates exercise demo stills with Gemini's image model
 * ({@code gemini-3.1-flash-image-preview}) and stores admin uploads. Each phase
 * prompt is built verbatim from the "Exercise instruction photography"
 * treatment in docs/photography-prompts.md so the library matches the rest of
 * the app. On success the exercise lands {@code NEEDS_REVIEW} — never APPROVED
 * (the anatomical-review gate). Never throws from the async path.
 */
@Component
@ConditionalOnProperty(name = "app.exercises.media.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiExerciseMediaService implements ExerciseMediaGenerator, ExerciseMediaUploader {

    private static final Logger log = LoggerFactory.getLogger(GeminiExerciseMediaService.class);

    private static final String SHARED_TREATMENT = """
        Warm neutral seamless background, oatmeal color, hex F0EBE0 or a hair
        lighter. Soft diffuse daylight from a single direction, large soft
        source, gentle realistic shadows, no hard flash, no studio specular
        hotspots. Muted natural color, slightly desaturated. Matte finish, no
        glossy advertising sheen. Subject centered with generous negative
        space. Photographic realism, full-frame camera look, 50mm to 85mm
        equivalent lens, moderate depth of field. No text, no graphics, no
        logos, no props beyond what is specified. Quiet, editorial,
        instrument-like mood — a precision-tool catalog, not a supplement ad.
        Vertical 4:5 framing for full body.""";

    private static final String MODEL_CLAUSE =
        "A single athletic person, mid-30s, neutral medium build, short hair, "
        + "calm neutral facial expression with no exertion grimace.";

    private static final String WARDROBE_CLAUSE =
        "Plain fitted athletic clothing in heather gray, no logos, no patterns, "
        + "no neon, fitted tank or t-shirt and training shorts, flat training shoes.";

    private static final String ENVIRONMENT_CLAUSE =
        "Plain warm-neutral studio, oatmeal seamless backdrop, matte rubber "
        + "flooring in a slightly darker warm gray, no gym equipment clutter "
        + "in the background.";

    private static final String CAMERA_CLAUSE =
        "True side-profile view, camera straight-on at the height of the lifter's "
        + "torso, full body and full equipment visible in frame, nothing cropped. "
        + "Even soft daylight, anatomical clarity prioritized over mood.";

    private final ExerciseMediaStorage storage;
    private final ExerciseService exerciseService;
    private final String model;
    private final Client client;

    public GeminiExerciseMediaService(
        ExerciseMediaStorage storage,
        ExerciseService exerciseService,
        @Value("${app.exercises.gemini-api-key:${GEMINI_API_KEY:}}") String apiKey,
        @Value("${app.exercises.media.model:gemini-3.1-flash-image-preview}") String model
    ) {
        this.storage = storage;
        this.exerciseService = exerciseService;
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY not set — exercise media generation will fail until configured");
            this.client = null;
        } else {
            this.client = Client.builder().apiKey(apiKey).build();
        }
    }

    @Override
    public CompletableFuture<Void> generateDemoAsync(Exercise exercise) {
        return generateDemoAsync(exercise, null);
    }

    @Override
    public CompletableFuture<Void> generateDemoAsync(Exercise exercise, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            boolean any = false;
            for (DemoPhase phase : DemoPhase.values()) {
                any |= generateOne(exercise, phase, promptOverride);
            }
            finalizeStatus(exercise.exerciseId(), any);
        });
    }

    @Override
    public CompletableFuture<Void> generatePhaseAsync(Exercise exercise, DemoPhase phase, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            boolean ok = generateOne(exercise, phase, promptOverride);
            finalizeStatus(exercise.exerciseId(), ok);
        });
    }

    @Override
    public String defaultPrompt(Exercise exercise, DemoPhase phase) {
        return buildPrompt(exercise, phase, null);
    }

    @Override
    public Exercise uploadFrame(String exerciseId, DemoPhase phase, byte[] bytes, String contentType) {
        String url = storage.upload(exerciseId, phase, bytes, contentType);
        exerciseService.recordFrame(exerciseId, phase, url);
        return exerciseService.updateMediaStatus(exerciseId, ExerciseMediaStatus.NEEDS_REVIEW);
    }

    @Override
    public Exercise deleteFrame(String exerciseId, DemoPhase phase, String imageUrl) {
        try {
            storage.deleteByUrl(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to delete exercise frame blob for {}: {}", exerciseId, e.getMessage());
        }
        return exerciseService.removeFrameCandidate(exerciseId, phase, imageUrl);
    }

    // ---- internals ----

    /** Generate one phase; returns true on success. Swallows errors. */
    private boolean generateOne(Exercise exercise, DemoPhase phase, String promptOverride) {
        try {
            if (client == null) {
                log.warn("Skipping exercise media for {} — no API key", exercise.exerciseId());
                return false;
            }
            String prompt = buildPrompt(exercise, phase, promptOverride);
            byte[] bytes = callGemini(prompt, exercise.exerciseId());
            if (bytes != null && bytes.length > 0) {
                String url = storage.upload(exercise.exerciseId(), phase, bytes);
                exerciseService.recordFrame(exercise.exerciseId(), phase, url);
                log.info("Generated {} frame for exercise {}: {}", phase, exercise.exerciseId(), url);
                return true;
            }
            log.warn("Empty image bytes for exercise {} phase {}", exercise.exerciseId(), phase);
            return false;
        } catch (Exception e) {
            log.error("Failed to generate {} frame for exercise {}: {}",
                phase, exercise.exerciseId(), e.getMessage(), e);
            return false;
        }
    }

    private void finalizeStatus(String exerciseId, boolean anySuccess) {
        try {
            exerciseService.updateMediaStatus(exerciseId,
                anySuccess ? ExerciseMediaStatus.NEEDS_REVIEW : ExerciseMediaStatus.FAILED);
        } catch (Exception e) {
            log.warn("Failed to set media status for exercise {}: {}", exerciseId, e.getMessage());
        }
    }

    String buildPrompt(Exercise exercise, DemoPhase phase, String promptOverride) {
        if (promptOverride != null && !promptOverride.isBlank()) {
            return promptOverride;
        }
        String name = exercise.name() == null ? "an exercise" : exercise.name();
        String cues = exercise.formCues() == null || exercise.formCues().isEmpty()
            ? "" : String.join("; ", exercise.formCues());
        String phaseClause = switch (phase) {
            case START -> "starting position, the very beginning of the movement";
            case MID -> "mid-rep, the bottom or peak-tension position of the movement";
            case END -> "end position, the lockout or finish of the movement";
        };
        return SHARED_TREATMENT + "\n" + MODEL_CLAUSE + "\n" + WARDROBE_CLAUSE + "\n"
            + ENVIRONMENT_CLAUSE + "\n" + CAMERA_CLAUSE + "\n"
            + "The person is performing: " + name + ", " + phaseClause
            + (cues.isBlank() ? "" : ", key form cues: " + cues) + ".";
    }

    byte[] callGemini(String prompt, String exerciseId) {
        try {
            Content content = Content.fromParts(Part.fromText(prompt));
            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();
            GenerateContentResponse response = client.models.generateContent(model, content, config);
            return extractImageBytes(response, exerciseId);
        } catch (Exception e) {
            log.error("Gemini image call failed for exercise {}: {}", exerciseId, e.getMessage(), e);
            return null;
        }
    }

    private byte[] extractImageBytes(GenerateContentResponse response, String exerciseId) {
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) {
            return null;
        }
        Content content = candidates.get(0).content().orElse(null);
        if (content == null) {
            return null;
        }
        for (Part part : content.parts().orElse(List.of())) {
            var inlineDataOpt = part.inlineData();
            if (inlineDataOpt.isPresent()) {
                var dataOpt = inlineDataOpt.get().data();
                if (dataOpt.isPresent() && dataOpt.get().length > 0) {
                    return dataOpt.get();
                }
            }
        }
        return null;
    }
}
