package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternPairCodecTest {
    @Test
    fun `independent HRP and VRP form deterministic calculation-ready source bundle`() {
        val reference = CanonicalAntennaPattern.isotropic(
            id = "pair-reference",
            name = "Pair Reference",
            nominalFrequencyHz = 100_100_000.0,
        )
        val hrp = AntennaPatternFileCodecs.encodeAdt(
            cut = reference.horizontalCut,
            nominalFrequencyHz = 100_100_000.0,
            title = "Pair Reference",
        ).payload
        val vrp = AntennaPatternFileCodecs.encodeAdt(
            cut = reference.verticalCut,
            nominalFrequencyHz = 100_100_000.0,
            title = "Pair Reference",
        ).payload
        val sources = listOf(
            AntennaPatternPairSource("../Pair Reference.hrp", hrp),
            AntennaPatternPairSource("Pair Reference.vrp", vrp),
        )

        val first = AntennaPatternPairCodec.parsePair(sources)
        val second = AntennaPatternPairCodec.parsePair(sources.reversed())

        assertTrue(first.pattern.isCalculationReady)
        assertEquals(100_100_000.0, first.pattern.nominalFrequencyHz!!, STRICT_TOLERANCE)
        assertEquals("Pair Reference", first.pattern.name)
        assertEquals("pair-reference.atx-antenna-sources.zip", first.sourceBundleFileName)
        assertEquals(first.sourceBundleSha256, second.sourceBundleSha256)
        assertArrayEquals(first.sourceBundle, second.sourceBundle)
        assertTrue(first.sourceFormatLabel.startsWith("Paired ADT HRP + ADT VRP"))
        assertTrue(first.warnings.any { warning -> warning.contains("deterministic ZIP") })

        val entries = AntennaPatternPairCodec.inspectBundleEntries(first.sourceBundle)
        assertEquals(
            listOf(
                "manifest.json",
                "horizontal/Pair Reference.hrp",
                "vertical/Pair Reference.vrp",
            ),
            entries.keys.toList(),
        )
        assertArrayEquals(hrp, entries.getValue("horizontal/Pair Reference.hrp"))
        assertArrayEquals(vrp, entries.getValue("vertical/Pair Reference.vrp"))
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"kind\": \"ATX_PLAN_HRP_VRP_SOURCE_PAIR\""))
        assertTrue(manifest.contains(sha256(hrp)))
        assertTrue(manifest.contains(sha256(vrp)))
    }

    @Test
    fun `pairing rejects duplicate planes without manufacturing a missing cut`() {
        val reference = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val first = AntennaPatternFileCodecs.encodeAdt(
            reference.horizontalCut,
            99_500_000.0,
            "First HRP",
        ).payload
        val second = AntennaPatternFileCodecs.encodeAdt(
            reference.horizontalCut,
            99_500_000.0,
            "Second HRP",
        ).payload

        val failure = expectCodecFailure {
            AntennaPatternPairCodec.parsePair(
                listOf(
                    AntennaPatternPairSource("first.hrp", first),
                    AntennaPatternPairSource("second.hrp", second),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("HRP is missing or duplicated"))
    }

    @Test
    fun `pairing rejects conflicting nominal frequencies`() {
        val reference = CanonicalAntennaPattern.isotropic()
        val hrp = AntennaPatternFileCodecs.encodeAdt(
            reference.horizontalCut,
            99_500_000.0,
            "Frequency HRP",
        ).payload
        val vrp = AntennaPatternFileCodecs.encodeAdt(
            reference.verticalCut,
            100_100_000.0,
            "Frequency VRP",
        ).payload

        val failure = expectCodecFailure {
            AntennaPatternPairCodec.parsePair(
                listOf(
                    AntennaPatternPairSource("frequency.hrp", hrp),
                    AntennaPatternPairSource("frequency.vrp", vrp),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("nominal frequency values conflict"))
    }

    @Test
    fun `pairing rejects a complete PRN instead of discarding one of its cuts`() {
        val reference = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val prn = AntennaPatternFileCodecs.encodePrn(reference).payload
        val vrp = AntennaPatternFileCodecs.encodeAdt(
            reference.verticalCut,
            99_500_000.0,
            "Companion VRP",
        ).payload

        val failure = expectCodecFailure {
            AntennaPatternPairCodec.parsePair(
                listOf(
                    AntennaPatternPairSource("complete.prn", prn),
                    AntennaPatternPairSource("companion.vrp", vrp),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("exactly one independent HRP or VRP"))
    }

    @Test
    fun `combined pair budget fails before either oversized source is decoded`() {
        val failure = expectCodecFailure {
            AntennaPatternPairCodec.parsePair(
                listOf(
                    AntennaPatternPairSource("large.hrp", ByteArray(8 * 1024 * 1024)),
                    AntennaPatternPairSource("large.vrp", ByteArray(8 * 1024 * 1024)),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("15 MiB pairing limit"))
    }

    @Test
    fun `dot segments and reserved device names become portable bundle entries`() {
        val reference = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val hrp = AntennaPatternFileCodecs.encodeAdt(
            reference.horizontalCut,
            99_500_000.0,
            "Portable HRP",
        ).payload
        val vrp = AntennaPatternFileCodecs.encodeAdt(
            reference.verticalCut,
            99_500_000.0,
            "Portable VRP",
        ).payload

        val paired = AntennaPatternPairCodec.parsePair(
            listOf(
                AntennaPatternPairSource("..", hrp),
                AntennaPatternPairSource("CON.vrp", vrp),
            ),
        )
        val entries = AntennaPatternPairCodec.inspectBundleEntries(paired.sourceBundle)

        assertTrue("horizontal/antenna-pattern.dat" in entries)
        assertTrue("vertical/_CON.vrp" in entries)
        assertArrayEquals(hrp, entries.getValue("horizontal/antenna-pattern.dat"))
        assertArrayEquals(vrp, entries.getValue("vertical/_CON.vrp"))
    }

    @Test
    fun `ambiguous paired PRN requires and applies one explicit interpretation`() {
        val horizontal = """
            NAME Desktop low-attenuation HRP
            FREQUENCY 100.1 MHz
            GAIN 8.5 dBi
            HORIZONTAL 3
            0 0.0 0
            90 0.5 0
            180 1.0 0
        """.trimIndent().plus("\n").toByteArray()
        val vertical = """
            NAME Desktop low-attenuation VRP
            FREQUENCY 100.1 MHz
            GAIN 8.5 dBi
            VERTICAL 3
            -90 0.5 0
            0 0.0 0
            90 1.0 0
        """.trimIndent().plus("\n").toByteArray()
        val sources = listOf(
            AntennaPatternPairSource("desktop.hrp.prn", horizontal),
            AntennaPatternPairSource("desktop.vrp.prn", vertical),
        )

        val failure = expectCodecFailure { AntennaPatternPairCodec.parsePair(sources) }
        assertTrue(failure is PrnValueConventionRequiredException)

        val attenuation = AntennaPatternPairCodec.parsePair(
            sources,
            PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB,
        )
        val linear = AntennaPatternPairCodec.parsePair(
            sources,
            PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD,
        )

        assertEquals(
            Math.pow(10.0, -0.5 / 20.0),
            attenuation.pattern.horizontalCut.samples.single { it.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
        assertEquals(
            0.5,
            linear.pattern.horizontalCut.samples.single { it.angleDegrees == 90.0 }
                .normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
    }

    private fun expectCodecFailure(block: () -> Unit): AntennaPatternCodecException = try {
        block()
        throw AssertionError("Expected AntennaPatternCodecException.")
    } catch (error: AntennaPatternCodecException) {
        error
    }

    private fun sha256(payload: ByteArray): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val STRICT_TOLERANCE = 1.0e-9
    }
}
