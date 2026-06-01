package com.gte619n.healthfitness.feature.workouts.ui

/**
 * 15-minute-increment time options ("00:00".."23:45"), mirroring web's
 * `HoursEditor` `TIME_OPTIONS`. Values are stored 24-hour "HH:mm"; labels are
 * 12-hour for display.
 */
object TimeOptions {
    val VALUES: List<String> = buildList {
        for (h in 0..23) {
            for (m in listOf(0, 15, 30, 45)) {
                add("%02d:%02d".format(h, m))
            }
        }
    }

    /** "13:30" -> "1:30 PM"; falls back to the raw value if unparsable. */
    fun label(value: String): String {
        val parts = value.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return value
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return value
        val period = if (h < 12) "AM" else "PM"
        val hour12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "%d:%02d %s".format(hour12, m, period)
    }
}
