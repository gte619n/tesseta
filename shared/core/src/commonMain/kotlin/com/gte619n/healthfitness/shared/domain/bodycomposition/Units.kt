package com.gte619n.healthfitness.shared.domain.bodycomposition

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/bodycomposition/Units.kt (D3: Moshi→kotlinx.serialization).
 *
 * Pure conversion helper, carried verbatim.
 */

const val KG_TO_LB: Double = 2.20462

fun kgToLb(kg: Double?): Double? = kg?.let { it * KG_TO_LB }
