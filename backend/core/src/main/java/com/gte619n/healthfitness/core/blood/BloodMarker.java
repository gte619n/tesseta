package com.gte619n.healthfitness.core.blood;

// Known blood markers we recognize. The enum doubles as a controlled
// vocabulary for storage and as the source of canonical units +
// reference-range hints (held in BloodReferenceRanges).
//
// Add new markers here as the user starts tracking them. Storage uses
// the enum name verbatim, so existing data isn't broken by new entries
// — only removing or renaming a value would be.
public enum BloodMarker {
    // Lipid panel
    TOTAL_CHOLESTEROL,
    LDL,
    HDL,
    TRIGLYCERIDES,
    APO_B,

    // Glycemic
    HBA1C,
    FASTING_GLUCOSE,

    // Inflammation
    HS_CRP,

    // Hormones
    TESTOSTERONE
}
