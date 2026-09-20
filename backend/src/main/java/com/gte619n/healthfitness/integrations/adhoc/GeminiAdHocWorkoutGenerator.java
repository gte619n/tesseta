package com.gte619n.healthfitness.integrations.adhoc;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutGenerator;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.DeloadModifier;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed single-workout generator (IMPL-ADHOC-01 D15). One structured
 * {@code propose_adhoc_workout} tool call, constrained to an equipment-derived
 * allow-list of published exercises, within a time budget. Gated behind
 * {@code app.adhoc-workouts.enabled} (off by default) so test/CI contexts and
 * deployments without an API key start clean; production sets it true.
 *
 * <p>Not exercised by automated tests (no live API key in CI), exactly like the
 * program designer client — the orchestration around it (equipment resolution,
 * estimate, constraint validate/repair) is unit-tested with a stub generator.
 */
@Component
@ConditionalOnProperty(name = "app.adhoc-workouts.enabled", havingValue = "true")
public class GeminiAdHocWorkoutGenerator implements AdHocWorkoutGenerator {

    static final String TOOL_NAME = "propose_adhoc_workout";
    /** Cap the exercises listed in the prompt so a huge catalog can't blow the context. */
    private static final int MAX_ALLOWLIST = 300;

    private final Client client;
    private final String model;
    private final ExerciseService exercises;
    private final ExerciseAvailabilityService availability;
    private final Tool tool;

    public GeminiAdHocWorkoutGenerator(
        Client client,
        ExerciseService exercises,
        ExerciseAvailabilityService availability,
        @Value("${app.adhoc-workouts.gemini-model:gemini-3.1-pro-preview}") String model
    ) {
        this.client = client;
        this.exercises = exercises;
        this.availability = availability;
        this.model = model;
        this.tool = Tool.builder().functionDeclarations(List.of(proposeTool())).build();
    }

