package com.gte619n.healthfitness.feature.medical.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.feature.medical.components.DrugImage
import com.gte619n.healthfitness.feature.medical.components.categoryLabel
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
internal fun ModalTopBar(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = Hf.type.headingLg.copy(fontSize = 18.sp), color = Hf.colors.textPrimary)
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close",
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(22.dp).clickable { onClose() },
        )
    }
}

@Composable
internal fun DrugResultRow(drug: Drug, onClick: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DrugImage(drug = drug, modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(drug.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Text(
                    "${categoryLabel(drug.category)} · ${drug.defaultUnit}",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
internal fun NotFoundPrompt(message: String?, onManual: () -> Unit) {
    Column {
        Text(
            message ?: "No match found.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(label = "Add manually", onClick = onManual)
    }
}

@Composable
internal fun DoseAndUnitRow(
    dose: String,
    unit: String,
    onDoseChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            LabeledField(label = "Dose") {
                HfTextField(value = dose, onValueChange = onDoseChange, placeholder = "e.g. 200")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            LabeledField(label = "Unit") {
                HfTextField(value = unit, onValueChange = onUnitChange, placeholder = "mg")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun <T> EnumChips(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) Hf.colors.accentBg else Hf.colors.surface,
                        RoundedCornerShape(7.dp),
                    )
                    .border(
                        0.5.dp,
                        if (isSelected) Hf.colors.accent else Hf.colors.borderDefault,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    label(entry),
                    style = Hf.type.bodySm,
                    color = if (isSelected) Hf.colors.accentDim else Hf.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
internal fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CapsLabel(label, color = Hf.colors.textSecondary)
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@Composable
internal fun HfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Hf.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Hf.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (enabled) Hf.colors.accent else Hf.colors.muted,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(label, style = Hf.type.capsSm, color = Hf.colors.textInverse)
    }
}

@Composable
internal fun TextButtonRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(label, style = Hf.type.capsSm, color = Hf.colors.textSecondary)
    }
}

internal fun formLabel(form: DrugForm): String = when (form) {
    DrugForm.INJECTABLE_VIAL -> "Injectable"
    DrugForm.TABLET -> "Tablet"
    DrugForm.CAPSULE -> "Capsule"
    DrugForm.SOFTGEL -> "Softgel"
    DrugForm.CREAM -> "Cream"
    DrugForm.PATCH -> "Patch"
    DrugForm.LIQUID -> "Liquid"
    DrugForm.POWDER -> "Powder"
}
