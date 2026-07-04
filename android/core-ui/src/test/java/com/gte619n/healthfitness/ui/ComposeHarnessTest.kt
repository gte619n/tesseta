package com.gte619n.healthfitness.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves Compose UI tests run on the JVM under Robolectric (no device), so they
 * gate in CI via testDebugUnitTest. Uses a self-contained composable to verify
 * the harness — feature/screen UI tests can now follow this pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeHarnessTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersAndRespondsToClicks() {
        composeRule.setContent {
            var count by remember { mutableStateOf(0) }
            Button(onClick = { count++ }) { Text("count: $count") }
        }

        composeRule.onNodeWithText("count: 0").assertIsDisplayed()
        composeRule.onNodeWithText("count: 0").performClick()
        composeRule.onNodeWithText("count: 1").assertIsDisplayed()
    }
}
