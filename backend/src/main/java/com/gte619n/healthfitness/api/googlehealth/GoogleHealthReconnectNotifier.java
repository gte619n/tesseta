package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.push.FcmSender;
import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link GoogleHealthConnectionBrokenEvent} into a user-visible push
 * prompting the user to reconnect. Mirrors {@code SyncChangePublisher}: loads
 * the user's device tokens and sends via {@link FcmSender}, but a
 * <em>notification</em> (title/body) rather than a silent sync ping.
 *
 * <p>The {@code data.type = gh-reconnect} lets the Android client route the
 * tap to the Google Health settings screen. Delivery failures never propagate
 * back into the token-exchange path — the event is published after the broken
 * flag is persisted, so a missed push only means the user relies on the
 * in-app banner instead.
 */
@Component
public class GoogleHealthReconnectNotifier {

    private static final Logger log =
        System.getLogger(GoogleHealthReconnectNotifier.class.getName());

    /** Data-message type discriminator the Android client switches on to route the tap. */
    public static final String MESSAGE_TYPE = "gh-reconnect";

    private static final String TITLE = "Reconnect Google Health";
    private static final String BODY =
        "Your Google Health data stopped syncing. Tap to reconnect.";

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public GoogleHealthReconnectNotifier(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    @EventListener
    public void onConnectionBroken(GoogleHealthConnectionBrokenEvent event) {
        try {
            List<FcmToken> all = tokens.findByUser(event.userId());
            if (all.isEmpty()) {
                return;
            }
            List<String> tokenValues = all.stream().map(FcmToken::token).toList();
            sender.sendNotification(tokenValues, TITLE, BODY, Map.of("type", MESSAGE_TYPE));
        } catch (RuntimeException e) {
            // A broken-connection detection must never fail because a push could
            // not be delivered; the in-app reconnect banner is the durable path.
            log.log(Level.WARNING,
                "Google Health reconnect push failed for user=" + event.userId() + ": " + e);
        }
    }
}
