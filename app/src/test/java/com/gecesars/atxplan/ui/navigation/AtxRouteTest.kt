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
    fun `project rename route preserves a bounded project ID`() {
        val route = ProjectRenameRoute("project:alpha-123")

        val restored = roundTrip(route) as ProjectRenameRoute

        assertEquals("project:alpha-123", restored.projectId)
        assertEquals("${PROJECT_RENAME_PREFIX}project:alpha-123", restored.stableId)
        assertEquals(route, AtxRoute.projectRename("project:alpha-123"))
    }

    @Test
    fun `RF assets route preserves a bounded project ID`() {
        val route = RfAssetsRoute("project:alpha-123")

        val restored = roundTrip(route) as RfAssetsRoute

        assertEquals("project:alpha-123", restored.projectId)
        assertEquals("${RF_ASSETS_PREFIX}project:alpha-123", restored.stableId)
        assertEquals(route, AtxRoute.rfAssets("project:alpha-123"))
    }

    @Test
    fun `antenna pattern route preserves a bounded project ID`() {
        val route = AntennaPatternsRoute("project:alpha-123")

        val restored = roundTrip(route) as AntennaPatternsRoute

        assertEquals("project:alpha-123", restored.projectId)
        assertEquals("${ANTENNA_PATTERNS_PREFIX}project:alpha-123", restored.stableId)
        assertEquals(route, AtxRoute.antennaPatterns("project:alpha-123"))
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
        val malformedRenameStableIds = listOf(
            PROJECT_RENAME_PREFIX,
            "${PROJECT_RENAME_PREFIX}   ",
            "${PROJECT_RENAME_PREFIX} project",
            "$PROJECT_RENAME_PREFIX${"x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)}",
            "$PROJECT_RENAME_PREFIX${"ok"}\u0000",
        )
        malformedRenameStableIds.forEach { stableId ->
            assertSame(DashboardRoute, AtxRoute.fromStableId(stableId).supportedOrDashboard())
        }
        val malformedRfAssetsStableIds = listOf(
            RF_ASSETS_PREFIX,
            "${RF_ASSETS_PREFIX}   ",
            "${RF_ASSETS_PREFIX} project",
            "$RF_ASSETS_PREFIX${"x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)}",
            "$RF_ASSETS_PREFIX${"ok"}\u0000",
        )
        malformedRfAssetsStableIds.forEach { stableId ->
            assertSame(DashboardRoute, AtxRoute.fromStableId(stableId).supportedOrDashboard())
        }
        val malformedAntennaStableIds = listOf(
            ANTENNA_PATTERNS_PREFIX,
            "${ANTENNA_PATTERNS_PREFIX}   ",
            "${ANTENNA_PATTERNS_PREFIX} project",
            "$ANTENNA_PATTERNS_PREFIX${"x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)}",
            "$ANTENNA_PATTERNS_PREFIX${"ok"}\u0000",
        )
        malformedAntennaStableIds.forEach { stableId ->
            assertSame(DashboardRoute, AtxRoute.fromStableId(stableId).supportedOrDashboard())
        }
        assertSame(DashboardRoute, AtxRoute.rfPathEditor(null))
        assertSame(DashboardRoute, AtxRoute.rfPathEditor(""))
        assertSame(DashboardRoute, AtxRoute.projectRename(null))
        assertSame(DashboardRoute, AtxRoute.projectRename(""))
        assertSame(DashboardRoute, AtxRoute.rfAssets(null))
        assertSame(DashboardRoute, AtxRoute.rfAssets(""))
        assertSame(DashboardRoute, AtxRoute.antennaPatterns(null))
        assertSame(DashboardRoute, AtxRoute.antennaPatterns(""))
        assertSame(
            DashboardRoute,
            RfPathEditorRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)).supportedOrDashboard(),
        )
        assertSame(
            DashboardRoute,
            ProjectRenameRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1))
                .supportedOrDashboard(),
        )
        assertSame(
            DashboardRoute,
            RfAssetsRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1))
                .supportedOrDashboard(),
        )
        assertSame(
            DashboardRoute,
            AntennaPatternsRoute("x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1))
                .supportedOrDashboard(),
        )
    }

    @Test
    fun `invalid constructed nested route serializes as Dashboard without truncating an ID`() {
        val oversizedProjectId = "x".repeat(MAX_RF_PATH_PROJECT_ID_LENGTH + 1)

        assertSame(DashboardRoute, roundTrip(RfPathEditorRoute(oversizedProjectId)))
        assertSame(DashboardRoute, roundTrip(ProjectRenameRoute(oversizedProjectId)))
        assertSame(DashboardRoute, roundTrip(RfAssetsRoute(oversizedProjectId)))
        assertSame(DashboardRoute, roundTrip(AntennaPatternsRoute(oversizedProjectId)))
    }

    private fun roundTrip(route: AtxRoute): AtxRoute =
        Json.decodeFromString(
            routeSerializer,
            Json.encodeToString(routeSerializer, route),
        )
}
