package com.gte619n.healthfitness.core.trt;

import java.util.List;

/**
 * The grounded TRT context for a designer turn: whether the user is on TRT,
 * the current monitoring-panel markers (latest value + trend + status), and
 * any danger flags fired this turn (ADR-0015).
 *
 * <p>Component names are part of the client wire contract — do not rename.
 */
public record TrtContext(
    boolean onTrt,
    List<TrtMarker> markers,
    List<DangerFlag> dangerFlags
) {}
