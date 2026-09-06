package com.gte619n.healthfitness.core.progression;

/**
 * An inclusive target rep range the block loop hands the session loop
 * (IMPL-PROG-01 §8). Kept local to the progression package so the engine does
 * not couple to the catalog's {@code RepRange}.
 */
public record RepBand(int min, int max) {
    public RepBand {
        if (max < min) throw new IllegalArgumentException("RepBand max < min: " + min + ".." + max);
    }

    /** Midpoint, rounded down — the point estimate the session loop prescribes toward. */
    public int mid() {
        return (min + max) / 2;
    }
}
