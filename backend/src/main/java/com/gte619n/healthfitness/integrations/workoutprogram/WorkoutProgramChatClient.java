package com.gte619n.healthfitness.integrations.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Seam between the workout-program chat controller and Gemini. Defined as an
 * interface so the controller and its tests can run against a fake without a
 * live API key (the real {@link GeminiWorkoutProgramChatClient} requires
 * {@code GEMINI_API_KEY} and is gated by {@code app.workout-programs.enabled}).
 */
public interface WorkoutProgramChatClient {

    record Turn(boolean userTurn, String text) {}

    /**
     * @param assistantText the full assistant text (also streamed via onToken).
     * @param proposal      a transient {@link WorkoutProgram} built from the
     *                      {@code propose_workout_program} tool args (no userId,
     *                      no ids, status DRAFT), or null if the model did not
     *                      call the tool.
     */
    record StreamResult(String assistantText, WorkoutProgram proposal) {}

    /**
     * Resolves a mid-conversation read-only data tool the model calls
     * (IMPL-18 {@code get_exercise_history}, {@code get_lab_history}). Returns the
     * tool result as a map fed back into the turn as a function response. The
     * controller binds this to the current user's digest + TRT services.
     */
    @FunctionalInterface
    interface ToolResolver {
        Map<String, Object> resolve(String toolName, Map<String, Object> args);
    }

    /**
     * Stream the assistant response. {@code context} is the per-request planning
     * context (health snapshot + per-gym executable-exercise allow-lists + the
     * history digest + training-science scaffold + TRT context) appended to the
     * system prompt. {@code tools} resolves the model's read-only data-tool calls
     * (exercise/lab history) mid-stream; the model may make several rounds before
     * it calls {@code propose_workout_program}.
     */
    StreamResult streamChat(List<Turn> history, String userMessage, String context,
                            Consumer<String> onToken, ToolResolver tools);
}
