package com.gte619n.healthfitness.domain.blood

import java.time.LocalDate

/**
 * Builds the unified per-marker view from the user's manual readings
 * plus extracted markers in their uploaded lab reports.
 *
 * Logic mirrors the web's `buildMarkerHistory` + dashboard derivation:
 *  - one [LatestMarker] per known [BloodMarker], in [MarkerCatalog.DISPLAY_ORDER]
 *  - the "latest" value picks the most-recent sampleDate across both
 *    sources (ties prefer manual entries — they're explicit)
 *  - history is the last 12 months, sorted ascending, deduped by date
 *    (last value wins per day)
 *  - the [ReferenceRange] is taken from the latest matching reading; if
 *    only lab data exists for a marker, reference is null and the UI
 *    falls back to "no range bar"
 */
object LatestMarkers {

    fun derive(
        readings: List<BloodReading>,
        reports: List<BloodTestReport>,
        today: LocalDate = LocalDate.now(),
    ): List<LatestMarker> {
        val cutoff = today.minusDays(365)

        // Manual reading history keyed by marker.
        val byManual: Map<BloodMarker, List<BloodReading>> =
            readings.groupBy { it.marker }

        // Lab-extracted history keyed by marker. A report may contain
        // multiple known markers, so flatten before grouping.
        data class LabPoint(
            val marker: BloodMarker,
            val report: BloodTestReport,
            val extracted: ExtractedMarker,
        )
        val labFlat: List<LabPoint> = reports.flatMap { report ->
            report.markers.mapNotNull { em ->
                val key = MarkerCatalog.fromExtractedName(em.name) ?: return@mapNotNull null
                if (em.value == null) return@mapNotNull null
                if (report.sampleDate == null) return@mapNotNull null
                LabPoint(key, report, em)
            }
        }
        val byLab: Map<BloodMarker, List<LabPoint>> = labFlat.groupBy { it.marker }

        return MarkerCatalog.DISPLAY_ORDER.map { marker ->
            val manualPts: List<MarkerHistoryPoint> = byManual[marker].orEmpty()
                .filter { !it.sampleDate.isBefore(cutoff) }
                .map { r ->
                    MarkerHistoryPoint(
                        date = r.sampleDate,
                        value = r.value,
                        unit = r.unit,
                        source = MarkerHistoryPoint.Source.Manual,
                    )
                }
            val labPts: List<MarkerHistoryPoint> = byLab[marker].orEmpty()
                .filter { it.report.sampleDate != null && !it.report.sampleDate.isBefore(cutoff) }
                .map { lp ->
                    MarkerHistoryPoint(
                        date = lp.report.sampleDate!!,
                        value = lp.extracted.value!!,
                        unit = lp.extracted.unit ?: lp.report.markers.firstOrNull()?.unit.orEmpty(),
                        source = MarkerHistoryPoint.Source.Lab(
                            reportId = lp.report.reportId,
                            labSource = lp.report.labSource,
                        ),
                    )
                }

            val merged = (manualPts + labPts)
                .sortedBy { it.date }
                .let(::dedupeByDate)

            // Latest value: prefer manual on date ties.
            val latestManual = byManual[marker].orEmpty().maxByOrNull { it.sampleDate }
            val latestLab = byLab[marker].orEmpty()
                .filter { it.report.sampleDate != null }
                .maxByOrNull { it.report.sampleDate!! }

            val pickManual = when {
                latestManual == null -> false
                latestLab == null -> true
                else -> !latestManual.sampleDate.isBefore(latestLab.report.sampleDate)
            }

            val latestValue: Double?
            val latestUnit: String
            val latestDate: LocalDate?
            val latestReference: ReferenceRange?
            val latestFlag: ExtractedMarker.Flag?
            val source: LatestMarker.Source

            if (pickManual && latestManual != null) {
                latestValue = latestManual.value
                latestUnit = latestManual.unit
                latestDate = latestManual.sampleDate
                latestReference = latestManual.reference
                latestFlag = null
                source = LatestMarker.Source.MANUAL
            } else if (latestLab != null) {
                latestValue = latestLab.extracted.value
                latestUnit = latestLab.extracted.unit ?: latestManual?.reference?.unit.orEmpty()
                latestDate = latestLab.report.sampleDate
                latestReference = latestManual?.reference
                latestFlag = latestLab.extracted.flag
                source = LatestMarker.Source.LAB
            } else {
                latestValue = null
                latestUnit = latestManual?.reference?.unit.orEmpty()
                latestDate = null
                latestReference = latestManual?.reference
                latestFlag = null
                source = LatestMarker.Source.NONE
            }

            LatestMarker(
                marker = marker,
                value = latestValue,
                unit = latestUnit,
                sampleDate = latestDate,
                reference = latestReference,
                flag = latestFlag,
                history = merged,
                source = source,
            )
        }
    }

    /** Same-day collapse: last entry wins. Input must be ascending. */
    private fun dedupeByDate(sorted: List<MarkerHistoryPoint>): List<MarkerHistoryPoint> {
        if (sorted.isEmpty()) return sorted
        val out = ArrayList<MarkerHistoryPoint>(sorted.size)
        for (p in sorted) {
            if (out.isNotEmpty() && out.last().date == p.date) {
                out[out.size - 1] = p
            } else {
                out.add(p)
            }
        }
        return out
    }
}
