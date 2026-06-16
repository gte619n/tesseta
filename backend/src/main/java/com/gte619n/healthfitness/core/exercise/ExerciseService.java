package com.gte619n.healthfitness.core.exercise;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean requireApprovedMedia;

    public ExerciseService(
        ExerciseRepository exercises,
        @Value("${app.exercises.require-approved-media:true}") boolean requireApprovedMedia
    ) {
        this.exercises = exercises;
        this.requireApprovedMedia = requireApprovedMedia;
    }

    /**
     * Published exercises for users/programming. When
     * {@code app.exercises.require-approved-media} is true (default), only
     * exercises whose demo media is APPROVED are returned.
     */
    public List<Exercise> listPublished(String search, MovementPattern pattern, BlockType block, String muscle) {
        return exercises.findPublished(search, pattern, block, muscle).stream()
            .filter(e -> !requireApprovedMedia || e.mediaStatus() == ExerciseMediaStatus.APPROVED)
            .toList();
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
            null,
            ExerciseMediaStatus.NONE,
            null,
            ExerciseStatus.DRAFT,
            contributorId,
            now,
            now,
            null,
            false,
            List.of()
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
            e.demoPlan(),
            e.planStatus(),
            e.reference(),
            e.status(),
            e.contributorId(),
            e.createdAt(),
            Instant.now(),
            e.aliasOfExerciseId(),
            e.reviewed(),
            e.groundingImageUrls()
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

    // ---- IMPL-20: reviewed sign-off + grounding image set ----

    /**
     * Set the human {@code reviewed} sign-off. Independent of
     * {@code mediaStatus}/{@code planStatus}.
     */
    public Exercise setReviewed(String exerciseId, boolean reviewed) {
        return withReviewed(require(exerciseId), reviewed);
    }

    /**
     * Persist the grounding image set — the URLs (own GCS candidates and/or
     * external reference URLs) used as regeneration pose references. A null list
     * is normalized to empty (explicitly "no grounding").
     */
    public Exercise setGroundingImageUrls(String exerciseId, List<String> imageUrls) {
        return withGroundingImageUrls(require(exerciseId),
            imageUrls == null ? List.of() : List.copyOf(imageUrls));
    }

    // ---- frame plan CRUD (IMPL-19) ----

    /** Current frame plan (the reviewable {@code demoPlan}); empty if none. */
    public List<FrameSpec> getPlan(String exerciseId) {
        List<FrameSpec> plan = require(exerciseId).demoPlan();
        return plan == null ? List.of() : plan;
    }

    /**
     * Replace the frame plan and mark it {@code NEEDS_REVIEW}. Used by the
     * planner (after a generation run) and by admin plan edits.
     */
    public Exercise savePlan(String exerciseId, List<FrameSpec> frames) {
        Exercise e = require(exerciseId);
        return withPlan(e, frames == null ? List.of() : List.copyOf(frames), ExerciseMediaStatus.NEEDS_REVIEW);
    }

    /** Approve the frame plan: {@code NEEDS_REVIEW} → {@code APPROVED}. */
    public Exercise approvePlan(String exerciseId) {
        Exercise e = require(exerciseId);
        if (e.planStatus() != ExerciseMediaStatus.NEEDS_REVIEW) {
            throw new IllegalStateException("Can only approve a plan that is awaiting review");
        }
        return withPlan(e, e.demoPlan(), ExerciseMediaStatus.APPROVED);
    }

    /** Set the plan status (used by the planner: PENDING before, FAILED on error). */
    public Exercise updatePlanStatus(String exerciseId, ExerciseMediaStatus status) {
        Exercise e = require(exerciseId);
        return withPlan(e, e.demoPlan(), status);
    }

    // ---- frame media bookkeeping ----

    /**
     * Append a freshly generated/uploaded url to a phase's candidates and make
     * it the active frame. Other phases untouched. Does not change
     * {@code mediaStatus} — the caller sets NEEDS_REVIEW once all phases land.
     *
     * <p>Legacy phase-based entry point (kept for the unchanged media service):
     * derives the plan {@code key} from the phase.
     */
    public Exercise recordFrame(String exerciseId, DemoPhase phase, String url) {
        return recordFrame(exerciseId, DemoFrame.keyForPhase(phase),
            "", "", phaseOrder(phase), url);
    }

    /**
     * Key-based frame record (IMPL-19): append {@code url} to the frame's
     * candidates and make it active, carrying the spec's {@code label}/
     * {@code caption}/{@code order}.
     */
    public Exercise recordFrame(String exerciseId, String key, String label, String caption, int order, String url) {
        Exercise e = require(exerciseId);
        List<DemoFrame> frames = upsertFrame(e.demoFrames(), key, label, caption, order, url);
        return withFrames(e, frames);
    }

    /** Select an existing candidate as the active frame for a phase (legacy). */
    public Exercise selectFrame(String exerciseId, DemoPhase phase, String imageUrl) {
        return selectFrame(exerciseId, DemoFrame.keyForPhase(phase), imageUrl);
    }

    /** Select an existing candidate as the active frame for a plan key. */
    public Exercise selectFrame(String exerciseId, String key, String imageUrl) {
        Exercise e = require(exerciseId);
        DemoFrame existing = frameFor(e.demoFrames(), key);
        if (existing == null || existing.imageCandidates() == null
            || !existing.imageCandidates().contains(imageUrl)) {
            throw new IllegalArgumentException("Image is not a candidate for this frame");
        }
        List<DemoFrame> frames = replaceFrame(e.demoFrames(),
            new DemoFrame(existing.key(), existing.label(), existing.caption(), existing.order(),
                imageUrl, existing.imageCandidates(), existing.phase()));
        return withFrames(e, frames);
    }

    /** Remove a candidate from a phase; if it was active, fall back (legacy). */
    public Exercise removeFrameCandidate(String exerciseId, DemoPhase phase, String imageUrl) {
        return removeFrameCandidate(exerciseId, DemoFrame.keyForPhase(phase), imageUrl);
    }

    /** Remove a candidate from a plan key; if it was active, fall back to first remaining. */
    public Exercise removeFrameCandidate(String exerciseId, String key, String imageUrl) {
        Exercise e = require(exerciseId);
        DemoFrame existing = frameFor(e.demoFrames(), key);
        if (existing == null || existing.imageCandidates() == null
            || !existing.imageCandidates().contains(imageUrl)) {
            throw new IllegalArgumentException("Image is not a candidate for this frame");
        }
        List<String> remaining = new ArrayList<>(existing.imageCandidates());
        remaining.remove(imageUrl);
        String active = imageUrl.equals(existing.imageUrl())
            ? (remaining.isEmpty() ? null : remaining.get(0))
            : existing.imageUrl();
        List<DemoFrame> frames = replaceFrame(e.demoFrames(),
            new DemoFrame(existing.key(), existing.label(), existing.caption(), existing.order(),
                active, remaining, existing.phase()));
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
            source.mediaStatus(), source.demoPlan(), source.planStatus(), source.reference(),
            ExerciseStatus.ARCHIVED, source.contributorId(),
            source.createdAt(), Instant.now(), targetId,
            source.reviewed(), source.groundingImageUrls()
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
            e.demoPromptOverride(), e.mediaStatus(), e.demoPlan(), e.planStatus(), e.reference(),
            status, e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            e.reviewed(), e.groundingImageUrls()
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
            e.demoPromptOverride(), mediaStatus, e.demoPlan(), e.planStatus(), e.reference(),
            e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            e.reviewed(), e.groundingImageUrls()
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
            e.demoPromptOverride(), e.mediaStatus(), e.demoPlan(), e.planStatus(), e.reference(),
            e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            e.reviewed(), e.groundingImageUrls()
        );
        exercises.save(updated);
        return updated;
    }

    private Exercise withPlan(Exercise e, List<FrameSpec> plan, ExerciseMediaStatus planStatus) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), e.demoFrames(), e.videoUrl(),
            e.demoPromptOverride(), e.mediaStatus(), plan, planStatus, e.reference(),
            e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            e.reviewed(), e.groundingImageUrls()
        );
        exercises.save(updated);
        return updated;
    }

    private Exercise withReviewed(Exercise e, boolean reviewed) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), e.demoFrames(), e.videoUrl(),
            e.demoPromptOverride(), e.mediaStatus(), e.demoPlan(), e.planStatus(), e.reference(),
            e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            reviewed, e.groundingImageUrls()
        );
        exercises.save(updated);
        return updated;
    }

    private Exercise withGroundingImageUrls(Exercise e, List<String> imageUrls) {
        Exercise updated = new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(), e.movementPattern(),
            e.primaryMuscles(), e.secondaryMuscles(), e.laterality(), e.mechanic(),
            e.description(), e.formCues(), e.requiredEquipment(), e.suitableBlockTypes(),
            e.defaultRepRange(), e.isTimed(), e.demoFrames(), e.videoUrl(),
            e.demoPromptOverride(), e.mediaStatus(), e.demoPlan(), e.planStatus(), e.reference(),
            e.status(), e.contributorId(),
            e.createdAt(), Instant.now(), e.aliasOfExerciseId(),
            e.reviewed(), imageUrls
        );
        exercises.save(updated);
        return updated;
    }

    private static int phaseOrder(DemoPhase phase) {
        if (phase == null) return 0;
        return switch (phase) {
            case START -> 0;
            case MID -> 1;
            case END -> 2;
        };
    }

    private static DemoFrame frameFor(List<DemoFrame> frames, String key) {
        if (frames == null || key == null) return null;
        return frames.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
    }

    private static List<DemoFrame> upsertFrame(
        List<DemoFrame> frames, String key, String label, String caption, int order, String url) {
        DemoFrame existing = frameFor(frames, key);
        LinkedHashSet<String> candidates = new LinkedHashSet<>(
            existing == null || existing.imageCandidates() == null ? List.of() : existing.imageCandidates());
        candidates.add(url);
        // Preserve any denormalized label/caption/order already on the frame.
        String resolvedLabel = existing != null && existing.label() != null ? existing.label() : label;
        String resolvedCaption = existing != null && existing.caption() != null ? existing.caption() : caption;
        int resolvedOrder = existing != null ? existing.order() : order;
        DemoPhase phase = existing != null ? existing.phase() : null;
        return replaceFrame(frames, new DemoFrame(
            key, resolvedLabel, resolvedCaption, resolvedOrder, url, new ArrayList<>(candidates), phase));
    }

    private static List<DemoFrame> replaceFrame(List<DemoFrame> frames, DemoFrame frame) {
        List<DemoFrame> result = new ArrayList<>();
        boolean replaced = false;
        if (frames != null) {
            for (DemoFrame f : frames) {
                if (f.key() != null && f.key().equals(frame.key())) {
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
