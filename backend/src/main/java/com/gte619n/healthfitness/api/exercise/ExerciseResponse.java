package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.DemoFrame;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseReference;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import java.time.Instant;
import java.util.List;

public record ExerciseResponse(
    String exerciseId,
    String name,
    List<String> aliases,
    MovementPattern movementPattern,
    List<String> primaryMuscles,
    List<String> secondaryMuscles,
    Laterality laterality,
    Mechanic mechanic,
    String description,
    List<String> formCues,
    List<EquipmentRequirement> requiredEquipment,
    List<BlockType> suitableBlockTypes,
    RepRange defaultRepRange,
    boolean isTimed,
    List<DemoFrame> demoFrames,
    List<FrameSpec> demoPlan,
    ExerciseMediaStatus planStatus,
    ExerciseReference reference,
    String videoUrl,
    String demoPromptOverride,
    ExerciseMediaStatus mediaStatus,
    ExerciseStatus status,
    String contributorId,
    Instant createdAt,
    Instant updatedAt,
    boolean reviewed,
    List<String> groundingImageUrls
) {
    /**
     * User-facing serialization. {@code reference} is admin-only (it is a public
     * exercise-library citation used by the generation pipeline, not user
     * content), so it is omitted here — see {@link #fromAdmin(Exercise)}.
     */
    public static ExerciseResponse from(Exercise e) {
        return build(e, null);
    }

    /**
     * Admin serialization — identical to {@link #from(Exercise)} but includes
     * the {@code reference}. Used by every {@code /api/admin/exercises*} response.
     */
    public static ExerciseResponse fromAdmin(Exercise e) {
        return build(e, e.reference());
    }

    private static ExerciseResponse build(Exercise e, ExerciseReference reference) {
        return new ExerciseResponse(
            e.exerciseId(),
            e.name(),
            e.aliases(),
            e.movementPattern(),
            e.primaryMuscles(),
            e.secondaryMuscles(),
            e.laterality(),
            e.mechanic(),
            e.description(),
            e.formCues(),
            e.requiredEquipment(),
            e.suitableBlockTypes(),
            e.defaultRepRange(),
            e.isTimed(),
            e.demoFrames(),
            e.demoPlan(),
            e.planStatus(),
            reference,
            e.videoUrl(),
            e.demoPromptOverride(),
            e.mediaStatus(),
            e.status(),
            e.contributorId(),
            e.createdAt(),
            e.updatedAt(),
            e.reviewed(),
            e.groundingImageUrls() == null ? List.of() : e.groundingImageUrls()
        );
    }
}
