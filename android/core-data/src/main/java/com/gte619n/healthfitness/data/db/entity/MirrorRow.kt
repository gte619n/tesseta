package com.gte619n.healthfitness.data.db.entity

/**
 * IMPL-AND-20 (Phase 3) — shared shape for every per-collection mirror row.
 *
 * Per the spec (D5/D8) there is **one `@Entity` per in-scope collection** rather
 * than a single generic `collection`-keyed table. Reasons:
 *  - The spec's Room schema shows one table per collection; the cursor/LWW logic
 *    and the per-row PENDING/FAILED badges (D11) all reason per table.
 *  - Distinct tables keep Room's generated queries simple and let Phase 4 attach
 *    per-collection indexes/foreign keys later without a schema rewrite.
 *  - The rows are tiny (the domain payload lives opaque in [payloadJson]), so the
 *    duplication is purely structural and is generated from this one template.
 *
 * Room cannot use this interface as an `@Entity` directly, so each concrete
 * entity re-declares the same columns. This interface exists so DAOs and the
 * sync engine can treat any mirror row uniformly.
 */
interface MirrorRow {
    /** Primary key = backend UUID (client-minted on create). */
    val id: String

    /** Moshi-serialized domain model (opaque to the DB layer). */
    val payloadJson: String

    /** Server epoch millis — the sync cursor key and the LWW ordering key (D3/D6). */
    val lastUpdate: Long

    /** [SyncRowStatus] name: ACTIVE or ARCHIVED (tombstone). */
    val status: String

    /** True when an unsynced local mutation is pending for this row. */
    val dirty: Boolean

    /** [SyncRowState] name: SYNCED | PENDING | FAILED — drives the D11 badges. */
    val syncState: String
}

/** Soft-delete status mirrored from the backend (D2). */
enum class SyncRowStatus { ACTIVE, ARCHIVED }

/** Per-row sync state that drives the D11 pending/failed badges. */
enum class SyncRowState { SYNCED, PENDING, FAILED }

/** Logical table names for the in-scope mirror collections. */
object MirrorTables {
    const val BODY_COMPOSITION = "bodyComposition"
    const val BLOOD_READINGS = "bloodReadings"
    const val BLOOD_TEST_REPORTS = "bloodTestReports"
    const val MEDICATIONS = "medications"
    const val MEDICATION_ADHERENCE = "medicationAdherence"
    const val MEDICATION_HISTORY = "medicationHistory"
    const val PROTOCOLS = "protocols"
    const val GOALS = "goals"
    const val GOAL_PHASES = "goalPhases"
    const val GOAL_STEPS = "goalSteps"
    const val GOAL_CHAT_THREADS = "goalChatThreads"
    const val GOAL_CHAT_MESSAGES = "goalChatMessages"
    const val NUTRITION_DAILY_LOGS = "nutritionDailyLogs"
    const val NUTRITION_ENTRIES = "nutritionEntries"
    const val NUTRITION_TARGETS = "nutritionTargets"
    const val LOCATIONS = "locations"
    const val DAILY_METRICS = "dailyMetrics"
    const val DEVICE_SYNCS = "deviceSyncs"
    const val DEXA_SCANS = "dexaScans"
    const val WEEKLY_WORKOUT_AGGREGATES = "weeklyWorkoutAggregates"
    const val USER_PROFILE = "userProfile"

    /** Every in-scope mirror table, in the order the sync engine pulls them. */
    val ALL: List<String> = listOf(
        BODY_COMPOSITION, BLOOD_READINGS, BLOOD_TEST_REPORTS, MEDICATIONS,
        MEDICATION_ADHERENCE, MEDICATION_HISTORY, PROTOCOLS, GOALS, GOAL_PHASES,
        GOAL_STEPS, GOAL_CHAT_THREADS, GOAL_CHAT_MESSAGES, NUTRITION_DAILY_LOGS,
        NUTRITION_ENTRIES, NUTRITION_TARGETS, LOCATIONS, DAILY_METRICS,
        DEVICE_SYNCS, DEXA_SCANS, WEEKLY_WORKOUT_AGGREGATES, USER_PROFILE,
    )
}
