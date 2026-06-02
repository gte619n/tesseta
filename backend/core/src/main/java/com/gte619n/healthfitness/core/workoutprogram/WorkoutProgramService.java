package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CRUD + normalization for workout programs. The whole Phase → Day → Block →
 * Prescription tree is stored embedded in the program document, so create and
 * update operate on the full tree; {@link #normalize} fills missing ids, order
 * indices, phase statuses, and phase target dates.
 */
@Service
public class WorkoutProgramService {

    private final WorkoutProgramRepository programs;

    public WorkoutProgramService(WorkoutProgramRepository programs) {
        this.programs = programs;
    }

    public List<WorkoutProgram> list(String userId) {
        return programs.findByUser(userId);
    }

    public Optional<WorkoutProgram> findById(String userId, String programId) {
        return programs.findById(userId, programId);
    }

    /** Create from a (possibly partially-filled) program; assigns ids/order/dates. */
    public WorkoutProgram create(WorkoutProgram input) {
        String programId = input.programId() != null ? input.programId() : "wp_" + shortId();
        Instant now = Instant.now();
        LocalDate startDate = input.startDate() != null ? input.startDate() : LocalDate.now();
        List<ProgramPhase> phases = normalize(startDate, input.phases());
        WorkoutProgram program = new WorkoutProgram(
            input.userId(),
            programId,
            input.title(),
            input.description(),
            input.goalId(),
            input.status() != null ? input.status() : ProgramStatus.DRAFT,
            input.source() != null ? input.source() : ProgramSource.MANUAL,
            startDate,
            input.schedule(),
            phases.stream().map(ProgramPhase::phaseId).toList(),
            phases,
            now,
            now,
            null
        );
        programs.save(program);
        return program;
    }

    /** Replace mutable fields. Null fields are left unchanged; if phases are
     *  supplied they are re-normalized. */
    public WorkoutProgram update(
        String userId, String programId,
        String title, String description, String goalId,
        ProgramSchedule schedule, LocalDate startDate, ProgramStatus status,
        List<ProgramPhase> phases
    ) {
        WorkoutProgram e = require(userId, programId);
        LocalDate newStart = startDate != null ? startDate : e.startDate();
        List<ProgramPhase> newPhases = phases != null ? normalize(newStart, phases) : e.phases();
        // Sticky: never reverse COMPLETED via update.
        ProgramStatus newStatus = status != null ? status : e.status();
        if (e.status() == ProgramStatus.COMPLETED && newStatus != ProgramStatus.COMPLETED) {
            newStatus = ProgramStatus.COMPLETED;
        }
        WorkoutProgram updated = new WorkoutProgram(
            e.userId(), e.programId(),
            title != null ? title : e.title(),
            description != null ? description : e.description(),
            goalId != null ? goalId : e.goalId(),
            newStatus,
            e.source(),
            newStart,
            schedule != null ? schedule : e.schedule(),
            newPhases.stream().map(ProgramPhase::phaseId).toList(),
            newPhases,
            e.createdAt(),
            Instant.now(),
            e.completedAt()
        );
        programs.save(updated);
        return updated;
    }

    public WorkoutProgram setStatus(String userId, String programId, ProgramStatus status) {
        WorkoutProgram e = require(userId, programId);
        WorkoutProgram updated = new WorkoutProgram(
            e.userId(), e.programId(), e.title(), e.description(), e.goalId(), status,
            e.source(), e.startDate(), e.schedule(), e.phaseOrder(), e.phases(),
            e.createdAt(), Instant.now(),
            status == ProgramStatus.COMPLETED ? Instant.now() : e.completedAt()
        );
        programs.save(updated);
        return updated;
    }

    public void archive(String userId, String programId) {
        setStatus(userId, programId, ProgramStatus.ARCHIVED);
    }

    private WorkoutProgram require(String userId, String programId) {
        return programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
    }

    /** Fill ids, order indices, phase statuses, and sequential target dates. */
    public static List<ProgramPhase> normalize(LocalDate startDate, List<ProgramPhase> phases) {
        if (phases == null) {
            return List.of();
        }
        List<ProgramPhase> out = new ArrayList<>();
        LocalDate cursor = startDate != null ? startDate : LocalDate.now();
        for (int i = 0; i < phases.size(); i++) {
            ProgramPhase p = phases.get(i);
            int weeks = Math.max(1, p.weeks());
            String phaseId = p.phaseId() != null ? p.phaseId() : "ph_" + shortId();
            ProgramPhaseStatus status = p.status() != null ? p.status()
                : (i == 0 ? ProgramPhaseStatus.ACTIVE : ProgramPhaseStatus.LOCKED);
            LocalDate phaseStart = p.targetStartDate() != null ? p.targetStartDate() : cursor;
            LocalDate phaseEnd = p.targetEndDate() != null ? p.targetEndDate()
                : phaseStart.plusWeeks(weeks).minusDays(1);
            cursor = phaseEnd.plusDays(1);
            out.add(new ProgramPhase(
                phaseId, p.title(), p.focus(), i, status, weeks, p.deloadWeekIndex(),
                phaseStart, phaseEnd, p.completedAt(), normalizeDays(p.days())
            ));
        }
        return out;
    }

    private static List<WorkoutDay> normalizeDays(List<WorkoutDay> days) {
        if (days == null) {
            return List.of();
        }
        List<WorkoutDay> out = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            WorkoutDay d = days.get(i);
            String dayId = d.dayId() != null ? d.dayId() : "wd_" + shortId();
            out.add(new WorkoutDay(dayId, d.label(), d.dayOfWeek(), d.locationId(), i,
                normalizeBlocks(d.blocks())));
        }
        return out;
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
                p.deloadModifier(), p.loggedSets()));
        }
        return out;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
