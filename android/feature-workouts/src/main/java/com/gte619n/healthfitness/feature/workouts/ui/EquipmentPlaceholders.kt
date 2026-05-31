package com.gte619n.healthfitness.feature.workouts.ui

import androidx.annotation.DrawableRes
import com.gte619n.healthfitness.ui.R

/**
 * Maps a free-form equipment [category] string to a per-category placeholder
 * vector drawable in `core-ui`. Used while an equipment image is PENDING/FAILED
 * or has no [com.gte619n.healthfitness.domain.workouts.Equipment.imageUrl].
 *
 * Matching is case-insensitive and substring-based; the first match wins.
 * Unknown / null categories fall back to a generic dumbbell silhouette.
 */
@DrawableRes
fun equipmentPlaceholderRes(category: String?): Int {
    val c = category?.lowercase().orEmpty()
    return when {
        c.contains("cardio") -> R.drawable.ic_equipment_cardio
        c.contains("cable") -> R.drawable.ic_equipment_cable
        c.contains("bodyweight") -> R.drawable.ic_equipment_bodyweight
        c.contains("bench") -> R.drawable.ic_equipment_bench
        c.contains("machine") -> R.drawable.ic_equipment_machine
        c.contains("free") ||
            c.contains("weight") ||
            c.contains("dumbbell") ||
            c.contains("barbell") -> R.drawable.ic_equipment_free_weights
        else -> R.drawable.ic_equipment_generic
    }
}
