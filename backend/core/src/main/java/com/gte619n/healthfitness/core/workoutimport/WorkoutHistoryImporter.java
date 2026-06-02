package com.gte619n.healthfitness.core.workoutimport;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.exercise.BlockType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Maps {@code future_workouts.json} (a name-only catalog + a flat, phase-tagged
 * log of completed sessions) into Firestore for one user (IMPL-15 history
 * import, ADR-0008). Three outputs:
 *
 * <ol>
 *   <li><b>Catalog</b> — each source exercise becomes an {@link Exercise} keyed
 *       by its <em>source UUID</em> (so the log's references resolve), enriched
 *       via {@link ExerciseMetadataEnricher}, {@code PUBLISHED} with no media.</li>
 *   <li><b>Program + phases</b> — one {@link WorkoutProgram} ({@code COMPLETED})
 *       holding 20 date-bounded {@link ProgramPhase}s (no weekly templates —
 *       the source has none).</li>
 *   <li><b>History</b> — each session becomes a {@code COMPLETED}
 *       {@link ScheduledWorkout} whose snapshot carries the performed
 *       {@link LoggedSet}s, written via {@code saveAll} (batched).</li>
 * </ol>
 *
 * Idempotent: catalog writes skip ids that already exist; program/phase/session
 * ids are deterministic so re-runs upsert cleanly.
 */
@Service
public class WorkoutHistoryImporter {

    private static final System.Logger log = System.getLogger(WorkoutHistoryImporter.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final String PROGRAM_ID = "imported-history";

    private final ExerciseRepository exercises;
    private final EquipmentRepository equipment;
    private final ExerciseMetadataEnricher enricher;
    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;

    public WorkoutHistoryImporter(
        ExerciseRepository exercises,
        EquipmentRepository equipment,
        ExerciseMetadataEnricher enricher,
        WorkoutProgramRepository programs,
        ScheduledWorkoutRepository scheduled
    ) {
        this.exercises = exercises;
        this.equipment = equipment;
        this.enricher = enricher;
        this.programs = programs;
        this.scheduled = scheduled;
    }

    public record ImportResult(
        int exercisesSeeded,
        int exercisesSkipped,
        int phases,
        int sessions,
        int sessionExercisesSkipped,
        List<String> unresolvedEquipmentNames
    ) {}

    public ImportResult importAll(String userId, FutureWorkouts data) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        TreeSet<String> unresolved = new TreeSet<>();
        int[] catalog = seedCatalog(data, unresolved);          // [seeded, skipped]
        int phaseCount = seedProgramAndPhases(userId, data);
        int[] hist = seedHistory(userId, data);                 // [sessions, skippedExercises]

        log.log(System.Logger.Level.INFO,
            "WorkoutHistoryImporter: user={0} seeded={1} skipped={2} phases={3} sessions={4} skippedSessionExercises={5} unresolvedEquip={6}",
            userId, catalog[0], catalog[1], phaseCount, hist[0], hist[1], unresolved.size());
        return new ImportResult(catalog[0], catalog[1], phaseCount, hist[0], hist[1], new ArrayList<>(unresolved));
    }

    // ---- 1. catalog ----

    private int[] seedCatalog(FutureWorkouts data, TreeSet<String> unresolved) {
        Map<String, String> equipmentByName = new HashMap<>();
        Map<String, String> equipmentByNormalized = new HashMap<>();
        List<String> catalogNames = new ArrayList<>();
        for (Equipment e : equipment.findCatalog(null, null, null)) {
            if (e.name() != null) {
                equipmentByName.put(e.name().toLowerCase(), e.equipmentId());
                equipmentByNormalized.putIfAbsent(normalizeEquipmentName(e.name()), e.equipmentId());
                catalogNames.add(e.name());
            }
        }
        int seeded = 0;
        int skipped = 0;
        for (FutureWorkouts.CatalogExercise src : data.exercises()) {
            if (src.id() == null || src.id().isBlank()) {
                continue;
            }
            if (exercises.findById(src.id()).isPresent()) {
                skipped++;
                continue;
            }
            ExerciseMetadataEnricher.Enrichment m = enricher.enrich(src.name(), catalogNames);
            List<EquipmentRequirement> reqs = resolveEquipment(m.equipmentNameGroups(), equipmentByName, equipmentByNormalized, unresolved);
            Instant now = Instant.now();
            exercises.save(new Exercise(
                src.id(),
                src.name(),
                src.name() == null ? null : src.name().toLowerCase(),
                List.of(),
                m.movementPattern(),
                m.primaryMuscles(),
                m.secondaryMuscles(),
                m.laterality(),
                m.mechanic(),
                m.description(),
                m.formCues(),
                reqs,
                m.suitableBlockTypes(),
                m.defaultRepRange(),
                m.isTimed(),
                List.of(),
                null,
                null,
                ExerciseMediaStatus.NONE,
                ExerciseStatus.PUBLISHED,
                null,
                now,
                now,
                null));
            seeded++;
        }
        return new int[] {seeded, skipped};
    }

    private List<EquipmentRequirement> resolveEquipment(
        List<List<String>> groups, Map<String, String> byName, Map<String, String> byNormalized,
        TreeSet<String> unresolved) {
        List<EquipmentRequirement> reqs = new ArrayList<>();
        if (groups == null) {
            return reqs;
        }
        for (List<String> group : groups) {
            List<String> ids = new ArrayList<>();
            for (String name : group) {
                if (name == null) {
                    continue;
                }
                String id = byName.get(name.toLowerCase());
                if (id == null) {
                    id = byNormalized.get(normalizeEquipmentName(name));
                }
                if (id != null) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } else {
                    unresolved.add(name);
                }
            }
            if (!ids.isEmpty()) {
                reqs.add(new EquipmentRequirement(ids));
            }
        }
        return reqs;
    }

    /** Normalize an equipment name for tolerant matching: lowercase, trim, collapse internal whitespace, and fold a trailing plural 's'. */
    private static String normalizeEquipmentName(String name) {
        if (name == null) return "";
        String n = name.toLowerCase().trim().replaceAll("\\s+", " ");
        if (n.endsWith("s") && n.length() > 1) n = n.substring(0, n.length() - 1);
        return n;
    }

    // ---- 2. program + phases ----

    private int seedProgramAndPhases(String userId, FutureWorkouts data) {
        // Group sessions by phase to derive each phase's date bounds.
        Map<String, PhaseBounds> bounds = new LinkedHashMap<>();
        for (FutureWorkouts.Session s : data.workouts()) {
            LocalDate d = dateOf(s);
            if (d == null || s.phaseId() == null) {
                continue;
            }
            bounds.computeIfAbsent(s.phaseId(), k -> new PhaseBounds(s.phaseName())).add(d);
        }
        List<Map.Entry<String, PhaseBounds>> ordered = new ArrayList<>(bounds.entrySet());
        ordered.sort(Comparator.comparing(e -> e.getValue().min));

        List<ProgramPhase> phases = new ArrayList<>();
        List<String> phaseOrder = new ArrayList<>();
        LocalDate programStart = null;
        LocalDate programEnd = null;
        for (int i = 0; i < ordered.size(); i++) {
            String phaseId = ordered.get(i).getKey();
            PhaseBounds b = ordered.get(i).getValue();
            int weeks = (int) ChronoUnit.WEEKS.between(b.min, b.max) + 1;
            phases.add(new ProgramPhase(
                phaseId, b.name, null, i, ProgramPhaseStatus.COMPLETED,
                Math.max(1, weeks), null, b.min, b.max, endOfDay(b.max), List.of()));
            phaseOrder.add(phaseId);
            if (programStart == null || b.min.isBefore(programStart)) programStart = b.min;
            if (programEnd == null || b.max.isAfter(programEnd)) programEnd = b.max;
        }

        Instant now = Instant.now();
        programs.save(new WorkoutProgram(
            userId, PROGRAM_ID, "Imported Training History",
            "Imported from future_workouts.json (IMPL-15, ADR-0008).",
            null, ProgramStatus.COMPLETED, ProgramSource.MANUAL,
            programStart, null, phaseOrder, phases, now, now, endOfDay(programEnd)));
        return phases.size();
    }

    // ---- 3. history ----

    private int[] seedHistory(String userId, FutureWorkouts data) {
        // Phase start dates → weekIndexInPhase.
        Map<String, LocalDate> phaseStart = new HashMap<>();
        for (FutureWorkouts.Session s : data.workouts()) {
            LocalDate d = dateOf(s);
            if (d == null || s.phaseId() == null) continue;
            phaseStart.merge(s.phaseId(), d, (a, b) -> a.isBefore(b) ? a : b);
        }

        List<FutureWorkouts.Session> sorted = new ArrayList<>(data.workouts());
        sorted.sort(Comparator.comparing(s -> s.completedTime() == null ? "" : s.completedTime()));

        List<ScheduledWorkout> out = new ArrayList<>();
        int skippedExercises = 0;
        for (int i = 0; i < sorted.size(); i++) {
            FutureWorkouts.Session s = sorted.get(i);
            LocalDate date = dateOf(s);
            if (date == null) {
                continue;
            }
            String dayId = "s" + i;
            List<Prescription> rxs = new ArrayList<>();
            int order = 0;
            if (s.exercises() != null) {
                for (FutureWorkouts.SessionExercise se : s.exercises()) {
                    if (se.exerciseId() == null || se.exerciseId().isBlank()) {
                        skippedExercises++;
                        continue;
                    }
                    List<LoggedSet> logged = new ArrayList<>();
                    if (se.sets() != null) {
                        for (FutureWorkouts.PerformedSet ps : se.sets()) {
                            logged.add(new LoggedSet(sanitizeWeight(ps.weightLbs()), ps.reps()));
                        }
                    }
                    rxs.add(new Prescription(
                        se.exerciseId(), order++, logged.size(),
                        null, null, null, null, null, null, se.exerciseName(), null, logged));
                }
            }
            Block block = new Block("logged", BlockType.MAIN, "Logged", 0, rxs);
            WorkoutDay session = new WorkoutDay(
                dayId, s.workoutName(), dayOfWeek(date), null, 0, List.of(block));
            LocalDate start = phaseStart.getOrDefault(s.phaseId(), date);
            int weekIndex = (int) ChronoUnit.WEEKS.between(start, date) + 1;
            out.add(new ScheduledWorkout(
                userId, PROGRAM_ID, date + "_" + dayId, date, s.phaseId(), dayId,
                s.workoutName(), Math.max(1, weekIndex), isDeload(s.workoutName()),
                null, ScheduledStatus.COMPLETED, session));
        }
        scheduled.saveAll(out);
        return new int[] {out.size(), skippedExercises};
    }

    // ---- helpers ----

    /** The export uses NaN for "no weight recorded"; store that as null. */
    private static Double sanitizeWeight(Double w) {
        return (w == null || w.isNaN() || w.isInfinite()) ? null : w;
    }

    private static boolean isDeload(String name) {
        if (name == null) return false;
        String u = name.toUpperCase();
        return u.contains("DE-LOAD") || u.contains("DELOAD");
    }

    private static LocalDate dateOf(FutureWorkouts.Session s) {
        if (s.completedTime() == null || s.completedTime().isBlank()) return null;
        try {
            return LocalDateTime.parse(s.completedTime().trim(), TS).toLocalDate();
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Unparseable completed_time ''{0}''", s.completedTime());
            return null;
        }
    }

    private static com.gte619n.healthfitness.core.location.DayOfWeek dayOfWeek(LocalDate d) {
        // java.time getValue() is 1 (MON) .. 7 (SUN); core DayOfWeek is MON..SUN.
        return com.gte619n.healthfitness.core.location.DayOfWeek.values()[d.getDayOfWeek().getValue() - 1];
    }

    private static Instant endOfDay(LocalDate d) {
        return d == null ? null : d.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
    }

    private static final class PhaseBounds {
        final String name;
        LocalDate min;
        LocalDate max;

        PhaseBounds(String name) {
            this.name = name;
        }

        void add(LocalDate d) {
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
    }
}
