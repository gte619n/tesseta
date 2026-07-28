package com.gte619n.healthfitness.integrations.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class GeminiExerciseMediaGroundingTest {

    private static final String BUCKET_PREFIX =
        "https://storage.googleapis.com/test-exercise-media/exercises/e1/";
    private static final String OWN_UPLOAD_URL = BUCKET_PREFIX + "grounding-upload_123.jpg";
    private static final String EXTERNAL_URL = "https://example.org/reference/pose.jpg";

    private ExerciseMediaStorage storage;
    private ExerciseService exerciseService;
    private GeminiExerciseMediaService media;

    @BeforeEach
    void setUp() {
        storage = Mockito.mock(ExerciseMediaStorage.class);
        exerciseService = Mockito.mock(ExerciseService.class);
        media = new GeminiExerciseMediaService(storage, exerciseService, null, null, "", false);
        when(exerciseService.setGroundingImageUrls(anyString(), any())).thenReturn(exerciseWith(List.of()));
    }

    @Test
    void uploadGroundingImage_appendsUrlToGroundingSet() {
        when(storage.upload(eq("e1"), eq("grounding-upload"), any(byte[].class), eq("image/jpeg")))
            .thenReturn(OWN_UPLOAD_URL);
        when(exerciseService.findById("e1"))
            .thenReturn(Optional.of(exerciseWith(List.of(EXTERNAL_URL))));

        media.uploadGroundingImage("e1", new byte[]{1, 2, 3}, "image/jpeg");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(exerciseService).setGroundingImageUrls(eq("e1"), captor.capture());
        assertThat(captor.getValue()).containsExactly(EXTERNAL_URL, OWN_UPLOAD_URL);
    }

    @Test
    void removeGroundingImage_ownUpload_unlinksAndDeletesObject() {
        when(exerciseService.findById("e1"))
            .thenReturn(Optional.of(exerciseWith(List.of(OWN_UPLOAD_URL, EXTERNAL_URL))));

        media.removeGroundingImage("e1", OWN_UPLOAD_URL);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(exerciseService).setGroundingImageUrls(eq("e1"), captor.capture());
        assertThat(captor.getValue()).containsExactly(EXTERNAL_URL);
        // Own grounding upload → permanently deleted from storage.
        verify(storage).deleteByUrl(OWN_UPLOAD_URL);
    }

    @Test
    void removeGroundingImage_externalUrl_unlinksOnly() {
        when(exerciseService.findById("e1"))
            .thenReturn(Optional.of(exerciseWith(List.of(OWN_UPLOAD_URL, EXTERNAL_URL))));

        media.removeGroundingImage("e1", EXTERNAL_URL);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(exerciseService).setGroundingImageUrls(eq("e1"), captor.capture());
        assertThat(captor.getValue()).containsExactly(OWN_UPLOAD_URL);
        // Candidate/external URL is never file-deleted.
        verify(storage, never()).deleteByUrl(anyString());
    }

    private static Exercise exerciseWith(List<String> groundingImageUrls) {
        Instant now = Instant.now();
        return new Exercise(
            "e1", "Test Exercise", "test exercise", List.of(),
            MovementPattern.SQUAT, List.of(), List.of(), Laterality.BILATERAL, Mechanic.COMPOUND,
            null, List.of(), List.of(), List.of(), null, false,
            List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, now, now, null, false, groundingImageUrls);
    }
}
