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
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
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
 * using the Pro model (ADR-0013). The per-request context (health snapshot +
 * per-gym executable-exercise allow-lists) is appended to the system prompt so
 * the model only prescribes executable exercises.
 */
@Component
@ConditionalOnProperty(name = "app.workout-programs.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiWorkoutProgramChatClient implements WorkoutProgramChatClient {

    static final String TOOL_NAME = "propose_workout_program";
    static final String TOOL_EXERCISE_HISTORY = "get_exercise_history";
    static final String TOOL_LAB_HISTORY = "get_lab_history";
    /** Cap the data-tool round-trips so a misbehaving model can't loop forever. */
    private static final int MAX_TOOL_ROUNDS = 6;

    private final Client client;
    private final String model;
    private final Tool tool;

    public GeminiWorkoutProgramChatClient(
        Client client,
        @Value("${app.workout-programs.gemini-model:gemini-3.1-pro-preview}") String model
    ) {
        this.client = client;
        this.model = model;
        this.tool = Tool.builder()
            .functionDeclarations(List.of(proposeTool(), exerciseHistoryTool(), labHistoryTool()))
            .build();
    }

    @Override
    public StreamResult streamChat(List<Turn> history, String userMessage, String context,
                                   Consumer<String> onToken, ToolResolver tools) {
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

        // Agentic loop: the model may call read-only data tools (exercise/lab
        // history) and use the results before it calls propose_workout_program.
        // Each round streams text to the user; on a data-tool call we feed the
        // result back as a function response and re-invoke. The propose tool is
        // terminal (its args become the proposal — no function response needed).
        for (int round = 0; round < MAX_TOOL_ROUNDS && proposal == null; round++) {
            List<FunctionCall> dataCalls = new ArrayList<>();
            try (ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream(model, contents, config)) {
                for (GenerateContentResponse chunk : stream) {
                    String delta = chunk.text();
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        if (onToken != null) onToken.accept(delta);
                    }
                    List<FunctionCall> calls = chunk.functionCalls();
                    if (calls == null) continue;
                    for (FunctionCall call : calls) {
                        String name = call.name().orElse(null);
                        if (TOOL_NAME.equals(name)) {
                            if (proposal == null) proposal = toProgram(call.args().orElse(Map.of()));
                        } else if (TOOL_EXERCISE_HISTORY.equals(name) || TOOL_LAB_HISTORY.equals(name)) {
                            dataCalls.add(call);
                        }
                    }
                }
            }
            if (proposal != null || dataCalls.isEmpty()) break;
            // Append the model's tool-call turn + our function responses, then loop.
            for (FunctionCall call : dataCalls) {
                String name = call.name().orElse("");
                Map<String, Object> args = call.args().orElse(Map.of());
                Map<String, Object> result;
                try {
                    result = tools == null ? Map.of("error", "no resolver") : tools.resolve(name, args);
                } catch (RuntimeException e) {
                    result = Map.of("error", e.getMessage() == null ? "tool failed" : e.getMessage());
                }
                if (result == null) result = Map.of();
                contents.add(Content.builder().role("model")
                    .parts(List.of(Part.fromFunctionCall(name, args))).build());
                contents.add(Content.builder().role("user")
                    .parts(List.of(Part.fromFunctionResponse(name, result))).build());
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
                    intg(pm.get("deloadWeekIndex")), null, null, null, days,
                    nutrition(pm.get("nutritionGuidance"))));
            }
        }
        return new WorkoutProgram(null, null, str(args == null ? null : args.get("title")),
            str(args == null ? null : args.get("description")), null, ProgramStatus.DRAFT,
            ProgramSource.AI_GENERATED, null, deriveSchedule(phases), null, phases, null, null, null,
            nutrition(args == null ? null : args.get("nutritionGuidance")));
    }

    @SuppressWarnings("unchecked")
    private static NutritionGuidance nutrition(Object raw) {
        if (!(raw instanceof Map<?, ?> m0)) return null;
        Map<String, Object> m = (Map<String, Object>) m0;
        NutritionGuidance g = new NutritionGuidance(intg(m.get("kcal")), intg(m.get("proteinG")),
            intg(m.get("carbsG")), intg(m.get("fatG")), str(m.get("note")));
        return g.isEmpty() ? null : g;
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
                intensity, intg(m.get("restSeconds")), str(m.get("tempo")), str(m.get("notes")), deload, null,
                dbl(m.get("targetWeightLbs")), str(m.get("loadBasis"))));
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
                "targetWeightLbs", Schema.builder().type(Type.Known.NUMBER)
                    .description("Concrete prescribed working load in lb when the user's logged/imported "
                        + "history supports it (e1RM × target-%, layoff/ease-in discounted). Omit when no "
                        + "history exists — use intensity (RPE/%1RM) instead. Never above the user's e1RM.").build(),
                "loadBasis", Schema.builder().type(Type.Known.STRING)
                    .description("One short line explaining the targetWeightLbs: the e1RM, last-done set, "
                        + "and any ease-in/staleness discount, e.g. 'e1RM 205 from 185x5 ~8wk ago, -10% ease-in'.").build(),
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
        Schema nutrition = Schema.builder().type(Type.Known.OBJECT)
            .description("Non-binding calorie/macro targets for this phase (display-only; the user logs "
                + "food elsewhere). e.g. accumulation = slight surplus, deload = maintenance.")
            .properties(orderedMap(
                "kcal", Schema.builder().type(Type.Known.INTEGER).description("Daily calorie target.").build(),
                "proteinG", Schema.builder().type(Type.Known.INTEGER).description("Daily protein grams.").build(),
                "carbsG", Schema.builder().type(Type.Known.INTEGER).description("Daily carb grams.").build(),
                "fatG", Schema.builder().type(Type.Known.INTEGER).description("Daily fat grams.").build(),
                "note", Schema.builder().type(Type.Known.STRING)
                    .description("One-line rationale, e.g. 'slight surplus to support the volume ramp'.").build()))
            .build();
        Schema phase = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "focus", Schema.builder().type(Type.Known.STRING).build(),
                "weeks", Schema.builder().type(Type.Known.INTEGER).build(),
                "deloadWeekIndex", Schema.builder().type(Type.Known.INTEGER)
                    .description("1-based week of this phase that is a deload; omit if none.").build(),
                "nutritionGuidance", nutrition,
                "days", Schema.builder().type(Type.Known.ARRAY).items(day).build()))
            .required("title", "weeks", "days")
            .build();
        Schema params = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "description", Schema.builder().type(Type.Known.STRING).build(),
                "nutritionGuidance", nutrition,
                "phases", Schema.builder().type(Type.Known.ARRAY).items(phase).build()))
            .required("title", "phases")
            .build();
        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Propose a complete periodized workout program for the user to review and edit.")
            .parameters(params)
            .build();
    }

    private static FunctionDeclaration exerciseHistoryTool() {
        Schema params = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "exerciseId", Schema.builder().type(Type.Known.STRING)
                    .description("An exerciseId from the allow-list to pull logged history for.").build(),
                "exerciseName", Schema.builder().type(Type.Known.STRING)
                    .description("Alternative to exerciseId: the exercise name to look up.").build(),
                "limit", Schema.builder().type(Type.Known.INTEGER)
                    .description("Max recent sets to return (default 5).").build()))
            .build();
        return FunctionDeclaration.builder()
            .name(TOOL_EXERCISE_HISTORY)
            .description("Look up the user's recent LOGGED sets for one exercise (date, weight, reps, RPE) "
                + "to ground a concrete prescribed load. The per-turn digest already summarizes the top "
                + "exercises; call this to drill into a specific lift in more detail.")
            .parameters(params)
            .build();
    }

    private static FunctionDeclaration labHistoryTool() {
        Schema params = Schema.builder().type(Type.Known.OBJECT)
            .properties(orderedMap(
                "markerName", Schema.builder().type(Type.Known.STRING)
                    .description("A blood marker, e.g. 'testosterone', 'estradiol', 'hematocrit', 'PSA'.").build()))
            .required("markerName")
            .build();
        return FunctionDeclaration.builder()
            .name(TOOL_LAB_HISTORY)
            .description("Look up the history of one blood marker (value, date, reference range) to ground "
                + "TRT decision-support in the user's actual labs (ADR-0015).")
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
            You are a history-grounded, science-informed strength coach. You design \
            (and iteratively refine) a periodized program the user reviews and edits, \
            grounded in what they have actually lifted and in their health data.

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
            for timed work), optional intensity (RPE or %1RM), rest, tempo, notes, an \
            optional deloadModifier, and — when history supports it — a concrete \
            targetWeightLbs plus a one-line loadBasis explaining how you got it.

            HARD CONSTRAINTS:
            - You may ONLY prescribe an exerciseId that appears in that day's gym \
            allow-list in the context below. Never invent an exerciseId. If a gym \
            lacks the equipment for a movement, pick an allowed alternative.
            - Match each exercise to a block type in its suitableBlockTypes.
            - NEVER prescribe a targetWeightLbs above the user's estimated 1RM (e1RM) \
            for that lift. When unsure of the load, use intensity (RPE/%1RM) instead.

            HISTORY-GROUNDED LOAD SELECTION:
            - The EXERCISE PERFORMANCE DIGEST in the context summarizes what the user \
            actually lifted (last-performed date → staleness, best recent set, e1RM, \
            typical RPE, rep ranges, recent volume trend). Use it.
            - When you know an exercise's e1RM, prescribe a concrete targetWeightLbs = \
            e1RM × the target intensity for the rep/RPE goal, then DISCOUNT for layoff \
            and ease-in: <2 weeks stale none, 2–6w about −10%, 6–12w about −20%, >12w \
            about −30% and start sub-maximal. An explicit "ease in" intent compounds \
            the discount and caps the early ramp. Set loadBasis to a short rationale, \
            e.g. "e1RM 205 from 185x5 ~8wk ago, -10% ease-in".
            - For lifts with NO logged history, omit targetWeightLbs and prescribe by \
            RPE or %1RM. Imported weight-only rows are a low-confidence floor — let \
            them inform, never anchor.
            - Call get_exercise_history to drill into a specific lift, and visibly use \
            the result (e.g. "your last bench top set was 185x5 ~8 weeks ago, so we \
            start at ..."). Call get_lab_history to pull a specific marker.

            TRAINING SCIENCE (the TRAINING SCIENCE SCAFFOLD in context has the numbers):
            - Keep planned weekly hard sets per primary muscle within MEV→MAV in \
            accumulation; do not exceed MRV. Respect sensible per-muscle weekly \
            frequency. Place a deload when a phase runs long. Limit week-over-week \
            volume/intensity increases to a sane ramp (tighter when easing in or after \
            a layoff). The validator will warn if you breach these.

            TRT / RECOVERY (ADR-0015 — grounded decision-support is ENABLED):
            - Use the user's TRT/recovery status to shape TRAINING: early phases assume \
            blunted recovery and work capacity, ramping volume as therapy takes effect \
            across the program (a phased recovery ramp that compounds the ease-in).
            - You MAY give specific, grounded TRT dosing/protocol/bloodwork/side-effect \
            decision-support, reasoned from the user's ACTUAL labs (in context) and the \
            cited TRT KNOWLEDGE BASE. Every clinical claim must cite its KB source. \
            Give concrete numbers and titration logic; do not hide behind blanket \
            "see your doctor" deferrals, and never assert un-grounded specifics. \
            ALWAYS surface any danger flag in the context (e.g. hematocrit > 54%) and \
            urge prompt clinician contact, regardless of what was asked.

            NUTRITION (display-only — the user logs food elsewhere):
            - Attach per-phase nutritionGuidance (kcal + protein/carb/fat grams + a \
            one-line note) suited to the phase goal (e.g. accumulation = slight \
            surplus, deload = maintenance). Optionally set a program-level \
            nutritionGuidance as a fallback summary. Do not invent food logs.

            GUIDANCE:
            - Prefer 2 to 5 Phases. Place a deload sensibly (often the last week of \
            an accumulation phase).
            - Gather enough context (and call the data tools when useful), then CALL \
            propose_workout_program with the full structure rather than describing it \
            in prose. After the tool call the user sees an editable card.
            """;
    }
}
