package com.gte619n.healthfitness.feature.goals

import com.gte619n.healthfitness.domain.goals.Comparator
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.GoalDomain
import com.gte619n.healthfitness.domain.goals.GoalSource
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.goals.Phase
import com.gte619n.healthfitness.domain.goals.PhaseStatus
import com.gte619n.healthfitness.domain.goals.GoalProposal
import com.gte619n.healthfitness.domain.goals.ProposalMetric
import com.gte619n.healthfitness.domain.goals.ProposalPhase
import com.gte619n.healthfitness.domain.goals.ProposalStep
import com.gte619n.healthfitness.domain.goals.Step
import com.gte619n.healthfitness.domain.goals.StepKind
import com.gte619n.healthfitness.domain.goals.StepMetricBinding

// Fake data for @Preview composables. Not used at runtime.
object GoalsFixtures {

    val cardioGoal = Goal(
        goalId = "g1",
        title = "Lower cardiovascular risk markers into optimal range",
        description = "Get ApoB and LDL into the optimal band over two quarters.",
        domain = GoalDomain.CARDIOVASCULAR,
        status = GoalStatus.ACTIVE,
        startDate = "2026-01-05",
        targetDate = "2026-09-30",
        createdAt = "2026-01-05T10:00:00Z",
        updatedAt = "2026-05-20T10:00:00Z",
        completedAt = null,
        phaseOrder = listOf("p1", "p2", "p3", "p4"),
        source = GoalSource.AI_GENERATED,
    )

    val behindGoal = Goal(
        goalId = "g2",
        title = "12-week strength base",
        description = "Build a foundation of compound-lift volume.",
        domain = GoalDomain.STRENGTH,
        status = GoalStatus.ACTIVE,
        startDate = "2025-12-01",
        targetDate = "2026-03-01",
        createdAt = "2025-12-01T10:00:00Z",
        updatedAt = "2026-02-20T10:00:00Z",
        completedAt = null,
        phaseOrder = listOf("p1", "p2", "p3"),
        source = GoalSource.MANUAL,
    )

    val sleepGoal = Goal(
        goalId = "g3",
        title = "Improve nightly sleep score",
        description = "Sustain a sleep score above 85.",
        domain = GoalDomain.SLEEP,
        status = GoalStatus.ACTIVE,
        startDate = "2026-04-01",
        targetDate = "2026-08-01",
        createdAt = "2026-04-01T10:00:00Z",
        updatedAt = "2026-05-01T10:00:00Z",
        completedAt = null,
        phaseOrder = listOf("p1", "p2"),
        source = GoalSource.AI_ASSISTED,
    )

    val listGoals = listOf(cardioGoal, behindGoal, sleepGoal)

    // An AI proposal as it would arrive on the SSE `proposal` event. One step
    // carries a metric validationError to exercise inline flagging on the card.
    val proposal = GoalProposal(
        title = "Get LDL under 100",
        description = "A two-phase plan to bring LDL into the optimal range.",
        domain = GoalDomain.CARDIOVASCULAR,
        targetDate = "2026-12-01",
        phases = listOf(
            ProposalPhase(
                title = "Establish a Zone 2 cardio base",
                description = "Build aerobic base over 8 weeks.",
                targetStartDate = "2026-06-01",
                targetEndDate = "2026-08-01",
                steps = listOf(
                    ProposalStep(
                        title = "Log 24 Zone 2 workouts",
                        kind = StepKind.COUNT,
                        metric = ProposalMetric("workouts.count", Comparator.GTE, 24.0),
                    ),
                    ProposalStep(title = "Schedule a follow-up panel", kind = StepKind.MANUAL),
                ),
            ),
            ProposalPhase(
                title = "Confirm LDL in range",
                description = "Re-test and verify.",
                targetStartDate = "2026-08-01",
                targetEndDate = "2026-12-01",
                steps = listOf(
                    ProposalStep(
                        title = "LDL under 100",
                        kind = StepKind.THRESHOLD,
                        metric = ProposalMetric(
                            "blood.ldl", Comparator.LT, 100.0,
                            validationError = "Example inline error: confirm the target.",
                        ),
                    ),
                ),
            ),
        ),
    )

