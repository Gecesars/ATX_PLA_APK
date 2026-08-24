package com.gecesars.atxplan.ui.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AtxRouteTest {
    private val routeSerializer = serializer<AtxRoute>()

    @Test
    fun `known top-level routes retain their stable wire identifiers`() {
        val routes = listOf(
            DashboardRoute,
            ProjectsRoute,
            MapRoute,
            StudiesRoute,
            CatalogRoute,
        )

        routes.forEach { route -> assertEquals(route, roundTrip(route)) }
    }

    @Test
    fun `unknown future identifier round trips without polymorphic failure`() {
        val decoded = Json.decodeFromString(routeSerializer, "\"future-feature:v9\"")

        assertTrue(decoded is UnsupportedRoute)
        assertEquals("future-feature:v9", decoded.stableId)
        assertSame(DashboardRoute, decoded.supportedOrDashboard())

        val restored = roundTrip(decoded)
        assertTrue(restored is UnsupportedRoute)
        assertEquals("future-feature:v9", restored.stableId)
        assertSame(DashboardRoute, restored.supportedOrDashboard())
    }

    @Test
    fun `nested editor route preserves a bounded project ID`() {
        val route = RfPathEditorRoute("project:alpha-123")

        val restored = roundTrip(route) as RfPathEditorRoute

        assertEquals("project:alpha-123", restored.projectId)
        assertEquals("${RF_PATH_EDITOR_PREFIX}project:alpha-123", restored.stableId)
    }

    @Test
    fun `malformed nested identifiers fall back safely`() {
        val malformedStableIds = listOf(
            RF_PATH_EDITOR_PREFIX,
            "${RF_PATH_EDITOR_PREFIX}   ",
            "${RF_PATH_EDITOR_PREFIX} project",
            "$RF_PATH_EDITOR_PREFIX${"x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)}",
            "$RF_PATH_EDITOR_PREFIX${"ok"}\u0000",
        )

        malformedStableIds.forEach { stableId ->
            assertSame(DashboardRoute, AtxRoute.fromStableId(stableId).supportedOrDashboard())
        }
        assertSame(DashboardRoute, AtxRoute.rfPathEditor(null))
        assertSame(DashboardRoute, AtxRoute.rfPathEditor(""))
        assertSame(
            DashboardRoute,
            RfPathEditorRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)).supportedOrDashboard(),
        )
    }

    @Test
    fun `invalid constructed nested route serializes as Dashboard without truncating an ID`() {
        val invalidRoute = RfPathEditorRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1))

        assertSame(DashboardRoute, roundTrip(invalidRoute))
    }

    private fun roundTrip(route: AtxRoute): AtxRoute =
        Json.decodeFromString(
            routeSerializer,
            Json.encodeToString(routeSerializer, route),
        )
}
