package com.gte619n.healthfitness.feature.workouts.program.chat

import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.data.net.Connectivity
import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.data.workouts.LocationRepository
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramCommitResult
import com.gte619n.healthfitness.data.workouts.program.chat.ScheduleDto
import com.gte619n.healthfitness.data.workouts.program.chat.WorkoutProgramChatRepository
import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.data.workouts.trt.TrtContextRepository
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class WorkoutDesignerViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val moshi: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val chatClient = mockk<WorkoutProgramChatClient>()
    private val chatRepo = mockk<WorkoutProgramChatRepository>(relaxed = true)
    private val locationRepo = mockk<LocationRepository>()
    private val goalsRepo = mockk<GoalsRepository>(relaxed = true)
    private val trtRepo = mockk<TrtContextRepository>()
    private val programRepo = mockk<WorkoutProgramRepository>(relaxed = true)
    private val connectivity = mockk<Connectivity>()

    /** A deep-program proposal JSON with the IMPL-18 additive fields populated. */
    private val proposalJson = """
        {
          "program": {
            "programId": null,
            "title": "5-Week Ease-In",
            "description": "Restarting TRT.",
            "goalId": null,
            "status": "DRAFT",
            "source": "AI_ASSISTED",
            "startDate": "2026-06-15",
            "trainingDays": ["mon","wed","fri"],
            "phases": [
              {
                "phaseId": "ph1",
                "title": "Accumulation",
                "orderIndex": 0,
                "status": "LOCKED",
                "weeks": 2,
                "nutritionGuidance": { "kcal": 2700, "proteinG": 185 },
                "days": [
                  {
                    "dayId": "d1",
                    "label": "Upper A",
                    "dayOfWeek": "mon",
                    "locationId": "g1",
                    "orderIndex": 0,
                    "blocks": [
                      {
                        "blockId": "b1",
                        "type": "MAIN",
                        "title": "Main",
                        "orderIndex": 0,
                        "prescriptions": [
                          {
                            "exerciseId": "ex-bench",
                            "orderIndex": 0,
                            "sets": 3,
                            "repsMin": 5,
                            "repsMax": 5,
                            "intensity": { "kind": "RPE", "value": 8.0 },
                            "targetWeightLbs": 185.0,
                            "loadBasis": "e1RM 225 x 80%, -10% layoff",
                            "exercise": { "exerciseId": "ex-bench", "name": "Bench Press" }
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ],
            "nutritionGuidance": { "kcal": 2600, "proteinG": 180, "note": "Slight surplus." }
          },
          "issues": ["Week-2 volume is near MRV for chest."],
          "warnings": ["Phase 'Build' jumps weekly volume 40% over the previous phase."]
        }
    """.trimIndent()

    private fun newViewModel(): WorkoutDesignerViewModel {
        every { connectivity.isOnline } returns MutableStateFlow(true)
        coEvery { locationRepo.list(any()) } returns Result.success(
            listOf(fakeLocation("g1", "Home Gym")),
        )
        coEvery { goalsRepo.goals(any()) } returns emptyList()
        coEvery { trtRepo.fetch() } returns Result.failure(RuntimeException("no labs"))
        return WorkoutDesignerViewModel(
            chatClient = chatClient,
            chatRepository = chatRepo,
            locationRepository = locationRepo,
            goalsRepository = goalsRepo,
            trtRepository = trtRepo,
            programRepository = programRepo,
            connectivity = connectivity,
            savedStateHandle = SavedStateHandle(),
            moshi = moshi,
        )
    }

    @Test
    fun `streaming a proposal builds an editor preserving weights and nutrition`() = runTest {
        every { chatClient.stream(any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Token("Here's a draft. "),
            ChatStreamEvent.Proposal(proposalJson),
            ChatStreamEvent.Done("thread-1"),
        )

        val vm = newViewModel()
        advanceUntilIdle()
        // Pick training days + a gym so the schedule is ready.
        vm.toggleTrainingDay(DayOfWeek.MON)
        vm.toggleTrainingDay(DayOfWeek.WED)
        advanceUntilIdle()

        vm.send("Design a 5-week ease-in program")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("thread-1", state.threadId)
        assertTrue(state.started)
        // The assistant message carries an editor with the parsed proposal.
        val assistant = state.messages.last()
        val editor = vm.editorFor(assistant.id)
        assertNotNull(editor)
        editor!!
        assertEquals("5-Week Ease-In", editor.title.value)
        assertEquals(2600, editor.nutritionGuidance?.kcal)
        // Per-prescription weight + basis survived parse → edit.
        val rx = editor.phases.single().days.single().blocks.single().prescriptions.single()
        assertEquals("185", rx.targetWeightLbs.value)
        assertEquals("e1RM 225 x 80%, -10% layoff", rx.loadBasis)
        // Validator issues (hard) and warnings (soft advisories) are both flagged.
        assertEquals(listOf("Week-2 volume is near MRV for chest."), vm.issuesFor(assistant.id))
        assertEquals(
            listOf("Phase 'Build' jumps weekly volume 40% over the previous phase."),
            vm.warningsFor(assistant.id),
        )
    }

    @Test
    fun `commit sends the schedule and surfaces the created programId`() = runTest {
        every { chatClient.stream(any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Proposal(proposalJson),
            ChatStreamEvent.Done("thread-1"),
        )
        val scheduleSlot = slot<ScheduleDto>()
        coEvery {
            chatRepo.commit(any(), any(), capture(scheduleSlot), any())
        } returns ProgramCommitResult.Created("prog-new")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.toggleTrainingDay(DayOfWeek.MON)
        advanceUntilIdle()
        vm.send("design it")
        advanceUntilIdle()

        val messageId = vm.state.value.messages.last().id
        vm.commit(messageId)
        advanceUntilIdle()

        assertEquals("prog-new", vm.state.value.committedProgramIds[messageId])
        assertNull(vm.state.value.error)
        // The committed schedule carries UPPERCASE day strings (locked contract).
        assertTrue(scheduleSlot.captured.trainingDays.contains("MON"))
        assertTrue(scheduleSlot.captured.dayLocations.containsKey("MON"))
        coVerify(exactly = 1) { chatRepo.commit(eq("thread-1"), any(), any(), any()) }
    }

    @Test
    fun `an invalid commit re-flags issues on the card`() = runTest {
        every { chatClient.stream(any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Proposal(proposalJson),
            ChatStreamEvent.Done("thread-1"),
        )
        coEvery {
            chatRepo.commit(any(), any(), any(), any())
        } returns ProgramCommitResult.Invalid(listOf("Bench load exceeds estimated 1RM."))

        val vm = newViewModel()
        advanceUntilIdle()
        vm.toggleTrainingDay(DayOfWeek.MON)
        advanceUntilIdle()
        vm.send("design it")
        advanceUntilIdle()

        val messageId = vm.state.value.messages.last().id
        vm.commit(messageId)
        advanceUntilIdle()

        assertNull(vm.state.value.committedProgramIds[messageId])
        assertEquals(listOf("Bench load exceeds estimated 1RM."), vm.issuesFor(messageId))
        assertNotNull(vm.state.value.error)
    }

    private fun fakeLocation(id: String, name: String) = Location(
        locationId = id,
        name = name,
        address = null,
        coverPhotoUrl = null,
        is24Hours = true,
        hours = null,
        amenities = emptyList(),
        equipmentIds = emptyList(),
        equipmentSpecs = emptyMap(),
        isDefault = false,
        isActive = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
