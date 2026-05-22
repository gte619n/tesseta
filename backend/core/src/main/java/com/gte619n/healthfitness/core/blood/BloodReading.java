package com.gte619n.healthfitness.core.blood;

import java.time.Instant;
import java.time.LocalDate;

public record BloodReading(
    String userId,
    String readingId,
    BloodMarker marker,
    double value,
    String unit,            // canonical unit; falls back to BloodReferenceRanges if null on input
    LocalDate sampleDate,
    String labSource,       // free text — "Quest", "LabCorp", or whatever
    String notes,           // optional
    Instant createdAt,
    Instant updatedAt
) {}
