package com.gte619n.healthfitness.core.goals.chat;

import java.util.List;

/**
 * The un-validated, loosely-typed proposal as it arrives from Gemini's
 * tool call or from the user-edited commit payload.
 *
 * <p>Everything is a {@link String} (or nested raw record) so the
 * validator can decide how to flag malformed enums, dates, and numbers
 * inline rather than failing deserialization. The API {@code GoalProposalDto}
 * and the Gemini tool-args map both convert into this shape before
 * {@link GoalProposalValidator} runs.
 */
public record RawProposal(
    String title,
    String description,
    String domain,
    String targetDate,
    List<RawPhase> phases
) {

    public record RawPhase(
        String title,
        String description,
        String targetStartDate,
        String targetEndDate,
        List<RawStep> steps
    ) {}

    public record RawStep(
        String title,
        String kind,
        RawMetric metric
    ) {}

    public record RawMetric(
        String metricKey,
        String comparator,
        Double targetValue,
        Integer windowDays,
        String countFrom
    ) {}
}
