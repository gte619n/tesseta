package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
import com.gte619n.healthfitness.shared.domain.goals.Phase
import com.gte619n.healthfitness.shared.domain.goals.Step
import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import com.gte619n.healthfitness.shared.domain.nutrition.MealGroup
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay

/**
 * IMPL-IOS-01 (#2 follow-up) — pure client-side assembly of the composite read models
 * (a nutrition day; a deep goal) from the flat per-collection mirror rows the sync
 * engine populates. Mirrors Android's server-side composition so the offline mirror
 * renders the same shapes the networked endpoints return. Pure + testable.
 */

/** Sum the six macros across entries (null treated as 0). */
internal fun sumMacros(entries: List<Entry>): Macros {
    fun total(select: (Macros) -> Double?): Double = entries.sumOf { select(it.macros) ?: 0.0 }
    return Macros(
        caloriesKcal = total { it.caloriesKcal },
        proteinGrams = total { it.proteinGrams },
        carbsGrams = total { it.carbsGrams },
        fatGrams = total { it.fatGrams },
        fiberGrams = total { it.fiberGrams },
        sugarGrams = total { it.sugarGrams },
    )
}

/**
 * Assemble `GET api/me/nutrition/{date}`'s shape from the mirrored entries + target:
 * the entries for [date], grouped into meals in display order with per-meal subtotals
 * and a day total.
 */
fun assembleNutritionDay(date: String, entries: List<Entry>, target: Macros?): NutritionDay {
    val forDay = entries.filter { it.date == date }
    val mealOrder = Meal.entries.withIndex().associate { (i, m) -> m.wire to i }
    val meals = forDay.groupBy { it.meal }
        .map { (meal, es) -> MealGroup(meal = meal, subtotal = sumMacros(es), entries = es) }
        .sortedBy { mealOrder[it.meal] ?: Int.MAX_VALUE }
    return NutritionDay(date = date, totals = sumMacros(forDay), target = target, meals = meals)
}

/**
 * Assemble the deep (roadmap) goal from the shallow [goal] row + its mirrored phases
 * and steps: phases for this goal in order, each with its steps in order.
 */
fun assembleGoalDeep(goal: Goal, phases: List<Phase>, steps: List<Step>): GoalDeep {
    val goalPhases = phases
        .filter { it.goalId == goal.goalId }
        .sortedBy { it.orderIndex }
        .map { phase ->
            phase.copy(steps = steps.filter { it.phaseId == phase.phaseId }.sortedBy { it.orderIndex })
        }
    return GoalDeep(
        goalId = goal.goalId,
        title = goal.title,
        description = goal.description,
        domain = goal.domain,
        status = goal.status,
        startDate = goal.startDate,
        targetDate = goal.targetDate,
        createdAt = goal.createdAt,
        updatedAt = goal.updatedAt,
        completedAt = goal.completedAt,
        phaseOrder = goal.phaseOrder,
        source = goal.source,
        phases = goalPhases,
    )
}
