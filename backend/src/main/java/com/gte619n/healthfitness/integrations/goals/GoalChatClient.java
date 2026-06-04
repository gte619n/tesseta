package com.gte619n.healthfitness.integrations.goals;

import com.gte619n.healthfitness.core.goals.chat.RawProposal;
import java.util.List;
import java.util.function.Consumer;

/**
 * The seam between the Goals chat controller and Gemini.
 *
 * <p>Defined as an interface so the controller and its tests can run
 * against a fake without a live API key (the real implementation,
 * {@link GeminiGoalChatClient}, requires {@code GEMINI_API_KEY} and is
 * gated by {@code app.goals.enabled}).
 */
public interface GoalChatClient {

    /**
     * One turn of conversation history fed back to the model.
     *
     * @param userTurn true if this turn was authored by the user, false
     *                 if it was the assistant.
     * @param text     the message text.
     */
    record Turn(boolean userTurn, String text) {}

    /**
     * The outcome of one streamed assistant turn.
     *
     * @param assistantText the full assistant text (also delivered
     *                       incrementally via the token callback).
     * @param proposal       the raw {@code propose_goal_structure} tool
     *                       args, or null if the model did not call the
     *                       tool this turn.
     */
    record StreamResult(String assistantText, RawProposal proposal) {}

    /**
     * Send {@code userMessage} with the given prior {@code history} and
     * stream the assistant's response.
     *
     * <p>Assistant text deltas are delivered to {@code onToken} as they
     * arrive. When the model emits a {@code propose_goal_structure} tool
     * call, its raw args are surfaced on the returned
     * {@link StreamResult#proposal()} (the caller validates them). The
     * method blocks until the stream is exhausted and returns the
     * aggregate result.
     *
     * @param healthContext a plain-text snapshot of the user's current
     *                       health (medications, body composition, blood
     *                       panel, vitals, and the current value of every
     *                       bindable registry metric) appended to the
     *                       static system instruction for this request so
     *                       the model plans against real numbers. May be
     *                       null or blank, in which case the model falls
     *                       back to the static system prompt alone.
     */
    StreamResult streamChat(
        List<Turn> history, String userMessage, String healthContext, Consumer<String> onToken);
}
