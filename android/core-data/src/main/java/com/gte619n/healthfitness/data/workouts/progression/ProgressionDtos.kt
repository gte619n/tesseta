package com.gte619n.healthfitness.data.workouts.progression

import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview

// Plain data classes; the Moshi KotlinJsonAdapterFactory (see NetworkModule)
// handles (de)serialization by reflection, matching the rest of the app.

data class PatternReviewDto(
    val pattern: String,
    val trend: String,
    val weeklySlopePct: Double,
    val fatigueIndex: Double,
    val currentTarget: Int,
    val proposedTarget: Int,
    val deload: Boolean,
    val reasoning: String,
) {
    fun toDomain() = PatternReview(
        pattern = pattern,
        trend = trend,
        weeklySlopePct = weeklySlopePct,
        fatigueIndex = fatigueIndex,
        currentTarget = currentTarget,
        proposedTarget = proposedTarget,
        deload = deload,
        reasoning = reasoning,
    )
}

data class BlockParametersDto(
    val mode: String,
    val expectedDriftPerDay: Double,
    val successCriterion: String,
    val manualOverride: Boolean,
    // Wire shape: pattern name → 2-element [min, max] list.
    val repRanges: Map<String, List<Int>>,
    val rirCaps: Map<String, Double>,
    val weeklyCeiling: Map<String, Int>,
) {
    fun toDomain() = BlockParameters(
        mode = mode,
        expectedDriftPerDay = expectedDriftPerDay,
        successCriterion = successCriterion,
        manualOverride = manualOverride,
        // Collapse the [min, max] list into a Pair; tolerate a malformed/short
        // list by falling back to whatever single value is present.
        repRanges = repRanges.mapValues { (_, range) ->
            val min = range.getOrNull(0) ?: 0
            val max = range.getOrNull(1) ?: min
            min to max
        },
        rirCaps = rirCaps,
        weeklyCeiling = weeklyCeiling,
    )
}
