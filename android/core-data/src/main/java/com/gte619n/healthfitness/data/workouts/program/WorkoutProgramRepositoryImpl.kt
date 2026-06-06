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
 * - [list] serves the `workoutPrograms` mirror and, while online, proactively
 *   upgrades every program to its assembled deep tree (see [cacheDeepForOffline])
 *   so the detail/workout screens render offline. The delta-pulled doc and the
 *   shallow list row both lack the assembled tree (no embedded exercise
 *   summaries / flattened trainingDays — the raw-doc-vs-DTO gap), so a row is
 *   only trusted once [isAssembledDeep] holds.
 * - [get] serves the mirrored deep doc only when it is a complete assembled tree;
 *   a shallow list row, a raw delta doc, or an absent row triggers a network
 *   fetch that persists the assembled deep for offline. Offline, it falls back to
 *   whatever is cached and only errors when nothing is cached at all.
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
                // Ensure each program's full deep tree (phases → days → blocks →
                // prescriptions + embedded exercise summaries) is cached, so the
                // detail/workout screens render offline. The delta-pulled doc and
                // the shallow list row both lack the assembled tree, so we upgrade
                // them from the network while online; offline failures are ignored
                // and the existing row is kept (best-effort).
                cacheDeepForOffline()
                programDao.observeActive().first()
                    .mapNotNull { decodeProgram(it.payloadJson) }
                    .map { it.toDomain() }
            }
        }

    override suspend fun get(programId: String): Result<WorkoutProgram> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (support.killSwitchOn()) return@runCatching api.get(programId).toDomain()
                val cached = mirroredDeep(programId)
                if (cached.isAssembledDeep()) return@runCatching cached!!.toDomain()
                // Shallow list row, raw delta doc, or absent: fetch the assembled
                // deep and persist it for offline. Offline → fall back to whatever
                // we have rather than failing; only surface the error if we have
                // nothing cached at all.
                try {
                    refreshDeep(programId).toDomain()
                } catch (e: Exception) {
                    cached?.toDomain() ?: throw e
                }
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

    /**
     * Upgrade any mirrored program that is not a complete assembled deep tree
     * (shallow list row, raw delta doc, or undecodable) by fetching it deep and
     * storing it. Best-effort: each fetch is independent and a network failure
     * (e.g. offline) leaves the existing row untouched. Self-limiting — once a
     * program is cached complete, [isAssembledDeep] is true and it is skipped.
     */
    private suspend fun cacheDeepForOffline() {
        val rows = programDao.observeActive().first()
        for (row in rows) {
            if (decodeDeep(row.payloadJson).isAssembledDeep()) continue
            runCatching { refreshDeep(row.id) }
        }
    }

    /**
     * True when a decoded deep doc actually carries the assembled tree — phases
     * present and every prescription has its embedded exercise summary. Shallow
     * list rows (no phases) and raw delta docs (phases but `exercise == null`)
     * return false so callers know to refresh from the network.
     */
    private fun WorkoutProgramDeepDto?.isAssembledDeep(): Boolean {
        val dto = this ?: return false
        if (dto.phases.isEmpty()) return false
        return dto.phases.all { phase ->
            phase.days.all { day ->
                day.blocks.all { block ->
                    block.prescriptions.all { it.exercise != null }
                }
            }
        }
    }

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
