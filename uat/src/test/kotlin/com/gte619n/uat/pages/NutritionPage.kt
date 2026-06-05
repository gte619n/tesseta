package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/nutrition/target — set daily macro targets. */
class NutritionPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun openTargets() {
        driver.get("${UatConfig.webBaseUrl}/me/nutrition/target")
        wait.until(
            ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("[data-testid=macro-target-caloriesKcal]"),
            ),
        )
    }

    /** Set the calorie target and save (other macros left blank). */
    fun setCalorieTarget(calories: Int) {
        val input = driver.findElement(By.cssSelector("[data-testid=macro-target-caloriesKcal]"))
        input.clear()
        input.sendKeys(calories.toString())
        driver.findElement(By.cssSelector("[data-testid=targets-save]")).click()
        // Save re-enables once the write resolves.
        wait.until(
            ExpectedConditions.elementToBeClickable(By.cssSelector("[data-testid=targets-save]")),
        )
    }
}
