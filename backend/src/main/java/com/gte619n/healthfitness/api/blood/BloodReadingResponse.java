package com.gte619n.healthfitness.api.blood;

import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReferenceRanges;
import java.time.LocalDate;

public record BloodReadingResponse(
    String readingId,
    BloodMarker marker,
    double value,
    String unit,
    LocalDate sampleDate,
    String labSource,
    String notes,
    Reference reference
) {
    public static BloodReadingResponse from(BloodReading r) {
        BloodReferenceRanges.Range range = BloodReferenceRanges.rangeFor(r.marker());
        return new BloodReadingResponse(
            r.readingId(),
            r.marker(),
            r.value(),
            r.unit(),
            r.sampleDate(),
            r.labSource(),
            r.notes(),
            new Reference(
                range.unit(),
                range.orientation().name(),
                range.goodThreshold(),
                range.displayMin(),
                range.displayMax()
            )
        );
    }

    public record Reference(
        String unit,
        String orientation,
        double goodThreshold,
        double displayMin,
        double displayMax
    ) {}
}
