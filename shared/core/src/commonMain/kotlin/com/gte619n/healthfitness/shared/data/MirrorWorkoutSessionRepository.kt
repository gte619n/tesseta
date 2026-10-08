package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.shared.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.shared.sync.MirrorTables
import com.gte619n.healthfitness.shared.sync.OutboxOp
import com.gte619n.healthfitness.shared.sync.OutboxStore
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * IMPL-IOS-01 Phase G — the iOS/KMP concrete [WorkoutSessionRepository], the port
 * of android/core-data/.../data/workouts/session/WorkoutSessionRepository.kt onto
 * the shared SQLDelight mirror + outbox rail (Phases B–D).
 *
 * ADR-0012: the in-progress session is a device-local DRAFT that survives process
 * death + works offline; the backend only ever learns the OUTCOME, via ONE
 * idempotent completion upsert routed through the offline outbox.
 *
 * ## Storage layout (mirror table `workoutScheduled`, id `"<programId>/<scheduledId>"`)
 *
 * A single mirror-row id is reused for BOTH the synced scheduled session (written
 * by the delta pull, decodes to [ScheduledWorkout]) AND the local live draft. They
 * are told apart by the row's `dirty` flag + payload shape:
 *  - a SYNCED/clean row carries the server's [ScheduledWorkout] JSON;
 *  - a DIRTY local row carries this file's [WorkoutSessionDraft] JSON.
 *
 * ## Per-set edits are LOCAL-ONLY (CRITICAL)
 *
 * [start]/[updateSets]/[markStarted] write the draft via the NON-enqueueing
 * [SqlDelightMirrorStore.writeLocal] — NOT the rail's [MirrorRepositorySupport]
 * createLocal/updateLocal (those enqueue an outbox op per call). A set-by-set
 * logging session must enqueue EXACTLY ONE outbox op, at [finish]/[skip], never one
 * per rep. [finish]/[skip] enqueue that single [CompleteSessionRequest] op directly
 * (routed by `OutboxEndpointRegistry.workoutSessions` → the idempotent
 * `PUT api/me/workout-programs/{programId}/sessions/{scheduledId}`) and then clear
 * the draft row. [discard] clears the draft locally with no outbox op.
 *
 * ## Draft serialization
 *
 * [WorkoutSessionDraft] is already `@Serializable`. Its `logged` map keys by the
 * structured [PrescriptionKey], which kotlinx.serialization JSON encodes as a
 * flat key/value array — valid and stable, so the draft is persisted as its own
 * JSON verbatim (no wrapper DTO). `kotlinx.datetime.Instant` carries the ISO
 * serializer the rest of the domain already relies on.
 *
 * ## Prescription sourcing (online-first-tolerant)
 *
 * [start] snapshots its [ScheduledWorkout] from the clean mirror row the sync pull
 * populated; on a cold miss it best-effort fetches the program calendar and mirrors
 * it. If neither resolves (offline + never synced) it returns `Result.failure` —
 * it never invents plan data.
 *
 * ## Deliberately minimal (recorded in §"Decisions for review")
 *
 * Parked-completion recovery ([observeParkedCompletions]/[restoreParked]/
 * [discardParked]) and [reset] need the outbox's "parked" (terminally-rejected op)
 * surface, which the shared [OutboxStore] does not yet expose (the Phase C drain
 * DROPS terminal 4xx instead of parking). Those return empty / best-effort no-ops
 * here; see the strategy doc.
 */
