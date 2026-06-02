package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import okhttp3.HttpUrl

/**
 * IMPL-AND-20 (Phase 5) — the ONE place that maps a mirror table + outbox op to
 * the REAL backend REST endpoint for a replayed write.
 *
 * Phase 4's [RestOutboxReplayClient] used a generic `api/me/<table>` shape. As
 * each in-scope repository adopts the outbox (Phase 5) its mutations must land on
 * the actual controller path (e.g. blood is `api/me/blood`, locations are
 * `api/me/gyms`, nutrition entries are `api/me/nutrition/{date}/entries`, etc.).
 * Keeping that mapping here — rather
 * than scattered across repositories — means the drain loop stays generic and the
 * endpoint contract is auditable in a single file.
 *
 * Each [EndpointSpec] resolves the HTTP method + URL for a given op. Most domains
 * are "flat" (`POST /base`, `PUT/DELETE /base/{id}`); a few need the entity id or
 * a path component (a date) lifted out of the payload, so the spec is given the
 * `entityId` and the decoded payload map for those cases.
 *
 * `entityId` convention: for nested collections the optimistic local row id is
 * minted as a **composite** `"<parent>/<child>"` (e.g. `"2026-06-02/entry-uuid"`
 * for a nutrition entry) so the replay can recover the parent path segment without
 * re-decoding the payload. Flat domains keep a plain UUID id.
 */
object OutboxEndpointRegistry {

    /** Resolves the wire method + URL for one replayed mutation. */
    fun resolve(
        base: HttpUrl,
        table: String,
        op: OutboxOp,
        entityId: String,
    ): Resolved {
        specs[table]?.let { return it.resolve(base, op, entityId) }
        // Phase-4-compatible generic fallback: `api/me/<table>` (POST), with an
        // id segment for UPDATE/DELETE. Keeps the existing drain test green and
        // any not-yet-migrated table replaying somewhere sane.
        val b = base.newBuilder().addPathSegments("api/me/$table")
        if (op != OutboxOp.CREATE) b.addPathSegment(entityId)
        return Resolved(op.httpMethod(), b.build())
    }

    data class Resolved(val method: String, val url: HttpUrl)

    private fun interface EndpointSpec {
        fun resolve(base: HttpUrl, op: OutboxOp, entityId: String): Resolved
    }

    /** `POST <segs>` for CREATE, `PUT/DELETE <segs>/{id}` for UPDATE/DELETE. */
    private fun flat(vararg segments: String) = EndpointSpec { base, op, entityId ->
        val b = base.newBuilder()
        segments.forEach { b.addPathSegments(it) }
        if (op != OutboxOp.CREATE) b.addPathSegment(entityId)
        Resolved(method = op.httpMethod(), url = b.build())
    }

    /**
     * Nutrition entries live under `api/me/nutrition/{date}/entries`. The
     * composite entityId is `"<date>/<entryId>"`; CREATE posts to the date's
     * collection, UPDATE/DELETE target the specific entry.
     */
    private val nutritionEntries = EndpointSpec { base, op, entityId ->
        val (date, entryId) = splitComposite(entityId)
        val b = base.newBuilder()
            .addPathSegments("api/me/nutrition")
            .addPathSegment(date)
            .addPathSegment("entries")
        if (op != OutboxOp.CREATE) b.addPathSegment(entryId)
        Resolved(op.httpMethod(), b.build())
    }

    /**
     * Medication adherence (#24). Offline-capable dose log/undo keyed by
     * `(medication, date)` (the backend idempotency-keys adherence by med+date).
     * The composite entityId is `"<medicationId>/<date>/<window>"`:
     *  - CREATE  ⇒ `POST api/me/medications/{med}/adherence` (the body carries the
     *    window/takenAt/dose/date).
     *  - DELETE  ⇒ `DELETE api/me/medications/{med}/adherence/{date}/{window}`
     *    (an undo).
     * There is no UPDATE; a re-log after an undo is a fresh CREATE.
     */
    private val medicationAdherence = EndpointSpec { base, op, entityId ->
        val (medId, date, window) = splitTriple(entityId)
        val b = base.newBuilder()
            .addPathSegments("api/me/medications")
            .addPathSegment(medId)
            .addPathSegment("adherence")
        if (op == OutboxOp.DELETE) {
            b.addPathSegment(date).addPathSegment(window)
        }
        Resolved(op.httpMethod(), b.build())
    }

    /**
     * Goal phases (#26): `POST api/me/goals/{goalId}/phases` for CREATE,
     * `PATCH/DELETE .../phases/{phaseId}` for UPDATE/DELETE. The composite
     * entityId is `"<goalId>/<phaseId>"`. The backend update is a PATCH.
     */
    private val goalPhases = EndpointSpec { base, op, entityId ->
        val (goalId, phaseId) = splitComposite(entityId)
        val b = base.newBuilder()
            .addPathSegments("api/me/goals")
            .addPathSegment(goalId)
            .addPathSegments("phases")
        if (op != OutboxOp.CREATE) b.addPathSegment(phaseId)
        // The backend phase update is PATCH (not PUT); map UPDATE→PATCH.
        val method = when (op) {
            OutboxOp.CREATE -> "POST"
            OutboxOp.UPDATE -> "PATCH"
            OutboxOp.DELETE -> "DELETE"
        }
        Resolved(method, b.build())
    }

