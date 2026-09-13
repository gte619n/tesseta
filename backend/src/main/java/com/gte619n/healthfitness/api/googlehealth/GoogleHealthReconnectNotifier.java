package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.push.UserNotificationPusher;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link GoogleHealthConnectionBrokenEvent} into a user-visible push
 * prompting the user to reconnect. Delegates delivery (token lookup, stale-token
 * pruning, dead-end logging) to {@link UserNotificationPusher}.
 *
 * <p>The {@code data.type = gh-reconnect} lets the Android client route the
 * tap to the Google Health settings screen. Delivery failures never propagate
 * back into the token-exchange path — the event is published after the broken
 * flag is persisted, so a missed push only means the user relies on the
 * in-app banner instead.
 */
@Component
public class GoogleHealthReconnectNotifier {

    /** Data-message type discriminator the Android client switches on to route the tap. */
    public static final String MESSAGE_TYPE = "gh-reconnect";

    private static final String TITLE = "Reconnect Google Health";
    private static final String BODY =
        "Your Google Health data stopped syncing. Tap to reconnect.";

    private final UserNotificationPusher push;

    public GoogleHealthReconnectNotifier(UserNotificationPusher push) {
        this.push = push;
    }

    @EventListener
    public void onConnectionBroken(GoogleHealthConnectionBrokenEvent event) {
        push.send(MESSAGE_TYPE, event.userId(), TITLE, BODY, Map.of("type", MESSAGE_TYPE));
    }
}
