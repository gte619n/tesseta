package com.gte619n.uat.flows

import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #2 — dashboard: seed a blood reading via the API, then confirm the
 *  dashboard's blood panel surfaces it after sign-in (seed-then-render). */
class DashboardFlowTest : BaseUatTest() {

    @Test
    fun dashboardRendersSeededBloodMarker() {
        val user = TestUsers.fresh("dashboard")
        val token = backend.devLogin(user.userId, user.email, user.name)
        // LDL is one of the dashboard's blood markers; 137 -> renders "137.00".
        backend.seedBloodReading(token, "LDL", 137.0, "mg/dL", "2026-06-05")

        signIn(user, callbackUrl = "/")

        // The blood panel populates from the seed (value renders as visible text).
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "Blood panel"))
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "137"))
    }
}
