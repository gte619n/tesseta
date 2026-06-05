package com.gte619n.uat.flows

import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #1 — auth & shell: dev sign-in works end-to-end, seeded data renders,
 *  and unauthenticated traffic is redirected. */
class AuthFlowTest : BaseUatTest() {

    @Test
    fun signedInUserReachesAppAndSeesSeededData() {
        val user = TestUsers.fresh("auth")
        // Seed a goal via the real API as this user, then sign in via the UI and
        // confirm the server-rendered goals page shows it (web -> backend read).
        val token = backend.devLogin(user.userId, user.email, user.name)
        backend.seedGoal(token, "Seeded UAT goal", "STRENGTH")

        signIn(user, callbackUrl = "/me/goals")

        // We're in the app, not bounced to sign-in.
        assertFalse(driver.currentUrl!!.contains("/auth/"), "should not be on an auth page")
        // The seeded goal title is rendered server-side from the backend.
        wait.until(
            ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "Seeded UAT goal"),
        )
    }

    @Test
    fun unauthenticatedIsRedirectedToSignIn() {
        goto("/me/goals")
        wait.until(ExpectedConditions.urlContains("/auth/signin"))
        assertTrue(driver.currentUrl!!.contains("/auth/signin"))
    }
}
