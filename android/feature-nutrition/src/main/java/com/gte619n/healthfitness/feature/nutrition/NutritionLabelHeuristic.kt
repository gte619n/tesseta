package com.gte619n.healthfitness.feature.nutrition

/**
 * Heuristic that decides whether OCR'd text from the live camera looks like a
 * packaged-food Nutrition Facts panel. Kept pure (no Android types) so the
 * unified capture screen can auto-trigger label analysis and so the rule is
 * unit-testable.
 *
 * "Nutrition Facts" alone is a strong enough signal. Otherwise we require a few
 * independent panel markers to co-occur, which keeps a stray "Calories" on a
 * menu or a single "protein" word from firing an analysis (and a Gemini call).
 */
internal fun looksLikeNutritionLabel(ocrText: String): Boolean {
    val text = ocrText.lowercase()
    if (text.contains("nutrition facts")) return true

    val markers = listOf(
        "serving size",
        "servings per",
        "amount per serving",
        "calories",
        "total fat",
        "saturated fat",
        "trans fat",
        "cholesterol",
        "sodium",
        "total carbohydrate",
        "dietary fiber",
        "total sugars",
        "added sugars",
        "protein",
        "% daily value",
        "vitamin",
    )
    val hits = markers.count { text.contains(it) }
    return hits >= 3
}
