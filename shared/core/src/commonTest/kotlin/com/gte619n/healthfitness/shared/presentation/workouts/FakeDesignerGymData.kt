package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.ChatStreamEvent
import com.gte619n.healthfitness.shared.data.CreateLocationRequest
import com.gte619n.healthfitness.shared.data.Equipment
import com.gte619n.healthfitness.shared.data.EquipmentRepository
import com.gte619n.healthfitness.shared.data.GoalRef
import com.gte619n.healthfitness.shared.data.Location
import com.gte619n.healthfitness.shared.data.LocationRepository
import com.gte619n.healthfitness.shared.data.PendingUpload
import com.gte619n.healthfitness.shared.data.ProgramChatMessage
import com.gte619n.healthfitness.shared.data.ProgramChatThreadResponse
import com.gte619n.healthfitness.shared.data.ProgramCommitResult
import com.gte619n.healthfitness.shared.data.ProgramProposal
import com.gte619n.healthfitness.shared.data.ProgressionRepository
import com.gte619n.healthfitness.shared.data.ScheduleDto
import com.gte619n.healthfitness.shared.data.SseClient
import com.gte619n.healthfitness.shared.data.UpdateLocationRequest
import com.gte619n.healthfitness.shared.data.WorkoutGoalsRepository
import com.gte619n.healthfitness.shared.data.WorkoutProgramChatRepository
import com.gte619n.healthfitness.shared.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.shared.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.shared.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.shared.domain.workouts.progression.PatternReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) — commonTest fixtures for the designer /
 * progression / gym VMs. Plain KMP fakes (no MockK) authored against the
 * interfaces in `data/WorkoutDesignerGymRepositories.kt`.
 */

/** Deterministic id source (id-1, id-2, ...). */
class SequentialIdGenerator : IdGenerator {
    private var n = 0
    override fun next(): String = "id-${++n}"
}

/** Replays a scripted list of chat events as a cold flow (reused SSE contract). */
class FakeSseClient(private val script: List<ChatStreamEvent>) : SseClient {
    var lastBasePath: String? = null; private set
    var lastThreadId: String? = null; private set
    var lastMessage: String? = null; private set

    override fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent> {
        lastBasePath = basePath
        lastThreadId = threadId
        lastMessage = message
        return script.asFlow()
    }
}

class FakeWorkoutProgramChatRepository(
    private val threads: List<ProgramChatThreadResponse> = emptyList(),
    private val messages: List<ProgramChatMessage> = emptyList(),
    private val commitResult: ProgramCommitResult = ProgramCommitResult.Created("prog-1"),
) : WorkoutProgramChatRepository {
    val committed = mutableListOf<ProgramProposal>()
    val deleted = mutableListOf<String>()

    override suspend fun commit(
        threadId: String,
        program: ProgramProposal,
        schedule: ScheduleDto,
        goalId: String?,
    ): ProgramCommitResult {
        committed += program
        return commitResult
    }

    override suspend fun listThreads(): List<ProgramChatThreadResponse> = threads
    override suspend fun listMessages(threadId: String): List<ProgramChatMessage> = messages
    override suspend fun deleteThread(threadId: String) {
        deleted += threadId
    }
}

class FakeLocationRepository(
    private val locations: List<Location> = emptyList(),
) : LocationRepository {
    override suspend fun cachedList(): List<Location> = locations
    override suspend fun list(): Result<List<Location>> = Result.success(locations)
    override suspend fun cached(locationId: String): Location? = locations.firstOrNull { it.locationId == locationId }
    override suspend fun get(locationId: String): Result<Location> =
        locations.firstOrNull { it.locationId == locationId }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no such gym"))
    override suspend fun create(request: CreateLocationRequest): Result<Location> =
        Result.success(Location(locationId = "new-1", name = request.name))
    override suspend fun update(locationId: String, request: UpdateLocationRequest): Result<Unit> = Result.success(Unit)
    override suspend fun delete(locationId: String): Result<Unit> = Result.success(Unit)
    override suspend fun setDefault(locationId: String): Result<Unit> = Result.success(Unit)
    override suspend fun removeEquipment(locationId: String, equipmentId: String): Result<Unit> = Result.success(Unit)
    override suspend fun uploadCoverPhoto(locationId: String, file: PendingUpload): Result<String> = Result.success("")
    override suspend fun deleteCoverPhoto(locationId: String): Result<Unit> = Result.success(Unit)
}

class FakeEquipmentRepository(
    private val catalog: List<Equipment> = emptyList(),
) : EquipmentRepository {
    override suspend fun cached(equipmentId: String): Equipment? = catalog.firstOrNull { it.equipmentId == equipmentId }
    override suspend fun get(equipmentId: String): Result<Equipment> =
        catalog.firstOrNull { it.equipmentId == equipmentId }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no such equipment"))
}

class FakeWorkoutGoalsRepository(private val goals: List<GoalRef> = emptyList()) : WorkoutGoalsRepository {
    override suspend fun activeGoals(): List<GoalRef> = goals
}

/** Progression repo serving scripted results; each read can be forced to fail. */
class FakeProgressionRepository(
    private val review: Result<List<PatternReview>> = Result.success(emptyList()),
    private val block: Result<BlockParameters> = Result.success(sampleBlock()),
    private val strengthResult: Result<List<ExerciseStrength>> = Result.success(emptyList()),
    private val energy: EnergyBalance? = null,
    private val updatedBlock: Result<BlockParameters> = Result.success(sampleBlock()),
) : ProgressionRepository {
    var lastMode: String? = null; private set

    override suspend fun weekReview(): Result<List<PatternReview>> = review
    override suspend fun blockParameters(): Result<BlockParameters> = block
    override suspend fun strength(): Result<List<ExerciseStrength>> = strengthResult
    override suspend fun energyBalance(): Result<EnergyBalance?> = Result.success(energy)
    override suspend fun updateBlockParameters(mode: String): Result<BlockParameters> {
        lastMode = mode
        return updatedBlock
    }
}

fun sampleBlock(mode: String = "GAINING", manualOverride: Boolean = false): BlockParameters = BlockParameters(
    mode = mode,
    expectedDriftPerDay = 0.1,
    successCriterion = "ADD_LOAD",
    manualOverride = manualOverride,
    repRanges = mapOf("PUSH_HORIZONTAL" to (6 to 10)),
    rirCaps = mapOf("COMPOUND" to 2.0),
    weeklyCeiling = mapOf("PUSH_HORIZONTAL" to 18),
)
