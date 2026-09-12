package com.gte619n.healthfitness.integrations.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer.LeftoverEstimate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deterministic mapping test for {@link LeftoverPhotoExtractor#toEstimate} — the
 * Gemini {@code estimate_leftovers} tool-args → {@link LeftoverEstimate} parsing,
 * without a live model (IMPL-LEFTOVER-01 §6.3 plumbing coverage).
 */
class LeftoverPhotoExtractorTest {

    @Test
    void mapsToolArgsToEstimate() {
        Map<String, Object> args = Map.of(
            "overallConfidence", 0.82,
            "items", List.of(
                Map.of("name", "rice", "remainingGrams", 70.0, "matched", true, "confidence", 0.9),
                Map.of("name", "salmon", "matched", false)));

        LeftoverEstimate est = LeftoverPhotoExtractor.toEstimate(args);

        assertEquals(0.82, est.overallConfidence(), 1e-6);
        assertEquals(2, est.items().size());
        assertEquals("rice", est.items().get(0).name());
        assertEquals(70.0, est.items().get(0).remainingGrams(), 1e-6);
        assertTrue(est.items().get(0).matched());
        assertFalse(est.items().get(1).matched());
    }

    @Test
    void nullArgs_yieldEmptyEstimate() {
        LeftoverEstimate est = LeftoverPhotoExtractor.toEstimate(null);
        assertEquals(0.0, est.overallConfidence(), 1e-6);
        assertTrue(est.items().isEmpty());
    }
}
