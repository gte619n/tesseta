package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.AdjustReviewReadyEvent;
import com.gte619n.healthfitness.core.nutrition.MealAdjustmentService;
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
 * Turns an {@link AdjustReviewReadyEvent} into a user-visible FCM push. Mirrors
 * {@link LeftoverReviewNotifier}: loads the user's device tokens and sends a
 * notification (title/body) rather than a silent sync ping.
 *
 * <p>The {@code data} carries the routing {@code type} plus the target
 * {@code date}/{@code entryId} so the Android client's Apply action commits the
 * exact entry and the body-tap deep-links to its editor. Delivery failures never
 * propagate — the event fires after the proposal is persisted, so a missed push
 * just means the user relies on the in-app pending-review state.
 */
@Component
public class AdjustReviewNotifier {

    private static final Logger log = System.getLogger(AdjustReviewNotifier.class.getName());

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public AdjustReviewNotifier(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    @EventListener
    public void onReviewReady(AdjustReviewReadyEvent event) {
        try {
            List<FcmToken> all = tokens.findByUser(event.userId());
            if (all.isEmpty()) {
                return;
            }
            List<String> tokenValues = all.stream().map(FcmToken::token).toList();
            String type = event.rejected()
                ? MealAdjustmentService.NOTIF_ADJUST_FAILED
                : MealAdjustmentService.NOTIF_ADJUST_REVIEW;
            String title = event.rejected() ? "Couldn't adjust the meal" : "Meal re-analyzed";
            String body = event.rejected()
                ? "Tap to try again"
                : String.format("%+d kcal · tap to review", Math.round(event.deltaKcal()));
            Map<String, String> data = Map.of(
                "type", type,
                "date", nullToEmpty(event.date()),
                "entryId", nullToEmpty(event.entryId()));
            sender.sendNotification(tokenValues, title, body, data);
        } catch (RuntimeException e) {
            // An adjustment result must never fail because a push could not be sent;
            // the in-app pending-review state is the durable path.
            log.log(Level.WARNING,
                "Adjust review push failed for user=" + event.userId() + ": " + e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
