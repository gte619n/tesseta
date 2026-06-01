package com.gte619n.healthfitness.data.bodycomposition.dto

import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegion
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import java.time.Instant
import java.time.LocalDate

private fun parseMetric(raw: String?): BodyCompositionMetric? = when (raw) {
    "WEIGHT_KG" -> BodyCompositionMetric.WEIGHT_KG
    "BODY_FAT_PERCENT" -> BodyCompositionMetric.BODY_FAT_PERCENT
    "LEAN_MASS_KG" -> BodyCompositionMetric.LEAN_MASS_KG
    "BMI" -> BodyCompositionMetric.BMI
    else -> null
}

private fun parseInstant(raw: String?): Instant? =
    raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun parseLocalDate(raw: String?): LocalDate? =
    raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * Maps a reading DTO to a domain point. Returns null when the row is missing
 * the fields required to be a usable point (metric, value, sampleTime).
 */
fun BodyCompositionReadingDto.toDomainOrNull(): BodyCompositionPoint? {
    val metric = parseMetric(metric) ?: return null
    val value = value ?: return null
    val sampleTime = parseInstant(sampleTime) ?: return null
    return BodyCompositionPoint(
        recordId = recordId ?: "",
        metric = metric,
        value = value,
        sampleTime = sampleTime,
        sourcePlatform = sourcePlatform,
        recordingMethod = recordingMethod,
    )
}

fun DexaRegionDto.toDomain(): DexaRegion = DexaRegion(
    totalMassLb = totalMassLb,
    leanTissueLb = leanTissueLb,
    fatTissueLb = fatTissueLb,
    regionFatPercent = regionFatPercent,
)

fun DexaScanSummaryDto.toSummary(): DexaScanSummary = DexaScanSummary(
    scanId = scanId ?: "",
    measuredOn = parseLocalDate(measuredOn),
    sourceFacility = sourceFacility,
    totalMassLb = totalMassLb,
    totalBodyFatPercent = totalBodyFatPercent,
)

fun DexaScanDto.toDomain(): DexaScan = DexaScan(
    scanId = scanId ?: "",
    measuredOn = parseLocalDate(measuredOn),
    sourceFacility = sourceFacility,
    totalMassLb = totalMassLb,
    leanTissueLb = leanTissueLb,
    fatTissueLb = fatTissueLb,
    totalBodyFatPercent = totalBodyFatPercent,
    visceralFatLb = visceralFatLb,
    androidGynoidRatio = androidGynoidRatio,
    trunk = trunk?.toDomain(),
    android = android?.toDomain(),
    gynoid = gynoid?.toDomain(),
    armsTotal = armsTotal?.toDomain(),
    armsRight = armsRight?.toDomain(),
    armsLeft = armsLeft?.toDomain(),
    legsTotal = legsTotal?.toDomain(),
    legsRight = legsRight?.toDomain(),
    legsLeft = legsLeft?.toDomain(),
    bmdTScore = bmdTScore,
    bmdZScore = bmdZScore,
    restingMetabolicRateKcal = restingMetabolicRateKcal,
)