    /**
     * Goal steps (#26): the structural step CRUD (title/description/order) routes
     * through here. The composite entityId is `"<goalId>/<phaseId>/<stepId>"`
     * (the backend nests steps under their phase):
     *  - CREATE ⇒ `POST api/me/goals/{goalId}/phases/{phaseId}/steps`
     *  - UPDATE ⇒ `PATCH .../steps/{stepId}` (title/description/order edit)
     *  - DELETE ⇒ `DELETE .../steps/{stepId}`
     *
     * D9 boundary: a manual **done/doneAt** toggle is server-evaluated and goes up
     * as an explicit `PATCH .../steps/{sid}` **intent** on the network path
     * ([com.gte619n.healthfitness.data.goals.GoalsRepository.setStepDone]) — it is
     * NEVER enqueued here, so a structural-edit replay can never clobber the
     * server-derived completion fields.
     */
    private val goalSteps = EndpointSpec { base, op, entityId ->
        val (goalId, phaseId, stepId) = splitTriple(entityId)
        val b = base.newBuilder()
            .addPathSegments("api/me/goals")
            .addPathSegment(goalId)
            .addPathSegment("phases")
            .addPathSegment(phaseId)
            .addPathSegments("steps")
        if (op != OutboxOp.CREATE) b.addPathSegment(stepId)
        val method = when (op) {
            OutboxOp.CREATE -> "POST"
            OutboxOp.UPDATE -> "PATCH"
            OutboxOp.DELETE -> "DELETE"
        }
        Resolved(method, b.build())
    }

    /**
     * User profile is a singleton doc. The LIVE backend contract is a partial
     * `PATCH /api/me` (see [com.gte619n.healthfitness.data.profile.ProfileService]) —
     * there is no `PUT /api/me/profile`. The replayed body carries the full mirrored
     * `ProfileDto`; Spring binds only the fields the patch DTO declares (e.g.
     * `heightCm`) and ignores the rest, so the height edit lands correctly. (#21)
     */
    private val profile = EndpointSpec { base, op, _ ->
        val url = base.newBuilder().addPathSegments("api/me").build()
        // Profile is update-only on the client; CREATE/UPDATE both PATCH /api/me.
        val method = if (op == OutboxOp.DELETE) "DELETE" else "PATCH"
        Resolved(method, url)
    }

    /**
     * The macro target is a per-user singleton: `PUT api/me/nutrition/target`
     * (singular, no id segment). CREATE/UPDATE both PUT it; there is no delete.
     */
    private val nutritionTarget = EndpointSpec { base, op, _ ->
        val url = base.newBuilder().addPathSegments("api/me/nutrition/target").build()
        val method = if (op == OutboxOp.DELETE) "DELETE" else "PUT"
        Resolved(method, url)
    }

    /**
     * Per-table endpoint specs. Tables NOT listed fall back to [defaultSpec]
     * (`api/me/<table>`), which preserves the Phase 4 generic behavior and keeps
     * the existing MockWebServer drain test (`/api/me/medications`) green.
     */
    private val specs: Map<String, EndpointSpec> = buildMap {
        put(MirrorTables.BLOOD_READINGS, flat("api/me/blood"))
        put(MirrorTables.BLOOD_TEST_REPORTS, flat("api/me/blood/reports"))
        put(MirrorTables.MEDICATIONS, flat("api/me/medications"))
        put(MirrorTables.GOALS, flat("api/me/goals"))
        put(MirrorTables.LOCATIONS, flat("api/me/gyms"))
        put(MirrorTables.BODY_COMPOSITION, flat("api/me/body-composition"))
        put(MirrorTables.NUTRITION_TARGETS, nutritionTarget)
        put(MirrorTables.NUTRITION_ENTRIES, nutritionEntries)
        put(MirrorTables.GOAL_PHASES, goalPhases)
        put(MirrorTables.GOAL_STEPS, goalSteps)
        put(MirrorTables.MEDICATION_ADHERENCE, medicationAdherence)
        put(MirrorTables.USER_PROFILE, profile)
    }

    private fun OutboxOp.httpMethod(): String = when (this) {
        OutboxOp.CREATE -> "POST"
        OutboxOp.UPDATE -> "PUT"
        OutboxOp.DELETE -> "DELETE"
    }

    /** Split a composite `"parent/child"` entityId; tolerant of a plain id. */
    private fun splitComposite(entityId: String): Pair<String, String> {
        val idx = entityId.indexOf('/')
        return if (idx < 0) entityId to entityId
        else entityId.substring(0, idx) to entityId.substring(idx + 1)
    }

    /**
     * Split a composite `"a/b/c"` entityId into its three segments, tolerant of a
     * shorter id (missing segments echo the last available one). Used for the
     * adherence (`med/date/window`) and step (`goal/phase/step`) endpoints.
     */
    private fun splitTriple(entityId: String): Triple<String, String, String> {
        val parts = entityId.split('/', limit = 3)
        val a = parts.getOrNull(0) ?: entityId
        val b = parts.getOrNull(1) ?: a
        val c = parts.getOrNull(2) ?: b
        return Triple(a, b, c)
    }

    /**
     * The `Idempotency-Key` to send for a replayed mutation (#24/#33). Most tables
     * use the random per-mutation `mutationId` (a fresh client UUID per write).
     * **Adherence** instead derives a deterministic `(med,date)` key so a re-queued
     * log of the same dose-day is a server-side no-op returning the current state —
     * matching the backend's `medicationAdherence:log:{med}:{date}` idempotency
     * scope (the day's log is keyed by med+date, not by an opaque write id).
     */
    fun idempotencyKey(table: String, entityId: String, mutationId: String): String =
        if (table == MirrorTables.MEDICATION_ADHERENCE) {
            val (med, date, _) = splitTriple(entityId)
            "adherence:$med:$date"
        } else {
            mutationId
        }
}
