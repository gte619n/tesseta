package com.gte619n.healthfitness.data.workouts.trt

import com.gte619n.healthfitness.domain.workouts.trt.DangerFlag
import com.gte619n.healthfitness.domain.workouts.trt.DangerSeverity
import com.gte619n.healthfitness.domain.workouts.trt.TrtContext
import com.gte619n.healthfitness.domain.workouts.trt.TrtMarker
import com.gte619n.healthfitness.domain.workouts.trt.TrtMarkerStatus
import com.gte619n.healthfitness.domain.workouts.trt.TrtTrend
import java.time.LocalDate

// Wire mirror of `GET api/me/workout-programs/chat/trt-context` (IMPL-AND-18).
// Enums parse with safe fallback so an unknown server value degrades to UNKNOWN
// rather than failing the whole decode.

data class TrtContextDto(
    val onTrt: Boolean = false,
    val markers: List<TrtMarkerDto> = emptyList(),
    val dangerFlags: List<DangerFlagDto> = emptyList(),
)

data class TrtMarkerDto(
    val name: String,
    val label: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val sampleDate: LocalDate? = null,
    val trend: String? = null,
    val status: String? = null,
)

data class DangerFlagDto(
    val marker: String? = null,
    val severity: String? = null,
    val message: String? = null,
)

private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }
        ?: fallback

fun TrtMarkerDto.toDomain(): TrtMarker = TrtMarker(
    name = name,
    label = label ?: name,
    value = value,
    unit = unit,
    refLow = refLow,
    refHigh = refHigh,
    sampleDate = sampleDate,
    trend = parseEnum(trend, TrtTrend.UNKNOWN),
    status = parseEnum(status, TrtMarkerStatus.UNKNOWN),
)

fun DangerFlagDto.toDomain(): DangerFlag = DangerFlag(
    marker = marker.orEmpty(),
    severity = parseEnum(severity, DangerSeverity.WARNING),
    message = message.orEmpty(),
)

fun TrtContextDto.toDomain(): TrtContext = TrtContext(
    onTrt = onTrt,
    markers = markers.map { it.toDomain() },
    dangerFlags = dangerFlags.map { it.toDomain() },
)
