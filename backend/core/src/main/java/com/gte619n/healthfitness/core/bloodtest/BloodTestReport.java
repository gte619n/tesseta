package com.gte619n.healthfitness.core.bloodtest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// A single blood test report — one PDF = one document. Contains all
// markers extracted from the lab report. Each marker has its own value,
// unit, and reference range as parsed from the source document.
//
// All numeric values are stored with their original units from the lab.
// Nullable on every numeric field — labs omit different subsets, and
// the extractor will return null rather than guess.
public record BloodTestReport(
    String userId,
    String reportId,
    LocalDate sampleDate,
    String labSource,         // e.g. "Quest Diagnostics", "LabCorp"
    String pdfStoragePath,
    // SHA-256 hex of the original PDF bytes. Used to dedupe re-uploads
    // of the same file — see BloodTestReportRepository.existsByContentHash.
    String contentHash,

    // All extracted markers from this report
    List<ExtractedMarker> markers,

    Instant createdAt,
    Instant updatedAt
) {}
