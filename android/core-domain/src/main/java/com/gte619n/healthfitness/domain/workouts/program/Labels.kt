package com.gte619n.healthfitness.domain.workouts.program

/**
 * Friendly display strings for workout-program enums, mirroring the
 * MetricKeyLabels idiom in goals (IMPL-AND-12). Each falls back to a
 * humanized form of the raw value so an unknown key degrades gracefully.
 */
object BlockTypeLabels {
    private val labels = mapOf(
        BlockType.WARMUP to "Warm-up",
        BlockType.MOBILITY to "Mobility",
        BlockType.CARDIO to "Cardio",
        BlockType.MAIN to "Main",
        BlockType.ACCESSORY to "Accessory",
        BlockType.CORE to "Core",
        BlockType.COOLDOWN to "Cool-down",
        BlockType.STRETCH to "Stretch",
    )

    fun label(type: BlockType): String = labels[type] ?: type.name.titleCaseWord()
}

object MuscleLabels {
    // Friendly names for the common primary-muscle keys the backend emits
    // (lowercase). Anything not listed is title-cased from its raw key.
    private val labels = mapOf(
        "quadriceps" to "Quads",
        "quads" to "Quads",
        "hamstrings" to "Hamstrings",
        "glutes" to "Glutes",
        "calves" to "Calves",
        "chest" to "Chest",
        "pectorals" to "Chest",
        "back" to "Back",
        "lats" to "Lats",
        "trapezius" to "Traps",
        "traps" to "Traps",
        "shoulders" to "Shoulders",
        "deltoids" to "Delts",
        "delts" to "Delts",
        "biceps" to "Biceps",
        "triceps" to "Triceps",
        "forearms" to "Forearms",
        "core" to "Core",
        "abdominals" to "Abs",
        "abs" to "Abs",
        "obliques" to "Obliques",
        "lower_back" to "Lower back",
        "hip_flexors" to "Hip flexors",
    )

    fun label(muscle: String): String =
        labels[muscle.lowercase()] ?: muscle.replace('_', ' ').titleCasePhrase()
}

private fun String.titleCaseWord(): String =
    lowercase().replaceFirstChar { it.uppercase() }

private fun String.titleCasePhrase(): String =
    split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.titleCaseWord() }
