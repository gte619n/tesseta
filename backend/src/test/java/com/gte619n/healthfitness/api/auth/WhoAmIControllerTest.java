package com.gte619n.healthfitness.api.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Slice test for the /api/me identity endpoint. Security is switched off
// (addFilters = false) because auth resolution itself is covered by the app
// module's SecurityConfigTest/DevHeaderAuthTest; here we pin the controller's
// own contract: it echoes the resolved caller and folds in the persisted
// height (or null when there's no user record yet).
@WebMvcTest(WhoAmIController.class)
@AutoConfigureMockMvc(addFilters = false)
class WhoAmIControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean UserRepository users;

    private static final CurrentUser CALLER =
        new CurrentUser("sub-123", "ada@example.com", "Ada Lovelace", "https://photo.example/ada");

    @Test
    void whoAmIReturnsCallerIdentityWithPersistedHeight() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);
        when(users.findById("sub-123")).thenReturn(Optional.of(
            new User("sub-123", "ada@example.com", "Ada Lovelace", null, 180, null, null)));

        mvc.perform(get("/api/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("sub-123"))
            .andExpect(jsonPath("$.email").value("ada@example.com"))
            .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
            .andExpect(jsonPath("$.photoUrl").value("https://photo.example/ada"))
            .andExpect(jsonPath("$.heightCm").value(180));
    }

    @Test
    void whoAmIToleratesMissingUserRecord() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);
        when(users.findById("sub-123")).thenReturn(Optional.empty());

        mvc.perform(get("/api/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("sub-123"));
    }

    @Test
    void patchPersistsHeightAndEchoesIt() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);

        mvc.perform(patch("/api/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"heightCm\":182}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.heightCm").value(182));

        verify(users).updateHeightCm("sub-123", 182);
    }
}
