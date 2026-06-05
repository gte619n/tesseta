package com.gte619n.uat.flows

import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import com.gte619n.uat.support.UatConfig
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/** Flow #8 — body composition: with no Google Health connection (the UAT state),
 *  the page must render the "Connect Google Health" call-to-action rather than
 *  erroring. (Reading real body-comp data needs a live Google Health link.) */
class BodyCompositionFlowTest : BaseUatTest() {

    @Test
    fun showsConnectGoogleHealthWhenNotConnected() {
        val user = TestUsers.fresh("bodycomp")
        signIn(user, callbackUrl = "/me/body-composition")
        driver.get("${UatConfig.webBaseUrl}/me/body-composition")
        wait.until(
            ExpectedConditions.textToBePresentInElementLocated(
                By.tagName("body"),
                "Connect Google Health",
            ),
        )
    }
}
