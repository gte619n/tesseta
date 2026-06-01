package com.gte619n.healthfitness.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

// Verifies the routing rules in SecurityConfig: which endpoints are public,
// which require authentication, and which are blanket-denied.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class SecurityConfigTest {

    @Autowired MockMvc mvc;

    @Test
    void helloIsPublic() throws Exception {
        mvc.perform(get("/api/hello"))
           .andExpect(status().isOk());
    }

    @Test
    void meRequiresAuth() throws Exception {
        mvc.perform(get("/api/me"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void meAcceptsValidJwt() throws Exception {
        mvc.perform(get("/api/me").with(jwt().jwt(b -> b
            .subject("108527834729384759201")
            .claim("email", "user@example.com")
            .claim("name", "Test User")
            .claim("picture", "https://example.com/avatar.jpg"))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.userId").value("108527834729384759201"))
           .andExpect(jsonPath("$.email").value("user@example.com"))
           .andExpect(jsonPath("$.displayName").value("Test User"))
           .andExpect(jsonPath("$.photoUrl").value("https://example.com/avatar.jpg"));
    }

    @Test
    void unknownPathIsDenied() throws Exception {
        // anyRequest().denyAll() => unmapped paths are 403/401, never 200.
        mvc.perform(get("/some/unknown/path"))
           .andExpect(status().is4xxClientError());
    }
}
