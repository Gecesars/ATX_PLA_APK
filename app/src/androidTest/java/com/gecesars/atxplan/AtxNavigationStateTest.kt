package com.gecesars.atxplan

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.ui.navigation.AtxRoute
import com.gecesars.atxplan.ui.navigation.DashboardRoute
import com.gecesars.atxplan.ui.navigation.RF_PATH_EDITOR_PREFIX
import com.gecesars.atxplan.ui.navigation.RfPathEditorRoute
import com.gecesars.atxplan.ui.navigation.StudiesRoute
import com.gecesars.atxplan.ui.navigation.UnsupportedRoute
import com.gecesars.atxplan.ui.navigation.activeRoute
import com.gecesars.atxplan.ui.navigation.rememberAtxNavBackStack
import com.gecesars.atxplan.ui.navigation.replaceTopLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtxNavigationStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typedBackStackSurvivesSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle { backStack.replaceTopLevel(StudiesRoute) }
        composeRule.onNodeWithText(routeMarker(StudiesRoute)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(routeMarker(StudiesRoute)).assertIsDisplayed()
    }

    @Test
    fun unknownRouteIdentifierFallsBackToDashboard() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.clear()
            backStack.add(AtxRoute.fromStableId("future-feature:v9"))
        }
        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(backStack.last() is UnsupportedRoute)
            assertEquals("future-feature:v9", backStack.last().stableId)
        }
    }

    @Test
    fun nestedEditorRouteSurvivesSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(RfPathEditorRoute("project-123"))
        }
        composeRule.onNodeWithText("Current route: ${RF_PATH_EDITOR_PREFIX}project-123")
            .assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Current route: ${RF_PATH_EDITOR_PREFIX}project-123")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            val route = backStack.last() as RfPathEditorRoute
            assertEquals("project-123", route.projectId)
        }
    }

    @Test
    fun malformedNestedRouteFallsBackWithoutEnteringTheBackStack() {
        lateinit var backStack: NavBackStack<AtxRoute>
        composeRule.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(AtxRoute.fromStableId(RF_PATH_EDITOR_PREFIX))
        }

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(DashboardRoute, backStack.last()) }
    }

    private fun routeMarker(route: AtxRoute) = "Current route: ${route.stableId}"
}
