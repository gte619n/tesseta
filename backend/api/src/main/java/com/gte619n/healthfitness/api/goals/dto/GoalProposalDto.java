package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.chat.GoalProposal;
import com.gte619n.healthfitness.core.goals.chat.RawProposal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire shape for a Goal proposal — streamed to the chat UI after
 * validation, and POSTed back (user-edited) on commit.
 *
 * <p>Each level carries an optional {@code validationError} so the UI can
 * flag the offending field inline. Conversions:
 * <ul>
 *   <li>{@link #from(GoalProposal)} — validated proposal → DTO (for the
 *       SSE {@code proposal} event).</li>
 *   <li>{@link #toRaw()} — user-edited DTO → {@link RawProposal} for
 *       re-validation on commit (validationError fields are ignored on
 *       the way in; the validator recomputes them).</li>
 * </ul>
 */
public record GoalProposalDto(
    String title,
    String description,
    GoalDomain domain,
    LocalDate targetDate,
    List<PhaseDto> phases,
    String validationError
) {

    public record PhaseDto(
        String title,
        String description,
        LocalDate targetStartDate,
        LocalDate targetEndDate,
        List<StepDto> steps,
        String validationError
    ) {}

    public record StepDto(
        String title,
        StepKind kind,
        MetricDto metric,
        String validationError
    ) {}

    public record MetricDto(
        String metricKey,
        Comparator comparator,
        Double targetValue,
        Integer windowDays,
        Instant countFrom,
        String validationError
    ) {}

    public static GoalProposalDto from(GoalProposal p) {
        if (p == null) return null;
        List<PhaseDto> phases = new ArrayList<>();
        if (p.phases() != null) {
            for (GoalProposal.ProposalPhase ph : p.phases()) {
                List<StepDto> steps = new ArrayList<>();
                if (ph.steps() != null) {
                    for (GoalProposal.ProposalStep s : ph.steps()) {
                        MetricDto metric = null;
                        if (s.metric() != null) {
                            GoalProposal.ProposalMetric m = s.metric();
                            metric = new MetricDto(
                                m.metricKey(), m.comparator(), m.targetValue(),
                                m.windowDays(), m.countFrom(), m.validationError());
                        }
                        steps.add(new StepDto(s.title(), s.kind(), metric, s.validationError()));
                    }
                }
                phases.add(new PhaseDto(
                    ph.title(), ph.description(), ph.targetStartDate(), ph.targetEndDate(),
                    steps, ph.validationError()));
            }
        }
        return new GoalProposalDto(
            p.title(), p.description(), p.domain(), p.targetDate(), phases, p.validationError());
    }

    /** Convert this (possibly user-edited) DTO into a RawProposal for validation. */
    public RawProposal toRaw() {
        List<RawProposal.RawPhase> rawPhases = new ArrayList<>();
        if (phases != null) {
            for (PhaseDto ph : phases) {
                List<RawProposal.RawStep> rawSteps = new ArrayList<>();
                if (ph.steps() != null) {
                    for (StepDto s : ph.steps()) {
                        RawProposal.RawMetric rawMetric = null;
                        if (s.metric() != null) {
                            MetricDto m = s.metric();
                            rawMetric = new RawProposal.RawMetric(
                                m.metricKey(),
                                m.comparator() != null ? m.comparator().name() : null,
                                m.targetValue(),
                                m.windowDays(),
                                m.countFrom() != null ? m.countFrom().toString() : null
                            );
                        }
                        rawSteps.add(new RawProposal.RawStep(
                            s.title(),
                            s.kind() != null ? s.kind().name() : null,
                            rawMetric));
                    }
                }
                rawPhases.add(new RawProposal.RawPhase(
                    ph.title(),
                    ph.description(),
                    ph.targetStartDate() != null ? ph.targetStartDate().toString() : null,
                    ph.targetEndDate() != null ? ph.targetEndDate().toString() : null,
                    rawSteps));
            }
        }
        return new RawProposal(
            title,
            description,
            domain != null ? domain.name() : null,
            targetDate != null ? targetDate.toString() : null,
            rawPhases);
    }
}
