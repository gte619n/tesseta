package com.gte619n.healthfitness.core.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-backed model &rarr; price table (IMPL-MULTIUSER-01 Pillar 2, D4).
 *
 * <p>Prices are USD per million input/output tokens and USD per generated
 * image. The table is keyed by a MODEL FAMILY PREFIX, resolved by longest
 * matching prefix against the concrete model name (so {@code gemini-3.8-flash}
 * and {@code gemini-3.1-flash-image} both resolve without listing every point
 * release). Sensible in-code defaults ship for the three families the app uses
 * today — flash (cheap text), pro (expensive text), flash-image (per-image) —
 * so metering works with zero config; {@code app.ai.pricing.*} in
 * application.yml overrides or extends the table.
 *
 * <p>Self-registers via {@code @Component} + {@code @ConfigurationProperties}
 * so it binds without an entry in the shared {@code @EnableConfigurationProperties}
 * list.
 *
 * <p>Pricing is an ESTIMATE only — not reconciled against actual Gemini billing
 * (the real billing project is separate). See spec §11.
 */
@Component
@ConfigurationProperties(prefix = "app.ai.pricing")
public class AiModelPricing {

    /**
     * One price point. Fields are nullable (boxed) so partial overrides in
     * application.yml leave the in-code default in place for the others.
     */
    public static class ModelPrice {
        private Double inputUsdPerMillion;
        private Double outputUsdPerMillion;
        private Double usdPerImage;

        public ModelPrice() {}

        public ModelPrice(Double inputUsdPerMillion, Double outputUsdPerMillion, Double usdPerImage) {
            this.inputUsdPerMillion = inputUsdPerMillion;
            this.outputUsdPerMillion = outputUsdPerMillion;
            this.usdPerImage = usdPerImage;
        }

        public Double getInputUsdPerMillion() {
            return inputUsdPerMillion;
        }

        public void setInputUsdPerMillion(Double inputUsdPerMillion) {
            this.inputUsdPerMillion = inputUsdPerMillion;
        }

        public Double getOutputUsdPerMillion() {
            return outputUsdPerMillion;
        }

        public void setOutputUsdPerMillion(Double outputUsdPerMillion) {
            this.outputUsdPerMillion = outputUsdPerMillion;
        }

        public Double getUsdPerImage() {
            return usdPerImage;
        }

        public void setUsdPerImage(Double usdPerImage) {
            this.usdPerImage = usdPerImage;
        }

        double inputOrZero() {
            return inputUsdPerMillion == null ? 0.0 : inputUsdPerMillion;
        }

        double outputOrZero() {
            return outputUsdPerMillion == null ? 0.0 : outputUsdPerMillion;
        }

        double imageOrZero() {
            return usdPerImage == null ? 0.0 : usdPerImage;
        }
    }

    /**
     * In-code defaults, keyed by family prefix. Longest-prefix match wins, so
     * {@code gemini-3.1-flash-image} resolves to the image family even though
     * {@code gemini-3.1-flash} / {@code gemini} would also prefix-match.
     *
     * <p>Values are representative order-of-magnitude estimates for the three
     * families (text flash, text pro, per-image). Override in application.yml
     * to true vendor rates.
     */
    private final Map<String, ModelPrice> defaults = defaultTable();

    /** Overrides/additions from {@code app.ai.pricing.models.*}. */
    private Map<String, ModelPrice> models = new LinkedHashMap<>();

    public Map<String, ModelPrice> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelPrice> models) {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }

    private static Map<String, ModelPrice> defaultTable() {
        Map<String, ModelPrice> t = new LinkedHashMap<>();
        // Per-image family FIRST only for readability; resolution is by prefix
        // length, not insertion order.
        //   flash-image: native image generation (per-image priced).
        t.put("gemini-3.1-flash-image", new ModelPrice(0.30, 2.50, 0.039));
        //   flash: cheap multimodal text/extraction.
        t.put("gemini-3.8-flash", new ModelPrice(0.30, 2.50, 0.0));
        t.put("gemini-3.1-flash", new ModelPrice(0.30, 2.50, 0.0));
        //   pro: expensive reasoning/chat (Goals).
        t.put("gemini-3.1-pro", new ModelPrice(1.25, 10.0, 0.0));
        //   broad family fallbacks.
        t.put("gemini-flash", new ModelPrice(0.30, 2.50, 0.0));
        t.put("gemini-pro", new ModelPrice(1.25, 10.0, 0.0));
        t.put("gemini", new ModelPrice(0.30, 2.50, 0.0));
        return t;
    }

    /**
     * Estimate the USD cost of one call.
     *
     * @param model        concrete model name (e.g. {@code gemini-3.8-flash})
     * @param inputTokens  prompt tokens
     * @param outputTokens candidate tokens
     * @param images       generated images (0 for text calls)
     */
    public double estimateCostUsd(String model, long inputTokens, long outputTokens, long images) {
        ModelPrice p = priceFor(model);
        double cost = 0.0;
        cost += (Math.max(0L, inputTokens) / 1_000_000.0) * p.inputOrZero();
        cost += (Math.max(0L, outputTokens) / 1_000_000.0) * p.outputOrZero();
        cost += Math.max(0L, images) * p.imageOrZero();
        return cost;
    }

    /**
     * Resolve the price for a model by longest matching family prefix. Explicit
     * config overrides ({@code models}) are consulted first (also by longest
     * prefix), then the in-code defaults. An unknown model yields a zero price
     * (cost 0) rather than throwing — metering never breaks an AI call.
     */
    ModelPrice priceFor(String model) {
        if (model == null || model.isBlank()) {
            return new ModelPrice(0.0, 0.0, 0.0);
        }
        ModelPrice override = longestPrefixMatch(models, model);
        if (override != null) {
            return override;
        }
        ModelPrice def = longestPrefixMatch(defaults, model);
        return def != null ? def : new ModelPrice(0.0, 0.0, 0.0);
    }

    private static ModelPrice longestPrefixMatch(Map<String, ModelPrice> table, String model) {
        if (table == null || table.isEmpty()) {
            return null;
        }
        String bestKey = null;
        for (String key : table.keySet()) {
            if (key != null && model.startsWith(key)
                && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return bestKey == null ? null : table.get(bestKey);
    }
}
