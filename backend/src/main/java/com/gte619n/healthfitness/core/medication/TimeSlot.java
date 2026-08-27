package com.gte619n.healthfitness.core.medication;

import java.time.LocalTime;

/**
 * A time slot with its own dose amount.
 * Supports split dosing (e.g., 100mg morning + 100mg evening).
 *
 * <p>IMPL-21: a slot may carry an optional explicit {@code time} set in drug setup.
 * When present it is the highest-precedence reminder time for that slot (drug-setup
 * explicit time → per-medication settings override → global window default); when
 * null the window's configured/default time applies. Windows remain the backbone
 * used for grouping and adherence keying.
 */
public record TimeSlot(
    TimeWindow window,
    double dose,
    LocalTime time      // (nullable) explicit slot time; null ⇒ use the window time
) {
    /** Back-compat constructor: a slot with no explicit time uses the window default. */
    public TimeSlot(TimeWindow window, double dose) {
        this(window, dose, null);
    }
}
