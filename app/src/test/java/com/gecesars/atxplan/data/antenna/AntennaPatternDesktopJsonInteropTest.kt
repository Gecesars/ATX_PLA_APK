package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternDesktopJsonInteropTest {
    @Test
    fun `desktop JSON v1 imports both cuts with metadata attenuation and phase`() {
        val payload = completeDesktopFixture().toByteArray(Charsets.UTF_8)

        val detection = AntennaPatternFileCodecs.detect(payload, "desktop.atxpat.json")
        val decoded = AntennaPatternFileCodecs.decode(payload, "desktop.atxpat.json")
        val pattern = requireNotNull(decoded.pattern)

        assertEquals(AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1, detection.format)
        assertEquals(AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1, decoded.detectedFormat)
        assertEquals(
            AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            decoded.valueConvention,
        )
        assertEquals(1, decoded.formatVersion)
        assertEquals("Desktop measured array", pattern.name)
        assertEquals(98.7e6, decoded.metadata.nominalFrequencyHz!!, STRICT_TOLERANCE)
        assertEquals(8.75, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(
            0.5,
            pattern.horizontalCut.samples.single { sample -> sample.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            FIELD_TOLERANCE,
        )
        assertEquals(
            25.0,
            pattern.horizontalCut.samples.single { sample -> sample.angleDegrees == 90.0 }
                .phaseDegrees!!,
            STRICT_TOLERANCE,
        )
        assertEquals(
            -5.0,
            pattern.verticalCut.samples.single { sample -> sample.angleDegrees == -90.0 }
                .phaseDegrees!!,
            STRICT_TOLERANCE,
        )
        assertEquals(
            PatternCoordinateFrame.GEOGRAPHIC_NORTH_CLOCKWISE,
            pattern.provenance.sourceCoordinateFrame,
        )
        assertTrue(pattern.isCalculationReady)
        assertTrue(decoded.warnings.any { warning -> warning.contains("declarative") })
        assertTrue(decoded.warnings.any { warning -> warning.contains("polarization") })
    }

    @Test
    fun `desktop JSON v1 single cut remains independently pairable`() {
        val decoded = AntennaPatternFileCodecs.decode(
            singleVerticalDesktopFixture().toByteArray(Charsets.UTF_8),
            "desktop-vrp.atxpat.json",
        )

        assertEquals(AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1, decoded.detectedFormat)
        assertNull(decoded.pattern)
        assertEquals(1, decoded.cuts.size)
        assertEquals(PatternCutPlane.VERTICAL, decoded.cuts.single().plane)
        assertEquals(
            0.1,
            decoded.cuts.single().samples.first().normalizedFieldAmplitude,
            FIELD_TOLERANCE,
        )
        assertEquals(14.0, decoded.cuts.single().samples.last().phaseDegrees!!, STRICT_TOLERANCE)
    }

    @Test
    fun `desktop JSON v1 export is deterministic explicit and round trips`() {
        val source = exportPattern()

        val first = AntennaPatternFileCodecs.encodeDesktopJsonV1(source, declaredGainDbi = 7.5)
        val second = AntennaPatternFileCodecs.encodeDesktopJsonV1(source, declaredGainDbi = 7.5)
        val decoded = AntennaPatternFileCodecs.decode(first.payload, "export.atxpat.json")
        val roundTrip = requireNotNull(decoded.pattern)

        assertArrayEquals(first.payload, second.payload)
        assertEquals(AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1, first.format)
        assertEquals(".atxpat.json", first.suggestedExtension)
        assertEquals("application/json", first.mediaType)
        assertTrue(first.payload.toString(Charsets.UTF_8).endsWith("\n"))
        assertTrue(first.payload.toString(Charsets.UTF_8).contains("\"format\":\"atx-antenna-pattern\""))
        assertTrue(first.payload.toString(Charsets.UTF_8).contains("\"phase_deg\":0.0"))
        assertTrue(first.warnings.any { warning -> warning.contains("without phase") })
        assertTrue(first.warnings.any { warning -> warning.contains("polarization") })
        assertTrue(first.warnings.any { warning -> warning.contains("400.0 dB") })
        assertTrue(first.warnings.any { warning -> warning.contains("source object") })
        assertEquals(7.5, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(
            0.5,
            roundTrip.horizontalCut.samples.single { sample -> sample.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            FIELD_TOLERANCE,
        )
        assertEquals(
            0.0,
            roundTrip.horizontalCut.samples.single { sample -> sample.angleDegrees == 90.0 }
                .phaseDegrees!!,
            STRICT_TOLERANCE,
        )
    }

    @Test
    fun `desktop JSON facade requires gain and honors the explicit format`() {
        val source = exportPattern()

        val missingGain = expectCodecFailure {
            AntennaPatternCodec.encode(source, AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1)
        }
        assertTrue(missingGain.message.orEmpty().contains("declaredGainDbi"))

        val facadePayload = AntennaPatternCodec.encode(
            pattern = source,
            format = AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1,
            options = AntennaPatternEncodeOptions(declaredGainDbi = 7.5),
        )
        assertArrayEquals(
            AntennaPatternFileCodecs.encodeDesktopJsonV1(source, 7.5).payload,
            facadePayload,
        )
    }

    @Test
    fun `desktop JSON v1 rejects unknown fields invalid ranges conventions and duplicate planes`() {
        val valid = completeDesktopFixture()
        val hostilePayloads = listOf(
            valid.replace(
                "\"version\": 1,",
                "\"version\": 1,\n  \"unexpected\": true,",
            ) to "unknown key",
            valid.replace("\"version\": 1", "\"version\": 2") to "version 1",
            valid.replace(
                "\"nominal_frequency_hz\": 98700000.0",
                "\"nominal_frequency_hz\": 100.0",
            ) to "nominal frequency",
            valid.replace("\"gain_dbi\": 8.75", "\"gain_dbi\": 101.0") to
                "declared gain",
            valid.replace("\"gain_dbi\": 8.75", "\"gain_dbi\": 1e309") to
                "floating-point",
            valid.replace(
                "azimuth-clockwise-from-north-0-360",
                "azimuth-relative-to-boresight",
            ) to "angle_convention",
            valid.replaceFirst("\"attenuation_db\": 20.0", "\"attenuation_db\": 401.0") to
                "attenuation",
            valid.replaceFirst("\"angle_deg\": 180.0", "\"angle_deg\": 360.0") to
                "[0, 360)",
            valid.replaceFirst("\"plane\": \"vertical\"", "\"plane\": \"horizontal\"") to
                "duplicate cut planes",
            valid.replace(VALID_SOURCE_SHA256, VALID_SOURCE_SHA256.uppercase()) to
                "lowercase hexadecimal",
            valid.replaceFirst(
                "\"phase_deg\": 10.0",
                "\"phase_deg\": 10.0, \"unexpected_sample_field\": 1",
            ) to "unknown key",
        )

        hostilePayloads.forEachIndexed { index, (payload, expectedMessage) ->
            val error = expectCodecFailure {
                AntennaPatternFileCodecs.decode(
                    payload.toByteArray(Charsets.UTF_8),
                    "hostile-desktop-$index.json",
                )
            }
            assertTrue(
                "Expected '$expectedMessage' in '${error.message}'.",
                error.message.orEmpty().contains(expectedMessage, ignoreCase = true),
            )
        }
    }

    @Test
    fun `desktop JSON lexical bounds count angle underscore keys before decoding`() {
        val excessiveSamples = buildString {
            append('{')
            repeat(20_001) { index ->
                if (index > 0) append(',')
                append("\"angle_deg\":0")
            }
            append('}')
        }.toByteArray(Charsets.UTF_8)

        val error = expectCodecFailure {
            AntennaPatternFileCodecs.decode(excessiveSamples, "desktop-pressure.json")
        }

        assertTrue(error.message.orEmpty().contains("sample limit before decoding"))
    }

    @Test
    fun `pretty desktop JSON is bounded by samples and tokens rather than legacy line count`() {
        val sampleCount = 5_100
        val samples = buildString {
            repeat(sampleCount) { index ->
                if (index > 0) append(",\n")
                val angle = index.toDouble() * 359.0 / (sampleCount - 1).toDouble()
                val attenuation = if (index == 0) 0.0 else 1.0
                append("""
                    {
                      "angle_deg": $angle,
                      "attenuation_db": $attenuation,
                      "phase_deg": 0.0
                    }
                """.trimIndent())
            }
        }
        val payload = """
            {
              "format": "atx-antenna-pattern",
              "version": 1,
              "name": "Large pretty desktop cut",
              "nominal_frequency_hz": 98700000.0,
              "gain_dbi": 8.75,
              "source": {"format": "Measured fixture", "sha256": "$VALID_SOURCE_SHA256"},
              "cuts": [{
                "plane": "horizontal",
                "angle_convention": "azimuth-clockwise-from-north-0-360",
                "polarization": "horizontal",
                "samples": [
                  $samples
                ]
              }]
            }
        """.trimIndent().plus("\n")
        assertTrue(
            payload.count { character -> character == '\n' } + 1 >
                AntennaPatternCodecLimits.MAX_INPUT_LINES,
        )

        val decoded = AntennaPatternFileCodecs.decode(
            payload.toByteArray(Charsets.UTF_8),
            "large-pretty.atxpat.json",
        )

        assertEquals(sampleCount, decoded.cuts.single().samples.size)
        assertNull(decoded.pattern)
    }

    private fun exportPattern(): CanonicalAntennaPattern {
        val provenance = PatternProvenance(
            origin = PatternOrigin.SYNTHESIZED,
            sourceLabel = "Desktop JSON interop fixture",
            engineId = "desktop-json-interop-test",
        )
        return CanonicalAntennaPattern(
            id = "desktop-json-export-fixture",
            name = "Android synthesized array",
            horizontalCut = AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = listOf(
                    PatternSample(0.0, 1.0, 10.0),
                    PatternSample(90.0, 0.5, null),
                    PatternSample(180.0, 0.0, -15.0),
                ),
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            verticalCut = AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = listOf(
                    PatternSample(-90.0, 0.1, null),
                    PatternSample(0.0, 1.0, 30.0),
                    PatternSample(90.0, 0.1, 55.0),
                ),
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            provenance = provenance,
            nominalFrequencyHz = 98.7e6,
        )
    }

    private fun expectCodecFailure(block: () -> Unit): AntennaPatternCodecException = try {
        block()
        throw AssertionError("Expected AntennaPatternCodecException.")
    } catch (error: AntennaPatternCodecException) {
        error
    }

    private fun completeDesktopFixture(): String = """
        {
          "format": "atx-antenna-pattern",
          "version": 1,
          "name": "Desktop measured array",
          "nominal_frequency_hz": 98700000.0,
          "gain_dbi": 8.75,
          "source": {
            "format": "PAT",
            "sha256": "$VALID_SOURCE_SHA256"
          },
          "cuts": [
            {
              "plane": "horizontal",
              "angle_convention": "azimuth-clockwise-from-north-0-360",
              "polarization": "horizontal",
              "samples": [
                {"angle_deg": 0.0, "attenuation_db": 0.0, "phase_deg": 10.0},
                {"angle_deg": 90.0, "attenuation_db": 6.020599913279624, "phase_deg": 25.0},
                {"angle_deg": 180.0, "attenuation_db": 20.0, "phase_deg": -15.0}
              ]
            },
            {
              "plane": "vertical",
              "angle_convention": "elevation-positive-up-minus90-plus90",
              "polarization": "vertical",
              "samples": [
                {"angle_deg": -90.0, "attenuation_db": 20.0, "phase_deg": -5.0},
                {"angle_deg": 0.0, "attenuation_db": 0.0, "phase_deg": 30.0},
                {"angle_deg": 90.0, "attenuation_db": 20.0, "phase_deg": 55.0}
              ]
            }
          ]
        }
    """.trimIndent().plus("\n")

    private fun singleVerticalDesktopFixture(): String = """
        {
          "format": "atx-antenna-pattern",
          "version": 1,
          "name": "Desktop vertical cut",
          "nominal_frequency_hz": 98700000.0,
          "gain_dbi": 8.75,
          "source": {"format": "PRN", "sha256": "$VALID_SOURCE_SHA256"},
          "cuts": [{
            "plane": "vertical",
            "angle_convention": "elevation-positive-up-minus90-plus90",
            "polarization": "vertical",
            "samples": [
              {"angle_deg": -90.0, "attenuation_db": 20.0, "phase_deg": -4.0},
              {"angle_deg": 0.0, "attenuation_db": 0.0, "phase_deg": 5.0},
              {"angle_deg": 90.0, "attenuation_db": 20.0, "phase_deg": 14.0}
            ]
          }]
        }
    """.trimIndent().plus("\n")

    companion object {
        private const val VALID_SOURCE_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val STRICT_TOLERANCE = 1.0e-9
        private const val FIELD_TOLERANCE = 1.0e-8
    }
}
