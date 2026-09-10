package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.domain.nutrition.AlcoholInfo
import com.gte619n.healthfitness.domain.nutrition.DrinkSession
import com.gte619n.healthfitness.domain.nutrition.DrinkTally
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.ServingSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IMPL-DRINK-01 — the pure drink-logging plan: D6 session-day back-dating and D22
 * multiplier scaling, plus the card's secondary-line / duration formatting.
 */
class DrinkSessionTest {

    // A Negroni-ish drink: 90 ml serving, 210 kcal, 0 g sugar, 1.5 std drinks.
    // macrosPer100g so a 90 ml serving × 1.0 = 210 kcal (see IL-4).
    private val negroni = Food(
        foodId = "d1",
        name = "Negroni",
        category = "drink",
        macrosPer100g = Macros(
            caloriesKcal = 210.0 * 100.0 / 90.0,
            carbsGrams = 6.0 * 100.0 / 90.0,
            sugarGrams = 0.0,
        ),
        servingSizes = listOf(ServingSize("1 glass", 90.0)),
        defaultServingIndex = 0,
        source = "CATALOG",
        status = "ACTIVE",
        imageStatus = "READY",
        imageUrl = "http://img",
        alcohol = AlcoholInfo(
            abvPercent = 24.0,
            servingVolumeMl = 90.0,
            alcoholGrams = 21.0,
            standardDrinks = 1.5,
        ),
    )

    private val fridaySession = DrinkSession(
        active = true,
        startedAtMillis = 0L,
        sessionDay = "2026-09-11", // Friday
        loggedDrinks = emptyList(),
    )

    @Test
    fun `plain tap freezes one serving of macros and standard drinks`() {
        val plan = planDrinkLog(negroni, quantity = 1.0, session = fridaySession, entryId = "e1", atMillis = 0L)
        assertEquals(210.0, plan.request.macros.caloriesKcal!!, 1e-6)
        assertEquals(1.5, plan.logged.standardDrinks, 1e-9)
        assertEquals(210.0, plan.logged.kcal, 1e-6)
        assertEquals("DRINKS", plan.request.meal)
        assertEquals("CATALOG", plan.request.source)
        assertEquals("d1", plan.request.foodId)
    }

    @Test
    fun `multiplier scales macros and standard drinks linearly (D22)`() {
        val x2 = planDrinkLog(negroni, quantity = 2.0, session = fridaySession, entryId = "e2", atMillis = 0L)
        assertEquals(420.0, x2.request.macros.caloriesKcal!!, 1e-6)
        assertEquals(3.0, x2.logged.standardDrinks, 1e-9)
        assertEquals(2.0, x2.request.quantity, 1e-9)

        val half = planDrinkLog(negroni, quantity = 0.5, session = fridaySession, entryId = "e3", atMillis = 0L)
        assertEquals(105.0, half.request.macros.caloriesKcal!!, 1e-6)
        assertEquals(0.75, half.logged.standardDrinks, 1e-9)
    }

    @Test
    fun `entry is back-dated to the session start day (D6)`() {
        // Logged "at 01:00 Saturday" but the session started Friday → dated Friday.
        val plan = planDrinkLog(negroni, quantity = 1.0, session = fridaySession, entryId = "e4", atMillis = 0L)
        assertEquals("2026-09-11", plan.date)
    }

    @Test
    fun `alcohol snapshot scales linearly`() {
        val scaled = negroni.alcohol!!.scaled(2.0)
        assertEquals(180.0, scaled.servingVolumeMl!!, 1e-9)
        assertEquals(42.0, scaled.alcoholGrams!!, 1e-9)
        assertEquals(3.0, scaled.standardDrinks!!, 1e-9)
        assertEquals(24.0, scaled.abvPercent!!, 1e-9) // ABV unchanged
    }

    @Test
    fun `secondary line omits budget when no target`() {
        val tally = DrinkTally(count = 2, standardDrinks = 2.5, kcal = 420.0, sugar = 1.0, carbs = 12.0)
        val line = secondaryLine(tally, target = null, kcalLoggedToday = 999.0)
        assertEquals("1 g sugar · 12 g carbs", line)
    }

