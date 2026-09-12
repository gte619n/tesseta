package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.LeftoverProposal
import com.gte619n.healthfitness.domain.nutrition.LeftoverServedIngredient
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.format.formatWholeNumber
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-LEFTOVER-01 — the "Remove Leftovers" controls inside the composite meal's
 * ingredients sheet (spec §5). Renders exactly one affordance based on the entry's
 * leftover [Entry.leftover] state:
 *  - eligible (no leftover / rejected)     → "Remove Leftovers" button (D4/D5)
 *  - ANALYZING                             → an "Analyzing leftovers…" note (D8)
 *  - PENDING_REVIEW                        → "Review leftovers" button (D7)
 *  - APPLIED                               → "Served → Ate" per-ingredient + a
 *                                            "Restore full portion" action (D15/D17)
 */
@Composable
internal fun LeftoverSection(
    entry: Entry,
    saving: Boolean,
    onRemoveLeftovers: () -> Unit,
    onReviewLeftovers: () -> Unit,
    onRestoreFullPortion: () -> Unit,
) {
    Text("Leftovers", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    Spacer(Modifier.height(6.dp))
    when {
        entry.isAnalyzingLeftovers -> {
            Text(
                "Analyzing leftovers… you'll get a notification when it's ready.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
        }
        entry.hasLeftoverReview -> {
            Text(
                "Your leftover photo is ready to review.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Review leftovers", Modifier.fillMaxWidth()) {
                if (!saving) onReviewLeftovers()
            }
        }
        entry.hasAppliedLeftover -> {
            AppliedLeftoverSummary(entry)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    if (saving) "Restoring…" else "Restore full portion",
                    Modifier.fillMaxWidth(),
                ) { if (!saving) onRestoreFullPortion() }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Re-shoot leftovers", Modifier.fillMaxWidth()) {
                if (!saving) onRemoveLeftovers()
            }
        }
        else -> {
            Text(
                "Didn't finish the meal? Photograph what's left and we'll subtract it.",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("🍽️ Remove Leftovers", Modifier.fillMaxWidth()) {
                if (!saving) onRemoveLeftovers()
            }
        }
    }
}

/** "Served X → Ate Y" per-ingredient list for an APPLIED entry (spec D17). */
@Composable
private fun AppliedLeftoverSummary(entry: Entry) {
    val leftover = entry.leftover ?: return
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp)) {
            val servedKcal = leftover.servedMacros?.caloriesKcal
            val ateKcal = entry.macros.caloriesKcal
            Text(
                "Served ${kcalOrDash(servedKcal)} → Ate ${kcalOrDash(ateKcal)}",
                style = Hf.type.monoSm,
                color = Hf.colors.textSecondary,
            )
            val served = leftover.servedIngredients
            val liveIngredients = entry.ingredients.orEmpty()
            if (served.isNotEmpty() && liveIngredients.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                served.forEach { s ->
                    val ate = liveIngredients.firstOrNull { it.name.equals(s.name, ignoreCase = true) }
                    val servedGrams = servedGramsOf(s)
                    val ateGrams = ate?.let { (it.servingGrams ?: 0.0) * (it.quantity ?: 1.0) }
                    Text(
                        "• ${s.name}: ${gramsOrDash(servedGrams)} → ${gramsOrDash(ateGrams)}",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * IMPL-LEFTOVER-01 (D7) — the review-diff sheet: the stored proposal's Served→Ate
 * totals + per-ingredient before/after, a warning banner when the proposal is a
 * partial mismatch, and Apply/Discard. The server holds the proposal, so Apply
 * just commits it (no proposal round-trips from the client, D13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeftoverReviewSheet(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val proposal = entry.leftover?.proposal
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
            Text("Review leftovers", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(entry.foodName, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            Spacer(Modifier.height(14.dp))

            if (proposal == null) {
                Text(
                    "This leftover is no longer available to review.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
            } else {
                if (proposal.warning) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        proposal.warningNote
                            ?: "Some items couldn't be matched — they were treated as fully eaten.",
                        style = Hf.type.bodySm,
                        color = Hf.colors.alert,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                HfCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(13.dp)) {
                        val servedKcal = proposal.servedTotals?.caloriesKcal
                        val ateKcal = proposal.consumedTotals?.caloriesKcal
                        Text(
                            "Served ${kcalOrDash(servedKcal)} → Ate ${kcalOrDash(ateKcal)}",
                            style = Hf.type.monoSm,
                            color = Hf.colors.textSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        proposal.items.forEach { item ->
                            Text(
                                "• ${item.name}: ${gramsOrDash(item.servedGrams)} → " +
                                    gramsOrDash(item.consumedGrams) +
                                    if (!item.matched) " (unmatched)" else "",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textSecondary,
                            )
                        }
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

private fun servedGramsOf(s: LeftoverServedIngredient): Double? {
    val grams = s.servingGrams ?: return null
    return grams * (s.quantity ?: 1.0)
}

private fun kcalOrDash(v: Double?): String =
    if (v == null) "—" else "${formatWholeNumber(v)} kcal"

private fun gramsOrDash(v: Double?): String =
    if (v == null) "—" else "${formatWholeNumber(v)} g"

@Suppress("unused")
private fun Macros.hasAny(): Boolean =
    caloriesKcal != null || proteinGrams != null || carbsGrams != null || fatGrams != null

@Suppress("unused")
private fun LeftoverProposal.itemCount(): Int = items.size
