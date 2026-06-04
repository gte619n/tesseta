package com.gte619n.healthfitness.integrations.bloodtest;

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

@Component
@ConditionalOnProperty(name = "app.bloodtest.enabled", havingValue = "true", matchIfMissing = true)
public class BloodTestExtractor {

    private static final String SYSTEM_PROMPT = """
        You are extracting structured fields from a blood test lab report
        PDF. Read the document and return ONE JSON object matching
        exactly the schema below. Field names and value types are
        mandatory. If a field is absent or unreadable, return null for
        that field — do NOT guess.

        Dates are ISO-8601 (YYYY-MM-DD). Units should be preserved exactly
        as shown in the report (e.g., "mg/dL", "mmol/L", "%", "mg/L").

        Schema:
        {
          "sampleDate": "YYYY-MM-DD or null — the date blood was drawn",
          "labSource": "string or null — e.g. 'Quest Diagnostics', 'LabCorp'",
          "markers": [
            {
              "name": "string — canonical marker name, use these exact names when matching:
                       TOTAL_CHOLESTEROL, LDL, HDL, TRIGLYCERIDES, APO_B,
                       HBA1C, FASTING_GLUCOSE, HS_CRP, VLDL, NON_HDL_CHOLESTEROL,
                       TC_HDL_RATIO, INSULIN, HOMA_IR, ALT, AST, GGT,
                       CREATININE, EGFR, BUN, URIC_ACID, TESTOSTERONE,
                       TSH, FREE_T4, FREE_T3,
                       VITAMIN_D, VITAMIN_B12, FERRITIN, IRON, TIBC,
                       WBC, RBC, HEMOGLOBIN, HEMATOCRIT, PLATELETS",
              "value": number or null,
              "unit": "string — exact unit from report",
              "refRangeLow": number or null — lower bound of reference range,
              "refRangeHigh": number or null — upper bound of reference range,
              "flag": "H" or "L" or null — if marked high/low on report
            }
          ]
        }

        Rules:
        - Extract ALL markers present in the report, not just common ones.
        - Use the canonical name from the list above when the marker matches.
        - TESTOSTERONE means TOTAL testosterone — map "Total Testosterone",
          "Testosterone, Total", "Testosterone, Serum" etc. to TESTOSTERONE.
          Keep "Free Testosterone" / "Bioavailable Testosterone" as their exact
          report names; they are distinct markers, not TESTOSTERONE.
        - For markers not in the canonical list, use the exact name from the report.
        - Reference ranges may be shown as "< X" (use null for low, X for high)
          or "> X" (use X for low, null for high) or "X - Y" (use X for low, Y for high).
        - Preserve the exact units from the report.

        Return ONLY the JSON object — no prose, no code fences.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json;

    public BloodTestExtractor(
        Client client,
        @Value("${app.bloodtest.gemini-model}") String model
    ) {
        this.client = client;
        this.model = model;
        this.json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    }

    public BloodTestExtraction extract(byte[] pdfBytes) {
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
            throw new BloodTestExtractionException("Gemini returned an empty response");
        }
        try {
            return json.readValue(text, BloodTestExtraction.class);
        } catch (Exception e) {
            throw new BloodTestExtractionException(
                "Failed to parse Gemini JSON response: " + e.getMessage(), e);
        }
    }
}
