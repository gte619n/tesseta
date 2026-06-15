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
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.util.ArrayList;
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

    /**
     * Instruction appended when a reference pose image is attached: the model
     * must copy the body position only, never the reference's background,
     * lighting, wardrobe, or person. The reference image is generation input
     * only and is never stored or returned.
     */
    private static final String GROUNDING_CLAUSE =
        "Use the provided reference image only as a guide for the body position "
        + "and limb configuration; reproduce that pose, but render entirely in the "
        + "house style described above (do not copy its background, lighting, "
        + "wardrobe, or person).";

    private final ExerciseMediaStorage storage;
    private final ExerciseService exerciseService;
    private final GroundingImageResolver grounding;
    private final String model;
    private final boolean groundingEnabled;
    private final Client client;

    public GeminiExerciseMediaService(
        ExerciseMediaStorage storage,
        ExerciseService exerciseService,
        GroundingImageResolver grounding,
        Client client,
        @Value("${app.exercises.media.model:gemini-3.1-flash-image-preview}") String model,
        @Value("${app.exercises.media.grounding-enabled:true}") boolean groundingEnabled
    ) {
        this.storage = storage;
        this.exerciseService = exerciseService;
        this.grounding = grounding;
        this.model = model;
        this.groundingEnabled = groundingEnabled;
        this.client = client;
    }

    @Override
    public CompletableFuture<Void> generateDemoAsync(Exercise exercise) {
        return generateDemoAsync(exercise, null);
    }

    @Override
    public CompletableFuture<Void> generateDemoAsync(Exercise exercise, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            boolean any;
            List<FrameSpec> plan = exercise.demoPlan();
            if (plan != null && !plan.isEmpty()) {
                // Plan-based: resolve grounding once, generate every spec.
                List<GroundingImageResolver.RefImage> refs = resolveGrounding(exercise);
                any = false;
                for (FrameSpec spec : plan) {
                    any |= generateSpec(exercise, plan, spec, refs, promptOverride);
                }
            } else {
                // Legacy START/MID/END path, unchanged.
                any = false;
                for (DemoPhase phase : DemoPhase.values()) {
                    any |= generatePhase(exercise, phase, promptOverride);
                }
            }
            finalizeStatus(exercise.exerciseId(), any);
        });
    }

    @Override
    public CompletableFuture<Void> generatePhaseAsync(Exercise exercise, DemoPhase phase, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            boolean ok = generatePhase(exercise, phase, promptOverride);
            finalizeStatus(exercise.exerciseId(), ok);
        });
    }

    @Override
    public CompletableFuture<Void> generateFrameAsync(Exercise exercise, String key, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            boolean ok;
            List<FrameSpec> plan = exercise.demoPlan();
            FrameSpec spec = specForKey(plan, key);
            if (spec != null) {
                List<GroundingImageResolver.RefImage> refs = resolveGrounding(exercise);
                ok = generateSpec(exercise, plan, spec, refs, promptOverride);
            } else {
                // No plan match — fall back to a legacy phase if the key is start/mid/end.
                DemoPhase phase = phaseForKey(key);
                ok = phase != null && generatePhase(exercise, phase, promptOverride);
            }
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
    public Exercise uploadFrame(String exerciseId, String key, byte[] bytes, String contentType) {
        String url = storage.upload(exerciseId, key, bytes, contentType);
        // Carry the spec's denormalized label/caption/order when the plan has it.
        FrameSpec spec = specForKey(exerciseService.getPlan(exerciseId), key);
        if (spec != null) {
            exerciseService.recordFrame(exerciseId, key, spec.label(), spec.caption(), spec.order(), url);
        } else {
            exerciseService.recordFrame(exerciseId, key, "", "", 0, url);
        }
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

    @Override
    public Exercise deleteFrame(String exerciseId, String key, String imageUrl) {
        try {
            storage.deleteByUrl(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to delete exercise frame blob for {}: {}", exerciseId, e.getMessage());
        }
        return exerciseService.removeFrameCandidate(exerciseId, key, imageUrl);
    }

    // ---- internals ----

    /** Legacy phase → key reverse lookup for plan-less fallback. */
    private static DemoPhase phaseForKey(String key) {
        if (key == null) {
            return null;
        }
        for (DemoPhase p : DemoPhase.values()) {
            if (key.equalsIgnoreCase(p.name()) || key.equalsIgnoreCase(keyForPhase(p))) {
                return p;
            }
        }
        return null;
    }

    private static String keyForPhase(DemoPhase p) {
        return switch (p) {
            case START -> "start";
            case MID -> "mid";
            case END -> "end";
        };
    }

    private static FrameSpec specForKey(List<FrameSpec> plan, String key) {
        if (plan == null || key == null) {
            return null;
        }
        return plan.stream().filter(s -> key.equals(s.key())).findFirst().orElse(null);
    }

    private List<GroundingImageResolver.RefImage> resolveGrounding(Exercise exercise) {
        if (!groundingEnabled || exercise.reference() == null) {
            return List.of();
        }
        try {
            return grounding.imagesFor(exercise.reference());
        } catch (Exception e) {
            log.warn("Grounding resolution failed for {}: {}", exercise.exerciseId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Map a plan frame to a reference pose image by order: first frame → first
     * (start) image, last frame → last (end) image, interior → nearest, none if
     * no references. Returns null when no reference applies.
     */
    private static GroundingImageResolver.RefImage referenceFor(
        List<FrameSpec> plan, FrameSpec spec, List<GroundingImageResolver.RefImage> refs) {
        if (refs == null || refs.isEmpty() || plan == null || plan.isEmpty()) {
            return null;
        }
        if (refs.size() == 1) {
            return refs.get(0);
        }
        int idx = Math.max(0, plan.indexOf(spec));
        int last = plan.size() - 1;
        if (idx <= 0) {
            return refs.get(0);
        }
        if (idx >= last) {
            return refs.get(refs.size() - 1);
        }
        // Interior frame: map proportionally to the nearest reference.
        int mapped = Math.round((float) idx / last * (refs.size() - 1));
        mapped = Math.min(refs.size() - 1, Math.max(0, mapped));
        return refs.get(mapped);
    }

    /** Generate one plan frame (grounded when a reference applies); true on success. */
    private boolean generateSpec(
        Exercise exercise, List<FrameSpec> plan, FrameSpec spec,
        List<GroundingImageResolver.RefImage> refs, String promptOverride) {
        String key = spec.key();
        try {
            if (client == null) {
                log.warn("Skipping exercise media for {} — no API key", exercise.exerciseId());
                return false;
            }
            GroundingImageResolver.RefImage ref = referenceFor(plan, spec, refs);
            String prompt = buildPrompt(exercise, spec, promptOverride, ref != null);
            byte[] bytes = callGemini(prompt, ref, exercise.exerciseId());
            if (bytes != null && bytes.length > 0) {
                String url = storage.upload(exercise.exerciseId(), key, bytes);
                exerciseService.recordFrame(
                    exercise.exerciseId(), key, spec.label(), spec.caption(), spec.order(), url);
                log.info("Generated frame '{}' for exercise {}{}: {}",
                    key, exercise.exerciseId(), ref != null ? " (grounded)" : "", url);
                return true;
            }
            log.warn("Empty image bytes for exercise {} frame {}", exercise.exerciseId(), key);
            return false;
        } catch (Exception e) {
            log.error("Failed to generate frame {} for exercise {}: {}",
                key, exercise.exerciseId(), e.getMessage(), e);
            return false;
        }
    }

    /** Generate one legacy phase; returns true on success. Swallows errors. */
    private boolean generatePhase(Exercise exercise, DemoPhase phase, String promptOverride) {
        try {
            if (client == null) {
                log.warn("Skipping exercise media for {} — no API key", exercise.exerciseId());
                return false;
            }
            String prompt = buildPrompt(exercise, phase, promptOverride);
            byte[] bytes = callGemini(prompt, null, exercise.exerciseId());
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
        // Movement-pattern-driven position so every exercise (not just bench) gets a
        // concrete, maximal-range phase — the fix for shallow mid-rep stills.
        String position = ExerciseMovementPhases.position(exercise.movementPattern(), phase);
        return composePrompt(exercise, position, false);
    }

    /**
     * Plan-based prompt (IMPL-19): identical house clauses to the legacy path,
     * but the position clause comes from {@link FrameSpec#positionPrompt()}
     * instead of {@link ExerciseMovementPhases}. {@code grounded} appends the
     * reference-image instruction when a pose reference is attached.
     */
    String buildPrompt(Exercise exercise, FrameSpec spec, String promptOverride, boolean grounded) {
        if (promptOverride != null && !promptOverride.isBlank()) {
            return grounded ? promptOverride + "\n" + GROUNDING_CLAUSE : promptOverride;
        }
        String position = spec.positionPrompt() == null || spec.positionPrompt().isBlank()
            ? ExerciseMovementPhases.position(exercise.movementPattern(), DemoPhase.MID)
            : spec.positionPrompt();
        return composePrompt(exercise, position, grounded);
    }

    private String composePrompt(Exercise exercise, String position, boolean grounded) {
        String name = exercise.name() == null ? "an exercise" : exercise.name();
        String cues = exercise.formCues() == null || exercise.formCues().isEmpty()
            ? "" : String.join("; ", exercise.formCues());
        boolean lying = ExerciseVideoPrompt.isLying(exercise);
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
        String base = SHARED_TREATMENT + "\n" + actor + "\n" + WARDROBE_CLAUSE + "\n"
            + ENVIRONMENT_CLAUSE + "\n" + camera + "\n" + EQUIPMENT_TREATMENT + "\n"
            + ANATOMY_CLAUSE + "\n"
            + "The person is performing " + name + ", shown " + position
            + (cues.isBlank() ? "" : ", key form cues: " + cues) + "." + posture;
        return grounded ? base + "\n" + GROUNDING_CLAUSE : base;
    }

    /**
     * Call the image model. When {@code ref} is non-null its bytes are attached
     * as an additional inline image Part to ground the pose (the reference image
     * is input only — never stored or returned).
     */
    byte[] callGemini(String prompt, GroundingImageResolver.RefImage ref, String exerciseId) {
        try {
            Content content;
            if (ref != null && ref.bytes() != null && ref.bytes().length > 0) {
                List<Part> parts = new ArrayList<>();
                // Reference image first, then the text prompt (mirrors GeminiFoodImageGenerator).
                parts.add(Part.fromBytes(ref.bytes(), ref.mime()));
                parts.add(Part.fromText(prompt));
                content = Content.fromParts(parts.toArray(new Part[0]));
            } else {
                content = Content.fromParts(Part.fromText(prompt));
            }
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
