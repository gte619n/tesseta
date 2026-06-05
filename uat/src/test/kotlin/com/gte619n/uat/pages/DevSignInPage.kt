package com.gte619n.uat.pages

import com.gte619n.uat.support.TestUser
import com.gte619n.uat.support.UatConfig
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/** Drives the flag-gated /auth/dev page — the Selenium entry point that
 *  replaces Google OAuth in UAT. */
class DevSignInPage(private val driver: WebDriver) {
    private val wait = WebDriverWait(driver, Duration.ofSeconds(15))

    fun signIn(user: TestUser, callbackUrl: String = "/") {
        driver.get("${UatConfig.webBaseUrl}/auth/dev?callbackUrl=$callbackUrl")
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=dev-signin-form]")))
        fill("dev-userId", user.userId)
        fill("dev-email", user.email)
        fill("dev-name", user.name)
        driver.findElement(By.cssSelector("[data-testid=dev-signin-submit]")).click()
        // Auth.js sets the session cookie then redirects off /auth/dev.
        wait.until { !it.currentUrl!!.contains("/auth/") }
    }

    private fun fill(testId: String, value: String) {
        val el = driver.findElement(By.cssSelector("[data-testid=$testId]"))
        el.clear()
        el.sendKeys(value)
    }
}
