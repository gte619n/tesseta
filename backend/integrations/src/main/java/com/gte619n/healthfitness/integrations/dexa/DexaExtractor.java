package com.gte619n.healthfitness.integrations.dexa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Sends a DEXA PDF directly to the Gemini API and asks for a strict-JSON
// extraction. The model is given an explicit field list and example
// units in the prompt; mismatched/missing fields come back as null
// (the JSON schema is enforced via responseMimeType="application/json"
// plus an explicit response format spec in the prompt).
@Component
@ConditionalOnProperty(name = "app.dexa.enabled", havingValue = "true", matchIfMissing = true)
public class DexaExtractor {

    private static final String SYSTEM_PROMPT = """
        You are extracting structured fields from a DEXA body composition
        report PDF. Read the document and return ONE JSON object matching
        exactly the schema below. Field names and value types are
        mandatory. If a field is absent or unreadable, return null for
        that field — do NOT guess.

        All mass values are pounds (lb). Percent values are unitless
        percents (e.g. 31.4 means 31.4%%). Dates are ISO-8601 (YYYY-MM-DD).

        Schema (every field nullable):
        {
          "measuredOn": "YYYY-MM-DD or null — the latest/current scan date",
          "sourceFacility": "string or null — e.g. 'DEXA Body Atlanta'",
          "totalMassLb": number,
          "leanTissueLb": number,
          "fatTissueLb": number,
          "totalBodyFatPercent": number,
          "visceralFatLb": number,
          "androidGynoidRatio": number,
          "trunk":      { "totalMassLb": n, "leanTissueLb": n, "fatTissueLb": n, "regionFatPercent": n },
          "android":    { ... same shape ... },
          "gynoid":     { ... },
          "armsTotal":  { ... },
          "armsRight":  { ... },
          "armsLeft":   { ... },
          "legsTotal":  { ... },
          "legsRight":  { ... },
          "legsLeft":   { ... },
          "bmdTScore": number,
          "bmdZScore": number,
          "restingMetabolicRateKcal": integer
        }

        Rules:
        - The summary table at the top has multiple dates. Extract the
          MOST RECENT row only.
        - "Region Fat" or "Region %" columns are the regionFatPercent.
        - Bone density T-score and Z-score are the latest values.
        - RMR is in kcal/day.

        Return ONLY the JSON object — no prose, no code fences.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json;

    public DexaExtractor(
        Client client,
        @Value("${app.dexa.gemini-model}") String model
    ) {
        this.client = client;
        this.model = model;
        this.json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    }

    public DexaExtraction extract(byte[] pdfBytes) {
        Content content = Content.fromParts(
            Part.fromText(SYSTEM_PROMPT),
            Part.fromBytes(pdfBytes, "application/pdf")
        );
        GenerateContentConfig config = GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .build();

        GenerateContentResponse response = client.models.generateContent(model, content, config);
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new DexaExtractionException("Gemini returned an empty response");
        }
        try {
            return json.readValue(text, DexaExtraction.class);
        } catch (Exception e) {
            throw new DexaExtractionException(
                "Failed to parse Gemini JSON response: " + e.getMessage(), e);
        }
    }
}
