package com.gte619n.healthfitness.feature.settings.biometrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.biometrics.BiometricSummary
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun BiometricsSection(
    viewModel: BiometricsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val weightUnit by viewModel.weightUnit.collectAsStateWithLifecycle()

    HfCard(transparent = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Biometrics", style = Hf.type.headingSm, color = Hf.colors.textPrimary)
            Text(
                "Choose which metrics show on your dashboard. Each shows its latest " +
                    "reading, when it was recorded, and how often it arrives.",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )

            when (val s = state) {
                is BiometricsSettingsViewModel.UiState.Loading ->
                    Text("Loading…", style = Hf.type.bodySm, color = Hf.colors.textTertiary)

                is BiometricsSettingsViewModel.UiState.Error ->
                    Text(s.message, style = Hf.type.bodySm, color = Hf.colors.alert)

                is BiometricsSettingsViewModel.UiState.Loaded ->
                    s.items.forEach { item ->
                        BiometricRow(item, weightUnit, onToggle = { viewModel.toggle(item.key) })
                    }
            }
        }
    }
}

@Composable
private fun BiometricRow(
    item: BiometricSummary,
    weightUnit: WeightUnit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(item.label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(
                subtitle(item, weightUnit),
                style = Hf.type.monoSm,
                color = Hf.colors.textTertiary,
            )
        }
        Switch(
            checked = item.visible,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Hf.colors.textInverse,
                checkedTrackColor = Hf.colors.accent,
            ),
        )
    }
}

private fun subtitle(s: BiometricSummary, weightUnit: WeightUnit): String =
    "${formatLatest(s, weightUnit)} · ${observedLabel(s.latestAt)} · ${cadenceLabel(s.avgIntervalDays)}"

private fun formatLatest(s: BiometricSummary, weightUnit: WeightUnit): String {
    val v = s.latestValue ?: return "—"
    return when (s.key) {
        "WEIGHT" ->
            UnitFormat.weightValueString(v * UnitFormat.LB_PER_KG, weightUnit) +
                " " + UnitFormat.weightLabel(weightUnit)
        "BODY_FAT" -> "%.1f%%".format(v)
        "SLEEP" -> "%.1f h".format(v / 60.0)
        "STEPS" -> "%,d steps".format(v.roundToInt())
        "RESTING_HR" -> "${v.roundToInt()} bpm"
        "HRV" -> "${v.roundToInt()} ms"
        else -> v.toString()
    }
}

private val OBSERVED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

private fun observedLabel(at: Instant?, now: Instant = Instant.now()): String {
    if (at == null) return "no data"
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 7L -> "${days}d ago"
        else -> OBSERVED_DATE.format(at.atZone(zone))
    }
}

private fun cadenceLabel(avgIntervalDays: Double?): String {
    if (avgIntervalDays == null) return "no recent data"
    return when {
        avgIntervalDays in 0.9..1.15 -> "≈ daily"
        avgIntervalDays >= 1 -> "≈ every %.1fd".format(avgIntervalDays)
        else -> "≈ ${(7 / avgIntervalDays).roundToInt()}×/wk"
    }
}
