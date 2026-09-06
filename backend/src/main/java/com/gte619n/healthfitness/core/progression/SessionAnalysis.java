package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure helpers that read the signal out of one exercise's performed sets
 * (IMPL-PROG-01 §5–§6). The "working load" is the heaviest rep-based set; the
 * last working set is the high-information observation that updates e1RM.
 */
public final class SessionAnalysis {

    private SessionAnalysis() {}

    /** Heaviest weight among rep-based sets (reps present); 0 if none. */
    public static double workingLoad(List<LoggedSet> sets) {
        double max = 0;
        for (LoggedSet s : rep(sets)) {
            if (s.weightLbs() != null && s.weightLbs() > max) max = s.weightLbs();
        }
        return max;
    }

    /** Reps achieved on each set at (approximately) the working load. */
    public static List<Integer> repsAtWorkingLoad(List<LoggedSet> sets, double workingLoad) {
        List<Integer> out = new ArrayList<>();
        for (LoggedSet s : rep(sets)) {
            if (s.weightLbs() != null && Math.abs(s.weightLbs() - workingLoad) < 1e-6 && s.reps() != null) {
                out.add(s.reps());
            }
        }
        return out;
    }

    /**
     * The last performed rep-based set: the greatest {@code completedAt}, and on
     * a tie (or when timestamps are absent — e.g. imported history) the one later
     * in list order. Sets are logged in performed order, so list order is the
     * reliable tiebreaker.
     */
    public static LoggedSet lastWorkingSet(List<LoggedSet> sets) {
        List<LoggedSet> r = rep(sets);
        if (r.isEmpty()) return null;
        LoggedSet best = r.get(0);
        for (LoggedSet s : r) {
            if (s.completedAt() == null || best.completedAt() == null) {
                best = s; // no timestamp → later list position wins
            } else if (!s.completedAt().isBefore(best.completedAt())) {
                best = s; // >= so ties resolve to the later list item
            }
        }
        return best;
    }

    /**
     * Epley implied max from the last working set, or null when there is no
     * usable RIR (effectiveRir null → the observation drives no e1RM update, §5.2).
     */
    public static Double observedE1rm(LoggedSet lastSet, double loadOffsetLbs) {
        if (lastSet == null || lastSet.reps() == null || lastSet.weightLbs() == null) return null;
        Double rir = lastSet.effectiveRir();
        if (rir == null) return null;
        double totalReps = lastSet.reps() + rir;
        return ProgressionMath.epleyE1rm(lastSet.weightLbs() + loadOffsetLbs, totalReps);
    }

    /** total capable reps = reps + effectiveRir (drives the noise model); null if no RIR. */
    public static Double totalCapableReps(LoggedSet lastSet) {
        if (lastSet == null || lastSet.reps() == null) return null;
        Double rir = lastSet.effectiveRir();
        return rir == null ? null : lastSet.reps() + rir;
    }

    private static List<LoggedSet> rep(List<LoggedSet> sets) {
        List<LoggedSet> out = new ArrayList<>();
        if (sets == null) return out;
        for (LoggedSet s : sets) {
            if (s != null && s.reps() != null) out.add(s);
        }
        return out;
    }
}
