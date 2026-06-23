package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.db.dao.WorkoutProgramDao
import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutHistoryPage
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
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
 *   whatever is cached and only errors when nothing is cached at all. Either way
 *   it [backfillDays]: a session-only program (e.g. imported history, whose stored
 *   phases have empty template days) has its days reconstructed from the cached
 *   schedule, mirroring the backend assembler so the workouts render offline.
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
    private val issuesAdapter = moshi.adapter<Map<String, List<String>>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Types.newParameterizedType(List::class.java, String::class.java),
        ),
    )

    /** Parse the `{ issues: [] }` body of a 422 activate response, or null. */
    private fun activationIssues(e: HttpException): List<String>? {
        if (e.code() != 422) return null
        val raw = e.response()?.errorBody()?.string() ?: return null
        val issues = runCatching { issuesAdapter.fromJson(raw)?.get("issues") }.getOrNull()
        return issues?.takeIf { it.isNotEmpty() }
    }

    override suspend fun list(): Result<List<WorkoutProgram>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (support.killSwitchOn()) {
                    return@runCatching api.list().map { it.toDomain() }
                }
                // Refresh from the backend when online so freshly created/committed
                // programs (which the mirror hasn't delta-pulled yet) show up
                // immediately; offline, keep whatever the mirror has. Previously
                // this only filled when the mirror was empty, so a just-committed
                // program never appeared until the next sync.
                runCatching { fillPrograms() }
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

    override fun observePrograms(): Flow<List<WorkoutProgram>> =
        support.observeLocalFirst(
            rows = programDao.observeActive(),
            decode = { decodeProgram(it)?.toDomain() },
            liveFallback = { api.list().map { it.toDomain() } },
            refresh = {
                // Fill the shallow list, then upgrade each to its deep tree so the
                // detail/workout screens render offline. Both best-effort.
                runCatching { fillPrograms() }
                cacheDeepForOffline()
            },
        )

    override fun observeProgram(programId: String): Flow<WorkoutProgram?> = channelFlow {
        if (support.killSwitchOn()) {
            send(runCatching { api.get(programId).toDomain() }.getOrNull())
            return@channelFlow
        }
        val refreshed = MutableStateFlow(false)
        launch {
            // Only hit the network when the cache isn't already a complete deep
            // tree (cache-first); always settle [refreshed] so a genuine miss
            // surfaces null instead of an endless spinner.
            if (!mirroredDeep(programId).isAssembledDeep()) runCatching { refreshDeep(programId) }
            refreshed.value = true
        }
        combine(programDao.observeActive(), scheduledDao.observeActive(), refreshed) { _, _, didRefresh ->
            val program = mirroredDeep(programId)?.toDomain()?.let { backfillDays(it) }
            program to didRefresh
        }.collect { (program, didRefresh) ->
            if (program != null || didRefresh) send(program)
        }
    }

    override suspend fun get(programId: String): Result<WorkoutProgram> =
        withContext(Dispatchers.IO) {
            runCatching {
                // The server's deep response already backfills session-only phases.
                if (support.killSwitchOn()) return@runCatching api.get(programId).toDomain()
                val cached = mirroredDeep(programId)
                if (cached.isAssembledDeep()) return@runCatching backfillDays(cached!!.toDomain())
                // Shallow list row, raw delta doc (empty template days / no embedded
                // exercise summaries), or absent: fetch the assembled deep and
                // persist it for offline. Offline → fall back to whatever we have,
                // backfilling session-only phases from the cached schedule; only
                // surface the error if we have nothing cached at all.
                try {
                    backfillDays(refreshDeep(programId).toDomain())
                } catch (e: Exception) {
                    backfillDays(cached?.toDomain() ?: throw e)
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

    override suspend fun workoutHistoryPage(page: Int, size: Int): Result<WorkoutHistoryPage> =
        withContext(Dispatchers.IO) {
            runCatching { api.workoutHistory(page = page, size = size).toDomain() }
        }

    override suspend fun nutritionGuidance(
        programId: String,
    ): Result<com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance?> =
        withContext(Dispatchers.IO) {
            runCatching { api.getNutritionGuidance(programId) }
        }

    override suspend fun applyNutritionTarget(
        programId: String,
    ): Result<com.gte619n.healthfitness.domain.nutrition.Macros> =
        withContext(Dispatchers.IO) {
            runCatching { api.applyNutritionTarget(programId) }
        }

    override fun observeCalendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<ScheduledWorkout>> = channelFlow {
        if (support.killSwitchOn()) {
            send(
                runCatching {
                    api.calendar(programId, from.toString(), to.toString()).map { it.toDomain() }
                }.getOrDefault(emptyList()),
            )
            return@channelFlow
        }
        val prefix = "$programId/"
        launch {
            // Cold-miss fill: only when nothing is cached for this program yet.
            // Subsequent freshness comes from the background SyncEngine pull and
            // local writes (activate/complete), which re-emit through the Flow.
            if (scheduledDao.observeActive().first().none { it.id.startsWith(prefix) }) {
                runCatching { fillScheduled(programId) }
            }
        }
        scheduledDao.observeActive()
            .map { rows ->
                rows.filter { it.id.startsWith(prefix) }
                    .mapNotNull { decodeScheduled(it.payloadJson) }
                    .map { it.toDomain() }
                    .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                    .sortedBy { it.date }
            }
            .collect { send(it) }
    }

    override suspend fun activate(programId: String): Result<List<ScheduledWorkout>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sessions = try {
                    api.activate(programId)
                } catch (e: HttpException) {
                    // 422 → the backend re-validated and returned { issues: [] }
                    // (same shape as the commit 422). Surface the actionable list
                    // so the detail screen can show the specific problems inline
                    // instead of a generic "couldn't activate" (IMPL-STAB G1).
                    activationIssues(e)?.let { throw ProgramActivationInvalidException(it) }
                    throw e
                }
                // Refresh the mirror so the detail/list reflect ACTIVE status and
                // the materialized "this week" sessions without waiting for a sync.
                runCatching { refreshDeep(programId) }
                runCatching {
                    support.refreshInto(
                        MirrorTables.WORKOUT_SCHEDULED,
                        sessions.map { it.toRefreshRow(programId) },
                    )
                }
                sessions.map { it.toDomain() }
            }
        }

    override suspend fun updateDetails(
        programId: String,
        title: String,
        description: String?,
    ): Result<WorkoutProgram> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = api.updateDetails(
                    programId,
                    UpdateProgramDetailsRequest(title = title, description = description),
                )
                // Store the updated deep tree so the detail/list reflect the new
                // title/description without waiting for a sync.
                support.refreshInto(MirrorTables.WORKOUT_PROGRAMS, listOf(dto.toRefreshRow()))
                dto.toDomain()
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
     * present, **every phase has days**, and every prescription has its embedded
     * exercise summary. Shallow list rows (no phases), raw delta docs (phases but
     * `exercise == null`), and session-only programs whose stored phases have
     * empty template days (e.g. imported history — its days live in the schedule)
     * all return false so callers refresh from the network and/or backfill from
     * the cached schedule. Note: an empty `days` list must NOT pass vacuously.
     */
    private fun WorkoutProgramDeepDto?.isAssembledDeep(): Boolean {
        val dto = this ?: return false
        if (dto.phases.isEmpty()) return false
        return dto.phases.all { phase ->
            phase.days.isNotEmpty() && phase.days.all { day ->
                day.blocks.all { block ->
                    block.prescriptions.all { it.exercise != null }
                }
            }
        }
    }

    /**
     * Backfill any phase that has no template days from the cached scheduled
     * sessions for this program — each performed session becomes a day under its
     * phase, ordered by date. Mirrors the backend assembler so a session-only
     * program (imported history) shows its workouts offline. A no-op when every
     * phase already has days or no sessions are cached.
     */
    private suspend fun backfillDays(program: WorkoutProgram): WorkoutProgram {
        if (program.phases.none { it.days.isEmpty() }) return program
        val byPhase = cachedScheduledFor(program.programId)
            .filter { it.session != null }
            .sortedBy { it.date }
            .groupBy { it.phaseId }
        if (byPhase.isEmpty()) return program
        val phases = program.phases.map { phase ->
            if (phase.days.isNotEmpty()) return@map phase
            val days = byPhase[phase.phaseId].orEmpty()
                .mapIndexedNotNull { index, sw -> sw.session?.copy(orderIndex = index) }
            if (days.isEmpty()) phase else phase.copy(days = days)
        }
        return program.copy(phases = phases)
    }

    /** Decoded scheduled sessions for a program from the mirror (no network). */
    private suspend fun cachedScheduledFor(programId: String): List<ScheduledWorkout> {
        val prefix = "$programId/"
        return scheduledDao.observeActive().first()
            .filter { it.id.startsWith(prefix) }
            .mapNotNull { decodeScheduled(it.payloadJson) }
            .map { it.toDomain() }
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
        id = programId.orEmpty(),
        payloadJson = deepAdapter.toJson(this),
        // updatedAt is nullable post-IMPL-18 (proposals carry none); committed
        // reads always set it, so fall back to now only defensively.
        lastUpdate = updatedAt?.toEpochMilli() ?: System.currentTimeMillis(),
    )

    private fun ScheduledWorkoutDto.toRefreshRow(programId: String) =
        MirrorRepositorySupport.RefreshRow(
            id = "$programId/$scheduledId",
            payloadJson = scheduledAdapter.toJson(this),
            lastUpdate = System.currentTimeMillis(),
        )
}
