package com.gte619n.healthfitness.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.gte619n.healthfitness.data.reminders.ReminderEngine
import com.gte619n.healthfitness.data.reminders.ReminderPlanWorker
import com.gte619n.healthfitness.data.reminders.ReminderReplanCoordinator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

// IMPL-12: activates Hilt for the whole app. The network/DI graph (Retrofit,
// OkHttp auth interceptor, GoalsApi/Repository) is assembled in core-data's
// NetworkModule; AppModule below supplies the app-only bindings (backend URL).
//
// Implements Coil's ImageLoaderFactory so the singleton ImageLoader configured
// in AppModule (memory + disk cache) is the one AsyncImage / HfAsyncImage use
// app-wide, instead of an ad-hoc per-call default loader.
//
// IMPL-AND-20 (Phase 4): also a WorkManager Configuration.Provider so the Hilt
// worker factory injects dependencies into the sync workers (@HiltWorker). The
// default WorkManager initializer is disabled in the manifest so this on-demand
// configuration is the one WorkManager uses.
@HiltAndroidApp
class HealthFitnessApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var reminderEngine: dagger.Lazy<ReminderEngine>

    // IMPL-STAB Workstream F: keeps the reminder alarm chain in sync with
    // medication-mirror changes + multi-device sync pushes (no restart needed).
    @Inject
    lateinit var reminderReplanCoordinator: dagger.Lazy<ReminderReplanCoordinator>

    // App-lifetime scope for fire-and-forget startup work (reminder replan).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun newImageLoader(): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // IMPL-16 Part A: re-arm the medication-reminder alarm chain on every
        // app start (cheap mirror read; quietly plans nothing when signed out)
        // and keep the ~12h safety-net replan registered.
        appScope.launch {
            runCatching { ReminderPlanWorker.register(WorkManager.getInstance(this@HealthFitnessApp)) }
            runCatching { reminderEngine.get().replan() }
            // IMPL-STAB Workstream F (items 1 & 2): start observing medication
            // changes + sync pushes so the alarm chain re-arms without a restart.
            runCatching { reminderReplanCoordinator.get().start() }
        }
    }
}
