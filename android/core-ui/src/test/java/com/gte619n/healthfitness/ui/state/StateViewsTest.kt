package com.gte619n.healthfitness.ui.state

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A real shared-primitive UI test (not just the harness): ErrorState renders its
 * message and invokes onRetry when the Retry button is pressed. The Hf design
 * tokens fall back to their composition-local defaults, so no theme wrapper is
 * needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StateViewsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun errorStateShowsMessageAndRetries() {
        var retried = false
        composeRule.setContent {
            ErrorState(message = "Couldn't load data", onRetry = { retried = true })
        }

        composeRule.onNodeWithText("Couldn't load data").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue("Retry should invoke onRetry", retried)
    }

    @Test
    fun emptyStateShowsTitleAndDescription() {
        composeRule.setContent {
            EmptyState(title = "Nothing here yet", description = "Add your first entry.")
        }

        composeRule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        composeRule.onNodeWithText("Add your first entry.").assertIsDisplayed()
    }
}
