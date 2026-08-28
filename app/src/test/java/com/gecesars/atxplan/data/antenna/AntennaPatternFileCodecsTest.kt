package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.AntennaArrayComposer
import com.gecesars.atxplan.domain.antenna.AntennaArrayConfiguration
import com.gecesars.atxplan.domain.antenna.AntennaArrayElement
import com.gecesars.atxplan.domain.antenna.AntennaCompositionOutcome
import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.AperturePositionMeters
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AntennaPatternFileCodecsTest {
    @Test
    fun `duplicate angle retains nonzero phase NoData and blocks coherent composition`() {
        val payload = """
            NAME Mixed duplicate phase availability
            FREQUENCY 100.1 MHz
            VALUE_CONVENTION NORMALIZED_FIELD_AMPLITUDE
            HORIZONTAL 6
            0 1.0
            360 1.0 90.0
            90 0.0
            90 1.0 45.0
            180 1.0 0.0
            270 1.0 0.0
            VERTICAL 3
            -90 0.5 0.0
            0 1.0 0.0
            90 0.5 0.0
        """.trimIndent().plus("\n").toByteArray()

        val decoded = AntennaPatternFileCodecs.decode(payload, "mixed-duplicate-phase.prn")
        val pattern = assertNotNullResult(decoded.pattern)

        assertNull(pattern.horizontalCut.samples.single { it.angleDegrees == 0.0 }.phaseDegrees)
        assertEquals(
            45.0,
            pattern.horizontalCut.samples.single { it.angleDegrees == 90.0 }.phaseDegrees!!,
            STRICT_TOLERANCE,
        )
        assertTrue(decoded.warnings.any { warning -> warning.contains("retained phase NoData") })

        val outcome = AntennaArrayComposer.compose(
            AntennaArrayConfiguration(
                id = "mixed-duplicate-phase-array",
                name = "Mixed duplicate phase array",
                frequencyHz = 100.1e6,
                elements = listOf(
                    AntennaArrayElement(
                        id = "element-1",
                        positionMeters = AperturePositionMeters(0.0, 0.0),
                        pattern = pattern,
                        powerFraction = 1.0,
                    ),
                ),
            ),
        )

        assertTrue(outcome is AntennaCompositionOutcome.NoData)
        val noData = outcome as AntennaCompositionOutcome.NoData
        assertTrue(noData.reason.contains("phase NoData"))
        assertTrue(noData.reason.contains("zero phase will not be assumed"))
    }

    @Test
    fun `explicit PRN attenuation averages duplicate closure in the field domain`() {
        val payload = prnFixture().toByteArray()

        val result = AntennaPatternFileCodecs.decode(payload, "fixture.prn")

        assertEquals(AntennaPatternFileFormat.PRN, result.detectedFormat)
        assertEquals(
            AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            result.valueConvention,
        )
        assertEquals(100.1e6, result.metadata.nominalFrequencyHz!!, STRICT_TOLERANCE)
        assertEquals(14.15, result.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(sha256(payload), result.sourceSha256)
        val pattern = assertNotNullResult(result.pattern)
        assertEquals(4, pattern.horizontalCut.samples.size)
        assertEquals(5, pattern.verticalCut.samples.size)
        assertEquals(
            0.75,
            pattern.horizontalCut.samples.single { it.angleDegrees == 0.0 }.normalizedFieldAmplitude,
            ROUND_TRIP_TOLERANCE,
        )
        assertTrue(
            result.warnings.any { warning ->
                warning.contains("averaged 1 duplicate") && warning.contains("complex field")
            },
        )
        assertTrue(result.warnings.any { it.contains("back-hemisphere") })
        assertTrue(result.warnings.any { it.contains("dBd was converted") })
        assertTrue(
            pattern.provenance.limitations.any {
                it.contains("positive field attenuation", ignoreCase = true)
            },
        )
    }

    @Test
    fun `PRN export and import preserve front cuts while disclosing unavailable back hemisphere`() {
        val source = referencePattern()

        val artifact = AntennaPatternFileCodecs.encodePrn(source, declaredGainDbi = 7.25)
        val decoded = AntennaPatternFileCodecs.decode(artifact.payload, "roundtrip.prn")
        val roundTrip = assertNotNullResult(decoded.pattern)

        assertEquals(".prn", artifact.suggestedExtension)
        assertTrue(
            artifact.payload.toString(Charsets.UTF_8)
                .contains("VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10"),
        )
        assertTrue(artifact.warnings.any { it.contains("NoData") })
        assertEquals(1.0, roundTrip.horizontalAt(0.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.5, roundTrip.horizontalAt(90.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.25, roundTrip.horizontalAt(180.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.1, roundTrip.verticalAt(-90.0), ROUND_TRIP_TOLERANCE)
        assertEquals(1.0, roundTrip.verticalAt(0.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.1, roundTrip.verticalAt(90.0), ROUND_TRIP_TOLERANCE)
        assertEquals(-30.0, roundTrip.horizontalCut.complexFieldAt(90.0).phaseDegrees, 1.0e-6)
        assertEquals(7.25, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertTrue(artifact.payload.toString(Charsets.UTF_8).contains("PHASE_UNIT DEGREE"))
        assertTrue(decoded.warnings.any { it.contains("back-hemisphere") })
    }

    @Test
    fun `desktop three-column single-cut PRN remains independently pairable`() {
        val payload = """
            NAME Desktop horizontal cut
            FREQUENCY 100.1 MHz
            GAIN 8.5 dBi
            VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10
            HORIZONTAL 3
            0 0.0 10.0
            90 6.020599913 25.0
            180 20.0 -15.0
        """.trimIndent().plus("\n").toByteArray()

        val detection = AntennaPatternFileCodecs.detect(payload, "desktop-horizontal.prn")
        val decoded = AntennaPatternFileCodecs.decode(payload, "desktop-horizontal.prn")

        assertEquals(AntennaPatternFileFormat.PRN, detection.format)
        assertNull(decoded.pattern)
        assertEquals(1, decoded.cuts.size)
        assertEquals(PatternCutPlane.HORIZONTAL, decoded.cuts.single().plane)
        assertEquals(25.0, decoded.cuts.single().complexFieldAt(90.0).phaseDegrees, 1.0e-9)
        assertEquals(8.5, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
    }

    @Test
    fun `unmarked desktop PRN requires explicit choice with materially distinct results`() {
        val desktopPayload = """
            NAME Desktop unit interval attenuation
            FREQUENCY 100.1 MHz
            GAIN 8.5 dBi
            HORIZONTAL 3
            0 0 10
            90 0.5 25
            180 1 -15
        """.trimIndent().plus("\n")
        val bytes = desktopPayload.toByteArray()

        val required = expectCodecFailure {
            AntennaPatternFileCodecs.decode(bytes, "desktop-unmarked.prn")
        }
        assertTrue(required is PrnValueConventionRequiredException)
        assertEquals(
            setOf(PatternCutPlane.HORIZONTAL),
            (required as PrnValueConventionRequiredException).ambiguousPlanes,
        )
        assertTrue(
            expectCodecFailure {
                AntennaPatternCodec.parse(bytes, "desktop-unmarked.prn")
            } is PrnValueConventionRequiredException,
        )

        val attenuation = AntennaPatternFileCodecs.decode(
            payload = bytes,
            sourceLabel = "desktop-unmarked.prn",
            prnValueConventionOverride =
                PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB,
        )
        val linear = AntennaPatternFileCodecs.decode(
            payload = bytes,
            sourceLabel = "desktop-unmarked.prn",
            prnValueConventionOverride = PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD,
        )
        assertEquals(
            AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            AntennaPatternFileCodecs.detect(
                payload = bytes,
                sourceLabel = "desktop-unmarked.prn",
                prnValueConventionOverride =
                    PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB,
            ).valueConvention,
        )
        assertEquals(
            AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            attenuation.valueConvention,
        )
        assertEquals(
            Math.pow(10.0, -0.5 / 20.0),
            attenuation.cuts.single().samples.single { sample -> sample.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
        assertEquals(
            0.5,
            linear.cuts.single().samples.single { sample -> sample.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
        assertEquals(
            25.0,
            linear.cuts.single().samples.single { sample -> sample.angleDegrees == 90.0 }
                .phaseDegrees!!,
            STRICT_TOLERANCE,
        )
        assertTrue(attenuation.warnings.any { warning -> warning.contains("selected by the caller") })
        assertTrue(linear.warnings.any { warning -> warning.contains("selected by the caller") })

        val facadeLinear = AntennaPatternCodec.parse(
            input = bytes,
            displayName = "desktop-unmarked.prn",
            prnValueConventionOverride = PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD,
        )
        assertEquals(
            0.5,
            facadeLinear.pattern.horizontalCut.samples
                .single { sample -> sample.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
    }

    @Test
    fun `unit interval PRN fails closed unless its value convention is explicit`() {
        val unmarked = """
            NAME Ambiguous unit interval
            HORIZONTAL 3
            0 1.0
            90 0.5
            180 0.0
            VERTICAL 3
            -90 0.0
            0 1.0
            90 0.0
        """.trimIndent().plus("\n")

        expectCodecFailure {
            AntennaPatternFileCodecs.detect(unmarked.toByteArray(), "ambiguous.prn")
        }.also { error ->
            assertTrue(error is PrnValueConventionRequiredException)
            assertTrue(error.message.orEmpty().contains("confined to [0, 1]"))
        }

        val mixedRanges = unmarked.replace("-90 0.0", "-90 2.0")
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(mixedRanges.toByteArray(), "mixed-ranges.prn")
        }.also { error ->
            assertTrue(error is PrnValueConventionRequiredException)
            assertTrue(error.message.orEmpty().contains("HORIZONTAL values confined to [0, 1]"))
        }

        val explicitLinear = unmarked.replace(
            "NAME Ambiguous unit interval",
            "NAME Explicit linear field\nVALUE_CONVENTION NORMALIZED_FIELD_AMPLITUDE",
        )
        val linear = AntennaPatternFileCodecs.decode(explicitLinear.toByteArray(), "linear.prn")
        assertEquals(
            AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            linear.valueConvention,
        )
        assertEquals(
            0.5,
            assertNotNullResult(linear.pattern).horizontalAt(90.0),
            STRICT_TOLERANCE,
        )

        val explicitAttenuation = explicitLinear.replace(
            "VALUE_CONVENTION NORMALIZED_FIELD_AMPLITUDE",
            "VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10",
        )
        val attenuation = AntennaPatternFileCodecs.decode(
            explicitAttenuation.toByteArray(),
            "attenuation.prn",
        )
        assertEquals(
            AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            attenuation.valueConvention,
        )
        assertEquals(
            Math.pow(10.0, -0.5 / 20.0),
            assertNotNullResult(attenuation.pattern).horizontalAt(90.0),
            STRICT_TOLERANCE,
        )

        val conflicting = explicitLinear.replace(
            "VALUE_CONVENTION NORMALIZED_FIELD_AMPLITUDE",
            "VALUE_CONVENTION NORMALIZED_FIELD_AMPLITUDE\nATTENUATION_UNIT DB",
        )
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(conflicting.toByteArray(), "conflict.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("conflicts")) }

        expectCodecFailure {
            AntennaPatternFileCodecs.decode(
                payload = explicitLinear.toByteArray(),
                sourceLabel = "explicit-linear-conflict.prn",
                prnValueConventionOverride =
                    PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB,
            )
        }.also { error -> assertTrue(error.message.orEmpty().contains("caller-selected")) }

        val nativeAttenuation = unmarked.replace(
            "NAME Ambiguous unit interval",
            "NAME Native attenuation\nATTENUATION_UNIT DB",
        )
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(
                payload = nativeAttenuation.toByteArray(),
                sourceLabel = "native-attenuation-conflict.prn",
                prnValueConventionOverride =
                    PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD,
            )
        }.also { error -> assertTrue(error.message.orEmpty().contains("explicit attenuation")) }
    }

    @Test
    fun `PRN override is rejected for non PRN formats`() {
        val canonicalJson = AntennaPatternFileCodecs.encodeCanonicalJson(referencePattern()).payload

        val decodeError = expectCodecFailure {
            AntennaPatternFileCodecs.decode(
                payload = canonicalJson,
                sourceLabel = "canonical.atx-antenna.json",
                prnValueConventionOverride =
                    PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB,
            )
        }
        assertTrue(decodeError.message.orEmpty().contains("only be applied to a detected PRN"))

        val detectError = expectCodecFailure {
            AntennaPatternFileCodecs.detect(
                payload = canonicalJson,
                sourceLabel = "canonical.atx-antenna.json",
                prnValueConventionOverride =
                    PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD,
            )
        }
        assertTrue(detectError.message.orEmpty().contains("only be applied to a detected PRN"))
    }

    @Test
    fun `ADT HRP voltage and phase round trip without manufacturing the other cut`() {
        val source = referencePattern()

        val artifact = AntennaPatternFileCodecs.encodeAdt(
            cut = source.horizontalCut,
            nominalFrequencyHz = requireNotNull(source.nominalFrequencyHz),
            title = source.name,
        )
        val decoded = AntennaPatternFileCodecs.decode(artifact.payload, "roundtrip.hrp")

        assertEquals(AntennaPatternFileFormat.ADT_HRP, decoded.detectedFormat)
        assertEquals(
            AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE,
            decoded.valueConvention,
        )
        assertNull(decoded.pattern)
        assertEquals(1, decoded.cuts.size)
        assertEquals(source.horizontalCut.samples, decoded.cuts.single().samples)
        assertEquals(100.1e6, decoded.metadata.nominalFrequencyHz!!, STRICT_TOLERANCE)
    }

    @Test
    fun `ADT VRP suffix selects plane and normalizes linear voltage with optional phase absent`() {
        val payload = """
            1/02/97 0:00 ; title : bounded VRP ; engineer : ATX Plan
            100.1000
            1
            0 0 0 1 0
            voltage
            -90 0.2
            0 2.0
            90 0.2
        """.trimIndent().plus("\n").toByteArray()

        val result = AntennaPatternFileCodecs.decode(payload, "fixture.vup")

        assertEquals(AntennaPatternFileFormat.ADT_VRP, result.detectedFormat)
        assertEquals(
            AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            result.valueConvention,
        )
        val cut = result.cuts.single()
        assertEquals(PatternCutPlane.VERTICAL, cut.plane)
        assertNull(cut.samples.first().phaseDegrees)
        assertEquals(1.0, cut.samples[1].normalizedFieldAmplitude, STRICT_TOLERANCE)
        assertEquals(0.1, cut.samples.first().normalizedFieldAmplitude, STRICT_TOLERANCE)
        assertTrue(result.warnings.any { it.contains("normalized to 1 E/Emax") })
    }

    @Test
    fun `desktop ADT vertical PAT label extracts the canonical VRP from full-circle source angles`() {
        val payload = fullCircleAdtFixture().toByteArray()
        val sourceName = "MTV-4 Measured Vertical pattern  470.pat"

        val detection = AntennaPatternFileCodecs.detect(payload, sourceName)
        val result = AntennaPatternFileCodecs.decode(payload, sourceName)
        val cut = result.cuts.single()

        assertEquals(AntennaPatternFileFormat.ADT_VRP, detection.format)
        assertEquals(AntennaPatternFileFormat.ADT_VRP, result.detectedFormat)
        assertEquals(PatternCutPlane.VERTICAL, cut.plane)
        assertEquals(PatternCutAvailability.AVAILABLE, cut.availability)
        assertEquals(181, cut.samples.size)
        assertEquals(-90.0, cut.samples.first().angleDegrees, STRICT_TOLERANCE)
        assertEquals(90.0, cut.samples.last().angleDegrees, STRICT_TOLERANCE)
        assertEquals(1.0, cut.complexFieldAt(0.0).magnitude, STRICT_TOLERANCE)
        assertTrue(result.warnings.any { warning -> warning.contains("omitted 179 samples") })
    }

    @Test
    fun `signed full-circle ADT PAT without a plane declaration fails closed`() {
        val error = expectCodecFailure {
            AntennaPatternFileCodecs.decode(fullCircleAdtFixture().toByteArray(), "ambiguous.pat")
        }

        assertTrue(error.message.orEmpty().contains("plane is ambiguous"))
    }

    @Test
    fun `facade completes a single ADT cut with a disclosed isotropic placeholder`() {
        val artifact = AntennaPatternFileCodecs.encodeAdt(
            cut = referencePattern().horizontalCut,
            nominalFrequencyHz = 100.1e6,
        )

        val parsed = AntennaPatternCodec.parse(artifact.payload, "single.hrp")

        assertEquals(AntennaPatternFileFormat.ADT_HRP, parsed.detectedFormat)
        assertEquals(PatternCutAvailability.AVAILABLE, parsed.pattern.horizontalCut.availability)
        assertEquals(
            PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER,
            parsed.pattern.verticalCut.availability,
        )
        assertFalse(parsed.isCalculationReady)
        assertEquals(3, parsed.pattern.verticalCut.samples.size)
        assertTrue(
            parsed.pattern.verticalCut.samples.all { sample ->
                sample.normalizedFieldAmplitude == 1.0
            },
        )
        assertTrue(parsed.warnings.any { it.contains("not measured or calculated data") })
        assertTrue(
            parsed.pattern.provenance.limitations.any {
                it.contains("isotropic planning placeholder")
            },
        )
        assertEquals(100.1e6, parsed.metadata.nominalFrequencyHz!!, STRICT_TOLERANCE)
        expectCodecFailure {
            AntennaPatternCodec.encode(
                parsed.pattern,
                AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
            )
        }.also { error -> assertTrue(error.message.orEmpty().contains("cannot be exported")) }
        expectCodecFailure {
            AntennaPatternFileCodecs.encodeVSoft(parsed.pattern.verticalCut)
        }.also { error -> assertTrue(error.message.orEmpty().contains("cannot be exported")) }
    }

    @Test
    fun `Progira EDX PAT imports separator gain azimuth and opposite elevation sign`() {
        val payload = """
            'By ADT', 3.000, 1
            0, 1.0000
            90, 0.5000
            180, 0.2000
            270, 0.5000
            999
            1, 3
            342,
            2.0, 0.2500
            1.0, 0.5000
            0.0, 1.0000
        """.trimIndent().plus("\n").toByteArray()

        val result = AntennaPatternFileCodecs.decode(payload, "fixture.ProgiraEDX.pat")

        assertEquals(AntennaPatternFileFormat.PROGIRA_EDX_PAT, result.detectedFormat)
        assertEquals(3.0, result.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(342.0, result.metadata.verticalCutAzimuthDegrees!!, STRICT_TOLERANCE)
        val vertical = assertNotNullResult(result.pattern).verticalCut.samples
        assertEquals(listOf(-2.0, -1.0, 0.0), vertical.map(PatternSample::angleDegrees))
        assertEquals(0.25, vertical.first().normalizedFieldAmplitude, STRICT_TOLERANCE)
        assertTrue(result.warnings.any { it.contains("sign was inverted") })
        assertTrue(result.warnings.any { it.contains("metadata only") })
    }

    @Test
    fun `Progira EDX PAT export round trip preserves normalized reference cuts`() {
        val source = referencePattern()

        val artifact = AntennaPatternFileCodecs.encodeProgiraEdxPat(
            pattern = source,
            declaredGainDbi = 8.75,
            verticalCutAzimuthDegrees = 15.0,
        )
        val decoded = AntennaPatternFileCodecs.decode(artifact.payload, "roundtrip.pat")
        val roundTrip = assertNotNullResult(decoded.pattern)

        assertTrue(artifact.payload.toString(Charsets.UTF_8).startsWith("'By ADT', 8.750, 1"))
        assertEquals(8.75, decoded.metadata.declaredGainDbi!!, STRICT_TOLERANCE)
        assertEquals(15.0, decoded.metadata.verticalCutAzimuthDegrees!!, STRICT_TOLERANCE)
        assertEquals(1.0, roundTrip.horizontalAt(0.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.5, roundTrip.horizontalAt(90.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.1, roundTrip.verticalAt(-90.0), ROUND_TRIP_TOLERANCE)
        assertEquals(1.0, roundTrip.verticalAt(0.0), ROUND_TRIP_TOLERANCE)
        assertEquals(0.1, roundTrip.verticalAt(90.0), ROUND_TRIP_TOLERANCE)
    }

    @Test
    fun `canonical ATX Antenna JSON v2 is deterministic and lossless`() {
        val source = referencePattern()
        val sourceMetadata = AntennaPatternFileMetadata(
            nominalFrequencyHz = source.nominalFrequencyHz,
            declaredGainDbi = 8.75,
            verticalCutAzimuthDegrees = 342.0,
            beamTiltDegrees = -1.25,
        )

        val first = AntennaPatternFileCodecs.encodeCanonicalJson(source, sourceMetadata)
        val second = AntennaPatternFileCodecs.encodeCanonicalJson(source, sourceMetadata)
        val detection = AntennaPatternFileCodecs.detect(first.payload, "pattern.atx-antenna.json")
        val decoded = AntennaPatternFileCodecs.decode(first.payload, "pattern.atx-antenna.json")
        val reencoded = AntennaPatternFileCodecs.encodeCanonicalJson(
            requireNotNull(decoded.pattern),
            decoded.metadata,
        )

        assertArrayEquals(first.payload, second.payload)
        assertArrayEquals(first.payload, reencoded.payload)
        assertTrue(first.payload.toString(Charsets.UTF_8).contains("\"schemaVersion\":2"))
        assertEquals("application/vnd.atx-plan.antenna+json;version=2", first.mediaType)
        assertEquals(AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1, detection.format)
        assertEquals(2, decoded.formatVersion)
        assertEquals(source, decoded.pattern)
        assertEquals(source.provenance, decoded.pattern?.provenance)
        assertEquals(source.horizontalCut.provenance, decoded.pattern?.horizontalCut?.provenance)
        assertEquals(source.verticalCut.provenance, decoded.pattern?.verticalCut?.provenance)
        assertEquals(sourceMetadata, decoded.metadata)
        assertTrue(decoded.warnings.single().contains(decoded.sourceSha256))
        assertTrue(decoded.warnings.single().contains("declarative"))
        assertTrue(requireNotNull(decoded.pattern).isCalculationReady)
    }

    @Test
    fun `legacy canonical JSON without structured cut availability stays review only`() {
        val encoded = AntennaPatternFileCodecs.encodeCanonicalJson(referencePattern()).payload
            .toString(Charsets.UTF_8)
        val legacyPayload = encoded
            .asLegacyJsonV1()
            .replace(",\"availability\":\"AVAILABLE\"", "")
            .toByteArray()

        val decoded = AntennaPatternFileCodecs.decode(
            legacyPayload,
            "legacy.atx-antenna.json",
        )
        val legacyPattern = requireNotNull(decoded.pattern)

        assertEquals(
            PatternCutAvailability.LEGACY_UNSPECIFIED,
            legacyPattern.horizontalCut.availability,
        )
        assertEquals(
            PatternCutAvailability.LEGACY_UNSPECIFIED,
            legacyPattern.verticalCut.availability,
        )
        assertFalse(legacyPattern.isCalculationReady)
        assertTrue(decoded.warnings.any { warning -> warning.contains("review-only") })
        expectCodecFailure {
            AntennaPatternFileCodecs.encodeCanonicalJson(legacyPattern)
        }
    }

    @Test
    fun `legacy JSON v1 with explicit cuts remains calculation ready with metadata NoData`() {
        val source = referencePattern()
        val legacyPayload = AntennaPatternFileCodecs.encodeCanonicalJson(source).payload
            .toString(Charsets.UTF_8)
            .asLegacyJsonV1()
            .toByteArray()

        val decoded = AntennaPatternFileCodecs.decode(legacyPayload, "legacy-v1.json")

        assertEquals(1, decoded.formatVersion)
        assertEquals(source, decoded.pattern)
        assertEquals(
            AntennaPatternFileMetadata(nominalFrequencyHz = source.nominalFrequencyHz),
            decoded.metadata,
        )
        assertTrue(requireNotNull(decoded.pattern).isCalculationReady)
        assertTrue(decoded.warnings.any { warning -> warning.contains("source-format metadata") })
    }

    @Test
    fun `JSON version metadata and availability tampering fails closed`() {
        val source = referencePattern()
        val metadata = AntennaPatternFileMetadata(
            nominalFrequencyHz = source.nominalFrequencyHz,
            declaredGainDbi = 6.5,
            verticalCutAzimuthDegrees = 17.0,
            beamTiltDegrees = -0.75,
        )
        val valid = AntennaPatternFileCodecs.encodeCanonicalJson(source, metadata).payload
            .toString(Charsets.UTF_8)

        listOf(
            valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3") to
                "schema versions 1 and 2",
            valid.replace(
                Regex(",\"metadata\":\\{[^{}]*}}$"),
                "}",
            ) to "requires an explicit metadata object",
            valid.replaceFirst(",\"availability\":\"AVAILABLE\"", "") to
                "requires explicit HORIZONTAL cut availability",
            valid.replaceFirst(
                "\"availability\":\"AVAILABLE\"",
                "\"availability\":\"TRUSTED\"",
            ) to "Unknown pattern cut availability",
            valid.replace(
                "\"verticalCutAzimuthDegrees\":17.0",
                "\"verticalCutAzimuthDegrees\":361.0",
            ) to "vertical-cut azimuth",
            valid.replace(
                "\"sourceLabel\":\"Controlled round-trip fixture\"",
                "\"sourceLabel\":\"Tampered\\u0000source\"",
            ) to "control characters",
        ).forEachIndexed { index, (payload, expectedMessage) ->
            val error = expectCodecFailure {
                AntennaPatternFileCodecs.decode(
                    payload.toByteArray(Charsets.UTF_8),
                    "tampered-$index.atx-antenna.json",
                )
            }
            assertTrue(
                "Expected '$expectedMessage' in '${error.message}'.",
                error.message.orEmpty().contains(expectedMessage, ignoreCase = true),
            )
        }

        val versionSmuggling = valid.replace("\"schemaVersion\":2", "\"schemaVersion\":1")
        val versionError = expectCodecFailure {
            AntennaPatternFileCodecs.decode(
                versionSmuggling.toByteArray(Charsets.UTF_8),
                "version-smuggling.json",
            )
        }
        assertTrue(versionError.message.orEmpty().contains("v1 cannot contain"))
    }

    @Test
    fun `JSON allocation pressure is rejected by lexical preflight before typed decoding`() {
        val excessiveSamples = buildString {
            append('{')
            repeat(20_001) { index ->
                if (index > 0) append(',')
                append("\"angleDegrees\":0")
            }
            append('}')
        }.toByteArray(Charsets.UTF_8)
        val sampleFailure = expectCodecFailure {
            AntennaPatternFileCodecs.decode(excessiveSamples, "sample-pressure.json")
        }
        assertTrue(sampleFailure.message.orEmpty().contains("sample limit before decoding"))

        val oversizedString = (
            "{\"format\":\"" + "x".repeat(4_097) + "\"}"
            ).toByteArray(Charsets.UTF_8)
        val stringFailure = expectCodecFailure {
            AntennaPatternFileCodecs.detect(oversizedString, "string-pressure.json")
        }
        assertTrue(stringFailure.message.orEmpty().contains("oversized string token"))
    }

    @Test
    fun `explicit review-only cut availability never becomes calculation ready`() {
        val valid = AntennaPatternFileCodecs.encodeCanonicalJson(referencePattern()).payload
            .toString(Charsets.UTF_8)
        val reviewOnlyPayload = valid.replaceFirst(
            "\"availability\":\"AVAILABLE\"",
            "\"availability\":\"ISOTROPIC_DISPLAY_PLACEHOLDER\"",
        ).toByteArray(Charsets.UTF_8)

        val decoded = AntennaPatternFileCodecs.decode(reviewOnlyPayload, "review-only.json")
        val pattern = requireNotNull(decoded.pattern)

        assertEquals(
            PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER,
            pattern.horizontalCut.availability,
        )
        assertFalse(pattern.isCalculationReady)
        assertTrue(decoded.warnings.any { warning -> warning.contains("review-only") })
        expectCodecFailure {
            AntennaPatternFileCodecs.encodeCanonicalJson(pattern, decoded.metadata)
        }
    }

    @Test
    fun `canonical JSON export rejects inconsistent frequency metadata`() {
        val source = referencePattern()

        val error = expectCodecFailure {
            AntennaPatternFileCodecs.encodeCanonicalJson(
                pattern = source,
                metadata = AntennaPatternFileMetadata(nominalFrequencyHz = 99.5e6),
            )
        }

        assertTrue(error.message.orEmpty().contains("must exactly match"))
    }

    @Test
    fun `facade encode exposes deterministic bytes and requires real PAT gain`() {
        val source = referencePattern()

        val json = AntennaPatternCodec.encode(
            source,
            AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
        )
        assertArrayEquals(
            AntennaPatternFileCodecs.encodeCanonicalJson(source).payload,
            json,
        )

        val error = expectCodecFailure {
            AntennaPatternCodec.encode(source, AntennaPatternFileFormat.PROGIRA_EDX_PAT)
        }
        assertTrue(error.message.orEmpty().contains("declaredGainDbi"))

        val missingAzimuth = expectCodecFailure {
            AntennaPatternCodec.encode(
                pattern = source,
                format = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
                options = AntennaPatternEncodeOptions(declaredGainDbi = 7.25),
            )
        }
        assertTrue(missingAzimuth.message.orEmpty().contains("verticalCutAzimuthDegrees"))

        val pat = AntennaPatternCodec.encode(
            pattern = source,
            format = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
            options = AntennaPatternEncodeOptions(
                declaredGainDbi = 7.25,
                verticalCutAzimuthDegrees = 0.0,
            ),
        )
        assertTrue(pat.toString(Charsets.UTF_8).startsWith("'By ADT', 7.250, 1"))
    }

    @Test
    fun `duplicate ADT closure averages magnitude and phase as complex field`() {
        val payload = """
            1/02/97 0:00 ; title : duplicate ; engineer : ATX Plan
            100.1000
            1
            0 0 0 1 0
            voltage
            0 1.0 0
            90 1.0 0
            180 0.5 0
            360 1.0 120
        """.trimIndent().plus("\n").toByteArray()

        val result = AntennaPatternFileCodecs.decode(payload, "duplicate.hrp")

        val zero = result.cuts.single().samples.single { it.angleDegrees == 0.0 }
        assertEquals(0.5, zero.normalizedFieldAmplitude, STRICT_TOLERANCE)
        assertEquals(60.0, zero.phaseDegrees!!, STRICT_TOLERANCE)
        assertTrue(result.warnings.any { it.contains("averaged 1 duplicate") })
    }

    @Test
    fun `mobile bounds reject oversized malformed and nonfinite inputs`() {
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(
                ByteArray(AntennaPatternCodecLimits.MAX_INPUT_BYTES + 1),
                "large.prn",
            )
        }.also { error -> assertTrue(error.message.orEmpty().contains("16 MiB")) }

        val tooManyLines = "\n".repeat(AntennaPatternCodecLimits.MAX_INPUT_LINES)
            .toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(tooManyLines, "lines.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("lines")) }

        val nonFinite = """
            NAME Invalid
            HORIZONTAL 2
            0 NaN
            180 0
            VERTICAL 2
            -90 0
            90 0
        """.trimIndent().toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(nonFinite, "nonfinite.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("invalid numeric")) }

        expectCodecFailure {
            AntennaPatternFileCodecs.decode(byteArrayOf(0xC3.toByte()), "invalid.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("valid UTF-8")) }
    }

    @Test
    fun `declared counts negative attenuation and unknown JSON fields fail closed`() {
        val oversizedCount = """
            NAME Invalid
            HORIZONTAL 10001
            VERTICAL 2
            -90 0
            90 0
        """.trimIndent().toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(oversizedCount, "count.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("between 2 and 10000")) }

        val negativeAttenuation = """
            NAME Invalid
            HORIZONTAL 2
            0 -1
            180 0
            VERTICAL 2
            -90 0
            90 0
        """.trimIndent().toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(negativeAttenuation, "negative.prn")
        }.also { error -> assertTrue(error.message.orEmpty().contains("cannot be negative")) }

        val validJson = AntennaPatternFileCodecs.encodeCanonicalJson(referencePattern())
            .payload
            .toString(Charsets.UTF_8)
        val unknownFieldJson = validJson.replace(
            "\"schemaVersion\":2",
            "\"schemaVersion\":2,\"unexpected\":true",
        ).toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.decode(unknownFieldJson, "unknown.json")
        }.also { error -> assertTrue(error.message.orEmpty().contains("invalid")) }
    }

    @Test
    fun `detection rejects incomplete PRN and ambiguous unsupported data`() {
        val singleCut = """
            VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10
            HORIZONTAL 2
            0 0
            180 0
        """.trimIndent().toByteArray()
        assertEquals(
            AntennaPatternFileFormat.PRN,
            AntennaPatternFileCodecs.detect(singleCut, "single-cut.prn").format,
        )

        val unknown = "angle,value\n0,1\n90,0.5\n".toByteArray()
        expectCodecFailure {
            AntennaPatternFileCodecs.detect(unknown, "unknown.csv")
        }.also { error -> assertTrue(error.message.orEmpty().contains("Unsupported")) }
    }

    private fun referencePattern(): CanonicalAntennaPattern {
        val provenance = PatternProvenance(
            origin = PatternOrigin.IMPORTED,
            sourceLabel = "Controlled round-trip fixture",
            sourceFormat = "ATX test fixture",
            sourceSha256 = "a".repeat(64),
            coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
            sourceCoordinateFrame = PatternCoordinateFrame.SOURCE_RELATIVE_UNSPECIFIED,
            engineId = "test-codec-v1",
            warnings = listOf("Controlled warning"),
            limitations = listOf("Controlled fixture, not a measured antenna"),
        )
        return CanonicalAntennaPattern(
            id = "round-trip-reference",
            name = "Round-trip reference",
            horizontalCut = AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = listOf(
                    PatternSample(0.0, 1.0, 0.0),
                    PatternSample(90.0, 0.5, -30.0),
                    PatternSample(180.0, 0.25, 45.0),
                    PatternSample(270.0, 0.5, 30.0),
                ),
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            verticalCut = AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = listOf(
                    PatternSample(-90.0, 0.1, null),
                    PatternSample(0.0, 1.0, 5.0),
                    PatternSample(90.0, 0.1, null),
                ),
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            provenance = provenance,
            nominalFrequencyHz = 100.1e6,
        )
    }

    private fun prnFixture(): String = """
        NAME Explicit attenuation fixture
        MAKE ATX Plan
        FREQUENCY 100.10 MHz
        GAIN 12.00 dBd
        VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10
        ATTENUATION_UNIT DB
        HORIZONTAL 5
        0 6.020600
        90 0.000000
        180 20.000000
        270 6.020600
        360 0.000000
        VERTICAL 7
        0 0.000000
        45 6.020600
        90 20.000000
        180 40.000000
        270 20.000000
        315 6.020600
        360 3.000000
    """.trimIndent().plus("\n")

    private fun fullCircleAdtFixture(): String = buildString {
        appendLine("1/06/07 0:00 ; title : Converted From ARP ; engineer : RFS")
        appendLine("470.00")
        appendLine("1")
        appendLine("0 0 0 1 0")
        appendLine("voltage")
        for (angle in -180 until 180) {
            val amplitude = if (angle == 0) "1.0000" else "0.5000"
            appendLine("$angle $amplitude 0.00")
        }
    }

    private fun CanonicalAntennaPattern.horizontalAt(angleDegrees: Double): Double =
        horizontalCut.samples.single { sample -> sample.angleDegrees == angleDegrees }
            .normalizedFieldAmplitude

    private fun CanonicalAntennaPattern.verticalAt(angleDegrees: Double): Double =
        verticalCut.samples.single { sample -> sample.angleDegrees == angleDegrees }
            .normalizedFieldAmplitude

    private fun assertNotNullResult(pattern: CanonicalAntennaPattern?): CanonicalAntennaPattern {
        assertNotNull(pattern)
        return requireNotNull(pattern)
    }

    private fun expectCodecFailure(block: () -> Unit): AntennaPatternCodecException {
        try {
            block()
        } catch (error: AntennaPatternCodecException) {
            return error
        }
        throw AssertionError("Expected AntennaPatternCodecException.")
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun String.asLegacyJsonV1(): String =
        replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            .replace(Regex(",\"metadata\":\\{[^{}]*}}$"), "}")

    companion object {
        private const val STRICT_TOLERANCE = 1.0e-9
        private const val ROUND_TRIP_TOLERANCE = 2.0e-6
    }
}
