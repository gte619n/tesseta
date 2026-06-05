package com.gte619n.uat.flows

import com.gte619n.uat.pages.MedicationsPage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #4 — medications: add a custom medication through the modal and confirm
 *  it lists + persists. (Catalog/AI drug lookup is avoided; the custom-entry
 *  path needs no Gemini call.) */
class MedicationsFlowTest : BaseUatTest() {

    @Test
    fun addCustomMedicationThroughUiPersists() {
        val user = TestUsers.fresh("meds")
        signIn(user, callbackUrl = "/me/meds")

        val name = "UAT Magnesium ${user.userId}"
        val meds = MedicationsPage(driver)
        meds.open()
        meds.addCustomMedication(name, "200")

        // It renders in the current-medications list.
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "UAT Magnesium"))

        // And it round-tripped to the backend.
        val token = backend.devLogin(user.userId, user.email, user.name)
        eventually {
            backend.getJson("/api/me/medications", token).toString().contains(name)
        }
        assertTrue(true)
    }
}
