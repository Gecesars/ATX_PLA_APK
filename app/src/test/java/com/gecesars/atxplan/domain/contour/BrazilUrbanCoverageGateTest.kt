package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrazilUrbanCoverageGateTest {
    @Test
    fun usesAllUrbanSectorAreaAsDenominator() {
        val result = BrazilUrbanCoverageGate.calculate(
            context = context(),
            service = BroadcastService.DIGITAL_TV,
            protectedContour = closedSquare(0.0, 0.0, 0.004),
            transmitter = GeoPoint(0.0, 0.0),
            frequencyMHz = 641.0,
            antennaHeightAglM = 100.0,
            thresholdDbuvPerM = -100.0,
            erpAtAzimuthKw = { 1.0 },
            terrain = TerrainElevationProvider { _, _ -> 100.0 },
        )

        assertEquals(RegulatoryGateStatus.FAIL, result.status)
        assertEquals(4.9, result.eligibleUrbanAreaKm2, 0.0)
        assertEquals(1_000L, result.eligibleUrbanPopulation)
        assertEquals(1, result.sectorCount)
        assertNotNull(result.areaCoveragePercent)
        assertTrue(checkNotNull(result.areaCoveragePercent) < 70.0)
        assertEquals(result.areaCoveragePercent, result.areaCoverageLowerPercent)
        assertEquals(0L, result.noDataCellCount)
    }

    @Test
    fun passesCompleteUrbanCoverageAndPreservesNoDataInterval() {
        val complete = BrazilUrbanCoverageGate.calculate(
            context = context(),
            service = BroadcastService.DIGITAL_TV,
            protectedContour = closedSquare(0.0, 0.0, 0.02),
            transmitter = GeoPoint(0.0, 0.0),
            frequencyMHz = 641.0,
            antennaHeightAglM = 100.0,
            thresholdDbuvPerM = -100.0,
            erpAtAzimuthKw = { 1.0 },
            terrain = TerrainElevationProvider { _, _ -> 100.0 },
        )
        assertEquals(RegulatoryGateStatus.PASS, complete.status)
        assertTrue(checkNotNull(complete.areaCoverageLowerPercent) >= 99.0)

        val noData = BrazilUrbanCoverageGate.calculate(
            context = context(),
            service = BroadcastService.DIGITAL_TV,
            protectedContour = closedSquare(0.0, 0.0, 0.02),
            transmitter = GeoPoint(0.0, 0.0),
            frequencyMHz = 641.0,
            antennaHeightAglM = 100.0,
            thresholdDbuvPerM = 51.0,
            erpAtAzimuthKw = { 1.0 },
            terrain = TerrainElevationProvider { _, _ -> null },
        )
        assertEquals(RegulatoryGateStatus.NO_DATA, noData.status)
        assertTrue(noData.noDataCellCount > 0L)
        assertTrue(checkNotNull(noData.areaCoverageLowerPercent) < checkNotNull(noData.areaCoverageUpperPercent))
    }

    private fun context(): BrazilBroadcastRegulatoryContext {
        val municipality = RegulatoryMunicipalityContext("3550308", "São Paulo", "SP")
        val sector = RegulatoryCensusSector(
            sectorCode = "355030801000001",
            areaKm2 = 4.9,
            residentPopulation = 1_000,
            polygons = listOf(RegulatoryCensusPolygon(listOf(RegulatoryCensusRing(closedSquare(0.0, 0.0, 0.01))))),
        )
        return BrazilBroadcastRegulatoryContext(
            municipality = municipality,
            censusGeometry = RegulatoryCensusGeometrySnapshot(
                municipality = municipality,
                sectors = listOf(sector),
                transmitterInsideMunicipality = true,
                sourceUrl = "https://example.test/SP.gpkg",
                sourcePageUrl = "https://example.test/ibge",
                sourceSha256 = "a".repeat(64),
                sourceByteCount = 4_096L,
                sourceEtag = "\"ibge-test\"",
                sourceLastModified = null,
            ),
            licensedBaseline = LicensedBroadcastBaselineSnapshot(
                stations = emptyList(),
                sourceUrl = "https://example.test/mcom.csv",
                sourcePageUrl = "https://example.test/mcom",
                sourceSha256 = "b".repeat(64),
                sourceByteCount = 1L,
                sourceEtag = "\"mcom-test\"",
                sourceLastModified = null,
                generatedOn = "2026-09-01",
                referenceDate = "2026-08-31",
                sourceRowCount = 0L,
                rejectedRowCount = 0L,
            ),
        )
    }

    private fun closedSquare(latitude: Double, longitude: Double, halfSize: Double): List<GeoPoint> =
        listOf(
            GeoPoint(latitude - halfSize, longitude - halfSize),
            GeoPoint(latitude - halfSize, longitude + halfSize),
            GeoPoint(latitude + halfSize, longitude + halfSize),
            GeoPoint(latitude + halfSize, longitude - halfSize),
            GeoPoint(latitude - halfSize, longitude - halfSize),
        )
}
