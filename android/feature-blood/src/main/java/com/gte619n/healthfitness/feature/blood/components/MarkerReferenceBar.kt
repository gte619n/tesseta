package com.gte619n.healthfitness.feature.blood.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.gte619n.healthfitness.ui.components.MarkerReferenceBar as SharedMarkerReferenceBar

/**
 * Reference-range bar for a single blood marker. The drawing primitive now
 * lives in core-ui (IMPL-AND-04 Q7) as
 * [com.gte619n.healthfitness.ui.components.MarkerReferenceBar]; this thin
 * adapter maps a [ReferenceRange] + [value] onto the shared bar's
 * good-zone / tick fractions so feature-blood call sites stay unchanged.
 */
@Composable
fun MarkerReferenceBar(
    value: Double?,
    reference: ReferenceRange,
    modifier: Modifier = Modifier,
) {
    val span = (reference.displayMax - reference.displayMin).takeIf { it > 0.0 } ?: 1.0
    fun fractionOf(v: Double): Float =
        ((v - reference.displayMin) / span).coerceIn(0.0, 1.0).toFloat()

    val thresholdFraction = fractionOf(reference.goodThreshold)
    // Good zone spans from the "good" side of the threshold to the matching edge.
    val (goodLeftPct, goodFillPct) = when (reference.orientation) {
        ReferenceRange.Orientation.LOWER_IS_BETTER -> 0f to thresholdFraction
        ReferenceRange.Orientation.HIGHER_IS_BETTER -> thresholdFraction to (1f - thresholdFraction)
    }
    val tickPct = value?.let { fractionOf(it) } ?: thresholdFraction

    SharedMarkerReferenceBar(
        goodLeftPct = goodLeftPct,
        goodFillPct = goodFillPct,
        tickPct = tickPct,
        modifier = modifier,
    )
}
