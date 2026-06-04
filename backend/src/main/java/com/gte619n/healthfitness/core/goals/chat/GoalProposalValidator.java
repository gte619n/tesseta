package com.gte619n.healthfitness.core.goals.chat;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a Gemini-proposed (or user-edited) Goal structure.
 *
 * <p>Validation never throws on bad content and never drops fields:
 * each offending value is preserved and an inline {@code validationError}
 * string is attached at the level it applies to, so the UI can flag it on
 * the proposal card (IMPL-12 acceptance criteria).
 *
 * <p>Rules enforced (IMPL-12 spec "Validation before the card renders" +
 * assumptions item 7):
 * <ul>
 *   <li>{@code metricKey} must be in the {@link MetricKey} registry.</li>
 *   <li>Comparator must be legal for the step kind:
 *       COUNT ⇒ only GTE / GT / EQ; THRESHOLD and SUSTAINED ⇒ any;
 *       MANUAL ⇒ no metric at all.</li>
 *   <li>{@code windowDays} present (and &gt; 0) iff kind == SUSTAINED.</li>
 *   <li>{@code countFrom} defaulted to "now" iff kind == COUNT.</li>
 *   <li>Phase target dates ordered (start ≤ end) and non-overlapping in
 *       sequence (strict-sequence Phases).</li>
 *   <li>Goal {@code targetDate} in the future.</li>
 * </ul>
 */
@Component
public class GoalProposalValidator {

    private final Clock clock;

    public GoalProposalValidator() {
        this(Clock.systemUTC());
    }

    // Visible for tests so "now" is deterministic.
    public GoalProposalValidator(Clock clock) {
        this.clock = clock;
    }

    public GoalProposal validate(RawProposal raw) {
        if (raw == null) {
            return new GoalProposal(null, null, null, null, List.of(), "Proposal is empty");
        }

        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();

        // ---- Goal level ----
        String goalError = null;

        GoalDomain domain = null;
        if (raw.domain() != null && !raw.domain().isBlank()) {
            try {
                domain = GoalDomain.valueOf(raw.domain().trim());
            } catch (IllegalArgumentException e) {
                goalError = "Unknown domain '" + raw.domain() + "'";
            }
        }

        LocalDate targetDate = null;
        if (raw.targetDate() != null && !raw.targetDate().isBlank()) {
            try {
                targetDate = LocalDate.parse(raw.targetDate().trim());
                if (!targetDate.isAfter(today)) {
                    goalError = appendError(goalError, "targetDate must be in the future");
                }
            } catch (DateTimeParseException e) {
                goalError = appendError(goalError, "targetDate is not a valid date");
            }
        }

        if (raw.title() == null || raw.title().isBlank()) {
            goalError = appendError(goalError, "title is required");
        }

        // ---- Phases ----
        List<GoalProposal.ProposalPhase> phases = new ArrayList<>();
        List<RawProposal.RawPhase> rawPhases = raw.phases() == null ? List.of() : raw.phases();

        // Track the previous phase's end date for non-overlap checking.
        LocalDate prevEnd = null;
        for (RawProposal.RawPhase rp : rawPhases) {
            String phaseError = null;

            if (rp.title() == null || rp.title().isBlank()) {
                phaseError = appendError(phaseError, "phase title is required");
            }

            LocalDate start = null;
            LocalDate end = null;
            if (rp.targetStartDate() != null && !rp.targetStartDate().isBlank()) {
                try {
                    start = LocalDate.parse(rp.targetStartDate().trim());
                } catch (DateTimeParseException e) {
                    phaseError = appendError(phaseError, "phase targetStartDate is not a valid date");
                }
            }
            if (rp.targetEndDate() != null && !rp.targetEndDate().isBlank()) {
                try {
                    end = LocalDate.parse(rp.targetEndDate().trim());
                } catch (DateTimeParseException e) {
                    phaseError = appendError(phaseError, "phase targetEndDate is not a valid date");
                }
            }
            if (start != null && end != null && start.isAfter(end)) {
                phaseError = appendError(phaseError, "phase start date must be on or before its end date");
            }
            // Strict sequence: this phase must not start before the
            // previous phase ends.
            if (start != null && prevEnd != null && start.isBefore(prevEnd)) {
                phaseError = appendError(phaseError, "phase dates overlap the previous phase");
            }
            if (end != null) {
                prevEnd = end;
            }

            // ---- Steps ----
            List<GoalProposal.ProposalStep> steps = new ArrayList<>();
            List<RawProposal.RawStep> rawSteps = rp.steps() == null ? List.of() : rp.steps();
            for (RawProposal.RawStep rs : rawSteps) {
                steps.add(validateStep(rs, now));
            }

            phases.add(new GoalProposal.ProposalPhase(
                rp.title(), rp.description(), start, end, steps, phaseError));
        }

        return new GoalProposal(
            raw.title(), raw.description(), domain, targetDate, phases, goalError);
    }

