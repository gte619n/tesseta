package com.gte619n.healthfitness.core.dexa;

import java.time.Instant;
import java.time.LocalDate;

// A single DEXA scan — one report = one document. Headline whole-body
// numbers live at the top; region rows and bone/metabolism live in their
// own fields so the UI can render each section independently.
//
// All masses in lbs (source report unit). Percent fields are unitless
// percents (e.g. 31.4 means 31.4%). Nullable on every numeric field —
// vendors omit different subsets of the report, and the extractor will
// return null rather than guess.
public record DexaScan(
    String userId,
    String scanId,
    LocalDate measuredOn,
    String sourceFacility,
    String pdfStoragePath,
    // SHA-256 hex of the original PDF bytes. Used to dedupe re-uploads
    // of the same file — see DexaScanRepository.existsByContentHash.
    String contentHash,

    // Whole-body summary
    Double totalMassLb,
    Double leanTissueLb,
    Double fatTissueLb,
    Double totalBodyFatPercent,

    // Visceral + abdomen
    Double visceralFatLb,
    Double androidGynoidRatio,

    // Per-region
    DexaRegion trunk,
    DexaRegion android,
    DexaRegion gynoid,
    DexaRegion armsTotal,
    DexaRegion armsRight,
    DexaRegion armsLeft,
    DexaRegion legsTotal,
    DexaRegion legsRight,
    DexaRegion legsLeft,

    // Bone density
    Double bmdTScore,
    Double bmdZScore,

    // Metabolism
    Integer restingMetabolicRateKcal,

    Instant createdAt,
    Instant updatedAt
) {}
