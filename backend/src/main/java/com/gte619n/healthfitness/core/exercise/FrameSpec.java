package com.gte619n.healthfitness.core.exercise;

/**
 * One planned demo position for an exercise (IMPL-19). The {@code demoPlan} is
 * an ordered list of these — model-derived and admin-reviewed — describing the
 * distinct positions this exercise needs to teach. Replaces the fixed
 * START/MID/END triad: a hold has one spec, a standard lift has two, a
 * skill/flow movement has three to five.
 *
 * <p>{@link DemoFrame} carries the generated image(s) for a spec, joined by
 * {@code key}.
 */
public record FrameSpec(
    String key,             // stable slug: "start" | "bottom" | "lockout" | "p1"...
    int order,              // 0-based display order
    String label,           // short UI label, e.g. "Bottom"
    String caption,         // one-line teaching cue
    String positionPrompt   // position clause fed to the image model
) {}
