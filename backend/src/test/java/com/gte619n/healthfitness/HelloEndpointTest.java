package com.gte619n.healthfitness;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class HelloEndpointTest {
    @Autowired MockMvc mvc;

    @Test
    void helloReturnsGreeting() throws Exception {
        mvc.perform(get("/api/hello"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Hello from tesseta"))
           .andExpect(jsonPath("$.timestamp").exists());
    }
}
