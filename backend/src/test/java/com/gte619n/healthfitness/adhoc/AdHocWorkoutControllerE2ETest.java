package com.gte619n.healthfitness.adhoc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutGenerator;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * IMPL-ADHOC-01 — full HTTP-layer E2E of the ad-hoc workout library through the
 * real controllers + services + in-memory repos (the DoD gate the service tests
 * proxied). Drives generate → save → run(complete) → verify history inclusion
 * (last-sets/e1RM + runCount) → archive/restore, with a stub generator (the live
 * Gemini one is gated off in tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, AdHocWorkoutControllerE2ETest.StubGeneratorConfig.class})
class AdHocWorkoutControllerE2ETest {

    private static final String USER = "user-adhoc-e2e";
    private static final String EX_ID = "ex_test_pushup";

    @Autowired MockMvc mvc;
    @Autowired ExerciseRepository exercises;

    /** A deterministic generator so /generate works without a live API key. */
    @TestConfiguration
    static class StubGeneratorConfig {
        @Bean
        AdHocWorkoutGenerator stubGenerator() {
            return (userId, prompt, minutes, equipmentIds) -> {
                Prescription rx = new Prescription(EX_ID, 0, 3, 8, 12, null, null, 60, null, null, null, null);
                WorkoutDay day = new WorkoutDay(null, "Hotel Full Body", null, null, 0,
                    List.of(new Block(null, BlockType.MAIN, "Main", 0, List.of(rx))));
                return new AdHocWorkoutGenerator.Generated(
                    "Hotel Full Body", "Bodyweight session for the road", List.of("Travel", "Full-body"), day);
            };
        }
    }

    @BeforeEach
    void seedExercise() {
        // A published bodyweight exercise (no equipment requirements) so the
        // constraint validator passes with an empty equipment set and the name
        // resolves in the response assembler.
        exercises.save(new Exercise(
            EX_ID, "Push-up", "push-up", List.of(), MovementPattern.PUSH_HORIZONTAL, List.of("chest"), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of()));
    }

    @Test
    void fullJourney_generateSaveRunHistoryArchiveRestore() throws Exception {
        // 1) GENERATE (preview) — bodyweight preset → clean constraint report.
        MvcResult gen = mvc.perform(post("/api/me/adhoc-workouts/generate")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"30 minute hotel gym full body","presetId":"bodyweight","targetDurationMinutes":30}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draft.title").value("Hotel Full Body"))
            .andExpect(jsonPath("$.draft.estimatedDurationSeconds", greaterThan(0)))
            .andExpect(jsonPath("$.draft.day.blocks[0].prescriptions[0].exerciseId").value(EX_ID))
            .andExpect(jsonPath("$.constraintReport").isEmpty())
            .andReturn();

        // 2) SAVE the draft → 201 + minted adhocId.
        MvcResult created = mvc.perform(post("/api/me/adhoc-workouts")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Hotel Full Body","summary":"Bodyweight session",
                     "source":"AI_GENERATED","prompt":"30 min hotel gym",
                     "targetDurationMinutes":30,"tags":["Travel"],
                     "day":{"label":"Hotel Full Body","blocks":[
                        {"type":"MAIN","title":"Main","prescriptions":[
                          {"exerciseId":"%s","sets":3,"repsMin":8,"repsMax":12,"restSeconds":60}
                        ]}]}}
                    """.formatted(EX_ID)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.adhocId").exists())
            .andExpect(jsonPath("$.estimatedDurationSeconds", greaterThan(0)))
            .andReturn();
        String adhocId = JsonPath.read(created.getResponse().getContentAsString(), "$.adhocId");
        String blockId = JsonPath.read(created.getResponse().getContentAsString(),
            "$.day.blocks[0].blockId");

        // 3) It appears in the library list.
        mvc.perform(get("/api/me/adhoc-workouts").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.adhocId == '" + adhocId + "')]").exists())
            .andExpect(jsonPath("$[?(@.adhocId == '" + adhocId + "')].tags[0]").value("Travel"));

        // 4) RUN it — terminal completion upsert with a logged set (client-minted id).
        mvc.perform(put("/api/me/adhoc-workouts/" + adhocId + "/sessions/aws_e2e00000001")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"COMPLETED","date":"2026-06-03",
                     "completedAt":"2026-06-03T18:30:00Z","durationSeconds":1800,
                     "logged":[{"blockId":"%s","orderIndex":0,
                        "sets":[{"weightLbs":0,"reps":20,"rpe":8,"restSeconds":60,"completedAt":"2026-06-03T18:29:00Z"}]}],
                     "feeling":4}
                    """.formatted(blockId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 5) The run reached history/e1RM: runCount incremented + last-sets prefill
        //    returns the just-logged set for the exercise.
        mvc.perform(get("/api/me/adhoc-workouts/" + adhocId).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runCount").value(1))
            .andExpect(jsonPath("$.lastPerformedAt").exists());

        mvc.perform(post("/api/me/adhoc-workouts/" + adhocId + "/last-sets")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exerciseIds\":[\"" + EX_ID + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$." + EX_ID).exists())
            .andExpect(jsonPath("$." + EX_ID + "[0].reps").value(20));

        // 6) REPLAY the identical run PUT (outbox retry) — runCount stays 1.
        mvc.perform(put("/api/me/adhoc-workouts/" + adhocId + "/sessions/aws_e2e00000001")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"COMPLETED","date":"2026-06-03",
                     "completedAt":"2026-06-03T18:30:00Z","durationSeconds":1800,
                     "logged":[{"blockId":"%s","orderIndex":0,
                        "sets":[{"weightLbs":0,"reps":20,"rpe":8,"restSeconds":60,"completedAt":"2026-06-03T18:29:00Z"}]}],
                     "feeling":4}
                    """.formatted(blockId)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/me/adhoc-workouts/" + adhocId).header("X-Dev-User", USER))
            .andExpect(jsonPath("$.runCount").value(1));

        // 7) ARCHIVE → gone from the active list, present with includeArchived,
        //    then RESTORE brings it back (D12).
        mvc.perform(delete("/api/me/adhoc-workouts/" + adhocId).header("X-Dev-User", USER))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/adhoc-workouts").header("X-Dev-User", USER))
            .andExpect(jsonPath("$[?(@.adhocId == '" + adhocId + "')]").doesNotExist());
        mvc.perform(get("/api/me/adhoc-workouts?includeArchived=true").header("X-Dev-User", USER))
            .andExpect(jsonPath("$[?(@.adhocId == '" + adhocId + "')]").exists());
        mvc.perform(post("/api/me/adhoc-workouts/" + adhocId + "/restore").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.adhocId").value(adhocId));
        mvc.perform(get("/api/me/adhoc-workouts").header("X-Dev-User", USER))
            .andExpect(jsonPath("$[?(@.adhocId == '" + adhocId + "')]").exists());
    }

    @Test
    void generateReturns503WhenGeneratorAbsentIsNotTheCaseHere_butValidatesPrompt() throws Exception {
        // A blank prompt is a 400 before the generator is consulted.
        mvc.perform(post("/api/me/adhoc-workouts/generate")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"presetId\":\"bodyweight\"}"))
            .andExpect(status().isBadRequest());
    }
}
