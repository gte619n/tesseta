package com.gte619n.healthfitness.domain.blood

import java.time.LocalDate

/**
 * Pure derivation of the combined per-marker latest view, shared by the Blood
 * overview grid and the dashboard BloodPanel so both surfaces render identical
 * values and ordering.
 *
 * Manual [BloodReading]s and lab [BloodTestReport] markers are merged per
 * [BloodMarker]. History is capped to one point per day (last write wins) over
 * the most recent 12 months. Markers are emitted in [MarkerCatalog.DISPLAY_ORDER];
 * markers with no data are included with a `null` value and [LatestMarker.Source.NONE].
 */
object LatestMarkers {

    fun derive(
        readings: List<BloodReading>,
        reports: List<BloodTestReport>,
        today: LocalDate = LocalDate.now(),
    ): List<LatestMarker> {
        val cutoff = today.minusMonths(12)

        return MarkerCatalog.DISPLAY_ORDER.map { marker ->
            // One point per day; last value for a day wins. Track provenance so
            // we can label the latest source and surface the reference range.
            data class Acc(
                val value: Double,
                val source: MarkerHistoryPoint.Source,
                val unit: String?,
                val reference: ReferenceRange?,
                val flag: ExtractedMarker.Flag?,
            )

            val byDate = sortedMapOf<LocalDate, Acc>()

            readings.asSequence()
                .filter { it.marker == marker }
                .forEach { r ->
                    byDate[r.sampleDate] = Acc(
                        value = r.value,
                        source = MarkerHistoryPoint.Source.Manual,
                        unit = r.unit,
                        reference = r.reference,
                        flag = null,
                    )
                }

            reports.asSequence().forEach { report ->
                val date = report.sampleDate ?: return@forEach
                report.markers.asSequence()
                    .filter { matches(it.name, marker) && it.value != null }
                    .forEach { em ->
                        byDate[date] = Acc(
                            value = em.value!!,
                            source = MarkerHistoryPoint.Source.Lab(report.reportId, report.labSource),
                            unit = em.unit,
                            reference = null,
                            flag = em.flag,
                        )
                    }
            }

            val history = byDate
                .filterKeys { !it.isBefore(cutoff) }
                .map { (date, acc) -> MarkerHistoryPoint(date, acc.value, acc.source) }
                .sortedBy { it.date }

            val latestEntry = byDate.entries.maxByOrNull { it.key }
            val latest = latestEntry?.value

            val source = when (latest?.source) {
                is MarkerHistoryPoint.Source.Manual -> LatestMarker.Source.MANUAL
                is MarkerHistoryPoint.Source.Lab -> LatestMarker.Source.LAB
                null -> LatestMarker.Source.NONE
            }

            LatestMarker(
                marker = marker,
                value = latest?.value,
                unit = latest?.unit ?: latest?.reference?.unit ?: "",
                sampleDate = latestEntry?.key,
                reference = latest?.reference,
                flag = latest?.flag,
                history = history,
                source = source,
            )
        }
    }

    /** Maps an extracted-marker name onto a [BloodMarker], if it is a known one. */
    fun toMarker(name: String): BloodMarker? =
        BloodMarker.entries.firstOrNull { matches(name, it) }

    private fun matches(name: String, marker: BloodMarker): Boolean {
        val normalized = name.trim().uppercase().replace(Regex("[^A-Z0-9]"), "_")
        return normalized == marker.name
    }
}
