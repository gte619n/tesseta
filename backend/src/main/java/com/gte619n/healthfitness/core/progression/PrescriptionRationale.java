package com.gte619n.healthfitness.core.progression;

import java.util.List;

/**
 * Structured "why this number" attached to an engine-authored prescription
 * (IMPL-PROG-01 D24). All UI (arrow glyph, confidence pill, why-line) renders
 * from this — never by parsing the legacy free-text {@code loadBasis}, which is
 * stamped FROM this for back-compat.
 *
 * @param direction UP/DOWN/HOLD vs. the last performed session (→ ▲/▼ glyph, D19)
 * @param inputs    short human strings naming what moved the number, e.g.
 *                  "last: 185×8 @ RIR 2", "deficit: holding load"
 */
public record PrescriptionRationale(
    ProgressionPath path,
    Direction direction,
    Double deltaLbs,
    Integer deltaReps,
    Integer deltaSets,
    Confidence confidence,
    List<String> inputs
) {
    /** Compact one-line summary, stamped into {@code Prescription.loadBasis}. */
    public String toLoadBasis() {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (path) {
            case KALMAN -> "engine";
            case DOUBLE_PROGRESSION, WARMUP -> "double progression";
            case FALLBACK_COLD_START -> "cold start";
            case FALLBACK_STALE -> "based on your last session";
            case FALLBACK_SANITY -> "held (sanity)";
            case FALLBACK_UNMATERIALIZED -> "based on your last session";
        });
        if (inputs != null && !inputs.isEmpty()) {
            sb.append(" · ").append(String.join("; ", inputs));
        }
        return sb.toString();
    }
}
