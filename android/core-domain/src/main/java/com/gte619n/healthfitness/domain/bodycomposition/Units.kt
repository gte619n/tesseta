package com.gte619n.healthfitness.domain.bodycomposition

/**
 * Body composition unit constants. Backend stores Google Health weight in
 * kg; the UI surfaces lbs. DEXA report values are already in lbs (raw
 * report unit) and pass through.
 */
const val KG_TO_LB: Double = 2.20462

fun kgToLb(kg: Double?): Double? = kg?.let { it * KG_TO_LB }
