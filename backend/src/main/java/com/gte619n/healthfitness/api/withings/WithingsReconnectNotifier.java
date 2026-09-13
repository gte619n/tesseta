package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.push.UserNotificationPusher;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link WithingsConnectionBrokenEvent} into a user-visible push
 * prompting the user to reconnect. Mirrors {@code GoogleHealthReconnectNotifier}:
 * delegates delivery (token lookup, stale-token pruning, dead-end logging) to
 * {@link UserNotificationPusher}.
 *
 * <p>The {@code data.type = withings-reconnect} lets the Android client route
 * the tap to the Withings settings screen. Delivery failures never propagate
 * back into the token-exchange path.
 */
@Component
public class WithingsReconnectNotifier {

    /** Data-message type discriminator the Android client switches on to route the tap. */
    public static final String MESSAGE_TYPE = "withings-reconnect";

    private static final String TITLE = "Reconnect Withings";
    private static final String BODY =
        "Your Withings data stopped syncing. Tap to reconnect.";

    private final UserNotificationPusher push;

    public WithingsReconnectNotifier(UserNotificationPusher push) {
        this.push = push;
    }

    @EventListener
    public void onConnectionBroken(WithingsConnectionBrokenEvent event) {
        push.send(MESSAGE_TYPE, event.userId(), TITLE, BODY, Map.of("type", MESSAGE_TYPE));
    }
}
