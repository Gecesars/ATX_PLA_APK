package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.toCanonicalPatternOrNull
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternCanonicalArtifactVerifierTest {
    @Test
    fun `verified artifact preserves known zero phase lost by the project cache`() {
        val pattern = CanonicalAntennaPattern.isotropic(
            id = "known-zero",
            name = "Known zero phase",
            nominalFrequencyHz = 100_100_000.0,
        )
        val fixture = fixture(pattern)

        val cached = fixture.record.toCanonicalPatternOrNull()!!
        assertTrue(cached.horizontalCut.samples.all { sample -> sample.phaseDegrees == null })

        val verified = AntennaPatternCanonicalArtifactVerifier.verify(
            fixture.record,
            fixture.artifact,
            fixture.payload,
        )
        assertTrue(verified.pattern.horizontalCut.samples.all { sample -> sample.phaseDegrees == 0.0 })
        assertTrue(verified.pattern.verticalCut.samples.all { sample -> sample.phaseDegrees == 0.0 })
    }

    @Test
    fun `verified artifact preserves mixed phase NoData that the project cache promotes to zero`() {
        val reference = CanonicalAntennaPattern.isotropic(
            id = "mixed-phase",
            name = "Mixed phase availability",
            nominalFrequencyHz = 100_100_000.0,
        )
        val pattern = reference.copy(
            horizontalCut = reference.horizontalCut.copy(
                samples = reference.horizontalCut.samples.map { sample ->
                    sample.copy(
                        phaseDegrees = when (sample.angleDegrees) {
                            90.0 -> null
                            180.0 -> 30.0
                            else -> 0.0
                        },
                    )
                },
            ),
        )
        val fixture = fixture(pattern)

        val cached = fixture.record.toCanonicalPatternOrNull()!!
        assertEquals(0.0, cached.horizontalCut.samples.single { it.angleDegrees == 90.0 }.phaseDegrees!!, 0.0)

        val verified = AntennaPatternCanonicalArtifactVerifier.verify(
            fixture.record,
            fixture.artifact,
            fixture.payload,
        )
        assertNull(
            verified.pattern.horizontalCut.samples.single { it.angleDegrees == 90.0 }.phaseDegrees,
        )
        assertEquals(
            30.0,
            verified.pattern.horizontalCut.samples.single { it.angleDegrees == 180.0 }.phaseDegrees!!,
            0.0,
        )
    }

    private fun fixture(pattern: CanonicalAntennaPattern): Fixture {
        val payload = AntennaPatternCodec.encode(
            pattern = pattern,
            format = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
            options = AntennaPatternEncodeOptions(
                nominalFrequencyHz = pattern.nominalFrequencyHz,
                title = pattern.name,
                declaredGainDbi = 8.0,
                verticalCutAzimuthDegrees = 0.0,
            ),
        )
        val artifact = ProjectArtifactReference(
            id = "artifact-${pattern.id}",
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "${pattern.id}.atx-antenna.json",
            mediaType = "application/vnd.atx-plan.antenna+json;version=2",
            sha256 = sha256(payload),
            byteCount = payload.size.toLong(),
            createdAtEpochMillis = 1L,
        )
        val record = pattern.toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "record-${pattern.id}",
                name = pattern.name,
                peakGainDbi = 8.0,
                sourceFormat = "fixture",
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = artifact.id,
                origin = AntennaPatternOrigin.IMPORTED,
            ),
        )
        return Fixture(record, artifact, payload)
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val record: com.gecesars.atxplan.domain.model.AntennaPatternRecord,
        val artifact: ProjectArtifactReference,
        val payload: ByteArray,
    )
}
