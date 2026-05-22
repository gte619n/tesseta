package com.gte619n.healthfitness.api.dexa;

import com.gte619n.healthfitness.core.dexa.DexaRegion;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import java.time.LocalDate;

public record DexaScanResponse(
    String scanId,
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
) {
    public static DexaScanResponse from(DexaScan s) {
        return new DexaScanResponse(
            s.scanId(),
            s.measuredOn(),
            s.sourceFacility(),
            s.totalMassLb(),
            s.leanTissueLb(),
            s.fatTissueLb(),
            s.totalBodyFatPercent(),
            s.visceralFatLb(),
            s.androidGynoidRatio(),
            s.trunk(),
            s.android(),
            s.gynoid(),
            s.armsTotal(),
            s.armsRight(),
            s.armsLeft(),
            s.legsTotal(),
            s.legsRight(),
            s.legsLeft(),
            s.bmdTScore(),
            s.bmdZScore(),
            s.restingMetabolicRateKcal()
        );
    }
}
