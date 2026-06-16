package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * The generated/uploaded image(s) for one planned {@link FrameSpec}, joined by
 * {@code key} (IMPL-19). {@code imageUrl} is the active frame;
 * {@code imageCandidates} holds every generated/uploaded url for the frame (the
 * active one is a member, or null). {@code label}/{@code caption} are
 * denormalized from the spec for client convenience.
 *
 * <p>{@code phase} is DEPRECATED and nullable — retained only to read legacy
 * documents written before the plan-keyed model. New documents key on
 * {@code key}; legacy reads derive {@code key} via {@link #keyForPhase}.
 */
public record DemoFrame(
    String key,                    // matches a FrameSpec.key
    String label,
    String caption,
    int order,
    String imageUrl,               // nullable
    List<String> imageCandidates,
    DemoPhase phase                // DEPRECATED, nullable — only to read legacy docs
) {
    /** Legacy phase → key mapping: START→"start", MID→"mid", END→"end". */
    public static String keyForPhase(DemoPhase p) {
        if (p == null) {
            return null;
        }
        return switch (p) {
            case START -> "start";
            case MID -> "mid";
            case END -> "end";
        };
    }
}
