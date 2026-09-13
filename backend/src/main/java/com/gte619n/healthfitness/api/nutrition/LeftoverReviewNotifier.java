package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.LeftoverReviewReadyEvent;
import com.gte619n.healthfitness.core.nutrition.LeftoverService;
import com.gte619n.healthfitness.core.push.UserNotificationPusher;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link LeftoverReviewReadyEvent} into a user-visible FCM push
 * (IMPL-LEFTOVER-01, spec D13 / IL-6). Maps the event to a title/body + routing
 * data and delegates delivery (token lookup, stale-token pruning, dead-end
 * logging) to {@link UserNotificationPusher}.
 *
 * <p>The {@code data} carries the routing {@code type} plus the target
 * {@code date}/{@code entryId} (IL-10) so the Android client's Apply action
 * commits the exact entry and the body-tap deep-links to its review. Delivery
 * failures never propagate — the event fires after the result is persisted, so a
 * missed push just means the user relies on the in-app pending-review state.
 */
@Component
public class LeftoverReviewNotifier {

    private final UserNotificationPusher push;

    public LeftoverReviewNotifier(UserNotificationPusher push) {
        this.push = push;
    }

    @EventListener
    public void onReviewReady(LeftoverReviewReadyEvent event) {
        String type = event.rejected() ? LeftoverService.NOTIF_RETAKE : LeftoverService.NOTIF_REVIEW;
        String title = event.rejected() ? "Couldn't read leftovers" : "Leftovers analyzed";
        String body = event.rejected()
            ? "Tap to retake"
            : String.format("−%d kcal · tap to review", Math.max(0, Math.round(event.deltaKcal())));
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