    private GoalProposal.ProposalStep validateStep(RawProposal.RawStep rs, Instant now) {
        String stepError = null;

        StepKind kind = null;
        if (rs.kind() == null || rs.kind().isBlank()) {
            stepError = appendError(stepError, "step kind is required");
        } else {
            try {
                kind = StepKind.valueOf(rs.kind().trim());
            } catch (IllegalArgumentException e) {
                stepError = appendError(stepError, "unknown step kind '" + rs.kind() + "'");
            }
        }
        if (rs.title() == null || rs.title().isBlank()) {
            stepError = appendError(stepError, "step title is required");
        }

        RawProposal.RawMetric rm = rs.metric();

        // MANUAL must not carry a metric; non-MANUAL must.
        if (kind == StepKind.MANUAL) {
            if (rm != null) {
                stepError = appendError(stepError, "MANUAL steps must not have a metric");
            }
            return new GoalProposal.ProposalStep(rs.title(), kind, null, stepError);
        }

        if (kind != null && rm == null) {
            stepError = appendError(stepError, "kind " + kind + " requires a metric");
            return new GoalProposal.ProposalStep(rs.title(), kind, null, stepError);
        }

        if (rm == null) {
            // kind itself was invalid/missing; nothing more to validate on the metric.
            return new GoalProposal.ProposalStep(rs.title(), kind, null, stepError);
        }

        GoalProposal.ProposalMetric metric = validateMetric(rm, kind, now);
        return new GoalProposal.ProposalStep(rs.title(), kind, metric, stepError);
    }

    private GoalProposal.ProposalMetric validateMetric(
        RawProposal.RawMetric rm, StepKind kind, Instant now) {

        String metricError = null;

        // metricKey ∈ registry
        MetricKey key = MetricKey.fromKey(rm.metricKey());
        if (rm.metricKey() == null || rm.metricKey().isBlank()) {
            metricError = appendError(metricError, "metricKey is required");
        } else if (key == null) {
            metricError = appendError(metricError, "unknown metricKey '" + rm.metricKey() + "'");
        }

        // comparator parse
        Comparator comparator = null;
        if (rm.comparator() == null || rm.comparator().isBlank()) {
            metricError = appendError(metricError, "comparator is required");
        } else {
            try {
                comparator = Comparator.valueOf(rm.comparator().trim());
            } catch (IllegalArgumentException e) {
                metricError = appendError(metricError, "unknown comparator '" + rm.comparator() + "'");
            }
        }

        // comparator legality for kind: COUNT ⇒ GTE / GT / EQ only.
        if (kind == StepKind.COUNT && comparator != null
            && !(comparator == Comparator.GTE || comparator == Comparator.GT || comparator == Comparator.EQ)) {
            metricError = appendError(metricError,
                "COUNT steps allow only GTE, GT, or EQ comparators");
        }

        if (rm.targetValue() == null) {
            metricError = appendError(metricError, "targetValue is required");
        }

        // windowDays present iff SUSTAINED
        Integer windowDays = rm.windowDays();
        if (kind == StepKind.SUSTAINED) {
            if (windowDays == null || windowDays <= 0) {
                metricError = appendError(metricError,
                    "SUSTAINED steps require windowDays > 0");
            }
        } else if (windowDays != null) {
            metricError = appendError(metricError,
                "windowDays is only allowed for SUSTAINED steps");
            windowDays = null;
        }

        // countFrom defaulted to now iff COUNT; not allowed otherwise.
        Instant countFrom = null;
        if (kind == StepKind.COUNT) {
            if (rm.countFrom() != null && !rm.countFrom().isBlank()) {
                try {
                    countFrom = Instant.parse(rm.countFrom().trim());
                } catch (DateTimeParseException e) {
                    // Try a bare date (YYYY-MM-DD) as a convenience.
                    try {
                        countFrom = LocalDate.parse(rm.countFrom().trim())
                            .atStartOfDay().toInstant(ZoneOffset.UTC);
                    } catch (DateTimeParseException e2) {
                        metricError = appendError(metricError, "countFrom is not a valid timestamp");
                    }
                }
            }
            if (countFrom == null && metricError == null) {
                countFrom = now;   // default to "now"
            } else if (countFrom == null) {
                countFrom = now;   // still default even if other errors exist
            }
        } else if (rm.countFrom() != null && !rm.countFrom().isBlank()) {
            metricError = appendError(metricError, "countFrom is only allowed for COUNT steps");
        }

        return new GoalProposal.ProposalMetric(
            rm.metricKey(), comparator, rm.targetValue(), windowDays, countFrom, metricError);
    }

    private static String appendError(String existing, String add) {
        if (existing == null || existing.isBlank()) return add;
        return existing + "; " + add;
    }
}
