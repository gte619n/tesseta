package com.gte619n.healthfitness.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Dev-mode bypass: X-Dev-User stands in for a Google JWT in tests so we don't
// need a JWKS or signed tokens to exercise authenticated endpoints.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class DevHeaderAuthTest {

    @Autowired MockMvc mvc;

    @Test
    void devUserHeaderAuthenticatesTheRequest() throws Exception {
        mvc.perform(get("/api/me").header("X-Dev-User", "u-dev-1"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.userId").value("u-dev-1"));
    }

    @Test
    void emptyDevUserHeaderIsNotAuthenticated() throws Exception {
        mvc.perform(get("/api/me").header("X-Dev-User", ""))
           .andExpect(status().isUnauthorized());
    }
}
