package com.gte619n.healthfitness.core.exercise;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Catalog lifecycle + demo-frame bookkeeping for the global exercise library
 * (IMPL-14). Mirrors {@code EquipmentService}: pure business logic over
 * {@link ExerciseRepository}, no Spring Web. Storage of media bytes lives in
 * {@code integrations}; this class only manages the metadata.
 */
@Service
public class ExerciseService {

    private final ExerciseRepository exercises;

    public ExerciseService(ExerciseRepository exercises) {
        this.exercises = exercises;
    }

    public List<Exercise> listPublished(String search, MovementPattern pattern, BlockType block, String muscle) {
        return exercises.findPublished(search, pattern, block, muscle);
    }

    public List<Exercise> listCatalog() {
        return exercises.findAll();
    }

    public List<Exercise> findReviewQueue() {
        return exercises.findByMediaStatus(ExerciseMediaStatus.NEEDS_REVIEW);
    }

    public Optional<Exercise> findById(String exerciseId) {
        return exercises.findById(exerciseId);
    }

    public List<Exercise> findByIds(List<String> ids) {
        return exercises.findByIds(ids);
    }

    /** Create a new exercise in DRAFT with no media. */
    public Exercise create(ExerciseEdit edit, String contributorId) {
        if (edit == null || edit.name() == null || edit.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String exerciseId = "ex_" + UUID.randomUUID().toString().substring(0, 12);
        Instant now = Instant.now();
        Exercise exercise = new Exercise(
            exerciseId,
            edit.name(),
            edit.name().toLowerCase(),
            orEmpty(edit.aliases()),
            edit.movementPattern() != null ? edit.movementPattern() : MovementPattern.OTHER,
            orEmpty(edit.primaryMuscles()),
            orEmpty(edit.secondaryMuscles()),
            edit.laterality() != null ? edit.laterality() : Laterality.BILATERAL,
            edit.mechanic() != null ? edit.mechanic() : Mechanic.COMPOUND,
            edit.description(),
            orEmpty(edit.formCues()),
            orEmpty(edit.requiredEquipment()),
            orEmpty(edit.suitableBlockTypes()),
            edit.defaultRepRange(),
            edit.isTimed() != null && edit.isTimed(),
            List.of(),
            null,
            edit.demoPromptOverride(),
            ExerciseMediaStatus.NONE,
            ExerciseStatus.DRAFT,
            contributorId,
            now,
            now,
            null
        );
        exercises.save(exercise);
        return exercise;
    }

    /** Patch editable metadata; null fields are left unchanged. */
    public Exercise update(String exerciseId, ExerciseEdit edit) {
        Exercise e = require(exerciseId);
        String name = edit.name() != null ? edit.name() : e.name();
        Exercise updated = new Exercise(
            e.exerciseId(),
            name,
            name.toLowerCase(),
            edit.aliases() != null ? edit.aliases() : e.aliases(),
            edit.movementPattern() != null ? edit.movementPattern() : e.movementPattern(),
            edit.primaryMuscles() != null ? edit.primaryMuscles() : e.primaryMuscles(),
            edit.secondaryMuscles() != null ? edit.secondaryMuscles() : e.secondaryMuscles(),
            edit.laterality() != null ? edit.laterality() : e.laterality(),
            edit.mechanic() != null ? edit.mechanic() : e.mechanic(),
            edit.description() != null ? edit.description() : e.description(),
            edit.formCues() != null ? edit.formCues() : e.formCues(),
            edit.requiredEquipment() != null ? edit.requiredEquipment() : e.requiredEquipment(),
            edit.suitableBlockTypes() != null ? edit.suitableBlockTypes() : e.suitableBlockTypes(),
            edit.defaultRepRange() != null ? edit.defaultRepRange() : e.defaultRepRange(),
            edit.isTimed() != null ? edit.isTimed() : e.isTimed(),
            e.demoFrames(),
            e.videoUrl(),
            edit.demoPromptOverride() != null ? edit.demoPromptOverride() : e.demoPromptOverride(),
            e.mediaStatus(),
            e.status(),
            e.contributorId(),
            e.createdAt(),
            Instant.now(),
            e.aliasOfExerciseId()
        );
        exercises.save(updated);
        return updated;
    }

    public Exercise publish(String exerciseId) {
        return withStatus(require(exerciseId), ExerciseStatus.PUBLISHED);
    }

    public Exercise archive(String exerciseId) {
        return withStatus(require(exerciseId), ExerciseStatus.ARCHIVED);
    }

    public Exercise approveMedia(String exerciseId) {
        Exercise e = require(exerciseId);
        if (e.mediaStatus() != ExerciseMediaStatus.NEEDS_REVIEW) {
            throw new IllegalArgumentException("Can only approve media that is awaiting review");
        }
        return withMediaStatus(e, ExerciseMediaStatus.APPROVED);
    }

    /** Set the media status (used by the generator: PENDING before, FAILED on error). */
    public Exercise updateMediaStatus(String exerciseId, ExerciseMediaStatus status) {
        return withMediaStatus(require(exerciseId), status);
    }

    /**
     * Append a freshly generated/uploaded url to a phase's candidates and make
     * it the active frame. Other phases untouched. Does not change
     * {@code mediaStatus} — the caller sets NEEDS_REVIEW once all phases land.
     */
    public Exercise recordFrame(String exerciseId, DemoPhase phase, String url) {
        Exercise e = require(exerciseId);
        List<DemoFrame> frames = upsertFrame(e.demoFrames(), phase, url, /*makeActive=*/true);
        return withFrames(e, frames);
    }

    /** Select an existing candidate as the active frame for a phase. */
    public Exercise selectFrame(String exerciseId, DemoPhase phase, String imageUrl) {
        Exercise e = require(exerciseId);
        DemoFrame existing = frameFor(e.demoFrames(), phase);
        if (existing == null || existing.imageCandidates() == null
            || !existing.imageCandidates().contains(imageUrl)) {
            throw new IllegalArgumentException("Image is not a candidate for this phase");
        }
        List<DemoFrame> frames = replaceFrame(e.demoFrames(),
            new DemoFrame(phase, imageUrl, existing.imageCandidates()));
        return withFrames(e, frames);
    }

    /** Remove a candidate from a phase; if it was active, fall back to first remaining. */
    public Exercise removeFrameCandidate(String exerciseId, DemoPhase phase, String imageUrl) {
        Exercise e = require(exerciseId);
        DemoFrame existing = frameFor(e.demoFrames(), phase);
        if (existing == null || existing.imageCandidates() == null
            || !existing.imageCandidates().contains(imageUrl)) {
            throw new IllegalArgumentException("Image is not a candidate for this phase");
        }
        List<String> remaining = new ArrayList<>(existing.imageCandidates());
        remaining.remove(imageUrl);
        String active = imageUrl.equals(existing.imageUrl())
            ? (remaining.isEmpty() ? null : remaining.get(0))
            : existing.imageUrl();
        List<DemoFrame> frames = replaceFrame(e.demoFrames(), new DemoFrame(phase, active, remaining));
        return withFrames(e, frames);
    }

    /**
     * Merge {@code sourceId} into {@code targetId}: mark the source an alias of
     * the target and archive it. Unlike Equipment there are no per-user
     * references to rewrite (programs reference exercise ids directly and
     * resolve through the alias pointer). Returns the target.
     */
    public Exercise mergeInto(String sourceId, String targetId) {
        if (sourceId == null || targetId == null) {
            throw new IllegalArgumentException("source and target exercise ids are required");
        }
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("source and target must differ");
        }
        Exercise source = require(sourceId);
        Exercise target = require(targetId);
        if (target.aliasOfExerciseId() != null) {
            throw new IllegalArgumentException("Target is itself an alias — cannot merge into an alias");
        }
        Exercise merged = new Exercise(
            source.exerciseId(), source.name(), source.nameLower(), source.aliases(),
            source.movementPattern(), source.primaryMuscles(), source.secondaryMuscles(),
            source.laterality(), source.mechanic(), source.description(), source.formCues(),
            source.requiredEquipment(), source.suitableBlockTypes(), source.defaultRepRange(),
            source.isTimed(), source.demoFrames(), source.videoUrl(), source.demoPromptOverride(),
            source.mediaStatus(), ExerciseStatus.ARCHIVED, source.contributorId(),
            source.createdAt(), Instant.now(), targetId
        );
        exercises.save(merged);
        return exercises.findById(targetId).orElse(target);
    }

