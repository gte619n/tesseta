package com.gte619n.healthfitness.domain.bodycomposition

import java.time.Instant
import java.time.LocalDate

/**
 * Canonical body-composition domain models. IMPL-AND-05 introduces this
 * package; IMPL-AND-01 already shipped its own variant under
 * `domain.dashboard` (WeightSummary, BodyMetric, BodyCompositionPoint)
 * for the dashboard hero. We keep the dashboard typing intact (it's wired
 * end-to-end with mappers and tests) and use these richer types for the
 * dedicated feature module's overview / DEXA paths. A future stage can
 * consolidate the two; see the Stage 05 note in
 * `docs/plans/android-impl-questions.md`.
 */

/** Mirrors the backend's `BodyCompositionMetric` enum. */
enum class BodyCompositionMetric { WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI }

/** Single raw reading from the backend's body-composition collection. */
data class BodyCompositionPoint(
    val recordId: String,
    val metric: BodyCompositionMetric,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

/**
 * Snapshot consumed by both the dashboard hero (eventually) and the
 * overview screen. Computed client-side from the raw point list returned
 * by `GET /api/me/body-composition`.
 *
 * `series90d` is metric=WEIGHT_KG, oldest-first, capped to the trailing
 * 90 days. Empty when the user has no weight readings in that window.
 */
data class BodyCompositionSnapshot(
    val latestWeightKg: Double?,
    val latestBodyFatPercent: Double?,
    val latestLeanMassKg: Double?,
    val latestBmi: Double?,
    val latestSampleTime: Instant?,
    val sevenDayDeltaKg: Double?,
    val ninetyDayDeltaKg: Double?,
    val series90d: List<BodyCompositionPoint>,
)

/**
 * Composite key used to address one of the nine DEXA regions in PATCH
 * paths and grid layouts. The `pathKey()` value matches the backend's
 * field name; the inline `EditableNumberCell` builds the full PATCH path
 * as `"<pathKey()>.<field>"` (e.g. `"trunk.leanTissueLb"`).
 */
enum class DexaRegionKey(val displayLabel: String) {
    TRUNK("Trunk"),
    ANDROID("Android"),
    GYNOID("Gynoid"),
    ARMS_TOTAL("Arms total"),
    ARMS_RIGHT("Right arm"),
    ARMS_LEFT("Left arm"),
    LEGS_TOTAL("Legs total"),
    LEGS_RIGHT("Right leg"),
    LEGS_LEFT("Left leg");

    fun pathKey(): String = when (this) {
        TRUNK -> "trunk"
        ANDROID -> "android"
        GYNOID -> "gynoid"
        ARMS_TOTAL -> "armsTotal"
        ARMS_RIGHT -> "armsRight"
        ARMS_LEFT -> "armsLeft"
        LEGS_TOTAL -> "legsTotal"
        LEGS_RIGHT -> "legsRight"
        LEGS_LEFT -> "legsLeft"
    }
}

/** One row in the regional grid. All fields nullable — vendors vary. */
data class DexaRegion(
    val totalMassLb: Double?,
    val leanTissueLb: Double?,
    val fatTissueLb: Double?,
    val regionFatPercent: Double?,
)

/** Grid card summary used in the overview's "DEXA scans" section. */
data class DexaScanSummary(
    val scanId: String,
    val measuredOn: LocalDate?,
    val sourceFacility: String?,
    val totalMassLb: Double?,
    val totalBodyFatPercent: Double?,
)

/** Full DEXA scan record returned by `GET /api/me/dexa/scans/{scanId}`. */
data class DexaScan(
    val scanId: String,
    val measuredOn: LocalDate?,
    val sourceFacility: String?,
    val totalMassLb: Double?,
    val leanTissueLb: Double?,
    val fatTissueLb: Double?,
    val totalBodyFatPercent: Double?,
    val visceralFatLb: Double?,
    val androidGynoidRatio: Double?,
    val trunk: DexaRegion?,
    val android: DexaRegion?,
    val gynoid: DexaRegion?,
    val armsTotal: DexaRegion?,
    val armsRight: DexaRegion?,
    val armsLeft: DexaRegion?,
    val legsTotal: DexaRegion?,
    val legsRight: DexaRegion?,
    val legsLeft: DexaRegion?,
    val bmdTScore: Double?,
    val bmdZScore: Double?,
    val restingMetabolicRateKcal: Int?,
) {
    fun region(key: DexaRegionKey): DexaRegion? = when (key) {
        DexaRegionKey.TRUNK -> trunk
        DexaRegionKey.ANDROID -> android
        DexaRegionKey.GYNOID -> gynoid
        DexaRegionKey.ARMS_TOTAL -> armsTotal
        DexaRegionKey.ARMS_RIGHT -> armsRight
        DexaRegionKey.ARMS_LEFT -> armsLeft
        DexaRegionKey.LEGS_TOTAL -> legsTotal
        DexaRegionKey.LEGS_RIGHT -> legsRight
        DexaRegionKey.LEGS_LEFT -> legsLeft
    }
}
