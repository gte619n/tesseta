package com.gte619n.uat.support

import com.gte619n.uat.pages.DevSignInPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.openqa.selenium.By
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

/**
 * Shared lifecycle for every UAT flow: one fresh headless Chrome per test, a
 * suite-level emulator wipe, and a `signIn` helper that drives the dev page.
 * Flows extend this and assert on the rendered UI.
 */
abstract class BaseUatTest {
    protected lateinit var driver: ChromeDriver
    protected val backend = UatBackendClient()
    protected lateinit var wait: WebDriverWait

    companion object {
        @JvmStatic
        @BeforeAll
        fun wipeOnce() {
            // Clean slate for the suite. Per-test isolation is by unique userId.
            UatBackendClient().wipeEmulator()
        }
    }

    @BeforeEach
    fun setUp() {
        driver = Drivers.chrome()
        wait = WebDriverWait(driver, Duration.ofSeconds(15))
    }

    @AfterEach
    fun tearDown(info: TestInfo) {
        runCatching {
            if (::driver.isInitialized) {
                // Capture a screenshot for any failed test (best-effort).
                Drivers.screenshot(driver, info.displayName.replace(Regex("[^A-Za-z0-9._-]+"), "_"))
            }
        }
        if (::driver.isInitialized) driver.quit()
    }

    /** Sign in through the dev page and land on the app. */
    protected fun signIn(user: TestUser, callbackUrl: String = "/") {
        DevSignInPage(driver).signIn(user, callbackUrl)
    }

    /** Navigate to an app path and wait for the document to settle. */
    protected fun goto(path: String) {
        driver.get(UatConfig.webBaseUrl.trimEnd('/') + path)
    }

    protected fun waitForTestId(testId: String) {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-testid=$testId]")))
    }

    /** Poll a backend assertion until it holds (UI writes settle async). */
    protected fun eventually(timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = false
        while (System.nanoTime() < deadline) {
            last = runCatching(check).getOrDefault(false)
            if (last) return
            Thread.sleep(250)
        }
        if (!last) throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
