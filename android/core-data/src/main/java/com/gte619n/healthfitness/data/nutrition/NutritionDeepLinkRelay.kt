package com.gte619n.healthfitness.data.nutrition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped relay for nutrition notification deep links (mirrors the Withings
 * OAuth coordinator relay). A tapped "adjust-review" notification body carries a
 * target (date, entryId) that must open the meal editor's review sheet for that
 * exact entry — but the FCM handler / Activity intent has no NavController. So the
 * Activity drops the target here; the nav layer collects it, navigates to the
 * nutrition screen, opens the review, then [consume]s it (single-shot).
 */
@Singleton
class NutritionDeepLinkRelay @Inject constructor() {

    /** A request to open the async-adjustment review sheet for a specific entry. */
    data class AdjustReviewTarget(val date: String, val entryId: String)

    private val _pending = MutableStateFlow<AdjustReviewTarget?>(null)
    val pending: StateFlow<AdjustReviewTarget?> = _pending.asStateFlow()

    /** Request the adjustment review sheet for (date, entryId). */
    fun openAdjustReview(date: String, entryId: String) {
        _pending.value = AdjustReviewTarget(date, entryId)
    }

    /** Clear the pending target once the nav layer has acted on it. */
    fun consume() {
        _pending.value = null
    }
}
