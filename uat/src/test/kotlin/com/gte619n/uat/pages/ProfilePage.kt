package com.gte619n.uat.pages

import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** /me/profile — height edit. Works in either unit mode (cm or ft+in); returns
 *  the height-in-cm the form will persist so the test can assert the round-trip. */
class ProfilePage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun open() {
        driver.get("${UatConfig.webBaseUrl}/me/profile")
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=height-save]")))
    }

    /** Set height to a known value and Save; returns the expected stored cm. */
    fun setHeightAndSave(): Int {
        val cmInputs = driver.findElements(By.cssSelector("[data-testid=height-cm]"))
        val expectedCm: Int
        if (cmInputs.isNotEmpty()) {
            type("height-cm", "188")
            expectedCm = 188
        } else {
            type("height-ft", "6")
            type("height-in", "2") // 6'2" == 187.96cm -> rounds to 188
            expectedCm = 188
        }
        driver.findElement(By.cssSelector("[data-testid=height-save]")).click()
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=height-saved]")))
        return expectedCm
    }

    private fun type(testId: String, value: String) {
        val el = driver.findElement(By.cssSelector("[data-testid=$testId]"))
        el.clear()
        el.sendKeys(value)
    }
}
