package com.gte619n.healthfitness.domain.workouts.adhoc

/**
 * A row in the ad-hoc workout Library (IMPL-ADHOC-01), distilled for the
 * read-only list the phone renders from the synced {@code adhocWorkouts} mirror.
 * The full workout body, generation, and the guided run are the remaining
 * Phase 4 feature work; the phone currently surfaces the templates a user
 * generated on the web.
 */
data class AdHocLibraryItem(
    val adhocId: String,
    val title: String,
    val summary: String?,
    val tags: List<String>,
    val pinned: Boolean,
    val estimatedDurationSeconds: Int?,
    val runCount: Int,
    val equipmentLabel: String?,
)
