package com.gte619n.healthfitness.data.workouts.program.chat

import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a REAL designer `proposal` SSE payload (captured from the backend) with
 * the exact Moshi config NetworkModule uses, to prove the deep-program DTOs
 * tolerate a pre-commit proposal (null phaseId/dayId/blockId etc.). This is the
 * code path WorkoutDesignerViewModel.handleEvent runs on the `proposal` event.
 */
class ProposalParseTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun parsesRealProposalPayload() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("proposal.json")!!
            .bufferedReader().readText()

        val dto = moshi.adapter(ProgramProposalDto::class.java).fromJson(json)

        assertNotNull("proposal failed to parse", dto)
        val program = dto!!.program
        assertNotNull("program was null after parse", program)
        assertTrue("expected at least one phase", program!!.phases.isNotEmpty())
        assertTrue(
            "expected days in the first phase",
            program.phases.first().days.isNotEmpty(),
        )
    }
}
