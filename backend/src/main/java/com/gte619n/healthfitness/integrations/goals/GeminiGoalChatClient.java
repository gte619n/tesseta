package com.gte619n.healthfitness.integrations.goals;

import com.gte619n.healthfitness.core.goals.chat.RawProposal;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed {@link GoalChatClient}.
 *
 * <p>Designs Goal structures through tool calling: the model is given the
 * {@code propose_goal_structure} function whose schema mirrors the
 * Goal/Phase/Step tool params, plus the metric-key registry embedded
 * verbatim in the system prompt. We stream the response so assistant text
 * tokens reach the UI as they arrive, then surface the raw tool args (if
 * any) for backend validation.
 */
@Component
@ConditionalOnProperty(name = "app.goals.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiGoalChatClient implements GoalChatClient {

    static final String TOOL_NAME = "propose_goal_structure";

    private final Client client;
    private final String model;
    private final Tool tool;

    public GeminiGoalChatClient(
        Client client,
        @Value("${app.goals.gemini-model:gemini-3.5-pro}") String model
    ) {
        this.client = client;
        this.model = model;

        this.tool = Tool.builder()
            .functionDeclarations(List.of(proposeGoalStructureTool()))
            .build();
    }

    @Override
    public StreamResult streamChat(
        List<Turn> history, String userMessage, String healthContext, Consumer<String> onToken) {
        // The static systemPrompt() describes the model + registry; the
        // per-request healthContext appends the user's current values so
        // the model plans against real numbers. Fall back to the static
        // prompt alone when no snapshot was supplied.
        String systemInstruction = (healthContext == null || healthContext.isBlank())
            ? systemPrompt()
            : systemPrompt() + "\n\n" + healthContext;
        GenerateContentConfig config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .tools(List.of(tool))
            .build();

        List<Content> contents = new ArrayList<>();
        if (history != null) {
            for (Turn t : history) {
                if (t == null || t.text() == null || t.text().isBlank()) continue;
                contents.add(Content.builder()
                    .role(t.userTurn() ? "user" : "model")
                    .parts(List.of(Part.fromText(t.text())))
                    .build());
            }
        }
        contents.add(Content.builder()
            .role("user")
            .parts(List.of(Part.fromText(userMessage)))
            .build());

        StringBuilder text = new StringBuilder();
        RawProposal proposal = null;

        try (ResponseStream<GenerateContentResponse> stream =
                 client.models.generateContentStream(model, contents, config)) {
            for (GenerateContentResponse chunk : stream) {
                String delta = chunk.text();
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    if (onToken != null) {
                        onToken.accept(delta);
                    }
                }
                if (proposal == null) {
                    List<FunctionCall> calls = chunk.functionCalls();
                    if (calls != null) {
                        for (FunctionCall call : calls) {
                            if (TOOL_NAME.equals(call.name().orElse(null))) {
                                proposal = toRawProposal(call.args().orElse(Map.of()));
                                break;
                            }
                        }
                    }
                }
            }
        }

        return new StreamResult(text.toString(), proposal);
    }

    // ---- tool-args → RawProposal mapping ----

    @SuppressWarnings("unchecked")
    static RawProposal toRawProposal(Map<String, Object> args) {
        if (args == null) return new RawProposal(null, null, null, null, List.of());
        List<RawProposal.RawPhase> phases = new ArrayList<>();
        Object rawPhases = args.get("phases");
        if (rawPhases instanceof List<?> list) {
            for (Object po : list) {
                if (!(po instanceof Map<?, ?> pm)) continue;
                Map<String, Object> phase = (Map<String, Object>) pm;
                List<RawProposal.RawStep> steps = new ArrayList<>();
                Object rawSteps = phase.get("steps");
                if (rawSteps instanceof List<?> sList) {
                    for (Object so : sList) {
                        if (!(so instanceof Map<?, ?> sm)) continue;
                        Map<String, Object> step = (Map<String, Object>) sm;
                        RawProposal.RawMetric metric = null;
                        Object rawMetric = step.get("metric");
                        if (rawMetric instanceof Map<?, ?> mm) {
                            Map<String, Object> m = (Map<String, Object>) mm;
                            metric = new RawProposal.RawMetric(
                                str(m.get("metricKey")),
                                str(m.get("comparator")),
                                dbl(m.get("targetValue")),
                                intg(m.get("windowDays")),
                                str(m.get("countFrom"))
                            );
                        }
                        steps.add(new RawProposal.RawStep(
                            str(step.get("title")),
                            str(step.get("kind")),
                            metric
                        ));
                    }
                }
                phases.add(new RawProposal.RawPhase(
                    str(phase.get("title")),
                    str(phase.get("description")),
                    str(phase.get("targetStartDate")),
                    str(phase.get("targetEndDate")),
                    steps
                ));
            }
        }
        return new RawProposal(
            str(args.get("title")),
            str(args.get("description")),
            str(args.get("domain")),
            str(args.get("targetDate")),
            phases
        );
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intg(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return (int) Math.round(Double.parseDouble(o.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- tool schema ----

    private static FunctionDeclaration proposeGoalStructureTool() {
        Schema metric = Schema.builder()
            .type(Type.Known.OBJECT)
            .description("Metric binding. Omit entirely for MANUAL steps.")
            .properties(orderedMap(
                "metricKey", Schema.builder().type(Type.Known.STRING)
                    .description("One of the registry keys, e.g. blood.ldl").build(),
                "comparator", Schema.builder().type(Type.Known.STRING)
                    .enum_("LT", "LTE", "GT", "GTE", "EQ").build(),
                "targetValue", Schema.builder().type(Type.Known.NUMBER).build(),
                "windowDays", Schema.builder().type(Type.Known.INTEGER)
                    .description("Required for SUSTAINED only; number of consecutive days.").build(),
                "countFrom", Schema.builder().type(Type.Known.STRING)
                    .description("COUNT only; ISO-8601 timestamp the tally starts from. Defaults to now.").build()
            ))
            .required("metricKey", "comparator", "targetValue")
            .build();

        Schema step = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "kind", Schema.builder().type(Type.Known.STRING)
                    .enum_("MANUAL", "THRESHOLD", "SUSTAINED", "COUNT").build(),
                "metric", metric
            ))
            .required("title", "kind")
            .build();

        Schema phase = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "description", Schema.builder().type(Type.Known.STRING).build(),
                "targetStartDate", Schema.builder().type(Type.Known.STRING)
                    .description("ISO date YYYY-MM-DD").build(),
                "targetEndDate", Schema.builder().type(Type.Known.STRING)
                    .description("ISO date YYYY-MM-DD").build(),
                "steps", Schema.builder().type(Type.Known.ARRAY).items(step).build()
            ))
            .required("title", "steps")
            .build();

        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "title", Schema.builder().type(Type.Known.STRING).build(),
                "description", Schema.builder().type(Type.Known.STRING).build(),
                "domain", Schema.builder().type(Type.Known.STRING)
                    .enum_("CARDIOVASCULAR", "BODY_COMPOSITION", "STRENGTH",
                        "METABOLIC", "SLEEP", "LONGEVITY", "OTHER").build(),
                "targetDate", Schema.builder().type(Type.Known.STRING)
                    .description("ISO date YYYY-MM-DD; must be in the future").build(),
                "phases", Schema.builder().type(Type.Known.ARRAY).items(phase).build()
            ))
            .required("title", "domain", "phases")
            .build();

        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Propose a complete Goal with Phases and Steps for the "
                + "user to review and edit.")
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

    // ---- system prompt ----

    static String systemPrompt() {
        return """
            You are the Goals planner for Tesseta, a personal health app. You help \
            the user design a health Goal as a structured roadmap of Phases and Steps.

            THE MODEL:
            - A Goal has a title, description, domain, and a future target date.
            - A Goal contains an ordered list of Phases. Phases run in STRICT \
            SEQUENCE: exactly one Phase is active at a time, and Phase N+1 cannot \
            begin until Phase N is complete. Design earlier Phases so they build the \
            foundation later Phases depend on. Phase target date ranges must be \
            ordered and must NOT overlap.
            - A Phase contains an ordered list of Steps. A Step is one of four kinds:
                MANUAL    - checked by hand; NO metric.
                THRESHOLD - done when a metric crosses a target (any comparator).
                SUSTAINED - done when a metric holds a condition for windowDays \
            consecutive days (any comparator; windowDays REQUIRED).
                COUNT     - done when a tallied metric reaches a target. Use only \
            the GTE, GT, or EQ comparator for COUNT — never LT or LTE.

            METRIC KEY REGISTRY (you may ONLY bind a Step to a key from this list; \
            anything else must be a MANUAL Step — do not invent metrics):
            """
            + registryBlock()
            + """

            GUIDANCE:
            - Prefer 3 to 6 Phases, and 2 to 6 Steps per Phase. Do not over-granularize.
            - Never propose a target date in the past, and never propose a comparator \
            that does not fit the metric or the Step kind.
            - Gather enough context from the user, then CALL the propose_goal_structure \
            tool with the full structure rather than describing the plan in prose. \
            After the tool call the user will see an editable card.
            """;
    }

    private static String registryBlock() {
        StringBuilder sb = new StringBuilder();
        for (MetricKey k : MetricKey.values()) {
            sb.append("  ").append(k.key()).append('\n');
        }
        return sb.toString();
    }
}
