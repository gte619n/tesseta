package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Writes an engine-derived prescription onto future PLANNED sessions of the
 * user's ACTIVE program(s) (IMPL-PROG-01 D2/D4). Only progression-eligible
 * blocks are touched (D21); COMPLETED/past sessions are never rewritten. Reuses
 * the {@code saveSessions} wholesale-replace path so nested blocks are replaced,
 * not merged.
 */
@Service
public class ProgressionWriteback {

    /** Only these block types carry working sets the engine owns (D21). */
    static final Set<BlockType> ELIGIBLE_BLOCKS =
        EnumSet.of(BlockType.MAIN, BlockType.ACCESSORY, BlockType.CORE);

    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;

    public ProgressionWriteback(WorkoutProgramRepository programs, ScheduledWorkoutRepository scheduled) {
        this.programs = programs;
        this.scheduled = scheduled;
    }

    /**
     * Stamp {@code load} onto every future PLANNED occurrence of {@code exerciseId}
     * in eligible blocks, strictly after {@code afterDate}.
     */
    public void applyNextPrescription(String userId, String exerciseId, PrescribedLoad load, LocalDate afterDate) {
        for (WorkoutProgram program : programs.findByUser(userId)) {
            if (program.status() != ProgramStatus.ACTIVE) continue;
            List<ScheduledWorkout> future = scheduled.findByProgram(
                userId, program.programId(), afterDate.plusDays(1), LocalDate.MAX);
            List<ScheduledWorkout> edited = new ArrayList<>();
            for (ScheduledWorkout sw : future) {
                if (sw.status() != ScheduledStatus.PLANNED || sw.session() == null) continue;
                WorkoutDay rewritten = rewriteDay(sw.session(), exerciseId, load);
                if (rewritten != null) edited.add(withSession(sw, rewritten));
            }
            if (!edited.isEmpty()) scheduled.saveSessions(edited);
        }
    }

    /** Rebuild a day with the load applied to matching prescriptions; null if nothing matched. */
    private static WorkoutDay rewriteDay(WorkoutDay day, String exerciseId, PrescribedLoad load) {
        if (day.blocks() == null) return null;
        boolean touched = false;
        List<Block> newBlocks = new ArrayList<>();
        for (Block block : day.blocks()) {
            if (block.type() == null || !ELIGIBLE_BLOCKS.contains(block.type()) || block.prescriptions() == null) {
                newBlocks.add(block);
                continue;
            }
            List<Prescription> newRxs = new ArrayList<>();
            for (Prescription rx : block.prescriptions()) {
                if (exerciseId.equals(rx.exerciseId()) && rx.durationSeconds() == null) {
                    newRxs.add(stamp(rx, load));
                    touched = true;
                } else {
                    newRxs.add(rx);
                }
            }
            newBlocks.add(new Block(block.blockId(), block.type(), block.title(), block.orderIndex(), newRxs));
        }
        if (!touched) return null;
        return new WorkoutDay(day.dayId(), day.label(), day.dayOfWeek(), day.locationId(),
            day.orderIndex(), newBlocks);
    }

    private static Prescription stamp(Prescription rx, PrescribedLoad load) {
        String basis = load.rationale() == null ? null : load.rationale().toLoadBasis();
        return new Prescription(
            rx.exerciseId(), rx.orderIndex(), load.sets(), load.repsMin(), load.repsMax(),
            rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(), rx.notes(),
            rx.deloadModifier(), rx.loggedSets(),
            load.targetWeightLbs(), basis, load.rationale());
    }

    private static ScheduledWorkout withSession(ScheduledWorkout sw, WorkoutDay day) {
        return new ScheduledWorkout(
            sw.userId(), sw.programId(), sw.scheduledId(), sw.date(), sw.phaseId(), sw.dayId(),
            sw.dayLabel(), sw.weekIndexInPhase(), sw.isDeload(), sw.locationId(), sw.status(),
            day, sw.completedAt(), sw.durationSeconds(), sw.feeling());
    }
}
