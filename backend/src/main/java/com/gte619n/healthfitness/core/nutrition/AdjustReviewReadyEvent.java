package com.gte619n.healthfitness.core.nutrition;

/**
 * Published when an async "Adjust with AI" pass settles: a valid proposal awaiting
 * review, or a failure. A listener ({@code AdjustReviewNotifier}) turns it into the
 * user-visible FCM push — mirroring the event-then-listener pattern of the leftover
 * review notifier rather than sending inline from the service.
 *
 * <p>{@code date}/{@code entryId} travel in the push data so the notification's
 * Apply action commits the exact entry and the body-tap deep-links to its editor.
 *
 * @param userId    the owning user
 * @param date      the entry's date (ISO yyyy-MM-dd)
 * @param entryId   the target entry
 * @param rejected  true → failed (retry prompt); false → review prompt
 * @param deltaKcal absolute calorie change (|new − old|); 0 when rejected
 */
public record AdjustReviewReadyEvent(
    String userId,
    String date,
    String entryId,
    boolean rejected,
    double deltaKcal
) {}
