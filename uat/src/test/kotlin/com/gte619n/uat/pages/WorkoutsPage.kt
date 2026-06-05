package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/workouts/gyms — create a gym (name only; defaults otherwise). */
class WorkoutsPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun openNewGym() {
        driver.get("${UatConfig.webBaseUrl}/me/workouts/gyms/new")
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=gym-name]")))
    }

    fun createGym(name: String) {
        driver.findElement(By.cssSelector("[data-testid=gym-name]")).sendKeys(name)
        driver.findElement(By.cssSelector("[data-testid=gym-save]")).click()
        // The form navigates off /new once the gym is created.
        wait.until { !it.currentUrl!!.endsWith("/new") }
    }

    fun openGymList() {
        driver.get("${UatConfig.webBaseUrl}/me/workouts/gyms")
    }
}
