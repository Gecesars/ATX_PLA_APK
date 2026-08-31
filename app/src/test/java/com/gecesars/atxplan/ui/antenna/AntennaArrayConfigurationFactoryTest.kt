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
            AntennaArrayTopology.ARBITRARY to 2,
        )
        expectedCounts.forEach { (topology, expectedCount) ->
            val configuration = buildArrayConfiguration(
                request(topology),
                CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0),
                mapOf(
                    "element-pattern" to
                        CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 101_000_000.0),
                ),
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

    @Test
    fun arbitraryElementsPreserveGeometryOrientationPatternAndNormalizedExcitation() {
        val frequencyHz = 100_000_000.0
        val wavelength = AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND / frequencyHz
        val globalPattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = frequencyHz)
        val elementPattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 101_000_000.0)
        val configuration = buildArrayConfiguration(
            request(AntennaArrayTopology.ARBITRARY),
            globalPattern,
            mapOf("element-pattern" to elementPattern),
        )

        assertEquals(null, configuration.declaredScanAngleDegrees)
        assertEquals(listOf("driver", "passive"), configuration.elements.map { it.id })
        assertEquals(1.0, configuration.elements[0].positionMeters.xMeters / wavelength, 1e-12)
        assertEquals(-0.5, configuration.elements[0].positionMeters.yMeters / wavelength, 1e-12)
        assertEquals(0.25, configuration.elements[0].positionMeters.zMeters / wavelength, 1e-12)
        assertEquals(1.0, configuration.elements[0].powerFraction, 1e-12)
        assertEquals(-90.0, configuration.elements[0].feedPhaseDegrees, 1e-9)
        assertEquals(45.0, configuration.elements[0].orientation.horizontalAngleDegrees, 1e-12)
        assertEquals(5.0, configuration.elements[0].orientation.elevationAngleDegrees, 1e-12)
        assertEquals(-10.0, configuration.elements[0].orientation.rollDegrees, 1e-12)
        assertEquals(elementPattern, configuration.elements[0].pattern)
        assertTrue(!configuration.elements[1].active)
        assertEquals(0.0, configuration.elements[1].powerFraction, 1e-12)
    }

    @Test
    fun arbitraryElementsRejectDuplicateIdsAndArraysWithoutActivePower() {
        val base = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 100_000_000.0)
        val duplicate = request(AntennaArrayTopology.ARBITRARY).let { request ->
            request.copy(
                arbitraryElements = request.arbitraryElements.map { element ->
                    element.copy(id = "duplicate")
                },
            )
        }
        val noPower = request(AntennaArrayTopology.ARBITRARY).let { request ->
            request.copy(
                arbitraryElements = request.arbitraryElements.map { element ->
                    element.copy(active = false)
                },
            )
        }

        assertTrue(
            runCatching { buildArrayConfiguration(duplicate, base) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { buildArrayConfiguration(noPower, base) }.exceptionOrNull()
                is IllegalArgumentException,
        )
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
        arbitraryElements = if (topology == AntennaArrayTopology.ARBITRARY) {
            listOf(
                AntennaArbitraryElementRequest(
                    id = "driver",
                    patternId = "element-pattern",
                    xWavelengths = 1.0,
                    yWavelengths = -0.5,
                    zWavelengths = 0.25,
                    relativePower = 3.0,
                    feedPhaseDegrees = 0.0,
                    feedDelayNanoseconds = 2.5,
                    horizontalOrientationDegrees = 45.0,
                    elevationOrientationDegrees = 5.0,
                    rollDegrees = -10.0,
                ),
                AntennaArbitraryElementRequest(
                    id = "passive",
                    xWavelengths = 0.0,
                    yWavelengths = 0.0,
                    zWavelengths = 0.0,
                    relativePower = 9.0,
                    feedPhaseDegrees = 15.0,
                    feedDelayNanoseconds = 0.0,
                    horizontalOrientationDegrees = 0.0,
                    elevationOrientationDegrees = 0.0,
                    rollDegrees = 0.0,
                    active = false,
                ),
            )
        } else {
            emptyList()
        },
    )
}
