package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import java.time.Instant
import java.time.LocalDate

/**
 * Client-side mirror of the backend's
 * {@code ExercisePerformanceDigestService.lastSessionSets}: for each exercise,
 * the sets performed the LAST time it was done — the most recent COMPLETED
 * session's sets for that exerciseId, across all programs, in performed order.
 * Keyed by exerciseId; exercises with no history are absent.
 *
 * Folded purely over the device's local mirror of completed sessions so the
 * logger's "same as last time" prefill works offline and before the current
 * (possibly ad-hoc) session exists server-side — the network lookup 404s in that
 * window, this needs no round-trip. Kept a pure function so it stays unit-testable.
 */
fun lastSessionSets(sessions: List<ScheduledWorkout>): Map<String, List<LoggedSet>> {
    val byExercise = LinkedHashMap<String, MutableList<Pair<LocalDate, LoggedSet>>>()
    for (sw in sessions) {
        if (sw.status != ScheduledStatus.COMPLETED) continue
        val day = sw.session ?: continue
        val date = sw.date
        for (block in day.blocks) {
            for (rx in block.prescriptions) {
                val exerciseId = rx.exerciseId
                if (exerciseId.isBlank()) continue
                for (set in rx.loggedSets) {
                    byExercise.getOrPut(exerciseId) { mutableListOf() }.add(date to set)
                }
            }
        }
    }

    val out = LinkedHashMap<String, List<LoggedSet>>()
    for ((exerciseId, performed) in byExercise) {
        val lastDate = performed.maxOfOrNull { it.first } ?: continue
        val lastSets = performed
            .filter { it.first == lastDate }
            .sortedWith(compareBy(nullsLast(naturalOrder<Instant>())) { it.second.completedAt })
            .map { it.second }
        if (lastSets.isNotEmpty()) out[exerciseId] = lastSets
    }
    return out
}
