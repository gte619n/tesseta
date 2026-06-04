package com.gte619n.healthfitness.api.bloodtest;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import java.time.LocalDate;
import java.util.List;

public record BloodTestReportResponse(
    String reportId,
    LocalDate sampleDate,
    String labSource,
    List<ExtractedMarker> markers
) {
    public static BloodTestReportResponse from(BloodTestReport r) {
        return new BloodTestReportResponse(
            r.reportId(),
            r.sampleDate(),
            r.labSource(),
            r.markers()
        );
    }
}
