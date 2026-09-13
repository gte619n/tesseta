package com.gte619n.healthfitness.mobile.push

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Commits an async "Adjust with AI" proposal straight from the completion
 * notification's "Apply" action, without opening the app — the async counterpart
 * of [LeftoverApplyReceiver].
 *
 * The backend completion push carries the target `date`/`entryId`, so this
 * receiver commits the EXACT entry. If the extras are missing (an older push), it
 * falls back to resolving the single pending-review adjustment from the
 * freshly-synced mirror ([NutritionRepository.findAdjustPendingReview]). The commit
 * is bodiless — the server holds the proposal (and the saveAsMeal choice).
 */
@AndroidEntryPoint
class AdjustApplyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: NutritionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPLY) return
        val date = intent.getStringExtra(EXTRA_DATE)?.takeIf { it.isNotBlank() }
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }
        val pending = goAsync()
        scope.launch {
            try {
                val target = if (date != null && entryId != null) {
                    date to entryId
                } else {
                    repository.findAdjustPendingReview()
                }
                if (target != null) {
                    runCatching { repository.commitAdjust(target.first, target.second) }
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(ADJUST_NOTIFICATION_ID)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPLY = "com.gte619n.healthfitness.ADJUST_APPLY"
        const val EXTRA_DATE = "com.gte619n.healthfitness.ADJUST_DATE"
        const val EXTRA_ENTRY_ID = "com.gte619n.healthfitness.ADJUST_ENTRY_ID"

        // Must match HfMessagingService.ADJUST_NOTIFICATION_ID.
        private const val ADJUST_NOTIFICATION_ID = 42030
    }
}
