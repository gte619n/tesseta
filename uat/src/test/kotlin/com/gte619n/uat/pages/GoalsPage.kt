package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/goals — manual goal creation + list. The manual editor requires a title,
 *  a target date, and at least one phase with start/end dates. */
class GoalsPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun openNew() {
        driver.get("${UatConfig.webBaseUrl}/me/goals/new")
        waitFor("goal-title")
    }

    fun createGoal(title: String) {
        type("goal-title", title)
        // Chrome date inputs (en-US): digits fill MM/DD/YYYY segment-by-segment.
        type("goal-target-date", "12312026")
        click("goal-add-phase")
        waitFor("phase-title")
        type("phase-title", "Phase 1")
        type("phase-start", "06052026")
        type("phase-end", "12312026")
        click("goal-save")
        // ManualGoalEditor pushes /me/goals/{goalId} after the writes land.
        wait.until { it.currentUrl!!.matches(Regex(".*/me/goals/[0-9a-f-]+$")) }
    }

    fun openList() {
        driver.get("${UatConfig.webBaseUrl}/me/goals")
    }

    private fun waitFor(testId: String) =
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=$testId]")))

    private fun type(testId: String, value: String) =
        driver.findElement(By.cssSelector("[data-testid=$testId]")).sendKeys(value)

    private fun click(testId: String) =
        driver.findElement(By.cssSelector("[data-testid=$testId]")).click()
}
