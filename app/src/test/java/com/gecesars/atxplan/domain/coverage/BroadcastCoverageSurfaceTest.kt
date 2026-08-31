package com.gecesars.atxplan.domain.coverage

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.contour.ContourRadialStatus
import com.gecesars.atxplan.domain.contour.RegulatoryContourRadialEvidence
import com.gecesars.atxplan.domain.contour.TerrainElevationProvider
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastCoverageSurfaceTest {
    @Test
    fun desktopPaletteBoundariesAndNoDataRemainExact() {
        assertEquals(0x00000000, BroadcastCoveragePalette.argb(null, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x00000000, BroadcastCoveragePalette.argb(44.999, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80FFA500.toInt(), BroadcastCoveragePalette.argb(45.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x8087CDF9.toInt(), BroadcastCoveragePalette.argb(50.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80FF00FF.toInt(), BroadcastCoveragePalette.argb(55.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80A52929.toInt(), BroadcastCoveragePalette.argb(60.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x800000FF.toInt(), BroadcastCoveragePalette.argb(65.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80007F00.toInt(), BroadcastCoveragePalette.argb(70.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80FF0000.toInt(), BroadcastCoveragePalette.argb(75.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80FFFF00.toInt(), BroadcastCoveragePalette.argb(80.0, CoverageRenderMode.BROADCAST_DISCRETE))
        assertEquals(0x80FFFF00.toInt(), BroadcastCoveragePalette.argb(130.0, CoverageRenderMode.BROADCAST_DISCRETE))

        val continuous = BroadcastCoveragePalette.argb(52.5, CoverageRenderMode.BROADCAST_CONTINUOUS)
        val heatmap = BroadcastCoveragePalette.argb(52.5, CoverageRenderMode.TURBO_HEATMAP)
        assertNotEquals(0, continuous)
        assertNotEquals(0, heatmap)
        assertNotEquals(continuous, heatmap)
    }

    @Test
    fun surfaceIsBoundedTransparentOutsideRadiusAndAppliesVerifiedHrpVrp() {
        var completedRows = 0
        val surface = BrazilDigitalTvCoverageSurfacePlanner.calculate(
            center = GeoPoint(-23.550520, -46.633308),
            radiusKm = 30.0,
            frequencyMHz = 641.0,
            peakErpKw = 1.0,
            antennaHeightAglM = 100.0,
            sector = Sector(
                id = "sector",
                name = "Directional channel 42",
                azimuthDegrees = 90.0,
                electricalTiltDegrees = 0.0,
                antennaHeightM = 100.0,
                transmitPowerDbm = 60.0,
                antennaGainDbi = 2.15,
                feederLossDb = 0.0,
                frequencyMHz = 641.0,
                networkId = "network",
                transmitAntennaPatternId = "directional",
            ),
            assignedPattern = directionalPattern(),
            radialEvidence = List(72) { index ->
                RegulatoryContourRadialEvidence(
                    azimuthDegrees = index * 5.0,
                    distanceKm = 30.0,
                    hnmtM = 100.0,
                    desiredFieldDbuvPerM = 60.0,
                    status = ContourRadialStatus.COMPLETE,
                )
            },
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            inputFingerprint = "a".repeat(64),
            onRowComplete = { completedRows += 1 },
        )

        assertEquals(181, surface.width)
        assertEquals(181, surface.height)
        assertEquals(181, completedRows)
        assertTrue(surface.directionalPatternApplied)
        assertNull(surface.valueAt(0, 0))
        assertNull(surface.valueAt(90, 90))
        val eastField = surface.valueAt(135, 90)
        val westField = surface.valueAt(45, 90)
        assertNotNull(eastField)
        assertNotNull(westField)
        assertTrue(checkNotNull(eastField) - checkNotNull(westField) > 15.0)
        assertTrue(surface.noDataCellCount > 0)
        assertTrue(surface.warnings.any { it.contains("HRP and VRP") })
    }

    private fun directionalPattern() = CanonicalAntennaPattern(
        id = "directional",
        name = "Directional test pattern",
        horizontalCut = AntennaPatternCut(
            plane = PatternCutPlane.HORIZONTAL,
            samples = listOf(
                PatternSample(0.0, 1.0, 0.0),
                PatternSample(90.0, 0.1, 0.0),
                PatternSample(180.0, 0.1, 0.0),
                PatternSample(270.0, 0.1, 0.0),
            ),
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        ),
        verticalCut = AntennaPatternCut(
            plane = PatternCutPlane.VERTICAL,
            samples = listOf(
                PatternSample(-90.0, 1.0, 0.0),
                PatternSample(90.0, 1.0, 0.0),
            ),
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        ),
        provenance = provenance,
        nominalFrequencyHz = 641_000_000.0,
    ).toProjectRecord(
        ProjectAntennaPatternIdentity(
            id = "directional",
            name = "Directional test pattern",
            peakGainDbi = 2.15,
            sourceFormat = "TEST",
            sourceSha256 = null,
            sourceArtifactId = null,
            canonicalArtifactId = "canonical-directional",
            origin = AntennaPatternOrigin.SYNTHESIZED,
        ),
    )

    private companion object {
        val provenance = PatternProvenance(
            origin = PatternOrigin.SYNTHESIZED,
            sourceLabel = "Coverage unit-test pattern",
            coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
            engineId = "coverage-unit-test",
        )
    }
}
