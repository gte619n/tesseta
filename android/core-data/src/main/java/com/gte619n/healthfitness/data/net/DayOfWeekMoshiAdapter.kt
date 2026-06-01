package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson

/**
 * Encodes [DayOfWeek] as lowercase (`"mon"`…`"sun"`) and decodes
 * case-insensitively, matching the backend's Jackson key (de)serializer
 * (IMPL-AND-03 [PR#8], IMPL-AND-06). Without this, weekly schedules and gym
 * hours maps fail to round-trip.
 */
class DayOfWeekMoshiAdapter {
    @ToJson fun toJson(value: DayOfWeek): String = value.name.lowercase()

    @FromJson fun fromJson(value: String): DayOfWeek = DayOfWeek.valueOf(value.uppercase())
}
