package com.gte619n.healthfitness.api.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.auth.SessionTokenService;
import com.gte619n.healthfitness.auth.SessionTokenService.InvalidRefreshTokenException;
import com.gte619n.healthfitness.auth.SessionTokenService.TokenPair;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Slice test for the ADR-0010 session endpoints. Security filters are off
// (resolution is covered elsewhere); here we pin the controller's own contract:
// exchange mints a pair, refresh maps an invalid token to 401, logout is 204,
// and a missing signing key surfaces as 503.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SessionTokenService sessions;
    @MockitoBean CurrentUserProvider currentUser;

    private static final TokenPair PAIR = new TokenPair("access-jwt", 1000L, "tid.secret", 2000L);

    @Test
    void exchangeMintsAPairForTheAuthenticatedCaller() throws Exception {
        when(sessions.isEnabled()).thenReturn(true);
        when(currentUser.get()).thenReturn(
            new CurrentUser("sub-123", "ada@example.com", "Ada Lovelace", null));
        when(sessions.issueFor(any())).thenReturn(PAIR);

        mvc.perform(post("/api/auth/exchange"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-jwt"))
            .andExpect(jsonPath("$.accessTokenExpiresAt").value(1000))
            .andExpect(jsonPath("$.refreshToken").value("tid.secret"))
            .andExpect(jsonPath("$.refreshTokenExpiresAt").value(2000));
    }

    @Test
    void exchangeReturns503WhenSessionTokensAreNotConfigured() throws Exception {
        when(sessions.isEnabled()).thenReturn(false);

        mvc.perform(post("/api/auth/exchange"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void refreshTradesARefreshTokenForANewPair() throws Exception {
        when(sessions.isEnabled()).thenReturn(true);
        when(sessions.refresh("tid.secret")).thenReturn(PAIR);

        mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"tid.secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-jwt"));
    }

    @Test
    void refreshMapsInvalidTokenTo401() throws Exception {
        when(sessions.isEnabled()).thenReturn(true);
        when(sessions.refresh(any()))
            .thenThrow(new InvalidRefreshTokenException("refresh token already used"));

        mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"dead\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesAndReturns204() throws Exception {
        mvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"tid.secret\"}"))
            .andExpect(status().isNoContent());

        verify(sessions).revoke("tid.secret");
    }
}
