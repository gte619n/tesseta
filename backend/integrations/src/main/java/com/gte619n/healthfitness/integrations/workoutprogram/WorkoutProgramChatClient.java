package com.gte619n.healthfitness.integrations.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import java.util.List;
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
     * Stream the assistant response. {@code context} is the per-request planning
     * context (health snapshot + per-gym executable-exercise allow-lists)
     * appended to the system prompt so the model only prescribes executable
     * exercises.
     */
    StreamResult streamChat(List<Turn> history, String userMessage, String context, Consumer<String> onToken);
}
