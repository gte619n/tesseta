package com.gte619n.healthfitness.api.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatThread;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IMPL-18b: committing on a program-bound chat thread edits that program IN
 * PLACE (same programId, kept ACTIVE) and re-materializes its forward schedule,
 * rather than creating a new draft. A design-new thread still creates a draft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutProgramChatCommitControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;
    @Autowired WorkoutProgramChatRepository chat;

    private static final String TEST_USER = "user-123";
    private static final LocalDate START = LocalDate.of(2026, 6, 1); // a Monday

    @BeforeEach
    void setUp() {
        ((InMemoryWorkoutProgramRepository) programs).clear();
        ((InMemoryScheduledWorkoutRepository) scheduled).clear();
    }

    @Test
    void editCommitUpdatesInPlaceAndRematerializes() throws Exception {
        seedActiveProgram("p1");
        String threadId = seedThreadBoundTo("p1");

        // The user's edit: rename + extend to 5 weeks, same (valid) empty day.
        CreateProgramRequest body = new CreateProgramRequest(
            "Revised plan", "edited", null, null, START, ProgramSource.AI_ASSISTED,
            List.of(phase("Revised Accumulation", 5)), null);

        mvc.perform(post("/api/me/workout-programs/chat/" + threadId + "/commit")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()) // 200, not 201 (in-place edit)
            .andExpect(jsonPath("$.programId").value("p1"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.title").value("Revised plan"));

        // The program kept its identity and the edit landed.
        WorkoutProgram updated = programs.findById(TEST_USER, "p1").orElseThrow();
        assertEquals("Revised plan", updated.title());
        assertEquals(ProgramStatus.ACTIVE, updated.status());
        assertEquals(5, updated.phases().get(0).weeks());
        // Re-materialization ran: forward sessions exist.
        List<ScheduledWorkout> sessions =
            scheduled.findByProgram(TEST_USER, "p1", LocalDate.MIN, LocalDate.MAX);
        assertFalse(sessions.isEmpty(), "expected re-materialized sessions");
    }

    @Test
    void editCommitWithInvalidProgramReturns422() throws Exception {
        seedActiveProgram("p1");
        String threadId = seedThreadBoundTo("p1");

        // Deload week 9 on a 4-week phase is impossible — a hard validator error.
        ProgramPhase bad = new ProgramPhase("ph1", "Accumulation", "Hypertrophy", 0, null,
            4, 9, null, null, null, List.of(day()));
        CreateProgramRequest body = new CreateProgramRequest(
            "Bad", null, null, null, START, ProgramSource.AI_ASSISTED, List.of(bad), null);

        mvc.perform(post("/api/me/workout-programs/chat/" + threadId + "/commit")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.issues").isArray());

        // The program is unchanged (still the seeded title).
        assertEquals("Original", programs.findById(TEST_USER, "p1").orElseThrow().title());
    }

    // --- helpers ---

    private void seedActiveProgram(String programId) {
        ProgramPhase phase = phase("Accumulation", 4);
        WorkoutProgram p = new WorkoutProgram(
            TEST_USER, programId, "Original", null, null, ProgramStatus.ACTIVE,
            ProgramSource.AI_ASSISTED, START, schedule(),
            List.of(phase.phaseId()), List.of(phase), null, null, null);
        programs.save(p);
    }

    private String seedThreadBoundTo(String programId) {
        String threadId = "t-" + programId;
        chat.createThread(new WorkoutProgramChatThread(
            TEST_USER, threadId, "Edit", schedule(), null, null, null, programId));
        return threadId;
    }

    private static ProgramSchedule schedule() {
        return new ProgramSchedule(List.of(DayOfWeek.MON), Map.of(DayOfWeek.MON, "loc1"));
    }

    private static WorkoutDay day() {
        // An empty block is valid (no exercises to check against equipment).
        return new WorkoutDay("d1", "Lower", DayOfWeek.MON, "loc1", 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of())));
    }

    private static ProgramPhase phase(String title, int weeks) {
        return new ProgramPhase("ph1", title, "Hypertrophy", 0, null,
            weeks, null, null, null, null, List.of(day()));
    }
}
