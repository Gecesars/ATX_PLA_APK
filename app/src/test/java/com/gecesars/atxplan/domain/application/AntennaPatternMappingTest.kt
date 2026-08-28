package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternMappingTest {
    @Test
    fun normalizedIdentityIgnoresContainerIdentityAndProvenance() {
        val first = CanonicalAntennaPattern.isotropic(
            id = "first-id",
            name = "First label",
            nominalFrequencyHz = 99_500_000.0,
        )
        val alternateProvenance = PatternProvenance(
            origin = PatternOrigin.IMPORTED,
            sourceLabel = "renamed-source.prn",
            sourceFormat = "PRN",
            sourceSha256 = "a".repeat(64),
        )
        val second = CanonicalAntennaPattern(
            id = "second-id",
            name = "Completely different label",
            horizontalCut = first.horizontalCut.copy(
                samples = first.horizontalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
                provenance = alternateProvenance,
            ),
            verticalCut = first.verticalCut.copy(
                samples = first.verticalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
                provenance = alternateProvenance,
            ),
            provenance = alternateProvenance,
            nominalFrequencyHz = first.nominalFrequencyHz,
        )

        assertEquals(
            first.calculateNormalizedContentSha256(peakGainDbi = 6.5),
            second.calculateNormalizedContentSha256(peakGainDbi = 6.5),
        )
    }

    @Test
    fun normalizedIdentityMatchesStableVersionTwoGoldenVector() {
        val pattern = CanonicalAntennaPattern.isotropic(
            nominalFrequencyHz = 99_500_000.0,
        )

        assertEquals(
            VERSION_TWO_GOLDEN_SHA256,
            pattern.calculateNormalizedContentSha256(peakGainDbi = 6.5),
        )
    }

    @Test
    fun normalizedIdentityChangesWithGainFrequencyOrComplexPatternContent() {
        val reference = CanonicalAntennaPattern.isotropic(
            nominalFrequencyHz = 99_500_000.0,
        )
        val differentFrequency = reference.copy(nominalFrequencyHz = 100_100_000.0)
        val directional = reference.copy(
            horizontalCut = cut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = listOf(
                    PatternSample(0.0, 1.0, 0.0),
                    PatternSample(90.0, 0.8, 30.0),
                    PatternSample(180.0, 0.2, 90.0),
                    PatternSample(270.0, 0.8, 30.0),
                ),
            ),
        )

        val referenceHash = reference.calculateNormalizedContentSha256(peakGainDbi = 6.5)
        assertNotEquals(
            referenceHash,
            reference.calculateNormalizedContentSha256(peakGainDbi = 6.6),
        )
        assertNotEquals(
            reference.calculateNormalizedContentSha256(peakGainDbi = null),
            reference.calculateNormalizedContentSha256(peakGainDbi = 0.0),
        )
        assertNotEquals(
            referenceHash,
            differentFrequency.calculateNormalizedContentSha256(peakGainDbi = 6.5),
        )
        assertNotEquals(
            referenceHash,
            directional.calculateNormalizedContentSha256(peakGainDbi = 6.5),
        )
        assertEquals(64, referenceHash.length)
        check(referenceHash.all { character -> character in '0'..'9' || character in 'a'..'f' })
    }

    @Test
    fun projectRoundTripPreservesNormalizedIdentity() {
        val pattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 600_000_000.0)
        val peakGainDbi = 8.25
        val hash = pattern.calculateNormalizedContentSha256(peakGainDbi)
        val record = pattern.toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-1",
                name = "Reference",
                peakGainDbi = peakGainDbi,
                sourceFormat = "test",
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = "artifact-1",
                origin = com.gecesars.atxplan.domain.model.AntennaPatternOrigin.SYNTHESIZED,
            ),
        )

        assertTrue(record.hasVerifiedNormalizedContentIdentity())
        val rehydrated = record.toCanonicalPatternOrNull()
        assertNotNull(rehydrated)
        assertEquals(hash, rehydrated?.calculateNormalizedContentSha256(peakGainDbi))
    }

    @Test
    fun rehydrationRejectsTamperedCalculationFieldsAndStoredHash() {
        val pattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val record = pattern.toProjectRecord(identity(peakGainDbi = 6.5))
        val horizontal = checkNotNull(record.horizontalCut)
        val alteredHorizontal = horizontal.copy(
            normalizedField = horizontal.normalizedField.mapIndexed { index, field ->
                if (index == 1) 0.5 else field
            },
        )

        assertNull(record.copy(peakGainDbi = 6.6).toCanonicalPatternOrNull())
        assertNull(record.copy(nominalFrequencyHz = 100_100_000.0).toCanonicalPatternOrNull())
        assertNull(record.copy(horizontalCut = alteredHorizontal).toCanonicalPatternOrNull())
        assertNull(record.copy(normalizedContentSha256 = "0".repeat(64)).toCanonicalPatternOrNull())
    }

    @Test
    fun legacyGainUnboundHashRemainsSchemaReadableButFailsClosedForCalculation() {
        val pattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val legacyRecord = pattern.toProjectRecord(identity(peakGainDbi = 6.5)).copy(
            normalizedContentSha256 = VERSION_ONE_GAIN_UNBOUND_SHA256,
        )

        assertEquals(1, legacyRecord.canonicalDataVersion)
        assertFalse(legacyRecord.hasVerifiedNormalizedContentIdentity())
        assertNull(legacyRecord.toCanonicalPatternOrNull())
    }

    @Test
    fun structuredCutAvailabilityIsBoundAndPlaceholdersNeverBecomeCalculationReady() {
        val available = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = 99_500_000.0)
        val placeholder = available.copy(
            verticalCut = available.verticalCut.copy(
                availability = PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER,
            ),
        )
        val legacy = available.copy(
            verticalCut = available.verticalCut.copy(
                availability = PatternCutAvailability.LEGACY_UNSPECIFIED,
            ),
        )

        assertTrue(available.isCalculationReady)
        assertFalse(placeholder.isCalculationReady)
        assertFalse(legacy.isCalculationReady)
        assertNotEquals(
            available.calculateNormalizedContentSha256(6.5),
            placeholder.calculateNormalizedContentSha256(6.5),
        )
        assertNotEquals(
            placeholder.calculateNormalizedContentSha256(6.5),
            legacy.calculateNormalizedContentSha256(6.5),
        )

        val placeholderRecord = placeholder.toProjectRecord(identity(peakGainDbi = 6.5))
        val legacyRecord = legacy.toProjectRecord(identity(peakGainDbi = 6.5))
        assertFalse(placeholderRecord.hasVerifiedNormalizedContentIdentity())
        assertFalse(legacyRecord.hasVerifiedNormalizedContentIdentity())
        assertNull(placeholderRecord.toCanonicalPatternOrNull())
        assertNull(legacyRecord.toCanonicalPatternOrNull())
    }

    private fun identity(peakGainDbi: Double?): ProjectAntennaPatternIdentity =
        ProjectAntennaPatternIdentity(
            id = "pattern-1",
            name = "Reference",
            peakGainDbi = peakGainDbi,
            sourceFormat = "test",
            sourceSha256 = null,
            sourceArtifactId = null,
            canonicalArtifactId = "artifact-1",
            origin = com.gecesars.atxplan.domain.model.AntennaPatternOrigin.SYNTHESIZED,
        )

    private fun cut(
        plane: PatternCutPlane,
        samples: List<PatternSample>,
    ): AntennaPatternCut = AntennaPatternCut(
        plane = plane,
        samples = samples,
        provenance = PatternProvenance(
            origin = PatternOrigin.SYNTHESIZED,
            sourceLabel = "Test",
        ),
        availability = PatternCutAvailability.AVAILABLE,
    )

    companion object {
        private const val VERSION_TWO_GOLDEN_SHA256 =
            "09835b20461cde2d7637cf8f3df5b40fc833031034cb259b008ec9e733316e4c"
        private const val VERSION_ONE_GAIN_UNBOUND_SHA256 =
            "345405b099920614fa8adc6030ad116449574d28885d39948fb85f31564c8ecb"
    }
}
