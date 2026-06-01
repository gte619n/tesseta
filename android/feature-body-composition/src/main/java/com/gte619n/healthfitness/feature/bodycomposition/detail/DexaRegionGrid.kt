package com.gte619n.healthfitness.feature.bodycomposition.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegion
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegionKey
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

private fun DexaRegionKey.label(): String = when (this) {
    DexaRegionKey.TRUNK -> "Trunk"
    DexaRegionKey.ANDROID -> "Android"
    DexaRegionKey.GYNOID -> "Gynoid"
    DexaRegionKey.ARMS_TOTAL -> "Arms (total)"
    DexaRegionKey.ARMS_RIGHT -> "Arms (right)"
    DexaRegionKey.ARMS_LEFT -> "Arms (left)"
    DexaRegionKey.LEGS_TOTAL -> "Legs (total)"
    DexaRegionKey.LEGS_RIGHT -> "Legs (right)"
    DexaRegionKey.LEGS_LEFT -> "Legs (left)"
}

/**
 * A click-to-edit grid of the nine DEXA regions. Each cell PATCHes a single
 * field; the `path` strings match the backend convention
 * (e.g. "trunk.leanTissueLb").
 */
@Composable
fun DexaRegionGrid(
    scan: DexaScan,
    onPatch: (path: String, value: Double?) -> Unit,
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit = WeightUnit.POUNDS,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DexaRegionKey.entries.forEach { key ->
            RegionRow(
                label = key.label(),
                region = scan.region(key),
                pathPrefix = key.pathKey(),
                onPatch = onPatch,
                weightUnit = weightUnit,
            )
        }
    }
}

@Composable
private fun RegionRow(
    label: String,
    region: DexaRegion?,
    pathPrefix: String,
    onPatch: (path: String, value: Double?) -> Unit,
    weightUnit: WeightUnit,
) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(text = label, style = Hf.type.headingSm)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cell(
                label = "Total",
                value = region?.totalMassLb,
                weightUnit = weightUnit,
                onSave = { onPatch("$pathPrefix.totalMassLb", it) },
                modifier = Modifier.weight(1f),
            )
            Cell(
                label = "Lean",
                value = region?.leanTissueLb,
                weightUnit = weightUnit,
                onSave = { onPatch("$pathPrefix.leanTissueLb", it) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cell(
                label = "Fat",
                value = region?.fatTissueLb,
                weightUnit = weightUnit,
                onSave = { onPatch("$pathPrefix.fatTissueLb", it) },
                modifier = Modifier.weight(1f),
            )
            Cell(
                label = "Fat %",
                value = region?.regionFatPercent,
                unit = "%",
                onSave = { onPatch("$pathPrefix.regionFatPercent", it) },
                modifier = Modifier.weight(1f),
            )
        }
      }
    }
}

@Composable
private fun Cell(
    label: String,
    value: Double?,
    onSave: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    weightUnit: WeightUnit? = null,
) {
    Column(modifier = modifier) {
        CapsLabel(label)
        EditableNumberCell(
            value = value,
            unit = unit,
            weightUnit = weightUnit,
            onSave = onSave,
        )
    }
}
