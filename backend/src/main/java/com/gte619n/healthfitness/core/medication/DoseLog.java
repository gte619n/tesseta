package com.gte619n.healthfitness.core.medication;

import java.time.Instant;

/**
 * Individual dose log entry within an adherence log.
 *
 * <p>IMPL-21: {@code missed} distinguishes an auto-recorded miss (the dose's day
 * ended untaken) from a genuine take. A dose is <em>taken</em> iff a log exists with
 * {@code missed == false}; <em>missed</em> iff a log exists with {@code missed == true};
 * <em>no data</em> iff no log exists. Missed doses do NOT count toward adherence.
 */
public record DoseLog(
    TimeWindow window,
    Instant takenAt,
    double dose,
    boolean missed
) {
    /** Back-compat constructor: a plain dose log is a genuine take (not missed). */
    public DoseLog(TimeWindow window, Instant takenAt, double dose) {
        this(window, takenAt, dose, false);
    }
}
