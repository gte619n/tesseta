package com.gte619n.healthfitness.core.workoutprogram.catalog;

import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.ArrayList;
import java.util.List;

/**
 * IMPL-MULTIUSER-01 P3.2 (D10) — full generalization of a user's program into a
 * shareable template. Pure functions only.
 *
 * <p><b>What it does (per D10):</b>
 * <ol>
 *   <li><b>Absolute loads → relative %.</b> A prescription's concrete
 *       {@code targetWeightLbs} is removed and replaced with a {@code PERCENT_1RM}
 *       intensity so the template is portable across bodies/strength levels. The
 *       percentage is <em>derived from the prescribed rep target</em> via the
 *       classic Epley-inverse table (see {@link #percentOf1RmForReps}) when the
 *       prescription does not already carry a usable intensity. RPE intensities
 *       are preserved as-is (already relative); an existing {@code PERCENT_1RM}
 *       is preserved. Rationale for using reps rather than the raw weight: we do
 *       not know the author's true 1RM, so the weight alone cannot be turned into
 *       a %1RM — but the rep target the author chose <em>is</em> an implicit %1RM
 *       prescription. This keeps the template's training stimulus intact while
 *       discarding the author-specific absolute load.</li>
 *   <li><b>Strip identifiers / per-user coaching.</b> {@code notes},
 *       {@code loadBasis}, {@code rationale}, and {@code loggedSets} are cleared
 *       (they reference the author's history, goals, and AI explanations).</li>
 * </ol>
 * The admin finalizes anything imperfect during review (D10).
 */
public final class ProgramGeneralizer {

    private ProgramGeneralizer() {}

    public static List<ProgramPhase> generalizePhases(List<ProgramPhase> phases) {
        List<ProgramPhase> out = new ArrayList<>();
        if (phases == null) return out;
        for (ProgramPhase p : phases) {
            out.add(new ProgramPhase(
                p.phaseId(),
                p.title(),
                p.focus(),
                p.orderIndex(),
                p.status(),
                p.weeks(),
                p.deloadWeekIndex(),
                null,                       // targetStartDate — scheduling is per-user
                null,                       // targetEndDate
                null,                       // completedAt — per-user instance state
                generalizeDays(p.days()),
                p.nutritionGuidance()
            ));
        }
        return out;
    }

    private static List<WorkoutDay> generalizeDays(List<WorkoutDay> days) {
        List<WorkoutDay> out = new ArrayList<>();
        if (days == null) return out;
        for (WorkoutDay d : days) {
            out.add(generalizeDay(d));
        }
        return out;
    }

    /**
     * Generalize a single workout day: strip the user-specific gym binding
     * ({@code locationId}) and generalize every prescription (loads→%, notes/ids
     * stripped). Reused by the ad-hoc catalog path (P3.3).
     */
    public static WorkoutDay generalizeDay(WorkoutDay d) {
        if (d == null) return null;
        return new WorkoutDay(
            d.dayId(),
            d.label(),
            d.dayOfWeek(),
            null,                           // locationId — a user-specific gym id
            d.orderIndex(),
            generalizeBlocks(d.blocks())
        );
    }

    private static List<Block> generalizeBlocks(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block b : blocks) {
            List<Prescription> rxs = new ArrayList<>();
            if (b.prescriptions() != null) {
                for (Prescription rx : b.prescriptions()) {
                    rxs.add(generalizePrescription(rx));
                }
            }
            out.add(new Block(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return out;
    }

    /**
     * Strip the per-user coaching fields and convert an absolute load to a
     * relative %1RM intensity (unless a relative intensity is already present).
     */
    static Prescription generalizePrescription(Prescription rx) {
        Intensity intensity = relativeIntensity(rx);
        // Uses the pre-IMPL-18 canonical ctor: null targetWeightLbs/loadBasis/
        // rationale, drops loggedSets. notes cleared.
        return new Prescription(
            rx.exerciseId(),
            rx.orderIndex(),
            rx.sets(),
            rx.repsMin(),
            rx.repsMax(),
            rx.durationSeconds(),
            intensity,
            rx.restSeconds(),
            rx.tempo(),
            null,                           // notes stripped (D10)
            rx.deloadModifier(),
            null                            // loggedSets stripped — author history
        );
    }

    /**
     * Preserve an existing relative intensity (RPE or %1RM). Otherwise, if the
     * prescription carried an absolute load (or none), derive a %1RM from the rep
     * target so the stimulus survives without exposing the author's weight.
     */
    private static Intensity relativeIntensity(Prescription rx) {
        Intensity existing = rx.intensity();
        if (existing != null && existing.kind() != null
            && existing.kind() != IntensityKind.NONE
            && existing.value() != null) {
            return existing;               // already relative (RPE / %1RM)
        }
        Integer reps = repTarget(rx);
        if (reps == null) {
            // Timed/holds or no rep info → no derivable %; leave unprescribed.
            return new Intensity(IntensityKind.NONE, null);
        }
        return new Intensity(IntensityKind.PERCENT_1RM, percentOf1RmForReps(reps));
    }

    /** The representative rep target: prefer the top of the band, else the min. */
    private static Integer repTarget(Prescription rx) {
        if (rx.repsMax() != null) return rx.repsMax();
        if (rx.repsMin() != null) return rx.repsMin();
        return null;
    }

    /**
     * Epley-inverse %1RM for a given rep target: {@code 1RM = w * (1 + reps/30)},
     * so the load that can be lifted for {@code reps} is {@code 1 / (1 + reps/30)}
     * of 1RM. Returned as a 0–100 percentage, clamped to a sane [40,100] band and
     * rounded to the nearest whole percent. (1 rep → ~97%, 5 → ~86%, 10 → ~75%,
     * 12 → ~71%, 15 → ~67%.)
     */
    static double percentOf1RmForReps(int reps) {
        int r = Math.max(1, reps);
        double pct = 100.0 / (1.0 + (r / 30.0));
        double clamped = Math.max(40.0, Math.min(100.0, pct));
        return Math.round(clamped);
    }
}
