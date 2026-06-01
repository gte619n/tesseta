package com.gte619n.healthfitness.domain.blood

/**
 * Display metadata for the tracked markers, mirroring the web client's
 * `MARKER_LABELS` + `MARKER_INFO`. Reference ranges are NOT here — those are
 * server-authoritative and ride on each reading.
 */
object MarkerCatalog {

    /** Canonical display order for the tracked-markers grid and dashboard. */
    val DISPLAY_ORDER: List<BloodMarker> = listOf(
        BloodMarker.TESTOSTERONE,
        BloodMarker.TOTAL_CHOLESTEROL,
        BloodMarker.LDL,
        BloodMarker.HDL,
        BloodMarker.TRIGLYCERIDES,
        BloodMarker.APO_B,
        BloodMarker.HBA1C,
        BloodMarker.FASTING_GLUCOSE,
        BloodMarker.HS_CRP,
    )

    fun displayName(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL -> "Total cholesterol"
        BloodMarker.LDL -> "LDL"
        BloodMarker.HDL -> "HDL"
        BloodMarker.TRIGLYCERIDES -> "Triglycerides"
        BloodMarker.APO_B -> "ApoB"
        BloodMarker.HBA1C -> "HbA1c"
        BloodMarker.FASTING_GLUCOSE -> "Fasting glucose"
        BloodMarker.HS_CRP -> "hs-CRP"
        BloodMarker.TESTOSTERONE -> "Testosterone"
    }

    fun description(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL ->
            "Sum of all cholesterol in your blood; a broad lipid-panel screen."
        BloodMarker.LDL ->
            "\"Bad\" cholesterol; the primary driver of atherosclerotic plaque."
        BloodMarker.HDL ->
            "\"Good\" cholesterol; helps clear LDL from the bloodstream."
        BloodMarker.TRIGLYCERIDES ->
            "Blood fats reflecting recent carbohydrate and alcohol intake."
        BloodMarker.APO_B ->
            "Count of atherogenic particles; a sharper cardiovascular risk marker than LDL."
        BloodMarker.HBA1C ->
            "Average blood glucose over the past ~3 months."
        BloodMarker.FASTING_GLUCOSE ->
            "Blood sugar after an overnight fast; an early diabetes screen."
        BloodMarker.HS_CRP ->
            "High-sensitivity inflammation marker linked to cardiovascular risk."
        BloodMarker.TESTOSTERONE ->
            "Primary male sex hormone. Affects muscle mass, bone density, and energy levels."
    }

    fun target(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL -> "Below 200 mg/dL"
        BloodMarker.LDL -> "Below 100 mg/dL (lower if high-risk)"
        BloodMarker.HDL -> "Above 40 mg/dL (men) / 50 mg/dL (women)"
        BloodMarker.TRIGLYCERIDES -> "Below 150 mg/dL"
        BloodMarker.APO_B -> "Below 90 mg/dL"
        BloodMarker.HBA1C -> "Below 5.7%"
        BloodMarker.FASTING_GLUCOSE -> "70–99 mg/dL"
        BloodMarker.HS_CRP -> "Below 1.0 mg/L"
        BloodMarker.TESTOSTERONE -> "300–1000 ng/dL (men)"
    }
}
