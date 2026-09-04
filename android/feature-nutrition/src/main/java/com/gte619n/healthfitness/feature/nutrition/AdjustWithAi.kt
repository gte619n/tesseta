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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.AdjustApplyRequest
import com.gte619n.healthfitness.domain.nutrition.AdjustItem
import com.gte619n.healthfitness.domain.nutrition.AdjustPreviewResponse
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Adjust with AI" section for an entry's edit sheet — the free-text correction
 * flow. The user types a fix (e.g. "that's pearl couscous, not lentils"); we run
 * a server-side preview, show the before/after diff, and only persist once they
 * confirm. Shared by the single-food ([EditEntrySheet]) and composite
 * ([IngredientsSheet]) sheets.
 *
 * @param isComposite    whether the entry is a multi-ingredient meal — gates the
 *                        "also save this meal" offer (a single food isn't a meal)
 * @param previewAdjustment runs the correction and returns the proposal (throws on failure)
 * @param onApply        persist the accepted proposal; the sheet is closed by the caller
 * @param applying       true while an accepted proposal is being applied
 */
@Composable
fun AdjustWithAiSection(
    isComposite: Boolean,
    previewAdjustment: suspend (String) -> AdjustPreviewResponse,
    onApply: (AdjustApplyRequest) -> Unit,
    applying: Boolean,
) {
    val scope = rememberCoroutineScope()
    var instruction by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<AdjustPreviewResponse?>(null) }
    var saveAsMeal by remember { mutableStateOf(false) }

    Text("Adjust with AI", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    Spacer(Modifier.height(6.dp))
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
        enabled = !loading && !applying,
    )

    val current = proposal
    if (current == null) {
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            if (loading) "Thinking…" else "✨ Adjust with AI",
            Modifier.fillMaxWidth(),
        ) {
            val text = instruction.trim()
            if (text.isBlank() || loading) return@PrimaryButton
            loading = true
            error = null
            scope.launch {
                try {
                    proposal = previewAdjustment(text)
                } catch (e: Exception) {
                    error = e.message ?: "Couldn't adjust the meal"
                } finally {
                    loading = false
                }
            }
        }
    } else {
        Spacer(Modifier.height(10.dp))
        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(13.dp)) {
                Text("Proposed change", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                Spacer(Modifier.height(4.dp))
                Text(current.mealName, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                current.items.forEach { item ->
                    Text(
                        "• ${item.name}${item.portionSummary()}",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val oldKcal = current.oldTotals.caloriesKcal?.roundToInt()
                val newKcal = current.newTotals.caloriesKcal?.roundToInt()
                Text(
                    "Calories: ${oldKcal ?: "?"} → ${newKcal ?: "?"} kcal",
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
                if (isComposite) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !applying) { saveAsMeal = !saveAsMeal },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = saveAsMeal, onCheckedChange = { saveAsMeal = it }, enabled = !applying)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Also save this meal so it's right next time",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Discard", Modifier.weight(1f)) {
                        if (applying) return@SecondaryButton
                        proposal = null
                        saveAsMeal = false
                    }
                    PrimaryButton(
                        if (applying) "Applying…" else "Apply",
                        Modifier.weight(1f),
                    ) {
                        if (applying) return@PrimaryButton
                        onApply(
                            AdjustApplyRequest(
                                mealName = current.mealName,
                                packagedProduct = current.packagedProduct,
                                items = current.items,
                                saveAsMeal = saveAsMeal && isComposite,
                            ),
                        )
                    }
                }
            }
        }
    }

    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = Hf.type.bodySm, color = Hf.colors.alert)
    }
}

/** "  120 g · 210 kcal" style tail for a proposed item, omitting unknown parts. */
private fun AdjustItem.portionSummary(): String {
    val grams = servingGrams?.roundToInt()
    val kcal = macros?.caloriesKcal?.roundToInt()
    val parts = buildList {
        if (grams != null) add("$grams g")
        if (kcal != null) add("$kcal kcal")
    }
    return if (parts.isEmpty()) "" else "  ${parts.joinToString(" · ")}"
}
