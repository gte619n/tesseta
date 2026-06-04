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

    /**
     * For supine/lying presses a dead-level side profile foreshortens the bar
     * (it points end-on at the camera). An elevated three-quarter angle reads
     * the bar path and both arms clearly. Posture-aware camera (see buildPrompt).
     */
    private static final String LYING_CAMERA_CLAUSE =
        "Camera at an elevated three-quarter angle from the lifting side, raised "
        + "above the bench and looking down at roughly 30 to 45 degrees, so the "
        + "full body, both arms, the bar, and the full vertical bar path from chest "
        + "to lockout are all clearly visible and not foreshortened. Whole body and "
        + "equipment in frame, nothing cropped. Even soft daylight, anatomical "
        + "clarity prioritized over mood.";

    /**
     * Shared equipment treatment — the single source of truth for how equipment
     * and load look across stills and video ({@link ExerciseVideoPrompt} reuses
     * it). Without this, the image model draws a light dumbbell with no plates
     * even for a "Barbell …" exercise.
     */
    public static final String EQUIPMENT_TREATMENT =
        "Modern, current commercial-gym equipment in clean condition, shown with "
        + "real, visibly loaded working weight appropriate to the exercise — an "
        + "Olympic barbell with properly secured iron or rubber bumper plates, "
        + "appropriately sized dumbbells or kettlebell, or a loaded weight-stack "
        + "machine, exactly as the named exercise requires. Never an empty barbell, "
        + "a mimed lift, or prop weights; the load looks genuinely heavy and the "
        + "equipment matches the exercise name. The plates and equipment are plain "
        + "matte black with absolutely no text, numbers, lettering, labels, or "
        + "branding of any kind on them. Show ONLY the single piece of equipment the "
        + "person is actually using — exactly one barbell, or one pair of dumbbells, "
        + "etc. Absolutely no power rack, squat stands, bench uprights, spotter arms, "
        + "mirrors, or any second, extra, duplicated, or overlapping barbell, bar, or "
        + "weight anywhere in the frame.";

    /**
     * Anatomy guardrails. Generative image models frequently produce extra
     * limbs, fused hands, or reversed grips; this is the anatomical-correctness
     * concern behind the NEEDS_REVIEW gate. Stating it explicitly cuts the
     * artifact rate.
     */
    public static final String ANATOMY_CLAUSE =
        "Anatomically correct human body: exactly one head, two arms, two legs, "
        + "two hands with five fingers each — no extra, missing, duplicated, or "
        + "fused limbs or digits. The hands grip the bar or handle naturally and "
        + "symmetrically with a correct, standard grip (thumbs wrapped), the bar "
        + "held the right way for the movement. Realistic, correct joint angles "
        + "for the position.";

    private final ExerciseMediaStorage storage;
    private final ExerciseService exerciseService;
    private final String model;
    private final Client client;

    public GeminiExerciseMediaService(
        ExerciseMediaStorage storage,
        ExerciseService exerciseService,
        Client client,
        @Value("${app.exercises.media.model:gemini-3.1-flash-image-preview}") String model
    ) {
        this.storage = storage;
        this.exerciseService = exerciseService;
        this.model = model;
        this.client = client;
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
        boolean lying = ExerciseVideoPrompt.isLying(exercise);
        // Movement-pattern-driven position so every exercise (not just bench) gets a
        // concrete, maximal-range phase — the fix for shallow mid-rep stills.
        String position = ExerciseMovementPhases.position(exercise.movementPattern(), phase);
        // Posture-aware camera: dead-level side profile foreshortens a supine press,
        // so lying lifts use an elevated three-quarter angle.
        String camera = lying ? LYING_CAMERA_CLAUSE : CAMERA_CLAUSE;
        // Lying movements (bench/floor/etc.) additionally need a posture anchor so the
        // model doesn't stand the subject up.
        String posture = lying
            ? " The subject stays lying flat on their back on the bench for the entire movement and never stands up."
            : "";
        // Per-exercise actor so all phases (and the video) share one consistent person.
        String actor = "The subject is " + ExerciseActor.forExercise(exercise.exerciseId()).describe()
            + ". Calm neutral facial expression, no exertion grimace.";
        return SHARED_TREATMENT + "\n" + actor + "\n" + WARDROBE_CLAUSE + "\n"
            + ENVIRONMENT_CLAUSE + "\n" + camera + "\n" + EQUIPMENT_TREATMENT + "\n"
            + ANATOMY_CLAUSE + "\n"
            + "The person is performing " + name + ", shown " + position
            + (cues.isBlank() ? "" : ", key form cues: " + cues) + "." + posture;
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
