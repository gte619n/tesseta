package com.gte619n.healthfitness.mobile.workouts

import android.content.Context
import com.gte619n.healthfitness.data.workout.WorkoutForegroundLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

// App-module implementation of the launcher the workout player calls to promote
// the active session to a foreground service. Lives here (not core-data)
// because it references the app's WorkoutSessionService.
@Singleton
class WorkoutForegroundLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkoutForegroundLauncher {
    override fun startForegroundSession() {
        WorkoutSessionService.start(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkoutForegroundModule {
    @Binds
    abstract fun bindWorkoutForegroundLauncher(
        impl: WorkoutForegroundLauncherImpl,
    ): WorkoutForegroundLauncher
}
