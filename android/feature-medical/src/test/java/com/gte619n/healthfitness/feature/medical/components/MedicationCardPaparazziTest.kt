package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.gte619n.healthfitness.domain.medications.AdherenceSummary
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Round 2 Stage D backstop snapshot for the medications feature module.
 * One MedicationCard snapshot exercises the full card layout: drug-form
 * placeholder icon (null imageUrl/fallback avoids Coil's network path),
 * name + category pill, dose/frequency line, time-window chip row, and
 * the 30-day adherence sparkline.
 *
 * Discontinued / no-time-slots / no-adherence variants are deferred until
 * the medications feature surfaces grow real screens that exercise them
 * — the production today only renders the active path on the meds
 * surface.
 */
class MedicationCardPaparazziTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NOTNIGHT),
    )

    @Test
    fun activeWithFallbackImage() {
        val drug = Drug(
            drugId = "drug-1",
            name = "Tirzepatide",
            aliases = listOf("Mounjaro"),
            category = DrugCategory.PRESCRIPTION,
            form = DrugForm.INJECTABLE_VIAL,
            defaultUnit = "mg",
            commonDoses = listOf("2.5 mg", "5 mg", "7.5 mg"),
            imageUrl = null,
            imageFallback = null,
            suggestedMarkers = emptyList(),
            description = null,
        )
        val adherence = AdherenceSummary(
            last30Days = List(30) { i ->
                AdherenceSummary.DayAdherence(
                    date = LocalDate.of(2026, 4, 27).plusDays(i.toLong()),
                    taken = i % 7 != 3,
                )
            },
            percentage = 85.7,
        )
        val medication = Medication(
            medicationId = "med-1",
            drugId = drug.drugId,
            drug = drug,
            customName = null,
            status = MedicationStatus.ACTIVE,
            dose = 5.0,
            unit = "mg",
            frequency = FrequencyConfig(
                type = FrequencyType.WEEKLY,
                timesPerPeriod = 1,
            ),
            timeSlots = listOf(
                TimeSlot(window = TimeWindow.MORNING, dose = 5.0),
            ),
            protocolId = null,
            notes = null,
            prescribedBy = "Dr. Smith",
            startDate = LocalDate.of(2026, 1, 8),
            endDate = null,
            discontinueReason = null,
            discontinueNotes = null,
            correlatedMarkers = emptyList(),
            adherence = adherence,
        )

        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp).width(240.dp)) {
                    MedicationCard(
                        medication = medication,
                        onClick = {},
                    )
                }
            }
        }
    }
}
