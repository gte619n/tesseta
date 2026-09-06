package com.gte619n.healthfitness.core.progression;

/**
 * Direction of a prescription change vs. the last performed session, rendered
 * as a ▲/▼ glyph on the client (IMPL-PROG-01 D19 — arrows, NOT colours, so the
 * green/red HIT/MISS language is untouched).
 */
public enum Direction {
    UP, DOWN, HOLD
}
