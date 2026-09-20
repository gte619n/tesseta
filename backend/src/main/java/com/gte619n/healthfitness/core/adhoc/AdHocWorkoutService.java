package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDurationEstimator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CRUD + normalization for ad-hoc workout templates (IMPL-ADHOC-01 Phase 1).
 * The workout body is a single embedded {@link WorkoutDay}; {@link #normalizeDay}
 * fills ids and order indices (mirroring {@code WorkoutProgramService.normalize})
 * and the duration estimate is recomputed on every write so cards stay accurate.
 */
@Service
public class AdHocWorkoutService {

    private final AdHocWorkoutRepository repo;

    public AdHocWorkoutService(AdHocWorkoutRepository repo) {
        this.repo = repo;
    }

    public List<AdHocWorkout> list(String userId) {
        return repo.findByUser(userId);
    }

    public List<AdHocWorkout> listIncludingArchived(String userId) {
        return repo.findByUserIncludingArchived(userId);
    }

    public Optional<AdHocWorkout> findById(String userId, String adhocId) {
        return repo.findById(userId, adhocId);
    }

    /** Create a template from a (possibly partially-filled) input; mints id/dates/estimate. */
    public AdHocWorkout create(AdHocWorkout input) {
        String adhocId = input.adhocId() != null ? input.adhocId() : "aw_" + shortId();
        Instant now = Instant.now();
        WorkoutDay day = normalizeDay(input.day());
        AdHocWorkout workout = new AdHocWorkout(
            input.userId(),
            adhocId,
            input.title(),
            input.summary(),
            input.source() != null ? input.source() : AdHocSource.MANUAL,
            input.prompt(),
            input.equipmentContext() != null ? input.equipmentContext() : EquipmentContext.empty(),
            input.targetDurationMinutes(),
            WorkoutDurationEstimator.estimateSeconds(day),
            input.tags(),
            input.pinned(),
            day,
            0,
            null,
            now,
            now
        );
        repo.save(workout);
        return workout;
    }

    /**
     * Replace mutable fields. Null args leave the existing value unchanged; when
     * {@code day} is supplied it is re-normalized and the estimate recomputed.
     * Run counters ({@code runCount}/{@code lastPerformedAt}) are never touched
     * here — they are owned by the run lifecycle.
     */
    public AdHocWorkout update(
        String userId, String adhocId,
        String title, String summary, List<String> tags, Boolean pinned,
        WorkoutDay day, Integer targetDurationMinutes, AdHocSource source
    ) {
        AdHocWorkout e = require(userId, adhocId);
        WorkoutDay newDay = day != null ? normalizeDay(day) : e.day();
        AdHocWorkout updated = new AdHocWorkout(
            e.userId(), e.adhocId(),
            title != null ? title : e.title(),
            summary != null ? summary : e.summary(),
            source != null ? source : e.source(),
            e.prompt(),
            e.equipmentContext(),
            targetDurationMinutes != null ? targetDurationMinutes : e.targetDurationMinutes(),
            WorkoutDurationEstimator.estimateSeconds(newDay),
            tags != null ? tags : e.tags(),
            pinned != null ? pinned : e.pinned(),
            newDay,
            e.runCount(),
            e.lastPerformedAt(),
            e.createdAt(),
            Instant.now()
        );
        repo.save(updated);
        return updated;
    }

    public void archive(String userId, String adhocId) {
        require(userId, adhocId);
        repo.archive(userId, adhocId);
    }

    public void restore(String userId, String adhocId) {
        repo.restore(userId, adhocId);
    }

    private AdHocWorkout require(String userId, String adhocId) {
        return repo.findById(userId, adhocId)
            .orElseThrow(() -> new IllegalArgumentException("Ad-hoc workout not found: " + adhocId));
    }

    // ---- normalization (mirrors WorkoutProgramService) ----

    /** Fill day/block/prescription ids and sequential order indices. */
    public static WorkoutDay normalizeDay(WorkoutDay day) {
        if (day == null) {
            return new WorkoutDay("wd_" + shortId(), "Workout", null, null, 0, List.of());
        }
        String dayId = day.dayId() != null ? day.dayId() : "wd_" + shortId();
        String label = day.label() != null ? day.label() : "Workout";
        return new WorkoutDay(dayId, label, day.dayOfWeek(), day.locationId(), 0,
            normalizeBlocks(day.blocks()));
    }

    private static List<Block> normalizeBlocks(List<Block> blocks) {
        if (blocks == null) {
            return List.of();
        }
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            String blockId = b.blockId() != null ? b.blockId() : "bk_" + shortId();
            BlockType type = b.type() != null ? b.type() : BlockType.MAIN;
            out.add(new Block(blockId, type, b.title(), i, normalizePrescriptions(b.prescriptions())));
        }
        return out;
    }

    private static List<Prescription> normalizePrescriptions(List<Prescription> ps) {
        if (ps == null) {
            return List.of();
        }
        List<Prescription> out = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            Prescription p = ps.get(i);
            out.add(new Prescription(p.exerciseId(), i, p.sets(), p.repsMin(), p.repsMax(),
                p.durationSeconds(), p.intensity(), p.restSeconds(), p.tempo(), p.notes(),
                p.deloadModifier(), p.loggedSets(), p.targetWeightLbs(), p.loadBasis()));
        }
        return out;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
