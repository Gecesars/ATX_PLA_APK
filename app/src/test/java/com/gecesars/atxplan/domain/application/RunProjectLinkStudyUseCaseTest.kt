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
import com.gecesars.atxplan.domain.model.StudyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RunProjectLinkStudyUseCaseTest {
    @Test
    fun `use case appends one immutable record and matching summary`() {
        val project = project()
        val catalog = ProjectCatalog(selectedProjectId = project.id, projects = listOf(project))

        val result = useCase(id = "link-study-created", now = 2_000L)(catalog, command(project))

        assertEquals(RunProjectLinkStudyStatus.CREATED, result.status)
        assertEquals(1, result.catalog.projects.single().linkStudies.size)
        assertEquals("link-study-created", result.record?.id)
        assertEquals(result.record, result.catalog.projects.single().linkStudies.single())
        val summary = result.catalog.projects.single().studies.single()
        assertEquals(result.record?.id, summary.id)
        assertEquals(StudyType.POINT_TO_POINT, summary.type)
        assertEquals(StudyStatus.COMPLETED, summary.status)
        assertEquals(2_000L, result.catalog.projects.single().updatedAtEpochMillis)
        assertTrue(project.linkStudies.isEmpty())
        assertTrue(project.studies.isEmpty())
    }

    @Test
    fun `stale project snapshot is rejected without a partial record`() {
        val reviewed = project()
        val latest = reviewed.copy(notes = "Changed elsewhere", updatedAtEpochMillis = 1_500L)
        val catalog = ProjectCatalog(selectedProjectId = latest.id, projects = listOf(latest))

        val result = useCase()(catalog, command(reviewed))

        assertEquals(RunProjectLinkStudyStatus.STALE, result.status)
        assertSame(catalog, result.catalog)
        assertNull(result.record)
        assertTrue(result.catalog.projects.single().linkStudies.isEmpty())
    }

    @Test
    fun `missing and incompatible endpoint references fail closed`() {
        val project = project()
        val catalog = ProjectCatalog(projects = listOf(project))

        val missing = useCase()(catalog, command(project).copy(receiverId = "missing"))
        val otherNetwork = project.networks.single().copy(
            id = "network-other",
            name = "Other Network",
        )
        val incompatibleProject = project.copy(
            networks = project.networks + otherNetwork,
            receivers = listOf(project.receivers.single().copy(networkId = "network-other")),
        )
        val incompatibleCatalog = ProjectCatalog(projects = listOf(incompatibleProject))
        val incompatible = useCase()(
            incompatibleCatalog,
            command(incompatibleProject),
        )

        assertEquals(RunProjectLinkStudyStatus.ENDPOINT_NOT_FOUND, missing.status)
        assertSame(catalog, missing.catalog)
        assertEquals(RunProjectLinkStudyStatus.INCOMPATIBLE_NETWORK, incompatible.status)
        assertSame(incompatibleCatalog, incompatible.catalog)
        assertTrue(incompatible.catalog.projects.single().linkStudies.isEmpty())
    }

    @Test
    fun `record ID collision does not alter the durable aggregate`() {
        val project = project()
        val first = useCase(id = "link-study-fixed")(ProjectCatalog(projects = listOf(project)), command(project))
        val latest = first.catalog.projects.single()

        val collision = useCase(id = "link-study-fixed")(
            first.catalog,
            command(latest).copy(requestId = "request-2", name = "Second Link Study"),
        )

        assertEquals(RunProjectLinkStudyStatus.ID_COLLISION, collision.status)
        assertSame(first.catalog, collision.catalog)
        assertEquals(1, collision.catalog.projects.single().linkStudies.size)
    }

    @Test
    fun `persisted link study requires exactly one matching timestamped summary`() {
        val project = project()
        val created = useCase(id = "link-study-summary")(
            ProjectCatalog(projects = listOf(project)),
            command(project),
        ).catalog.projects.single()
        val summary = created.studies.single()

        assertThrows(IllegalArgumentException::class.java) {
            created.copy(studies = created.studies + summary)
        }
        assertThrows(IllegalArgumentException::class.java) {
            created.copy(
                studies = listOf(summary.copy(updatedAtEpochMillis = summary.updatedAtEpochMillis + 1L)),
            )
        }
    }

    private fun useCase(
        id: String = "link-study-1",
        now: Long = 2_000L,
    ) = RunProjectLinkStudyUseCase(
        idGenerator = LinkStudyRecordIdGenerator { id },
        clock = EpochMillisClock { now },
    )

    private fun command(project: PlannerProject) = RunProjectLinkStudyCommand(
        requestId = "request-1",
        expectedProject = project,
        name = "Stored East Link",
        siteId = SITE_ID,
        sectorId = SECTOR_ID,
        receiverId = RECEIVER_ID,
    )

    private fun project(): PlannerProject {
        val network = RfNetwork(
            id = NETWORK_ID,
            name = "Study Network",
            system = RadioSystem.GENERIC,
            downlinkFrequencyMHz = 900.0,
            bandwidthMHz = 10.0,
        )
        val sector = Sector(
            id = SECTOR_ID,
            name = "East Sector",
            azimuthDegrees = 90.0,
            antennaHeightM = 30.0,
            transmitPowerDbm = 43.0,
            antennaGainDbi = 15.0,
            feederLossDb = 2.0,
            frequencyMHz = 900.0,
            networkId = network.id,
        )
        val site = RadioSite(
            id = SITE_ID,
            name = "Origin Site",
            location = GeoPoint(0.0, 0.0),
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = RECEIVER_ID,
            name = "East Receiver",
            networkId = network.id,
            location = GeoCoordinate(LatitudeDegrees(0.0), LongitudeDegrees(0.008_993_2)),
            antennaHeightM = HeightM(30.0),
            antennaGainDbi = GainDbi(2.0),
            systemLossDb = LossDb(1.0),
            sensitivityDbm = PowerDbm(-95.0),
            noiseFigureDb = LossDb(6.0),
            azimuthDegrees = AzimuthDegrees(270.0),
        )
        return PlannerProject(
            id = PROJECT_ID,
            name = "Study Project",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
            networks = listOf(network),
            sites = listOf(site),
            receivers = listOf(receiver),
        )
    }

    private companion object {
        const val PROJECT_ID = "project-study"
        const val NETWORK_ID = "network-study"
        const val SITE_ID = "site-study"
        const val SECTOR_ID = "sector-study"
        const val RECEIVER_ID = "receiver-study"
    }
}
