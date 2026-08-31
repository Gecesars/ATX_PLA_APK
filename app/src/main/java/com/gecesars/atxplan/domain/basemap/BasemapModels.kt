package com.gecesars.atxplan.domain.basemap

import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.GeographicViewport
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import java.net.URI
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Auditable configuration for one fixed HTTPS XYZ raster source. */
data class RasterBasemapProvider(
    val id: String,
    val label: String,
    val tileUrlTemplate: String,
    val attribution: String,
    val attributionUrl: String,
    val termsUrl: String,
    val usageNotice: String,
    val minimumZoom: Int = 0,
    val maximumZoom: Int = 19,
    val tileSizePx: Int = 256,
) {
    init {
        require(PROVIDER_ID_REGEX.matches(id)) { "The basemap provider ID is invalid." }
        require(label.isNotBlank() && label.length <= MAX_PROVIDER_LABEL_LENGTH) {
            "The basemap provider label is invalid."
        }
        require(attribution.isNotBlank() && attribution.length <= MAX_ATTRIBUTION_LENGTH) {
            "The basemap attribution is invalid."
        }
        require(usageNotice.isNotBlank() && usageNotice.length <= MAX_USAGE_NOTICE_LENGTH) {
            "The basemap usage notice is invalid."
        }
        require(minimumZoom in 0..MAX_XYZ_ZOOM && maximumZoom in minimumZoom..MAX_XYZ_ZOOM) {
            "The basemap zoom range is invalid."
        }
        require(tileSizePx == 256 || tileSizePx == 512) {
            "The basemap tile size must be 256 or 512 pixels."
        }
        validateHttpsUrl(attributionUrl, requireTemplateTokens = false)
        validateHttpsUrl(termsUrl, requireTemplateTokens = false)
        validateHttpsUrl(tileUrlTemplate, requireTemplateTokens = true)
    }

    val allowedHost: String
        get() = requireNotNull(URI(tileUrlTemplate.withResolvedTemplateTokens()).host).lowercase()

    fun tileUrl(coordinate: BasemapTileCoordinate): String {
        require(coordinate.zoom in minimumZoom..maximumZoom) {
            "The requested tile zoom is outside this provider's range."
        }
        return tileUrlTemplate
            .replace("{z}", coordinate.zoom.toString())
            .replace("{x}", coordinate.x.toString())
            .replace("{y}", coordinate.y.toString())
    }
}

data class BasemapTileCoordinate(
    val zoom: Int,
    val x: Int,
    val y: Int,
) {
    init {
        require(zoom in 0..MAX_XYZ_ZOOM) { "The XYZ tile zoom is invalid." }
        val dimension = 1 shl zoom
        require(x in 0 until dimension && y in 0 until dimension) {
            "The XYZ tile coordinate is outside its zoom matrix."
        }
    }
}

data class BasemapTilePlan(
    val providerId: String,
    val tileZoom: Int,
    val coordinates: List<BasemapTileCoordinate>,
) {
    init {
        require(PROVIDER_ID_REGEX.matches(providerId)) { "The tile-plan provider ID is invalid." }
        require(tileZoom in 0..MAX_XYZ_ZOOM) { "The tile-plan zoom is invalid." }
        require(coordinates.isNotEmpty() && coordinates.size <= MAX_VISIBLE_TILE_COUNT) {
            "A visible tile plan must contain 1 to $MAX_VISIBLE_TILE_COUNT tiles."
        }
        require(coordinates.all { it.zoom == tileZoom }) {
            "A visible tile plan must use one zoom level."
        }
        require(coordinates.distinct().size == coordinates.size) {
            "A visible tile plan must not contain duplicate coordinates."
        }
    }
}

/**
 * Plans only tiles intersecting the current human-visible viewport.
 *
 * It never creates multi-zoom or padded regions. If a very large viewport would exceed the
 * request cap, it lowers the raster zoom until the same visible extent fits the cap.
 */