    val deepGoal = GoalDeep(
        goalId = "g1",
        title = "Lower cardiovascular risk markers into optimal range",
        description = "Get ApoB and LDL into the optimal band over two quarters " +
            "by establishing a Zone 2 base and dialing in nutrition.",
        domain = GoalDomain.CARDIOVASCULAR,
        status = GoalStatus.ACTIVE,
        startDate = "2026-01-05",
        targetDate = "2026-09-30",
        createdAt = "2026-01-05T10:00:00Z",
        updatedAt = "2026-05-20T10:00:00Z",
        completedAt = null,
        phaseOrder = listOf("p1", "p2", "p3", "p4"),
        source = GoalSource.AI_GENERATED,
        phases = listOf(
            Phase(
                phaseId = "p1",
                goalId = "g1",
                title = "Baseline & labs",
                description = "Establish a starting point.",
                orderIndex = 0,
                status = PhaseStatus.COMPLETED,
                targetStartDate = "2026-01-05",
                targetEndDate = "2026-02-01",
                completedAt = "2026-02-01T10:00:00Z",
                stepOrder = listOf("s1", "s2"),
                steps = listOf(
                    Step("s1", "p1", "g1", "Order a full lipid panel", 0, StepKind.MANUAL, done = true),
                    Step("s2", "p1", "g1", "Record baseline weight", 1, StepKind.MANUAL, done = true),
                ),
            ),
            Phase(
                phaseId = "p2",
                goalId = "g1",
                title = "Establish Zone 2 cardio base",
                description = "Build aerobic base with consistent Zone 2 sessions.",
                orderIndex = 1,
                status = PhaseStatus.ACTIVE,
                targetStartDate = "2026-02-01",
                targetEndDate = "2026-05-01",
                completedAt = null,
                stepOrder = listOf("s3", "s4", "s5"),
                steps = listOf(
                    Step(
                        "s3", "p2", "g1", "Log 40 Zone 2 workouts", 0, StepKind.COUNT,
                        done = false,
                        metric = StepMetricBinding("workouts.count", Comparator.GTE, 40.0, countFrom = "2026-02-01"),
                    ),
                    Step(
                        "s4", "p2", "g1", "Bring resting HR under 55", 1, StepKind.THRESHOLD,
                        done = true, doneAt = "2026-04-12T10:00:00Z",
                        metric = StepMetricBinding("vitals.restingHr", Comparator.LT, 55.0),
                        metricRegressed = true,
                    ),
                    Step("s5", "p2", "g1", "Schedule a midpoint check-in", 2, StepKind.MANUAL, done = false),
                ),
            ),
            Phase(
                phaseId = "p3",
                goalId = "g1",
                title = "Dial in nutrition",
                description = "Tighten protein and saturated fat targets.",
                orderIndex = 2,
                status = PhaseStatus.LOCKED,
                targetStartDate = "2026-05-01",
                targetEndDate = "2026-07-15",
                completedAt = null,
                stepOrder = listOf("s6"),
                steps = listOf(
                    Step(
                        "s6", "p3", "g1", "Average 150g protein over 7 days", 0, StepKind.SUSTAINED,
                        done = false,
                        metric = StepMetricBinding("nutrition.proteinAvg7d", Comparator.GTE, 150.0, windowDays = 7),
                    ),
                ),
            ),
            Phase(
                phaseId = "p4",
                goalId = "g1",
                title = "Re-test & confirm",
                description = "Confirm markers landed in range.",
                orderIndex = 3,
                status = PhaseStatus.LOCKED,
                targetStartDate = "2026-07-15",
                targetEndDate = "2026-09-30",
                completedAt = null,
                stepOrder = listOf("s7"),
                steps = listOf(
                    Step(
                        "s7", "p4", "g1", "ApoB under 80", 0, StepKind.THRESHOLD,
                        done = false,
                        metric = StepMetricBinding("blood.apoB", Comparator.LT, 80.0),
                    ),
                ),
            ),
        ),
    )
}
