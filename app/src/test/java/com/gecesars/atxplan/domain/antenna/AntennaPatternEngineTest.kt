package com.gecesars.atxplan.domain.antenna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternEngineTest {
    @Test
    fun `horizontal interpolation is cyclic and interpolates complex field`() {
        val cut = AntennaPatternCut(
            plane = PatternCutPlane.HORIZONTAL,
            samples = listOf(
                PatternSample(0.0, 1.0, 0.0),
                PatternSample(90.0, 1.0, 180.0),
                PatternSample(180.0, 1.0, 0.0),
                PatternSample(270.0, 1.0, 180.0),
            ),
            provenance = provenance(),
            availability = PatternCutAvailability.AVAILABLE,
        )

        assertEquals(0.0, cut.complexFieldAt(45.0).magnitude, NUMERICAL_TOLERANCE)
        assertEquals(0.0, cut.complexFieldAt(315.0).magnitude, NUMERICAL_TOLERANCE)
        assertEquals(
            cut.complexFieldAt(90.0),
            cut.complexFieldAt(450.0),
        )
        assertEquals(
            cut.complexFieldAt(270.0),
            cut.complexFieldAt(-90.0),
        )
    }

    @Test
    fun `vertical interpolation clamps to available endpoints`() {
        val cut = AntennaPatternCut(
            plane = PatternCutPlane.VERTICAL,
            samples = listOf(
                PatternSample(-60.0, 0.25, 0.0),
                PatternSample(0.0, 1.0, 0.0),
                PatternSample(60.0, 0.5, 0.0),
            ),
            provenance = provenance(),
            availability = PatternCutAvailability.AVAILABLE,
        )

        assertEquals(0.25, cut.complexFieldAt(-90.0).magnitude, STRICT_TOLERANCE)
        assertEquals(0.5, cut.complexFieldAt(90.0).magnitude, STRICT_TOLERANCE)
        assertEquals(0.75, cut.complexFieldAt(30.0).magnitude, STRICT_TOLERANCE)
    }

    @Test
    fun `horizontal correction uses field ratio and a finite null floor`() {
        val pattern = patternWithHorizontalSamples(
            listOf(
                PatternSample(0.0, 1.0),
                PatternSample(90.0, 0.1),
                PatternSample(180.0, 0.0),
                PatternSample(270.0, 0.1),
            ),
        )

        assertEquals(-20.0, pattern.horizontalCorrectionDb(90.0), STRICT_TOLERANCE)
        assertEquals(-85.0, pattern.horizontalCorrectionDb(180.0, floorDb = -85.0), STRICT_TOLERANCE)
        assertEquals(0.0, pattern.horizontalCorrectionDb(360.0), STRICT_TOLERANCE)
    }

    @Test
    fun `physical angle mapping keeps HRP in XZ and VRP in YZ`() {
        val boresight = ApertureDirection.fromAngles(0.0, 0.0)
        val positiveHorizontal = ApertureDirection.fromAngles(90.0, 0.0)
        val positiveVertical = ApertureDirection.fromAngles(0.0, 90.0)

        assertVector(boresight, x = 0.0, y = 0.0, z = 1.0)
        assertVector(positiveHorizontal, x = 1.0, y = 0.0, z = 0.0)
        assertVector(positiveVertical, x = 0.0, y = 1.0, z = 0.0)
        assertEquals(
            0.0,
            AntennaPatternEngine.geographicAzimuthToPhysicalHorizontal(90.0),
            STRICT_TOLERANCE,
        )
        assertEquals(
            90.0,
            AntennaPatternEngine.physicalHorizontalToGeographicAzimuth(0.0),
            STRICT_TOLERANCE,
        )
    }

    @Test
    fun `canonical cut rejects non normalized and unordered untrusted samples`() {
        assertThrows(IllegalArgumentException::class.java) {
            AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = listOf(
                    PatternSample(0.0, 0.8),
                    PatternSample(90.0, 0.4),
                ),
                provenance = provenance(),
                availability = PatternCutAvailability.AVAILABLE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = listOf(
                    PatternSample(0.0, 1.0),
                    PatternSample(-10.0, 0.5),
                ),
                provenance = provenance(),
                availability = PatternCutAvailability.AVAILABLE,
            )
        }
    }

    @Test
    fun `NoData and unsupported availability remain explicit`() {
        val noData = CanonicalPatternAvailability.NoData(
            reason = "The source contains no vertical cut.",
            provenance = provenance(),
        )
        val unsupported = CanonicalPatternAvailability.Unsupported(
            reason = "The source coordinate convention is not declared.",
            provenance = provenance(),
        )

        assertTrue(noData.reason.contains("no vertical cut"))
        assertTrue(unsupported.reason.contains("not declared"))
    }

    private fun patternWithHorizontalSamples(
        horizontalSamples: List<PatternSample>,
    ): CanonicalAntennaPattern {
        val provenance = provenance()
        return CanonicalAntennaPattern(
            id = "directional-test",
            name = "Directional test pattern",
            horizontalCut = AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = horizontalSamples,
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            verticalCut = AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = listOf(
                    PatternSample(-90.0, 1.0),
                    PatternSample(0.0, 1.0),
                    PatternSample(90.0, 1.0),
                ),
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            provenance = provenance,
        )
    }

    private fun provenance(): PatternProvenance = PatternProvenance(
        origin = PatternOrigin.IMPORTED,
        sourceLabel = "Unit-test source",
        sourceFormat = "TEST",
        sourceSha256 = "a".repeat(64),
    )

    private fun assertVector(
        actual: ApertureDirection,
        x: Double,
        y: Double,
        z: Double,
    ) {
        assertEquals(x, actual.x, NUMERICAL_TOLERANCE)
        assertEquals(y, actual.y, NUMERICAL_TOLERANCE)
        assertEquals(z, actual.z, NUMERICAL_TOLERANCE)
    }

    private companion object {
        const val STRICT_TOLERANCE = 1.0e-9
        const val NUMERICAL_TOLERANCE = 1.0e-12
    }
}
