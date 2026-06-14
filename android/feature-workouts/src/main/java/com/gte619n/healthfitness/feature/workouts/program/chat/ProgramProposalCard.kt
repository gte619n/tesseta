package com.gte619n.healthfitness.feature.workouts.program.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.feature.workouts.program.prescriptionSummary
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import androidx.compose.foundation.text.KeyboardOptions

/**
 * The Android twin of the web WorkoutProgramProposalCard (IMPL-AND-18). Renders
 * the streamed deep program inline in the chat: editable title/description, a
 * per-phase breakdown of days → prescriptions showing the **prescribed weight**
 * (targetWeightLbs, editable) with the RPE/%1RM fallback and a tap-to-reveal
 * "why" (loadBasis, R6), plus a **per-phase nutrition strip** (kcal + macros +
 * note, falling back to the program-level guidance). Validator issues stream
 * inline (R1). On "Create program" the card collapses to a confirmation linking
 * to the new program.
 */
@Composable
fun ProgramProposalCard(
    edit: ProgramProposalEdit,
    issues: List<String>,
    warnings: List<String>,
    committedProgramId: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpenProgram: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (committedProgramId != null) {
        ProgramCreatedCard(programId = committedProgramId, onOpenProgram = onOpenProgram, modifier = modifier)
        return
    }

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            CapsLabel("Proposed program", color = Hf.colors.accentDim)
            Spacer(Modifier.height(8.dp))

            if (issues.isNotEmpty()) {
                IssuesBanner(issues)
                Spacer(Modifier.height(8.dp))
            }

            if (warnings.isNotEmpty()) {
                WarningsBanner(warnings)
                Spacer(Modifier.height(8.dp))
            }

            FieldLabel("Title")
            ProposalTextField(
                value = edit.title.value,
                onChange = { edit.title.value = it },
                placeholder = "Program title",
            )
            Spacer(Modifier.height(8.dp))
            FieldLabel("Description")
            ProposalTextField(
                value = edit.description.value,
                onChange = { edit.description.value = it },
                placeholder = "What is this program about?",
                singleLine = false,
            )

            // Program-level nutrition fallback shown once at the top when no phase
            // carries its own guidance (R4: a one-line program-level summary).
            val programNutrition = edit.nutritionGuidance
            if (programNutrition != null && !programNutrition.isEmpty &&
                edit.phases.all { it.nutritionGuidanceOrNull() == null }
            ) {
                Spacer(Modifier.height(10.dp))
                NutritionStrip(programNutrition, label = "Nutrition")
            }

            Spacer(Modifier.height(14.dp))
            edit.phases.forEachIndexed { index, phase ->
                PhaseSection(index = index, phase = phase)
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
                        .clickable(enabled = !saving) { onDiscard() }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Discard", style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Hf.colors.accent, RoundedCornerShape(9.dp))
                        .clickable(enabled = !saving) { onSave() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (saving) "Creating…" else "Create program",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textInverse,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseSection(index: Int, phase: PhaseEdit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(9.dp))
            .background(Hf.colors.canvas, RoundedCornerShape(9.dp))
            .padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsLabel("Phase ${index + 1}", color = Hf.colors.accentDim)
            Spacer(Modifier.weight(1f))
            phase.titleOrNull()?.let {
                Text(it, style = Hf.type.bodyMd, color = Hf.colors.textPrimary, maxLines = 1)
            }
        }

        phase.nutritionGuidanceOrNull()?.let {
            Spacer(Modifier.height(8.dp))
            NutritionStrip(it, label = "Phase nutrition")
        }

        phase.days.forEach { day ->
            Spacer(Modifier.height(8.dp))
            DaySection(day)
        }
    }
}

@Composable
private fun DaySection(day: DayEdit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        day.dayLabelOrNull()?.let {
            CapsLabel(it, color = Hf.colors.textSecondary, size = 9)
            Spacer(Modifier.height(4.dp))
        }
        day.blocks.forEach { block ->
            block.prescriptions.forEach { rx ->
                PrescriptionRow(rx)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PrescriptionRow(rx: PrescriptionEdit) {
    var showWhy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderSubtle, RoundedCornerShape(8.dp))
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rx.exerciseName(),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textPrimary,
                    maxLines = 1,
                )
                val summary = prescriptionSummary(rx.prescription())
                if (summary.isNotBlank()) {
                    Text(summary, style = Hf.type.bodySm, color = Hf.colors.textTertiary, maxLines = 1)
                }
            }
            // The "why" affordance — shown only when a load basis exists (R6).
            if (!rx.loadBasis.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Why this weight",
                    tint = if (showWhy) Hf.colors.accent else Hf.colors.textTertiary,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { showWhy = !showWhy }
                        .padding(4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        FieldLabel("Prescribed weight (lb)")
        ProposalTextField(
            value = rx.targetWeightLbs.value,
            onChange = { rx.targetWeightLbs.value = it },
            placeholder = rx.weightPlaceholder(),
            keyboardType = KeyboardType.Number,
        )

        AnimatedVisibility(visible = showWhy && !rx.loadBasis.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Hf.colors.accentBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(rx.loadBasis.orEmpty(), style = Hf.type.bodySm, color = Hf.colors.accentDim)
            }
        }
    }
}

@Composable
private fun NutritionStrip(guidance: NutritionGuidance, label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.goodBg, RoundedCornerShape(8.dp))
            .padding(9.dp),
    ) {
        CapsLabel(label, color = Hf.colors.accentDim, size = 9)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            guidance.kcal?.let { Pill("$it kcal", tone = HfTone.Good) }
            guidance.proteinG?.let { Pill("P ${it}g", tone = HfTone.Neutral) }
            guidance.carbsG?.let { Pill("C ${it}g", tone = HfTone.Neutral) }
            guidance.fatG?.let { Pill("F ${it}g", tone = HfTone.Neutral) }
        }
        if (!guidance.note.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(guidance.note!!, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        }
    }
}

/** Hard blockers (impossible loads/equipment) — red; these stop a commit. */
@Composable
private fun IssuesBanner(issues: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.alertBg, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CapsLabel("Needs fixing", color = Hf.colors.alert, size = 9)
            issues.forEach { Text("• $it", style = Hf.type.bodySm, color = Hf.colors.alert) }
        }
    }
}

/** Soft advisories (volume/deload/ramp) — amber; you can save anyway (R1). */
@Composable
private fun WarningsBanner(warnings: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.warnBg, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CapsLabel("Advisory — you can save anyway", color = Hf.colors.warn, size = 9)
            warnings.forEach { Text("• $it", style = Hf.type.bodySm, color = Hf.colors.warn) }
        }
    }
}

@Composable
private fun ProgramCreatedCard(programId: String, onOpenProgram: (String) -> Unit, modifier: Modifier) {
    HfCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = Hf.colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Program created", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                Text("Saved to your programs.", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
            Box(
                modifier = Modifier
                    .background(Hf.colors.accentBg, RoundedCornerShape(8.dp))
                    .clickable { onOpenProgram(programId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("View program", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
            }
        }
    }
}

// ---- atoms ----

@Composable
private fun FieldLabel(text: String) {
    CapsLabel(text, color = Hf.colors.textTertiary, size = 9)
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun ProposalTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
            .background(Hf.colors.canvas, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = Hf.type.bodyMd.copy(color = Hf.colors.textPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(Hf.colors.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
                }
                inner()
            },
        )
    }
}
