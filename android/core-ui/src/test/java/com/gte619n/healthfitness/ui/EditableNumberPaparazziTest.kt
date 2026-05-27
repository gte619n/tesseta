package com.gte619n.healthfitness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.gte619n.healthfitness.ui.input.EditableNumber
import org.junit.Rule
import org.junit.Test

/**
 * Round 2 Stage A smoke test — confirms Paparazzi is wired correctly across
 * core-ui (and, by extension, the feature modules that depend on it). Covers
 * the two `EditableNumber` states the production UI exercises today: the
 * read-mode pill with a formatted value + suffix, and the "no value" hint
 * that renders the placeholder em-dash in the muted text colour.
 *
 * The "edit mode" state can't be triggered without a real input event, so
 * it's left for Stage D's `EditableNumberCell` snapshot to cover via the
 * wrapper's `initiallyEditing` affordance.
 */
class EditableNumberPaparazziTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NOTNIGHT),
    )

    @Test
    fun readMode_withValue() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp)) {
                    EditableNumber(
                        value = 189.2,
                        onCommit = {},
                        suffix = "lb",
                        decimals = 1,
                    )
                }
            }
        }
    }

    @Test
    fun readMode_emptyPlaceholder() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp)) {
                    EditableNumber(
                        value = null,
                        onCommit = {},
                        suffix = "lb",
                        decimals = 1,
                    )
                }
            }
        }
    }
}
