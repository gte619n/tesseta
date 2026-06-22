package com.gte619n.healthfitness.integrations.workoutprogram;

import java.util.List;

/**
 * Seam between the workout session feature and Gemini for the post-workout AI
 * "coach" recap (IMPL-COACH). Defined as an interface — like
 * {@link WorkoutProgramChatClient} — so the completion path can run against a
 * stub without a live API key. The real {@link GeminiWorkoutCoachClient} shares
 * the single google-genai client bean (present only when {@code GEMINI_API_KEY}
 * is set) and returns {@code null} when AI is unavailable, so a recap is always
 * best-effort and never blocks completion.
 */
public interface WorkoutCoachClient {

    /** One performed exercise distilled for the recap prompt. */
    record ExerciseLine(
        String name,
        int setsLogged,
        Double topWeightLbs,   // heaviest logged set, nullable (bodyweight/unset)
        Integer topReps,       // reps at the top set, nullable
        Double avgRpe          // mean RPE across logged sets, nullable
    ) {}

    /** The completed session distilled into the facts the coach speaks to. */
    record SessionRecap(
        String dayLabel,
        int durationSeconds,
        double totalVolumeLbs,
        int setsCompleted,
        int setsPrescribed,
        List<ExerciseLine> exercises
    ) {}

    /**
     * A short, encouraging 2–3 sentence recap of the session, or {@code null}
     * when the coach is disabled, has no API key, or the model call fails.
     */
    String generateRecap(SessionRecap session);
}
