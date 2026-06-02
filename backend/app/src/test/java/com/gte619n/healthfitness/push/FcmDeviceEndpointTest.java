package com.gte619n.healthfitness.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.push.InMemoryFcmTokenRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 2 device-token registry endpoints (IMPL-AND-20, D18):
 * {@code PUT /api/me/devices/fcm} registers/refreshes a token at the
 * per-device key, and {@code DELETE /api/me/devices/fcm} removes it on sign-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class FcmDeviceEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InMemoryFcmTokenRepository tokenRepo;

    private static final String USER = "user-device-fcm";

    @BeforeEach
    void reset() {
        tokenRepo.clear();
    }

    @Test
    void putRegistersTokenAtDeviceKey() throws Exception {
        mvc.perform(put("/api/me/devices/fcm")
                .header("X-Dev-User", USER)
                .contentType("application/json")
                .content(json.writeValueAsString(
                    Map.of("token", "tok-1", "deviceId", "dev-1"))))
            .andExpect(status().isNoContent());

        assertThat(tokenRepo.findByUser(USER))
            .extracting(FcmToken::deviceId, FcmToken::token)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("dev-1", "tok-1"));
    }

    @Test
    void putWithSameDeviceRefreshesToken() throws Exception {
        tokenRepo.save(USER, new FcmToken("dev-1", "old-token", null));

        mvc.perform(put("/api/me/devices/fcm")
                .header("X-Dev-User", USER)
                .contentType("application/json")
                .content(json.writeValueAsString(
                    Map.of("token", "new-token", "deviceId", "dev-1"))))
            .andExpect(status().isNoContent());

        assertThat(tokenRepo.findByUser(USER))
            .extracting(FcmToken::deviceId, FcmToken::token)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("dev-1", "new-token"));
    }

    @Test
    void deleteRemovesToken() throws Exception {
        tokenRepo.save(USER, new FcmToken("dev-1", "tok-1", null));
        tokenRepo.save(USER, new FcmToken("dev-2", "tok-2", null));

        mvc.perform(delete("/api/me/devices/fcm")
                .header("X-Dev-User", USER)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("deviceId", "dev-1"))))
            .andExpect(status().isNoContent());

        assertThat(tokenRepo.findByUser(USER))
            .extracting(FcmToken::deviceId)
            .containsExactly("dev-2");
    }

    @Test
    void putWithMissingTokenIsBadRequest() throws Exception {
        mvc.perform(put("/api/me/devices/fcm")
                .header("X-Dev-User", USER)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("deviceId", "dev-1"))))
            .andExpect(status().isBadRequest());
    }
}
