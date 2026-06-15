package com.gte619n.healthfitness.core.trt;

/**
 * A hard safety alert raised when a TRT-relevant lab crosses a known risk
 * threshold (ADR-0015, S6e). Surfaced in the designer response regardless of
 * what the user asked.
 *
 * <p>Component names are part of the client wire contract — do not rename.
 */
public record DangerFlag(
    String marker,
    Severity severity,
    String message
) {}
