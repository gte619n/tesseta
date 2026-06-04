package com.gte619n.healthfitness.core.dexa;

// One DEXA region row: total/lean/fat mass and the regional body-fat
// percent. Used for trunk, android, gynoid, and the arms+legs (total +
// per-side). Values stored in lbs to match the source report; convert
// at the UI layer if/when we add unit preferences.
public record DexaRegion(
    Double totalMassLb,
    Double leanTissueLb,
    Double fatTissueLb,
    Double regionFatPercent
) {}
