package com.gte619n.healthfitness.data.reminders

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Wire DTO for `GET/PUT /api/me/medications/reminder-settings`. Windows are
 * enum names, times "HH:mm" strings — the backend's exact shape.
 */
data class ReminderSettingsDto(
    val enabled: Boolean? = null,
    val windowTimes: Map<String, String>? = null,
    val perMedication: Map<String, MedicationOverrideDto>? = null,
) {
    data class MedicationOverrideDto(
        val enabled: Boolean? = null,
        val times: Map<String, String>? = null,
    )
}

interface ReminderSettingsApi {

    @GET("api/me/medications/reminder-settings")
    suspend fun get(): ReminderSettingsDto

    @PUT("api/me/medications/reminder-settings")
    suspend fun put(@Body body: ReminderSettingsDto): ReminderSettingsDto
}
