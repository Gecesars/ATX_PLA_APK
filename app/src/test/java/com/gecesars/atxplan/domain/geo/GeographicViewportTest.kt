package com.gecesars.atxplan.domain.geo

import com.gecesars.atxplan.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeographicViewportTest {
    @Test
    fun `origin uses the center of the normalized Mercator world`() {
        val projected = GeographicViewport.project(GeoPoint(0.0, 0.0))

        assertEquals(0.5, projected.x, STRICT_TOLERANCE)
        assertEquals(0.5, projected.y, STRICT_TOLERANCE)
        assertGeoPointEquals(
            expected = GeoPoint(0.0, 0.0),
            actual = GeographicViewport.unproject(projected),
        )
    }

    @Test
    fun `projection round trip retains practical planning coordinates`() {
        listOf(
            GeoPoint(-23.550_520, -46.633_308),
            GeoPoint(51.507_351, -0.127_758),
            GeoPoint(-33.868_820, 151.209_296),
            GeoPoint(64.146_582, -21.942_635),
        ).forEach { point ->
            assertGeoPointEquals(
                expected = point,
                actual = GeographicViewport.unproject(GeographicViewport.project(point)),
            )
        }
    }

    @Test
    fun `projection clamps polar coordinates and treats both antimeridian values as one meridian`() {
        val northPole = GeographicViewport.unproject(
            GeographicViewport.project(GeoPoint(90.0, 25.0)),
        )
        val southPole = GeographicViewport.unproject(
            GeographicViewport.project(GeoPoint(-90.0, 25.0)),
        )
        val westEdge = GeographicViewport.project(GeoPoint(0.0, -180.0))
        val eastEdge = GeographicViewport.project(GeoPoint(0.0, 180.0))

        assertEquals(WEB_MERCATOR_MAX_LATITUDE_DEGREES, northPole.latitude, GEO_TOLERANCE)
        assertEquals(-WEB_MERCATOR_MAX_LATITUDE_DEGREES, southPole.latitude, GEO_TOLERANCE)
        assertEquals(westEdge, eastEdge)
        assertEquals(0.0, eastEdge.x, STRICT_TOLERANCE)
    }

    @Test
    fun `canonical display point clamps a pole without changing the stored coordinate`() {
        val storedPoint = GeoPoint(latitude = 90.0, longitude = 180.0)

        val displayPoint = GeographicViewport.canonicalPoint(storedPoint)

        assertEquals(90.0, storedPoint.latitude, STRICT_TOLERANCE)
        assertEquals(180.0, storedPoint.longitude, STRICT_TOLERANCE)
        assertEquals(WEB_MERCATOR_MAX_LATITUDE_DEGREES, displayPoint.latitude, GEO_TOLERANCE)
        assertEquals(-180.0, displayPoint.longitude, STRICT_TOLERANCE)
    }

    @Test
    fun `screen conversion takes the shortest path across the antimeridian`() {
        val camera = GeographicCamera(center = GeoPoint(0.0, 179.5), zoom = 4.0)
        val viewport = ViewportSizePx(width = 800.0, height = 500.0)
        val point = GeoPoint(1.25, -179.5)

        val screen = GeographicViewport.toScreen(point, camera, viewport)
        val restored = GeographicViewport.fromScreen(screen, camera, viewport)

        assertTrue("The nearby point must remain near the viewport center.", screen.x in 400.0..420.0)
        assertGeoPointEquals(point, restored)
    }

    @Test
    fun `positive drag moves content with the pointer and pans the camera in the opposite direction`() {
        val original = GeographicCamera(center = GeoPoint(0.0, 0.0), zoom = 3.0)
        val viewport = ViewportSizePx(width = 600.0, height = 400.0)
        val dragged = GeographicViewport.panBy(original, deltaXpx = 128.0, deltaYpx = 128.0)
        val oldCenterAfterDrag = GeographicViewport.toScreen(original.center, dragged, viewport)

        assertEquals(viewport.width / 2.0 + 128.0, oldCenterAfterDrag.x, SCREEN_TOLERANCE)
        assertEquals(viewport.height / 2.0 + 128.0, oldCenterAfterDrag.y, SCREEN_TOLERANCE)
        assertTrue(dragged.center.longitude < original.center.longitude)
        assertTrue(dragged.center.latitude > original.center.latitude)
    }

    @Test
    fun `zoom keeps an off-center anchor on the same geographic point`() {
        val original = GeographicCamera(center = GeoPoint(-23.55, 179.8), zoom = 5.0)
        val viewport = ViewportSizePx(width = 900.0, height = 600.0)
        val anchor = ScreenPointPx(x = 720.0, y = 170.0)
        val anchorBefore = GeographicViewport.fromScreen(anchor, original, viewport)

        val zoomed = GeographicViewport.zoomBy(
            camera = original,
            zoomFactor = 3.5,
            anchor = anchor,
            viewport = viewport,
        )
        val anchorAfter = GeographicViewport.fromScreen(anchor, zoomed, viewport)

        assertGeoPointEquals(anchorBefore, anchorAfter)
        assertEquals(original.zoom + kotlin.math.log2(3.5), zoomed.zoom, STRICT_TOLERANCE)
    }

    @Test
    fun `zoom respects caller bounds and returns the same camera at a reached bound`() {
        val camera = GeographicCamera(center = GeoPoint(0.0, 0.0), zoom = 8.0)
        val viewport = ViewportSizePx(width = 500.0, height = 500.0)
        val anchor = ScreenPointPx(250.0, 250.0)

        val maximum = GeographicViewport.zoomBy(
            camera = camera,
            zoomFactor = 1_000_000.0,
            anchor = anchor,
            viewport = viewport,
            minZoom = 5.0,
            maxZoom = 10.0,
        )
        val unchanged = GeographicViewport.zoomBy(
            camera = maximum,
            zoomFactor = 2.0,
            anchor = anchor,
            viewport = viewport,
            minZoom = 5.0,
            maxZoom = 10.0,
        )

        assertEquals(10.0, maximum.zoom, STRICT_TOLERANCE)
        assertSame(maximum, unchanged)
    }

    @Test
    fun `fit camera keeps a regional project inside its padded viewport`() {
        val sites = listOf(
            GeoPoint(-23.681, -46.825),
            GeoPoint(-23.352, -46.426),
            GeoPoint(-23.482, -46.511),
        )
        val viewport = ViewportSizePx(width = 1_000.0, height = 700.0)
        val padding = 70.0

        val camera = GeographicViewport.fitCamera(
            points = sites,
            viewport = viewport,
            paddingPx = padding,
            maxZoom = 18.0,
        )

        sites.forEach { site ->
            val screen = GeographicViewport.toScreen(site, camera, viewport)
            assertTrue(screen.x >= padding - SCREEN_TOLERANCE)
            assertTrue(screen.x <= viewport.width - padding + SCREEN_TOLERANCE)
            assertTrue(screen.y >= padding - SCREEN_TOLERANCE)
            assertTrue(screen.y <= viewport.height - padding + SCREEN_TOLERANCE)
        }
    }

    @Test
    fun `fit camera chooses the narrow extent across the antimeridian`() {
        val sites = listOf(
            GeoPoint(-17.0, 179.0),
            GeoPoint(-16.5, -179.0),
        )
        val viewport = ViewportSizePx(width = 800.0, height = 600.0)

        val camera = GeographicViewport.fitCamera(
            points = sites,
            viewport = viewport,
            paddingPx = 50.0,
            maxZoom = 18.0,
        )

        assertTrue(abs(abs(camera.center.longitude) - 180.0) < GEO_TOLERANCE)
        assertTrue("A two-degree extent should fit above global zoom levels.", camera.zoom > 7.0)
        sites.forEach { site ->
            val screen = GeographicViewport.toScreen(site, camera, viewport)
            assertTrue(screen.x in 50.0 - SCREEN_TOLERANCE..750.0 + SCREEN_TOLERANCE)
        }
    }

    @Test
    fun `single point fit uses maximum zoom and keeps its center`() {
        val point = GeoPoint(-23.550_52, -46.633_308)

        val camera = GeographicViewport.fitCamera(
            points = listOf(point),
            viewport = ViewportSizePx(640.0, 480.0),
            paddingPx = 48.0,
            minZoom = 3.0,
            maxZoom = 17.0,
        )

        assertGeoPointEquals(point, camera.center)
        assertEquals(17.0, camera.zoom, STRICT_TOLERANCE)
    }

    @Test
    fun `meters per pixel follows Web Mercator ground resolution`() {
        val equator = GeographicViewport.metersPerPixel(latitudeDegrees = 0.0, zoom = 0.0)
        val sixtyDegrees = GeographicViewport.metersPerPixel(latitudeDegrees = 60.0, zoom = 0.0)

        assertEquals(156_543.033_928, equator, 0.000_001)
        assertEquals(equator / 2.0, sixtyDegrees, 0.000_001)
        assertEquals(
            equator / 1_024.0,
            GeographicViewport.metersPerPixel(latitudeDegrees = 0.0, zoom = 10.0),
            0.000_001,
        )
    }

    @Test
    fun `scale bar selects a conventional distance inside the available width`() {
        val camera = GeographicCamera(center = GeoPoint(-23.55, -46.63), zoom = 13.0)

        val scale = GeographicViewport.scaleBar(camera = camera, maxWidthPx = 140.0)

        assertEquals(2_000.0, scale.distanceMeters, STRICT_TOLERANCE)
        assertTrue(scale.widthPx > 0.0)
        assertTrue(scale.widthPx <= 140.0)
    }

    @Test
    fun `invalid camera viewport gesture fit and scale inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicCamera(center = GeoPoint(0.0, 0.0), zoom = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicCamera(center = GeoPoint(0.0, 0.0), zoom = 25.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ViewportSizePx(width = 0.0, height = 100.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenPointPx(x = Double.POSITIVE_INFINITY, y = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.panBy(
                camera = GeographicCamera(GeoPoint(0.0, 0.0), 2.0),
                deltaXpx = Double.NaN,
                deltaYpx = 0.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.zoomBy(
                camera = GeographicCamera(GeoPoint(0.0, 0.0), 2.0),
                zoomFactor = 0.0,
                anchor = ScreenPointPx(10.0, 10.0),
                viewport = ViewportSizePx(100.0, 100.0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.fitCamera(
                points = emptyList(),
                viewport = ViewportSizePx(100.0, 100.0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.fitCamera(
                points = listOf(GeoPoint(0.0, 0.0)),
                viewport = ViewportSizePx(100.0, 100.0),
                paddingPx = 50.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.metersPerPixel(latitudeDegrees = 91.0, zoom = 2.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.worldSizePx(zoom = 24.0, tileSizePx = Double.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicViewport.scaleBar(
                camera = GeographicCamera(GeoPoint(0.0, 0.0), 2.0),
                maxWidthPx = Double.POSITIVE_INFINITY,
            )
        }
    }

    private fun assertGeoPointEquals(expected: GeoPoint, actual: GeoPoint) {
        assertEquals(expected.latitude, actual.latitude, GEO_TOLERANCE)
        assertEquals(expected.longitude, actual.longitude, GEO_TOLERANCE)
    }

    private companion object {
        const val STRICT_TOLERANCE = 1e-12
        const val GEO_TOLERANCE = 1e-9
        const val SCREEN_TOLERANCE = 1e-6
    }
}