    @Override
    public Generated generate(
        String userId, String prompt, Integer targetDurationMinutes, Set<String> availableEquipmentIds
    ) {
        List<Exercise> allowed = exercises.listPublished(null, null, null, null).stream()
            .filter(availability::mediaOk)
            .filter(e -> ExerciseAvailabilityService.satisfiedBy(e, availableEquipmentIds))
            .limit(MAX_ALLOWLIST)
            .toList();

        String context = allowListContext(allowed, targetDurationMinutes);
        GenerateContentConfig config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText(systemPrompt() + "\n\n" + context)))
            .tools(List.of(tool))
            .build();
        List<Content> contents = List.of(
            Content.builder().role("user").parts(List.of(Part.fromText(prompt))).build());

        GenerateContentResponse response = client.models.generateContent(model, contents, config);
        List<FunctionCall> calls = response.functionCalls();
        if (calls != null) {
            for (FunctionCall call : calls) {
                if (TOOL_NAME.equals(call.name().orElse(null))) {
                    return toGenerated(call.args().orElse(Map.of()));
                }
            }
        }
        // No structured proposal — return an empty day the constraint validator
        // will pass (nothing prescribed) and the user can edit/regenerate.
        return new Generated("Ad-hoc workout", null, List.of(),
            new WorkoutDay(null, "Workout", null, null, 0, List.of()));
    }

    private static String allowListContext(List<Exercise> allowed, Integer minutes) {
        StringBuilder sb = new StringBuilder();
        if (minutes != null) {
            sb.append("TIME BUDGET: aim for about ").append(minutes).append(" minutes total.\n\n");
        }
        sb.append("EXERCISE ALLOW-LIST — you may ONLY prescribe an exerciseId from this list "
            + "(they are executable with the available equipment). Never invent an id:\n");
        for (Exercise e : allowed) {
            sb.append("- ").append(e.exerciseId()).append(" : ").append(e.name());
            if (Boolean.TRUE.equals(e.isTimed())) {
                sb.append(" (timed)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---- tool args -> WorkoutDay ----

    @SuppressWarnings("unchecked")
    Generated toGenerated(Map<String, Object> args) {
        String title = str(args.get("title"));
        String summary = str(args.get("summary"));
        List<String> tags = new ArrayList<>();
        if (args.get("tags") instanceof List<?> tl) {
            for (Object t : tl) {
                if (t != null) tags.add(t.toString());
            }
        }
        WorkoutDay day = new WorkoutDay(null, title != null ? title : "Workout", null, null, 0,
            blocks(args.get("blocks")));
        return new Generated(title != null ? title : "Ad-hoc workout", summary, tags, day);
    }

    @SuppressWarnings("unchecked")
    private static List<Block> blocks(Object raw) {
        List<Block> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int bi = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m0)) continue;
            Map<String, Object> m = (Map<String, Object>) m0;
            out.add(new Block(null,
                enumOrNull(str(m.get("type")), com.gte619n.healthfitness.core.exercise.BlockType.class),
                str(m.get("title")), bi++, prescriptions(m.get("prescriptions"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Prescription> prescriptions(Object raw) {
        List<Prescription> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int i = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m0)) continue;
            Map<String, Object> m = (Map<String, Object>) m0;
            Intensity intensity = null;
            if (m.get("intensity") instanceof Map<?, ?> im0) {
                Map<String, Object> im = (Map<String, Object>) im0;
                intensity = new Intensity(enumOrNull(str(im.get("kind")), IntensityKind.class), dbl(im.get("value")));
            }
            DeloadModifier deload = null;
            out.add(new Prescription(str(m.get("exerciseId")), i++, intg(m.get("sets")),
                intg(m.get("repsMin")), intg(m.get("repsMax")), intg(m.get("durationSeconds")),
                intensity, intg(m.get("restSeconds")), str(m.get("tempo")), str(m.get("notes")), deload, null,
                dbl(m.get("targetWeightLbs")), str(m.get("loadBasis"))));
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer intg(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Math.round(Double.parseDouble(o.toString())); } catch (NumberFormatException e) { return null; }
    }

    private static <E extends Enum<E>> E enumOrNull(String name, Class<E> type) {
        if (name == null) return null;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return null; }
    }

    // ---- tool schema ----

    private static FunctionDeclaration proposeTool() {
        Schema intensity = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "kind", Schema.builder().type(Type.Known.STRING).enum_("RPE", "PERCENT_1RM", "NONE").build(),
                "value", Schema.builder().type(Type.Known.NUMBER).build()))
            .build();
        Schema prescription = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "exerciseId", Schema.builder().type(Type.Known.STRING)
                    .description("MUST be an exerciseId from the allow-list in the context.").build(),
                "sets", Schema.builder().type(Type.Known.INTEGER).build(),
                "repsMin", Schema.builder().type(Type.Known.INTEGER).build(),
                "repsMax", Schema.builder().type(Type.Known.INTEGER).build(),
                "durationSeconds", Schema.builder().type(Type.Known.INTEGER)
                    .description("For timed/cardio/holds instead of reps.").build(),
                "intensity", intensity,
                "restSeconds", Schema.builder().type(Type.Known.INTEGER).build(),
                "tempo", Schema.builder().type(Type.Known.STRING).build(),
                "notes", Schema.builder().type(Type.Known.STRING).build(),
                "targetWeightLbs", Schema.builder().type(Type.Known.NUMBER)
                    .description("Concrete working load in lb when the available equipment offers it and "
                        + "history supports it; never above the user's e1RM. Omit for bodyweight.").build(),
                "loadBasis", Schema.builder().type(Type.Known.STRING).build()))
            .required("exerciseId")
            .build();
        Schema block = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "type", Schema.builder().type(Type.Known.STRING)
                    .enum_("WARMUP", "MOBILITY", "CARDIO", "MAIN", "ACCESSORY", "CORE", "COOLDOWN", "STRETCH").build(),
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "prescriptions", Schema.builder().type(Type.Known.ARRAY).items(prescription).build()))
            .required("type", "prescriptions")
            .build();
        Schema params = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING)
                    .description("Short, purpose-driven title, e.g. 'Hotel Gym Full Body'.").build(),
                "summary", Schema.builder().type(Type.Known.STRING)
                    .description("One-line description of the workout's purpose.").build(),
                "tags", Schema.builder().type(Type.Known.ARRAY)
                    .items(Schema.builder().type(Type.Known.STRING).build())
                    .description("1-3 purpose tags, e.g. Travel, Full-body, Quick.").build(),
                "blocks", Schema.builder().type(Type.Known.ARRAY).items(block).build()))
            .required("title", "blocks")
            .build();
        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Propose a single ad-hoc workout for one session, fitting the time budget and "
                + "using only allow-listed exercises, for the user to review and edit.")
            .parameters(params)
            .build();
    }

    private static Map<String, Schema> orderedMap(Object... kv) {
        Map<String, Schema> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], (Schema) kv[i + 1]);
        }
        return map;
    }

    static String systemPrompt() {
        return """
            You design ONE ad-hoc workout for a single session — something the user can \
            knock out right now (travelling, a hotel gym, a Sunday at home). Not a \
            multi-week program: a single day of Blocks (WARMUP / MAIN / ACCESSORY / \
            optional CORE / COOLDOWN), each with ordered prescriptions.

            HARD RULES:
            - You may ONLY prescribe an exerciseId that appears in the allow-list in the \
            context (those are executable with the available equipment). NEVER invent an \
            exerciseId or prescribe one that is not listed.
            - Fit roughly the requested time budget (a duration estimator will check).
            - Prefer loads the available equipment actually offers. Never prescribe a \
            targetWeightLbs above the user's estimated 1RM; omit it for bodyweight moves.
            - Give the workout a short purpose-driven title and 1-3 tags.
            """;
    }
}
