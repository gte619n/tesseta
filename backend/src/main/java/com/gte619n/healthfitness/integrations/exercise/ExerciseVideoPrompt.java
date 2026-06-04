package com.gte619n.healthfitness.integrations.exercise;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.List;

/**
 * Builds catalog-driven Veo prompts for an exercise demo video (IMPL-15,
 * ADR-0009). Mirrors how {@code GeminiExerciseMediaService.buildPrompt} composes
 * the still prompts from a shared treatment, so the clips match the still
 * library's look (same actor, wardrobe, seamless studio, editorial mood).
 *
 * <p>A full demo is **two ~8s clips from different camera angles**, both
 * image-to-video seeded from the same START still so the actor and equipment are
 * identical, then concatenated to ~15s:
 * <ul>
 *   <li>{@link View#SIDE} — a locked-off true side profile (matches the seed);</li>
 *   <li>{@link View#FRONT} — the camera orbits from that side profile to a front
 *       three-quarter angle, giving a second view of the same movement.</li>
 * </ul>
 *
 * <p>Per the request, every prompt insists on <b>modern commercial-gym
 * equipment</b> and <b>real, visibly loaded working weight</b> (no empty bars or
 * mimed lifts).
 */
public final class ExerciseVideoPrompt {

    /** The two camera angles that make up one demo. */
    public enum View { SIDE, FRONT }

    private static final String TREATMENT = """
        Photorealistic fitness instruction video. Warm neutral seamless \
        background (oatmeal, around hex F0EBE0), soft diffuse daylight from a \
        single large source, gentle realistic shadows, no hard flash, no \
        specular hotspots. Muted, slightly desaturated natural color, matte \
        finish, quiet editorial instrument-like mood — a precision-tool \
        catalog, not a supplement ad. Full-frame camera look, 50-85mm \
        equivalent lens, moderate depth of field. Vertical 9:16 framing with \
        the full body and the full equipment in frame at all times, nothing \
        cropped.""";


    // Shared equipment treatment (single source of truth in GeminiExerciseMediaService),
    // plus a video-specific note that the tempo reflects the real load.
    private static final String EQUIPMENT = GeminiExerciseMediaService.EQUIPMENT_TREATMENT
        + " The movement tempo reflects that real resistance.";

    private ExerciseVideoPrompt() {}

    /** Compose the prompt for one camera angle of the given exercise. */
    public static String build(Exercise exercise, View view) {
        String name = exercise == null || exercise.name() == null ? "the exercise" : exercise.name();
        List<String> cues = exercise == null ? null : exercise.formCues();
        String cueClause = (cues == null || cues.isEmpty())
            ? "" : " Key form: " + String.join("; ", cues) + ".";

        boolean lying = isLying(exercise);
        String rom = romHint(exercise == null ? null : exercise.movementPattern());

        String actor = "The same single athlete throughout, matching the reference image: "
            + ExerciseActor.forExercise(exercise == null ? null : exercise.exerciseId()).describe()
            + ", calm neutral expression. Plain fitted heather-gray athletic clothing, no logos, "
            + "training shorts and flat training shoes. Keep the person's face, body, and clothing "
            + "identical for the entire clip.";

        String motion = "The athlete performs two or three slow, controlled repetitions of "
            + name + " at a realistic gym tempo. "
            + "CRITICAL: every repetition must travel the COMPLETE, full range of motion — "
            + rom + " Do not perform shallow, partial, or half reps; the full depth and the "
            + "full extension must be unmistakable in every rep." + cueClause;

        String camera = switch (view) {
            case SIDE -> lying
                ? "Camera: a locked-off elevated three-quarter angle from the lifting side, "
                    + "raised above the bench looking down at roughly 30 to 45 degrees, static "
                    + "throughout, so the full body, both arms, the bar, and the full bar path "
                    + "from chest to lockout are clearly visible and not foreshortened."
                : "Camera: a locked-off true side-profile view at the lifter's "
                    + "torso height, static throughout, the whole body and all the equipment "
                    + "visible side-on so the full range of motion is clearly readable.";
            case FRONT -> lying
                ? "Camera: begin at the side profile matching the reference image, then crane "
                    + "smoothly up and over to a high front three-quarter angle looking down "
                    + "the lifter's body. The athlete stays lying flat on the bench the entire "
                    + "time and never sits up; show the full press from chest to full lockout "
                    + "from this second angle."
                : "Camera: begin at the side profile matching the reference image, then slowly "
                    + "and smoothly orbit around the standing athlete to a front three-quarter "
                    + "angle — a second viewing angle of the same movement — keeping the full "
                    + "body and equipment in frame and the full range of motion visible.";
        };

        return TREATMENT + "\n\n" + actor + "\n\n" + EQUIPMENT + "\n\n"
            + GeminiExerciseMediaService.ANATOMY_CLAUSE + "\n\n" + motion + "\n\n" + camera;
    }

    /** Lying movements (bench/floor/supine) get a different second angle than the standing orbit. */
    public static boolean isLying(Exercise exercise) {
        String n = exercise == null || exercise.name() == null ? "" : exercise.name().toLowerCase();
        return n.contains("bench press") || n.contains("lying") || n.contains("floor press")
            || n.contains("supine") || n.contains("skullcrusher") || n.contains("skull crusher")
            || n.contains("chest fly") || n.contains("hip thrust") || n.contains("glute bridge");
    }

    /** Movement-specific bottom→top description so "full range" is concrete (shared helper). */
    private static String romHint(MovementPattern p) {
        return ExerciseMovementPhases.fullRom(p);
    }
}
