package com.gte619n.healthfitness.domain.prefs

/**
 * On-device audio settings for the workout coach (IMPL-COACH PR2). Both default
 * on — the coach is meant to be hands-free — and are stored locally only. Read
 * and written through the concrete `data.prefs.CoachAudioPreferences` repository.
 */
data class CoachAudioSettings(
    /** Play a beep over the headphones when a rest period ends. */
    val restBeep: Boolean = true,
    /** Speak the exercise, weight, and reps aloud at the start of each set. */
    val voiceAnnouncements: Boolean = true,
)
