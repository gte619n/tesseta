package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.dao.WorkoutSessionDraftDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import com.gte619n.healthfitness.data.db.entity.WorkoutScheduledEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutSessionDraftEntity
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.workouts.program.ScheduledWorkoutDto
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramApi
import com.gte619n.healthfitness.data.workouts.program.toDomain
import com.gte619n.healthfitness.data.workouts.program.toDto
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * ADR-0012 (IMPL-AND-16) — the device-local active-session store + completion
 * upload.
 *
 * The in-progress session is a [WorkoutSessionDraftEntity] row in the
 * encrypted Room store: the UI's source of truth, fully functional offline.
 * The backend only ever sees outcomes — [finish]/[skip] (and the Decision-4
 * stale sweep) build the IMPL-16 D2 `CompleteSessionRequest` and route it
 * through the offline outbox as an UPDATE on the `workoutScheduled` mirror row
 * (`"<programId>/<scheduledId>"`):
 *  - the mirror row optimistically flips to the completed/skipped
 *    `ScheduledWorkoutDto` (PENDING) so calendars update instantly, and
 *  - the outbox carries the D2 request as its wire body
 *    ([MirrorRepositorySupport.updateLocalWithWire]), replayed as the
 *    idempotent `PUT .../sessions/{scheduledId}` with the usual
 *    `Idempotency-Key`/backoff machinery.
 *
 * Per D13 the latched kill-switch drops the write to a direct live-network
 * PUT ([WorkoutProgramApi.completeSession]) — in that mode neither Room nor
 * the outbox is the source of truth.
 */
