package com.gte619n.healthfitness.integrations.medication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Uses the RxNorm REST API (NIH/NLM) to look up drug information.
 * RxNorm is the standard for drug name normalization used by EHRs.
 * API is free, no authentication required, rate limit 20 req/sec.
 *
 * @see <a href="https://lhncbc.nlm.nih.gov/RxNav/APIs/RxNormAPIs.html">RxNorm API Docs</a>
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class RxNormLookupService {

    private static final String BASE_URL = "https://rxnav.nlm.nih.gov/REST";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper json;

    public RxNormLookupService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    /**
     * Look up a drug by name using RxNorm.
     *
     * @param query Drug name to search for
     * @return Drug information if found, empty if not found
     */
    public Optional<RxNormDrugResult> lookup(String query) {
        try {
            // First, search for drugs matching the name
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = BASE_URL + "/drugs.json?name=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            DrugsResponse drugsResponse = json.readValue(response.body(), DrugsResponse.class);

            if (drugsResponse.drugGroup == null ||
                drugsResponse.drugGroup.conceptGroup == null ||
                drugsResponse.drugGroup.conceptGroup.isEmpty()) {
                return Optional.empty();
            }

            // Find the best match - prefer SBD (Semantic Branded Drug) or SCD (Semantic Clinical Drug)
            ConceptProperties bestMatch = null;
            for (ConceptGroup group : drugsResponse.drugGroup.conceptGroup) {
                if (group.conceptProperties == null) continue;

                for (ConceptProperties props : group.conceptProperties) {
                    if (bestMatch == null) {
                        bestMatch = props;
                    }
                    // Prefer branded drugs (SBD) or clinical drugs (SCD) over ingredients (IN)
                    String tty = group.tty;
                    if ("SBD".equals(tty) || "SCD".equals(tty) || "GPCK".equals(tty) || "BPCK".equals(tty)) {
                        bestMatch = props;
                        break;
                    }
                }
            }

            if (bestMatch == null) {
                return Optional.empty();
            }

            // Get additional properties including drug class
            return Optional.of(buildResult(bestMatch, drugsResponse.drugGroup));

        } catch (Exception e) {
            System.err.println("RxNorm lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    private RxNormDrugResult buildResult(ConceptProperties props, DrugGroup drugGroup) {
        String name = props.name;
        List<String> aliases = new ArrayList<>();

        // Extract synonyms from all concept groups
        if (drugGroup.conceptGroup != null) {
            for (ConceptGroup group : drugGroup.conceptGroup) {
                if (group.conceptProperties != null) {
                    for (ConceptProperties cp : group.conceptProperties) {
                        if (!cp.name.equalsIgnoreCase(name) && !aliases.contains(cp.name)) {
                            aliases.add(cp.name);
                        }
                        if (cp.synonym != null && !cp.synonym.equalsIgnoreCase(name) && !aliases.contains(cp.synonym)) {
                            aliases.add(cp.synonym);
                        }
                    }
                }
            }
        }

        // Determine form from TTY or name
        String form = inferForm(name, drugGroup);

        // Determine category - RxNorm drugs are typically prescriptions
        String category = "PRESCRIPTION";

        // Extract dosage info from name if present
        List<String> commonDoses = extractDoses(name, drugGroup);

        // Determine unit from name
        String defaultUnit = inferUnit(name);

        return new RxNormDrugResult(
            props.rxcui,
            cleanDrugName(name),
            aliases.size() > 5 ? aliases.subList(0, 5) : aliases,
            category,
            form,
            defaultUnit,
            commonDoses,
            List.of(), // suggestedMarkers - will be filled by AI fallback or defaults
            null // description - will be filled by AI fallback
        );
    }

    private String cleanDrugName(String name) {
        // Remove dosage info to get clean drug name
        // "Finasteride 1 MG Oral Tablet" -> "Finasteride"
        String[] parts = name.split("\\s+");
        StringBuilder clean = new StringBuilder();
        for (String part : parts) {
            if (part.matches("\\d+.*") || part.equals("MG") || part.equals("MCG") ||
                part.equals("ML") || part.equals("Oral") || part.equals("Tablet") ||
                part.equals("Capsule") || part.equals("Injectable") || part.equals("Solution") ||
                part.equals("Suspension") || part.equals("Cream") || part.equals("Gel") ||
                part.equals("Patch") || part.equals("Pack") || part.equals("[") ||
                part.contains("]")) {
                break;
            }
            if (!clean.isEmpty()) clean.append(" ");
            clean.append(part);
        }
        String result = clean.isEmpty() ? name : clean.toString();
        return toTitleCase(result);
    }

    /**
     * Converts a string to Title Case.
     * "FINASTERIDE" -> "Finasteride"
     * "vitamin d3" -> "Vitamin D3"
     */
    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        StringBuilder result = new StringBuilder();
        String[] words = input.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                // Keep numbers and alphanumeric combinations (like D3, B12) mostly intact
                if (word.matches("[A-Z]\\d+")) {
                    // Vitamin letter codes like D3, B12 - keep as-is
                    result.append(word);
                } else {
                    // Title case: first letter uppercase, rest lowercase
                    result.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                }
                if (i < words.length - 1) {
                    result.append(" ");
                }
            }
        }
        return result.toString();
    }

    private String inferForm(String name, DrugGroup drugGroup) {
        String lowerName = name.toLowerCase();
        if (lowerName.contains("tablet")) return "TABLET";
        if (lowerName.contains("capsule")) return "CAPSULE";
        if (lowerName.contains("injectable") || lowerName.contains("injection")) return "INJECTABLE_VIAL";
        if (lowerName.contains("cream")) return "CREAM";
        if (lowerName.contains("gel")) return "CREAM";
        if (lowerName.contains("patch")) return "PATCH";
        if (lowerName.contains("solution") || lowerName.contains("liquid")) return "LIQUID";
        if (lowerName.contains("powder")) return "POWDER";
        if (lowerName.contains("softgel")) return "SOFTGEL";

        // Check TTY
        if (drugGroup.conceptGroup != null) {
            for (ConceptGroup group : drugGroup.conceptGroup) {
                if (group.tty != null) {
                    if (group.tty.contains("TAB")) return "TABLET";
                    if (group.tty.contains("CAP")) return "CAPSULE";
                    if (group.tty.contains("INJ")) return "INJECTABLE_VIAL";
                }
            }
        }

        return "TABLET"; // Default
    }

    private String inferUnit(String name) {
        if (name.contains(" MG ") || name.contains(" MG/")) return "mg";
        if (name.contains(" MCG ") || name.contains(" MCG/")) return "mcg";
        if (name.contains(" ML ") || name.contains(" ML/")) return "mL";
        if (name.contains(" IU ") || name.contains(" IU/")) return "IU";
        return "mg"; // Default
    }

    private List<String> extractDoses(String name, DrugGroup drugGroup) {
        List<String> doses = new ArrayList<>();

        // Extract from all concept names
        if (drugGroup.conceptGroup != null) {
            for (ConceptGroup group : drugGroup.conceptGroup) {
                if (group.conceptProperties != null) {
                    for (ConceptProperties cp : group.conceptProperties) {
                        String dose = extractDoseFromName(cp.name);
                        if (dose != null && !doses.contains(dose)) {
                            doses.add(dose);
                        }
                    }
                }
            }
        }

        return doses.size() > 5 ? doses.subList(0, 5) : doses;
    }

    private String extractDoseFromName(String name) {
        // Match patterns like "1 MG", "100 MCG", "200 MG/ML"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*(?:MG|MCG|ML|IU|%|G)(?:/ML)?)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase().replaceAll("\\s+", "");
        }
        return null;
    }

    /**
     * Result of an RxNorm drug lookup.
     */
    public record RxNormDrugResult(
        String rxcui,
        String name,
        List<String> aliases,
        String category,
        String form,
        String defaultUnit,
        List<String> commonDoses,
        List<String> suggestedMarkers,
        String description
    ) {}

    // JSON response DTOs

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DrugsResponse(DrugGroup drugGroup) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DrugGroup(String name, List<ConceptGroup> conceptGroup) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConceptGroup(String tty, List<ConceptProperties> conceptProperties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConceptProperties(String rxcui, String name, String synonym, String tty) {}
}