    // ---- helpers ----

    private Exercise require(String exerciseId) {
        return exercises.findById(exerciseId)
            .orElseThrow(() -> new IllegalArgumentException("Exercise not found: " + exerciseId));
    }

    private Exercise withStatus(Exercise e, ExerciseStatus status) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), e.demoFrames(), e.videoUrl(),
            e.demoPromptOverride(), e.mediaStatus(), status, e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId()
        );
        exercises.save(updated);
        return updated;
    }

    private Exercise withMediaStatus(Exercise e, ExerciseMediaStatus mediaStatus) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), e.demoFrames(), e.videoUrl(),
            e.demoPromptOverride(), mediaStatus, e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId()
        );
        exercises.save(updated);
        return updated;
    }

    private Exercise withFrames(Exercise e, List<DemoFrame> frames) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), frames, e.videoUrl(),
            e.demoPromptOverride(), e.mediaStatus(), e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId()
        );
        exercises.save(updated);
        return updated;
    }

    private static DemoFrame frameFor(List<DemoFrame> frames, DemoPhase phase) {
        if (frames == null) return null;
        return frames.stream().filter(f -> f.phase() == phase).findFirst().orElse(null);
    }

    private static List<DemoFrame> upsertFrame(List<DemoFrame> frames, DemoPhase phase, String url, boolean makeActive) {
        DemoFrame existing = frameFor(frames, phase);
        LinkedHashSet<String> candidates = new LinkedHashSet<>(
            existing == null || existing.imageCandidates() == null ? List.of() : existing.imageCandidates());
        candidates.add(url);
        String active = makeActive ? url : (existing == null ? url : existing.imageUrl());
        return replaceFrame(frames, new DemoFrame(phase, active, new ArrayList<>(candidates)));
    }

    private static List<DemoFrame> replaceFrame(List<DemoFrame> frames, DemoFrame frame) {
        List<DemoFrame> result = new ArrayList<>();
        boolean replaced = false;
        if (frames != null) {
            for (DemoFrame f : frames) {
                if (f.phase() == frame.phase()) {
                    result.add(frame);
                    replaced = true;
                } else {
                    result.add(f);
                }
            }
        }
        if (!replaced) result.add(frame);
        return result;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