class WorkoutSessionRepositoryImpl(
    private val api: WorkoutProgramApi,
    private val draftDao: WorkoutSessionDraftDao,
    private val scheduledDao: WorkoutScheduledDao,
    private val support: MirrorRepositorySupport,
    private val outbox: OutboxRepository,
    moshi: Moshi,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WorkoutSessionRepository {

    private val scheduledAdapter = moshi.adapter(ScheduledWorkoutDto::class.java)
    private val requestAdapter = moshi.adapter(CompleteSessionRequest::class.java)
    private val loggedAdapter = moshi.adapter<List<LoggedPrescriptionDto>>(
        Types.newParameterizedType(List::class.java, LoggedPrescriptionDto::class.java),
    )

    override fun observeDraft(programId: String, scheduledId: String): Flow<WorkoutSessionDraft?> =
        draftDao.observe(programId, scheduledId).map { it?.toDomain() }

    override fun observeDrafts(): Flow<List<WorkoutSessionDraft>> =
        draftDao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun start(
        programId: String,
        scheduledId: String,
    ): Result<WorkoutSessionDraft> = withContext(io) {
        runCatching {
            // Resume an in-flight draft rather than restarting it (ADR-0012 D1).
            draftDao.getByKey(programId, scheduledId)?.toDomain()
                ?.let { return@runCatching it }

            val row = scheduledDao.getById(mirrorId(programId, scheduledId))
                ?: error("Scheduled session $programId/$scheduledId is not mirrored locally")
            val dto = decodeScheduled(row.payloadJson)
                ?: error("Scheduled session $programId/$scheduledId payload is undecodable")
            val now = clock()
            val entity = WorkoutSessionDraftEntity(
                programId = programId,
                scheduledId = scheduledId,
                startedAt = now,
                lastActivityAt = now,
                status = DraftStatus.ACTIVE.name,
                // Re-encode (rather than reuse payloadJson verbatim) so the
                // snapshot is exactly the DTO shape regardless of mirror-source
                // extras.
                sessionJson = scheduledAdapter.toJson(dto),
                loggedJson = EMPTY_LOGGED_JSON,
            )
            draftDao.upsert(entity)
            entity.toDomain() ?: error("Draft for $programId/$scheduledId failed to decode")
        }
    }

    override suspend fun updateSets(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        sets: List<LoggedSet>,
    ): Result<WorkoutSessionDraft> = withContext(io) {
        runCatching {
            val entity = draftDao.getByKey(programId, scheduledId)
                ?: error("No active draft for $programId/$scheduledId")
            val logged = decodeLogged(entity.loggedJson)
                .associateBy { PrescriptionKey(it.blockId, it.orderIndex) }
                .toMutableMap()
            if (sets.isEmpty()) {
                logged.remove(key)
            } else {
                logged[key] = LoggedPrescriptionDto(
                    blockId = key.blockId,
                    orderIndex = key.orderIndex,
                    sets = sets.map { it.toDto() },
                )
            }
            val updated = entity.copy(
                loggedJson = loggedAdapter.toJson(logged.values.toList()),
                lastActivityAt = clock(),
            )
            draftDao.upsert(updated)
            updated.toDomain() ?: error("Draft for $programId/$scheduledId failed to decode")
        }
    }

    override suspend fun finish(programId: String, scheduledId: String): Result<Unit> =
        withContext(io) {
            runCatching {
                val entity = draftDao.getByKey(programId, scheduledId)
                    ?: error("No active draft for $programId/$scheduledId")
                val completedAt = Instant.ofEpochMilli(clock())
                uploadCompleted(entity, completedAt)
                draftDao.delete(programId, scheduledId)
            }
        }

    override suspend fun skip(programId: String, scheduledId: String): Result<Unit> =
        withContext(io) {
            runCatching {
                // D4: SKIPPED clears actuals — no completedAt/duration/logged.
                val request = CompleteSessionRequest(status = ScheduledStatus.SKIPPED.name)
                uploadOutcome(
                    programId = programId,
                    scheduledId = scheduledId,
                    request = request,
                    fallbackSessionJson = draftDao.getByKey(programId, scheduledId)?.sessionJson,
                )
                draftDao.delete(programId, scheduledId)
            }
        }

    override suspend fun discard(programId: String, scheduledId: String): Result<Unit> =
        withContext(io) {
            runCatching { draftDao.delete(programId, scheduledId) }
        }

    // ---- IMPL-16 Q3: "restore into logger" recovery for parked completions ----

    override fun observeParkedCompletions(): Flow<List<ParkedCompletion>> =
        combine(
            outbox.parked(MirrorTables.WORKOUT_SCHEDULED),
            draftDao.observeAll(),
            scheduledDao.observeActive(),
        ) { parked, drafts, mirrored ->
            val draftKeys = drafts.map { it.programId to it.scheduledId }.toSet()
            val mirrorById = mirrored.associateBy { it.id }
            parked.mapNotNull { row ->
                val request = row.payloadJson?.let { decodeRequest(it) }
                    ?: return@mapNotNull null
                val programId = row.entityId.substringBefore('/')
                val scheduledId = row.entityId.substringAfter('/')
                // Single owner (N4): a draft already in flight for this session
                // owns its UI — its finish supersedes the parked payload in the
                // outbox chain, so no recovery affordance is shown beside it.
                if (programId to scheduledId in draftKeys) return@mapNotNull null
                val snapshot = mirrorById[row.entityId]?.let { decodeScheduled(it.payloadJson) }
                val keys = snapshot.prescriptionKeys()
                ParkedCompletion(
                    programId = programId,
                    scheduledId = scheduledId,
                    status = runCatching { ScheduledStatus.valueOf(request.status) }
                        .getOrDefault(ScheduledStatus.COMPLETED),
                    completedAt = request.completedAt,
                    loggedSetCount = request.logged.sumOf { it.sets.size },
                    orphanedSetCount = request.logged
                        .filter { PrescriptionKey(it.blockId, it.orderIndex) !in keys }
                        .sumOf { it.sets.size },
                    sessionAvailable = snapshot?.session != null,
                    dayLabel = snapshot?.dayLabel?.takeIf { it.isNotBlank() },
                )
            }
        }

    override suspend fun restoreParked(
        programId: String,
        scheduledId: String,
    ): Result<WorkoutSessionDraft> = withContext(io) {
        runCatching {
            val id = mirrorId(programId, scheduledId)
            // N4: never create a second upload owner beside an in-flight draft.
            check(draftDao.getByKey(programId, scheduledId) == null) {
                "A session draft is already in flight for $id"
            }
            val parked = outbox.parkedForEntity(id)
            val request = parked.lastOrNull()?.payloadJson?.let { decodeRequest(it) }
                ?: error("No parked completion for $id")
            val mirrorRow = scheduledDao.getById(id)
                ?.takeIf { it.status != SyncRowStatus.ARCHIVED.name }
                ?: error("Scheduled session $id is no longer mirrored locally")
            val current = decodeScheduled(mirrorRow.payloadJson)?.takeIf { it.session != null }
                ?: error("Scheduled session $id has no session snapshot to restore against")

            // Map the rejected actuals onto the CURRENT snapshot by
            // (blockId, orderIndex); orphaned entries cannot be re-uploaded
            // (the backend 400s unknown keys, D2) and their count was surfaced
            // on the ParkedCompletion before the user confirmed.
            val keys = current.prescriptionKeys()
            val matched = request.logged.filter {
                PrescriptionKey(it.blockId, it.orderIndex) in keys && it.sets.isNotEmpty()
            }
            val now = clock()
            val startedAt = request.startedAtMillis() ?: now
            val cleared = current.withOutcomeCleared()
            val entity = WorkoutSessionDraftEntity(
                programId = programId,
                scheduledId = scheduledId,
                startedAt = startedAt,
                // The user just acted: a fresh 24h window keeps the stale sweep
                // from instantly re-finalizing a restored multi-day-old session.
                lastActivityAt = now,
                status = DraftStatus.ACTIVE.name,
                sessionJson = scheduledAdapter.toJson(cleared),
                loggedJson = loggedAdapter.toJson(matched),
            )
            draftDao.upsert(entity)
            // The draft is the single owner again (N4): the parked row goes away…
            parked.forEach { outbox.deleteMutation(it.mutationId) }
            // …and our optimistic local completion is reverted so calendars stop
            // showing an outcome the server rejected (left SYNCED+clean so the
            // next pull replaces it with the server's canonical row).
            revertOptimisticOutcome(mirrorRow)
            entity.toDomain() ?: error("Restored draft for $id failed to decode")
        }
    }

    override suspend fun discardParked(programId: String, scheduledId: String): Result<Unit> =
        withContext(io) {
            runCatching {
                val id = mirrorId(programId, scheduledId)
                val parked = outbox.parkedForEntity(id)
                if (parked.isEmpty()) return@runCatching
                parked.forEach { outbox.deleteMutation(it.mutationId) }
                scheduledDao.getById(id)?.let { revertOptimisticOutcome(it) }
                Unit
            }
        }

    /**
     * Undo the optimistic completion [uploadOutcome] wrote, if it is still ours
     * (dirty). A row a pull already reconciled is the server's truth — leave it.
     */
    private suspend fun revertOptimisticOutcome(row: WorkoutScheduledEntity) {
        if (!row.dirty) return
        val dto = decodeScheduled(row.payloadJson) ?: return
        scheduledDao.upsert(
            row.copy(
                payloadJson = scheduledAdapter.toJson(dto.withOutcomeCleared()),
                dirty = false,
                syncState = SyncRowState.SYNCED.name,
            ),
        )
    }

    /** `completedAt − durationSeconds`, else the earliest per-set timestamp. */
    private fun CompleteSessionRequest.startedAtMillis(): Long? {
        completedAt?.let { at ->
            durationSeconds?.let { return at.toEpochMilli() - it * 1000L }
        }
        return logged.asSequence()
            .flatMap { it.sets }
            .mapNotNull { it.completedAt }
            .minOrNull()
            ?.toEpochMilli()
    }

    private fun ScheduledWorkoutDto?.prescriptionKeys(): Set<PrescriptionKey> =
        this?.session?.blocks
            ?.flatMap { block ->
                block.prescriptions.map { PrescriptionKey(block.blockId, it.orderIndex) }
            }
            ?.toSet()
            .orEmpty()

    /** Strip a (rejected or restored-over) outcome back to a planned snapshot. */
    private fun ScheduledWorkoutDto.withOutcomeCleared(): ScheduledWorkoutDto = copy(
        status = ScheduledStatus.PLANNED.name,
        completedAt = null,
        durationSeconds = null,
        session = session?.let { day ->
            day.copy(
                blocks = day.blocks.map { block ->
                    block.copy(
                        prescriptions = block.prescriptions.map {
                            it.copy(loggedSets = emptyList())
                        },
                    )
                },
            )
        },
    )

    override suspend fun finalizeStaleDrafts(): Result<WorkoutSessionRepository.StaleDraftResult> =
        withContext(io) {
            runCatching {
                val cutoff = clock() - STALE_AFTER_MILLIS
                var finalized = 0
                var discarded = 0
                for (entity in draftDao.listIdleBefore(cutoff)) {
                    val logged = decodeLogged(entity.loggedJson)
                    if (logged.sumOf { it.sets.size } == 0) {
                        // Nothing real to save — drop the stale shell (ADR-0012 D4).
                        draftDao.delete(entity.programId, entity.scheduledId)
                        discarded++
                        continue
                    }
                    // Finalize as COMPLETED at the last set's completedAt (falling
                    // back to the draft's last activity for sets logged without a
                    // timestamp). Per-draft runCatching: one failed upload must not
                    // block the rest of the sweep — the draft stays for the next pass.
                    runCatching {
                        val completedAt = logged.asSequence()
                            .flatMap { it.sets }
                            .mapNotNull { it.completedAt }
                            .maxOrNull()
                            ?: Instant.ofEpochMilli(entity.lastActivityAt)
                        uploadCompleted(entity, completedAt)
                        draftDao.delete(entity.programId, entity.scheduledId)
                        finalized++
                    }
                }
                WorkoutSessionRepository.StaleDraftResult(finalized, discarded)
            }
        }

    /** Build + upload the COMPLETED outcome for a draft (finish + stale sweep). */
    private suspend fun uploadCompleted(entity: WorkoutSessionDraftEntity, completedAt: Instant) {
        val durationSeconds = ((completedAt.toEpochMilli() - entity.startedAt) / 1000L)
            .coerceAtLeast(0L)
            .toInt()
        val request = CompleteSessionRequest(
            status = ScheduledStatus.COMPLETED.name,
            completedAt = completedAt,
            durationSeconds = durationSeconds,
            logged = decodeLogged(entity.loggedJson).filter { it.sets.isNotEmpty() },
        )
        uploadOutcome(
            programId = entity.programId,
            scheduledId = entity.scheduledId,
            request = request,
            fallbackSessionJson = entity.sessionJson,
        )
    }

    /**
     * Route one outcome upsert: optimistic mirror update + outbox enqueue
     * (normal path), or a direct live PUT when the kill-switch is latched.
     */
    private suspend fun uploadOutcome(
        programId: String,
        scheduledId: String,
        request: CompleteSessionRequest,
        fallbackSessionJson: String?,
    ) {
        if (support.killSwitchOn()) {
            api.completeSession(programId, scheduledId, request)
            return
        }
        val id = mirrorId(programId, scheduledId)
        // Prefer the live mirror row; fall back to the draft's snapshot if the
        // mirror was wiped/resynced under the draft.
        val base = scheduledDao.getById(id)?.let { decodeScheduled(it.payloadJson) }
            ?: fallbackSessionJson?.let { decodeScheduled(it) }
            ?: error("No local scheduled session for $id")
        support.updateLocalWithWire(
            table = MirrorTables.WORKOUT_SCHEDULED,
            id = id,
            payloadJson = scheduledAdapter.toJson(base.withOutcome(request)),
            wirePayloadJson = requestAdapter.toJson(request),
            lastUpdate = clock(),
        )
    }

    /**
     * Apply an outcome to the mirrored DTO so calendars/history reflect it
     * instantly: status + outcome fields, and the logged sets merged into
     * their `(blockId, orderIndex)` prescriptions (cleared for SKIPPED).
     */
    private fun ScheduledWorkoutDto.withOutcome(
        request: CompleteSessionRequest,
    ): ScheduledWorkoutDto {
        val byKey = request.logged.associateBy { it.blockId to it.orderIndex }
        return copy(
            status = request.status,
            completedAt = request.completedAt,
            durationSeconds = request.durationSeconds,
            session = session?.let { day ->
                day.copy(
                    blocks = day.blocks.map { block ->
                        block.copy(
                            prescriptions = block.prescriptions.map { prescription ->
                                prescription.copy(
                                    loggedSets = byKey[block.blockId to prescription.orderIndex]
                                        ?.sets
                                        .orEmpty(),
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun WorkoutSessionDraftEntity.toDomain(): WorkoutSessionDraft? {
        val scheduled = decodeScheduled(sessionJson) ?: return null
        return WorkoutSessionDraft(
            programId = programId,
            scheduledId = scheduledId,
            startedAt = Instant.ofEpochMilli(startedAt),
            lastActivityAt = Instant.ofEpochMilli(lastActivityAt),
            status = DraftStatus.ACTIVE,
            scheduled = scheduled.toDomain(),
            logged = decodeLogged(loggedJson).associate { entry ->
                PrescriptionKey(entry.blockId, entry.orderIndex) to
                    entry.sets.map { it.toDomain() }
            },
        )
    }

    private fun decodeScheduled(json: String): ScheduledWorkoutDto? =
        runCatching { scheduledAdapter.fromJson(json) }.getOrNull()

    private fun decodeRequest(json: String): CompleteSessionRequest? =
        runCatching { requestAdapter.fromJson(json) }.getOrNull()

    private fun decodeLogged(json: String): List<LoggedPrescriptionDto> =
        runCatching { loggedAdapter.fromJson(json) }.getOrNull().orEmpty()

    companion object {
        /** The IMPL-AND-20 sync id of one scheduled session's mirror row. */
        fun mirrorId(programId: String, scheduledId: String): String =
            "$programId/$scheduledId"

        /**
         * ADR-0012 Decision 4: a draft idle for **more than** 24h is stale
         * (exactly-24h drafts survive until the next sweep).
         */
        const val STALE_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000

        private const val EMPTY_LOGGED_JSON = "[]"
    }
}
