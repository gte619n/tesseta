package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.AdjustItem
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.format.formatWholeNumber
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * "Adjust with AI" section for an entry's edit sheet — the async free-text
 * correction flow. The user types a fix (e.g. "that's pearl couscous, not
 * lentils") and submits; the correction runs server-side in the background
 * (surviving backgrounding / process death) and pushes a review notification when
 * the proposal is ready. Renders exactly one affordance based on the entry's
 * [Entry.adjustment] state (the same state-pill pattern as [LeftoverHeroPill]):
 *  - ADJUSTING       → an "Adjusting…" note (no action; a notification will arrive)
 *  - PENDING_REVIEW  → a "Review adjustment" button
 *  - else            → the free-text field + "also save this meal" + submit button
 *
 * Shared by the single-food ([EditEntrySheet]) and composite ([IngredientsSheet])
 * sheets. [isComposite] gates the "also save this meal" offer (a single food isn't
 * a meal); the choice is captured at submit and honored when the proposal commits.
 */
@Composable
fun AdjustWithAiSection(
    entry: Entry,
    isComposite: Boolean,
    onSubmitAdjust: (instruction: String, saveAsMeal: Boolean) -> Unit,
    onReviewAdjust: () -> Unit,
) {
    Text("Adjust with AI", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    Spacer(Modifier.height(6.dp))
    when {
        entry.isAdjusting -> {
            Text(
                "Adjusting… you'll get a notification when it's ready.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
        }
        entry.hasAdjustReview -> {
            Text(
                "Your adjustment is ready to review.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Review adjustment", Modifier.fillMaxWidth()) { onReviewAdjust() }
        }
        else -> {
            var instruction by remember { mutableStateOf("") }
            var saveAsMeal by remember { mutableStateOf(false) }
            Text(
                "Wrong food or portion? Describe the fix and let AI re-read the meal.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("e.g. that's pearl couscous, not lentils") },
            )
            if (isComposite) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { saveAsMeal = !saveAsMeal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saveAsMeal, onCheckedChange = { saveAsMeal = it })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Also save this meal so it's right next time",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("✨ Adjust with AI", Modifier.fillMaxWidth()) {
                val text = instruction.trim()
                if (text.isNotBlank()) onSubmitAdjust(text, saveAsMeal && isComposite)
            }
        }
    }
}

/**
 * The async-adjustment review-diff sheet: the stored proposal's revised meal name,
 * proposed items and old→new calories, with Apply/Discard. The server holds the
 * proposal (and the saveAsMeal choice captured at submit), so Apply just commits it
 * — no proposal round-trips from the client, mirroring [LeftoverReviewSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdjustReviewSheet(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val proposal = entry.adjustment?.proposal
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Review adjustment", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(entry.foodName, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            Spacer(Modifier.height(14.dp))

            if (proposal == null) {
                Text(
                    "This adjustment is no longer available to review.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
            } else {
                HfCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(13.dp)) {
                        Text("Proposed change", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(proposal.mealName, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        proposal.items.forEach { item ->
                            Text(
                                "• ${item.name}${item.portionSummary()}",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val oldKcal = proposal.oldTotals.caloriesKcal?.let { formatWholeNumber(it) }
                        val newKcal = proposal.newTotals.caloriesKcal?.let { formatWholeNumber(it) }
                        Text(
                            "Calories: ${oldKcal ?: "?"} → ${newKcal ?: "?"} kcal",
                            style = Hf.type.monoSm,
                            color = Hf.colors.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Discard", Modifier.weight(1f)) { if (!saving) onDiscard() }
                PrimaryButton(
                    if (saving) "Applying…" else "Apply",
                    Modifier.weight(1f),
                ) { if (!saving && proposal != null) onApply() }
            }
        }
    }
}

/** "  120 g · 210 kcal" style tail for a proposed item, omitting unknown parts. */
private fun AdjustItem.portionSummary(): String {
    val grams = servingGrams?.let { formatWholeNumber(it) }
    val kcal = macros?.caloriesKcal?.let { formatWholeNumber(it) }
    val parts = buildList {
        if (grams != null) add("$grams g")
        if (kcal != null) add("$kcal kcal")
    }
    return if (parts.isEmpty()) "" else "  ${parts.joinToString(" · ")}"
}
