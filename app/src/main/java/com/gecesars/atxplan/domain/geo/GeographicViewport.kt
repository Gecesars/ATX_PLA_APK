package com.gecesars.atxplan.domain.geo

import com.gecesars.atxplan.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

const val WEB_MERCATOR_MAX_LATITUDE_DEGREES = 85.0511287798066
const val MIN_GEOGRAPHIC_ZOOM = 0.0
const val MAX_GEOGRAPHIC_ZOOM = 24.0
const val DEFAULT_MERCATOR_TILE_SIZE_PX = 256.0

/** A finite point in the repeating, normalized Web Mercator world. */
data class MercatorWorldPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "A Mercator world point requires finite coordinates."
        }
    }
}

data class ScreenPointPx(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "A screen point requires finite coordinates."
        }
    }
}

data class ViewportSizePx(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) {
            "A viewport requires positive, finite dimensions."
        }
    }
}

data class GeographicCamera(
    val center: GeoPoint,
    val zoom: Double,
) {
    init {
        require(zoom.isFinite() && zoom in MIN_GEOGRAPHIC_ZOOM..MAX_GEOGRAPHIC_ZOOM) {
            "Geographic zoom must be between $MIN_GEOGRAPHIC_ZOOM and $MAX_GEOGRAPHIC_ZOOM."
        }
    }
}

data class GeographicScaleBar(
    val distanceMeters: Double,
    val widthPx: Double,
) {
    init {
        require(
            distanceMeters.isFinite() && distanceMeters > 0.0 &&
                widthPx.isFinite() && widthPx > 0.0,
        ) {
            "A geographic scale bar requires a positive distance and width."
        }
    }
}

/**
 * Pure Web Mercator camera math for the offline geographic canvas.
 *
 * Longitude repeats at the antimeridian. Latitude is clamped only for projection because Web
 * Mercator cannot represent either pole; stored [GeoPoint] values remain unchanged.
 */
object GeographicViewport {
    /**
     * Returns the exact Web Mercator point used for display without changing the stored source.
     * Polar latitude is clamped and +180 longitude is normalized to the equivalent -180 meridian.
     */
    fun canonicalPoint(point: GeoPoint): GeoPoint = unproject(project(point))

    fun project(point: GeoPoint): MercatorWorldPoint {
        val latitude = point.latitude.coerceIn(
            -WEB_MERCATOR_MAX_LATITUDE_DEGREES,
            WEB_MERCATOR_MAX_LATITUDE_DEGREES,
        )
        val latitudeRadians = Math.toRadians(latitude)
        val x = wrapUnit((point.longitude + 180.0) / 360.0)
        val y = (
            1.0 - kotlin.math.ln(
                tan(latitudeRadians) + (1.0 / cos(latitudeRadians)),
            ) / PI
            ) / 2.0
        return MercatorWorldPoint(x = x, y = y.coerceIn(0.0, 1.0))
    }

