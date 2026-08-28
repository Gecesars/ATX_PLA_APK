package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaPatternCatalogUseCaseTest {
    private val useCase = AntennaPatternCatalogUseCase(EpochMillisClock { 20L })

    @Test
    fun installAssignAndDeleteAreStaleSafe() {
        val originalSector = sector()
        val originalProject = project(originalSector)
        val artifact = artifact()
        val pattern = pattern(artifact.id)
        val installed = useCase.install(
            ProjectCatalog(selectedProjectId = originalProject.id, projects = listOf(originalProject)),
            InstallAntennaPatternCommand(originalProject.id, pattern, artifact),
        )

        assertEquals(AntennaPatternMutationStatus.INSTALLED, installed.status)
        assertEquals(pattern, installed.catalog.selectedProject?.antennaPatterns?.single())
        assertEquals(artifact, installed.catalog.selectedProject?.artifacts?.single())

        val assigned = useCase.assignTransmitPattern(
            installed.catalog,
            AssignTransmitAntennaPatternCommand(
                projectId = originalProject.id,
                siteId = "site-1",
                expectedSector = originalSector,
                patternId = pattern.id,
            ),
        )

        assertEquals(AntennaPatternMutationStatus.ASSIGNED, assigned.status)
        assertEquals(
            pattern.id,
            assigned.catalog.selectedProject?.sites?.single()?.sectors?.single()
                ?.transmitAntennaPatternId,
        )
        val blocked = useCase.delete(
            assigned.catalog,
            DeleteAntennaPatternCommand(originalProject.id, pattern),
        )
        assertEquals(AntennaPatternMutationStatus.BLOCKED_REFERENCES, blocked.status)

        val assignedSector = checkNotNull(
            assigned.catalog.selectedProject?.sites?.single()?.sectors?.single(),
        )
        val unassigned = useCase.assignTransmitPattern(
            assigned.catalog,
            AssignTransmitAntennaPatternCommand(
                projectId = originalProject.id,
                siteId = "site-1",
                expectedSector = assignedSector,
                patternId = null,
            ),
        )
        assertNull(
            unassigned.catalog.selectedProject?.sites?.single()?.sectors?.single()
                ?.transmitAntennaPatternId,
        )
        val deleted = useCase.delete(
            unassigned.catalog,
            DeleteAntennaPatternCommand(originalProject.id, pattern),
        )
        assertEquals(AntennaPatternMutationStatus.DELETED, deleted.status)
        assertEquals(emptyList<AntennaPatternRecord>(), deleted.catalog.selectedProject?.antennaPatterns)
        assertEquals(emptyList<ProjectArtifactReference>(), deleted.catalog.selectedProject?.artifacts)
    }

    @Test
    fun duplicateCanonicalArtifactReusesExistingPatternIdentity() {
        val project = project(sector())
        val firstArtifact = artifact("artifact-1")
        val first = useCase.install(
            ProjectCatalog(projects = listOf(project)),
            InstallAntennaPatternCommand(project.id, pattern(firstArtifact.id), firstArtifact),
        )
        val secondArtifact = artifact("artifact-2")
        val duplicate = useCase.install(
            first.catalog,
            InstallAntennaPatternCommand(
                project.id,
                pattern(secondArtifact.id).copy(id = "pattern-2"),
                secondArtifact,
            ),
        )

        assertEquals(AntennaPatternMutationStatus.DUPLICATE, duplicate.status)
        assertEquals("pattern-1", duplicate.patternId)
        assertEquals(1, duplicate.catalog.projects.single().antennaPatterns.size)
        assertEquals(1, duplicate.catalog.projects.single().artifacts.size)
    }

    @Test
    fun sameNormalizedCutsWithDifferentCanonicalArtifactsRemainDistinct() {
        val project = project(sector())
        val firstArtifact = artifact("artifact-1", sha256 = "3".repeat(64))
        val first = useCase.install(
            ProjectCatalog(projects = listOf(project)),
            InstallAntennaPatternCommand(project.id, pattern(firstArtifact.id), firstArtifact),
        )
        val secondArtifact = artifact("artifact-2", sha256 = "4".repeat(64))
        val second = useCase.install(
            first.catalog,
            InstallAntennaPatternCommand(
                project.id,
                pattern(secondArtifact.id).copy(id = "pattern-2"),
                secondArtifact,
            ),
        )

        assertEquals(AntennaPatternMutationStatus.INSTALLED, second.status)
        assertEquals(2, second.catalog.projects.single().antennaPatterns.size)
        assertEquals(2, second.catalog.projects.single().artifacts.size)
    }

    @Test
    fun corruptMatchingStoredIdentityCannotBeBypassedDuringInstall() {
        val existingArtifact = artifact("artifact-1")
        val valid = pattern(existingArtifact.id)
        val corrupt = valid.copy(peakGainDbi = requireNotNull(valid.peakGainDbi) + 0.5)
        val corruptProject = project(sector()).copy(
            antennaPatterns = listOf(corrupt),
            artifacts = listOf(existingArtifact),
        )
        val candidateArtifact = artifact("artifact-2")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            useCase.install(
                ProjectCatalog(projects = listOf(corruptProject)),
                InstallAntennaPatternCommand(
                    corruptProject.id,
                    pattern(candidateArtifact.id).copy(id = "pattern-2"),
                    candidateArtifact,
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("stored antenna pattern"))
    }

    @Test
    fun staleSectorCannotBeOverwritten() {
        val original = sector()
        val project = project(original)
        val artifact = artifact()
        val pattern = pattern(artifact.id)
        val installed = useCase.install(
            ProjectCatalog(projects = listOf(project)),
            InstallAntennaPatternCommand(project.id, pattern, artifact),
        )
        val stale = useCase.assignTransmitPattern(
            installed.catalog,
            AssignTransmitAntennaPatternCommand(
                projectId = project.id,
                siteId = "site-1",
                expectedSector = original.copy(frequencyMHz = 101.1),
                patternId = pattern.id,
            ),
        )

        assertEquals(AntennaPatternMutationStatus.STALE, stale.status)
        assertNull(stale.catalog.projects.single().sites.single().sectors.single().transmitAntennaPatternId)
    }

    @Test
    fun invalidOrGainUnboundIdentityCannotBeInstalledOrAssigned() {
        val originalSector = sector()
        val artifact = artifact()
        val valid = pattern(artifact.id)
        val invalidRecords = listOf(
            valid.copy(normalizedContentSha256 = "2".repeat(64)),
            valid.copy(peakGainDbi = requireNotNull(valid.peakGainDbi) + 0.5),
        )

        invalidRecords.forEach { invalid ->
            val emptyCatalog = ProjectCatalog(projects = listOf(project(originalSector)))
            assertThrows(IllegalArgumentException::class.java) {
                useCase.install(
                    emptyCatalog,
                    InstallAntennaPatternCommand("project-1", invalid, artifact),
                )
            }.also { error ->
                assertTrue(error.message.orEmpty().contains("gain-bound normalized content identity"))
            }

            val loadedProject = project(originalSector).copy(
                antennaPatterns = listOf(invalid),
                artifacts = listOf(artifact),
            )
            val assigned = useCase.assignTransmitPattern(
                ProjectCatalog(projects = listOf(loadedProject)),
                AssignTransmitAntennaPatternCommand(
                    projectId = loadedProject.id,
                    siteId = "site-1",
                    expectedSector = originalSector,
                    patternId = invalid.id,
                ),
            )

            assertEquals(AntennaPatternMutationStatus.NOT_CALCULATION_READY, assigned.status)
            assertTrue(assigned.reason.orEmpty().contains("gain-bound normalized content identity"))
            assertNull(
                assigned.catalog.projects.single().sites.single().sectors.single()
                    .transmitAntennaPatternId,
            )
        }
    }

    private fun project(sector: Sector) = PlannerProject(
        id = "project-1",
        name = "Pattern Test",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
        sites = listOf(
            RadioSite(
                id = "site-1",
                name = "Site",
                location = GeoPoint(-23.5, -46.6),
                sectors = listOf(sector),
            ),
        ),
    )

    private fun sector() = Sector(
        id = "sector-1",
        name = "FM TX",
        azimuthDegrees = 0.0,
        antennaHeightM = 60.0,
        transmitPowerDbm = 50.0,
        antennaGainDbi = 8.0,
        feederLossDb = 1.0,
        frequencyMHz = 99.5,
    )

    private fun pattern(artifactId: String): AntennaPatternRecord =
        CanonicalAntennaPattern.isotropic(
            id = "source-pattern",
            name = "Cardioid Reference",
            nominalFrequencyHz = 99_500_000.0,
        ).toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-1",
                name = "Cardioid Reference",
                peakGainDbi = 8.0,
                sourceFormat = "ATX_ANTENNA_JSON_V1",
                sourceSha256 = "1".repeat(64),
                sourceArtifactId = null,
                canonicalArtifactId = artifactId,
                origin = AntennaPatternOrigin.IMPORTED,
            ),
        )

    private fun artifact(
        id: String = "artifact-1",
        sha256: String = "3".repeat(64),
    ) = ProjectArtifactReference(
        id = id,
        role = ProjectArtifactRole.ANTENNA_PATTERN,
        fileName = "cardioid.atx-antenna.json",
        mediaType = "application/vnd.atxplan.antenna+json",
        sha256 = sha256,
        byteCount = 1_024L,
        createdAtEpochMillis = 20L,
    )
}
