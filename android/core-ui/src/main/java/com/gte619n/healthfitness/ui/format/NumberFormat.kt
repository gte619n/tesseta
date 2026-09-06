package com.gte619n.healthfitness.ui.format

import java.text.NumberFormat
import java.util.Locale

/**
 * Shared display formatting for numeric values. Values are grouped with
 * thousands separators (e.g. 1409 -> "1,409") so large numbers read cleanly
 * across the app. The decimal is dropped for whole numbers and otherwise kept
 * up to [maxDecimals] places (trailing zeros trimmed).
 *
 * For values that feed back into editable inputs use the raw value instead —
 * grouped strings do not round-trip through number parsing.
 */
fun formatNumber(value: Double, maxDecimals: Int = 1): String =
    NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = maxDecimals
        minimumFractionDigits = 0
    }.format(value)

/** Grouped whole-number display, e.g. 2450.4 -> "2,450". */
fun formatWholeNumber(value: Double): String = formatNumber(value, maxDecimals = 0)
