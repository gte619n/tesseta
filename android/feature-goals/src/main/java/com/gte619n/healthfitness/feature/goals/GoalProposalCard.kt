package com.gte619n.healthfitness.feature.goals

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.goals.Comparator
import com.gte619n.healthfitness.domain.goals.GoalDomain
import com.gte619n.healthfitness.domain.goals.MetricRegistry
import com.gte619n.healthfitness.domain.goals.StepKind
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Android twin of the web <GoalProposalCard>: an editable Goal/Phase/Step
 * roadmap rendered inline in the chat (and reusable as a blank manual editor).
 * Fully editable — title/description/domain/target date; phases with M3 date
 * pickers; steps with a kind selector and, for non-MANUAL kinds, a metric
 * binding editor (metricKey dropdown over the 14 registry keys, comparator,
 * targetValue, windowDays for SUSTAINED). Inline validationError per field.
 *
 * On "Save goal" the card collapses to a "Goal created" confirmation linking to
 * the roadmap. [committedGoalId] drives that collapsed state.
 */
@Composable
fun GoalProposalCard(
    edit: ProposalEdit,
    committedGoalId: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpenGoal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (committedGoalId != null) {
        GoalCreatedCard(goalId = committedGoalId, onOpenGoal = onOpenGoal, modifier = modifier)
        return
    }

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            CapsLabel("Proposed goal", color = Hf.colors.accentDim)
            Spacer(Modifier.height(8.dp))

            ValidationBanner(edit.validationError)

            EditableField(
                label = "Title",
                value = edit.title.value,
                onChange = { edit.title.value = it },
                placeholder = "Goal title",
            )
            Spacer(Modifier.height(8.dp))
            EditableField(
                label = "Description",
                value = edit.description.value,
                onChange = { edit.description.value = it },
                placeholder = "What is this goal about?",
                singleLine = false,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    FieldLabel("Domain")
                    EnumDropdown(
                        selected = edit.domain.value,
                        options = GoalDomain.entries,
                        labelOf = { it.label },
                        onSelect = { edit.domain.value = it },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    FieldLabel("Target date")
                    DateField(
                        value = edit.targetDate.value,
                        onChange = { edit.targetDate.value = it },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsLabel("Phases", color = Hf.colors.textSecondary)
                AddButton("Add phase") { edit.phases.add(PhaseEdit.blank()) }
            }
            Spacer(Modifier.height(6.dp))

            edit.phases.forEachIndexed { index, phase ->
                PhaseEditor(
                    index = index,
                    total = edit.phases.size,
                    phase = phase,
                    onMoveUp = { if (index > 0) edit.phases.swap(index, index - 1) },
                    onMoveDown = { if (index < edit.phases.lastIndex) edit.phases.swap(index, index + 1) },
                    onRemove = { edit.phases.removeAt(index) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
                        .clickable(enabled = !saving) { onDiscard() }
                        .padding(vertical = 11.dp),
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
                        if (saving) "Saving…" else "Save goal",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textInverse,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseEditor(
    index: Int,
    total: Int,
    phase: PhaseEdit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(9.dp))
            .background(Hf.colors.canvas, RoundedCornerShape(9.dp))
            .padding(11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsLabel("Phase ${index + 1}", color = Hf.colors.accentDim)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconAction(Icons.Outlined.ArrowUpward, "Move up", enabled = index > 0, onClick = onMoveUp)
                IconAction(Icons.Outlined.ArrowDownward, "Move down", enabled = index < total - 1, onClick = onMoveDown)
                IconAction(Icons.Outlined.Close, "Remove phase", onClick = onRemove)
            }
        }
        ValidationBanner(phase.validationError)
        Spacer(Modifier.height(6.dp))
        EditableField(
            label = "Title",
            value = phase.title.value,
            onChange = { phase.title.value = it },
            placeholder = "Phase title",
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel("Starts")
                DateField(value = phase.targetStartDate.value, onChange = { phase.targetStartDate.value = it })
            }
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel("Ends")
                DateField(value = phase.targetEndDate.value, onChange = { phase.targetEndDate.value = it })
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsLabel("Steps", color = Hf.colors.textSecondary)
            AddButton("Add step") { phase.steps.add(StepEdit.blank()) }
        }
        Spacer(Modifier.height(4.dp))
        phase.steps.forEachIndexed { si, step ->
            StepEditor(
                index = si,
                total = phase.steps.size,
                step = step,
                onMoveUp = { if (si > 0) phase.steps.swap(si, si - 1) },
                onMoveDown = { if (si < phase.steps.lastIndex) phase.steps.swap(si, si + 1) },
                onRemove = { phase.steps.removeAt(si) },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    total: Int,
    step: StepEdit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderSubtle, RoundedCornerShape(8.dp))
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                EditableField(
                    label = null,
                    value = step.title.value,
                    onChange = { step.title.value = it },
                    placeholder = "Step ${index + 1}",
                )
            }
            IconAction(Icons.Outlined.ArrowUpward, "Move up", enabled = index > 0, onClick = onMoveUp)
            IconAction(Icons.Outlined.ArrowDownward, "Move down", enabled = index < total - 1, onClick = onMoveDown)
            IconAction(Icons.Outlined.Close, "Remove step", onClick = onRemove)
        }
        ValidationBanner(step.validationError)
        Spacer(Modifier.height(6.dp))
        FieldLabel("Kind")
        EnumDropdown(
            selected = step.kind.value,
            options = StepKind.entries,
            labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { step.kind.value = it },
        )

        if (step.kind.value != StepKind.MANUAL) {
            Spacer(Modifier.height(6.dp))
            ValidationBanner(step.metricValidationError)
            FieldLabel("Metric")
            EnumDropdown(
                selected = step.metricKey.value,
                options = MetricRegistry.keys,
                labelOf = { it ?: "Select a metric" },
                onSelect = { step.metricKey.value = it },
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.width(96.dp)) {
                    FieldLabel("Comparator")
                    EnumDropdown(
                        selected = step.comparator.value,
                        options = Comparator.entries,
                        labelOf = { it?.let { c -> "${c.name} (${c.symbol})" } ?: "—" },
                        onSelect = { step.comparator.value = it },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    FieldLabel("Target")
                    EditableField(
                        label = null,
                        value = step.targetValue.value,
                        onChange = { step.targetValue.value = it },
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                    )
                }
                if (step.kind.value == StepKind.SUSTAINED) {
                    Column(modifier = Modifier.width(78.dp)) {
                        FieldLabel("Window d")
                        EditableField(
                            label = null,
                            value = step.windowDays.value,
                            onChange = { step.windowDays.value = it },
                            placeholder = "30",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCreatedCard(goalId: String, onOpenGoal: (String) -> Unit, modifier: Modifier) {
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
                Text("Goal created", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                Text("Saved to your roadmap.", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
            Box(
                modifier = Modifier
                    .background(Hf.colors.accentBg, RoundedCornerShape(8.dp))
                    .clickable { onOpenGoal(goalId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("View roadmap", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
            }
        }
    }
}

// ---- small editor atoms ----

@Composable
private fun FieldLabel(text: String) {
    CapsLabel(text, color = Hf.colors.textTertiary, size = 9)
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun ValidationBanner(error: String?) {
    if (error.isNullOrBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Hf.colors.alertBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Hf.colors.accentBg, RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = Hf.colors.accentDim, modifier = Modifier.size(13.dp))
        Text(text, style = Hf.type.capsSm, color = Hf.colors.accentDim)
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (enabled) Hf.colors.textSecondary else Hf.colors.textQuaternary,
        modifier = Modifier
            .size(26.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(5.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun GoalProposalCardPreview() {
    HealthFitnessTheme {
        Column(modifier = Modifier.padding(12.dp)) {
            GoalProposalCard(
                edit = remember { ProposalEdit.from(GoalsFixtures.proposal) },
                committedGoalId = null,
                saving = false,
                onSave = {},
                onDiscard = {},
                onOpenGoal = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun GoalCreatedPreview() {
    HealthFitnessTheme {
        Column(modifier = Modifier.padding(12.dp)) {
            GoalProposalCard(
                edit = remember { ProposalEdit.blank() },
                committedGoalId = "g-new",
                saving = false,
                onSave = {},
                onDiscard = {},
                onOpenGoal = {},
            )
        }
    }
}

// Swap helper for reorder.
private fun <T> androidx.compose.runtime.snapshots.SnapshotStateList<T>.swap(a: Int, b: Int) {
    val tmp = this[a]
    this[a] = this[b]
    this[b] = tmp
}

/** Parse "yyyy-MM-dd" → epoch millis (UTC) for the DatePicker initial state. */
internal fun isoToEpochMillis(iso: String?): Long? =
    parseDate(iso)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

/** epoch millis (UTC) → "yyyy-MM-dd". */
internal fun epochMillisToIso(millis: Long): String =
    LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).toString()
