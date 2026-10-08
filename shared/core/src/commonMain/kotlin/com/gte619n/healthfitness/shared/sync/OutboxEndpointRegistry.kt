package com.gte619n.healthfitness.shared.sync

/**
 * IMPL-IOS-01 Phase 1C — KMP port of the Android `OutboxEndpointRegistry`: the ONE
 * place mapping a mirror table + outbox op to the REAL backend REST endpoint for a
 * replayed write. Ported to emit a relative path string (the shared Ktor client
 * already carries the base URL) instead of an OkHttp HttpUrl.
 *
 * `entityId` convention (matches Android): nested collections mint a COMPOSITE id
 * `"<parent>/<child>"` (e.g. nutrition entry `"2026-06-02/entry-uuid"`, adherence
 * `"<med>/<date>/<window>"`) so the replay recovers the parent path segments
 * without re-decoding the payload. Flat domains keep a plain id.
 */
object OutboxEndpointRegistry {

    data class Resolved(val method: String, val path: String)

    fun resolve(table: String, op: OutboxOp.Operation, entityId: String): Resolved =
        specs[table]?.invoke(op, entityId)
            // Generic fallback: POST api/me/<table>, METHOD api/me/<table>/{id}.
            ?: Resolved(op.method(), if (op == OutboxOp.Operation.CREATE) "api/me/$table" else "api/me/$table/$entityId")

    /**
     * The `Idempotency-Key` for a replayed mutation. Most tables use the random
     * per-mutation id; adherence derives a deterministic `(med,date,window)` key so
     * a re-queued log of the same dose is a server-side no-op (the window MUST be in
     * the key — a twice-daily med's second dose would otherwise collide with the
     * first and never record).
     */
    fun idempotencyKey(table: String, entityId: String, mutationId: String): String =
        if (table == MirrorTables.MEDICATION_ADHERENCE) {
            val (med, date, window) = splitTriple(entityId)
            "adherence:$med:$date:$window"
        } else {
            mutationId
        }

    // --- per-collection specs -------------------------------------------------

    private val specs: Map<String, (OutboxOp.Operation, String) -> Resolved> = buildMap {
        put(MirrorTables.BLOOD_READINGS, flat("api/me/blood"))
        put(MirrorTables.BLOOD_TEST_REPORTS, flat("api/me/blood/reports"))
        put(MirrorTables.MEDICATIONS, flat("api/me/medications"))
        put(MirrorTables.GOALS, flat("api/me/goals"))
        put(MirrorTables.LOCATIONS, flat("api/me/gyms"))
        put(MirrorTables.BODY_COMPOSITION, flat("api/me/body-composition"))
        put(MirrorTables.NUTRITION_TARGETS, ::nutritionTarget)
        put(MirrorTables.NUTRITION_ENTRIES, ::nutritionEntries)
        put(MirrorTables.GOAL_PHASES, ::goalPhases)
        put(MirrorTables.GOAL_STEPS, ::goalSteps)
        put(MirrorTables.MEDICATION_ADHERENCE, ::medicationAdherence)
        put(MirrorTables.USER_PROFILE, ::profile)
        put(MirrorTables.WORKOUT_SCHEDULED, ::workoutSessions)
    }

    /** POST <base> for CREATE, PUT/DELETE <base>/{id} otherwise. */
    private fun flat(base: String): (OutboxOp.Operation, String) -> Resolved =
        { op, entityId -> Resolved(op.method(), if (op == OutboxOp.Operation.CREATE) base else "$base/$entityId") }

    /** api/me/nutrition/{date}/entries ; UPDATE→PATCH (e.g. move-meal patches `meal`). */
    private fun nutritionEntries(op: OutboxOp.Operation, entityId: String): Resolved {
        val (date, entryId) = splitComposite(entityId)
        val base = "api/me/nutrition/$date/entries"
        return when (op) {
            OutboxOp.Operation.CREATE -> Resolved("POST", base)
            OutboxOp.Operation.UPDATE -> Resolved("PATCH", "$base/$entryId")
            OutboxOp.Operation.DELETE -> Resolved("DELETE", "$base/$entryId")
        }
    }

