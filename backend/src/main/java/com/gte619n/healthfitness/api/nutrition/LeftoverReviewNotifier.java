package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.LeftoverReviewReadyEvent;
import com.gte619n.healthfitness.core.nutrition.LeftoverService;
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
 * Turns a {@link LeftoverReviewReadyEvent} into a user-visible FCM push
 * (IMPL-LEFTOVER-01, spec D13 / IL-6). Mirrors {@code GoogleHealthReconnectNotifier}:
 * loads the user's device tokens and sends a notification (title/body) rather than
 * a silent sync ping.
 *
 * <p>The {@code data} carries the routing {@code type} plus the target
 * {@code date}/{@code entryId} (IL-10) so the Android client's Apply action
 * commits the exact entry and the body-tap deep-links to its review. Delivery
 * failures never propagate — the event fires after the result is persisted, so a
 * missed push just means the user relies on the in-app pending-review state.
 */
@Component
public class LeftoverReviewNotifier {

    private static final Logger log = System.getLogger(LeftoverReviewNotifier.class.getName());

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public LeftoverReviewNotifier(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    @EventListener
    public void onReviewReady(LeftoverReviewReadyEvent event) {
        try {
            List<FcmToken> all = tokens.findByUser(event.userId());
            if (all.isEmpty()) {
                return;
            }
            List<String> tokenValues = all.stream().map(FcmToken::token).toList();
            String type = event.rejected() ? LeftoverService.NOTIF_RETAKE : LeftoverService.NOTIF_REVIEW;
            String title = event.rejected() ? "Couldn't read leftovers" : "Leftovers analyzed";
            String body = event.rejected()
                ? "Tap to retake"
                : String.format("−%d kcal · tap to review", Math.max(0, Math.round(event.deltaKcal())));
            Map<String, String> data = Map.of(
                "type", type,
                "date", nullToEmpty(event.date()),
                "entryId", nullToEmpty(event.entryId()));
            sender.sendNotification(tokenValues, title, body, data);
        } catch (RuntimeException e) {
            // A leftover result must never fail because a push could not be sent;
            // the in-app pending-review state is the durable path (D13).
            log.log(Level.WARNING,
                "Leftover review push failed for user=" + event.userId() + ": " + e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
