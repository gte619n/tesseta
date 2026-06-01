package com.gte619n.healthfitness.data.workout

// Lets feature modules ask the app to promote the active workout to a
// foreground service (which owns the ongoing controls notification) without
// depending on the app module. The app provides the concrete implementation
// via Hilt; if none is bound the workout still runs, just without the
// background-survivable notification.
interface WorkoutForegroundLauncher {
    fun startForegroundSession()
}
