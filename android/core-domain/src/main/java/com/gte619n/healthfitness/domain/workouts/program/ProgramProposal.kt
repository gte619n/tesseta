package com.gte619n.healthfitness.domain.workouts.program

/**
 * A streamed workout-program designer proposal (IMPL-AND-18). The backend's
 * `proposal` SSE event carries `{ program: <deep WorkoutProgram>, issues: [] }`.
 * The deep [program] is the same domain [WorkoutProgram] the read surface uses
 * (phases → days → blocks → prescriptions + embedded summaries), now additively
 * carrying `targetWeightLbs`/`loadBasis` per prescription and `nutritionGuidance`
 * per phase + program. [issues] are the validator's hard blockers (impossible
 * loads/equipment); [warnings] are the soft, override-able advisories (volume
 * past MRV, missing deload, steep ramp) — shown but never block a commit (R1).
 */
data class ProgramProposal(
    val program: WorkoutProgram,
    val issues: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)
