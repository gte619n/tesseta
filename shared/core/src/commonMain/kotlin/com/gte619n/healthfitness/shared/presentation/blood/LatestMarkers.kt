package com.gte619n.healthfitness.shared.presentation.blood

import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import com.gte619n.healthfitness.shared.domain.blood.BloodReading
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.shared.domain.blood.LatestMarker
import com.gte619n.healthfitness.shared.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.shared.domain.blood.MarkerHistoryPoint
import com.gte619n.healthfitness.shared.domain.blood.ReferenceRange
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — KMP port of
 * android/core-domain/.../domain/blood/LatestMarkers.kt (java.time → kotlinx-datetime).
 *
 * Lives in the blood presentation package (not domain/blood) because the shared
 * domain/blood models are a frozen, already-ported reference; the Blood VMs need
 * this pure derivation, so it is co-located with them here. Logic is 1:1 with the
 * Android source.
 *
 * Pure derivation of the combined per-marker latest view, shared by the Blood
 * overview grid and the marker-detail history. The displayed latest value for
 * every marker is pulled from the single most recent lab report so the grid
 * reflects one coherent draw; a marker absent from that report is emitted with a
 * `null` value. With no lab reports at all, we fall back to the most recent manual
 * [BloodReading] per marker. The sparkline [LatestMarker.history] merges manual
 * readings and all lab reports, one point per day (last write wins), over the most
 * recent 12 months, in [MarkerCatalog.DISPLAY_ORDER].
 */
object LatestMarkers {

    fun derive(
        readings: List<BloodReading>,
        reports: List<BloodTestReport>,
        today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    ): List<LatestMarker> {
        val cutoff = today.minus(DatePeriod(months = 12))

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

            val byDate = HashMap<LocalDate, Acc>()

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
                .filterKeys { it >= cutoff }
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
     * Per-marker matchers recognising the variants labs print (e.g. "Total
     * Testosterone") that don't normalize to the bare canonical token. Scoped per
     * marker so we don't false-positive "NON_HDL" → HDL or "VLDL" → LDL. Input is
     * the upper-cased, non-alphanumerics-as-underscore form.
     */
    private val ALIASES: Map<BloodMarker, (String) -> Boolean> = mapOf(
        // Total / serum testosterone resolves to TESTOSTERONE. Free and
        // Bioavailable testosterone are clinically distinct and must NOT collapse.
        BloodMarker.TESTOSTERONE to { n: String ->
            n.contains("TESTOSTERONE") && !n.contains("FREE") && !n.contains("BIO")
        },
    )
}
