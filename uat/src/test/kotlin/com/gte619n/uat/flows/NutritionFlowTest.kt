package com.gte619n.uat.flows

import com.gte619n.uat.pages.NutritionPage
import com.gte619n.uat.support.BaseUatTest
import com.gte619n.uat.support.TestUsers
import org.junit.jupiter.api.Test

/** Flow #5 — nutrition: set a daily calorie target through the UI and confirm it
 *  persisted (catalog-backed food entry needs a seeded catalog, out of scope). */
class NutritionFlowTest : BaseUatTest() {

    @Test
    fun setMacroTargetPersists() {
        val user = TestUsers.fresh("nutrition")
        signIn(user, callbackUrl = "/me/nutrition/target")

        val nutrition = NutritionPage(driver)
        nutrition.openTargets()
        nutrition.setCalorieTarget(2350)

        val token = backend.devLogin(user.userId, user.email, user.name)
        eventually {
            backend.getJson("/api/me/nutrition/target", token).get("caloriesKcal")?.asInt() == 2350
        }
    }
}
