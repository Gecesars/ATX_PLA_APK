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
import com.gecesars.atxplan.domain.study.ProjectLinkStudyEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateProjectUseCaseTest {
    @Test
    fun `duplicates the complete project aggregate then appends and selects the copy`() {
        val source = richProject()
        val before = simpleProject("project-before", "Before")
        val after = simpleProject("project-after", "After")
        val original = ProjectCatalog(
            selectedProjectId = before.id,
            projects = listOf(before, source, after),
        )

        val result = useCase(projectId = DUPLICATED_PROJECT_ID, nowEpochMillis = 5_000L)(
            original,
            DuplicateProjectCommand(source.id, "  Field Deployment Copy  "),
        )

        val duplicate = result.duplicatedProject
        assertEquals(source, result.sourceProject)
        assertSame(source, result.sourceProject)
        assertEquals(DUPLICATED_PROJECT_ID, duplicate.id)
        assertEquals("Field Deployment Copy", duplicate.name)
        assertEquals(5_000L, duplicate.createdAtEpochMillis)
        assertEquals(5_000L, duplicate.updatedAtEpochMillis)
        assertEquals(source.customer, duplicate.customer)
        assertEquals(source.notes, duplicate.notes)
        assertEquals(source.isDemonstration, duplicate.isDemonstration)
        assertEquals(source.networks, duplicate.networks)
        assertEquals(source.sites, duplicate.sites)
        assertEquals(source.studies, duplicate.studies)
        assertEquals(source.receivers, duplicate.receivers)
        assertEquals(source.studies.single().updatedAtEpochMillis, duplicate.studies.single().updatedAtEpochMillis)
        assertEquals(source.networks.single().id, duplicate.sites.single().sectors.single().networkId)
        assertEquals(source.networks.single().id, duplicate.receivers.single().networkId)

        assertNotSame(source, duplicate)
        assertNotSame(source.networks, duplicate.networks)
        assertNotSame(source.networks.single(), duplicate.networks.single())
        assertNotSame(source.sites, duplicate.sites)
        assertNotSame(source.sites.single(), duplicate.sites.single())
        assertNotSame(source.sites.single().sectors, duplicate.sites.single().sectors)
        assertNotSame(source.sites.single().sectors.single(), duplicate.sites.single().sectors.single())
        assertNotSame(source.studies, duplicate.studies)
        assertNotSame(source.studies.single(), duplicate.studies.single())
        assertNotSame(source.receivers, duplicate.receivers)
        assertNotSame(source.receivers.single(), duplicate.receivers.single())

        assertEquals(original.schemaVersion, result.catalog.schemaVersion)
        assertEquals(
            listOf(before.id, source.id, after.id, DUPLICATED_PROJECT_ID),
            result.catalog.projects.map(PlannerProject::id),
        )
        assertEquals(DUPLICATED_PROJECT_ID, result.catalog.selectedProjectId)
        assertSame(before, result.catalog.projects[0])
        assertSame(source, result.catalog.projects[1])
        assertSame(after, result.catalog.projects[2])
        assertSame(duplicate, result.catalog.projects[3])
        assertEquals(listOf(before, source, after), original.projects)
        assertEquals(before.id, original.selectedProjectId)
    }

    @Test
    fun `copies the latest source state supplied by the catalog transaction`() {
        val initialSource = richProject()
        val latestNetwork = RfNetwork(
            id = "network-latest",
            name = "Latest Network",
            system = RadioSystem.NR_5G,
            downlinkFrequencyMHz = 3_650.0,
            bandwidthMHz = 100.0,
        )
        val latestSource = initialSource.copy(
            name = "Latest Durable Source",
            notes = "Committed by a peer before duplication.",
            updatedAtEpochMillis = 9_000L,
            networks = initialSource.networks + latestNetwork,
        )
        val latestCatalog = ProjectCatalog(
            selectedProjectId = latestSource.id,
            projects = listOf(latestSource),
        )

        val result = useCase(projectId = DUPLICATED_PROJECT_ID, nowEpochMillis = 10_000L)(
            latestCatalog,
            DuplicateProjectCommand(initialSource.id, "Latest Durable Copy"),
        )

        assertSame(latestSource, result.sourceProject)
        assertEquals(latestSource.networks, result.duplicatedProject.networks)
        assertEquals(latestSource.notes, result.duplicatedProject.notes)
        assertEquals(2, result.duplicatedProject.networks.size)
        assertEquals(10_000L, result.duplicatedProject.createdAtEpochMillis)
        assertEquals(10_000L, result.duplicatedProject.updatedAtEpochMillis)
    }

    @Test
    fun `duplicate preserves immutable study origin instead of rebasing its source snapshot`() {
        val base = richProject()
        val site = base.sites.single()
        val record = ProjectLinkStudyEngine.calculate(
            id = "link-study-origin",
            name = "Origin Study",
            createdAtEpochMillis = 900L,
            projectId = base.id,
            projectName = base.name,
            network = base.networks.single(),
            site = site,
            sector = site.sectors.single(),
            receiver = base.receivers.single(),
        )
        val source = base.copy(
            updatedAtEpochMillis = record.createdAtEpochMillis,
            studies = base.studies + StudySummary(
                id = record.id,
                name = record.name,
                type = StudyType.POINT_TO_POINT,
                status = StudyStatus.COMPLETED,
                updatedAtEpochMillis = record.createdAtEpochMillis,
            ),
            linkStudies = listOf(record),
        )

        val duplicate = useCase(DUPLICATED_PROJECT_ID, 1_000L)(
            ProjectCatalog(selectedProjectId = source.id, projects = listOf(source)),
            DuplicateProjectCommand(source.id, "Study Origin Copy"),
        ).duplicatedProject

        assertEquals(source.linkStudies, duplicate.linkStudies)
        assertEquals(SOURCE_PROJECT_ID, duplicate.linkStudies.single().input.projectId)
        assertEquals("Source RF Project", duplicate.linkStudies.single().input.projectName)
        assertEquals(
            duplicate.linkStudies.single().createdAtEpochMillis,
            duplicate.studies.single { it.id == record.id }.updatedAtEpochMillis,
        )
    }

    @Test
    fun `normalizes valid project name boundaries and rejects invalid names`() {
        val source = simpleProject(SOURCE_PROJECT_ID, "Source")
        val catalog = ProjectCatalog(projects = listOf(source))
        val maximumName = "N".repeat(80)

        val minimumResult = useCase("project-minimum", 200L)(
            catalog,
            DuplicateProjectCommand(source.id, "  AB  "),
        )
        val maximumResult = useCase("project-maximum", 300L)(
            catalog,
            DuplicateProjectCommand(source.id, " $maximumName "),
        )

        assertEquals("AB", minimumResult.duplicatedProject.name)
        assertEquals(maximumName, maximumResult.duplicatedProject.name)
        listOf("", "   ", "A", "  A  ", "N".repeat(81)).forEach { invalidName ->
            assertThrows(IllegalArgumentException::class.java) {
                DuplicateProjectCommand(source.id, invalidName)
            }
        }
    }

    @Test
    fun `matches schema-valid imported project IDs exactly`() {
        val importedId = " imported-project-id "
        val source = simpleProject(importedId, "Imported")
        val catalog = ProjectCatalog(
            selectedProjectId = importedId,
            projects = listOf(source),
        )
        var generatedIdCalls = 0
        val useCase = DuplicateProjectUseCase(
            idGenerator = ProjectDuplicationIdGenerator {
                generatedIdCalls += 1
                DUPLICATED_PROJECT_ID
            },
            clock = EpochMillisClock { 500L },
        )

        val result = useCase(
            catalog,
            DuplicateProjectCommand(importedId, "Imported Copy"),
        )

        assertEquals(importedId, result.sourceProject.id)
        assertEquals(1, generatedIdCalls)
        assertThrows(IllegalArgumentException::class.java) {
            useCase(
                catalog,
                DuplicateProjectCommand(importedId.trim(), "Missing Source Copy"),
            )
        }
        assertEquals(1, generatedIdCalls)
        assertThrows(IllegalArgumentException::class.java) {
            DuplicateProjectCommand("   ", "Invalid Source Copy")
        }
    }

    @Test
    fun `rejects route-unsafe generated project IDs before reading the clock`() {
        val original = ProjectCatalog(projects = listOf(simpleProject()))
        val snapshot = original.copy()
        val invalidIds = listOf(
            "",
            "   ",
            " project-copy ",
            "x".repeat(129),
            "project-copy\nunsafe",
        )

        invalidIds.forEach { invalidId ->
            var clockCalls = 0
            val useCase = DuplicateProjectUseCase(
                idGenerator = ProjectDuplicationIdGenerator { invalidId },
                clock = EpochMillisClock {
                    clockCalls += 1
                    500L
                },
            )

            assertThrows(IllegalArgumentException::class.java) {
                useCase(
                    original,
                    DuplicateProjectCommand(SOURCE_PROJECT_ID, "Valid Copy"),
                )
            }
            assertEquals(0, clockCalls)
            assertEquals(snapshot, original)
            assertSame(snapshot.projects, original.projects)
        }
    }

    @Test
    fun `rejects a generated root collision without changing the input catalog`() {
        val source = richProject()
        val original = ProjectCatalog(
            selectedProjectId = source.id,
            projects = listOf(source),
        )
        val snapshot = original.copy()
        var clockCalls = 0
        val useCase = DuplicateProjectUseCase(
            idGenerator = ProjectDuplicationIdGenerator { source.id },
            clock = EpochMillisClock {
                clockCalls += 1
                500L
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            useCase(
                original,
                DuplicateProjectCommand(source.id, "Colliding Copy"),
            )
        }

        assertEquals(0, clockCalls)
        assertEquals(snapshot, original)
        assertSame(snapshot.projects, original.projects)
        assertSame(source.networks, original.projects.single().networks)
    }

    @Test
    fun `rejects a generated root collision with an archived project before reading the clock`() {
        val source = richProject()
        val archived = richProject().copy(id = DUPLICATED_PROJECT_ID, name = "Archived Copy")
        val archivedRecord = ArchivedProject(
            project = archived,
            archivedAtEpochMillis = 100L,
            originalProjectIndex = 0,
        )
        val original = ProjectCatalog(
            selectedProjectId = source.id,
            projects = listOf(source),
            archivedProjects = listOf(archivedRecord),
        )
        var clockCalls = 0
        val useCase = DuplicateProjectUseCase(
            idGenerator = ProjectDuplicationIdGenerator { DUPLICATED_PROJECT_ID },
            clock = EpochMillisClock {
                clockCalls += 1
                500L
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            useCase(original, DuplicateProjectCommand(source.id, "Colliding Archive Copy"))
        }

        assertEquals(0, clockCalls)
        assertEquals(listOf(source), original.projects)
        assertEquals(listOf(archivedRecord), original.archivedProjects)
        assertSame(archived, original.archivedProjects.single().project)
    }

    @Test
    fun `clock failure leaves the source aggregate and catalog untouched`() {
        val source = richProject()
        val original = ProjectCatalog(projects = listOf(source))
        val snapshot = original.copy()
        val useCase = DuplicateProjectUseCase(
            idGenerator = ProjectDuplicationIdGenerator { DUPLICATED_PROJECT_ID },
            clock = EpochMillisClock { error("Clock unavailable") },
        )

        assertThrows(IllegalStateException::class.java) {
            useCase(
                original,
                DuplicateProjectCommand(source.id, "Clock Failure Copy"),
            )
        }

        assertEquals(snapshot, original)
        assertSame(snapshot.projects, original.projects)
        assertSame(source.networks, original.projects.single().networks)
        assertSame(source.sites, original.projects.single().sites)
        assertSame(source.studies, original.projects.single().studies)
        assertSame(source.receivers, original.projects.single().receivers)
    }

    @Test
    fun `schema 5 JSON round trip preserves the duplicated aggregate`() {
        val source = richProject()
        val result = useCase(DUPLICATED_PROJECT_ID, 8_000L)(
            ProjectCatalog(selectedProjectId = source.id, projects = listOf(source)),
            DuplicateProjectCommand(source.id, "Serialized Copy"),
        )
        val json = Json { encodeDefaults = true }

        val restored = json.decodeFromString<ProjectCatalog>(json.encodeToString(result.catalog))
        val restoredCopy = restored.projects.last()

        assertEquals(5, restored.schemaVersion)
        assertEquals(result.catalog, restored)
        assertEquals(DUPLICATED_PROJECT_ID, restored.selectedProjectId)
        assertEquals(source.networks, restoredCopy.networks)
        assertEquals(source.sites, restoredCopy.sites)
        assertEquals(source.studies, restoredCopy.studies)
        assertEquals(source.receivers, restoredCopy.receivers)
    }

    @Test
    fun `AppUseCases exposes the injected duplication collaborators`() {
        val source = simpleProject()
        val repository = object : ProjectRepository {
            override suspend fun loadCatalog(): ProjectCatalog = error("Not used")

            override suspend fun updateCatalog(
                transform: (ProjectCatalog) -> ProjectCatalog,
            ): ProjectCatalog = error("Not used")
        }
        val useCases = AppUseCases.create(
            repository = repository,
            projectDuplicationIdGenerator = ProjectDuplicationIdGenerator {
                DUPLICATED_PROJECT_ID
            },
            clock = EpochMillisClock { 12_345L },
        )

        val result = useCases.duplicateProject(
            ProjectCatalog(projects = listOf(source)),
            DuplicateProjectCommand(source.id, "Wired Copy"),
        )

        assertEquals(DUPLICATED_PROJECT_ID, result.duplicatedProject.id)
        assertEquals(12_345L, result.duplicatedProject.createdAtEpochMillis)
        assertEquals(12_345L, result.duplicatedProject.updatedAtEpochMillis)
    }

    private fun useCase(
        projectId: String,
        nowEpochMillis: Long,
    ) = DuplicateProjectUseCase(
        idGenerator = ProjectDuplicationIdGenerator { projectId },
        clock = EpochMillisClock { nowEpochMillis },
    )

    private fun richProject(): PlannerProject {
        val network = RfNetwork(
            id = "network-source",
            name = "FWA Network",
            system = RadioSystem.FWA,
            downlinkFrequencyMHz = 3_550.125,
            bandwidthMHz = 80.25,
        )
        val sector = Sector(
            id = "sector-source",
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
            id = "site-source",
            name = "Hilltop TX",
            location = GeoPoint(-23.459_101_234, -46.755_509_876),
            groundElevationM = 1_134.75,
            notes = "Synthetic transmitter",
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = "receiver-source",
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
            id = "study-source",
            name = "Synthetic Link Budget",
            type = StudyType.POINT_TO_POINT,
            status = StudyStatus.READY,
            updatedAtEpochMillis = 777L,
        )
        return PlannerProject(
            id = SOURCE_PROJECT_ID,
            name = "Source RF Project",
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

    private fun simpleProject(
        id: String = SOURCE_PROJECT_ID,
        name: String = "Source Project",
    ) = PlannerProject(
        id = id,
        name = name,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private companion object {
        const val SOURCE_PROJECT_ID = "project-source"
        const val DUPLICATED_PROJECT_ID = "project-duplicate"
    }
}
