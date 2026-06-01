package com.gte619n.healthfitness.domain.bodycomposition

import java.time.Instant
import java.time.LocalDate

enum class BodyCompositionMetric { WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI }

data class BodyCompositionPoint(
    val recordId: String,
    val metric: BodyCompositionMetric,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

/** Snapshot consumed by the dashboard hero and the overview screen. */
data class BodyCompositionSnapshot(
    val latestWeightKg: Double?,
    val latestBodyFatPercent: Double?,
    val latestLeanMassKg: Double?,
    val latestBmi: Double?,
    val latestSampleTime: Instant?,
    val sevenDayDeltaKg: Double?,
    val ninetyDayDeltaKg: Double?,
    /** metric=WEIGHT_KG, oldest-first */
    val series90d: List<BodyCompositionPoint>,
    /** metric=BODY_FAT_PERCENT, oldest-first */
    val series90dBodyFat: List<BodyCompositionPoint> = emptyList(),
)

enum class DexaRegionKey {
    TRUNK, ANDROID, GYNOID,
    ARMS_TOTAL, ARMS_RIGHT, ARMS_LEFT,
    LEGS_TOTAL, LEGS_RIGHT, LEGS_LEFT;

    /** PATCH path prefix, matches the backend's `UpdateFieldRequest.path`. */
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

data class DexaRegion(
    val totalMassLb: Double?,
    val leanTissueLb: Double?,
    val fatTissueLb: Double?,
    val regionFatPercent: Double?,
)

/** Grid cell summary used in the overview's "DEXA scans" section. */
data class DexaScanSummary(
    val scanId: String,
    val measuredOn: LocalDate?,
    val sourceFacility: String?,
    val totalMassLb: Double?,
    val totalBodyFatPercent: Double?,
)

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
    /** Returns the [DexaRegion] for the given key, or null if absent. */
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
