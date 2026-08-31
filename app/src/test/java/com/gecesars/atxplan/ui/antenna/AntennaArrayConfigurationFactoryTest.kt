package com.gecesars.atxplan.ui.antenna

import com.gecesars.atxplan.domain.antenna.AntennaPatternEngine
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaArrayConfigurationFactoryTest {
    @Test
    fun everyExposedTopologyProducesBoundedDeterministicGeometry() {
        val expectedCounts = mapOf(
            AntennaArrayTopology.SINGLE to 1,
            AntennaArrayTopology.VERTICAL_STACK to 3,
            AntennaArrayTopology.HORIZONTAL_LINEAR to 4,
            AntennaArrayTopology.PLANAR to 12,
            AntennaArrayTopology.CIRCULAR to 4,
            AntennaArrayTopology.MULTIPANEL to 12,
        )
        expectedCounts.forEach { (topology, expectedCount) ->
            val configuration = buildArrayConfiguration(
                request(topology),
                CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0),
            )
            assertEquals(topology.name, expectedCount, configuration.elements.size)
            assertEquals(
                topology.name,
                1.0,
                configuration.elements.sumOf { element -> element.powerFraction },
                1e-12,
            )
            assertTrue(configuration.elements.all { element -> element.feedPhaseDegrees.isFinite() })
        }
    }

    @Test
    fun circularAndMultipanelCoordinatesUseDifferentPhysicalFrames() {
        val wavelength = AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND / 100_000_000.0
        val circular = buildArrayConfiguration(
            request(AntennaArrayTopology.CIRCULAR),
            CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0),
        )
        circular.elements.forEach { element ->
            val radiusWavelengths = kotlin.math.hypot(
                element.positionMeters.xMeters,
                element.positionMeters.yMeters,
            ) / wavelength
            assertEquals(1.0, radiusWavelengths, 1e-12)
            assertEquals(0.0, element.positionMeters.zMeters, 1e-12)
            assertEquals(0.0, element.orientation.horizontalAngleDegrees, 1e-12)
        }

        val multipanel = buildArrayConfiguration(
            request(AntennaArrayTopology.MULTIPANEL),
            CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0),
        )
        assertEquals(
            listOf(0.0, 90.0, 180.0, 270.0),
            multipanel.elements.map { it.orientation.horizontalAngleDegrees }.distinct(),
        )
        multipanel.elements.forEach { element ->
            val towerRadiusWavelengths = kotlin.math.hypot(
                element.positionMeters.xMeters,
                element.positionMeters.zMeters,
            ) / wavelength
            assertEquals(1.0, towerRadiusWavelengths, 1e-12)
        }
    }

    @Test
    fun cosineTaperIsSymmetricAndNormalizedInPower() {
        val configuration = buildArrayConfiguration(
            request(
                topology = AntennaArrayTopology.VERTICAL_STACK,
                taper = AntennaArrayTaper.COSINE,
            ),
            CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0),
        )
        val powers = configuration.elements.map { element -> element.powerFraction }
        assertEquals(powers.first(), powers.last(), 1e-12)
        assertTrue(powers[1] > powers[0])
        assertEquals(1.0, powers.sum(), 1e-12)
    }

    private fun request(
        topology: AntennaArrayTopology,
        taper: AntennaArrayTaper = AntennaArrayTaper.UNIFORM,
    ) = AntennaArraySynthesisRequest(
        name = "${topology.label} test",
        basePatternId = null,
        frequencyMHz = 100.0,
        topology = topology,
        columns = 4,
        rows = 3,
        horizontalSpacingWavelengths = 1.0,
        verticalSpacingWavelengths = 0.5,
        horizontalScanDegrees = 0.0,
        verticalScanDegrees = 0.0,
        taper = taper,
    )
}
