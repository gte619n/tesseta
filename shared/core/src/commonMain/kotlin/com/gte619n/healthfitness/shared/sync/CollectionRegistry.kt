package com.gte619n.healthfitness.shared.sync

/**
 * IMPL-IOS-01 Phase 1C — the KMP port of the Android `CollectionRegistry`
 * (android/core-data .../data/sync/CollectionRegistry.kt), moved verbatim in
 * behavior to `commonMain` so Android AND iOS route the backend delta's
 * `collection` strings through ONE table. This is the anti-drift keystone: the
 * "backend emits a collection the client silently drops" bug class
 * (nutrition-sync-slash-collection-bug) can now only be fixed once, for both
 * platforms.
 *
 * The mapping is bound to the shared fixture
 * `docs/reference/sync-emitted-collections.txt` by
 * [com.gte619n.healthfitness.shared.sync.CollectionRegistryContractTest]
 * (commonTest), the KMP sibling of the backend
 * `SyncEmittedCollectionsContractTest`.
 */
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
    const val WORKOUT_PROGRAMS = "workoutPrograms"
    const val WORKOUT_SCHEDULED = "workoutScheduled"
    const val ADHOC_WORKOUTS = "adhocWorkouts"
    const val ADHOC_SESSIONS = "adhocSessions"
    const val USER_PROFILE = "userProfile"

    /** Every in-scope mirror table, in the order the sync engine pulls them. */
    val ALL: List<String> = listOf(
        BODY_COMPOSITION, BLOOD_READINGS, BLOOD_TEST_REPORTS, MEDICATIONS,
        MEDICATION_ADHERENCE, MEDICATION_HISTORY, PROTOCOLS, GOALS, GOAL_PHASES,
        GOAL_STEPS, GOAL_CHAT_THREADS, GOAL_CHAT_MESSAGES, NUTRITION_DAILY_LOGS,
        NUTRITION_ENTRIES, NUTRITION_TARGETS, LOCATIONS, DAILY_METRICS,
        DEVICE_SYNCS, DEXA_SCANS, WEEKLY_WORKOUT_AGGREGATES, WORKOUT_PROGRAMS,
        WORKOUT_SCHEDULED, ADHOC_WORKOUTS, ADHOC_SESSIONS, USER_PROFILE,
    )
}

object CollectionRegistry {

    /**
     * Wire `collection` string → local mirror table name, for every string that
     * is NOT already identical to its table name. Mirrors the Android registry's
     * alias map (incl. the slash-forms the backend delta emits — the exact set
     * enumerated in docs/reference/sync-emitted-collections.txt).
     */
    private val aliases: Map<String, String> = buildMap {
        // Medication subcollections.
        put("adherence", MirrorTables.MEDICATION_ADHERENCE)
        put("medications.adherence", MirrorTables.MEDICATION_ADHERENCE)
        put("medications/adherence", MirrorTables.MEDICATION_ADHERENCE)
        put("history", MirrorTables.MEDICATION_HISTORY)
        put("medications.history", MirrorTables.MEDICATION_HISTORY)
        put("medications/history", MirrorTables.MEDICATION_HISTORY)
        // Goals subcollections.
        put("phases", MirrorTables.GOAL_PHASES)
        put("goals.phases", MirrorTables.GOAL_PHASES)
        put("goals/phases", MirrorTables.GOAL_PHASES)
        put("steps", MirrorTables.GOAL_STEPS)
        put("goals/phases/steps", MirrorTables.GOAL_STEPS)
        put("messages", MirrorTables.GOAL_CHAT_MESSAGES)
        put("goalChatThreads.messages", MirrorTables.GOAL_CHAT_MESSAGES)
        put("goalChatThreads/messages", MirrorTables.GOAL_CHAT_MESSAGES)
        // Nutrition split collection.
        put("nutritionDays", MirrorTables.NUTRITION_DAILY_LOGS)
        put("nutritionDays.", MirrorTables.NUTRITION_DAILY_LOGS)
        put("entries", MirrorTables.NUTRITION_ENTRIES)
        put("nutritionDays.entries", MirrorTables.NUTRITION_ENTRIES)
        put("nutritionDays/entries", MirrorTables.NUTRITION_ENTRIES)
        // Workout program scheduled sessions.
        put("scheduled", MirrorTables.WORKOUT_SCHEDULED)
        put("workoutPrograms/scheduled", MirrorTables.WORKOUT_SCHEDULED)
        put("workoutPrograms.scheduled", MirrorTables.WORKOUT_SCHEDULED)
        // Ad-hoc workout sessions.
        put("sessions", MirrorTables.ADHOC_SESSIONS)
        put("adhocWorkouts/sessions", MirrorTables.ADHOC_SESSIONS)
        put("adhocWorkouts.sessions", MirrorTables.ADHOC_SESSIONS)
        // User profile wire aliases.
        put("users", MirrorTables.USER_PROFILE)
        put("user", MirrorTables.USER_PROFILE)
        put("profile", MirrorTables.USER_PROFILE)
    }

    private val canonical: Set<String> = MirrorTables.ALL.toSet()

    /**
     * Resolve a backend `collection` string to a local table name, or `null` if
     * the client does not mirror it yet (forward-compatible: the engine logs and
     * skips rather than crashing).
     */
    fun tableFor(collection: String): String? =
        when {
            collection in canonical -> collection
            else -> aliases[collection]
        }
}
