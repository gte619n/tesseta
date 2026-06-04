package com.gte619n.healthfitness.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.blood.BloodController;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.push.InMemoryFcmTokenRepository;
import com.gte619n.healthfitness.testsupport.push.RecordingFcmSender;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 2 verification (IMPL-AND-20, D18): a write fans a silent FCM data
 * message out to the user's other devices with origin-device suppression.
 *
 * <p>Exercised through the real {@link BloodController} write path with a
 * capturing {@link RecordingFcmSender}: a POST carrying
 * {@code X-HF-Origin-Device: device-A} must reach tokens B and C but NOT A, and
 * the captured message must be data-only with the changed collection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class SyncFanoutTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InMemoryFcmTokenRepository tokenRepo;
    @Autowired RecordingFcmSender sender;

    private static final String USER = "user-fanout";

    @BeforeEach
    void reset() {
        tokenRepo.clear();
        sender.clear();
        // Three registered devices for the user.
        tokenRepo.save(USER, new FcmToken("device-A", "token-A", null));
        tokenRepo.save(USER, new FcmToken("device-B", "token-B", null));
        tokenRepo.save(USER, new FcmToken("device-C", "token-C", null));
    }

    @Test
    void writeFromDeviceAFansOutToBandCButNotA() throws Exception {
        var body = new BloodController.CreateRequest(
            null, BloodMarker.HDL, 55.0, "mg/dL", LocalDate.now(), null, null);

        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, "device-A")
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated());

        // Exactly one fan-out captured.
        assertThat(sender.sends()).hasSize(1);
        RecordingFcmSender.Sent sent = sender.lastSend();

        // Recipients: B and C, NOT the originating device A (D18 suppression).
        assertThat(sent.tokens())
            .containsExactlyInAnyOrder("token-B", "token-C")
            .doesNotContain("token-A");

        // Data-only sync message for the changed collection.
        assertThat(sent.collections()).containsExactly("bloodReadings");
    }

    @Test
    void writeWithUnknownOriginDeviceFansOutToAll() throws Exception {
        var body = new BloodController.CreateRequest(
            null, BloodMarker.LDL, 90.0, "mg/dL", LocalDate.now(), null, null);

        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, "device-unknown")
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated());

        assertThat(sender.lastSend().tokens())
            .containsExactlyInAnyOrder("token-A", "token-B", "token-C");
    }

    @Test
    void prunesTokensFcmReportsUnregistered() throws Exception {
        // Arrange B to come back UNREGISTERED so the publisher prunes it.
        sender.reportUnregistered("token-B");

        var body = new BloodController.CreateRequest(
            null, BloodMarker.HDL, 60.0, "mg/dL", LocalDate.now(), null, null);

        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, "device-A")
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated());

        // device-B's registration was pruned; A (origin) and C remain.
        assertThat(tokenRepo.findByUser(USER))
            .extracting(FcmToken::deviceId)
            .containsExactlyInAnyOrder("device-A", "device-C");
    }
}
