package com.gte619n.healthfitness.core.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * recordFrame captures the exact generation prompt + grounding URLs on the frame,
 * and frame edits (select / remove candidate) preserve them.
 */
class ExerciseServiceRecordFrameTest {

    private InMemoryExerciseRepository repo;
    private ExerciseService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryExerciseRepository();
        repo.save(bare("ex1"));
        service = new ExerciseService(repo, true);
    }

    private static Exercise bare(String id) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }

    private static DemoFrame frame(Exercise e, String key) {
        return e.demoFrames().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    void recordFramePersistsPromptAndGrounding() {
        Exercise e = service.recordFrame(
            "ex1", "start", "Start", "cap", 0, "url1", "THE FINAL PROMPT", List.of("g1", "g2"));

        DemoFrame f = frame(e, "start");
        assertThat(f.generationPrompt()).isEqualTo("THE FINAL PROMPT");
        assertThat(f.groundingUrls()).containsExactly("g1", "g2");
        assertThat(f.imageUrl()).isEqualTo("url1");
    }

    @Test
    void selectAndRemovePreservePromptAndGrounding() {
        service.recordFrame("ex1", "start", "Start", "cap", 0, "url1", "P1", List.of("g1"));
        // Regenerate → newest prompt/grounding + a second candidate, active=url2.
        service.recordFrame("ex1", "start", "Start", "cap", 0, "url2", "P2", List.of("g2"));

        // Selecting an older candidate keeps the latest recorded prompt/grounding.
        Exercise sel = service.selectFrame("ex1", "start", "url1");
        DemoFrame fs = frame(sel, "start");
        assertThat(fs.imageUrl()).isEqualTo("url1");
        assertThat(fs.generationPrompt()).isEqualTo("P2");
        assertThat(fs.groundingUrls()).containsExactly("g2");

        // Removing a candidate preserves them too.
        Exercise rem = service.removeFrameCandidate("ex1", "start", "url2");
        DemoFrame fr = frame(rem, "start");
        assertThat(fr.generationPrompt()).isEqualTo("P2");
        assertThat(fr.groundingUrls()).containsExactly("g2");
    }

    @Test
    void uploadWithoutPromptKeepsPriorRecordedPrompt() {
        service.recordFrame("ex1", "start", "Start", "cap", 0, "url1", "P1", List.of("g1"));
        // An admin upload (no prompt) must not wipe the recorded generation prompt.
        Exercise up = service.recordFrame("ex1", "start", "Start", "cap", 0, "url2");
        DemoFrame f = frame(up, "start");
        assertThat(f.generationPrompt()).isEqualTo("P1");
        assertThat(f.groundingUrls()).containsExactly("g1");
        assertThat(f.imageCandidates()).contains("url1", "url2");
    }
}
