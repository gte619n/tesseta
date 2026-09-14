package com.gte619n.healthfitness.api.nutrition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.nutrition.EntryAnalysisStatus;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.integrations.nutrition.SignedUrlService;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SEC-012 — the private meal-photo endpoint. Verifies per-user authorization
 * (a foreign entry is 404, never a redirect to someone else's photo) and that the
 * entry DTO carries a {@code photoUrl} pointing at this endpoint.
 *
 * <p>The GCS-backed {@code SignedUrlService} is gated off in the test profile, so
 * this test supplies a deterministic stub bean that signs a fixed URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, MealPhotoServingTest.StubSigner.class})
class MealPhotoServingTest {

    @Autowired MockMvc mvc;
    @Autowired FoodEntryRepository entries;

    private static final String USER_A = "user-photo-a";
    private static final String USER_B = "user-photo-b";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
    private static final String SIGNED = "https://signed.example/read?sig=abc";

    @TestConfiguration
    static class StubSigner {
        @Bean
        SignedUrlService signedUrlService() {
            SignedUrlService s = Mockito.mock(SignedUrlService.class);
            Mockito.when(s.signedReadUrl(Mockito.anyString())).thenReturn(SIGNED);
            return s;
        }
    }

    private FoodEntry photoEntry(String userId, String entryId) {
        return new FoodEntry(
            userId, DATE, entryId, MealType.LUNCH, null, "Chicken bowl", "1 bowl", 400.0, 1.0,
            new Macros(500.0, 40.0, 30.0, 20.0, 0.0, 0.0),
            "https://storage.googleapis.com/bucket/nutrition/" + userId + "/x.jpg",
            null, EntrySource.PHOTO,
            List.of(), null, FoodImageStatus.READY, EntryAnalysisStatus.READY,
            Instant.now(), Instant.now(), null, null);
    }

    @Test
    void ownerGetsRedirectToSignedUrl() throws Exception {
        entries.save(photoEntry(USER_A, "e-own"));
        mvc.perform(get("/api/me/nutrition/photo/e-own")
                .header("X-Dev-User", USER_A)
                .param("date", DATE.toString()))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", SIGNED));
    }

    @Test
    void foreignEntryIs404NotAnotherUsersPhoto() throws Exception {
        entries.save(photoEntry(USER_A, "e-foreign"));
        // User B asks for user A's entry id + date → must be 404, never a redirect.
        mvc.perform(get("/api/me/nutrition/photo/e-foreign")
                .header("X-Dev-User", USER_B)
                .param("date", DATE.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void dayDtoPopulatesPhotoUrlForEntriesWithAPhoto() throws Exception {
        entries.save(photoEntry(USER_A, "e-dto"));
        mvc.perform(get("/api/me/nutrition/" + DATE)
                .header("X-Dev-User", USER_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.meals[?(@.meal == 'LUNCH')].entries[?(@.entryId == 'e-dto')].photoUrl")
                .value("/api/me/nutrition/photo/e-dto?date=" + DATE));
    }
}
