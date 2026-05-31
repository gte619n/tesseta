package com.gte619n.healthfitness.data.net

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.time.Instant
import java.time.LocalDate

/**
 * Moshi adapters for java.time types used across the feature DTOs (IMPL-AND-00).
 * The backend (Spring Boot + Jackson JavaTimeModule, timestamps-as-ISO) emits
 * `LocalDate` as `yyyy-MM-dd` and `Instant` as ISO-8601. See the follow-up
 * questions doc — if any endpoint emits epoch millis instead, extend [Instant]
 * here with a numeric `@FromJson`.
 */
class LocalDateAdapter {
    @ToJson fun toJson(value: LocalDate): String = value.toString()

    @FromJson fun fromJson(value: String): LocalDate = LocalDate.parse(value)
}

class InstantAdapter {
    @ToJson fun toJson(value: Instant): String = value.toString()

    @FromJson fun fromJson(value: String): Instant = Instant.parse(value)
}
