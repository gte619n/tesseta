package com.gte619n.healthfitness.api.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.progression.Trend;
import com.gte619n.healthfitness.core.progression.WeekLoop;
import org.junit.jupiter.api.Test;

/**
 * A pattern with too little data to fit a trend yields a NaN weekly slope
 * (olsSlope's "unknown" sentinel, surfaced as {@link Trend#UNKNOWN}). JSON has no
 * NaN literal and the Android Moshi reader rejects it ("JSON forbids NaN and
 * infinities: NaN at path $[n].weeklySlopePct"), so {@link
 * ProgressionController.WeekReviewDto} must never let a non-finite value reach the
 * wire.
 */
class WeekReviewDtoTest {

    @Test
    void nanSlopeSerializesAsFinite() {
        WeekLoop.PatternReview r = new WeekLoop.PatternReview(
            MovementPattern.OTHER, Trend.UNKNOWN, Double.NaN, 0.0,
            4, 4, false, "not enough data → hold");

        ProgressionController.WeekReviewDto dto = ProgressionController.WeekReviewDto.of(r);

        assertTrue(Double.isFinite(dto.weeklySlopePct()), "NaN slope must not reach the wire");
        assertEquals(0.0, dto.weeklySlopePct(), 0.0);
        assertEquals("UNKNOWN", dto.trend());
    }

    @Test
    void infiniteSlopeSerializesAsFinite() {
        WeekLoop.PatternReview r = new WeekLoop.PatternReview(
            MovementPattern.OTHER, Trend.RISING, Double.POSITIVE_INFINITY, 0.0,
            4, 5, false, "rising → hold volume");

        ProgressionController.WeekReviewDto dto = ProgressionController.WeekReviewDto.of(r);

        assertTrue(Double.isFinite(dto.weeklySlopePct()));
        assertEquals(0.0, dto.weeklySlopePct(), 0.0);
    }

    @Test
    void finiteSlopeConvertsFractionToPercent() {
        WeekLoop.PatternReview r = new WeekLoop.PatternReview(
            MovementPattern.PUSH_HORIZONTAL, Trend.RISING, 0.024, -0.1,
            6, 6, false, "rising → hold volume");

        ProgressionController.WeekReviewDto dto = ProgressionController.WeekReviewDto.of(r);

        assertEquals(2.4, dto.weeklySlopePct(), 1e-9);
    }
}
