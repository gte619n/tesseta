package com.gte619n.healthfitness.uat;

import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.integrations.goals.GoalChatClient;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deterministic AI stubs for UAT / local end-to-end runs (see
 * infra/scripts/uat.sh), so the full server boots with NO {@code GEMINI_API_KEY}
 * and the Goals / Workout-program chat controllers still wire.
 *
 * <p>The live {@code GeminiGoalChatClient} / {@code GeminiWorkoutProgramChatClient}
 * are gated by {@code app.goals.enabled} / {@code app.workout-programs.enabled};
 * UAT turns those off (no API key), which would otherwise leave their controllers
 * with an unsatisfied dependency and fail startup. These stubs fill the seam with
 * a fixed, streamed reply (no proposal) — mirroring the fakes the unit tests
 * install via {@code TestPersistenceConfig}.
 *
 * <p><b>Production-safe:</b> the whole config is gated on
 * {@code app.uat.stubs-enabled} (default false — set true only by uat.sh), and
 * each bean additionally backs off via {@link ConditionalOnMissingBean}, so it
 * can never shadow the real client or the unit-test fakes.
 */
@Configuration
@ConditionalOnProperty(name = "app.uat.stubs-enabled", havingValue = "true")
public class UatStubConfig {

    @Bean
    @ConditionalOnMissingBean(GoalChatClient.class)
    GoalChatClient uatGoalChatClient() {
        return (history, userMessage, healthContext, onToken) -> {
            String reply = "UAT stub: goal assistant reply.";
            for (String word : reply.split(" ")) {
                onToken.accept(word + " ");
            }
            return new GoalChatClient.StreamResult(reply, null);
        };
    }

    @Bean
    @ConditionalOnMissingBean(WorkoutProgramChatClient.class)
    WorkoutProgramChatClient uatWorkoutProgramChatClient() {
        return (history, userMessage, context, onToken) -> {
            String reply = "UAT stub: workout program assistant reply.";
            for (String word : reply.split(" ")) {
                onToken.accept(word + " ");
            }
            return new WorkoutProgramChatClient.StreamResult(reply, null);
        };
    }

    // WorkoutHistoryImporter (a core @Service) hard-depends on this port; the
    // live Gemini enricher is gated off in UAT (no API key), so supply the
    // interface's own neutral default rather than calling the model.
    @Bean
    @ConditionalOnMissingBean(ExerciseMetadataEnricher.class)
    ExerciseMetadataEnricher uatExerciseMetadataEnricher() {
        return (exerciseName, allowedEquipmentNames) -> ExerciseMetadataEnricher.empty(exerciseName);
    }
}
