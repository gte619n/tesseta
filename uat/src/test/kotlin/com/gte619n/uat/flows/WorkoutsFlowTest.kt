package com.gte619n.uat.flows

import com.gte619n.uat.pages.WorkoutsPage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #7 — workouts: create a gym through the UI, confirm it lists + persists. */
class WorkoutsFlowTest : BaseUatTest() {

    @Test
    fun createGymThroughUiAppearsInListAndBackend() {
        val user = TestUsers.fresh("workouts")
        signIn(user, callbackUrl = "/me/workouts/gyms")

        val name = "Iron Temple ${user.userId}"
        val workouts = WorkoutsPage(driver)
        workouts.openNewGym()
        workouts.createGym(name)

        workouts.openGymList()
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), name))

        val token = backend.devLogin(user.userId, user.email, user.name)
        eventually {
            backend.getJson("/api/me/gyms", token).any { it.get("name").asText() == name }
        }
        assertTrue(true)
    }
}
