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
 * IMPL-LEFTOVER-01 (D13) — commits a leftover proposal straight from the
 * completion notification's "Apply" action, without opening the app.
 *
 * The backend completion push carries the target `date`/`entryId` (IL-10), so
 * this receiver commits the EXACT entry. If the extras are missing (e.g. an older
 * push), it falls back to resolving the single pending-review entry from the
 * freshly-synced mirror ([NutritionRepository.findLeftoverPendingReview]).
 */
@AndroidEntryPoint
class LeftoverApplyReceiver : BroadcastReceiver() {

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
                    repository.findLeftoverPendingReview()
                }
                if (target != null) {
                    runCatching { repository.applyLeftovers(target.first, target.second) }
                }
                // Clear the notification once we've acted on it.
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(LEFTOVER_NOTIFICATION_ID)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPLY = "com.gte619n.healthfitness.LEFTOVER_APPLY"
        const val EXTRA_DATE = "com.gte619n.healthfitness.LEFTOVER_DATE"
        const val EXTRA_ENTRY_ID = "com.gte619n.healthfitness.LEFTOVER_ENTRY_ID"

        // Must match HfMessagingService.LEFTOVER_NOTIFICATION_ID.
        private const val LEFTOVER_NOTIFICATION_ID = 42020
    }
}