    @Test
    fun `secondary line shows remaining budget when target exists`() {
        val tally = DrinkTally(count = 1, standardDrinks = 1.5, kcal = 210.0, sugar = 0.0, carbs = 6.0)
        val line = secondaryLine(tally, target = 2000.0, kcalLoggedToday = 640.0)
        assertEquals("0 g sugar · 6 g carbs · 1,360 kcal of today's budget", line)
    }

    @Test
    fun `remaining budget floors at zero`() {
        val tally = DrinkTally(count = 5, standardDrinks = 6.5, kcal = 980.0, sugar = 2.0, carbs = 40.0)
        val line = secondaryLine(tally, target = 2000.0, kcalLoggedToday = 2500.0)
        assertEquals("2 g sugar · 40 g carbs · 0 kcal of today's budget", line)
    }

    @Test
    fun `duration formats hours and minutes`() {
        assertEquals("3h 45m", formatDuration(3 * 3_600_000L + 45 * 60_000L))
        assertEquals("40m", formatDuration(40 * 60_000L))
        assertEquals("0m", formatDuration(-5L))
    }

    @Test
    fun `standard drink display keeps one decimal`() {
        assertEquals("1.5", formatStd(1.5))
        assertEquals("3.0", formatStd(3.0))
    }

    // ---- IL-13: session tally ↔ day-view delete reconcile ------------------

    private fun logged(entryId: String, atMillis: Long = 0L) = DrinkSession.LoggedDrink(
        entryId = entryId,
        foodId = "d1",
        name = "Negroni",
        standardDrinks = 1.5,
        kcal = 210.0,
        sugar = 0.0,
        carbs = 6.0,
        quantity = 1.0,
        atMillis = atMillis,
    )

    // The tally re-derives from loggedDrinks, so removing the reconciled ids gives
    // the count the card would show after the store pops them.
    private fun tallyAfterReconcile(session: DrinkSession, activeIds: Set<String>): Int {
        val drop = reconcileTally(session, activeIds, nowMillis = 1_000_000L, graceMillis = 4_000L)
        val remaining = session.loggedDrinks.filterNot { it.entryId in drop }
        return DrinkTally.of(remaining).count
    }

    @Test
    fun `reconcile drops a session drink whose entry was deleted from the day view`() {
        // 3 logged, all settled (atMillis well before now); the mirror now has only
        // e1 + e3 active (e2 was deleted from the day view).
        val session = DrinkSession(
            active = true,
            startedAtMillis = 0L,
            sessionDay = "2026-09-11",
            loggedDrinks = listOf(logged("e1"), logged("e2"), logged("e3")),
        )
        val drop = reconcileTally(session, setOf("e1", "e3"), nowMillis = 1_000_000L)
        assertEquals(listOf("e2"), drop)
        // Tally drops from 3 to 2.
        assertEquals(3, DrinkTally.of(session.loggedDrinks).count)
        assertEquals(2, tallyAfterReconcile(session, setOf("e1", "e3")))
    }

    @Test
    fun `reconcile is a no-op when every logged drink still has its entry`() {
        val session = DrinkSession(
            active = true,
            startedAtMillis = 0L,
            sessionDay = "2026-09-11",
            loggedDrinks = listOf(logged("e1"), logged("e2")),
        )
        assertEquals(emptyList<String>(), reconcileTally(session, setOf("e1", "e2"), nowMillis = 1_000_000L))
        assertEquals(2, tallyAfterReconcile(session, setOf("e1", "e2")))
    }

    @Test
    fun `reconcile never reaps a just-logged drink still inside the settle window`() {
        // e2 was logged 1s ago (< 4s grace) and its add-entry op hasn't written the
        // mirror yet — it must NOT be dropped despite being absent from active ids.
        val session = DrinkSession(
            active = true,
            startedAtMillis = 0L,
            sessionDay = "2026-09-11",
            loggedDrinks = listOf(logged("e1", atMillis = 0L), logged("e2", atMillis = 999_000L)),
        )
        assertEquals(emptyList<String>(), reconcileTally(session, setOf("e1"), nowMillis = 1_000_000L, graceMillis = 4_000L))
    }

    @Test
    fun `reconcile does nothing for an inactive session`() {
        val ended = DrinkSession(
            active = false,
            startedAtMillis = 0L,
            sessionDay = "2026-09-11",
            loggedDrinks = listOf(logged("e1")),
        )
        assertEquals(emptyList<String>(), reconcileTally(ended, emptySet(), nowMillis = 1_000_000L))
    }
}
