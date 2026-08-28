package com.gecesars.atxplan.ui.antenna

import com.gecesars.atxplan.data.project.ArtifactAvailability
import com.gecesars.atxplan.data.project.ProjectArtifactRepository
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternArtifactAvailabilityTest {
    @Test
    fun `duplicate requires every referenced artifact to be available`() = runTest {
        val fixture = fixture()

        requireAvailableAntennaPatternArtifacts(
            project = fixture.project,
            patternId = PATTERN_ID,
            repository = AvailabilityRepository(emptyMap()),
        )
    }

    @Test
    fun `missing duplicate source is rejected without accepting metadata alone`() = runTest {
        val fixture = fixture()
        val failure = expectIntegrityFailure {
            requireAvailableAntennaPatternArtifacts(
                project = fixture.project,
                patternId = PATTERN_ID,
                repository = AvailabilityRepository(
                    mapOf(fixture.source.id to ArtifactAvailability.MISSING),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("import-source artifact is missing"))
        assertTrue(failure.message.orEmpty().contains("duplicate was not accepted"))
    }

    @Test
    fun `corrupt duplicate canonical blob is rejected`() = runTest {
        val fixture = fixture()
        val failure = expectIntegrityFailure {
            requireAvailableAntennaPatternArtifacts(
                project = fixture.project,
                patternId = PATTERN_ID,
                repository = AvailabilityRepository(
                    mapOf(fixture.canonical.id to ArtifactAvailability.CORRUPT),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("canonical artifact is corrupt"))
        assertTrue(failure.message.orEmpty().contains("duplicate was not accepted"))
    }

    private fun fixture(): Fixture {
        val canonical = artifact(
            id = "artifact-canonical",
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            sha256 = "a".repeat(64),
        )
        val source = artifact(
            id = "artifact-source",
            role = ProjectArtifactRole.IMPORT_SOURCE,
            sha256 = "b".repeat(64),
        )
        val pattern = CanonicalAntennaPattern.isotropic(
            id = "source-pattern",
            name = "Integrity reference",
            nominalFrequencyHz = 99_500_000.0,
        ).toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = PATTERN_ID,
                name = "Integrity reference",
                peakGainDbi = 1.0,
                sourceFormat = "PRN",
                sourceSha256 = source.sha256,
                sourceArtifactId = source.id,
                canonicalArtifactId = canonical.id,
                origin = AntennaPatternOrigin.IMPORTED,
            ),
        )
        return Fixture(
            project = PlannerProject(
                id = "project-1",
                name = "Artifact integrity",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                antennaPatterns = listOf(pattern),
                artifacts = listOf(source, canonical),
            ),
            canonical = canonical,
            source = source,
        )
    }

    private fun artifact(
        id: String,
        role: ProjectArtifactRole,
        sha256: String,
    ) = ProjectArtifactReference(
        id = id,
        role = role,
        fileName = "$id.dat",
        mediaType = "application/octet-stream",
        sha256 = sha256,
        byteCount = 128L,
        createdAtEpochMillis = 1L,
    )

    private suspend fun expectIntegrityFailure(block: suspend () -> Unit): IllegalStateException = try {
        block()
        throw AssertionError("Expected duplicate artifact integrity validation to fail.")
    } catch (error: IllegalStateException) {
        error
    }

    private data class Fixture(
        val project: PlannerProject,
        val canonical: ProjectArtifactReference,
        val source: ProjectArtifactReference,
    )

    private class AvailabilityRepository(
        private val overrides: Map<String, ArtifactAvailability>,
    ) : ProjectArtifactRepository {
        override suspend fun storeArtifact(
            role: ProjectArtifactRole,
            fileName: String,
            mediaType: String,
            input: InputStream,
            maximumBytes: Long,
            expectedSha256: String?,
        ): ProjectArtifactReference = error("Not used by this integrity test.")

        override suspend fun artifactAvailability(
            reference: ProjectArtifactReference,
        ): ArtifactAvailability = overrides[reference.id] ?: ArtifactAvailability.AVAILABLE

        override suspend fun copyArtifact(
            reference: ProjectArtifactReference,
            output: OutputStream,
            maximumBytes: Long,
        ) = error("Not used by this integrity test.")
    }

    private companion object {
        const val PATTERN_ID = "pattern-integrity"
    }
}
