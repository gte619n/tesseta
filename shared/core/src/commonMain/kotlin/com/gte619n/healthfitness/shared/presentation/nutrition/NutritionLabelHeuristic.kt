package com.gte619n.healthfitness.shared.presentation.nutrition

/**
 * IMPL-IOS-01 Phase 3 Wave C — OCR label gate, ported verbatim from Android
 * `feature-nutrition/NutritionLabelHeuristic.kt`. On iOS the OCR text comes from
 * `VNRecognizeTextRequest`; this decides when the recognized text is a nutrition
 * label worth sending to the AI label endpoint (so the camera doesn't fire the
 * analyze on arbitrary text). Single-sourced so both clients agree.
 */
fun looksLikeNutritionLabel(ocrText: String): Boolean {
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
