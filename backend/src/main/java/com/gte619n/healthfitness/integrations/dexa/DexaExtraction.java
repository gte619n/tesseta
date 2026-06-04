package com.gte619n.healthfitness.integrations.dexa;

import com.gte619n.healthfitness.core.dexa.DexaRegion;
import java.time.LocalDate;

// Plain DTO returned by the Gemini extractor. The service layer maps this
// into a DexaScan with the userId/scanId/pdfStoragePath baked in.
public record DexaExtraction(
    LocalDate measuredOn,
    String sourceFacility,
    Double totalMassLb,
    Double leanTissueLb,
    Double fatTissueLb,
    Double totalBodyFatPercent,
    Double visceralFatLb,
    Double androidGynoidRatio,
    DexaRegion trunk,
    DexaRegion android,
    DexaRegion gynoid,
    DexaRegion armsTotal,
    DexaRegion armsRight,
    DexaRegion armsLeft,
    DexaRegion legsTotal,
    DexaRegion legsRight,
    DexaRegion legsLeft,
    Double bmdTScore,
    Double bmdZScore,
    Integer restingMetabolicRateKcal
) {}
