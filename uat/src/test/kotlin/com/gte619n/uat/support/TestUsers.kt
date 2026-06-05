package com.gte619n.uat.support

import java.util.concurrent.atomic.AtomicInteger

/** A test identity. `admin` users carry the email the backend's ADMIN_EMAILS
 *  allow-lists in UAT (admin@uat.local). */
data class TestUser(val userId: String, val email: String, val name: String, val admin: Boolean = false)

/**
 * Per-test unique users so data never collides — each test owns its own
 * `users/{userId}` document tree in the emulator, so tests are isolated without
 * a per-test reset. The suite-level wipe handles cross-run cleanliness.
 */
object TestUsers {
    private val counter = AtomicInteger(0)

    fun fresh(label: String): TestUser {
        val id = "uat-${label.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${counter.incrementAndGet()}"
        return TestUser(id, "$id@uat.local", label)
    }

    /** The admin identity — email must be in the backend's ADMIN_EMAILS. */
    fun admin(): TestUser = TestUser("uat-admin", "admin@uat.local", "UAT Admin", admin = true)
}
