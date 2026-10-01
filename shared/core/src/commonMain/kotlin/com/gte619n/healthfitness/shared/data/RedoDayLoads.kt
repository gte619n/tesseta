package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutDay

/**
 * IMPL-IOS-01 — shared redo-a-day load resumption (#283). Rebuilds a template
 * [day] so each prescription carries the user's last progressed LOAD (weight,
 * load-basis, engine rationale) drawn from this program's [completedSessions],
 * while keeping the template's rep band + set count.
 *
 * Single-sourced here so the concrete `WorkoutProgramRepository` (Phase 1C Room
 * impl) on each platform mints an offline redo identically — the client mirror of
 * the backend `WorkoutScheduleService.resumeDayLoads` and the Android repo's
 * private `resumeDayLoads`. Without it a redo run shows the author's week-one
 * weights (and the seed's 95 lb dumbbell), and — with no rationale — the logger
 * falls back to the stale last-session rep carry ("announces 15, snaps to 8").
 *
 * Only the LOAD travels: an exercise with no completed history — or whose last
 * completed prescription carried no target (bodyweight/timed, `targetWeightLbs`
 * null) — keeps its template prescription untouched. [completedSessions] should
 * already be scoped to the program (the caller reads them from the mirror).
 */
fun resumeDayLoads(day: WorkoutDay, completedSessions: List<ScheduledWorkout>): WorkoutDay {
    val completed = completedSessions
        .filter { it.status == ScheduledStatus.COMPLETED }
        .sortedByDescending { it.date }
    if (completed.isEmpty()) return day

    // Newest completed prescription per exercise that carried a real target.
    val resumeByExercise = HashMap<String, Prescription>()
    for (sw in completed) {
        val session = sw.session ?: continue
        for (block in session.blocks) {
            for (rx in block.prescriptions) {
                if (rx.targetWeightLbs != null && !resumeByExercise.containsKey(rx.exerciseId)) {
                    resumeByExercise[rx.exerciseId] = rx
                }
            }
        }
    }
    if (resumeByExercise.isEmpty()) return day

    return day.copy(
        blocks = day.blocks.map { block ->
            block.copy(
                prescriptions = block.prescriptions.map { rx ->
                    val prev = resumeByExercise[rx.exerciseId]
                    if (prev == null) {
                        rx
                    } else {
                        rx.copy(
                            targetWeightLbs = prev.targetWeightLbs,
                            loadBasis = prev.loadBasis,
                            rationale = prev.rationale,
                        )
                    }
                },
            )
        },
    )
}
