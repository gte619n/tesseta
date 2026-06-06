package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutBlockSplitter;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutBlockSplitter.Section;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.integrations.workoutprogram.GeminiWorkoutBlockClassifier;
import com.gte619n.healthfitness.integrations.workoutprogram.GeminiWorkoutBlockClassifier.ExerciseToClassify;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-time Cloud Run Job that rewrites the imported-history program's logged
 * sessions (a single flat "Logged" block) into Warm-up / Main / Cool-down
 * blocks, persisted to Firestore so the structure is stable and offline-capable
 * (newer programs are authored with real blocks already).
 *
 * <p>Hybrid split: the deterministic part is which movements are working sets —
 * {@link WorkoutBlockSplitter#isStrength} from {@code suitableBlockTypes} → Main.
 * The remaining mobility/stretch movements are labelled Warm-up vs Cool-down by
 * {@link GeminiWorkoutBlockClassifier} (best-effort, per unique exercise), since
 * the logged order is not warm-up→main→cool-down and a positional rule can't
 * recover it. Exercises left unclassified default to Main (harmless).
 *
 * <p>Idempotent: a day already split (more than one block, or a Warm-up/Cool-down
 * block present) is skipped. Defaults to a dry run; set
 * {@code app.workouts.split.dry-run=false} to persist. Scope is a single user's
 * imported program via {@code app.workouts.split.user-id}.
 * See {@code infra/scripts/deploy-split-workout-blocks-job.sh}.
 */
@Component
@Profile("job-split-blocks")
public class SplitImportedWorkoutBlocksJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(SplitImportedWorkoutBlocksJob.class.getName());
    private static final System.Logger.Level INFO = System.Logger.Level.INFO;
    private static final System.Logger.Level WARN = System.Logger.Level.WARNING;

    private final ScheduledWorkoutRepository scheduled;
    private final ExerciseRepository exercises;
    private final ObjectProvider<GeminiWorkoutBlockClassifier> classifierProvider;
    private final String userId;
    private final boolean dryRun;

    public SplitImportedWorkoutBlocksJob(
        ScheduledWorkoutRepository scheduled,
        ExerciseRepository exercises,
        ObjectProvider<GeminiWorkoutBlockClassifier> classifierProvider,
        @Value("${app.workouts.split.user-id:}") String userId,
        @Value("${app.workouts.split.dry-run:true}") boolean dryRun
    ) {
        this.scheduled = scheduled;
        this.exercises = exercises;
        this.classifierProvider = classifierProvider;
        this.userId = userId;
        this.dryRun = dryRun;
    }

    @Override
    public void run(String... args) {
        if (userId == null || userId.isBlank()) {
            log.log(WARN, "SplitImportedWorkoutBlocksJob: app.workouts.split.user-id is unset — nothing to do");
            return;
        }
        log.log(INFO, "SplitImportedWorkoutBlocksJob: starting (user={0}, dryRun={1})", userId, dryRun);

        List<ScheduledWorkout> sessions = scheduled.findByProgram(
            userId, WorkoutHistoryImporter.PROGRAM_ID,
            LocalDate.of(1970, 1, 1), LocalDate.of(2999, 12, 31));
        if (sessions.isEmpty()) {
            log.log(INFO, "SplitImportedWorkoutBlocksJob: no sessions for program {0}",
                WorkoutHistoryImporter.PROGRAM_ID);
            return;
        }

        Map<String, Section> sectionById = classifyExercises(sessions);

        List<ScheduledWorkout> updated = new ArrayList<>();
        int skipped = 0;
        for (ScheduledWorkout sw : sessions) {
            WorkoutDay day = sw.session();
            if (day == null || day.blocks() == null || day.blocks().isEmpty() || alreadySplit(day)) {
                skipped++;
                continue;
            }
            List<Block> newBlocks = WorkoutBlockSplitter.split(day, sectionById);
            if (newBlocks.size() <= 1) {
                // Nothing meaningful to split (e.g. all Main).
                skipped++;
                continue;
            }
            WorkoutDay newDay = new WorkoutDay(
                day.dayId(), day.label(), day.dayOfWeek(), day.locationId(), day.orderIndex(), newBlocks);
            updated.add(new ScheduledWorkout(
                sw.userId(), sw.programId(), sw.scheduledId(), sw.date(), sw.phaseId(), sw.dayId(),
                sw.dayLabel(), sw.weekIndexInPhase(), sw.isDeload(), sw.locationId(), sw.status(),
                newDay, sw.completedAt(), sw.durationSeconds()));
        }

        logSectionSummary(sectionById);
        log.log(INFO, "SplitImportedWorkoutBlocksJob: {0} session(s) to split, {1} skipped (already split / empty / single-section)",
            updated.size(), skipped);
        logSample(updated);

        if (dryRun) {
            log.log(INFO, "SplitImportedWorkoutBlocksJob: DRY RUN — no writes. "
                + "Set app.workouts.split.dry-run=false to persist.");
            return;
        }
        scheduled.saveAll(updated);
        log.log(INFO, "SplitImportedWorkoutBlocksJob: persisted {0} split session(s)", updated.size());
    }

    /** Build the exerciseId → Section map: strength deterministically, the rest via Gemini. */
    private Map<String, Section> classifyExercises(List<ScheduledWorkout> sessions) {
        Set<String> ids = new LinkedHashSet<>();
        for (ScheduledWorkout sw : sessions) {
            for (var rx : WorkoutBlockSplitter.orderedPrescriptions(sw.session())) {
                if (rx.exerciseId() != null) ids.add(rx.exerciseId());
            }
        }
        Map<String, Exercise> byId = new HashMap<>();
        for (Exercise e : exercises.findByIds(new ArrayList<>(ids))) {
            byId.put(e.exerciseId(), e);
        }

        Map<String, Section> sectionById = new HashMap<>();
        List<ExerciseToClassify> toClassify = new ArrayList<>();
        for (String id : ids) {
            Exercise e = byId.get(id);
            List<BlockType> suitable = e == null ? null : e.suitableBlockTypes();
            if (WorkoutBlockSplitter.isStrength(suitable)) {
                sectionById.put(id, Section.MAIN);
            } else {
                toClassify.add(new ExerciseToClassify(id, e == null ? id : e.name(), suitable));
            }
        }

        GeminiWorkoutBlockClassifier classifier = classifierProvider.getIfAvailable();
        if (classifier != null && !toClassify.isEmpty()) {
            Map<String, Section> g = classifier.classify(toClassify);
            sectionById.putAll(g);
            log.log(INFO, "SplitImportedWorkoutBlocksJob: classified {0}/{1} non-strength exercise(s) via Gemini",
                g.size(), toClassify.size());
        } else if (!toClassify.isEmpty()) {
            log.log(WARN, "SplitImportedWorkoutBlocksJob: Gemini classifier unavailable — {0} mobility exercise(s) "
                + "default to Main (no warm-up/cool-down split)", toClassify.size());
        }
        return sectionById;
    }

    private static boolean alreadySplit(WorkoutDay day) {
        if (day.blocks().size() > 1) return true;
        for (Block b : day.blocks()) {
            if (b.type() == BlockType.WARMUP || b.type() == BlockType.COOLDOWN) return true;
        }
        return false;
    }

    private void logSectionSummary(Map<String, Section> sectionById) {
        Map<Section, Integer> counts = new EnumMap<>(Section.class);
        for (Section s : sectionById.values()) counts.merge(s, 1, Integer::sum);
        log.log(INFO, "SplitImportedWorkoutBlocksJob: exercise sections — warm-up={0}, main={1}, cool-down={2}",
            counts.getOrDefault(Section.WARMUP, 0), counts.getOrDefault(Section.MAIN, 0),
            counts.getOrDefault(Section.COOLDOWN, 0));
    }

    private void logSample(List<ScheduledWorkout> updated) {
        if (updated.isEmpty()) return;
        WorkoutDay day = updated.get(0).session();
        StringBuilder sb = new StringBuilder();
        for (Block b : day.blocks()) {
            sb.append("\n  [").append(b.type()).append("] ").append(b.title());
            for (var rx : b.prescriptions()) {
                sb.append("\n     - ").append(rx.notes() == null ? rx.exerciseId() : rx.notes());
            }
        }
        log.log(INFO, "SplitImportedWorkoutBlocksJob: sample split ({0}):{1}",
            updated.get(0).scheduledId(), sb);
    }
}
