package com.gte619n.healthfitness.domain.blood

import java.time.LocalDate

/**
 * Pure derivation of the combined per-marker latest view, shared by the Blood
 * overview grid and the dashboard BloodPanel so both surfaces render identical
 * values and ordering.
 *
 * The displayed latest value for every marker is pulled from the **single most
 * recent lab report** so the grid reflects one coherent draw: a marker absent
 * from that report is emitted with a `null` value (rendered as "—"), never
 * back-filled from an older report or reading. When the user has no lab reports
 * at all, we fall back to their most recent manual [BloodReading] per marker so
 * manual-only users still see their numbers.
 *
 * Sparkline [history] is independent of that anchor: it merges manual readings
 * and all lab reports, one point per day (last write wins), over the most recent
 * 12 months. Markers are emitted in [MarkerCatalog.DISPLAY_ORDER]; markers with
 * no data are included with a `null` value and [LatestMarker.Source.NONE].
 */
object LatestMarkers {

    fun derive(
        readings: List<BloodReading>,
        reports: List<BloodTestReport>,
        today: LocalDate = LocalDate.now(),
    ): List<LatestMarker> {
        val cutoff = today.minusMonths(12)

        // The one report the grid reads its current values from: the most recent
        // dated report (createdAt breaks same-day ties). Null when there are none.
        val latestReport = reports
            .filter { it.sampleDate != null }
            .maxWithOrNull(compareBy({ it.sampleDate }, { it.createdAt }))

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

            // Current value comes from the latest report only: read this marker out
            // of it (omitted -> null -> "—"). With no reports, fall back to the most
            // recent manual reading so manual-only users aren't blanked out.
            if (latestReport != null) {
                val em = latestReport.markers.firstOrNull { matches(it.name, marker) && it.value != null }
                LatestMarker(
                    marker = marker,
                    value = em?.value,
                    unit = em?.unit ?: "",
                    sampleDate = if (em != null) latestReport.sampleDate else null,
                    reference = null,
                    flag = em?.flag,
                    history = history,
                    source = if (em != null) LatestMarker.Source.LAB else LatestMarker.Source.NONE,
                )
            } else {
                val latestEntry = byDate.entries.maxByOrNull { it.key }
                val latest = latestEntry?.value
                LatestMarker(
                    marker = marker,
                    value = latest?.value,
                    unit = latest?.unit ?: latest?.reference?.unit ?: "",
                    sampleDate = latestEntry?.key,
                    reference = latest?.reference,
                    flag = latest?.flag,
                    history = history,
                    source = if (latest != null) LatestMarker.Source.MANUAL else LatestMarker.Source.NONE,
                )
            }
        }
    }

    /** Maps an extracted-marker name onto a [BloodMarker], if it is a known one. */
    fun toMarker(name: String): BloodMarker? =
        BloodMarker.entries.firstOrNull { matches(name, it) }

    private fun matches(name: String, marker: BloodMarker): Boolean {
        val normalized = name.trim().uppercase().replace(Regex("[^A-Z0-9]"), "_")
        if (normalized == marker.name) return true
        return ALIASES[marker]?.invoke(normalized) == true
    }

    /**
     * Lab reports rarely print the bare canonical token — testosterone arrives
     * as "Total Testosterone", "Testosterone, Total, LC/MS", etc., none of which
     * normalize to "TESTOSTERONE". These per-marker matchers recognise those
     * variants, mirroring the web client's MARKER_PATTERNS, so a report keeps
     * resolving even if it was extracted before the marker was added to the
     * extraction prompt. Scoped per marker (not a blanket substring check) so we
     * don't false-positive "NON_HDL" → HDL or "VLDL" → LDL.
     *
     * The input is the upper-cased, non-alphanumerics-as-underscore form.
     */
    private val ALIASES: Map<BloodMarker, (String) -> Boolean> = mapOf(
        // Total / serum testosterone resolves to TESTOSTERONE. Free and
        // Bioavailable testosterone are clinically distinct and must NOT
        // collapse into it.
        BloodMarker.TESTOSTERONE to { n ->
            n.contains("TESTOSTERONE") && !n.contains("FREE") && !n.contains("BIO")
        },
    )
}
