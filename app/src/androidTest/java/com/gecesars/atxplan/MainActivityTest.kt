package com.gecesars.atxplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardShowsEngineeringEntryPoint() {
        composeRule.onNodeWithText("Engineering Center").assertIsDisplayed()
        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Link Budget").assertIsDisplayed()
    }
}
