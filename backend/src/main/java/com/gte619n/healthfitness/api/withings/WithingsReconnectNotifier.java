package com.gte619n.healthfitness.api.withings;

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
 * Turns a {@link WithingsConnectionBrokenEvent} into a user-visible push
 * prompting the user to reconnect. Mirrors {@code GoogleHealthReconnectNotifier}:
 * loads the user's device tokens and sends a notification via {@link FcmSender}.
 *
 * <p>The {@code data.type = withings-reconnect} lets the Android client route
 * the tap to the Withings settings screen. Delivery failures never propagate
 * back into the token-exchange path.
 */
@Component
public class WithingsReconnectNotifier {

    private static final Logger log =
        System.getLogger(WithingsReconnectNotifier.class.getName());

    /** Data-message type discriminator the Android client switches on to route the tap. */
    public static final String MESSAGE_TYPE = "withings-reconnect";

    private static final String TITLE = "Reconnect Withings";
    private static final String BODY =
        "Your Withings data stopped syncing. Tap to reconnect.";

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public WithingsReconnectNotifier(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    @EventListener
    public void onConnectionBroken(WithingsConnectionBrokenEvent event) {
        try {
            List<FcmToken> all = tokens.findByUser(event.userId());
            if (all.isEmpty()) {
                return;
            }
            List<String> tokenValues = all.stream().map(FcmToken::token).toList();
            sender.sendNotification(tokenValues, TITLE, BODY, Map.of("type", MESSAGE_TYPE));
        } catch (RuntimeException e) {
            log.log(Level.WARNING,
                "Withings reconnect push failed for user=" + event.userId() + ": " + e);
        }
    }
}