    fun unproject(point: MercatorWorldPoint): GeoPoint {
        val x = wrapUnit(point.x)
        val y = point.y.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y)))),
            longitude = x * 360.0 - 180.0,
        )
    }

    fun worldSizePx(
        zoom: Double,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): Double {
        requireValidZoom(zoom)
        requirePositiveFinite("Tile size", tileSizePx)
        val worldSize = tileSizePx * 2.0.pow(zoom)
        require(worldSize.isFinite()) { "The projected world size exceeds the supported range." }
        return worldSize
    }

    fun toScreen(
        point: GeoPoint,
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): ScreenPointPx {
        val worldSize = worldSizePx(camera.zoom, tileSizePx)
        val pointWorld = project(point)
        val centerWorld = project(camera.center)
        return ScreenPointPx(
            x = viewport.width / 2.0 + shortestWrappedDelta(pointWorld.x - centerWorld.x) * worldSize,
            y = viewport.height / 2.0 + (pointWorld.y - centerWorld.y) * worldSize,
        )
    }

    fun fromScreen(
        point: ScreenPointPx,
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): GeoPoint {
        val worldSize = worldSizePx(camera.zoom, tileSizePx)
        val centerWorld = project(camera.center)
        return unproject(
            MercatorWorldPoint(
                x = centerWorld.x + (point.x - viewport.width / 2.0) / worldSize,
                y = centerWorld.y + (point.y - viewport.height / 2.0) / worldSize,
            ),
        )
    }

    /**
     * Moves the camera for a screen-space drag. Positive X moves map content to the right and
     * positive Y moves it down, matching direct-manipulation gesture deltas.
     */
    fun panBy(
        camera: GeographicCamera,
        deltaXpx: Double,
        deltaYpx: Double,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): GeographicCamera {
        require(deltaXpx.isFinite() && deltaYpx.isFinite()) {
            "Pan deltas must be finite."
        }
        val worldSize = worldSizePx(camera.zoom, tileSizePx)
        val centerWorld = project(camera.center)
        return camera.copy(
            center = unproject(
                MercatorWorldPoint(
                    x = centerWorld.x - deltaXpx / worldSize,
                    y = centerWorld.y - deltaYpx / worldSize,
                ),
            ),
        )
    }

    /** Zooms around a screen anchor while keeping the geographic point under that anchor fixed. */
    fun zoomBy(
        camera: GeographicCamera,
        zoomFactor: Double,
        anchor: ScreenPointPx,
        viewport: ViewportSizePx,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
        minZoom: Double = MIN_GEOGRAPHIC_ZOOM,
        maxZoom: Double = MAX_GEOGRAPHIC_ZOOM,
    ): GeographicCamera {
        requirePositiveFinite("Zoom factor", zoomFactor)
        requireValidZoomRange(minZoom, maxZoom)
        val targetZoom = (camera.zoom + log2(zoomFactor)).coerceIn(minZoom, maxZoom)
        if (targetZoom == camera.zoom) return camera

        val anchorWorld = project(fromScreen(anchor, camera, viewport, tileSizePx))
        val targetWorldSize = worldSizePx(targetZoom, tileSizePx)
        val targetCenter = unproject(
            MercatorWorldPoint(
                x = anchorWorld.x - (anchor.x - viewport.width / 2.0) / targetWorldSize,
                y = anchorWorld.y - (anchor.y - viewport.height / 2.0) / targetWorldSize,
            ),
        )
        return GeographicCamera(center = targetCenter, zoom = targetZoom)
    }

    /**
     * Fits points into the smallest antimeridian-aware geographic extent that contains them.
     * A single-point extent uses [maxZoom].
     */
    fun fitCamera(
        points: Collection<GeoPoint>,
        viewport: ViewportSizePx,
        paddingPx: Double = 0.0,
        minZoom: Double = MIN_GEOGRAPHIC_ZOOM,
        maxZoom: Double = MAX_GEOGRAPHIC_ZOOM,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): GeographicCamera {
        require(points.isNotEmpty()) { "At least one geographic point is required to fit a camera." }
        require(paddingPx.isFinite() && paddingPx >= 0.0) {
            "Viewport padding must be finite and non-negative."
        }
        require(paddingPx * 2.0 < viewport.width && paddingPx * 2.0 < viewport.height) {
            "Viewport padding must leave a positive drawable area."
        }
        requireValidZoomRange(minZoom, maxZoom)
        requirePositiveFinite("Tile size", tileSizePx)
        worldSizePx(maxZoom, tileSizePx)

        val projected = points.map(::project)
        val longitudeExtent = smallestWrappedExtent(projected.map(MercatorWorldPoint::x))
        val minimumY = projected.minOf(MercatorWorldPoint::y)
        val maximumY = projected.maxOf(MercatorWorldPoint::y)
        val availableWidth = viewport.width - paddingPx * 2.0
        val availableHeight = viewport.height - paddingPx * 2.0
        val zoomForWidth = zoomForSpan(
            span = longitudeExtent.span,
            availablePixels = availableWidth,
            tileSizePx = tileSizePx,
            maxZoom = maxZoom,
        )
        val zoomForHeight = zoomForSpan(
            span = maximumY - minimumY,
            availablePixels = availableHeight,
            tileSizePx = tileSizePx,
            maxZoom = maxZoom,
        )
        val zoom = min(zoomForWidth, zoomForHeight).coerceIn(minZoom, maxZoom)
        return GeographicCamera(
            center = unproject(
                MercatorWorldPoint(
                    x = longitudeExtent.center,
                    y = (minimumY + maximumY) / 2.0,
                ),
            ),
            zoom = zoom,
        )
    }

    fun metersPerPixel(
        latitudeDegrees: Double,
        zoom: Double,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): Double {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "Scale latitude must be between -90 and 90 degrees."
        }
        val projectedLatitude = latitudeDegrees.coerceIn(
            -WEB_MERCATOR_MAX_LATITUDE_DEGREES,
            WEB_MERCATOR_MAX_LATITUDE_DEGREES,
        )
        return EARTH_EQUATORIAL_CIRCUMFERENCE_M * cos(Math.toRadians(projectedLatitude)) /
            worldSizePx(zoom, tileSizePx)
    }

    /** Chooses a conventional 1, 2, or 5 times power-of-ten scale no wider than [maxWidthPx]. */
    fun scaleBar(
        camera: GeographicCamera,
        maxWidthPx: Double,
        tileSizePx: Double = DEFAULT_MERCATOR_TILE_SIZE_PX,
    ): GeographicScaleBar {
        requirePositiveFinite("Maximum scale width", maxWidthPx)
        val metersPerPixel = metersPerPixel(camera.center.latitude, camera.zoom, tileSizePx)
        val maximumDistance = metersPerPixel * maxWidthPx
        require(maximumDistance.isFinite()) { "The requested scale range exceeds the supported range." }
        val magnitude = 10.0.pow(floor(log10(maximumDistance)))
        val normalizedDistance = maximumDistance / magnitude
        val multiplier = when {
            normalizedDistance >= 5.0 -> 5.0
            normalizedDistance >= 2.0 -> 2.0
            else -> 1.0
        }
        val distanceMeters = multiplier * magnitude
        return GeographicScaleBar(
            distanceMeters = distanceMeters,
            widthPx = distanceMeters / metersPerPixel,
        )
    }

    private fun zoomForSpan(
        span: Double,
        availablePixels: Double,
        tileSizePx: Double,
        maxZoom: Double,
    ): Double = if (span <= ZERO_SPAN_EPSILON) {
        maxZoom
    } else {
        log2(availablePixels / (tileSizePx * span)).coerceAtMost(maxZoom)
    }

    private fun smallestWrappedExtent(longitudes: List<Double>): WrappedExtent {
        if (longitudes.size == 1) {
            return WrappedExtent(center = wrapUnit(longitudes.single()), span = 0.0)
        }
        val sorted = longitudes.map(::wrapUnit).sorted()
        var largestGap = -1.0
        var gapStartIndex = 0
        sorted.indices.forEach { index ->
            val next = if (index == sorted.lastIndex) sorted.first() + 1.0 else sorted[index + 1]
            val gap = next - sorted[index]
            if (gap > largestGap) {
                largestGap = gap
                gapStartIndex = index
            }
        }
        val extentStart = if (gapStartIndex == sorted.lastIndex) {
            sorted.first()
        } else {
            sorted[gapStartIndex + 1]
        }
        val span = (1.0 - largestGap).coerceIn(0.0, 1.0)
        return WrappedExtent(
            center = wrapUnit(extentStart + span / 2.0),
            span = span,
        )
    }

    private fun requireValidZoom(zoom: Double) {
        require(zoom.isFinite() && zoom in MIN_GEOGRAPHIC_ZOOM..MAX_GEOGRAPHIC_ZOOM) {
            "Geographic zoom must be between $MIN_GEOGRAPHIC_ZOOM and $MAX_GEOGRAPHIC_ZOOM."
        }
    }

    private fun requireValidZoomRange(minZoom: Double, maxZoom: Double) {
        require(
            minZoom.isFinite() && maxZoom.isFinite() &&
                minZoom in MIN_GEOGRAPHIC_ZOOM..MAX_GEOGRAPHIC_ZOOM &&
                maxZoom in MIN_GEOGRAPHIC_ZOOM..MAX_GEOGRAPHIC_ZOOM &&
                minZoom <= maxZoom,
        ) {
            "The geographic zoom range is invalid."
        }
    }

    private fun requirePositiveFinite(label: String, value: Double) {
        require(value.isFinite() && value > 0.0) { "$label must be positive and finite." }
    }
}

private data class WrappedExtent(
    val center: Double,
    val span: Double,
)

private fun wrapUnit(value: Double): Double {
    val remainder = value % 1.0
    return if (remainder < 0.0) remainder + 1.0 else remainder
}

private fun shortestWrappedDelta(delta: Double): Double {
    val wrapped = wrapUnit(delta + 0.5) - 0.5
    return if (wrapped == -0.5 && delta > 0.0) 0.5 else wrapped
}

private const val EARTH_EQUATORIAL_CIRCUMFERENCE_M = 40_075_016.68557849
private const val ZERO_SPAN_EPSILON = 1e-15
