package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/meds — add a custom medication (no catalog/AI lookup). */
class MedicationsPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun open() {
        driver.get("${UatConfig.webBaseUrl}/me/meds")
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=meds-add-btn]")))
    }

    /** Add via the custom-entry path. We type only 2 chars in search (the
     *  manual-entry affordance appears, but the 3-char AI lookup never fires). */
    fun addCustomMedication(name: String, dose: String) {
        driver.findElement(By.cssSelector("[data-testid=meds-add-btn]")).click()
        val search = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=meds-search-input]")),
        )
        search.sendKeys("zz") // 2 chars: reveals "Add manually instead", no AI lookup
        val manual = wait.until(
            ExpectedConditions.elementToBeClickable(By.cssSelector("[data-testid=meds-manual-btn]")),
        )
        manual.click()
        val nameInput = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=meds-custom-name]")),
        )
        nameInput.clear()
        nameInput.sendKeys(name)
        driver.findElement(By.cssSelector("[data-testid=meds-custom-dose]")).sendKeys(dose)
        driver.findElement(By.cssSelector("[data-testid=meds-custom-submit]")).click()
        // Modal closes on success.
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("[data-testid=meds-custom-name]")))
    }
}