object BasemapTilePlanner {
    fun planVisibleTiles(
        provider: RasterBasemapProvider,
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        displayTileSizePx: Double,
        maximumTileCount: Int = MAX_VISIBLE_TILE_COUNT,
    ): BasemapTilePlan {
        require(displayTileSizePx.isFinite() && displayTileSizePx > 0.0) {
            "The display tile size must be positive and finite."
        }
        require(maximumTileCount in 1..MAX_VISIBLE_TILE_COUNT) {
            "The visible tile limit is invalid."
        }
        val densityZoomOffset = log2(displayTileSizePx / provider.tileSizePx.toDouble())
        var tileZoom = (camera.zoom + densityZoomOffset)
            .roundToInt()
            .coerceIn(provider.minimumZoom, provider.maximumZoom)
        var coordinates = coordinatesFor(camera, viewport, displayTileSizePx, tileZoom)
        while (coordinates.size > maximumTileCount && tileZoom > provider.minimumZoom) {
            tileZoom -= 1
            coordinates = coordinatesFor(camera, viewport, displayTileSizePx, tileZoom)
        }
        require(coordinates.size <= maximumTileCount) {
            "The visible viewport exceeds the bounded tile request limit."
        }
        return BasemapTilePlan(
            providerId = provider.id,
            tileZoom = tileZoom,
            coordinates = coordinates,
        )
    }

    private fun coordinatesFor(
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        displayTileSizePx: Double,
        tileZoom: Int,
    ): List<BasemapTileCoordinate> {
        val cameraWorldSize = GeographicViewport.worldSizePx(camera.zoom, displayTileSizePx)
        val center = GeographicViewport.project(camera.center)
        val halfWidthWorld = viewport.width / (2.0 * cameraWorldSize)
        val halfHeightWorld = viewport.height / (2.0 * cameraWorldSize)
        val dimension = 2.0.pow(tileZoom).roundToInt()
        val minimumRawX = floor((center.x - halfWidthWorld) * dimension).toInt()
        val maximumRawX = floor(
            Math.nextDown((center.x + halfWidthWorld) * dimension),
        ).toInt()
        val minimumY = floor((center.y - halfHeightWorld) * dimension)
            .toInt()
            .coerceIn(0, dimension - 1)
        val maximumY = floor(Math.nextDown((center.y + halfHeightWorld) * dimension))
            .toInt()
            .coerceIn(0, dimension - 1)
        val ordered = linkedSetOf<BasemapTileCoordinate>()
        for (rawX in minimumRawX..maximumRawX) {
            val x = Math.floorMod(rawX, dimension)
            for (y in minimumY..maximumY) {
                ordered += BasemapTileCoordinate(tileZoom, x, y)
            }
        }
        return ordered.toList()
    }
}

private fun validateHttpsUrl(value: String, requireTemplateTokens: Boolean) {
    require(value.length in 1..MAX_PROVIDER_URL_LENGTH && value.none(Char::isISOControl)) {
        "A basemap provider URL is invalid."
    }
    val uri = runCatching {
        URI(if (requireTemplateTokens) value.withResolvedTemplateTokens() else value)
    }.getOrElse {
        throw IllegalArgumentException("A basemap provider URL is invalid.", it)
    }
    require(
        uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in setOf(-1, 443),
    ) { "A basemap provider URL must be a host-only HTTPS URL without credentials or fragments." }
    if (requireTemplateTokens) {
        require(TILE_TEMPLATE_TOKENS.all { token -> value.countOccurrences(token) == 1 }) {
            "A basemap tile URL must contain one {z}, {x}, and {y} token."
        }
    }
}

private fun String.withResolvedTemplateTokens(): String =
    TILE_TEMPLATE_TOKENS.fold(this) { resolved, token -> resolved.replace(token, "0") }

private fun String.countOccurrences(value: String): Int {
    var count = 0
    var offset = 0
    while (true) {
        val index = indexOf(value, offset)
        if (index < 0) return count
        count += 1
        offset = index + value.length
    }
}

const val MAX_BASEMAP_PROVIDER_COUNT = 10
const val MAX_VISIBLE_TILE_COUNT = 48
const val MAX_XYZ_ZOOM = 24

private val PROVIDER_ID_REGEX = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val TILE_TEMPLATE_TOKENS = listOf("{z}", "{x}", "{y}")
private const val MAX_PROVIDER_LABEL_LENGTH = 100
private const val MAX_ATTRIBUTION_LENGTH = 300
private const val MAX_USAGE_NOTICE_LENGTH = 400
private const val MAX_PROVIDER_URL_LENGTH = 2_048
