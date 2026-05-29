package com.gte619n.healthfitness.feature.goals

import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.Phase
import com.gte619n.healthfitness.domain.goals.PhaseStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Date parsing + display helpers. Backend dates arrive as ISO-8601 strings
// (LocalDate "2026-05-28" or Instant). We parse defensively and render in the
// caps-mono "MAY 28" style used across the app.

private val capsMonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** Parse a backend date string (LocalDate or Instant) to a LocalDate, or null. */
fun parseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return try {
        // Pure LocalDate "2026-05-28"
        LocalDate.parse(raw)
    } catch (_: Exception) {
        try {
            // Instant / OffsetDateTime "2026-05-28T..." — take the date part.
            LocalDate.parse(raw.substring(0, 10))
        } catch (_: Exception) {
            null
        }
    }
}

/** "MAY 28" caps display, or "—" if unparseable. */
fun formatCapsDate(raw: String?): String =
    parseDate(raw)?.format(capsMonthDay)?.uppercase(Locale.US) ?: "—"

/** "MAY 28 → JUL 12" range. */
fun formatDateRange(start: String?, end: String?): String =
    "${formatCapsDate(start)} → ${formatCapsDate(end)}"

/** True when a phase is past its target end date and not yet completed. */
fun Phase.isBehindSchedule(): Boolean {
    if (status == PhaseStatus.COMPLETED) return false
    val end = parseDate(targetEndDate) ?: return false
    return LocalDate.now().isAfter(end)
}

/** Derived progress numbers for a deep goal. */
data class GoalProgress(
    val totalPhases: Int,
    val activePhaseIndex: Int, // 1-based for display; 0 if none active
    val completedPhases: Int,
    val totalSteps: Int,
    val doneSteps: Int,
) {
    val stepFraction: Float
        get() = if (totalSteps == 0) 0f else doneSteps.toFloat() / totalSteps

    /** "Phase 2 of 4 · 7 of 19 steps" */
    val summary: String
        get() {
            val phasePart = if (activePhaseIndex > 0) {
                "Phase $activePhaseIndex of $totalPhases"
            } else if (completedPhases == totalPhases && totalPhases > 0) {
                "All $totalPhases phases complete"
            } else {
                "$totalPhases phases"
            }
            return "$phasePart · $doneSteps of $totalSteps steps"
        }
}

fun GoalDeep.progress(): GoalProgress {
    val ordered = phases.sortedBy { it.orderIndex }
    val activeIdx = ordered.indexOfFirst { it.status == PhaseStatus.ACTIVE }
    val completed = ordered.count { it.status == PhaseStatus.COMPLETED }
    val allSteps = ordered.flatMap { it.steps }
    return GoalProgress(
        totalPhases = ordered.size,
        activePhaseIndex = if (activeIdx >= 0) activeIdx + 1 else 0,
        completedPhases = completed,
        totalSteps = allSteps.size,
        doneSteps = allSteps.count { it.done },
    )
}
