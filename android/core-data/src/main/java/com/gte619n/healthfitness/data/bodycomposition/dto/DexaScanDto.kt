package com.gte619n.healthfitness.data.bodycomposition.dto

/** A single DEXA region's masses (lbs). */
data class DexaRegionDto(
    val totalMassLb: Double?,
    val leanTissueLb: Double?,
    val fatTissueLb: Double?,
    val regionFatPercent: Double?,
)

/** Grid summary row from `GET /api/me/dexa/scans`. */
data class DexaScanSummaryDto(
    val scanId: String?,
    val measuredOn: String?,
    val sourceFacility: String?,
    val totalMassLb: Double?,
    val totalBodyFatPercent: Double?,
)

/** Full scan from `GET /api/me/dexa/scans/{scanId}`. */
data class DexaScanDto(
    val scanId: String?,
    val measuredOn: String?,
    val sourceFacility: String?,
    val totalMassLb: Double?,
    val leanTissueLb: Double?,
    val fatTissueLb: Double?,
    val totalBodyFatPercent: Double?,
    val visceralFatLb: Double?,
    val androidGynoidRatio: Double?,
    val trunk: DexaRegionDto?,
    val android: DexaRegionDto?,
    val gynoid: DexaRegionDto?,
    val armsTotal: DexaRegionDto?,
    val armsRight: DexaRegionDto?,
    val armsLeft: DexaRegionDto?,
    val legsTotal: DexaRegionDto?,
    val legsRight: DexaRegionDto?,
    val legsLeft: DexaRegionDto?,
    val bmdTScore: Double?,
    val bmdZScore: Double?,
    val restingMetabolicRateKcal: Int?,
)

/** Body for `PATCH /api/me/dexa/scans/{scanId}/field`. */
data class PatchFieldRequest(
    val path: String,
    val value: Double?,
)
