package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/blood — add a manual reading via the modal (marker defaults to the first
 *  in the list, date defaults to today). */
class BloodPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun open() {
        driver.get("${UatConfig.webBaseUrl}/me/blood")
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=add-reading-btn]")))
    }

    fun addReading(value: String) {
        driver.findElement(By.cssSelector("[data-testid=add-reading-btn]")).click()
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=reading-value]")))
        driver.findElement(By.cssSelector("[data-testid=reading-value]")).sendKeys(value)
        driver.findElement(By.cssSelector("[data-testid=add-reading-submit]")).click()
        // Modal closes once the write completes.
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("[data-testid=reading-value]")))
    }
}