class MirrorWorkoutSessionRepository(
    private val mirror: SqlDelightMirrorStore,
    private val outbox: OutboxStore,
    private val engine: SyncEngine,
    private val client: HttpClient?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val now: () -> Instant = { Clock.System.now() },
    private val json: Json = DRAFT_JSON,
) : WorkoutSessionRepository {

    // ---- reads ------------------------------------------------------------

    override fun observeDraft(programId: String, scheduledId: String): Flow<WorkoutSessionDraft?> {
        val id = mirrorId(programId, scheduledId)
        return mirror.observeActiveRecords(MirrorTables.WORKOUT_SCHEDULED).map { records ->
            records.firstOrNull { it.id == id && it.dirty }?.let { decodeDraft(it.payloadJson) }
        }
    }

    override fun observeDrafts(): Flow<List<WorkoutSessionDraft>> =
        mirror.observeActiveRecords(MirrorTables.WORKOUT_SCHEDULED).map { records ->
            records.filter { it.dirty }.mapNotNull { decodeDraft(it.payloadJson) }
        }

    override suspend fun peekDraft(programId: String, scheduledId: String): WorkoutSessionDraft? =
        currentDraft(programId, scheduledId)

    // ---- live session (local-only writes) ---------------------------------

    override suspend fun start(programId: String, scheduledId: String): Result<Unit> = runCatching {
        // Resume an in-flight draft rather than restarting it (ADR-0012 D1).
        currentDraft(programId, scheduledId)?.let { return@runCatching }

        val scheduled = resolveScheduled(programId, scheduledId)
            ?: error(
                "This workout hasn't been downloaded yet. " +
                    "Connect to the internet once to start it.",
            )
        val at = now()
        val draft = WorkoutSessionDraft(
            programId = programId,
            scheduledId = scheduledId,
            startedAt = at,
            lastActivityAt = at,
            status = DraftStatus.ACTIVE,
            scheduled = scheduled,
            // A COMPLETED/imported snapshot carries the sets it was performed with —
            // seed the draft so opening it shows (and lets you correct) them; a
            // still-PLANNED session seeds empty.
            logged = loggedFromSnapshot(scheduled),
        )
        writeDraftLocal(draft)
    }

    override suspend fun markStarted(programId: String, scheduledId: String): Result<Unit> = runCatching {
        val draft = currentDraft(programId, scheduledId)
            ?: error("No active draft for ${mirrorId(programId, scheduledId)}")
        val at = now()
        writeDraftLocal(draft.copy(startedAt = at, lastActivityAt = at))
    }

    override suspend fun updateSets(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        sets: List<LoggedSet>,
    ): Result<Unit> = runCatching {
        val draft = currentDraft(programId, scheduledId)
            ?: error("No active draft for ${mirrorId(programId, scheduledId)}")
        val logged = draft.logged.toMutableMap()
        if (sets.isEmpty()) logged.remove(key) else logged[key] = sets
        writeDraftLocal(draft.copy(logged = logged, lastActivityAt = now()))
    }

    // ---- terminal actions (one outbox op, then clear the draft) -----------

    override suspend fun finish(programId: String, scheduledId: String, feeling: Int?): Result<Unit> =
        runCatching {
            val draft = currentDraft(programId, scheduledId)
                ?: error("No active draft for ${mirrorId(programId, scheduledId)}")
            val completedAt = now()
            val durationSeconds = (completedAt.epochSeconds - draft.startedAt.epochSeconds)
                .coerceAtLeast(0L).toInt()
            val request = CompleteSessionRequest(
                status = ScheduledStatus.COMPLETED.name,
                completedAt = completedAt,
                durationSeconds = durationSeconds,
                logged = draft.logged.entries
                    .filter { it.value.isNotEmpty() }
                    .map { (key, sets) ->
                        LoggedPrescriptionDto(blockId = key.blockId, orderIndex = key.orderIndex, sets = sets)
                    },
                // Day reference so the server can materialize a client-minted ad-hoc
                // session on first contact (harmless for already-materialized ones).
                phaseId = draft.scheduled.phaseId.takeIf { it.isNotBlank() },
                dayId = draft.scheduled.dayId.takeIf { it.isNotBlank() },
                date = draft.scheduled.date,
                feeling = feeling,
            )
            enqueueCompletion(programId, scheduledId, request)
            deleteDraftLocal(programId, scheduledId)
        }

    override suspend fun skip(programId: String, scheduledId: String): Result<Unit> = runCatching {
        // D4: SKIPPED clears actuals — no completedAt/duration/logged.
        val draft = currentDraft(programId, scheduledId)
        val request = CompleteSessionRequest(
            status = ScheduledStatus.SKIPPED.name,
            phaseId = draft?.scheduled?.phaseId?.takeIf { it.isNotBlank() },
            dayId = draft?.scheduled?.dayId?.takeIf { it.isNotBlank() },
            date = draft?.scheduled?.date,
        )
        enqueueCompletion(programId, scheduledId, request)
        deleteDraftLocal(programId, scheduledId)
    }

    override suspend fun discard(programId: String, scheduledId: String): Result<Unit> = runCatching {
        deleteDraftLocal(programId, scheduledId)
    }

    // ---- best-effort network reads ----------------------------------------

    override suspend fun lastSets(programId: String, scheduledId: String): Map<String, List<LoggedSet>> {
        val http = client ?: return emptyMap()
        return runCatching {
            http.get("api/me/workout-programs/$programId/sessions/$scheduledId/last-sets")
                .body<Map<String, List<LoggedSet>>>()
        }.getOrDefault(emptyMap())
    }

    override suspend fun fetchRecap(programId: String, scheduledId: String): String? {
        val http = client ?: return null
        return runCatching {
            http.get("api/me/workout-programs/$programId/sessions/$scheduledId/recap")
                .body<SessionRecapDto>().recap
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ---- parked-completion recovery -----------------------------------------
    //
    // A completion the server terminally rejected (e.g. the plan was rewritten under
    // the upload) is PARKED in the outbox (SyncEngine.drainOutbox) rather than dropped.
    // We surface those for WORKOUT_SCHEDULED so the user can restore (re-open a draft to
    // re-finish) or discard them.

    override fun observeParkedCompletions(): Flow<List<ParkedCompletion>> =
        outbox.observeParked().map { ops ->
            ops.filter { it.collection == MirrorTables.WORKOUT_SCHEDULED }
                .mapNotNull { toParkedCompletion(it) }
        }

    private fun toParkedCompletion(op: OutboxOp): ParkedCompletion? {
        val (programId, scheduledId) = splitMirrorId(op.entityId)
        val req = op.docJson
            ?.let { runCatching { json.decodeFromString(CompleteSessionRequest.serializer(), it) }.getOrNull() }
            ?: return null
        val scheduled = mirror.record(MirrorTables.WORKOUT_SCHEDULED, op.entityId)
            ?.takeIf { !it.dirty }
            ?.let { decodeScheduled(it.payloadJson) }
        // Orphans = logged sets whose (blockId, orderIndex) no longer exists in the
        // CURRENT plan snapshot (the plan was rewritten under the upload). With no
        // snapshot (sessionAvailable=false) everything is orphaned — only discard applies.
        val valid = scheduled?.let(::validPrescriptionKeys) ?: emptySet()
        val orphaned = req.logged
            .filter { PrescriptionKey(it.blockId, it.orderIndex) !in valid }
            .sumOf { it.sets.size }
        return ParkedCompletion(
            programId = programId,
            scheduledId = scheduledId,
            status = runCatching { ScheduledStatus.valueOf(req.status) }.getOrDefault(ScheduledStatus.COMPLETED),
            completedAt = req.completedAt,
            loggedSetCount = req.logged.sumOf { it.sets.size },
            orphanedSetCount = orphaned,
            sessionAvailable = scheduled != null,
            dayLabel = scheduled?.dayLabel,
        )
    }

    override suspend fun reset(programId: String, scheduledId: String): Result<Unit> = runCatching {
        // Un-log: PLANNED clears actuals — same idempotent upsert path as skip.
        val draft = currentDraft(programId, scheduledId)
        val request = CompleteSessionRequest(
            status = ScheduledStatus.PLANNED.name,
            phaseId = draft?.scheduled?.phaseId?.takeIf { it.isNotBlank() },
            dayId = draft?.scheduled?.dayId?.takeIf { it.isNotBlank() },
            date = draft?.scheduled?.date,
        )
        enqueueCompletion(programId, scheduledId, request)
        deleteDraftLocal(programId, scheduledId)
    }

    override suspend fun restoreParked(programId: String, scheduledId: String): Result<Unit> = runCatching {
        val id = mirrorId(programId, scheduledId)
        val op = outbox.parked().firstOrNull { it.collection == MirrorTables.WORKOUT_SCHEDULED && it.entityId == id }
            ?: error("No parked completion for $id")
        val req = op.docJson
            ?.let { json.decodeFromString(CompleteSessionRequest.serializer(), it) }
            ?: error("Parked completion has no payload")
        val scheduled = mirror.record(MirrorTables.WORKOUT_SCHEDULED, id)
            ?.takeIf { !it.dirty }
            ?.let { decodeScheduled(it.payloadJson) }
            ?: error("This session is no longer available to restore")
        // Re-materialize a live draft from the rejected payload's sets so the user can
        // re-finish, then drop the parked op. Orphaned sets (keys no longer in the
        // current plan) are dropped — the ParkedCompletion.orphanedSetCount already
        // surfaced the count so nothing vanishes silently.
        val valid = validPrescriptionKeys(scheduled)
        val logged = req.logged
            .filter { PrescriptionKey(it.blockId, it.orderIndex) in valid }
            .associate { PrescriptionKey(it.blockId, it.orderIndex) to it.sets }
        val at = now()
        writeDraftLocal(
            WorkoutSessionDraft(
                programId = programId,
                scheduledId = scheduledId,
                startedAt = at,
                lastActivityAt = at,
                status = DraftStatus.ACTIVE,
                scheduled = scheduled,
                logged = logged,
            ),
        )
        outbox.discard(op.id)
    }

    override suspend fun discardParked(programId: String, scheduledId: String): Result<Unit> = runCatching {
        val id = mirrorId(programId, scheduledId)
        outbox.parked()
            .filter { it.collection == MirrorTables.WORKOUT_SCHEDULED && it.entityId == id }
            .forEach { outbox.discard(it.id) }
    }

    // ---- internals --------------------------------------------------------

    /** The current local draft for this session (dirty row carrying draft JSON), or null. */
    private fun currentDraft(programId: String, scheduledId: String): WorkoutSessionDraft? {
        val row = mirror.record(MirrorTables.WORKOUT_SCHEDULED, mirrorId(programId, scheduledId)) ?: return null
        if (!row.dirty) return null
        return decodeDraft(row.payloadJson)
    }

    /**
     * The planned [ScheduledWorkout] to snapshot into a fresh draft: the clean
     * mirror row the sync pull populated, else a best-effort calendar fetch that is
     * mirrored for next time. Null when neither resolves (offline + never synced).
     */
    private suspend fun resolveScheduled(programId: String, scheduledId: String): ScheduledWorkout? {
        val id = mirrorId(programId, scheduledId)
        val record = mirror.record(MirrorTables.WORKOUT_SCHEDULED, id)
        val mirrored = record?.let { decodeScheduled(it.payloadJson) }
        // A dirty row carries an un-synced local outcome (e.g. a pending
        // completion) — newer truth than any server copy, so don't read through
        // over it (matches Android's per-row dirty guard).
        if (record?.dirty == true) return mirrored
        // Read-through, NOT mirror-first: the server rewrites this doc after it was
        // first mirrored (progression writeback, continuation heals), and a stale
        // mirror here means coaching the whole workout at the wrong weights.
        // Refresh from the network at the moment the numbers matter — time-boxed so
        // a gym dead zone doesn't hold the coach screen hostage — and fall back to
        // the mirrored copy offline/slow. (Parity with Android #300.)
        val fresh = withTimeoutOrNull(SCHEDULED_READ_THROUGH_TIMEOUT_MS) {
            fetchAndMirrorScheduled(programId, scheduledId)
        }
        return fresh ?: mirrored
    }

    /**
     * Cold-miss recovery for [start]: pull the program's whole schedule and mirror
     * it (SYNCED/clean), returning the now-mirrored session. Null when offline /
     * no client / the id isn't in the schedule.
     */
    private suspend fun fetchAndMirrorScheduled(programId: String, scheduledId: String): ScheduledWorkout? {
        val http = client ?: return null
        val sessions = runCatching {
            http.get("api/me/workout-programs/$programId/calendar") {
                parameter("from", "1970-01-01")
                parameter("to", "2999-12-31")
            }.body<List<ScheduledWorkout>>()
        }.getOrNull() ?: return null
        val stamp = now().toString()
        sessions.forEach {
            mirror.applyLocalSynced(
                collection = MirrorTables.WORKOUT_SCHEDULED,
                id = mirrorId(programId, it.scheduledId),
                payloadJson = json.encodeToString(ScheduledWorkout.serializer(), it),
                lastUpdate = stamp,
            )
        }
        return sessions.firstOrNull { it.scheduledId == scheduledId }
    }

    /** Non-enqueueing local draft upsert (dirty) — the per-set-edit write path. */
    private fun writeDraftLocal(draft: WorkoutSessionDraft) {
        mirror.writeLocal(
            collection = MirrorTables.WORKOUT_SCHEDULED,
            id = mirrorId(draft.programId, draft.scheduledId),
            payloadJson = json.encodeToString(WorkoutSessionDraft.serializer(), draft),
            lastUpdate = provisionalStamp(mirrorId(draft.programId, draft.scheduledId)),
        )
    }

    /**
     * Clear the draft after a terminal action. Tombstoning the row locally would
     * hide the session from calendars until the next pull; instead drop the row so
     * the engine's next delta re-populates the server-authoritative scheduled row.
     */
    private fun deleteDraftLocal(programId: String, scheduledId: String) {
        mirror.deleteRowLocal(MirrorTables.WORKOUT_SCHEDULED, mirrorId(programId, scheduledId))
    }

    /** Enqueue the ONE idempotent completion op + kick a drain. */
    private suspend fun enqueueCompletion(
        programId: String,
        scheduledId: String,
        request: CompleteSessionRequest,
    ) {
        val id = mirrorId(programId, scheduledId)
        val mutationId = mint()
        outbox.enqueue(
            OutboxOp(
                id = mutationId,
                collection = MirrorTables.WORKOUT_SCHEDULED,
                entityId = id,
                docJson = json.encodeToString(CompleteSessionRequest.serializer(), request),
                operation = OutboxOp.Operation.UPDATE, // routes to the PUT upsert
                idempotencyKey = mutationId,
                enqueuedAt = now().toString(),
            ),
        )
        scope.launch { runCatching { engine.drainOutbox() } }
    }

    private fun decodeDraft(payloadJson: String): WorkoutSessionDraft? =
        runCatching { json.decodeFromString(WorkoutSessionDraft.serializer(), payloadJson) }.getOrNull()

    private fun decodeScheduled(payloadJson: String): ScheduledWorkout? =
        runCatching { json.decodeFromString(ScheduledWorkout.serializer(), payloadJson) }.getOrNull()

    /** The actuals a snapshot already carries, shaped as the draft's logged map. */
    private fun loggedFromSnapshot(scheduled: ScheduledWorkout): Map<PrescriptionKey, List<LoggedSet>> =
        scheduled.session?.blocks.orEmpty().flatMap { block ->
            block.prescriptions.mapNotNull { rx ->
                rx.loggedSets.takeIf { it.isNotEmpty() }
                    ?.let { PrescriptionKey(block.blockId, rx.orderIndex) to it }
            }
        }.toMap()

    private fun provisionalStamp(id: String): String = "9999-local-$id-${now()}"

    private fun mint(): String = "ios-" + Random.nextLong().toString(16).removePrefix("-")

    private companion object {
        /**
         * Bound on the [start] read-through refresh (parity with Android #300):
         * long enough for a normal calendar fetch, short enough that a gym dead
         * zone doesn't hold the coach screen hostage before falling back to the
         * mirrored copy.
         */
        const val SCHEDULED_READ_THROUGH_TIMEOUT_MS = 4_000L

        /** The sync id of one scheduled session's mirror row (parity with Android). */
        fun mirrorId(programId: String, scheduledId: String): String = "$programId/$scheduledId"

        /** Inverse of [mirrorId]: "<programId>/<scheduledId>" → the pair (tolerant of a plain id). */
        fun splitMirrorId(id: String): Pair<String, String> {
            val i = id.indexOf('/')
            return if (i < 0) id to id else id.substring(0, i) to id.substring(i + 1)
        }

        /** The (blockId, orderIndex) keys the current plan snapshot still defines — a
         *  logged set keyed outside this set is "orphaned" (its prescription was removed). */
        fun validPrescriptionKeys(scheduled: ScheduledWorkout): Set<PrescriptionKey> =
            scheduled.session?.blocks.orEmpty().flatMap { block ->
                block.prescriptions.map { PrescriptionKey(block.blockId, it.orderIndex) }
            }.toSet()

        /** Lenient JSON matching the wire + mirror payloads (unknown keys / absent nulls). */
        val DRAFT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

/**
 * ADR-0012 / IMPL-17 D2 — the completion upsert wire body (shared port of
 * android `CompleteSessionRequest`). Carried as the outbox op's `docJson`, replayed
 * as the idempotent `PUT .../sessions/{scheduledId}`. Reuses the domain [LoggedSet]
 * (its fields are wire-identical to the backend `LoggedSetDto`).
 */
@Serializable
data class CompleteSessionRequest(
    /** `COMPLETED` / `SKIPPED` / `PLANNED` ([ScheduledStatus] name). */
    val status: String,
    val completedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val logged: List<LoggedPrescriptionDto> = emptyList(),
    val phaseId: String? = null,
    val dayId: String? = null,
    val date: LocalDate? = null,
    /** Post-workout mood (1..5); null when skipped / not COMPLETED. */
    val feeling: Int? = null,
)

/** The performed sets for one prescription, keyed by `(blockId, orderIndex)`. */
@Serializable
data class LoggedPrescriptionDto(
    val blockId: String,
    val orderIndex: Int = 0,
    val sets: List<LoggedSet> = emptyList(),
    /** The exercise actually performed (#4); null when done as designed. */
    val exerciseId: String? = null,
)

/** IMPL-COACH: the AI recap payload (null until available). */
@Serializable
data class SessionRecapDto(val recap: String? = null)
