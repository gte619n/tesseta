package com.gte619n.healthfitness.domain.blood

/**
 * Display labels + descriptions for each [BloodMarker]. Mirrors the
 * web's `MARKER_LABELS` + `MARKER_INFO`. Pure-Kotlin so both the
 * dashboard and the feature module read the same names without an
 * Android resource dependency.
 */
object MarkerCatalog {

    fun displayName(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL -> "Total cholesterol"
        BloodMarker.LDL -> "LDL"
        BloodMarker.HDL -> "HDL"
        BloodMarker.TRIGLYCERIDES -> "Triglycerides"
        BloodMarker.APO_B -> "ApoB"
        BloodMarker.HBA1C -> "HbA1c"
        BloodMarker.FASTING_GLUCOSE -> "Fasting glucose"
        BloodMarker.HS_CRP -> "hs-CRP"
    }

    fun description(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL ->
            "Sum of all cholesterol in the blood. A broad lipid screen."
        BloodMarker.LDL ->
            "Low-density lipoprotein; the main atherogenic lipid in clinical scores."
        BloodMarker.HDL ->
            "High-density lipoprotein; higher values are protective."
        BloodMarker.TRIGLYCERIDES ->
            "Fasting triglycerides; elevated values track insulin resistance."
        BloodMarker.APO_B ->
            "Apolipoprotein B; a particle count for atherogenic lipids."
        BloodMarker.HBA1C ->
            "Three-month average glucose; the standard glycemic-control marker."
        BloodMarker.FASTING_GLUCOSE ->
            "Plasma glucose after an overnight fast."
        BloodMarker.HS_CRP ->
            "High-sensitivity C-reactive protein; a low-grade inflammation marker."
    }

    fun target(marker: BloodMarker): String = when (marker) {
        BloodMarker.TOTAL_CHOLESTEROL -> "Below 200 mg/dL"
        BloodMarker.LDL -> "Below 100 mg/dL (optimal); below 70 mg/dL for high-risk patients"
        BloodMarker.HDL -> "60 mg/dL or higher"
        BloodMarker.TRIGLYCERIDES -> "Below 150 mg/dL"
        BloodMarker.APO_B -> "Below 90 mg/dL"
        BloodMarker.HBA1C -> "Below 5.7%"
        BloodMarker.FASTING_GLUCOSE -> "Below 100 mg/dL"
        BloodMarker.HS_CRP -> "Below 1.0 mg/L"
    }

    /**
     * Visual order on the overview grid. Lipids first (most-tracked
     * preventive panel), then glycemic, then inflammation. Matches the
     * web client.
     */
    val DISPLAY_ORDER: List<BloodMarker> = listOf(
        BloodMarker.LDL,
        BloodMarker.APO_B,
        BloodMarker.HDL,
        BloodMarker.TOTAL_CHOLESTEROL,
        BloodMarker.TRIGLYCERIDES,
        BloodMarker.HBA1C,
        BloodMarker.FASTING_GLUCOSE,
        BloodMarker.HS_CRP,
    )

    /**
     * Best-effort mapping from extracted-marker `name` (canonical
     * uppercase, matching backend enum) to [BloodMarker]. Returns null
     * for unmapped names — the UI renders them under "Other markers".
     */
    fun fromExtractedName(name: String): BloodMarker? = runCatching {
        BloodMarker.valueOf(name.trim().uppercase())
    }.getOrNull()
}
