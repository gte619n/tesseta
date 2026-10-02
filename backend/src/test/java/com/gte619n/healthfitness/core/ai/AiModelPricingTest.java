package com.gte619n.healthfitness.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class AiModelPricingTest {

    private final AiModelPricing pricing = new AiModelPricing();

    @Test
    void flashFamilyPricesInputAndOutputTokens() {
        // 1M in @ $0.30, 0.5M out @ $2.50 = 0.30 + 1.25 = 1.55
        double cost = pricing.estimateCostUsd("gemini-3.8-flash", 1_000_000, 500_000, 0);
        assertThat(cost).isCloseTo(1.55, within(1e-9));
    }

    @Test
    void proFamilyIsMoreExpensiveThanFlash() {
        double flash = pricing.estimateCostUsd("gemini-3.8-flash", 1_000_000, 1_000_000, 0);
        double pro = pricing.estimateCostUsd("gemini-3.1-pro-preview", 1_000_000, 1_000_000, 0);
        // pro: 1.25 + 10.0 = 11.25
        assertThat(pro).isCloseTo(11.25, within(1e-9));
        assertThat(pro).isGreaterThan(flash);
    }

    @Test
    void imageFamilyPricesPerImage() {
        // flash-image default 0.039/image; 3 images = 0.117 (+ any token cost)
        double cost = pricing.estimateCostUsd("gemini-3.1-flash-image-preview", 0, 0, 3);
        assertThat(cost).isCloseTo(0.117, within(1e-9));
    }

    @Test
    void imageFamilyPrefixBeatsPlainFlashPrefix() {
        // Longest-prefix match: flash-image must resolve to the image family,
        // not the plain flash family (which prices 0 per image).
        double withImages = pricing.estimateCostUsd("gemini-3.1-flash-image", 0, 0, 1);
        assertThat(withImages).isGreaterThan(0.0);
    }

    @Test
    void unknownModelCostsZeroRatherThanThrowing() {
        assertThat(pricing.estimateCostUsd("some-other-model", 1_000_000, 1_000_000, 5))
            .isEqualTo(0.0);
        assertThat(pricing.estimateCostUsd(null, 100, 100, 1)).isEqualTo(0.0);
    }

    @Test
    void negativeCountsAreFlooredAtZero() {
        assertThat(pricing.estimateCostUsd("gemini-3.8-flash", -5, -5, -5)).isEqualTo(0.0);
    }

    @Test
    void configOverrideTakesPrecedenceOverDefaults() {
        AiModelPricing p = new AiModelPricing();
        p.getModels().put("gemini-3.8-flash",
            new AiModelPricing.ModelPrice(10.0, 20.0, 0.0));
        // 1M in @ $10 = 10.0
        assertThat(p.estimateCostUsd("gemini-3.8-flash", 1_000_000, 0, 0))
            .isCloseTo(10.0, within(1e-9));
    }
}
