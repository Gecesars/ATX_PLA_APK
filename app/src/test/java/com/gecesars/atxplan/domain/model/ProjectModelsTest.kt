package com.gecesars.atxplan.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun `new catalogs use schema 3`() {
        assertEquals(3, PROJECT_CATALOG_SCHEMA_VERSION)
        assertEquals(3, ProjectCatalog().schemaVersion)
        assertTrue(ProjectCatalog().archivedProjects.isEmpty())
    }

    @Test
    fun `project factory trims user data and creates stable timestamps`() {
        val project = ProjectFactory.create(
            name = "  Mountain Link  ",
            customer = "  Carrier A  ",
            nowEpochMillis = 1234L,
        )

        assertEquals("Mountain Link", project.name)
        assertEquals("Carrier A", project.customer)
        assertEquals(1234L, project.createdAtEpochMillis)
        assertEquals(1234L, project.updatedAtEpochMillis)
        assertFalse(project.isDemonstration)
    }

    @Test
    fun `project catalog falls back to first project when selection is stale`() {
        val project = ProjectFactory.create("Project A", "", nowEpochMillis = 1L)
        val catalog = ProjectCatalog(
            selectedProjectId = "missing",
            projects = listOf(project),
        )

        assertEquals(project.id, catalog.selectedProject?.id)
    }

    @Test
    fun `archived projects are excluded from active selection`() {
        val archived = ProjectFactory.create("Archived Project", "", nowEpochMillis = 1L)
        val active = ProjectFactory.create("Active Project", "", nowEpochMillis = 2L)
        val catalog = ProjectCatalog(
            selectedProjectId = archived.id,
            projects = listOf(active),
            archivedProjects = listOf(
                ArchivedProject(
                    project = archived,
                    archivedAtEpochMillis = 3L,
                    originalProjectIndex = 0,
                ),
            ),
        )

        assertSame(active, catalog.selectedProject)
    }

    @Test
    fun `demonstration project survives serialization round trip`() {
        val json = Json { encodeDefaults = true }
        val catalog = ProjectCatalog(
            selectedProjectId = "project-demo-sao-paulo",
            projects = listOf(ProjectFactory.demonstration(nowEpochMillis = 42L)),
        )

        val restored = json.decodeFromString<ProjectCatalog>(json.encodeToString(catalog))

        assertEquals(catalog, restored)
        assertEquals(3, restored.selectedProject?.sites?.size)
        assertTrue(restored.selectedProject?.isDemonstration == true)
    }

    @Test
    fun `archived aggregate and lifecycle metadata survive schema 3 round trip`() {
        val json = Json { encodeDefaults = true }
        val project = ProjectFactory.demonstration(nowEpochMillis = 42L)
        val archived = ArchivedProject(
            project = project,
            archivedAtEpochMillis = 84L,
            originalProjectIndex = 7,
        )
        val catalog = ProjectCatalog(archivedProjects = listOf(archived))

        val restored = json.decodeFromString<ProjectCatalog>(json.encodeToString(catalog))

        assertEquals(catalog, restored)
        assertEquals(project.networks, restored.archivedProjects.single().project.networks)
        assertEquals(project.sites, restored.archivedProjects.single().project.sites)
        assertEquals(84L, restored.archivedProjects.single().archivedAtEpochMillis)
        assertEquals(7, restored.archivedProjects.single().originalProjectIndex)
    }

    @Test
    fun `invalid coordinates and duplicate project ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0) }

        val project = ProjectFactory.create("Valid Project", "", nowEpochMillis = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCatalog(projects = listOf(project, project))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCatalog(
                projects = listOf(project),
                archivedProjects = listOf(
                    ArchivedProject(project, archivedAtEpochMillis = 2L, originalProjectIndex = 0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCatalog(
                archivedProjects = listOf(
                    ArchivedProject(project, archivedAtEpochMillis = 2L, originalProjectIndex = 0),
                    ArchivedProject(project, archivedAtEpochMillis = 3L, originalProjectIndex = 1),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedProject(project, archivedAtEpochMillis = 2L, originalProjectIndex = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedProject(project, archivedAtEpochMillis = -1L, originalProjectIndex = 0)
        }
    }

    @Test
    fun `engineering value objects enforce canonical boundaries`() {
        assertEquals(-90.0, LatitudeDegrees(-90.0).value, 0.0)
        assertEquals(90.0, LatitudeDegrees(90.0).value, 0.0)
        assertEquals(-180.0, LongitudeDegrees(-180.0).value, 0.0)
        assertEquals(359.999, AzimuthDegrees(359.999).value, 0.0)
        assertEquals(-90.0, TiltDegrees(-90.0).value, 0.0)
        assertEquals(90.0, TiltDegrees(90.0).value, 0.0)
        assertEquals(0.0, DistanceKm(0.0).value, 0.0)
        assertEquals(0.0, HeightM(0.0).value, 0.0)
        assertEquals(0.0, LossDb(0.0).value, 0.0)
        assertEquals(-300.0, PowerDbm(-300.0).value, 0.0)
        assertEquals(-12.0, GainDbi(-12.0).value, 0.0)
        assertEquals(0.001, FrequencyMHz(0.001).value, 0.0)
        assertEquals(0.000_001, BandwidthMHz(0.000_001).value, 0.0)

        assertThrows(IllegalArgumentException::class.java) { LatitudeDegrees(90.000_001) }
        assertThrows(IllegalArgumentException::class.java) { LongitudeDegrees(180.0) }
        assertThrows(IllegalArgumentException::class.java) { FrequencyMHz(0.0) }
        assertThrows(IllegalArgumentException::class.java) { BandwidthMHz(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PowerDbm(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { GainDbi(Double.NEGATIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { LossDb(-0.001) }
        assertThrows(IllegalArgumentException::class.java) { DistanceKm(-0.001) }
        assertThrows(IllegalArgumentException::class.java) { HeightM(-0.001) }
        assertThrows(IllegalArgumentException::class.java) { AzimuthDegrees(360.0) }
        assertThrows(IllegalArgumentException::class.java) { TiltDegrees(90.000_001) }
    }

    @Test
    fun `unit value objects retain primitive JSON representation`() {
        val json = Json

        assertEquals("99.5", json.encodeToString(FrequencyMHz(99.5)))
        assertEquals("0.2", json.encodeToString(BandwidthMHz(0.2)))
        assertEquals("-23.55052", json.encodeToString(LatitudeDegrees(-23.55052)))
        assertEquals("-46.63331", json.encodeToString(LongitudeDegrees(-46.63331)))
        assertEquals("43.0", json.encodeToString(PowerDbm(43.0)))
        assertEquals("18.0", json.encodeToString(GainDbi(18.0)))
        assertEquals("1.5", json.encodeToString(LossDb(1.5)))
        assertEquals("10.0", json.encodeToString(DistanceKm(10.0)))
        assertEquals("12.5", json.encodeToString(HeightM(12.5)))
        assertEquals("315.0", json.encodeToString(AzimuthDegrees(315.0)))
        assertEquals("-2.0", json.encodeToString(TiltDegrees(-2.0)))
        assertEquals(FrequencyMHz(99.5), json.decodeFromString<FrequencyMHz>("99.5"))
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString<LatitudeDegrees>("91.0")
        }
    }

    @Test
    fun `catalog JSON without receivers remains backward compatible`() {
        val legacyCatalog = """
            {
              "schemaVersion": 1,
              "selectedProjectId": "legacy-project",
              "projects": [
                {
                  "id": "legacy-project",
                  "name": "Legacy Project",
                  "createdAtEpochMillis": 10,
                  "updatedAtEpochMillis": 20
                }
              ]
            }
        """.trimIndent()

        val restored = Json.decodeFromString<ProjectCatalog>(legacyCatalog)

        assertTrue(restored.selectedProject?.receivers?.isEmpty() == true)
    }

    @Test
    fun `receiver survives project round trip with numeric units and references`() {
        val network = testNetwork()
        val receiver = testReceiver(networkId = network.id)
        val project = PlannerProject(
            id = "project-rf",
            name = "RF Project",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            networks = listOf(network),
            receivers = listOf(receiver),
        )
        val json = Json { encodeDefaults = true }

        val encoded = json.encodeToString(project)
        val restored = json.decodeFromString<PlannerProject>(encoded)
        val receiverObject = json.parseToJsonElement(encoded)
            .jsonObject.getValue("receivers")
            .jsonArray.single()
            .jsonObject

        assertEquals(project, restored)
        assertEquals(12.5, receiverObject.getValue("antennaHeightM").jsonPrimitive.double, 0.0)
        assertEquals(-96.0, receiverObject.getValue("sensitivityDbm").jsonPrimitive.double, 0.0)
        assertEquals(
            -23.55052,
            receiverObject.getValue("location").jsonObject
                .getValue("latitude").jsonPrimitive.double,
            0.0,
        )
    }

    @Test
    fun `project rejects duplicate receivers and orphaned network references`() {
        val network = testNetwork()
        val receiver = testReceiver(networkId = network.id)

        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "duplicate-receivers",
                name = "Duplicate Receivers",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                networks = listOf(network),
                receivers = listOf(receiver, receiver),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "orphaned-receiver",
                name = "Orphaned Receiver",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                networks = listOf(network),
                receivers = listOf(testReceiver(networkId = "missing-network")),
            )
        }
    }

    private fun testNetwork() = RfNetwork(
        id = "network-fwa",
        name = "FWA Network",
        system = RadioSystem.FWA,
        downlinkFrequencyMHz = 3_550.0,
        bandwidthMHz = 100.0,
    )

    private fun testReceiver(networkId: String) = Receiver(
        id = "receiver-001",
        name = "Warehouse CPE",
        networkId = networkId,
        location = GeoCoordinate(
            latitude = LatitudeDegrees(-23.55052),
            longitude = LongitudeDegrees(-46.63331),
        ),
        antennaHeightM = HeightM(12.5),
        antennaGainDbi = GainDbi(18.0),
        systemLossDb = LossDb(1.5),
        sensitivityDbm = PowerDbm(-96.0),
        noiseFigureDb = LossDb(6.0),
        azimuthDegrees = AzimuthDegrees(315.0),
        electricalTiltDegrees = TiltDegrees(-2.0),
        notes = "Synthetic test endpoint",
    )
}
