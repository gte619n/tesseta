package com.gte619n.healthfitness.feature.bodycomposition.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegionKey
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Inline-editable region grid for the DEXA detail screen. Each cell is
 * a [EditableNumberCell] wired through [onPatch]; the ViewModel handles
 * optimistic UI + revert.
 *
 * Columns: Region | Total mass | Lean | Fat | Fat %.
 * Rows: nine `DexaRegionKey` values in display order.
 */
@Composable
fun DexaRegionGrid(
    scan: DexaScan,
    onPatch: (path: String, value: Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            SectionTitle("Regional composition")
            Spacer(Modifier.height(10.dp))
            HeaderRow()
            Spacer(Modifier.height(4.dp))
            DexaRegionKey.values().forEachIndexed { index, key ->
                val region = scan.region(key)
                if (index > 0) Spacer(Modifier.height(2.dp))
                RegionRow(
                    label = key.displayLabel,
                    region = region,
                    onPatch = { field, value ->
                        onPatch("${key.pathKey()}.$field", value)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColHeader("Region", weight = 1.4f, alignEnd = false)
        ColHeader("Total", weight = 1f)
        ColHeader("Lean", weight = 1f)
        ColHeader("Fat", weight = 1f)
        ColHeader("Fat %", weight = 1f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ColHeader(
    text: String,
    weight: Float,
    alignEnd: Boolean = true,
) {
    Text(
        text = text.uppercase(),
        style = Hf.type.capsSm.copy(fontSize = 9.sp),
        color = Hf.colors.textTertiary,
        modifier = Modifier.weight(weight),
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

@Composable
private fun RegionRow(
    label: String,
    region: com.gte619n.healthfitness.domain.bodycomposition.DexaRegion?,
    onPatch: (field: String, value: Double?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = Hf.type.bodyMd.copy(fontSize = 12.sp),
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1.4f),
        )
        CellWrap(weight = 1f) {
            EditableNumberCell(
                value = region?.totalMassLb,
                onSave = { onPatch("totalMassLb", it) },
                decimals = 1,
            )
        }
        CellWrap(weight = 1f) {
            EditableNumberCell(
                value = region?.leanTissueLb,
                onSave = { onPatch("leanTissueLb", it) },
                decimals = 1,
            )
        }
        CellWrap(weight = 1f) {
            EditableNumberCell(
                value = region?.fatTissueLb,
                onSave = { onPatch("fatTissueLb", it) },
                decimals = 1,
            )
        }
        CellWrap(weight = 1f) {
            EditableNumberCell(
                value = region?.regionFatPercent,
                onSave = { onPatch("regionFatPercent", it) },
                decimals = 1,
            )
        }
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Hf.colors.borderSubtle),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CellWrap(
    weight: Float,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.weight(weight),
        contentAlignment = Alignment.CenterEnd,
    ) {
        content()
    }
}
