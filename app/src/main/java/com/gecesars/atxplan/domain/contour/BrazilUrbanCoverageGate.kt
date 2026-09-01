package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.model.GeoPoint
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object BrazilUrbanCoverageGate {
    const val RASTER_SPACING_M = 250.0
    const val AZIMUTH_QUANTIZATION_DEGREES = 1
    private const val DISTANCE_QUANTIZATION_M = 250.0
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val EARTH_RADIUS_FACTOR = 4.0 / 3.0
    private const val RECEIVER_HEIGHT_AGL_M = 10.0
    private const val TERRAIN_SPACING_M = 30.0
    private const val MAXIMUM_RASTER_CELLS = 2_000_000

    fun calculate(
        context: BrazilBroadcastRegulatoryContext,
        service: BroadcastService,
        protectedContour: List<GeoPoint>,
        transmitter: GeoPoint,
        frequencyMHz: Double,
        antennaHeightAglM: Double,
        thresholdDbuvPerM: Double,
        erpAtAzimuthKw: (Double) -> Double,
        terrain: TerrainElevationProvider,
        isCancelled: () -> Boolean = { false },
    ): RegulatoryCoverageGateEvidence {
        if (protectedContour.size < 4 || protectedContour.first() != protectedContour.last()) {
            return noData(
                context.municipality,
                service,
                context.censusGeometry.sectors.size,
                "The project protected contour is incomplete, so the urban-coverage gate is NoData.",
            )
        }
        require(frequencyMHz.isFinite() && frequencyMHz > 0.0 && antennaHeightAglM > 0.0)
        val municipality = context.municipality
        if (context.censusGeometry.municipality != municipality) {
            throw IOException("The census geometry does not belong to the selected municipality.")
        }
        val prepared = context.censusGeometry.sectors.sortedBy(RegulatoryCensusSector::sectorCode)
            .map(::PreparedSector)
        val spatialIndex = SectorSpatialIndex(prepared)
        if (!context.censusGeometry.transmitterInsideMunicipality) {
            return noData(
                municipality,
                service,
                prepared.size,
                "The project transmitter is outside the selected municipality's official census-sector geometry.",
            )
        }
        val urbanBounds = Bounds.union(prepared.map(PreparedSector::bounds))
        val longitudeStep = RASTER_SPACING_M /
            (111_320.0 * cos(Math.toRadians(transmitter.latitude)).coerceAtLeast(0.05))
        val latitudeStep = RASTER_SPACING_M / 110_574.0
        val westIndex = floor(urbanBounds.west / longitudeStep).toInt()
        val eastIndex = ceil(urbanBounds.east / longitudeStep).toInt()
        val southIndex = floor(urbanBounds.south / latitudeStep).toInt()
        val northIndex = ceil(urbanBounds.north / latitudeStep).toInt()
        val width = eastIndex.toLong() - westIndex.toLong()
        val height = northIndex.toLong() - southIndex.toLong()
        if (width <= 0L || height <= 0L || width > MAXIMUM_RASTER_CELLS ||
            height > MAXIMUM_RASTER_CELLS || width * height > MAXIMUM_RASTER_CELLS
        ) {
            throw IOException("The urban-coverage raster exceeds its mobile safety bound.")
        }
        val fieldCache = hashMapOf<PropagationKey, CachedField>()
        val sectorArea = DoubleArray(prepared.size)
        val coveredSectorArea = DoubleArray(prepared.size)
        var noDataCells = 0L
        val noDataSectorArea = DoubleArray(prepared.size)
        for (rowIndex in southIndex until northIndex) {
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val latitude = (rowIndex + 0.5) * latitudeStep
            val cellAreaKm2 = RASTER_SPACING_M * RASTER_SPACING_M / 1_000_000.0 *
                (cos(Math.toRadians(latitude)) /
                    cos(Math.toRadians(transmitter.latitude)).coerceAtLeast(0.05))
            for (columnIndex in westIndex until eastIndex) {
                val longitude = (columnIndex + 0.5) * longitudeStep
                val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: continue
                val sectorIndex = spatialIndex.find(point)
                if (sectorIndex < 0) continue
                sectorArea[sectorIndex] += cellAreaKm2
                if (!pointInRing(point, protectedContour)) continue
                val path = polarKey(transmitter, point)
                val field = fieldCache.getOrPut(path) {
                    CachedField(pointToPointField(
                        transmitter = transmitter,
                        azimuthDegrees = path.azimuthDegrees.toDouble(),
                        distanceM = path.distanceSteps * DISTANCE_QUANTIZATION_M,
                        frequencyMHz = frequencyMHz,
                        antennaHeightAglM = antennaHeightAglM,
                        erpKw = erpAtAzimuthKw(path.azimuthDegrees.toDouble()),
                        terrain = terrain,
                    ))
                }.value
                if (field == null) {
                    noDataCells += 1L
                    noDataSectorArea[sectorIndex] += cellAreaKm2
                } else if (field >= thresholdDbuvPerM) {
                    coveredSectorArea[sectorIndex] += cellAreaKm2
                }
            }
        }
        val eligibleArea = prepared.sumOf { sector -> sector.source.areaKm2 }
        if (eligibleArea <= 0.0) {
            return noData(
                municipality,
                service,
                prepared.size,
                "The official urban census sectors have no positive declared area.",
            )
        }
        var coveredArea = 0.0
        var noDataArea = 0.0
        var population = 0L
        var coveredPopulation = 0.0
        prepared.forEachIndexed { index, sector ->
            population += sector.population
            val sampledArea = sectorArea[index]
            if (sampledArea <= 0.0) {
                noDataArea += sector.source.areaKm2
            } else {
                val coveredFraction = (coveredSectorArea[index] / sampledArea).coerceIn(0.0, 1.0)
                val noDataFraction = (noDataSectorArea[index] / sampledArea).coerceIn(0.0, 1.0)
                coveredArea += sector.source.areaKm2 * coveredFraction
                noDataArea += sector.source.areaKm2 * noDataFraction
                coveredPopulation += sector.population * coveredFraction
            }
        }
        val areaPercent = coveredArea / eligibleArea * 100.0
        val lowerPercent = areaPercent
        val upperPercent = ((coveredArea + noDataArea) / eligibleArea).coerceAtMost(1.0) * 100.0
        val populationPercent = population.takeIf { it > 0L }
            ?.let { coveredPopulation / it.toDouble() * 100.0 }
        val requirement = if (service == BroadcastService.FM) 50 else 70
        val status = when {
            lowerPercent + 1e-9 >= requirement -> RegulatoryGateStatus.PASS
            upperPercent + 1e-9 < requirement -> RegulatoryGateStatus.FAIL
            else -> RegulatoryGateStatus.NO_DATA
        }
        return RegulatoryCoverageGateEvidence(
            municipality = municipality,
            requirementPercent = requirement,
            rasterSpacingM = RASTER_SPACING_M,
            eligibleUrbanAreaKm2 = eligibleArea,
            coveredUrbanAreaKm2 = coveredArea,
            areaCoveragePercent = areaPercent,
            areaCoverageLowerPercent = lowerPercent,
            areaCoverageUpperPercent = upperPercent,
            eligibleUrbanPopulation = population,
            coveredUrbanPopulationEstimate = coveredPopulation,
            populationCoveragePercent = populationPercent,
            sectorCount = prepared.size,
            noDataCellCount = noDataCells,
            status = status,
            method = "All official IBGE 2022 urban-sector polygons in the selected municipality are rasterized at 250 m, while each sector's official AREA_KM2 value supplies the denominator. Cells outside the P.1546 protected contour are uncovered. Cells inside it use P.526-15 Deygout–Assis field with 1° azimuth and 250 m distance bins over 30 m DTM profiles. A sector with no sample center is entirely NoData. Population is an explicit uniform-within-sector area-weighted estimate and is not used to override the area gate.",
        )
    }

    private fun noData(
        municipality: RegulatoryMunicipalityContext,
        service: BroadcastService,
        sectorCount: Int,
        method: String,
    ) = RegulatoryCoverageGateEvidence(
        municipality = municipality,
        requirementPercent = if (service == BroadcastService.FM) 50 else 70,
        rasterSpacingM = RASTER_SPACING_M,
        eligibleUrbanAreaKm2 = 0.0,
        coveredUrbanAreaKm2 = 0.0,
        areaCoveragePercent = null,
        areaCoverageLowerPercent = null,
        areaCoverageUpperPercent = null,
        eligibleUrbanPopulation = 0L,
        coveredUrbanPopulationEstimate = 0.0,
        populationCoveragePercent = null,
        sectorCount = sectorCount,
        noDataCellCount = 0L,
        status = RegulatoryGateStatus.NO_DATA,
        method = method,
    )

    private fun pointToPointField(
        transmitter: GeoPoint,
        azimuthDegrees: Double,
        distanceM: Double,
        frequencyMHz: Double,
        antennaHeightAglM: Double,
        erpKw: Double,
        terrain: TerrainElevationProvider,
    ): Double? {
        if (!distanceM.isFinite() || distanceM <= 0.0 || !erpKw.isFinite() || erpKw <= 0.0) return null
        val sampleCount = ceil(distanceM / TERRAIN_SPACING_M).toInt().coerceAtLeast(1)
        val distances = ArrayList<Double>(sampleCount + 1)
        val ground = ArrayList<Double>(sampleCount + 1)
        repeat(sampleCount + 1) { index ->
            val nodeDistance = distanceM * index / sampleCount
            val point = destination(transmitter, azimuthDegrees, nodeDistance)
            val elevation = terrain.elevationMeters(point.latitude, point.longitude) ?: return null
            distances += nodeDistance
            ground += elevation
        }
        val effectiveEarthRadius = EARTH_RADIUS_M * EARTH_RADIUS_FACTOR
        val heights = ground.mapIndexed { index, elevation ->
            when (index) {
                0 -> elevation + antennaHeightAglM
                ground.lastIndex -> elevation + RECEIVER_HEIGHT_AGL_M
                else -> {
                    val d = distances[index]
                    elevation + d * (distanceM - d) / (2.0 * effectiveEarthRadius)
                }
            }
        }
        val diffraction = P526DeygoutAssis.calculate(distances, heights, frequencyMHz)
        return freeSpaceErpFieldDbuvPerM(erpKw * 1_000.0, distanceM) - diffraction.lossDb
    }

    private fun polarKey(start: GeoPoint, end: GeoPoint): PropagationKey {
        val distance = greatCircleDistanceM(start, end)
        val bearing = initialBearingDegrees(start, end)
        return PropagationKey(
            azimuthDegrees = floor(bearing + 0.5).toInt().mod(360),
            distanceSteps = max(1, floor(distance / DISTANCE_QUANTIZATION_M + 0.5).toInt()),
        )
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        var previous = ring.last()
        ring.forEach { current ->
            val intersects = (current.latitude > point.latitude) != (previous.latitude > point.latitude) &&
                point.longitude < (previous.longitude - current.longitude) *
                (point.latitude - current.latitude) /
                (previous.latitude - current.latitude) + current.longitude
            if (intersects) inside = !inside
            previous = current
        }
        return inside
    }

    private fun destination(start: GeoPoint, bearingDegrees: Double, distanceM: Double): GeoPoint {
        val angular = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val resultLatitude = kotlin.math.asin(
            sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing),
        )
        val resultLongitude = longitude + atan2(
            sin(bearing) * sin(angular) * cos(latitude),
            cos(angular) - sin(latitude) * sin(resultLatitude),
        )
        return GeoPoint(
            Math.toDegrees(resultLatitude),
            ((Math.toDegrees(resultLongitude) + 540.0) % 360.0) - 180.0,
        )
    }

    private fun greatCircleDistanceM(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
    }

    private fun initialBearingDegrees(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun freeSpaceErpFieldDbuvPerM(erpW: Double, distanceM: Double): Double =
        20.0 * log10(sqrt(30.0 * 1.64 * erpW) / distanceM * 1_000_000.0)

    private data class PropagationKey(val azimuthDegrees: Int, val distanceSteps: Int)
    private data class CachedField(val value: Double?)

    private class PreparedSector(val source: RegulatoryCensusSector) {
        val population: Long = source.residentPopulation
        val bounds: Bounds = Bounds.of(
            source.polygons.flatMap { polygon -> polygon.rings.flatMap(RegulatoryCensusRing::points) },
        )

        fun contains(point: GeoPoint): Boolean = source.polygons.any { polygon ->
            pointInRing(point, polygon.rings.first().points) &&
                polygon.rings.drop(1).none { hole -> pointInRing(point, hole.points) }
        }
    }

    private class SectorSpatialIndex(
        private val sectors: List<PreparedSector>,
    ) {
        private val buckets: Map<Long, IntArray>

        init {
            val mutableBuckets = hashMapOf<Long, MutableList<Int>>()
            var memberships = 0L
            sectors.forEachIndexed { index, sector ->
                val west = floor(sector.bounds.west / SPATIAL_BUCKET_DEGREES).toInt()
                val east = floor(sector.bounds.east / SPATIAL_BUCKET_DEGREES).toInt()
                val south = floor(sector.bounds.south / SPATIAL_BUCKET_DEGREES).toInt()
                val north = floor(sector.bounds.north / SPATIAL_BUCKET_DEGREES).toInt()
                for (latitudeBucket in south..north) {
                    for (longitudeBucket in west..east) {
                        memberships += 1L
                        if (memberships > MAXIMUM_SPATIAL_BUCKET_MEMBERSHIPS) {
                            throw IOException("The census spatial index exceeds its mobile safety bound.")
                        }
                        val key = bucketKey(latitudeBucket, longitudeBucket)
                        mutableBuckets.getOrPut(key) { mutableListOf() }.add(index)
                    }
                }
            }
            buckets = mutableBuckets.mapValues { (_, values) -> values.toIntArray() }
        }

        fun find(point: GeoPoint): Int {
            val latitudeBucket = floor(point.latitude / SPATIAL_BUCKET_DEGREES).toInt()
            val longitudeBucket = floor(point.longitude / SPATIAL_BUCKET_DEGREES).toInt()
            return (buckets[bucketKey(latitudeBucket, longitudeBucket)] ?: IntArray(0))
                .firstOrNull { index ->
                    sectors[index].bounds.contains(point) && sectors[index].contains(point)
                } ?: -1
        }

        private fun bucketKey(latitude: Int, longitude: Int): Long =
            latitude.toLong().shl(32) xor (longitude.toLong() and 0xffff_ffffL)
    }

    private data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun contains(point: GeoPoint): Boolean =
            point.longitude in west..east && point.latitude in south..north

        companion object {
            fun of(points: List<GeoPoint>): Bounds {
                if (points.isEmpty()) throw IOException("A regulatory polygon contains no points.")
                return Bounds(
                    points.minOf(GeoPoint::longitude),
                    points.minOf(GeoPoint::latitude),
                    points.maxOf(GeoPoint::longitude),
                    points.maxOf(GeoPoint::latitude),
                )
            }

            fun union(bounds: List<Bounds>): Bounds {
                if (bounds.isEmpty()) throw IOException("The municipality has no urban geometry bounds.")
                return Bounds(
                    bounds.minOf(Bounds::west),
                    bounds.minOf(Bounds::south),
                    bounds.maxOf(Bounds::east),
                    bounds.maxOf(Bounds::north),
                )
            }
        }
    }
}

private const val SPATIAL_BUCKET_DEGREES = 0.02
private const val MAXIMUM_SPATIAL_BUCKET_MEMBERSHIPS = 2_000_000L
