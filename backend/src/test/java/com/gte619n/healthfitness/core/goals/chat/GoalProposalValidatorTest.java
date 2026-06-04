package com.gte619n.healthfitness.core.goals.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalProposalValidatorTest {

    // Fixed "now" = 2026-05-28T00:00:00Z so date assertions are deterministic.
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-28T00:00:00Z"), ZoneOffset.UTC);
    private final GoalProposalValidator validator = new GoalProposalValidator(clock);

    private RawProposal.RawMetric metric(String key, String cmp, Double target,
                                         Integer windowDays, String countFrom) {
        return new RawProposal.RawMetric(key, cmp, target, windowDays, countFrom);
    }

    @Test
    void fullyValidProposalHasNoErrors() {
        RawProposal raw = new RawProposal(
            "Lower LDL", "Get LDL into optimal range", "CARDIOVASCULAR", "2026-12-01",
            List.of(
                new RawProposal.RawPhase("Foundation", "diet", "2026-06-01", "2026-08-01",
                    List.of(
                        new RawProposal.RawStep("LDL under 100", "THRESHOLD",
                            metric("blood.ldl", "LT", 100.0, null, null)),
                        new RawProposal.RawStep("Log it", "MANUAL", null)
                    )),
                new RawProposal.RawPhase("Sustain", "hold", "2026-08-02", "2026-11-01",
                    List.of(
                        new RawProposal.RawStep("RHR under 55 for 30d", "SUSTAINED",
                            metric("vitals.restingHr", "LT", 55.0, 30, null)),
                        new RawProposal.RawStep("40 workouts", "COUNT",
                            metric("workouts.count", "GTE", 40.0, null, null))
                    ))
            ));

        GoalProposal p = validator.validate(raw);
        assertTrue(p.isValid(), "expected valid, got: " + describeErrors(p));
        // countFrom defaulted to now for the COUNT step.
        GoalProposal.ProposalStep countStep = p.phases().get(1).steps().get(1);
        assertEquals(clock.instant(), countStep.metric().countFrom());
    }

    @Test
    void unknownMetricKeyIsFlaggedInline() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "OTHER", "2026-09-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "THRESHOLD",
                    metric("blood.madeUp", "LT", 1.0, null, null))))));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        String err = p.phases().get(0).steps().get(0).metric().validationError();
        assertNotNull(err);
        assertTrue(err.contains("unknown metricKey"), err);
        // Field preserved, not dropped.
        assertEquals("blood.madeUp", p.phases().get(0).steps().get(0).metric().metricKey());
    }

    @Test
    void countStepRejectsLtComparator() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "STRENGTH", "2026-09-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "COUNT",
                    metric("workouts.count", "LT", 40.0, null, null))))));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.phases().get(0).steps().get(0).metric().validationError()
            .contains("COUNT steps allow only"));
    }

    @Test
    void sustainedRequiresWindowDays() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "SLEEP", "2026-09-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "SUSTAINED",
                    metric("vitals.sleepScore", "GTE", 80.0, null, null))))));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.phases().get(0).steps().get(0).metric().validationError()
            .contains("windowDays"));
    }

    @Test
    void windowDaysOnlyAllowedForSustained() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "METABOLIC", "2026-09-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "THRESHOLD",
                    metric("blood.hba1c", "LT", 5.4, 30, null))))));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        GoalProposal.ProposalMetric m = p.phases().get(0).steps().get(0).metric();
        assertTrue(m.validationError().contains("only allowed for SUSTAINED"));
        // windowDays stripped from the normalized output.
        assertNull(m.windowDays());
    }

    @Test
    void manualStepMustNotHaveMetric() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "OTHER", "2026-09-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "MANUAL",
                    metric("blood.ldl", "LT", 100.0, null, null))))));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.phases().get(0).steps().get(0).validationError()
            .contains("MANUAL steps must not have a metric"));
    }

    @Test
    void targetDateInPastIsFlagged() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "OTHER", "2020-01-01",
            List.of());
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.validationError().contains("targetDate must be in the future"));
    }

    @Test
    void overlappingPhaseDatesAreFlagged() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "LONGEVITY", "2026-12-01",
            List.of(
                new RawProposal.RawPhase("P1", null, "2026-06-01", "2026-08-01", List.of()),
                // starts before P1 ends → overlap
                new RawProposal.RawPhase("P2", null, "2026-07-15", "2026-10-01", List.of())
            ));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.phases().get(1).validationError().contains("overlap"));
    }

    @Test
    void phaseStartAfterEndIsFlagged() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "OTHER", "2026-12-01",
            List.of(new RawProposal.RawPhase("P", null, "2026-08-01", "2026-06-01", List.of())));
        GoalProposal p = validator.validate(raw);
        assertFalse(p.isValid());
        assertTrue(p.phases().get(0).validationError().contains("on or before"));
    }

    @Test
    void countFromHonoredWhenProvided() {
        RawProposal raw = new RawProposal(
            "Goal", "d", "STRENGTH", "2026-12-01",
            List.of(new RawProposal.RawPhase("P", null, null, null,
                List.of(new RawProposal.RawStep("S", "COUNT",
                    metric("workouts.count", "GTE", 10.0, null, "2026-01-01T00:00:00Z"))))));
        GoalProposal p = validator.validate(raw);
        assertTrue(p.isValid(), describeErrors(p));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
            p.phases().get(0).steps().get(0).metric().countFrom());
    }

    @Test
    void emptyProposalIsInvalid() {
        GoalProposal p = validator.validate(null);
        assertFalse(p.isValid());
    }

    private static String describeErrors(GoalProposal p) {
        StringBuilder sb = new StringBuilder("goal=" + p.validationError());
        if (p.phases() != null) {
            for (GoalProposal.ProposalPhase ph : p.phases()) {
                sb.append(" | phase=").append(ph.validationError());
                if (ph.steps() != null) {
                    for (GoalProposal.ProposalStep s : ph.steps()) {
                        sb.append(" step=").append(s.validationError());
                        if (s.metric() != null) sb.append(" metric=").append(s.metric().validationError());
                    }
                }
            }
        }
        return sb.toString();
    }
}
