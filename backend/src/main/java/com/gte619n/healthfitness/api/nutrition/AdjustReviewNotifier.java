package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.AdjustReviewReadyEvent;
import com.gte619n.healthfitness.core.nutrition.MealAdjustmentService;
import com.gte619n.healthfitness.core.push.UserNotificationPusher;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns an {@link AdjustReviewReadyEvent} into a user-visible FCM push. Mirrors
 * {@link LeftoverReviewNotifier}: maps the event to a title/body + routing data
 * and delegates delivery (token lookup, stale-token pruning, dead-end logging)
 * to {@link UserNotificationPusher}.
 *
 * <p>The {@code data} carries the routing {@code type} plus the target
 * {@code date}/{@code entryId} so the Android client's Apply action commits the
 * exact entry and the body-tap deep-links to its editor. Delivery failures never
 * propagate — the event fires after the proposal is persisted, so a missed push
 * just means the user relies on the in-app pending-review state.
 */
@Component
public class AdjustReviewNotifier {

    private final UserNotificationPusher push;

    public AdjustReviewNotifier(UserNotificationPusher push) {
        this.push = push;
    }

    @EventListener
    public void onReviewReady(AdjustReviewReadyEvent event) {
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
        push.send(type, event.userId(), title, body, data);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
