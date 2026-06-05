package com.gte619n.uat.flows

import com.gte619n.uat.pages.ProfilePage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Flow #3 — profile: edit height through the UI and confirm the PATCH /api/me
 *  write round-trips to the backend (read back via the API as the same user). */
class ProfileFlowTest : BaseUatTest() {

    @Test
    fun editingHeightPersistsToBackend() {
        val user = TestUsers.fresh("profile")
        signIn(user, callbackUrl = "/me/profile")

        val expectedCm = ProfilePage(driver).setHeightAndSave()

        // Read it back via the API as the same user — proves the UI write landed.
        val token = backend.devLogin(user.userId, user.email, user.name)
        val me = backend.getJson("/api/me", token)
        assertEquals(expectedCm, me.get("heightCm").asInt())
    }
}
