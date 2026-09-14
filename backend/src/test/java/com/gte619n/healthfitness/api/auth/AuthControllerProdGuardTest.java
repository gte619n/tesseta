package com.gte619n.healthfitness.api.auth;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.auth.AppAuthProperties;
import com.gte619n.healthfitness.auth.SessionTokenService;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// SEC-008: dev-login is fail-closed in prod. Even with the enable-flag ON, a
// deployment whose project id is the prod project must refuse dev-login (404),
// so no single env-var flip can arm the arbitrary-account mint in prod.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.gcp.project-id=health-fitness-160")
class AuthControllerProdGuardTest {

    @Autowired MockMvc mvc;
    @MockitoBean SessionTokenService sessions;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean AppAuthProperties authProps;

    @Test
    void devLoginIs404OnProdProjectEvenWhenFlagEnabled() throws Exception {
        when(authProps.isDevLoginEnabled()).thenReturn(true);
        when(sessions.isEnabled()).thenReturn(true);

        mvc.perform(post("/api/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"attacker\"}"))
            .andExpect(status().isNotFound());

        // No session was minted despite the flag being on.
        verify(sessions, never()).issueFor(ArgumentMatchers.any());
    }
}
