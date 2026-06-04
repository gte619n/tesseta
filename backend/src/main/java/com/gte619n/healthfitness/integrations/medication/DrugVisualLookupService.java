package com.gte619n.healthfitness.integrations.medication;

import com.gte619n.healthfitness.config.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fetches drug visual information from FDA/NIH sources:
 *
 * 1. OpenFDA Drug Label - Physical characteristics from drug label text
 *    (color, shape, imprint parsed from "dosage forms" and "how supplied" sections)
 * 2. OpenFDA NDC - Dosage form, route, manufacturer
 * 3. DailyMed SPL - Structured Product Labeling
 *
 * Note: RxImageAccess API was discontinued in December 2021.
 *
 * This data is used to generate accurate medication imagery.
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugVisualLookupService {

    private static final String OPENFDA_LABEL_BASE = "https://api.fda.gov/drug/label.json";
    private static final String OPENFDA_NDC_BASE = "https://api.fda.gov/drug/ndc.json";
    private static final String DAILYMED_BASE = "https://dailymed.nlm.nih.gov/dailymed/services/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Patterns for extracting pill characteristics from FDA label text
    private static final Pattern COLOR_PATTERN = Pattern.compile(
        "\\b(white|off-white|cream|yellow|orange|peach|pink|red|brown|tan|beige|green|blue|purple|gray|grey|black|maroon)\\s*(?:color(?:ed)?)?\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SHAPE_PATTERN = Pattern.compile(
        "\\b(round|oval|oblong|capsule[- ]?shaped|rectangular|square|triangular|diamond|hexagonal|octagonal|biconvex|flat)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMPRINT_PATTERN = Pattern.compile(
        "(?:debossed|embossed|imprinted|marked|engraved|printed)\\s+with\\s+['\"]([A-Z0-9\\-/]+)['\"](?:\\s+on\\s+one\\s+side)?(?:\\s+and\\s+['\"]([A-Z0-9\\-/]+)['\"])?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SIZE_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(?:mm|millimeter)",
        Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;
    private final ObjectMapper json;

    public DrugVisualLookupService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
        this.json = JsonSupport.LENIENT;
    }

    /**
     * Fetch all available visual information for a drug.
     * Combines data from OpenFDA Label, NDC, and DailyMed.
     *
     * @param rxcui The RxNorm Concept Unique Identifier (currently unused, kept for API compat)
     * @param drugName The drug name
     * @return Visual characteristics if available
     */
    public DrugVisualInfo lookup(String rxcui, String drugName) {
        // Fetch from FDA Drug Label (best source for physical characteristics)
        Optional<FdaLabelResult> labelResult = fetchFdaLabel(drugName);

        // Fetch from OpenFDA NDC (for dosage form, route, manufacturer)
        Optional<OpenFdaResult> ndcResult = fetchOpenFdaNdc(drugName);

        // Merge results
        return DrugVisualInfo.merge(labelResult, ndcResult, drugName);
    }

    /**
     * Fetch pill characteristics from FDA Drug Label.
     * Parses the "dosage_forms_and_strengths" and "how_supplied" sections
     * to extract color, shape, and imprint information.
     */
    private Optional<FdaLabelResult> fetchFdaLabel(String drugName) {
        if (drugName == null || drugName.isBlank()) {
            return Optional.empty();
        }

        try {
            String encoded = URLEncoder.encode(drugName, StandardCharsets.UTF_8);
            String url = OPENFDA_LABEL_BASE + "?search=openfda.generic_name:" + encoded + "&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode root = json.readTree(response.body());
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            JsonNode first = results.get(0);

            // Get text fields that might contain physical description
            String dosageFormsText = getArrayText(first, "dosage_forms_and_strengths");
            String howSuppliedText = getArrayText(first, "how_supplied");
            String descriptionText = getArrayText(first, "description");

            // Combine all text for parsing
            String allText = String.join(" ", dosageFormsText, howSuppliedText, descriptionText);

            // Parse physical characteristics
            String color = extractFirstMatch(COLOR_PATTERN, allText);
            String shape = extractFirstMatch(SHAPE_PATTERN, allText);
            String imprint = extractImprint(allText);
            String size = extractFirstMatch(SIZE_PATTERN, allText);

            // Get brand name from openfda section
            String brandName = null;
            JsonNode openfda = first.path("openfda");
            if (openfda.has("brand_name") && openfda.path("brand_name").isArray()) {
                JsonNode brandNames = openfda.path("brand_name");
                if (!brandNames.isEmpty()) {
                    brandName = brandNames.get(0).asText(null);
                }
            }

            return Optional.of(new FdaLabelResult(
                color,
                shape,
                imprint,
                size,
                brandName,
                dosageFormsText.isBlank() ? null : dosageFormsText
            ));

        } catch (Exception e) {
            System.err.println("FDA Label lookup failed for " + drugName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch from OpenFDA NDC database for dosage form and manufacturer.
     */
    private Optional<OpenFdaResult> fetchOpenFdaNdc(String drugName) {
        if (drugName == null || drugName.isBlank()) {
            return Optional.empty();
        }

        try {
            String encoded = URLEncoder.encode(drugName, StandardCharsets.UTF_8);
            String url = OPENFDA_NDC_BASE + "?search=generic_name:" + encoded +
                         "+OR+brand_name:" + encoded + "&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode root = json.readTree(response.body());
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            JsonNode first = results.get(0);

            String dosageForm = first.path("dosage_form").asText(null);
            JsonNode routeArray = first.path("route");
            String route = (routeArray.isArray() && !routeArray.isEmpty())
                ? routeArray.get(0).asText(null)
                : null;
            String brandName = first.path("brand_name").asText(null);
            String genericName = first.path("generic_name").asText(null);
            String labelerName = first.path("labeler_name").asText(null);

            // Active ingredients
            List<String> activeIngredients = new ArrayList<>();
            JsonNode ingredients = first.path("active_ingredients");
            if (ingredients.isArray()) {
                for (JsonNode ing : ingredients) {
                    String name = ing.path("name").asText(null);
                    String strength = ing.path("strength").asText(null);
                    if (name != null) {
                        activeIngredients.add(strength != null ? name + " " + strength : name);
                    }
                }
            }

            return Optional.of(new OpenFdaResult(
                dosageForm, route, brandName, genericName,
                labelerName, activeIngredients
            ));

        } catch (Exception e) {
            System.err.println("OpenFDA NDC lookup failed for " + drugName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    // Helper methods

    private String getArrayText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isArray() && !field.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : field) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(item.asText(""));
            }
            return sb.toString();
        }
        return "";
    }

    private String extractFirstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractImprint(String text) {
        Matcher matcher = IMPRINT_PATTERN.matcher(text);
        if (matcher.find()) {
            String part1 = matcher.group(1);
            String part2 = matcher.group(2);
            if (part2 != null && !part2.isBlank()) {
                return part1 + "/" + part2;
            }
            return part1;
        }
        return null;
    }

    // Result records

    public record FdaLabelResult(
        String color,
        String shape,
        String imprint,
        String size,
        String brandName,
        String physicalDescription
    ) {}

    public record OpenFdaResult(
        String dosageForm,
        String route,
        String brandName,
        String genericName,
        String labelerName,
        List<String> activeIngredients
    ) {}

    /**
     * Consolidated visual information from all sources.
     */
    public record DrugVisualInfo(
        String drugName,
        // No real image URL - RxImageAccess was discontinued in 2021
        String realImageUrl,
        // Physical characteristics from FDA label
        String color,
        String shape,
        String imprint,
        String size,
        String dosageForm,
        String route,
        String brandName,
        String manufacturer,
        String physicalDescription,
        // Whether we have enough info for accurate AI generation
        boolean hasVisualCharacteristics
    ) {
        /**
         * Merge results from all sources into a single DrugVisualInfo.
         */
        public static DrugVisualInfo merge(
            Optional<FdaLabelResult> labelResult,
            Optional<OpenFdaResult> ndcResult,
            String drugName
        ) {
            // From FDA Label
            String color = labelResult.map(FdaLabelResult::color).orElse(null);
            String shape = labelResult.map(FdaLabelResult::shape).orElse(null);
            String imprint = labelResult.map(FdaLabelResult::imprint).orElse(null);
            String size = labelResult.map(FdaLabelResult::size).orElse(null);
            String physicalDescription = labelResult.map(FdaLabelResult::physicalDescription).orElse(null);

            // From OpenFDA NDC
            String dosageForm = ndcResult.map(OpenFdaResult::dosageForm).orElse(null);
            String route = ndcResult.map(OpenFdaResult::route).orElse(null);
            String manufacturer = ndcResult.map(OpenFdaResult::labelerName).orElse(null);

            // Brand name - prefer label, fall back to NDC
            String brandName = labelResult.map(FdaLabelResult::brandName)
                .orElse(ndcResult.map(OpenFdaResult::brandName).orElse(null));

            // We have visual characteristics if we have color OR shape OR dosage form
            boolean hasVisuals = color != null || shape != null || dosageForm != null;

            return new DrugVisualInfo(
                drugName,
                null,  // No real image URL - RxImageAccess discontinued
                color,
                shape,
                imprint,
                size,
                dosageForm,
                route,
                brandName,
                manufacturer,
                physicalDescription,
                hasVisuals
            );
        }

        /**
         * Build a detailed prompt snippet for AI image generation.
         */
        public String toPromptDescription() {
            StringBuilder sb = new StringBuilder();

            // Check if physical description indicates injection pen
            boolean isPenFromDescription = physicalDescription != null &&
                (physicalDescription.toLowerCase().contains("pen") ||
                 physicalDescription.toLowerCase().contains("pre-filled") ||
                 physicalDescription.toLowerCase().contains("prefilled"));

            // For pen injectors, skip color/shape since those don't apply
            if (isLikelyPenInjector() || isPenFromDescription) {
                return "modern prefilled injection pen with dose dial and protective cap, sleek medical device design";
            }

            if (color != null) {
                sb.append(color.toLowerCase()).append(" ");
            }

            if (shape != null) {
                sb.append(shape.toLowerCase()).append(" ");
            }

            // Map dosage form to visual description
            if (dosageForm != null) {
                String form = dosageForm.toLowerCase();
                if (form.contains("tablet")) {
                    sb.append("tablet");
                } else if (form.contains("capsule")) {
                    sb.append("capsule");
                } else if (form.contains("injection") || form.contains("injectable")) {
                    sb.append("injection vial");
                } else if (form.contains("solution")) {
                    sb.append("liquid solution bottle");
                } else if (form.contains("cream") || form.contains("ointment")) {
                    sb.append("topical cream tube");
                } else if (form.contains("patch")) {
                    sb.append("transdermal patch");
                } else if (form.contains("powder") || form.contains("granule")) {
                    // Check if it's actually an injectable that comes as powder
                    if (isPenFromDescription) {
                        sb.append("prefilled injection pen");
                    } else {
                        sb.append("powder");
                    }
                } else {
                    sb.append(form);
                }
            } else if (sb.isEmpty()) {
                sb.append("pharmaceutical product");
            }

            if (imprint != null && !imprint.isBlank()) {
                sb.append(" with '").append(imprint).append("' imprint");
            }

            if (size != null) {
                sb.append(", ").append(size).append("mm");
            }

            return sb.toString().trim();
        }

        /**
         * Check if this drug is likely a pen injector based on brand/form.
         */
        private boolean isLikelyPenInjector() {
            if (brandName == null && drugName == null) return false;

            String nameToCheck = (brandName != null ? brandName : drugName).toLowerCase();

            return nameToCheck.contains("mounjaro") ||
                   nameToCheck.contains("ozempic") ||
                   nameToCheck.contains("wegovy") ||
                   nameToCheck.contains("zepbound") ||
                   nameToCheck.contains("trulicity") ||
                   nameToCheck.contains("victoza") ||
                   nameToCheck.contains("saxenda") ||
                   nameToCheck.contains("rybelsus") ||
                   nameToCheck.contains("tirzepatide") ||
                   nameToCheck.contains("semaglutide") ||
                   nameToCheck.contains("liraglutide") ||
                   nameToCheck.contains("dulaglutide") ||
                   nameToCheck.contains("insulin") ||
                   nameToCheck.contains("lantus") ||
                   nameToCheck.contains("humalog") ||
                   nameToCheck.contains("novolog");
        }
    }
}
