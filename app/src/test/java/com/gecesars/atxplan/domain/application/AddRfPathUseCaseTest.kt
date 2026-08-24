package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.BandwidthMHz
import com.gecesars.atxplan.domain.model.FrequencyMHz
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
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.TiltDegrees
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AddRfPathUseCaseTest {
    @Test
    fun `adds one fully linked RF path as a single deterministic catalog update`() {
        val original = catalogWithEmptyTarget()
        val useCase = deterministicUseCase(nowEpochMillis = 9_876L)

        val result = useCase(original, validCommand())

        assertEquals(original.selectedProjectId, result.catalog.selectedProjectId)
        assertEquals(0, original.projects.single().networks.size)
        assertEquals(0, original.projects.single().sites.size)
        assertEquals(0, original.projects.single().receivers.size)
        assertEquals(9_876L, result.project.updatedAtEpochMillis)
        assertEquals("network-001", result.network.id)
        assertEquals("site-001", result.site.id)
        assertEquals("sector-001", result.sector.id)
        assertEquals("receiver-001", result.receiver.id)
        assertEquals("FWA 3.5 GHz", result.network.name)
        assertEquals("Hilltop TX", result.site.name)
        assertEquals("Sector Alpha", result.sector.name)
        assertEquals("Warehouse CPE", result.receiver.name)
        assertEquals(3_550.123_456, result.network.downlinkFrequencyMHz, 0.0)
        assertEquals(80.125, result.network.bandwidthMHz, 0.0)
        assertEquals(3_550.123_456, result.sector.frequencyMHz, 0.0)
        assertEquals(result.network.id, result.sector.networkId)
        assertEquals(result.network.id, result.receiver.networkId)
        assertEquals(listOf(result.sector), result.site.sectors)
        assertEquals(listOf(result.network), result.project.networks)
        assertEquals(listOf(result.site), result.project.sites)
        assertEquals(listOf(result.receiver), result.project.receivers)
    }

    @Test
    fun `JSON round trip preserves generated IDs precision units and references`() {
        val result = deterministicUseCase(nowEpochMillis = 9_876L)(
            catalogWithEmptyTarget(),
            validCommand(),
        )
        val json = Json { encodeDefaults = true }

        val restored = json.decodeFromString<ProjectCatalog>(
            json.encodeToString(result.catalog),
        )
        val project = restored.projects.single()
        val network = project.networks.single()
        val sector = project.sites.single().sectors.single()
        val receiver = project.receivers.single()

        assertEquals(result.catalog, restored)
        assertEquals(result.network.downlinkFrequencyMHz.toBits(), network.downlinkFrequencyMHz.toBits())
        assertEquals(result.network.bandwidthMHz.toBits(), network.bandwidthMHz.toBits())
        assertEquals(result.receiver.location.latitude, receiver.location.latitude)
        assertEquals(result.receiver.antennaHeightM, receiver.antennaHeightM)
        assertEquals(network.id, sector.networkId)
        assertEquals(network.id, receiver.networkId)
    }

    @Test
    fun `aggregate accepts legacy unlinked sectors and rejects unknown network references`() {
        val legacySector = validLegacySector(networkId = null)
        val legacyProject = baseProject().copy(
            sites = listOf(
                RadioSite(
                    id = "site-legacy",
                    name = "Legacy Site",
                    location = GeoPoint(1.0, 2.0),
                    sectors = listOf(legacySector),
                ),
            ),
        )

        assertEquals(null, legacyProject.sites.single().sectors.single().networkId)
        assertThrows(IllegalArgumentException::class.java) {
            baseProject().copy(
                sites = listOf(
                    RadioSite(
                        id = "site-orphaned",
                        name = "Orphaned Site",
                        location = GeoPoint(1.0, 2.0),
                        sectors = listOf(validLegacySector(networkId = "network-missing")),
                    ),
                ),
            )
        }
    }

    @Test
    fun `rejects duplicate generated IDs without changing the input catalog`() {
        val original = catalogWithEmptyTarget()
        val snapshot = original.copy()
        val useCase = AddRfPathUseCase(
            idGenerator = RfEntityIdGenerator { "duplicate-id" },
            clock = EpochMillisClock { 9_876L },
        )

        assertThrows(IllegalArgumentException::class.java) {
            useCase(original, validCommand())
        }
        assertEquals(snapshot, original)
    }

    @Test
    fun `rejects generated IDs that collide with existing RF entities`() {
        val existingNetwork = RfNetwork(
            id = "network-001",
            name = "Existing Network",
            system = RadioSystem.GENERIC,
            downlinkFrequencyMHz = 450.0,
            bandwidthMHz = 0.025,
        )
        val original = ProjectCatalog(
            selectedProjectId = TARGET_PROJECT_ID,
            projects = listOf(baseProject().copy(networks = listOf(existingNetwork))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            deterministicUseCase(nowEpochMillis = 9_876L)(original, validCommand())
        }
        assertEquals(listOf(existingNetwork), original.projects.single().networks)
    }

    @Test
    fun `rejects invalid command identity names project lookup and generated ID`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddRfPathCommand(
                projectId = " ",
                network = validCommand().network,
                site = validCommand().site,
                sector = validCommand().sector,
                receiver = validCommand().receiver,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewRfNetwork(
                name = " ",
                system = RadioSystem.FWA,
                downlinkFrequencyMHz = FrequencyMHz(3_550.0),
                bandwidthMHz = BandwidthMHz(80.0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            deterministicUseCase(nowEpochMillis = 9_876L)(
                catalogWithEmptyTarget(),
                validCommand().copy(projectId = "project-missing"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddRfPathUseCase(
                idGenerator = RfEntityIdGenerator { " " },
                clock = EpochMillisClock { 9_876L },
            )(catalogWithEmptyTarget(), validCommand())
        }
    }

    @Test
    fun `preserves a future imported project timestamp when the wall clock is behind`() {
        val result = deterministicUseCase(nowEpochMillis = 99L)(
            catalogWithEmptyTarget(),
            validCommand(),
        )

        assertEquals(100L, result.project.updatedAtEpochMillis)
        assertEquals(1, result.project.networks.size)
        assertEquals(1, result.project.sites.size)
        assertEquals(1, result.project.receivers.size)
    }

    @Test
    fun `legacy sector JSON without a network reference remains readable`() {
        val legacyProjectJson = """
            {
              "id": "project-legacy",
              "name": "Legacy Project",
              "createdAtEpochMillis": 1,
              "updatedAtEpochMillis": 1,
              "sites": [
                {
                  "id": "site-legacy",
                  "name": "Legacy Site",
                  "location": {"latitude": 1.0, "longitude": 2.0},
                  "sectors": [
                    {
                      "id": "sector-legacy",
                      "name": "Legacy Sector",
                      "azimuthDegrees": 90.0,
                      "antennaHeightM": 30.0,
                      "transmitPowerDbm": 43.0,
                      "antennaGainDbi": 15.0,
                      "feederLossDb": 2.0,
                      "frequencyMHz": 450.0
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val restored = Json.decodeFromString<PlannerProject>(legacyProjectJson)

        assertTrue(restored.sites.single().sectors.single().networkId == null)
    }

    private fun validCommand() = AddRfPathCommand(
        projectId = TARGET_PROJECT_ID,
        network = NewRfNetwork(
            name = "  FWA 3.5 GHz  ",
            system = RadioSystem.FWA,
            downlinkFrequencyMHz = FrequencyMHz(3_550.123_456),
            bandwidthMHz = BandwidthMHz(80.125),
        ),
        site = NewTransmitterSite(
            name = "  Hilltop TX  ",
            location = coordinate(-23.459_101_234, -46.755_509_876),
            notes = "  Synthetic transmitter  ",
        ),
        sector = NewTransmitterSector(
            name = "  Sector Alpha  ",
            azimuthDegrees = AzimuthDegrees(123.456_789),
            electricalTiltDegrees = TiltDegrees(2.25),
            antennaHeightM = HeightM(42.75),
            transmitPowerDbm = PowerDbm(43.125),
            antennaGainDbi = GainDbi(17.875),
            feederLossDb = LossDb(1.625),
        ),
        receiver = NewReceiver(
            name = "  Warehouse CPE  ",
            location = coordinate(-23.550_521_234, -46.633_319_876),
            antennaHeightM = HeightM(12.375),
            antennaGainDbi = GainDbi(18.25),
            systemLossDb = LossDb(1.125),
            sensitivityDbm = PowerDbm(-96.875),
            noiseFigureDb = LossDb(5.625),
            azimuthDegrees = AzimuthDegrees(315.125),
            electricalTiltDegrees = TiltDegrees(-1.75),
            notes = "  Synthetic endpoint  ",
        ),
    )

    private fun deterministicUseCase(nowEpochMillis: Long) = AddRfPathUseCase(
        idGenerator = RfEntityIdGenerator { kind ->
            when (kind) {
                RfEntityKind.NETWORK -> "network-001"
                RfEntityKind.SITE -> "site-001"
                RfEntityKind.SECTOR -> "sector-001"
                RfEntityKind.RECEIVER -> "receiver-001"
            }
        },
        clock = EpochMillisClock { nowEpochMillis },
    )

    private fun catalogWithEmptyTarget() = ProjectCatalog(
        selectedProjectId = TARGET_PROJECT_ID,
        projects = listOf(baseProject()),
    )

    private fun baseProject() = PlannerProject(
        id = TARGET_PROJECT_ID,
        name = "Target Project",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private fun coordinate(latitude: Double, longitude: Double) = GeoCoordinate(
        latitude = LatitudeDegrees(latitude),
        longitude = LongitudeDegrees(longitude),
    )

    private fun validLegacySector(networkId: String?) = Sector(
        id = "sector-legacy",
        name = "Legacy Sector",
        azimuthDegrees = 90.0,
        antennaHeightM = 30.0,
        transmitPowerDbm = 43.0,
        antennaGainDbi = 15.0,
        feederLossDb = 2.0,
        frequencyMHz = 450.0,
        networkId = networkId,
    )

    private companion object {
        const val TARGET_PROJECT_ID = "project-target"
    }
}
