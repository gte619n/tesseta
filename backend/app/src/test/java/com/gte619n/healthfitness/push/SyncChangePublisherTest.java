package com.gte619n.healthfitness.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.SyncChangePublisher;
import com.gte619n.healthfitness.core.push.SyncChangedEvent;
import com.gte619n.healthfitness.testsupport.push.InMemoryFcmTokenRepository;
import com.gte619n.healthfitness.testsupport.push.RecordingFcmSender;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit test for {@link SyncChangePublisher} fan-out semantics
 * (IMPL-AND-20, D18) without the full app context. Covers the server-originated
 * (webhook) case: a {@code null} originDeviceId fans out to ALL of the user's
 * tokens.
 */
class SyncChangePublisherTest {

    private InMemoryFcmTokenRepository tokens;
    private RecordingFcmSender sender;
    private SyncChangePublisher publisher;

    private static final String USER = "user-pub";

    @BeforeEach
    void setUp() {
        tokens = new InMemoryFcmTokenRepository();
        sender = new RecordingFcmSender();
        publisher = new SyncChangePublisher(tokens, sender);
        tokens.save(USER, new FcmToken("device-A", "token-A", null));
        tokens.save(USER, new FcmToken("device-B", "token-B", null));
        tokens.save(USER, new FcmToken("device-C", "token-C", null));
    }

    @Test
    void serverOriginatedChangeFansOutToAllTokens() {
        // originDeviceId=null ⇒ Google Health webhook / server-side change.
        publisher.onSyncChanged(
            new SyncChangedEvent(USER, List.of("bodyComposition"), null));

        assertThat(sender.sends()).hasSize(1);
        assertThat(sender.lastSend().tokens())
            .containsExactlyInAnyOrder("token-A", "token-B", "token-C");
        assertThat(sender.lastSend().collections()).containsExactly("bodyComposition");
    }

    @Test
    void deviceOriginatedChangeSuppressesOriginToken() {
        publisher.onSyncChanged(
            new SyncChangedEvent(USER, List.of("dailyMetrics"), "device-B"));

        assertThat(sender.lastSend().tokens())
            .containsExactlyInAnyOrder("token-A", "token-C")
            .doesNotContain("token-B");
    }

    @Test
    void noTokensIsANoOpSend() {
        tokens.clear();
        publisher.onSyncChanged(
            new SyncChangedEvent(USER, List.of("goals"), "device-A"));
        assertThat(sender.sends()).isEmpty();
    }
}
