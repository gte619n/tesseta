package com.gte619n.uat.flows

import com.gte619n.uat.pages.BloodPage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Flow #9 — blood: add a manual marker reading through the modal and confirm it
 *  persisted (the PDF-upload path is intentionally disabled in UAT). */
class BloodFlowTest : BaseUatTest() {

    @Test
    fun addManualReadingPersists() {
        val user = TestUsers.fresh("blood")
        signIn(user, callbackUrl = "/me/blood")

        val blood = BloodPage(driver)
        blood.open()
        blood.addReading("95")

        val token = backend.devLogin(user.userId, user.email, user.name)
        val readings = backend.getJson("/api/me/blood", token)
        assertTrue(
            readings.any { it.get("value").asDouble() == 95.0 },
            "the manual reading should be persisted",
        )
    }
}
