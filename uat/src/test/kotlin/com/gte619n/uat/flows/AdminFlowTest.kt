package com.gte619n.uat.flows

import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions

/**
 * Flow #10 — admin: the admin area is gated by email allow-list. An admin
 * identity (admin@uat.local, granted via ADMIN_EMAILS in uat.sh) reaches the
 * equipment catalog; a normal user is redirected away. (The drug catalog is
 * intentionally unreachable in UAT — its backend controller is AI-gated off.)
 */
class AdminFlowTest : BaseUatTest() {

    @Test
    fun adminReachesEquipmentCatalog() {
        signIn(TestUsers.admin(), callbackUrl = "/admin/equipment/catalog")
        wait.until(ExpectedConditions.urlContains("/admin/equipment/catalog"))
        assertTrue(driver.currentUrl!!.contains("/admin/equipment/catalog"))
        // Admin chrome (sub-nav) renders.
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("body"), "Equipment"))
    }

    @Test
    fun nonAdminIsRedirectedAwayFromAdmin() {
        val user = TestUsers.fresh("notadmin")
        signIn(user, callbackUrl = "/admin/equipment/catalog")
        // requireAdmin() redirects non-admins to "/".
        wait.until { !it.currentUrl!!.contains("/admin") }
        assertFalse(driver.currentUrl!!.contains("/admin"))
    }
}
