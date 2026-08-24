package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.StudyStatus
import com.gecesars.atxplan.domain.model.StudySummary
import com.gecesars.atxplan.domain.model.StudyType
import com.gecesars.atxplan.domain.model.TiltDegrees
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameProjectUseCaseTest {
    @Test
    fun `renames a nonselected project while preserving its complete RF graph and catalog state`() {
        val original = richCatalog()
        val originalTarget = original.projects[1]

        val result = useCase(nowEpochMillis = 5_000L)(
            original,
            RenameProjectCommand(
                projectId = TARGET_PROJECT_ID,
                expectedName = originalTarget.name,
                newName = "  Renamed RF Project  ",
            ),
        )

        assertTrue(result.didChange)
        assertEquals(RenameProjectStatus.CHANGED, result.status)
        assertEquals("Renamed RF Project", result.project.name)
        assertEquals(5_000L, result.project.updatedAtEpochMillis)
        assertEquals(original.schemaVersion, result.catalog.schemaVersion)
        assertEquals(original.selectedProjectId, result.catalog.selectedProjectId)
        assertEquals(
            original.projects.map(PlannerProject::id),
            result.catalog.projects.map(PlannerProject::id),
        )
        assertEquals(
            originalTarget.copy(
                name = "Renamed RF Project",
                updatedAtEpochMillis = 5_000L,
            ),
            result.project,
        )
        assertSame(original.projects[0], result.catalog.projects[0])
        assertSame(originalTarget.networks, result.project.networks)
        assertSame(originalTarget.sites, result.project.sites)
        assertSame(originalTarget.studies, result.project.studies)
        assertSame(originalTarget.receivers, result.project.receivers)
        assertEquals(
            result.project.networks.single().id,
            result.project.sites.single().sectors.single().networkId,
        )
        assertEquals(
            result.project.networks.single().id,
            result.project.receivers.single().networkId,
        )
        assertEquals(originalTarget.createdAtEpochMillis, result.project.createdAtEpochMillis)
        assertEquals(originalTarget.customer, result.project.customer)
        assertEquals(originalTarget.notes, result.project.notes)
        assertEquals(originalTarget.isDemonstration, result.project.isDemonstration)
        assertNotSame(original, result.catalog)
        assertNotSame(originalTarget, result.project)
        assertSame(result.project, result.catalog.projects[1])
    }

    @Test
    fun `allows duplicate project names because project identity remains ID based`() {
        val original = richCatalog()
        val existingName = original.projects.first().name

        val result = useCase(nowEpochMillis = 5_000L)(
            original,
            RenameProjectCommand(TARGET_PROJECT_ID, "Original RF Project", existingName),
        )

        assertTrue(result.didChange)
        assertEquals(
            listOf(existingName, existingName),
            result.catalog.projects.map(PlannerProject::name),
        )
        assertEquals(TARGET_PROJECT_ID, result.project.id)
    }

    @Test
    fun `matches an imported project ID exactly without requiring it to be trimmed`() {
        val importedId = " imported-project-id "
        val importedProject = simpleProject(
            id = importedId,
            name = "Imported Project",
        )
        val original = ProjectCatalog(
            selectedProjectId = importedId,
            projects = listOf(importedProject),
        )

        val result = useCase(nowEpochMillis = 200L)(
            original,
            RenameProjectCommand(importedId, "Imported Project", "Imported Project Renamed"),
        )

        assertTrue(result.didChange)
        assertEquals(importedId, result.project.id)
        assertEquals(importedId, result.catalog.selectedProjectId)
        assertEquals("Imported Project Renamed", result.project.name)
    }

    @Test
    fun `accepts project name boundaries after trimming`() {
        val original = ProjectCatalog(projects = listOf(simpleProject()))

        val minimumResult = useCase(nowEpochMillis = 200L)(
            original,
            RenameProjectCommand(TARGET_PROJECT_ID, "Original Project", "  AB  "),
        )
        val maximumName = "N".repeat(80)
        val maximumResult = useCase(nowEpochMillis = 300L)(
            minimumResult.catalog,
            RenameProjectCommand(TARGET_PROJECT_ID, "AB", " $maximumName "),
        )

        assertEquals("AB", minimumResult.project.name)
        assertEquals(maximumName, maximumResult.project.name)
    }

    @Test
    fun `rejects blank short and overlong project names`() {
        listOf(
            "",
            "   ",
            "A",
            "  A  ",
            "N".repeat(81),
            "  ${"N".repeat(81)}  ",
        ).forEach { invalidName ->
            assertThrows(IllegalArgumentException::class.java) {
                RenameProjectCommand(TARGET_PROJECT_ID, "Original Project", invalidName)
            }
        }
    }

    @Test
    fun `rejects blank expected names and invalid project IDs without changing the catalog`() {
        assertThrows(IllegalArgumentException::class.java) {
            RenameProjectCommand("   ", "Original Project", "Valid Name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenameProjectCommand(TARGET_PROJECT_ID, "   ", "Valid Name")
        }

        val original = richCatalog()
        val snapshot = original.copy()

        assertThrows(IllegalArgumentException::class.java) {
            useCase(nowEpochMillis = 5_000L)(
                original,
                RenameProjectCommand("missing-project", "Original Project", "Valid Name"),
            )
        }

        assertEquals(snapshot, original)
        assertSame(snapshot.projects, original.projects)
    }

    @Test
    fun `does not normalize a project ID before lookup`() {
        val original = ProjectCatalog(projects = listOf(simpleProject()))

        assertThrows(IllegalArgumentException::class.java) {
            useCase(nowEpochMillis = 200L)(
                original,
                RenameProjectCommand(
                    " $TARGET_PROJECT_ID ",
                    "Original Project",
                    "Valid Name",
                ),
            )
        }

        assertEquals("Original Project", original.projects.single().name)
    }

    @Test
    fun `preserves a future imported timestamp when the wall clock is behind`() {
        val original = ProjectCatalog(
            projects = listOf(
                simpleProject().copy(updatedAtEpochMillis = 50_000L),
            ),
        )

        val result = useCase(nowEpochMillis = 100L)(
            original,
            RenameProjectCommand(TARGET_PROJECT_ID, "Original Project", "Future-Dated Project"),
        )

        assertTrue(result.didChange)
        assertEquals(50_000L, result.project.updatedAtEpochMillis)
        assertEquals("Future-Dated Project", result.project.name)
    }

    @Test
    fun `equal effective name is an identity preserving no-op and does not read the clock`() {
        val original = richCatalog()
        val originalTarget = original.projects[1]
        var clockCalls = 0
        val useCase = RenameProjectUseCase(
            clock = EpochMillisClock {
                clockCalls += 1
                9_999L
            },
        )

        val result = useCase(
            original,
            RenameProjectCommand(
                TARGET_PROJECT_ID,
                originalTarget.name,
                "  ${originalTarget.name}  ",
            ),
        )

        assertFalse(result.didChange)
        assertEquals(RenameProjectStatus.UNCHANGED, result.status)
        assertSame(original, result.catalog)
        assertSame(originalTarget, result.project)
        assertEquals(original, result.catalog)
        assertEquals(originalTarget, result.project)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `stale expected name preserves the latest project and does not read the clock`() {
        val original = richCatalog()
        val originalTarget = original.projects[1]
        var clockCalls = 0
        val useCase = RenameProjectUseCase(
            clock = EpochMillisClock {
                clockCalls += 1
                9_999L
            },
        )

        val result = useCase(
            original,
            RenameProjectCommand(
                projectId = TARGET_PROJECT_ID,
                expectedName = "Outdated Project Name",
                newName = "Competing Rename",
            ),
        )

        assertEquals(RenameProjectStatus.STALE_NAME, result.status)
        assertFalse(result.didChange)
        assertSame(original, result.catalog)
        assertSame(originalTarget, result.project)
        assertEquals("Original RF Project", result.project.name)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `stale command is idempotent when its desired name is already durable`() {
        val original = richCatalog()
        val originalTarget = original.projects[1]
        var clockCalls = 0
        val useCase = RenameProjectUseCase(
            clock = EpochMillisClock {
                clockCalls += 1
                9_999L
            },
        )

        val result = useCase(
            original,
            RenameProjectCommand(
                projectId = TARGET_PROJECT_ID,
                expectedName = "Outdated Project Name",
                newName = "  ${originalTarget.name}  ",
            ),
        )

        assertEquals(RenameProjectStatus.UNCHANGED, result.status)
        assertFalse(result.didChange)
        assertSame(original, result.catalog)
        assertSame(originalTarget, result.project)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `noncanonical durable name is an identity preserving normalized no-op`() {
        val project = simpleProject(name = "  Legacy Project Name  ")
        val original = ProjectCatalog(projects = listOf(project))
        var clockCalls = 0
        val useCase = RenameProjectUseCase(
            clock = EpochMillisClock {
                clockCalls += 1
                9_999L
            },
        )

        val result = useCase(
            original,
            RenameProjectCommand(
                projectId = TARGET_PROJECT_ID,
                expectedName = project.name,
                newName = "Legacy Project Name",
            ),
        )

        assertEquals(RenameProjectStatus.UNCHANGED, result.status)
        assertSame(original, result.catalog)
        assertSame(project, result.project)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `clock failure leaves the input catalog and nested graph untouched`() {
        val original = richCatalog()
        val snapshot = original.copy()
        val originalTarget = original.projects[1]
        val useCase = RenameProjectUseCase(
            clock = EpochMillisClock { error("Clock unavailable") },
        )

        assertThrows(IllegalStateException::class.java) {
            useCase(
                original,
                RenameProjectCommand(
                    TARGET_PROJECT_ID,
                    "Original RF Project",
                    "Renamed Project",
                ),
            )
        }

        assertEquals(snapshot, original)
        assertSame(snapshot.projects, original.projects)
        assertSame(originalTarget.networks, original.projects[1].networks)
        assertSame(originalTarget.sites, original.projects[1].sites)
        assertSame(originalTarget.studies, original.projects[1].studies)
        assertSame(originalTarget.receivers, original.projects[1].receivers)
    }

    @Test
    fun `schema 2 JSON round trip preserves the renamed project and RF references`() {
        val result = useCase(nowEpochMillis = 5_000L)(
            richCatalog(),
            RenameProjectCommand(
                TARGET_PROJECT_ID,
                "Original RF Project",
                "Serialized RF Project",
            ),
        )
        val json = Json { encodeDefaults = true }

        val encoded = json.encodeToString(result.catalog)
        val restored = json.decodeFromString<ProjectCatalog>(encoded)
        val restoredProject = restored.projects[1]

        assertEquals(2, restored.schemaVersion)
        assertEquals(result.catalog, restored)
        assertEquals("Serialized RF Project", restoredProject.name)
        assertEquals(
            restoredProject.networks.single().id,
            restoredProject.sites.single().sectors.single().networkId,
        )
        assertEquals(
            restoredProject.networks.single().id,
            restoredProject.receivers.single().networkId,
        )
    }

    private fun useCase(nowEpochMillis: Long) = RenameProjectUseCase(
        clock = EpochMillisClock { nowEpochMillis },
    )

    private fun richCatalog(): ProjectCatalog {
        val network = RfNetwork(
            id = "network-fwa",
            name = "FWA Network",
            system = RadioSystem.FWA,
            downlinkFrequencyMHz = 3_550.125,
            bandwidthMHz = 80.25,
        )
        val sector = Sector(
            id = "sector-alpha",
            name = "Sector Alpha",
            active = true,
            azimuthDegrees = 123.5,
            electricalTiltDegrees = -2.25,
            antennaHeightM = 42.75,
            transmitPowerDbm = 43.125,
            antennaGainDbi = 17.875,
            feederLossDb = 1.625,
            frequencyMHz = 3_550.125,
            networkId = network.id,
        )
        val site = RadioSite(
            id = "site-hilltop",
            name = "Hilltop TX",
            location = GeoPoint(-23.459_101_234, -46.755_509_876),
            groundElevationM = 1_134.75,
            notes = "Synthetic transmitter",
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = "receiver-warehouse",
            name = "Warehouse CPE",
            networkId = network.id,
            location = GeoCoordinate(
                latitude = LatitudeDegrees(-23.550_521_234),
                longitude = LongitudeDegrees(-46.633_319_876),
            ),
            antennaHeightM = HeightM(12.375),
            antennaGainDbi = GainDbi(18.25),
            systemLossDb = LossDb(1.125),
            sensitivityDbm = PowerDbm(-96.875),
            noiseFigureDb = LossDb(5.625),
            azimuthDegrees = AzimuthDegrees(315.125),
            electricalTiltDegrees = TiltDegrees(-1.75),
            notes = "Synthetic endpoint",
        )
        val study = StudySummary(
            id = "study-link-budget",
            name = "Synthetic Link Budget",
            type = StudyType.POINT_TO_POINT,
            status = StudyStatus.READY,
            updatedAtEpochMillis = 777L,
        )
        val target = PlannerProject(
            id = TARGET_PROJECT_ID,
            name = "Original RF Project",
            customer = "Carrier A",
            notes = "Synthetic project fixture",
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
            isDemonstration = true,
            networks = listOf(network),
            sites = listOf(site),
            studies = listOf(study),
            receivers = listOf(receiver),
        )
        return ProjectCatalog(
            selectedProjectId = SELECTED_PROJECT_ID,
            projects = listOf(
                simpleProject(
                    id = SELECTED_PROJECT_ID,
                    name = "Selected Project",
                ),
                target,
            ),
        )
    }

    private fun simpleProject(
        id: String = TARGET_PROJECT_ID,
        name: String = "Original Project",
    ) = PlannerProject(
        id = id,
        name = name,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private companion object {
        const val TARGET_PROJECT_ID = "project-target"
        const val SELECTED_PROJECT_ID = "project-selected"
    }
}
