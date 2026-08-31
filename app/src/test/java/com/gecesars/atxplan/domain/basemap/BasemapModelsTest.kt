package com.gecesars.atxplan.domain.basemap

import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import com.gecesars.atxplan.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapModelsTest {
    @Test
    fun `visible planner selects density-aware zoom without prefetch padding`() {
        val provider = provider()
        val plan = BasemapTilePlanner.planVisibleTiles(
            provider = provider,
            camera = GeographicCamera(GeoPoint(-23.55052, -46.633308), zoom = 8.0),
            viewport = ViewportSizePx(width = 1_080.0, height = 900.0),
            displayTileSizePx = 832.0,
        )

        assertEquals(10, plan.tileZoom)
        assertTrue(plan.coordinates.isNotEmpty())
        assertTrue(plan.coordinates.size <= MAX_VISIBLE_TILE_COUNT)
        assertEquals(plan.coordinates.size, plan.coordinates.distinct().size)
        assertTrue(plan.coordinates.all { tile -> tile.zoom == plan.tileZoom })
    }

    @Test
    fun `provider rejects credentials fragments insecure schemes and malformed templates`() {
        listOf(
            "http://tiles.example.test/{z}/{x}/{y}.png",
            "https://user:secret@tiles.example.test/{z}/{x}/{y}.png",
            "https://tiles.example.test/{z}/{x}/{y}.png#fragment",
            "https://tiles.example.test/{z}/{x}.png",
            "https://tiles.example.test/{z}/{x}/{y}/{y}.png",
        ).forEach { template ->
            val result = runCatching { provider(template) }
            assertTrue("Expected rejection for $template", result.isFailure)
        }
    }

    @Test
    fun `tile coordinates cannot escape their xyz matrix`() {
        assertTrue(runCatching { BasemapTileCoordinate(4, x = -1, y = 2) }.isFailure)
        assertTrue(runCatching { BasemapTileCoordinate(4, x = 16, y = 2) }.isFailure)
        assertTrue(runCatching { BasemapTileCoordinate(4, x = 2, y = 16) }.isFailure)
        assertEquals(BasemapTileCoordinate(4, 2, 3), BasemapTileCoordinate(4, 2, 3))
    }

    private fun provider(
        template: String = "https://tiles.example.test/{z}/{x}/{y}.png",
    ) = RasterBasemapProvider(
        id = "test-provider",
        label = "Test Provider",
        tileUrlTemplate = template,
        attribution = "Test attribution",
        attributionUrl = "https://tiles.example.test/attribution",
        termsUrl = "https://tiles.example.test/terms",
        usageNotice = "Interactive viewport tests only.",
        maximumZoom = 19,
    )
}
