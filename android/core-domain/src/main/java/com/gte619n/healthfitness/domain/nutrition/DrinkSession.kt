package com.gte619n.healthfitness.domain.nutrition

/**
 * IMPL-DRINK-01 (§4.3) — the device-local, bounded "night out" a user tracks from
 * the home Drink card. NOT synced (D7): it lives only in on-device storage and
 * survives process death / reboot. The only synced artifacts are the individual
 * drink nutrition entries this session enqueues.
 *
 * @param active whether a night is currently being tracked.
 * @param startedAtMillis epoch millis of Start (the D21 "Started <day> <time>").
 * @param sessionDay ISO yyyy-MM-dd of the start day; ALL entries are dated here,
 *   so a 01:00 drink in a Friday-night session lands on Friday's nutrition (D6).
 * @param loggedDrinks the drinks logged so far, in tap order — the source of the
 *   live tally and the on-card list.
 */
data class DrinkSession(
    val active: Boolean = false,
    val startedAtMillis: Long = 0L,
    val sessionDay: String = "",
    val loggedDrinks: List<LoggedDrink> = emptyList(),
) {
    /** A drink logged within a session. Macros are the frozen scaled snapshot. */
    data class LoggedDrink(
        /** Client-minted id of the nutrition entry this row created (for Undo/delete). */
        val entryId: String,
        val foodId: String,
        val name: String,
        /** Standard drinks for this log = drink default × [quantity]. */
        val standardDrinks: Double,
        val kcal: Double,
        val sugar: Double,
        val carbs: Double,
        /** The long-press multiplier (1.0 for a plain tap). */
        val quantity: Double,
        val atMillis: Long,
    )

    companion object {
        val NONE = DrinkSession()
    }
}

/**
 * Running totals for the live headline + secondary line (D14). Pure derivation of
 * [DrinkSession.loggedDrinks] — see [DrinkTally.of].
 */
data class DrinkTally(
    val count: Int = 0,
    val standardDrinks: Double = 0.0,
    val kcal: Double = 0.0,
    val sugar: Double = 0.0,
    val carbs: Double = 0.0,
) {
    companion object {
        val EMPTY = DrinkTally()

        /**
         * Fold a session's logged drinks into a tally. This is the single, pure
         * reducer the card headline reads (N taps → Σ; Undo pops a row → the sums
         * drop; a ×q log contributes q-scaled std drinks + macros). Kept free of
         * Android/UI deps so it's unit-tested directly.
         */
        fun of(drinks: List<DrinkSession.LoggedDrink>): DrinkTally = DrinkTally(
            count = drinks.size,
            standardDrinks = drinks.sumOf { it.standardDrinks },
            kcal = drinks.sumOf { it.kcal },
            sugar = drinks.sumOf { it.sugar },
            carbs = drinks.sumOf { it.carbs },
        )
    }
}

/**
 * The one-time End summary (D15/D21). [durationMillis] is `closeTime − start`
 * where closeTime is the user-editable close time (default now, must be ≥ start).
 */
data class DrinkSessionSummary(
    val count: Int,
    val standardDrinks: Double,
    val kcal: Double,
    val durationMillis: Long,
)
