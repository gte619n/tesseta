package com.gte619n.healthfitness.blood;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class BloodControllerTest {

    @Autowired MockMvc mvc;

    private static final String TEST_USER = "user-blood-test";

    @Test
    void createAndListTestosteroneReading() throws Exception {
        // "TESTOSTERONE" must deserialize to the BloodMarker enum constant by
        // name and round-trip its HIGHER_IS_BETTER range from the backend.
        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"marker":"TESTOSTERONE","value":650,"unit":"ng/dL","sampleDate":"2026-05-30"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.readingId").exists())
            .andExpect(jsonPath("$.marker").value("TESTOSTERONE"))
            .andExpect(jsonPath("$.value").value(650.0))
            .andExpect(jsonPath("$.unit").value("ng/dL"))
            .andExpect(jsonPath("$.reference.orientation").value("HIGHER_IS_BETTER"))
            .andExpect(jsonPath("$.reference.goodThreshold").value(300.0))
            .andExpect(jsonPath("$.reference.displayMin").value(200.0))
            .andExpect(jsonPath("$.reference.displayMax").value(1200.0));

        mvc.perform(get("/api/me/blood")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.marker == 'TESTOSTERONE')]").exists())
            .andExpect(jsonPath("$[?(@.marker == 'TESTOSTERONE')].value").value(650.0));
    }
}
