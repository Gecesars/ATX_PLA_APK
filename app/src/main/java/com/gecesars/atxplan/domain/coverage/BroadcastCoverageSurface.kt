package com.gecesars.atxplan.domain.coverage

import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyPlanner
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.P1546LandReference
import com.gecesars.atxplan.domain.contour.RegulatoryContourRadialEvidence
import com.gecesars.atxplan.domain.contour.TerrainElevationProvider
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.Sector
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class CoverageRenderMode(val displayName: String) {
    BROADCAST_DISCRETE("Broadcast bands"),
    BROADCAST_CONTINUOUS("Continuous"),
    TURBO_HEATMAP("Heatmap"),
}

data class CoverageGeographicBounds(
    val northLatitude: Double,
    val southLatitude: Double,
    val westLongitude: Double,
    val eastLongitude: Double,
) {
    init {
        require(northLatitude.isFinite() && southLatitude.isFinite())
        require(westLongitude.isFinite() && eastLongitude.isFinite())
        require(northLatitude > southLatitude)
        require(eastLongitude > westLongitude) {
            "Coverage surfaces that cross the antimeridian are not supported."
        }
    }

    val cornerPoints: List<GeoPoint>
        get() = listOf(
            GeoPoint(northLatitude, westLongitude),
            GeoPoint(northLatitude, eastLongitude),
            GeoPoint(southLatitude, eastLongitude),
            GeoPoint(southLatitude, westLongitude),
        )
}

/**
 * Bounded, transient coverage field. A NaN cell is explicit NoData and must render transparent.
 * Rows are north-to-south Web Mercator samples; columns are west-to-east Web Mercator samples.
 */
