package com.gte619n.healthfitness.feature.blood

import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.LatestMarkers
import com.gte619n.healthfitness.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestMarkersTest {

    private val today = LocalDate.of(2026, 5, 30)

    private fun ref() = ReferenceRange(
        unit = "mg/dL",
        orientation = ReferenceRange.Orientation.LOWER_IS_BETTER,
        goodThreshold = 100.0,
        displayMin = 0.0,
        displayMax = 200.0,
    )

    private fun reading(value: Double, date: LocalDate) = BloodReading(
        readingId = "r-$value-$date",
        marker = BloodMarker.LDL,
        value = value,
        unit = "mg/dL",
        sampleDate = date,
        labSource = null,
        notes = null,
        reference = ref(),
    )

    @Test
    fun emitsAllCatalogMarkersInDisplayOrder() {
        val result = LatestMarkers.derive(emptyList(), emptyList(), today)
        assertEquals(MarkerCatalog.DISPLAY_ORDER, result.map { it.marker })
    }

    @Test
    fun markerWithNoDataHasNullValueAndNoneSource() {
        val ldl = LatestMarkers.derive(emptyList(), emptyList(), today).first { it.marker == BloodMarker.LDL }
        assertNull(ldl.value)
        assertEquals(LatestMarker.Source.NONE, ldl.source)
        assertTrue(ldl.history.isEmpty())
    }

    @Test
    fun latestReadingWinsAndSourceIsManual() {
        val readings = listOf(
            reading(120.0, today.minusMonths(2)),
            reading(82.0, today.minusDays(3)),
        )
        val ldl = LatestMarkers.derive(readings, emptyList(), today).first { it.marker == BloodMarker.LDL }
        assertEquals(82.0, ldl.value!!, 0.0001)
        assertEquals(LatestMarker.Source.MANUAL, ldl.source)
        assertEquals(2, ldl.history.size)
        assertEquals(today.minusMonths(2), ldl.history.first().date)
    }

    @Test
    fun sameDayEntriesDedupeLastWriteWins() {
        val readings = listOf(reading(120.0, today))
        val report = BloodTestReport(
            reportId = "rep1",
            sampleDate = today,
            labSource = "Quest",
            markers = listOf(ExtractedMarker("LDL", 90.0, "mg/dL", null, null, null)),
            pdfDownloadPath = "/api/me/blood/reports/rep1/pdf",
            createdAt = Instant.EPOCH,
        )
        val ldl = LatestMarkers.derive(readings, listOf(report), today).first { it.marker == BloodMarker.LDL }
        assertEquals(1, ldl.history.size)
        assertEquals(90.0, ldl.value!!, 0.0001)
        assertEquals(LatestMarker.Source.LAB, ldl.source)
    }

    @Test
    fun pointsOlderThan12MonthsExcluded() {
        val readings = listOf(
            reading(150.0, today.minusMonths(14)),
            reading(82.0, today.minusDays(1)),
        )
        val ldl = LatestMarkers.derive(readings, emptyList(), today).first { it.marker == BloodMarker.LDL }
        assertEquals(1, ldl.history.size)
        assertEquals(82.0, ldl.history.first().value, 0.0001)
    }

    @Test
    fun toMarkerMapsCanonicalAndDashedNames() {
        assertEquals(BloodMarker.LDL, LatestMarkers.toMarker("LDL"))
        assertEquals(BloodMarker.HS_CRP, LatestMarkers.toMarker("hs-CRP"))
        assertNull(LatestMarkers.toMarker("Vitamin D"))
    }
}
