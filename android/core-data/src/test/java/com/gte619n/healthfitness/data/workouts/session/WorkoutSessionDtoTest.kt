package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.data.workouts.program.LoggedSetDto
import com.gte619n.healthfitness.data.workouts.program.PrescriptionDto
import com.gte619n.healthfitness.data.workouts.program.ScheduledWorkoutDto
import com.gte619n.healthfitness.data.workouts.program.toDomain
import com.gte619n.healthfitness.data.workouts.program.toDto
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ADR-0012 / IMPL-17 D2+D3 — locks the completion-request wire shape and the
 * LoggedSet expansion (`rpe`/`restSeconds`/`completedAt`, all nullable so
 * imported-history rows stay valid).
 */
class WorkoutSessionDtoTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(CompleteSessionRequest::class.java)

    @Test
    fun `completion request round-trips with full actuals`() {
        val request = CompleteSessionRequest(
            status = "COMPLETED",
            completedAt = Instant.parse("2026-06-08T18:32:00Z"),
            durationSeconds = 3210,
            logged = listOf(
                LoggedPrescriptionDto(
                    blockId = "b1",
                    orderIndex = 0,
                    sets = listOf(
                        LoggedSetDto(
                            weightLbs = 135.0,
                            reps = 8,
                            rpe = 8.5,
                            restSeconds = 90,
                            completedAt = Instant.parse("2026-06-08T18:05:00Z"),
                        ),
                    ),
                ),
            ),
        )

        val json = requestAdapter.toJson(request)
        assertTrue(json.contains("\"status\":\"COMPLETED\""))
        assertTrue(json.contains("\"completedAt\":\"2026-06-08T18:32:00Z\""))
        assertTrue(json.contains("\"durationSeconds\":3210"))
        assertTrue(json.contains("\"blockId\":\"b1\""))
        assertTrue(json.contains("\"rpe\":8.5"))

        assertEquals(request, requestAdapter.fromJson(json))
    }

    @Test
    fun `skipped request omits the absent actuals`() {
        val json = requestAdapter.toJson(CompleteSessionRequest(status = "SKIPPED"))
        assertFalse(json.contains("completedAt"))
        assertFalse(json.contains("durationSeconds"))
        // Moshi keeps the (empty) logged array; the backend treats it as none.
        assertEquals(
            CompleteSessionRequest(status = "SKIPPED"),
            requestAdapter.fromJson(json),
        )
    }

    @Test
    fun `weight-only logged set decodes with the new fields null`() {
        // Imported-history shape (ADR-0008): weight only — rpe/rest/completedAt
        // (and even reps) absent.
        val set = moshi.adapter(LoggedSetDto::class.java)
            .fromJson("""{"weightLbs":225.0}""")!!
        assertEquals(225.0, set.weightLbs!!, 0.0)
        assertNull(set.reps)
        assertNull(set.rpe)
        assertNull(set.restSeconds)
        assertNull(set.completedAt)
    }

    @Test
    fun `prescription carries loggedSets through to the domain`() {
        val prescription = moshi.adapter(PrescriptionDto::class.java).fromJson(
            """
            {"exerciseId":"ex1","orderIndex":0,"sets":3,
             "loggedSets":[{"weightLbs":135.0,"reps":8,"rpe":8.0,
               "restSeconds":120,"completedAt":"2026-06-08T18:05:00Z"}]}
            """.trimIndent(),
        )!!.toDomain()

        val logged = prescription.loggedSets.single()
        assertEquals(8, logged.reps)
        assertEquals(8.0, logged.rpe!!, 0.0)
        assertEquals(120, logged.restSeconds)
        assertEquals(Instant.parse("2026-06-08T18:05:00Z"), logged.completedAt)
    }

    @Test
    fun `logged set maps both directions losslessly`() {
        val domain = LoggedSet(
            weightLbs = 95.0,
            reps = 12,
            rpe = 7.5,
            restSeconds = 60,
            completedAt = Instant.parse("2026-06-08T18:10:00Z"),
        )
        assertEquals(domain, domain.toDto().toDomain())
    }

    @Test
    fun `scheduled workout decodes outcome fields and tolerates their absence`() {
        val adapter = moshi.adapter(ScheduledWorkoutDto::class.java)

        val completed = adapter.fromJson(
            """
            {"scheduledId":"2026-06-08_d1","date":"2026-06-08","status":"COMPLETED",
             "completedAt":"2026-06-08T18:32:00Z","durationSeconds":3210}
            """.trimIndent(),
        )!!
        assertEquals(Instant.parse("2026-06-08T18:32:00Z"), completed.completedAt)
        assertEquals(3210, completed.durationSeconds)

        // Pre-ADR-0012 payloads (no outcome fields) must still decode.
        val planned = adapter.fromJson(
            """{"scheduledId":"2026-06-08_d1","date":"2026-06-08","status":"PLANNED"}""",
        )!!
        assertNull(planned.completedAt)
        assertNull(planned.durationSeconds)
    }
}
