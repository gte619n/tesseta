package com.gte619n.healthfitness.integrations.bloodtest;

import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import java.time.LocalDate;
import java.util.List;

// Plain DTO returned by the Gemini extractor. The service layer maps this
// into a BloodTestReport with the userId/reportId/pdfStoragePath baked in.
public record BloodTestExtraction(
    LocalDate sampleDate,
    String labSource,
    List<ExtractedMarker> markers
) {}
