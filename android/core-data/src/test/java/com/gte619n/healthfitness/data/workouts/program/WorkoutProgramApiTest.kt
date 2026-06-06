package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class WorkoutProgramApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: WorkoutProgramApi

    private val moshi: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WorkoutProgramApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `list parses shallow programs with lowercase day-of-week`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                [{"programId":"p1","title":"Strength","description":null,"goalId":"g1",
                  "status":"ACTIVE","source":"AI_GENERATED","startDate":"2026-05-01",
                  "trainingDays":["mon","wed","fri"],"totalWeeks":12,"phaseCount":3,
                  "completedPhaseCount":1,"createdAt":"2026-04-20T00:00:00Z",
                  "updatedAt":"2026-05-01T00:00:00Z"}]
                """.trimIndent(),
            ),
        )
        val result = api.list()
        assertEquals(1, result.size)
        assertEquals(listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI), result[0].trainingDays)
        assertEquals("/api/me/workout-programs", server.takeRequest().path)
    }

    @Test
    fun `get parses deep program with embedded summary`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"programId":"p1","title":"Strength","goalId":"g1","goalTitle":"Squat",
                 "status":"ACTIVE","source":"MANUAL","startDate":"2026-05-01",
                 "trainingDays":["mon"],"createdAt":"2026-04-20T00:00:00Z",
                 "updatedAt":"2026-05-01T00:00:00Z","completedAt":null,
                 "phases":[{"phaseId":"ph1","title":"Base","focus":"Volume","orderIndex":0,
                   "status":"ACTIVE","weeks":4,"deloadWeekIndex":3,
                   "targetStartDate":"2026-05-01","targetEndDate":"2026-05-28",
                   "days":[{"dayId":"d1","label":"Lower","dayOfWeek":"mon","locationId":"loc1",
                     "locationName":"Home","orderIndex":0,
                     "blocks":[{"blockId":"b1","type":"MAIN","title":"Main","orderIndex":0,
                       "prescriptions":[{"exerciseId":"ex1","orderIndex":0,"sets":3,"repsMin":8,
                         "repsMax":10,"durationSeconds":null,
                         "intensity":{"kind":"RPE","value":8.0},"restSeconds":90,"tempo":"3-1-1",
                         "notes":null,"deloadModifier":{"setsMultiplier":0.6,"intensityDelta":-1.0},
                         "loggedSets":null,
                         "exercise":{"exerciseId":"ex1","name":"Back Squat",
                           "primaryMuscles":["quadriceps"],"formCues":["Brace"],
                           "demoFrames":[{"phase":"START","imageUrl":"https://x/a.jpg",
                             "imageCandidates":[]}]}}]}]}]}]}
                """.trimIndent(),
            ),
        )
        val deep = api.get("p1")
        assertEquals("Squat", deep.goalTitle)
        val presc = deep.phases.single().days.single().blocks.single().prescriptions.single()
        assertEquals("Back Squat", presc.exercise?.name)
        assertEquals("RPE", presc.intensity?.kind)
        assertEquals("/api/me/workout-programs/p1", server.takeRequest().path)
    }

    @Test
    fun `deep decode tolerates explicit null fields the backend emits`() = runBlocking {
        // Spring MVC serializes explicit nulls. Imported-history session days have
        // a null locationId (and prescriptions carry the name in `notes`, with no
        // embedded exercise). The decode must not blow up on those nulls.
        server.enqueue(
            MockResponse().setBody(
                """
                {"programId":"imported-history","title":"Future.co Training",
                 "description":null,"goalId":null,"goalTitle":null,
                 "status":"COMPLETED","source":"MANUAL","startDate":"2023-03-20",
                 "trainingDays":[],"createdAt":"2023-03-20T00:00:00Z",
                 "updatedAt":"2023-03-20T00:00:00Z","completedAt":null,
                 "phases":[{"phaseId":"Phase_1","title":"Phase 1","focus":null,"orderIndex":0,
                   "status":"COMPLETED","weeks":4,"deloadWeekIndex":null,
                   "targetStartDate":"2023-03-20","targetEndDate":"2023-04-17",
                   "days":[{"dayId":"s0","label":"Push Day","dayOfWeek":"mon","locationId":null,
                     "locationName":null,"orderIndex":0,
                     "blocks":[{"blockId":"logged","type":"MAIN","title":"Logged","orderIndex":0,
                       "prescriptions":[{"exerciseId":"ex1","orderIndex":0,"sets":3,"repsMin":null,
                         "repsMax":null,"durationSeconds":null,"intensity":null,"restSeconds":null,
                         "tempo":null,"notes":"Bench Press","deloadModifier":null,
                         "loggedSets":[{"weightLbs":135.0,"reps":8}],"exercise":null}]}]}]}]}
                """.trimIndent(),
            ),
        )
        val deep = api.get("imported-history")
        val day = deep.phases.single().days.single()
        assertEquals("s0", day.dayId)
        val presc = day.blocks.single().prescriptions.single()
        assertEquals("Bench Press", presc.notes)
        assertEquals(1, presc.loggedSets.orEmpty().size)
    }

    @Test
    fun `calendar encodes from and to query params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                [{"scheduledId":"s1","date":"2026-06-01","phaseId":"ph1","dayId":"d1",
                  "dayLabel":"Lower","weekIndexInPhase":1,"isDeload":false,"locationId":"loc1",
                  "locationName":"Home","status":"PLANNED","session":null}]
                """.trimIndent(),
            ),
        )
        val result = api.calendar("p1", "2026-06-01", "2026-06-07")
        assertEquals(1, result.size)
        assertEquals("2026-06-01", result[0].date.toString())
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/api/me/workout-programs/p1/calendar"))
        assertTrue(path.contains("from=2026-06-01"))
        assertTrue(path.contains("to=2026-06-07"))
    }
}
