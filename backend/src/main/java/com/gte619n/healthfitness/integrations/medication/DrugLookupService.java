package com.gte619n.healthfitness.integrations.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Uses Gemini with Google Search grounding to look up drug information.
 * Given a user query (e.g., "testosterone cypionate 200mg weekly"),
 * returns structured drug metadata including category, form, typical doses,
 * and suggested blood markers to track.
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugLookupService {

    private static final String SYSTEM_PROMPT = """
        You are a pharmacology assistant that classifies medications and supplements.
        Given a drug name or query, search for accurate information and return a
        structured JSON response.

        Use Google Search to find current, accurate information about the drug including:
        - Official drug name and common brand names
        - Drug category (prescription, supplement, OTC, peptide, topical)
        - Physical form (injectable vial, tablet, capsule, cream, patch, etc.)
        - Common dosages and units
        - Blood markers that should be monitored when taking this medication

        Return ONE JSON object matching exactly this schema:
        {
          "name": "string — canonical drug name (generic name preferred)",
          "aliases": ["array of alternative names, brand names, abbreviations"],
          "category": "PRESCRIPTION | SUPPLEMENT | OTC | PEPTIDE | TOPICAL",
          "form": "INJECTABLE_VIAL | TABLET | CAPSULE | SOFTGEL | CREAM | PATCH | LIQUID | POWDER",
          "defaultUnit": "string — typical dosing unit (mg, mcg, IU, mL, etc.)",
          "commonDoses": ["array of typical dose strings, e.g., '100mg', '200mg'"],
          "suggestedMarkers": ["array of blood markers to monitor, use canonical names:
            TESTOSTERONE_TOTAL, TESTOSTERONE_FREE, ESTRADIOL, LH, FSH, SHBG,
            PROLACTIN, PSA, HEMATOCRIT, HEMOGLOBIN, RBC, LIVER_PANEL, LIPID_PANEL,
            TOTAL_CHOLESTEROL, LDL, HDL, TRIGLYCERIDES, HBA1C, FASTING_GLUCOSE,
            TSH, FREE_T4, FREE_T3, IGF1, CORTISOL, DHEA_S, VITAMIN_D, VITAMIN_B12,
            FERRITIN, CREATININE, BUN, EGFR"],
          "description": "string — brief 1-2 sentence description of the drug's purpose"
        }

        Category definitions:
        - PRESCRIPTION: Requires a doctor's prescription (testosterone, HCG, etc.)
        - SUPPLEMENT: Over-the-counter dietary supplements (vitamins, minerals, herbs)
        - OTC: Over-the-counter medications (ibuprofen, aspirin, antihistamines)
        - PEPTIDE: Research peptides and growth factors (BPC-157, TB-500, etc.)
        - TOPICAL: Creams, gels, patches applied to skin

        Form definitions:
        - INJECTABLE_VIAL: Liquid in vial for injection (testosterone cypionate, HCG)
        - TABLET: Solid tablet (anastrozole, metformin)
        - CAPSULE: Hard or soft capsule (fish oil, vitamin D)
        - SOFTGEL: Soft gel capsule
        - CREAM: Topical cream or gel
        - PATCH: Transdermal patch
        - LIQUID: Oral liquid/solution
        - POWDER: Powder form (creatine, protein)

        Rules:
        - Use Google Search to verify current drug information
        - Return accurate, medically sound information
        - For TRT-related drugs, include testosterone, estradiol, hematocrit markers
        - For metabolic drugs, include glucose, HbA1c, lipid markers
        - If the drug is not found or query is ambiguous, return null

        Return ONLY the JSON object — no prose, no code fences.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json;

    public DrugLookupService(
        Client client,
        @Value("${app.medications.gemini-model}") String model
    ) {
        this.client = client;
        this.model = model;
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    }

    /**
     * Look up drug information using Gemini with Google Search grounding.
     *
     * @param query User's search query (drug name, description, etc.)
     * @return Structured drug information or null if not found
     */
    public DrugLookupResult lookup(String query) {
        Content content = Content.fromParts(
            Part.fromText(SYSTEM_PROMPT),
            Part.fromText("Look up this medication: " + query)
        );

        // Enable Google Search grounding
        // Note: Cannot use responseMimeType with Google Search tool
        Tool googleSearchTool = Tool.builder()
            .googleSearch(GoogleSearch.builder().build())
            .build();

        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(googleSearchTool))
            .build();

        GenerateContentResponse response = client.models.generateContent(model, content, config);
        String text = response.text();
        if (text == null || text.isBlank() || "null".equals(text.trim())) {
            return null;
        }

        // Clean up response - remove markdown code fences if present
        text = text.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }

        try {
            return json.readValue(text, DrugLookupResult.class);
        } catch (Exception e) {
            throw new DrugLookupException(
                "Failed to parse Gemini JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Result of a drug lookup operation.
     */
    public record DrugLookupResult(
        String name,
        List<String> aliases,
        String category,
        String form,
        String defaultUnit,
        List<String> commonDoses,
        List<String> suggestedMarkers,
        String description
    ) {}

    public static class DrugLookupException extends RuntimeException {
        public DrugLookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
