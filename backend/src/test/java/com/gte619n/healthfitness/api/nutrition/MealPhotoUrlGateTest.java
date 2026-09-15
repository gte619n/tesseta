package com.gte619n.healthfitness.api.nutrition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.nutrition.EntryAnalysisStatus;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SEC-012 rollout gate — by DEFAULT (flag off) the entry DTO must NOT carry a
 * {@code photoUrl}, so clients render the raw public {@code imageUrl} and never
 * hit the authenticated redirect endpoint. This is what un-breaks image loading
 * on already-shipped app builds that can't attach the bearer to image requests;
 * the flag is flipped on only once auth-capable clients are in the field. The
 * enabled path is covered by {@link MealPhotoServingTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class MealPhotoUrlGateTest {

    @Autowired MockMvc mvc;
    @Autowired FoodEntryRepository entries;

    private static final String USER = "user-photo-gate";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);

    @Test
    void photoUrlIsNullWhenGateOff() throws Exception {
        entries.save(new FoodEntry(
            USER, DATE, "e-gate", MealType.LUNCH, null, "Chicken bowl", "1 bowl", 400.0, 1.0,
            new Macros(500.0, 40.0, 30.0, 20.0, 0.0, 0.0),
            "https://storage.googleapis.com/bucket/nutrition/" + USER + "/x.jpg",
            null, EntrySource.PHOTO,
            List.of(), null, FoodImageStatus.READY, EntryAnalysisStatus.READY,
            Instant.now(), Instant.now(), null, null));

        mvc.perform(get("/api/me/nutrition/" + DATE).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.meals[?(@.meal == 'LUNCH')].entries[?(@.entryId == 'e-gate')].photoUrl")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }
}
