package com.gte619n.healthfitness.integrations.workoutprogram;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.DeloadModifier;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed program designer. Designs a Program → Phase → Day → Block →
 * Prescription structure via the {@code propose_workout_program} tool call,
 * using the Pro model (ADR-0007). The per-request context (health snapshot +
 * per-gym executable-exercise allow-lists) is appended to the system prompt so
 * the model only prescribes executable exercises.
 */
@Component
@ConditionalOnProperty(name = "app.workout-programs.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiWorkoutProgramChatClient implements WorkoutProgramChatClient {

    static final String TOOL_NAME = "propose_workout_program";

    private final Client client;
    private final String model;
    private final Tool tool;

    public GeminiWorkoutProgramChatClient(
        @Value("${app.workout-programs.gemini-api-key:${GEMINI_API_KEY:}}") String apiKey,
        @Value("${app.workout-programs.gemini-model:gemini-3.1-pro-preview}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY (app.workout-programs.gemini-api-key) is required for the program designer");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.tool = Tool.builder().functionDeclarations(List.of(proposeTool())).build();
    }

    @Override
    public StreamResult streamChat(List<Turn> history, String userMessage, String context, Consumer<String> onToken) {
        String systemInstruction = (context == null || context.isBlank())
            ? systemPrompt() : systemPrompt() + "\n\n" + context;
        GenerateContentConfig config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .tools(List.of(tool))
            .build();

        List<Content> contents = new ArrayList<>();
        if (history != null) {
            for (Turn t : history) {
                if (t == null || t.text() == null || t.text().isBlank()) continue;
                contents.add(Content.builder().role(t.userTurn() ? "user" : "model")
                    .parts(List.of(Part.fromText(t.text()))).build());
            }
        }
        contents.add(Content.builder().role("user").parts(List.of(Part.fromText(userMessage))).build());

        StringBuilder text = new StringBuilder();
        WorkoutProgram proposal = null;
        try (ResponseStream<GenerateContentResponse> stream =
                 client.models.generateContentStream(model, contents, config)) {
            for (GenerateContentResponse chunk : stream) {
                String delta = chunk.text();
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    if (onToken != null) onToken.accept(delta);
                }
                if (proposal == null) {
                    List<FunctionCall> calls = chunk.functionCalls();
                    if (calls != null) {
                        for (FunctionCall call : calls) {
                            if (TOOL_NAME.equals(call.name().orElse(null))) {
                                proposal = toProgram(call.args().orElse(Map.of()));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return new StreamResult(text.toString(), proposal);
    }

    // ---- tool args -> transient WorkoutProgram ----

    @SuppressWarnings("unchecked")
    static WorkoutProgram toProgram(Map<String, Object> args) {
        List<ProgramPhase> phases = new ArrayList<>();
        if (args != null && args.get("phases") instanceof List<?> pl) {
            int pi = 0;
            for (Object po : pl) {
                if (!(po instanceof Map<?, ?> pm0)) continue;
                Map<String, Object> pm = (Map<String, Object>) pm0;
                List<WorkoutDay> days = new ArrayList<>();
                if (pm.get("days") instanceof List<?> dl) {
                    int di = 0;
                    for (Object do0 : dl) {
                        if (!(do0 instanceof Map<?, ?> dm0)) continue;
                        Map<String, Object> dm = (Map<String, Object>) dm0;
                        days.add(new WorkoutDay(null, str(dm.get("label")),
                            enumOrNull(str(dm.get("dayOfWeek")), DayOfWeek.class),
                            str(dm.get("locationId")), di++, blocks(dm.get("blocks"))));
                    }
                }
                phases.add(new ProgramPhase(null, str(pm.get("title")), str(pm.get("focus")), pi++,
                    ProgramPhaseStatus.LOCKED, intg(pm.get("weeks")) == null ? 1 : intg(pm.get("weeks")),
                    intg(pm.get("deloadWeekIndex")), null, null, null, days));
            }
        }
        return new WorkoutProgram(null, null, str(args == null ? null : args.get("title")),
            str(args == null ? null : args.get("description")), null, ProgramStatus.DRAFT,
            ProgramSource.AI_GENERATED, null, deriveSchedule(phases), null, phases, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Block> blocks(Object raw) {
        List<Block> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int bi = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m0)) continue;
            Map<String, Object> m = (Map<String, Object>) m0;
            out.add(new Block(null, enumOrNull(str(m.get("type")), BlockType.class), str(m.get("title")),
                bi++, prescriptions(m.get("prescriptions"))));
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
            if (m.get("deloadModifier") instanceof Map<?, ?> dm0) {
                Map<String, Object> dm = (Map<String, Object>) dm0;
                deload = new DeloadModifier(dbl(dm.get("setsMultiplier")), dbl(dm.get("intensityDelta")));
            }
            out.add(new Prescription(str(m.get("exerciseId")), i++, intg(m.get("sets")),
                intg(m.get("repsMin")), intg(m.get("repsMax")), intg(m.get("durationSeconds")),
                intensity, intg(m.get("restSeconds")), str(m.get("tempo")), str(m.get("notes")), deload));
        }
        return out;
    }

    private static ProgramSchedule deriveSchedule(List<ProgramPhase> phases) {
        LinkedHashSet<DayOfWeek> days = new LinkedHashSet<>();
        Map<DayOfWeek, String> locs = new LinkedHashMap<>();
        for (ProgramPhase p : phases) {
            for (WorkoutDay d : p.days()) {
                if (d.dayOfWeek() != null) {
                    days.add(d.dayOfWeek());
                    locs.putIfAbsent(d.dayOfWeek(), d.locationId());
                }
            }
        }
        return new ProgramSchedule(new ArrayList<>(days), locs);
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
        Schema deload = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "setsMultiplier", Schema.builder().type(Type.Known.NUMBER)
                    .description("e.g. 0.5 to halve sets on the deload week").build(),
                "intensityDelta", Schema.builder().type(Type.Known.NUMBER)
                    .description("e.g. -2 to drop 2 RPE on the deload week").build()))
            .build();
        Schema prescription = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "exerciseId", Schema.builder().type(Type.Known.STRING)
                    .description("MUST be an exerciseId from this day's gym allow-list in the context.").build(),
                "sets", Schema.builder().type(Type.Known.INTEGER).build(),
                "repsMin", Schema.builder().type(Type.Known.INTEGER).build(),
                "repsMax", Schema.builder().type(Type.Known.INTEGER).build(),
                "durationSeconds", Schema.builder().type(Type.Known.INTEGER)
                    .description("For timed/cardio/holds instead of reps.").build(),
                "intensity", intensity,
                "restSeconds", Schema.builder().type(Type.Known.INTEGER).build(),
                "tempo", Schema.builder().type(Type.Known.STRING).build(),
                "notes", Schema.builder().type(Type.Known.STRING).build(),
                "deloadModifier", deload))
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
        Schema day = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "label", Schema.builder().type(Type.Known.STRING).description("e.g. Push, Lower A").build(),
                "dayOfWeek", Schema.builder().type(Type.Known.STRING)
                    .enum_("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").build(),
                "locationId", Schema.builder().type(Type.Known.STRING)
                    .description("A gym locationId from the context.").build(),
                "blocks", Schema.builder().type(Type.Known.ARRAY).items(block).build()))
            .required("label", "dayOfWeek", "locationId", "blocks")
            .build();
        Schema phase = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "focus", Schema.builder().type(Type.Known.STRING).build(),
                "weeks", Schema.builder().type(Type.Known.INTEGER).build(),
                "deloadWeekIndex", Schema.builder().type(Type.Known.INTEGER)
                    .description("1-based week of this phase that is a deload; omit if none.").build(),
                "days", Schema.builder().type(Type.Known.ARRAY).items(day).build()))
            .required("title", "weeks", "days")
            .build();
        Schema params = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "description", Schema.builder().type(Type.Known.STRING).build(),
                "phases", Schema.builder().type(Type.Known.ARRAY).items(phase).build()))
            .required("title", "phases")
            .build();
        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Propose a complete periodized workout program for the user to review and edit.")
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
            You are the workout-program designer for Tesseta, a personal health app. \
            You design a periodized training program the user reviews and edits.

            THE MODEL:
            - A Program has a title, description, and an ordered list of Phases.
            - A Phase spans a number of weeks and may flag ONE week as a deload \
            (reduced volume/intensity). Phases run in STRICT SEQUENCE — design earlier \
            Phases to build the foundation later ones depend on. Apply progressive \
            overload across a Phase.
            - A Phase has a weekly microcycle of Workout Days. Each Day maps to one \
            weekday and ONE gym (locationId), and has ordered Blocks.
            - A Block is typed: WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, \
            COOLDOWN, STRETCH. Put each exercise in a block its type suits.
            - A prescription names an exerciseId with sets/reps (or durationSeconds \
            for timed work), optional intensity (RPE or %1RM), rest, tempo, notes, \
            and an optional deloadModifier.

            HARD CONSTRAINTS:
            - You may ONLY prescribe an exerciseId that appears in that day's gym \
            allow-list in the context below. Never invent an exerciseId. If a gym \
            lacks the equipment for a movement, pick an allowed alternative.
            - Match each exercise to a block type in its suitableBlockTypes.
            - Balance weekly volume across muscle groups and across the training days.

            GUIDANCE:
            - Prefer 2 to 5 Phases. Place a deload sensibly (often the last week of \
            an accumulation phase).
            - Gather enough context, then CALL propose_workout_program with the full \
            structure rather than describing it in prose. After the tool call the \
            user sees an editable card.
            """;
    }
}
