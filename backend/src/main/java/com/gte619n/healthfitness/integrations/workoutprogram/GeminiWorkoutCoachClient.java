package com.gte619n.healthfitness.integrations.workoutprogram;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed post-workout coach (IMPL-COACH). Generates a short, encouraging
 * recap with {@code gemini-3.5-flash} (the approved general-work text model,
 * same as the frame planner). Mirrors {@link GeminiExerciseFramePlanner}: it
 * shares the single google-genai {@link Client} bean (present only when
 * {@code GEMINI_API_KEY} is set) injected as {@link Optional}, so the bean wires
 * in every context — including tests, where the client is absent.
 *
 * <p>The recap is strictly best-effort: a disabled flag, a missing key, or any
 * model/parse failure returns {@code null} rather than throwing, because a recap
 * must never block session completion.
 */
@Component
public class GeminiWorkoutCoachClient implements WorkoutCoachClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiWorkoutCoachClient.class);

    private static final String SYSTEM_PROMPT = """
        You are an upbeat, knowledgeable strength coach writing a short recap the
        athlete reads the moment they finish a workout. Speak directly to them
        ("you"). Be specific about what they just did — call out the standout
        lift (heaviest load or hardest effort) and the total work — and close
        with one concrete, encouraging note for next time.

        Constraints:
        - 2 to 3 sentences, plain text, no markdown, no emoji, no bullet lists.
        - Warm and motivating but never fake; ground every claim in the numbers
          given. Do not invent exercises, weights, or reps.
        - If little was logged, keep it brief and still encouraging.
        """;

    private final Optional<Client> client;
    private final String model;
    private final boolean enabled;

    public GeminiWorkoutCoachClient(
        Optional<Client> client,
        @Value("${app.workout-coach.gemini-model:gemini-3.5-flash}") String model,
        @Value("${app.workout-coach.enabled:true}") boolean enabled
    ) {
        this.client = client;
        this.model = model;
        this.enabled = enabled;
    }

    @Override
    public String generateRecap(SessionRecap session) {
        if (!enabled || client.isEmpty() || session == null) {
            return null;
        }
        try {
            Content content = Content.fromParts(
                Part.fromText(SYSTEM_PROMPT),
                Part.fromText(describe(session)));
            GenerateContentResponse response =
                client.get().models.generateContent(model, content, GenerateContentConfig.builder().build());
            String text = response.text();
            if (text == null || text.isBlank()) {
                return null;
            }
            return text.trim();
        } catch (Exception e) {
            // Best-effort: a recap failure must not surface to the client.
            log.warn("Workout coach recap generation failed: {}", e.toString());
            return null;
        }
    }

    /** Render the session facts as a compact, model-readable brief. */
    private static String describe(SessionRecap s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(s.dayLabel() == null ? "workout" : s.dayLabel()).append('\n');
        sb.append("Duration: ").append(Math.max(0, s.durationSeconds()) / 60).append(" min\n");
        sb.append("Total volume: ").append(Math.round(s.totalVolumeLbs())).append(" lb\n");
        sb.append("Sets completed: ").append(s.setsCompleted());
        if (s.setsPrescribed() > 0) {
            sb.append(" of ").append(s.setsPrescribed()).append(" prescribed");
        }
        sb.append('\n');
        List<ExerciseLine> exercises = s.exercises() == null ? List.of() : s.exercises();
        if (!exercises.isEmpty()) {
            sb.append("Exercises:\n");
            for (ExerciseLine e : exercises) {
                sb.append("- ").append(e.name()).append(": ").append(e.setsLogged()).append(" sets");
                if (e.topWeightLbs() != null) {
                    sb.append(", top ").append(formatWeight(e.topWeightLbs()));
                    if (e.topReps() != null) {
                        sb.append(" x ").append(e.topReps());
                    }
                }
                if (e.avgRpe() != null) {
                    sb.append(String.format(Locale.US, ", avg RPE %.1f", e.avgRpe()));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatWeight(double lbs) {
        if (lbs == Math.rint(lbs)) {
            return ((long) lbs) + " lb";
        }
        return String.format(Locale.US, "%.1f lb", lbs);
    }
}
