package com.gecesars.atxplan.data.antenna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AntennaPatternDuplicateAngleGoldenTest {
    @Test
    fun `desktop complex field mean contract matches the duplicate angle golden`() {
        val payload = resourceBytes("/fixtures/antenna/duplicate_complex_field.hrp")
        val expected = resourceBytes("/fixtures/antenna/duplicate_complex_field.expected.tsv")
            .toString(Charsets.UTF_8)
            .trimEnd()

        val result = AntennaPatternFileCodecs.decode(payload, "duplicate_complex_field.hrp")
        val actual = result.cuts.single().samples.joinToString("\n") { sample ->
            String.format(
                Locale.ROOT,
                "%.6f\t%.6f\t%.6f",
                sample.angleDegrees,
                sample.normalizedFieldAmplitude,
                sample.phaseDegrees ?: 0.0,
            )
        }

        assertEquals(expected, actual)
        assertTrue(
            result.warnings.any { warning ->
                warning.contains("averaged 1 duplicate") && warning.contains("complex field")
            },
        )
    }

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource $path" }
            .use { input -> input.readBytes() }
}
