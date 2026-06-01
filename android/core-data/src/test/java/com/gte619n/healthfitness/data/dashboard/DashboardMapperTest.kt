package com.gte619n.healthfitness.data.dashboard

import com.gte619n.healthfitness.domain.dashboard.MarkerTone
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMapperTest {

    private fun reading(
        marker: String,
        value: Double,
        sampleDate: String,
        reference: ReferenceDto,
    ) = BloodReadingDto(
        readingId = "r-$marker-$sampleDate",
        marker = marker,
        value = value,
        unit = reference.unit,
        sampleDate = sampleDate,
        labSource = null,
        notes = null,
        reference = reference,
    )

    private val testosteroneRef = ReferenceDto(
        unit = "ng/dL",
        orientation = "HIGHER_IS_BETTER",
        goodThreshold = 300.0,
        displayMin = 200.0,
        displayMax = 1200.0,
    )

    private val ldlRef = ReferenceDto(
        unit = "mg/dL",
        orientation = "LOWER_IS_BETTER",
        goodThreshold = 100.0,
        displayMin = 0.0,
        displayMax = 200.0,
    )

    @Test
    fun testosteroneSortsFirstAndScoresGood() {
        val markers = BloodMarkerSummaryMapper.toDashboardMarkers(
            listOf(
                reading("LDL", value = 90.0, sampleDate = "2026-05-20", reference = ldlRef),
                reading("TESTOSTERONE", value = 650.0, sampleDate = "2026-05-30", reference = testosteroneRef),
            ),
        )

        assertEquals("TESTOSTERONE", markers.first().markerKey)
        assertEquals("Testosterone", markers.first().displayName)
        // value (650) is above the good threshold (300) for a higher-is-better
        // marker → Good.
        assertEquals(MarkerTone.Good, markers.first().tone)
    }

    @Test
    fun testosteroneBelowThresholdIsNotGood() {
        val markers = BloodMarkerSummaryMapper.toDashboardMarkers(
            listOf(
                reading("TESTOSTERONE", value = 210.0, sampleDate = "2026-05-30", reference = testosteroneRef),
            ),
        )

        val testosterone = markers.single { it.markerKey == "TESTOSTERONE" }
        // 210 < 300 threshold → below the good side.
        assertEquals(false, testosterone.tone == MarkerTone.Good)
    }
}
