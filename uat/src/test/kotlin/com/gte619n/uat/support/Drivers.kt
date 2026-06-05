package com.gte619n.uat.support

import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import java.io.File
import java.time.Duration

/** Headless Chrome factory. Selenium 4.6+ Selenium Manager auto-provisions a
 *  matching chromedriver, so there's no driver binary to manage here. */
object Drivers {
    fun chrome(): ChromeDriver {
        val options = ChromeOptions().apply {
            if (UatConfig.headless) addArguments("--headless=new")
            addArguments("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1400,1600")
        }
        return ChromeDriver(options).apply {
            manage().timeouts().implicitlyWait(Duration.ofSeconds(2))
        }
    }

    /** Dump a PNG into uat/build/reports/screenshots for post-mortem on failure. */
    fun screenshot(driver: ChromeDriver, name: String) {
        val dir = File("build/reports/screenshots").apply { mkdirs() }
        val png = (driver as TakesScreenshot).getScreenshotAs(OutputType.FILE)
        png.copyTo(File(dir, "$name.png"), overwrite = true)
    }
}
