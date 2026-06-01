package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * One still frame of the demo for a given {@link DemoPhase}. {@code imageUrl}
 * is the active frame; {@code imageCandidates} holds every generated/uploaded
 * url for the phase (the active one is a member, or null).
 */
public record DemoFrame(
    DemoPhase phase,
    String imageUrl,
    List<String> imageCandidates
) {}
