package com.gte619n.healthfitness.domain.bodycomposition

const val KG_TO_LB: Double = 2.20462

fun kgToLb(kg: Double?): Double? = kg?.let { it * KG_TO_LB }