    /** CREATE → POST .../adherence ; DELETE → DELETE .../adherence/{date}/{window}. No UPDATE. */
    private fun medicationAdherence(op: OutboxOp.Operation, entityId: String): Resolved {
        val (med, date, window) = splitTriple(entityId)
        val base = "api/me/medications/$med/adherence"
        return if (op == OutboxOp.Operation.DELETE) Resolved("DELETE", "$base/$date/$window")
        else Resolved(op.method(), base)
    }

    /** api/me/goals/{goalId}/phases ; UPDATE→PATCH. */
    private fun goalPhases(op: OutboxOp.Operation, entityId: String): Resolved {
        val (goalId, phaseId) = splitComposite(entityId)
        val base = "api/me/goals/$goalId/phases"
        return when (op) {
            OutboxOp.Operation.CREATE -> Resolved("POST", base)
            OutboxOp.Operation.UPDATE -> Resolved("PATCH", "$base/$phaseId")
            OutboxOp.Operation.DELETE -> Resolved("DELETE", "$base/$phaseId")
        }
    }

    /** api/me/goals/{goalId}/phases/{phaseId}/steps ; UPDATE→PATCH. */
    private fun goalSteps(op: OutboxOp.Operation, entityId: String): Resolved {
        val (goalId, phaseId, stepId) = splitTriple(entityId)
        val base = "api/me/goals/$goalId/phases/$phaseId/steps"
        return when (op) {
            OutboxOp.Operation.CREATE -> Resolved("POST", base)
            OutboxOp.Operation.UPDATE -> Resolved("PATCH", "$base/$stepId")
            OutboxOp.Operation.DELETE -> Resolved("DELETE", "$base/$stepId")
        }
    }

    /** Singleton: PATCH /api/me (CREATE/UPDATE both patch; DELETE maps to DELETE). */
    private fun profile(op: OutboxOp.Operation, @Suppress("UNUSED_PARAMETER") entityId: String): Resolved =
        Resolved(if (op == OutboxOp.Operation.DELETE) "DELETE" else "PATCH", "api/me")

    /** Singleton: PUT api/me/nutrition/target (CREATE/UPDATE both PUT). */
    private fun nutritionTarget(op: OutboxOp.Operation, @Suppress("UNUSED_PARAMETER") entityId: String): Resolved =
        Resolved(if (op == OutboxOp.Operation.DELETE) "DELETE" else "PUT", "api/me/nutrition/target")

    /** Idempotent completion upsert: PUT api/me/workout-programs/{programId}/sessions/{scheduledId}. */
    private fun workoutSessions(@Suppress("UNUSED_PARAMETER") op: OutboxOp.Operation, entityId: String): Resolved {
        val (programId, scheduledId) = splitComposite(entityId)
        return Resolved("PUT", "api/me/workout-programs/$programId/sessions/$scheduledId")
    }

    private fun OutboxOp.Operation.method(): String = when (this) {
        OutboxOp.Operation.CREATE -> "POST"
        OutboxOp.Operation.UPDATE -> "PUT"
        OutboxOp.Operation.DELETE -> "DELETE"
    }

    private fun splitComposite(entityId: String): Pair<String, String> {
        val idx = entityId.indexOf('/')
        return if (idx < 0) entityId to entityId
        else entityId.substring(0, idx) to entityId.substring(idx + 1)
    }

    private fun splitTriple(entityId: String): Triple<String, String, String> {
        val parts = entityId.split('/', limit = 3)
        val a = parts.getOrNull(0) ?: entityId
        val b = parts.getOrNull(1) ?: a
        val c = parts.getOrNull(2) ?: b
        return Triple(a, b, c)
    }
}
