package com.gte619n.healthfitness.mobile.workouts

import android.content.Context
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0012 Decision 6 — starts [WorkoutSessionService] whenever a local draft
 * session exists.
 *
 * The logger UI lives in `feature-workouts`, which cannot reference an `:app`
 * service class; instead of a cross-module start seam, the UI simply writes
 * the draft ([WorkoutSessionRepository.start]) and this app-side collector —
 * running for the whole signed-in lifetime, wired in MainActivity next to the
 * first-sync gate — reacts to "a draft now exists" by starting the foreground
 * service. The same reaction rehydrates the notification when the app reopens
 * with a draft still in Room (process death, task swipe).
 *
 * Stop is not handled here: the service observes the draft itself and stops
 * when the row is deleted (finish/skip/discard/stale sweep).
 */
@Singleton
class WorkoutSessionForegroundLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    // Lazy: resolving the repository opens the SQLCipher store — only do it
    // inside [run], which MainActivity launches on Dispatchers.IO.
    private val sessions: dagger.Lazy<WorkoutSessionRepository>,
) {
    /** Collects forever (until the caller's scope dies). Call off the main thread. */
    suspend fun run() {
        sessions.get().observeDrafts()
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .collect { hasDraft ->
                if (hasDraft) {
                    // Guard the background-start window (drafts are only created
                    // from the foreground UI, but don't crash if that ever shifts).
                    runCatching { WorkoutSessionService.start(context) }
                }
            }
    }
}
