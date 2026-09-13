package com.gte619n.healthfitness.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.gte619n.healthfitness.core.push.FcmSendResult;
import com.gte619n.healthfitness.core.push.FcmSender;
import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.UserNotificationPusher;
import com.gte619n.healthfitness.testsupport.push.InMemoryFcmTokenRepository;
import com.gte619n.healthfitness.testsupport.push.RecordingFcmSender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit test for {@link UserNotificationPusher} — the shared delivery
 * path behind the adjust/leftover review and reconnect notifiers. Covers the
 * bookkeeping the notifiers used to skip: pruning tokens FCM rejects, and never
 * throwing back into the event path (an empty registry or a broken transport
 * must not fail the operation that produced the notification).
 */
class UserNotificationPusherTest {

    private static final String USER = "user-push";

    private InMemoryFcmTokenRepository tokens;
    private RecordingFcmSender sender;
    private UserNotificationPusher pusher;

    @BeforeEach
    void setUp() {
        tokens = new InMemoryFcmTokenRepository();
        sender = new RecordingFcmSender();
        pusher = new UserNotificationPusher(tokens, sender);
        tokens.save(USER, new FcmToken("device-A", "token-A", null));
        tokens.save(USER, new FcmToken("device-B", "token-B", null));
    }

    @Test
    void sendsToAllRegisteredDevices() {
        pusher.send("adjust-review", USER, "Meal re-analyzed", "+120 kcal · tap to review",
            Map.of("type", "adjust-review", "date", "2026-09-13", "entryId", "e-1"));

        assertThat(sender.notificationSends()).hasSize(1);
        RecordingFcmSender.SentNotification sent = sender.lastNotification();
        assertThat(sent.tokens()).containsExactlyInAnyOrder("token-A", "token-B");
        assertThat(sent.title()).isEqualTo("Meal re-analyzed");
        assertThat(sent.body()).isEqualTo("+120 kcal · tap to review");
        assertThat(sent.data()).containsEntry("type", "adjust-review").containsEntry("entryId", "e-1");
    }

    @Test
    void emptyRegistryIsANoOpSendAndDoesNotThrow() {
        tokens.clear();
        assertThatCode(() ->
            pusher.send("leftover-review", USER, "t", "b", Map.of("type", "leftover-review")))
            .doesNotThrowAnyException();
        assertThat(sender.notificationSends()).isEmpty();
    }

    @Test
    void prunesTokensFcmReportsUnregistered() {
        sender.reportUnregistered("token-B");

        pusher.send("gh-reconnect", USER, "t", "b", Map.of("type", "gh-reconnect"));

        assertThat(tokens.findByUser(USER))
            .extracting(FcmToken::deviceId)
            .containsExactly("device-A");
    }

    @Test
    void transportFailureNeverPropagates() {
        FcmSender broken = new FcmSender() {
            @Override
            public FcmSendResult sendSyncData(List<String> t, List<String> c) {
                return FcmSendResult.empty();
            }

            @Override
            public FcmSendResult sendNotification(
                List<String> t, String title, String body, Map<String, String> data) {
                throw new IllegalStateException("transport down");
            }
        };
        UserNotificationPusher failing = new UserNotificationPusher(tokens, broken);

        assertThatCode(() ->
            failing.send("adjust-review", USER, "t", "b", Map.of("type", "adjust-review")))
            .doesNotThrowAnyException();
    }
}
