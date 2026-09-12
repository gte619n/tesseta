package com.gte619n.healthfitness.core.nutrition;

/**
 * Published when a "Remove Leftovers" analysis settles (IMPL-LEFTOVER-01, IL-6):
 * a valid result awaiting review, or a rejection prompting a retake. A listener
 * ({@code LeftoverReviewNotifier}) turns it into the user-visible FCM push —
 * mirroring the event-then-listener pattern of the Google Health / Withings
 * reconnect notifiers rather than sending inline from the service.
 *
 * <p>{@code date}/{@code entryId} travel in the push data (IL-10) so the
 * notification's Apply action commits the exact entry rather than guessing which
 * pending-review entry the user meant.
 *
 * @param userId      the owning user
 * @param date        the entry's date (ISO yyyy-MM-dd)
 * @param entryId     the target entry
 * @param rejected    true → retake prompt; false → review prompt
 * @param deltaKcal   calories removed (served − consumed); 0 when rejected
 */
public record LeftoverReviewReadyEvent(
    String userId,
    String date,
    String entryId,
    boolean rejected,
    double deltaKcal
) {}
