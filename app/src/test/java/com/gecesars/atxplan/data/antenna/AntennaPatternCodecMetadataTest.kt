package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternCodecMetadataTest {
    @Test
    fun `application facade preserves engineering metadata in canonical JSON v2`() {
        val source = CanonicalAntennaPattern.isotropic(
            id = "metadata-pattern",
            name = "Metadata Pattern",
            nominalFrequencyHz = 100_100_000.0,
        )

        val payload = AntennaPatternCodec.encode(
            pattern = source,
            format = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
            options = AntennaPatternEncodeOptions(
                nominalFrequencyHz = 100_100_000.0,
                title = source.name,
                declaredGainDbi = 8.25,
                verticalCutAzimuthDegrees = 15.0,
                beamTiltDegrees = -1.5,
            ),
        )
        val decoded = AntennaPatternCodec.parse(payload, "metadata.atx-antenna.json")

        assertEquals(2, decoded.formatVersion)
        assertTrue(decoded.isCalculationReady)
        assertEquals(8.25, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(15.0, decoded.metadata.verticalCutAzimuthDegrees!!, STRICT_TOLERANCE)
        assertEquals(-1.5, decoded.metadata.beamTiltDegrees!!, STRICT_TOLERANCE)
        assertEquals(100_100_000.0, decoded.metadata.nominalFrequencyHz!!, STRICT_TOLERANCE)
    }

    @Test
    fun `canonical JSON facade rejects metadata frequency that differs from pattern`() {
        val source = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)

        val failure = expectCodecFailure {
            AntennaPatternCodec.encode(
                pattern = source,
                format = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
                options = AntennaPatternEncodeOptions(nominalFrequencyHz = 100_100_000.0),
            )
        }

        assertTrue(failure.message.orEmpty().contains("must exactly match"))
    }

    @Test
    fun `artifact facade retains lossy warnings and source format metadata`() {
        val isotropic = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val source = isotropic.copy(
            horizontalCut = isotropic.horizontalCut.copy(
                samples = isotropic.horizontalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
            ),
        )

        val prn = AntennaPatternCodec.encodeArtifact(
            pattern = source,
            format = AntennaPatternFileFormat.PRN,
            options = AntennaPatternEncodeOptions(declaredGainDbi = 7.75),
        )
        assertTrue(prn.payload.toString(Charsets.UTF_8).contains("GAIN 7.750000 dBi"))
        assertTrue(prn.warnings.any { warning -> warning.contains("exported as 0 degrees") })

        val vSoft = AntennaPatternCodec.encodeArtifact(
            pattern = source,
            format = AntennaPatternFileFormat.VSOFT_VRP,
            options = AntennaPatternEncodeOptions(beamTiltDegrees = -2.25),
        )
        assertTrue(vSoft.payload.toString(Charsets.UTF_8).contains("Beam Tilt = -2.25"))
        assertTrue(vSoft.warnings.any { warning -> warning.contains("preserved V-Soft beam tilt") })
    }

    private fun expectCodecFailure(block: () -> Unit): AntennaPatternCodecException = try {
        block()
        throw AssertionError("Expected AntennaPatternCodecException.")
    } catch (error: AntennaPatternCodecException) {
        error
    }

    private companion object {
        const val STRICT_TOLERANCE = 1.0e-9
    }
}
