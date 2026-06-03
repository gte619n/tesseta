package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables

/**
 * IMPL-AND-20 (Phase 4) — the ONE place that maps the backend delta API's
 * `collection` string to a local Room mirror table name ([MirrorTables]).
 *
 * The backend `/api/me/sync` response tags every change with a `collection`
 * string (see the spec's API Contracts). The Phase 1 backend agent is still
 * finalizing the exact wire strings — especially for the subcollections
 * (adherence / history / phases / steps / messages / entries). When those land,
 * **adjust the [aliases] map below and nothing else**: the SyncEngine, the
 * DAO dispatch, and the outbox all funnel through [tableFor].
 *
 * Design:
 *  - [MirrorTables] constants are treated as the canonical local table names and
 *    are accepted verbatim (a collection string that already equals a table name
 *    maps to itself). This makes the common case zero-config.
 *  - [aliases] holds every *additional* wire string the backend might send that
 *    differs from the local table name. Add a row here to wire a new/renamed
 *    backend collection string to its table. Unknown collections return `null`
 *    and are skipped by the engine (logged, never crash) so a backend that
 *    introduces a collection the client doesn't mirror yet is forward-compatible.
 */
object CollectionRegistry {

    /**
     * Wire `collection` string → local Room table name, for every string that
     * is NOT already identical to its table name.
     *
     * ⚠️ Phase 1 hand-off: confirm/replace these once the backend's final
     * `collection` values are published. Each commented domain note records the
     * Firestore path the string is expected to come from.
     */
    private val aliases: Map<String, String> = buildMap {
        // Medication subcollections — backend may emit either the plain
        // subcollection name or a dotted path; map both to the flat mirror table.
        put("adherence", MirrorTables.MEDICATION_ADHERENCE)
        put("medications.adherence", MirrorTables.MEDICATION_ADHERENCE)
        put("history", MirrorTables.MEDICATION_HISTORY)
        put("medications.history", MirrorTables.MEDICATION_HISTORY)

        // Goal subcollections.
        put("phases", MirrorTables.GOAL_PHASES)
        put("goals.phases", MirrorTables.GOAL_PHASES)
        put("steps", MirrorTables.GOAL_STEPS)
        put("goals.steps", MirrorTables.GOAL_STEPS)

        // Goal-chat thread + message subcollection.
        put("messages", MirrorTables.GOAL_CHAT_MESSAGES)
        put("goalChatThreads.messages", MirrorTables.GOAL_CHAT_MESSAGES)

        // Nutrition: backend `nutritionDays/{day}/entries` → flat entries table;
        // `users/{uid}` profile doc → userProfile table.
        put("entries", MirrorTables.NUTRITION_ENTRIES)
        put("nutritionDays.entries", MirrorTables.NUTRITION_ENTRIES)
        put("nutritionDays", MirrorTables.NUTRITION_DAILY_LOGS)
        put("users", MirrorTables.USER_PROFILE)
        put("user", MirrorTables.USER_PROFILE)
        put("profile", MirrorTables.USER_PROFILE)

        // Workout-program materialized sessions (backend emits the subcollection
        // under "workoutPrograms/scheduled"). The "workoutPrograms" program docs
        // themselves match the table name verbatim (canonical). Workout-program
        // chat is online-only and never synced.
        put("scheduled", MirrorTables.WORKOUT_SCHEDULED)
        put("workoutPrograms/scheduled", MirrorTables.WORKOUT_SCHEDULED)
        put("workoutPrograms.scheduled", MirrorTables.WORKOUT_SCHEDULED)
    }

    /** Local table names accepted verbatim (collection string == table name). */
    private val canonical: Set<String> = MirrorTables.ALL.toSet()

    /**
     * Resolve a backend `collection` string to a local mirror table name, or
     * `null` if the client does not mirror it (engine skips it forward-compatibly).
     */
    fun tableFor(collection: String): String? = when (collection) {
        in canonical -> collection
        else -> aliases[collection]
    }

    /**
     * The id field name the table's domain DTO expects, for tables whose delta
     * `doc` must carry it. A Firestore document never stores its own id as a
     * field, so the sync delta's `doc` omits it — but the GET-endpoint DTOs the
     * repositories decode require it (e.g. `locationId`, and a `recordId` keys the
     * weight list). The SyncEngine injects the change id under this name when it
     * applies a pulled change, so a pulled row decodes exactly like a GET-filled
     * one. Tables absent here need no injection (their doc already suffices, or
     * they're not decoded by id). Top-level collections only — subcollection
     * composite ids are handled by their own repos.
     */
    fun idFieldFor(table: String): String? = idFields[table]

    private val idFields: Map<String, String> = mapOf(
        MirrorTables.LOCATIONS to "locationId",
        MirrorTables.WORKOUT_PROGRAMS to "programId",
        MirrorTables.MEDICATIONS to "medicationId",
        MirrorTables.BODY_COMPOSITION to "recordId",
        MirrorTables.BLOOD_READINGS to "readingId",
        MirrorTables.BLOOD_TEST_REPORTS to "reportId",
        MirrorTables.DEXA_SCANS to "scanId",
    )
}