class BroadcastCoverageSurface(
    val width: Int,
    val height: Int,
    val bounds: CoverageGeographicBounds,
    val valuesDbuvPerM: FloatArray,
    val minimumCalculatedDbuvPerM: Double?,
    val maximumCalculatedDbuvPerM: Double?,
    val metricId: String,
    val unit: String,
    val modelId: String,
    val statisticalBasis: String,
    val noDataMeaning: String,
    val inputFingerprint: String,
    val directionalPatternApplied: Boolean,
    val warnings: List<String>,
) {
    init {
        require(width in 2..MAX_SURFACE_DIMENSION && height in 2..MAX_SURFACE_DIMENSION)
        require(valuesDbuvPerM.size == width * height)
        require(valuesDbuvPerM.all { it.isNaN() || it.isFinite() })
        require(metricId.isNotBlank() && unit.isNotBlank() && modelId.isNotBlank())
        require(statisticalBasis.isNotBlank() && noDataMeaning.isNotBlank())
        require(inputFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(warnings.size <= 32 && warnings.all { it.isNotBlank() && it.length <= 500 })
        require(
            minimumCalculatedDbuvPerM == null ||
                minimumCalculatedDbuvPerM.isFinite(),
        )
        require(
            maximumCalculatedDbuvPerM == null ||
                maximumCalculatedDbuvPerM.isFinite(),
        )
        require(
            minimumCalculatedDbuvPerM == null || maximumCalculatedDbuvPerM == null ||
                minimumCalculatedDbuvPerM <= maximumCalculatedDbuvPerM,
        )
    }

    val noDataCellCount: Int
        get() = valuesDbuvPerM.count(Float::isNaN)

    fun valueAt(column: Int, row: Int): Double? {
        require(column in 0 until width && row in 0 until height)
        return valuesDbuvPerM[row * width + column]
            .takeUnless(Float::isNaN)
            ?.toDouble()
    }

    companion object {
        const val MAX_SURFACE_DIMENSION = 257
    }
}

/** Palette parity with the desktop broadcast raster, plus continuous and Turbo alternatives. */
object BroadcastCoveragePalette {
    const val PALETTE_ID = "DISCRETE_BROADCAST_DBVM_45_80"
    const val UNIT = "dBµV/m"
    const val MINIMUM_VISIBLE_DBUV_PER_M = 45.0
    const val MAXIMUM_REFERENCE_DBUV_PER_M = 80.0
    const val DEFAULT_ALPHA = 128

    data class LegendBand(
        val minimumInclusiveDbuvPerM: Double,
        val maximumExclusiveDbuvPerM: Double?,
        val rgb: Int,
        val label: String,
    )

    val discreteBands: List<LegendBand> = listOf(
        LegendBand(45.0, 50.0, 0xFFA500, "45 to <50"),
        LegendBand(50.0, 55.0, 0x87CDF9, "50 to <55"),
        LegendBand(55.0, 60.0, 0xFF00FF, "55 to <60"),
        LegendBand(60.0, 65.0, 0xA52929, "60 to <65"),
        LegendBand(65.0, 70.0, 0x0000FF, "65 to <70"),
        LegendBand(70.0, 75.0, 0x007F00, "70 to <75"),
        LegendBand(75.0, 80.0, 0xFF0000, "75 to <80"),
        LegendBand(80.0, null, 0xFFFF00, "80 or higher"),
    )

    fun argb(
        valueDbuvPerM: Double?,
        mode: CoverageRenderMode,
        alpha: Int = DEFAULT_ALPHA,
    ): Int {
        require(alpha in 0..255)
        val value = valueDbuvPerM
            ?.takeIf { it.isFinite() && it >= MINIMUM_VISIBLE_DBUV_PER_M }
            ?: return 0x00000000
        val rgb = when (mode) {
            CoverageRenderMode.BROADCAST_DISCRETE -> discreteRgb(value)
            CoverageRenderMode.BROADCAST_CONTINUOUS -> continuousRgb(value)
            CoverageRenderMode.TURBO_HEATMAP -> turboRgb(value)
        }
        return (alpha shl 24) or rgb
    }

    private fun discreteRgb(value: Double): Int = discreteBands
        .last { band -> value >= band.minimumInclusiveDbuvPerM }
        .rgb

    private fun continuousRgb(value: Double): Int {
        val bounded = value.coerceIn(MINIMUM_VISIBLE_DBUV_PER_M, MAXIMUM_REFERENCE_DBUV_PER_M)
        val lower = discreteBands.last { it.minimumInclusiveDbuvPerM <= bounded }
        val upper = discreteBands.firstOrNull { it.minimumInclusiveDbuvPerM > bounded } ?: lower
        if (lower === upper) return lower.rgb
        val fraction = (bounded - lower.minimumInclusiveDbuvPerM) /
            (upper.minimumInclusiveDbuvPerM - lower.minimumInclusiveDbuvPerM)
        return interpolateRgb(lower.rgb, upper.rgb, fraction)
    }

    private fun turboRgb(value: Double): Int {
        val x = ((value - MINIMUM_VISIBLE_DBUV_PER_M) /
            (MAXIMUM_REFERENCE_DBUV_PER_M - MINIMUM_VISIBLE_DBUV_PER_M)).coerceIn(0.0, 1.0)
        val red = 34.61 + x * (1172.33 + x * (-10793.56 + x * (33300.12 + x * (-38394.49 + x * 14825.05))))
        val green = 23.31 + x * (557.33 + x * (1225.33 + x * (-3574.96 + x * (1073.77 + x * 707.56))))
        val blue = 27.20 + x * (3211.10 + x * (-15327.97 + x * (27814.00 + x * (-22569.18 + x * 6838.66))))
        return packRgb(red, green, blue)
    }

    private fun interpolateRgb(lower: Int, upper: Int, fraction: Double): Int {
        fun component(shift: Int): Double {
            val start = (lower shr shift) and 0xff
            val end = (upper shr shift) and 0xff
            return start + (end - start) * fraction
        }
        return packRgb(component(16), component(8), component(0))
    }

    private fun packRgb(red: Double, green: Double, blue: Double): Int =
        ((red.coerceIn(0.0, 255.0).toInt() and 0xff) shl 16) or
            ((green.coerceIn(0.0, 255.0).toInt() and 0xff) shl 8) or
            (blue.coerceIn(0.0, 255.0).toInt() and 0xff)
}

/**
 * CPU-only operational field surface for a completed FM or digital-TV study. This does not replace the
 * protected regulatory contour and is not itself a filing result.
 */
object BrazilDigitalTvCoverageSurfacePlanner {
    const val GRID_SIZE = 181
    const val METRIC_ID = "electric-field-strength"
    const val NO_DATA_MEANING =
        "Outside the requested radius, below the 1 km P.1546 boundary, or unavailable terrain/model input."
    private const val EARTH_MEAN_RADIUS_M = 6_371_008.8

    fun calculate(
        center: GeoPoint,
        radiusKm: Double,
        frequencyMHz: Double,
        peakErpKw: Double,
        antennaHeightAglM: Double,
        sector: Sector,
        assignedPattern: AntennaPatternRecord?,
        radialEvidence: List<RegulatoryContourRadialEvidence>,
        terrain: TerrainElevationProvider,
        inputFingerprint: String,
        service: BroadcastService = BroadcastService.DIGITAL_TV,
        isCancelled: () -> Boolean = { false },
        onRowComplete: () -> Unit = {},
    ): BroadcastCoverageSurface {
        require(radiusKm.isFinite() && radiusKm in 1.0..100.0)
        require(frequencyMHz.isFinite() && frequencyMHz > 0.0)
        require(peakErpKw.isFinite() && peakErpKw > 0.0)
        require(antennaHeightAglM.isFinite() && antennaHeightAglM > 0.0)
        require(radialEvidence.size >= 2)
        require(inputFingerprint.matches(Regex("[0-9a-f]{64}")))

        val pattern = assignedPattern?.takeIf(AntennaPatternRecord::hasVerifiedNormalizedContentIdentity)
        val north = destination(center, 0.0, radiusKm * 1_000.0).latitude
        val south = destination(center, 180.0, radiusKm * 1_000.0).latitude
        val east = destination(center, 90.0, radiusKm * 1_000.0).longitude
        val west = destination(center, 270.0, radiusKm * 1_000.0).longitude
        val bounds = CoverageGeographicBounds(north, south, west, east)
        val northMercatorY = mercatorY(north)
        val southMercatorY = mercatorY(south)
        val siteGroundM = terrain.elevationMeters(center.latitude, center.longitude)
        val values = FloatArray(GRID_SIZE * GRID_SIZE) { Float.NaN }
        var minimum: Double? = null
        var maximum: Double? = null

        repeat(GRID_SIZE) { row ->
            if (isCancelled()) throw com.gecesars.atxplan.domain.contour.RegulatoryStudyCancelled()
            val rowFraction = row.toDouble() / (GRID_SIZE - 1)
            val latitude = inverseMercatorY(
                northMercatorY + (southMercatorY - northMercatorY) * rowFraction,
            )
            repeat(GRID_SIZE) cell@{ column ->
                val columnFraction = column.toDouble() / (GRID_SIZE - 1)
                val longitude = west + (east - west) * columnFraction
                val point = GeoPoint(latitude, longitude)
                val distanceM = greatCircleDistanceM(center, point)
                if (distanceM < 1_000.0 || distanceM > radiusKm * 1_000.0) return@cell
                val bearing = initialBearingDegrees(center, point)
                val hnmtM = interpolateHnmt(radialEvidence, bearing) ?: return@cell
                if (hnmtM !in P1546LandReference.MIN_EFFECTIVE_HEIGHT_M..
                    P1546LandReference.MAX_EFFECTIVE_HEIGHT_M
                ) {
                    return@cell
                }
                val receiverGroundM = terrain.elevationMeters(latitude, longitude) ?: return@cell
                val horizontalField = pattern?.horizontalCut?.let { cut ->
                    interpolatePeriodic(cut.normalizedField, bearing - sector.azimuthDegrees)
                } ?: 1.0
                val verticalField = if (pattern == null || siteGroundM == null) {
                    1.0
                } else {
                    val geometricDropM = distanceM.pow(2.0) / (2.0 * EARTH_MEAN_RADIUS_M)
                    val elevationUpDegrees = Math.toDegrees(
                        atan2(
                            receiverGroundM + BrazilDigitalTvRegulatoryStudyPlanner.RECEIVER_HEIGHT_AGL_M -
                                (siteGroundM + antennaHeightAglM) - geometricDropM,
                            distanceM,
                        ),
                    )
                    interpolateClamped(
                        pattern.verticalCut!!.normalizedField,
                        elevationUpDegrees - sector.electricalTiltDegrees,
                    )
                }
                val fieldRatio = horizontalField * verticalField
                if (!fieldRatio.isFinite() || fieldRatio <= 0.0) return@cell
                val directionalErpKw = peakErpKw * fieldRatio.pow(2.0)
                val distanceKm = distanceM / 1_000.0
                val e50 = P1546LandReference.fieldStrengthDbuvPerM(
                    frequencyMHz,
                    50,
                    hnmtM,
                    distanceKm,
                    directionalErpKw,
                )
                val field = when (service) {
                    BroadcastService.FM -> e50
                    BroadcastService.DIGITAL_TV -> {
                        val e10 = P1546LandReference.fieldStrengthDbuvPerM(
                            frequencyMHz,
                            10,
                            hnmtM,
                            distanceKm,
                            directionalErpKw,
                        )
                        2.0 * e50 - e10
                    }
                }
                if (!field.isFinite()) return@cell
                values[row * GRID_SIZE + column] = field.toFloat()
                minimum = minimum?.let { minOf(it, field) } ?: field
                maximum = maximum?.let { maxOf(it, field) } ?: field
            }
            onRowComplete()
        }
        return BroadcastCoverageSurface(
            width = GRID_SIZE,
            height = GRID_SIZE,
            bounds = bounds,
            valuesDbuvPerM = values,
            minimumCalculatedDbuvPerM = minimum,
            maximumCalculatedDbuvPerM = maximum,
            metricId = METRIC_ID,
            unit = BroadcastCoveragePalette.UNIT,
            modelId = BrazilDigitalTvRegulatoryStudyPlanner.P1546_MODEL_ID,
            statisticalBasis = when (service) {
                BroadcastService.FM -> "E(50,50)"
                BroadcastService.DIGITAL_TV -> "E(50,90), derived as 2 × E(50,50) - E(50,10)"
            },
            noDataMeaning = NO_DATA_MEANING,
            inputFingerprint = inputFingerprint,
            directionalPatternApplied = pattern != null,
            warnings = buildList {
                add("This CPU-generated surface is an operational visualization, not a regulatory filing contour.")
                add("Field values use radial HNMT interpolation and nearest-pixel terrain samples.")
                if (pattern == null) {
                    add(
                        when {
                            assignedPattern == null ->
                                "No transmit pattern is assigned; an omnidirectional field ratio is used."
                            else ->
                                "The assigned pattern failed canonical identity verification; an omnidirectional field ratio is used."
                        },
                    )
                } else {
                    add("Verified HRP and VRP field amplitudes shape peak ERP exactly once through (E/Emax)².")
                    add("Electrical tilt uses the elevation-up convention; negative values point downward.")
                    add("Separable HRP and VRP cuts are an approximation and do not reconstruct a full measured 3D pattern.")
                }
            },
        )
    }

    private fun interpolateHnmt(
        radials: List<RegulatoryContourRadialEvidence>,
        bearingDegrees: Double,
    ): Double? {
        val sorted = radials.sortedBy(RegulatoryContourRadialEvidence::azimuthDegrees)
        val step = 360.0 / sorted.size
        val position = wrap360(bearingDegrees) / step
        val lowerUnwrapped = floor(position).toInt()
        val lower = sorted[Math.floorMod(lowerUnwrapped, sorted.size)].hnmtM ?: return null
        val upper = sorted[(lowerUnwrapped + 1).mod(sorted.size)].hnmtM ?: return null
        val fraction = position - floor(position)
        return lower * (1.0 - fraction) + upper * fraction
    }

    private fun interpolatePeriodic(values: List<Double>, angleDegrees: Double): Double {
        val position = wrap360(angleDegrees)
        val lower = floor(position).toInt()
        val fraction = position - lower
        return values[Math.floorMod(lower, values.size)] * (1.0 - fraction) +
            values[(lower + 1).mod(values.size)] * fraction
    }

    private fun interpolateClamped(values: List<Double>, elevationDegrees: Double): Double {
        val position = elevationDegrees.coerceIn(-90.0, 90.0) + 90.0
        val lower = floor(position).toInt().coerceIn(0, values.lastIndex)
        val upper = (lower + 1).coerceAtMost(values.lastIndex)
        val fraction = position - floor(position)
        return values[lower] * (1.0 - fraction) + values[upper] * fraction
    }

    private fun mercatorY(latitude: Double): Double {
        val bounded = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(bounded)
        return (1.0 - ln(kotlin.math.tan(radians) + 1.0 / cos(radians)) / Math.PI) / 2.0
    }

    private fun inverseMercatorY(y: Double): Double =
        Math.toDegrees(atan2(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * y)), 1.0))

    private fun destination(start: GeoPoint, bearingDegrees: Double, distanceM: Double): GeoPoint {
        val angularDistance = distanceM / EARTH_MEAN_RADIUS_M
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        val normalizedLongitude = Math.toDegrees(destinationLongitude)
            .let { ((it + 540.0) % 360.0) - 180.0 }
        return GeoPoint(Math.toDegrees(destinationLatitude), normalizedLongitude)
    }

    private fun greatCircleDistanceM(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        return 2.0 * EARTH_MEAN_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
    }

    private fun initialBearingDegrees(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return wrap360(Math.toDegrees(atan2(y, x)))
    }

    private fun wrap360(value: Double): Double {
        val wrapped = value % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }
}
