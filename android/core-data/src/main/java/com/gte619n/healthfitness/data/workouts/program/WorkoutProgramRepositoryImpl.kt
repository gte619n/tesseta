package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.db.dao.WorkoutProgramDao
import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first workout-program viewer.
 *
 * The phone is read-only here (IMPL-AND-15), so there is no outbox: reads come
 * from the mirror (D8) and the network's only job is to fill it. The background
 * SyncEngine keeps `workoutPrograms` + `workoutScheduled` fresh via the delta
 * pull; these methods additionally fill on a cold miss so a screen opened before
 * the first full sync still renders.
 *
 * - [list] serves the `workoutPrograms` mirror (the program doc embeds the full
 *   phase/day/block tree, so the summary round-trips).
 * - [get] serves the deep DTO from the same mirror row when present (written by a
 *   prior fetch), else fetches + stores it. The raw delta-pulled doc may not
 *   decode to the deep DTO (the #7 raw-doc-vs-DTO gap shared across the branch);
 *   when it doesn't, the read self-heals from the network while online.
 * - [calendar] serves the `workoutScheduled` mirror filtered to this program
 *   (rows keyed `"<programId>/<scheduledId>"`, matching the sync id) and date
 *   range, filling for the program on a cold miss.
 *
 * Per D13, a latched kill-switch drops every read back to the live network.
 */
@Singleton
class WorkoutProgramRepositoryImpl @Inject constructor(
    private val api: WorkoutProgramApi,
    private val programDao: WorkoutProgramDao,
    private val scheduledDao: WorkoutScheduledDao,
    private val support: MirrorRepositorySupport,
    private val moshi: Moshi,
) : WorkoutProgramRepository {

    private val listAdapter = moshi.adapter(WorkoutProgramDto::class.java)
    private val deepAdapter = moshi.adapter(WorkoutProgramDeepDto::class.java)
    private val scheduledAdapter = moshi.adapter(ScheduledWorkoutDto::class.java)

    override suspend fun list(): Result<List<WorkoutProgram>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (support.killSwitchOn()) {
                    return@runCatching api.list().map { it.toDomain() }
                }
                if (programDao.observeActive().first().isEmpty()) fillPrograms()
                programDao.observeActive().first()
                    .mapNotNull { decodeProgram(it.payloadJson) }
                    .map { it.toDomain() }
            }
        }

    override suspend fun get(programId: String): Result<WorkoutProgram> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (support.killSwitchOn()) return@runCatching api.get(programId).toDomain()
                mirroredDeep(programId)?.toDomain() ?: refreshDeep(programId).toDomain()
            }
        }

    override suspend fun calendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<ScheduledWorkout>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (support.killSwitchOn()) {
                    return@runCatching api.calendar(programId, from.toString(), to.toString())
                        .map { it.toDomain() }
                }
                val prefix = "$programId/"
                val cached = scheduledDao.observeActive().first()
                    .filter { it.id.startsWith(prefix) }
                    .mapNotNull { decodeScheduled(it.payloadJson) }
                val source = cached.ifEmpty { fillScheduled(programId) }
                source
                    .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                    .sortedBy { it.date }
                    .map { it.toDomain() }
            }
        }

    /** Pull the full program list into the mirror as SYNCED rows. */
    private suspend fun fillPrograms() {
        val dtos = api.list()
        support.refreshInto(MirrorTables.WORKOUT_PROGRAMS, dtos.map { it.toRefreshRow() })
    }

    /** Fetch one program (deep) and store it in the mirror; returns the DTO. */
    private suspend fun refreshDeep(programId: String): WorkoutProgramDeepDto {
        val dto = api.get(programId)
        support.refreshInto(MirrorTables.WORKOUT_PROGRAMS, listOf(dto.toRefreshRow()))
        return dto
    }

    private suspend fun mirroredDeep(programId: String): WorkoutProgramDeepDto? =
        programDao.getById(programId)?.let { decodeDeep(it.payloadJson) }

    /** Fill the scheduled mirror for one program over its whole horizon. */
    private suspend fun fillScheduled(programId: String): List<ScheduledWorkoutDto> {
        val dtos = api.calendar(programId, "1970-01-01", "2999-12-31")
        support.refreshInto(
            MirrorTables.WORKOUT_SCHEDULED,
            dtos.map { it.toRefreshRow(programId) },
        )
        return dtos
    }

    private fun decodeProgram(json: String): WorkoutProgramDto? =
        runCatching { listAdapter.fromJson(json) }.getOrNull()

    private fun decodeDeep(json: String): WorkoutProgramDeepDto? =
        runCatching { deepAdapter.fromJson(json) }.getOrNull()

    private fun decodeScheduled(json: String): ScheduledWorkoutDto? =
        runCatching { scheduledAdapter.fromJson(json) }.getOrNull()

    private fun WorkoutProgramDto.toRefreshRow() = MirrorRepositorySupport.RefreshRow(
        id = programId,
        payloadJson = listAdapter.toJson(this),
        lastUpdate = updatedAt.toEpochMilli(),
    )

    private fun WorkoutProgramDeepDto.toRefreshRow() = MirrorRepositorySupport.RefreshRow(
        id = programId,
        payloadJson = deepAdapter.toJson(this),
        lastUpdate = updatedAt.toEpochMilli(),
    )

    private fun ScheduledWorkoutDto.toRefreshRow(programId: String) =
        MirrorRepositorySupport.RefreshRow(
            id = "$programId/$scheduledId",
            payloadJson = scheduledAdapter.toJson(this),
            lastUpdate = System.currentTimeMillis(),
        )
}
