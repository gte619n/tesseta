package com.gte619n.healthfitness.integrations.gym;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.MediaResolution;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.core.gym.VideoEquipmentDetector;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * IMPL-GYM-003: detects gym equipment from a walkthrough video. Uploads the video
 * to the Gemini Files API (via {@link GeminiFilesService}), calls a video-capable
 * model with a {@code detect_equipment} tool for structured output, and maps the
 * result to {@link ParsedEquipment} — the SAME type the text bulk-import parser
 * emits — so downstream catalog matching/confirm is reused unchanged.
 *
 * <p>Media resolution defaults to LOW: equipment is large and obvious, so the
 * cheaper (~100 tokens/s) tokenization is plenty and keeps per-scan cost tiny.
 */
@Component
@ConditionalOnProperty(name = "app.gym.video-scan.enabled", havingValue = "true", matchIfMissing = false)
public class EquipmentVideoDetector implements VideoEquipmentDetector {

    private static final Logger log = LoggerFactory.getLogger(EquipmentVideoDetector.class);
    static final String TOOL_NAME = "detect_equipment";

    private static final String SYSTEM_PROMPT = """
        You are watching a first-person walkthrough video of a gym. Identify every
        DISTINCT piece of exercise equipment that appears. List each distinct model
        ONCE, even if it appears many times or there are several identical units.
        Ignore people, mirrors, TVs, water fountains, and decor.

        For each item provide:
        - name: canonical generic name (e.g. "Treadmill", "Leg Press", "Dumbbells"),
          NOT a brand-prefixed name.
        - brand: manufacturer if legible/recognizable, otherwise null.
        - category: exactly one of:
            "Free Weights", "Machines - Strength", "Machines - Cardio",
            "Cable Systems", "Benches & Racks", "Bodyweight", "Accessories"
        - subcategory: one canonical value for the chosen category:
            "Free Weights":        Barbells, Dumbbells, Kettlebells, Weight Plates, Other
            "Machines - Strength": Chest, Back, Shoulders, Arms, Legs, Core
            "Machines - Cardio":   Treadmill, Elliptical, Stationary Bike, Rowing Machine, Stair Climber, Other
            "Cable Systems":       Single Cable, Dual Cable, Multi-Station
            "Benches & Racks":     Benches, Racks, Stations
            "Bodyweight":          Pull-Up, Dip, Other
            "Accessories":         Supports, Attachments, Mobility
        - specSchema: one of SELECTORIZED, PLATE_LOADED, BODYWEIGHT, CABLE, CARDIO, WEIGHT_SET.
        - confidence: CERTAIN, LIKELY, or UNCERTAIN — how sure you are of the identification.
        - rawText: a short note of what you saw and roughly when (e.g. "row of 8 Matrix treadmills ~00:14").

        Call the detect_equipment tool with the full list. Do not return prose.
        """;

    private final com.google.genai.Client client;
    private final GeminiFilesService files;
    private final String model;
    private final Tool tool;

    public EquipmentVideoDetector(
        com.google.genai.Client client,
        GeminiFilesService files,
        @Value("${app.gym.video-scan.model:${GEMINI_MODEL:gemini-3.8-flash}}") String model
    ) {
        this.client = client;
        this.files = files;
        this.model = model;
        this.tool = Tool.builder().functionDeclarations(List.of(detectEquipmentTool())).build();
    }

    @Override
    public List<ParsedEquipment> detect(InputStream video, long length, String mimeType) {
        GeminiFilesService.UploadedFile file = files.uploadAndAwaitActive(video, length, mimeType);
        try {
            Content content = Content.fromParts(
                Part.fromText(SYSTEM_PROMPT),
                Part.fromUri(file.uri(), mimeType));
            GenerateContentConfig config = GenerateContentConfig.builder()
                .tools(List.of(tool))
                .mediaResolution(MediaResolution.Known.MEDIA_RESOLUTION_LOW)
                .build();

            GenerateContentResponse response;
            try {
                response = client.models.generateContent(model, content, config);
            } catch (RuntimeException e) {
                throw new IllegalStateException("gym video detection call failed", e);
            }
            Map<String, Object> args = toolArgs(response);
            if (args == null) {
                throw new IllegalStateException("Gemini did not return a detect_equipment tool call");
            }
            return toEquipment(args);
        } finally {
            files.delete(file.name());
        }
    }

    private Map<String, Object> toolArgs(GenerateContentResponse response) {
        List<FunctionCall> calls = response.functionCalls();
        if (calls == null) {
            return null;
        }
        for (FunctionCall call : calls) {
            if (TOOL_NAME.equals(call.name().orElse(null))) {
                return call.args().orElse(Map.of());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ParsedEquipment> toEquipment(Map<String, Object> args) {
        Object raw = args.get("equipment");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ParsedEquipment> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) m;
            String name = str(item.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(new ParsedEquipment(
                name,
                str(item.get("brand")),
                str(item.get("category")),
                str(item.get("subcategory")),
                specSchema(str(item.get("specSchema"))),
                new LinkedHashMap<>(),
                str(item.get("confidence")),
                str(item.get("rawText"))));
        }
        return out;
    }

    private static SpecSchema specSchema(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpecSchema.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.debug("Unknown specSchema from detector: {}", value);
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static FunctionDeclaration detectEquipmentTool() {
        Schema item = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ordered(
                "name", Schema.builder().type(Type.Known.STRING)
                    .description("Canonical generic equipment name.").build(),
                "brand", Schema.builder().type(Type.Known.STRING)
                    .description("Manufacturer, if legible; omit otherwise.").build(),
                "category", Schema.builder().type(Type.Known.STRING).build(),
                "subcategory", Schema.builder().type(Type.Known.STRING).build(),
                "specSchema", Schema.builder().type(Type.Known.STRING)
                    .description("SELECTORIZED | PLATE_LOADED | BODYWEIGHT | CABLE | CARDIO | WEIGHT_SET")
                    .build(),
                "confidence", Schema.builder().type(Type.Known.STRING)
                    .description("CERTAIN | LIKELY | UNCERTAIN").build(),
                "rawText", Schema.builder().type(Type.Known.STRING)
                    .description("Short note of what was seen and roughly when.").build()))
            .required("name", "category")
            .build();

        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ordered(
                "equipment", Schema.builder().type(Type.Known.ARRAY).items(item).build()))
            .required("equipment")
            .build();

        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Return every distinct piece of gym equipment seen in the video.")
            .parameters(params)
            .build();
    }

    private static Map<String, Schema> ordered(Object... kv) {
        Map<String, Schema> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], (Schema) kv[i + 1]);
        }
        return map;
    }
}
