package com.gte619n.uat.flows

import com.gte619n.uat.pages.GoalsPage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #6 — goals: create a goal through the UI (goal -> detail), then confirm
 *  it appears in the list and is persisted to the backend. */
class GoalsFlowTest : BaseUatTest() {

    @Test
    fun createGoalThroughUiAppearsInListAndBackend() {
        val user = TestUsers.fresh("goals")
        signIn(user, callbackUrl = "/me/goals")

        val title = "Bench press 1.5x bodyweight"
        val goals = GoalsPage(driver)
        goals.openNew()
        goals.createGoal(title)

        // Detail page renders the new goal's title.
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), title))

        // It shows up in the list too.
        goals.openList()
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), title))

        // And it round-tripped to the backend.
        val token = backend.devLogin(user.userId, user.email, user.name)
        val list = backend.getJson("/api/me/goals", token)
        assertTrue(list.any { it.get("title").asText() == title }, "goal should be persisted")
    }
}
