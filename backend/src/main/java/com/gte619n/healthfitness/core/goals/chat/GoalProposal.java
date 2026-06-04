package com.gte619n.healthfitness.core.goals.chat;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.StepKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A validated Goal structure proposed by Gemini (or hand-edited by the
 * user before commit).
 *
 * <p>This is the canonical in-core representation produced by
 * {@link GoalProposalValidator}. Every level carries an optional
 * {@code validationError} string: when validation flags a field, the
 * offending value is <strong>kept</strong> and the error is attached
 * inline so the UI can render it on the proposal card, rather than
 * silently dropping the field (IMPL-12 acceptance criteria).
 *
 * <p>Date fields are normalized to {@link LocalDate}; {@code countFrom}
 * is normalized to {@link Instant} and defaulted to "now" for COUNT
 * steps during validation.
 */
public record GoalProposal(
    String title,
    String description,
    GoalDomain domain,
    LocalDate targetDate,
    List<ProposalPhase> phases,
    // Goal-level error (e.g. targetDate in the past, missing title).
    String validationError
) {

    public record ProposalPhase(
        String title,
        String description,
        LocalDate targetStartDate,
        LocalDate targetEndDate,
        List<ProposalStep> steps,
        String validationError
    ) {}

    public record ProposalStep(
        String title,
        StepKind kind,
        ProposalMetric metric,   // null for MANUAL
        String validationError
    ) {}

    public record ProposalMetric(
        String metricKey,
        Comparator comparator,
        Double targetValue,
        Integer windowDays,   // SUSTAINED only
        Instant countFrom,    // COUNT only
        String validationError
    ) {}

    /**
     * True when no level of the proposal carries a validationError.
     * The commit endpoint refuses to persist a proposal that isn't
     * fully valid.
     */
    public boolean isValid() {
        if (validationError != null) return false;
        if (phases == null) return true;
        for (ProposalPhase p : phases) {
            if (p.validationError() != null) return false;
            if (p.steps() == null) continue;
            for (ProposalStep s : p.steps()) {
                if (s.validationError() != null) return false;
                ProposalMetric m = s.metric();
                if (m != null && m.validationError() != null) return false;
            }
        }
        return true;
    }
}
