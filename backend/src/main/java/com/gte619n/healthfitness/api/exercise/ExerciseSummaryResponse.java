package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.DemoFrame;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Slim catalog projection for the admin list/grid views (IMPL-20). Carries only
 * what the catalog needs to render and filter/sort 352 exercises client-side —
 * deliberately image-thin (full detail loads via {@code GET /{id}}). The web
 * derives thumbnail URLs from {@code frameImageUrls} by path convention.
 */
public record ExerciseSummaryResponse(
    String exerciseId,
    String name,
    MovementPattern movementPattern,
    List<BlockType> suitableBlockTypes,
    List<String> primaryMuscles,
    ExerciseStatus status,
    ExerciseMediaStatus mediaStatus,
    ExerciseMediaStatus planStatus,
    boolean reviewed,
    int frameCount,
    List<String> frameImageUrls
) {
    /**
     * Project an exercise to its summary. {@code frameCount} is the plan size
     * (or the legacy {@code demoFrames} size when there is no plan);
     * {@code frameImageUrls} is the active {@code imageUrl} per frame in display
     * order and may contain nulls for frames without a selected image.
     */
    public static ExerciseSummaryResponse from(Exercise e) {
        List<FrameSpec> plan = e.demoPlan();
        List<DemoFrame> frames = e.demoFrames() == null ? List.of() : e.demoFrames();
        int frameCount = plan != null && !plan.isEmpty() ? plan.size() : frames.size();

        // Active imageUrl per frame, in display order. When a plan exists we
        // order by the plan (joining frames by key); otherwise order the
        // legacy frames by their own order field. Nulls are preserved.
        List<String> frameImageUrls = new ArrayList<>();
        if (plan != null && !plan.isEmpty()) {
            for (FrameSpec spec : plan.stream().sorted(Comparator.comparingInt(FrameSpec::order)).toList()) {
                DemoFrame f = frames.stream()
                    .filter(df -> df.key() != null && df.key().equals(spec.key()))
                    .findFirst().orElse(null);
                frameImageUrls.add(f == null ? null : f.imageUrl());
            }
        } else {
            for (DemoFrame f : frames.stream().sorted(Comparator.comparingInt(DemoFrame::order)).toList()) {
                frameImageUrls.add(f.imageUrl());
            }
        }

        return new ExerciseSummaryResponse(
            e.exerciseId(),
            e.name(),
            e.movementPattern(),
            e.suitableBlockTypes(),
            e.primaryMuscles(),
            e.status(),
            e.mediaStatus(),
            e.planStatus(),
            e.reviewed(),
            frameCount,
            frameImageUrls
        );
    }
}
