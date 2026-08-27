package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.data.project.ProjectRepository
import com.gecesars.atxplan.domain.model.ArchivedProject
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteProjectUseCaseTest {
    private val useCase = DeleteProjectUseCase()

    @Test
    fun `deleting the selected first project selects the next project`() {
        val first = simpleProject("project-first", "First")
        val middle = simpleProject("project-middle", "Middle")
        val last = simpleProject("project-last", "Last")

        val result = useCase(
            ProjectCatalog(
                selectedProjectId = first.id,
                projects = listOf(first, middle, last),
            ),
            DeleteProjectCommand(first),
        )

        assertDeleted(result, first, listOf(middle, last), middle.id)
    }

    @Test
    fun `deleting the selected middle project selects the next project`() {
        val first = simpleProject("project-first", "First")
        val middle = simpleProject("project-middle", "Middle")
        val last = simpleProject("project-last", "Last")

        val result = useCase(
            ProjectCatalog(
                selectedProjectId = middle.id,
                projects = listOf(first, middle, last),
            ),
            DeleteProjectCommand(middle),
        )

        assertDeleted(result, middle, listOf(first, last), last.id)
    }

    @Test
    fun `deleting the selected last project selects the previous project`() {
        val first = simpleProject("project-first", "First")
        val middle = simpleProject("project-middle", "Middle")
        val last = simpleProject("project-last", "Last")

        val result = useCase(
            ProjectCatalog(
                selectedProjectId = last.id,
                projects = listOf(first, middle, last),
            ),
            DeleteProjectCommand(last),
        )

        assertDeleted(result, last, listOf(first, middle), middle.id)
    }

    @Test
    fun `deleting the only project leaves an empty catalog without a selection`() {
        val only = richProject()

        val result = useCase(
            ProjectCatalog(selectedProjectId = only.id, projects = listOf(only)),
            DeleteProjectCommand(only),
        )

        assertDeleted(result, only, emptyList(), null)
        assertNull(result.catalog.selectedProject)
    }

    @Test
    fun `deleting an unselected project keeps a valid selection and every other aggregate unchanged`() {
        val selected = richProject(id = "project-selected", name = "Selected")
        val target = richProject(id = "project-target", name = "Target")
        val peer = richProject(id = "project-peer", name = "Peer")
        val originalProjects = listOf(selected, target, peer)
        val original = ProjectCatalog(
            selectedProjectId = selected.id,
            projects = originalProjects,
        )

        val result = useCase(original, DeleteProjectCommand(target))

        assertDeleted(result, target, listOf(selected, peer), selected.id)
        assertSame(selected, result.catalog.projects[0])
        assertSame(peer, result.catalog.projects[1])
        assertSame(selected.networks, result.catalog.projects[0].networks)
        assertSame(peer.sites, result.catalog.projects[1].sites)
        assertEquals(originalProjects, original.projects)
        assertSame(originalProjects, original.projects)
        assertEquals(target.id, original.projects[1].id)
        assertEquals(selected.id, original.selectedProjectId)
        assertNotSame(original.projects, result.catalog.projects)
    }

    @Test
    fun `successful deletion normalizes an invalid selection deterministically`() {
        val first = simpleProject("project-first", "First")
        val target = simpleProject("project-target", "Target")
        val last = simpleProject("project-last", "Last")

        val deleted = useCase(
            ProjectCatalog(
                selectedProjectId = "project-missing-selection",
                projects = listOf(first, target, last),
            ),
            DeleteProjectCommand(target),
        )

        assertEquals(first.id, deleted.catalog.selectedProjectId)
        assertEquals(listOf(first, last), deleted.catalog.projects)
    }

    @Test
    fun `deleting the effective first project with a null selection selects its successor`() {
        val first = simpleProject("project-first", "First")
        val successor = simpleProject("project-successor", "Successor")
        val catalog = ProjectCatalog(
            selectedProjectId = null,
            projects = listOf(first, successor),
        )
        assertSame(first, catalog.selectedProject)

        val result = useCase(catalog, DeleteProjectCommand(first))

        assertDeleted(result, first, listOf(successor), successor.id)
        assertSame(successor, result.catalog.selectedProject)
    }

    @Test
    fun `a peer name change returns stale without deleting or replacing the latest aggregate`() {
        val expected = richProject()
        val latest = expected.copy(
            name = "Peer Renamed Project",
            updatedAtEpochMillis = expected.updatedAtEpochMillis + 1L,
        )
        val catalog = ProjectCatalog(selectedProjectId = latest.id, projects = listOf(latest))

        val result = useCase(catalog, DeleteProjectCommand(expected))

        assertFalse(result.didDelete)
        assertEquals(DeleteProjectStatus.STALE_PROJECT, result.status)
        assertEquals(expected, result.expectedProject)
        assertSame(latest, result.currentProject)
        assertSame(catalog, result.catalog)
        assertSame(latest, result.catalog.projects.single())
    }

    @Test
    fun `changes anywhere in the RF and study graph make the expected snapshot stale`() {
        val expected = richProject()
        val latestSnapshots = listOf(
            expected.copy(
                networks = expected.networks.map { network ->
                    network.copy(bandwidthMHz = network.bandwidthMHz + 5.0)
                },
            ),
            expected.copy(
                sites = expected.sites.map { site ->
                    site.copy(
                        sectors = site.sectors.map { sector ->
                            sector.copy(transmitPowerDbm = sector.transmitPowerDbm + 1.0)
                        },
                    )
                },
            ),
            expected.copy(
                studies = expected.studies.map { study ->
                    study.copy(status = StudyStatus.COMPLETED)
                },
            ),
            expected.copy(
                receivers = expected.receivers.map { receiver ->
                    receiver.copy(notes = "Updated by a peer")
                },
            ),
        )

        latestSnapshots.forEach { latest ->
            val result = useCase(
                ProjectCatalog(selectedProjectId = latest.id, projects = listOf(latest)),
                DeleteProjectCommand(expected),
            )

            assertEquals(DeleteProjectStatus.STALE_PROJECT, result.status)
            assertSame(latest, result.currentProject)
            assertSame(latest, result.catalog.projects.single())
        }
    }

    @Test
    fun `a peer change to another project is preserved and does not block deletion`() {
        val expectedTarget = richProject(id = "project-target", name = "Target")
        val initialPeer = richProject(id = "project-peer", name = "Initial Peer")
        val latestPeer = initialPeer.copy(
            notes = "Latest durable peer state",
            updatedAtEpochMillis = 9_000L,
        )
        val latestCatalog = ProjectCatalog(
            selectedProjectId = latestPeer.id,
            projects = listOf(expectedTarget, latestPeer),
        )

        val result = useCase(latestCatalog, DeleteProjectCommand(expectedTarget))

        assertTrue(result.didDelete)
        assertEquals(listOf(latestPeer), result.catalog.projects)
        assertSame(latestPeer, result.catalog.projects.single())
        assertEquals(latestPeer.id, result.catalog.selectedProjectId)
    }

    @Test
    fun `a project removed by a peer returns not found without throwing`() {
        val expected = richProject(id = "project-removed", name = "Removed")
        val survivor = richProject(id = "project-survivor", name = "Survivor")
        val latestCatalog = ProjectCatalog(
            selectedProjectId = expected.id,
            projects = listOf(survivor),
        )

        val result = useCase(latestCatalog, DeleteProjectCommand(expected))

        assertFalse(result.didDelete)
        assertEquals(DeleteProjectStatus.NOT_FOUND, result.status)
        assertNull(result.currentProject)
        assertSame(latestCatalog, result.catalog)
        assertSame(survivor, result.catalog.projects.single())
        assertEquals(expected.id, result.catalog.selectedProjectId)
    }

    @Test
    fun `a project archived by a peer returns archived without permanent deletion`() {
        val expected = richProject(id = "project-archived", name = "Archived")
        val archived = ArchivedProject(
            project = expected,
            archivedAtEpochMillis = 10L,
            originalProjectIndex = 0,
        )
        val latestCatalog = ProjectCatalog(archivedProjects = listOf(archived))

        val result = useCase(latestCatalog, DeleteProjectCommand(expected))

        assertFalse(result.didDelete)
        assertEquals(DeleteProjectStatus.ARCHIVED, result.status)
        assertNull(result.currentProject)
        assertSame(latestCatalog, result.catalog)
        assertSame(archived, result.catalog.archivedProjects.single())
    }

    @Test
    fun `structurally equal snapshots delete imported IDs by exact identity`() {
        val imported = richProject(id = " imported project ", name = "Imported")
        val decodedEquivalentSnapshot = imported.copy(
            networks = imported.networks.map { network -> network.copy() },
            sites = imported.sites.map { site ->
                site.copy(sectors = site.sectors.map { sector -> sector.copy() })
            },
            studies = imported.studies.map { study -> study.copy() },
            receivers = imported.receivers.map { receiver -> receiver.copy() },
        )
        val catalog = ProjectCatalog(selectedProjectId = imported.id, projects = listOf(imported))

        val result = useCase(catalog, DeleteProjectCommand(decodedEquivalentSnapshot))
        val trimmedIdResult = useCase(
            catalog,
            DeleteProjectCommand(simpleProject(imported.id.trim(), imported.name)),
        )

        assertEquals(DeleteProjectStatus.DELETED, result.status)
        assertEquals(DeleteProjectStatus.NOT_FOUND, trimmedIdResult.status)
    }

    @Test
    fun `schema 4 JSON round trip preserves the deletion result catalog`() {
        val first = richProject(id = "project-first", name = "First")
        val target = richProject(id = "project-target", name = "Target")
        val result = useCase(
            ProjectCatalog(selectedProjectId = target.id, projects = listOf(first, target)),
            DeleteProjectCommand(target),
        )
        val json = Json { encodeDefaults = true }

        val restored = json.decodeFromString<ProjectCatalog>(
            json.encodeToString(result.catalog),
        )

        assertEquals(4, restored.schemaVersion)
        assertEquals(result.catalog, restored)
        assertEquals(first.id, restored.selectedProjectId)
        assertEquals(listOf(first), restored.projects)
    }

    @Test
    fun `result invariants reject inconsistent status payloads`() {
        val project = simpleProject()
        val validCatalog = ProjectCatalog(
            selectedProjectId = project.id,
            projects = listOf(project),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DeleteProjectResult(
                catalog = validCatalog,
                expectedProject = project,
                currentProject = project,
                status = DeleteProjectStatus.DELETED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeleteProjectResult(
                catalog = validCatalog,
                expectedProject = project,
                currentProject = null,
                status = DeleteProjectStatus.STALE_PROJECT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeleteProjectResult(
                catalog = ProjectCatalog(projects = emptyList()),
                expectedProject = project,
                currentProject = project,
                status = DeleteProjectStatus.NOT_FOUND,
            )
        }
        val staleWithUnchangedInvalidSelection = DeleteProjectResult(
            catalog = ProjectCatalog(selectedProjectId = null, projects = listOf(project)),
            expectedProject = project.copy(name = "Stale"),
            currentProject = project,
            status = DeleteProjectStatus.STALE_PROJECT,
        )
        assertNull(staleWithUnchangedInvalidSelection.catalog.selectedProjectId)
    }

    @Test
    fun `AppUseCases checks deletion against the latest catalog inside the transaction`() = runBlocking {
        val expected = richProject()
        val latest = expected.copy(notes = "Peer edit already committed")
        var durableCatalog = ProjectCatalog(
            selectedProjectId = latest.id,
            projects = listOf(latest),
        )
        val repository = object : ProjectRepository {
            override suspend fun loadCatalog(): ProjectCatalog = durableCatalog

            override suspend fun updateCatalog(
                transform: (ProjectCatalog) -> ProjectCatalog,
            ): ProjectCatalog {
                durableCatalog = transform(durableCatalog)
                return durableCatalog
            }
        }
        val useCases = AppUseCases.create(
            repository = repository,
            dispatchers = AppCoroutineDispatchers(
                storage = Dispatchers.Unconfined,
                computation = Dispatchers.Unconfined,
            ),
        )
        var deletionResult: DeleteProjectResult? = null

        val updated = useCases.updateProjectCatalog { latestCatalog ->
            useCases.deleteProject(
                latestCatalog,
                DeleteProjectCommand(expected),
            ).also { result -> deletionResult = result }.catalog
        }

        assertEquals(DeleteProjectStatus.STALE_PROJECT, deletionResult?.status)
        assertSame(latest, deletionResult?.currentProject)
        assertEquals(ProjectCatalog(selectedProjectId = latest.id, projects = listOf(latest)), updated)
        assertEquals(updated, durableCatalog)
    }

    private fun assertDeleted(
        result: DeleteProjectResult,
        deletedProject: PlannerProject,
        remainingProjects: List<PlannerProject>,
        selectedProjectId: String?,
    ) {
        assertTrue(result.didDelete)
        assertEquals(DeleteProjectStatus.DELETED, result.status)
        assertEquals(deletedProject, result.expectedProject)
        assertSame(deletedProject, result.currentProject)
        assertEquals(remainingProjects, result.catalog.projects)
        assertEquals(selectedProjectId, result.catalog.selectedProjectId)
        assertTrue(result.catalog.projects.none { project -> project.id == deletedProject.id })
    }

    private fun simpleProject(
        id: String = "project-target",
        name: String = "Target",
    ) = PlannerProject(
        id = id,
        name = name,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private fun richProject(
        id: String = "project-target",
        name: String = "Target RF Project",
    ): PlannerProject {
        val network = RfNetwork(
            id = "$id-network",
            name = "FWA Network",
            system = RadioSystem.FWA,
            downlinkFrequencyMHz = 3_550.125,
            bandwidthMHz = 80.25,
        )
        val sector = Sector(
            id = "$id-sector",
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
            id = "$id-site",
            name = "Hilltop TX",
            location = GeoPoint(-23.459_101_234, -46.755_509_876),
            groundElevationM = 1_134.75,
            notes = "Synthetic transmitter",
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = "$id-receiver",
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
            id = "$id-study",
            name = "Synthetic Link Budget",
            type = StudyType.POINT_TO_POINT,
            status = StudyStatus.READY,
            updatedAtEpochMillis = 777L,
        )
        return PlannerProject(
            id = id,
            name = name,
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
    }
}
