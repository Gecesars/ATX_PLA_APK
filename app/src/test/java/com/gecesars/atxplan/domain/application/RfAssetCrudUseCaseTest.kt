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
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.ReceiverNetworkProfile
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.TiltDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RfAssetCrudUseCaseTest {
    @Test
    fun `network create update and delete form independent durable transitions`() {
        val original = catalog()
        val useCase = useCase(now = 200L)

        val created = useCase(
            original,
            RfAssetMutationCommand.CreateNetwork(
                projectId = PROJECT_ID,
                input = networkInput("  Operations Network  "),
                requestId = "create-network",
            ),
        )
        val network = created.project!!.networks.single()

        assertEquals(RfAssetMutationStatus.CREATED, created.receipt.status)
        assertEquals(RfAssetKind.NETWORK, created.receipt.kind)
        assertEquals("network-generated", network.id)
        assertEquals("Operations Network", network.name)
        assertTrue(original.projects.single().networks.isEmpty())

        val updated = useCase(
            created.catalog,
            RfAssetMutationCommand.UpdateNetwork(
                projectId = PROJECT_ID,
                expected = network,
                input = networkInput("Updated Network", active = false),
                requestId = "update-network",
            ),
        )
        val updatedNetwork = updated.project!!.networks.single()

        assertEquals(RfAssetMutationStatus.UPDATED, updated.receipt.status)
        assertEquals(network.id, updatedNetwork.id)
        assertEquals("Updated Network", updatedNetwork.name)
        assertEquals(false, updatedNetwork.active)

        val deleted = useCase(
            updated.catalog,
            RfAssetMutationCommand.DeleteNetwork(
                projectId = PROJECT_ID,
                expected = updatedNetwork,
                requestId = "delete-network",
            ),
        )

        assertEquals(RfAssetMutationStatus.DELETED, deleted.receipt.status)
        assertTrue(deleted.project!!.networks.isEmpty())
    }

    @Test
    fun `site create update and delete preserve unrelated project data`() {
        val network = network()
        val original = catalog(project(networks = listOf(network)))
        val useCase = useCase(now = 210L)

        val created = useCase(
            original,
            RfAssetMutationCommand.CreateSite(
                projectId = PROJECT_ID,
                input = siteInput("  Ridge Site  "),
                requestId = "create-site",
            ),
        )
        val site = created.project!!.sites.single()
        assertEquals("site-generated", site.id)
        assertEquals("Ridge Site", site.name)

        val updated = useCase(
            created.catalog,
            RfAssetMutationCommand.UpdateSite(
                projectId = PROJECT_ID,
                expected = site,
                input = siteInput("Ridge Site North", towerHeightM = 48.5),
                requestId = "update-site",
            ),
        )
        val updatedSite = updated.project!!.sites.single()
        assertEquals(RfAssetMutationStatus.UPDATED, updated.receipt.status)
        assertEquals(48.5, updatedSite.towerHeightM!!, 0.0)
        assertEquals(listOf(network), updated.project!!.networks)

        val deleted = useCase(
            updated.catalog,
            RfAssetMutationCommand.DeleteSite(
                projectId = PROJECT_ID,
                expected = updatedSite,
                deleteContainedSectors = false,
                requestId = "delete-site",
            ),
        )
        assertEquals(RfAssetMutationStatus.DELETED, deleted.receipt.status)
        assertTrue(deleted.project!!.sites.isEmpty())
        assertEquals(listOf(network), deleted.project!!.networks)
    }

    @Test
    fun `map move changes only site coordinates and rejects stale snapshots`() {
        val sector = sector()
        val importedSite = site(sectors = listOf(sector)).copy(
            name = "  Imported Site Name  ",
            notes = "  Preserve imported spacing exactly.  ",
        )
        val original = catalog(project(networks = listOf(network()), sites = listOf(importedSite)))
        var clockCalls = 0
        val useCase = RfAssetCrudUseCase(
            idGenerator = RfEntityIdGenerator { "unused-id" },
            clock = EpochMillisClock {
                clockCalls += 1
                215L
            },
        )
        val destination = GeoPoint(latitude = -23.56141, longitude = -46.65588)

        val moved = useCase(
            original,
            RfAssetMutationCommand.MoveSite(
                projectId = PROJECT_ID,
                expected = importedSite,
                location = destination,
                requestId = "move-site",
            ),
        )

        assertEquals(RfAssetMutationStatus.UPDATED, moved.receipt.status)
        assertEquals(RfAssetKind.SITE, moved.receipt.kind)
        assertEquals(SITE_ID, moved.receipt.entityId)
        assertEquals(importedSite.copy(location = destination), moved.project!!.sites.single())
        assertEquals(215L, moved.project!!.updatedAtEpochMillis)
        assertEquals(importedSite, original.projects.single().sites.single())
        assertEquals(1, clockCalls)

        val retriedAfterCommittedMove = useCase(
            moved.catalog,
            RfAssetMutationCommand.MoveSite(
                projectId = PROJECT_ID,
                expected = importedSite,
                location = destination,
                requestId = "retry-committed-map-move",
            ),
        )
        assertSame(moved.catalog, retriedAfterCommittedMove.catalog)
        assertEquals(RfAssetMutationStatus.UNCHANGED, retriedAfterCommittedMove.receipt.status)
        assertEquals(1, clockCalls)

        val stale = useCase(
            moved.catalog,
            RfAssetMutationCommand.MoveSite(
                projectId = PROJECT_ID,
                expected = importedSite,
                location = GeoPoint(latitude = -23.57, longitude = -46.66),
                requestId = "stale-map-move",
            ),
        )
        assertSame(moved.catalog, stale.catalog)
        assertEquals(RfAssetMutationStatus.STALE, stale.receipt.status)
        assertEquals(1, clockCalls)

        val unchanged = useCase(
            moved.catalog,
            RfAssetMutationCommand.MoveSite(
                projectId = PROJECT_ID,
                expected = moved.project!!.sites.single(),
                location = destination,
                requestId = "unchanged-map-move",
            ),
        )
        assertSame(moved.catalog, unchanged.catalog)
        assertEquals(RfAssetMutationStatus.UNCHANGED, unchanged.receipt.status)
        assertEquals(1, clockCalls)

        val missing = useCase(
            moved.catalog,
            RfAssetMutationCommand.MoveSite(
                projectId = PROJECT_ID,
                expected = importedSite.copy(id = "site-missing"),
                location = destination,
                requestId = "missing-map-move",
            ),
        )
        assertSame(moved.catalog, missing.catalog)
        assertEquals(RfAssetMutationStatus.NOT_FOUND, missing.receipt.status)
        assertEquals("site-missing", missing.receipt.entityId)
        assertEquals(1, clockCalls)
    }

    @Test
    fun `sector create update and delete stay scoped to the selected site`() {
        val firstSite = site(id = "site-first")
        val secondSite = site(id = "site-second")
        val original = catalog(
            project(
                networks = listOf(network()),
                sites = listOf(firstSite, secondSite),
            ),
        )
        val useCase = useCase(now = 220L)

        val created = useCase(
            original,
            RfAssetMutationCommand.CreateSector(
                projectId = PROJECT_ID,
                siteId = firstSite.id,
                input = sectorInput("  Sector Alpha  "),
                requestId = "create-sector",
            ),
        )
        val createdProject = created.project!!
        val sector = createdProject.sites.first().sectors.single()
        assertEquals("sector-generated", sector.id)
        assertEquals("Sector Alpha", sector.name)
        assertTrue(createdProject.sites[1].sectors.isEmpty())

        val updated = useCase(
            created.catalog,
            RfAssetMutationCommand.UpdateSector(
                projectId = PROJECT_ID,
                siteId = firstSite.id,
                expected = sector,
                input = sectorInput("Sector Beta", active = false),
                requestId = "update-sector",
            ),
        )
        val updatedSector = updated.project!!.sites.first().sectors.single()
        assertEquals(RfAssetMutationStatus.UPDATED, updated.receipt.status)
        assertEquals(sector.id, updatedSector.id)
        assertEquals(false, updatedSector.active)

        val deleted = useCase(
            updated.catalog,
            RfAssetMutationCommand.DeleteSector(
                projectId = PROJECT_ID,
                siteId = firstSite.id,
                expected = updatedSector,
                requestId = "delete-sector",
            ),
        )
        assertEquals(RfAssetMutationStatus.DELETED, deleted.receipt.status)
        assertTrue(deleted.project!!.sites.first().sectors.isEmpty())
        assertEquals(secondSite, deleted.project!!.sites[1])
    }

    @Test
    fun `receiver create update and delete retain typed RF values`() {
        val original = catalog(project(networks = listOf(network())))
        val useCase = useCase(now = 230L)

        val created = useCase(
            original,
            RfAssetMutationCommand.CreateReceiver(
                projectId = PROJECT_ID,
                input = receiverInput("  Warehouse Receiver  "),
                requestId = "create-receiver",
            ),
        )
        val receiver = created.project!!.receivers.single()
        assertEquals("receiver-generated", receiver.id)
        assertEquals(-23.55, receiver.location.latitude.value, 0.0)

        val updated = useCase(
            created.catalog,
            RfAssetMutationCommand.UpdateReceiver(
                projectId = PROJECT_ID,
                expected = receiver,
                input = receiverInput("Warehouse Receiver East", sensitivityDbm = -101.25),
                requestId = "update-receiver",
            ),
        )
        val updatedReceiver = updated.project!!.receivers.single()
        assertEquals(RfAssetMutationStatus.UPDATED, updated.receipt.status)
        assertEquals(receiver.id, updatedReceiver.id)
        assertEquals(-101.25, updatedReceiver.sensitivityDbm.value, 0.0)

        val deleted = useCase(
            updated.catalog,
            RfAssetMutationCommand.DeleteReceiver(
                projectId = PROJECT_ID,
                expected = updatedReceiver,
                requestId = "delete-receiver",
            ),
        )
        assertEquals(RfAssetMutationStatus.DELETED, deleted.receipt.status)
        assertTrue(deleted.project!!.receivers.isEmpty())
    }

    @Test
    fun `receiver update preserves equipment and per-network profiles outside the compact editor`() {
        val primaryNetwork = network()
        val profileNetwork = network(id = "network-profile", name = "Profile Network")
        val profile = ReceiverNetworkProfile(
            networkId = profileNetwork.id,
            antennaGainDbi = 14.5,
            systemLossDb = 1.75,
            sensitivityDbm = -102.0,
        )
        val existing = receiver().copy(
            equipmentModel = "Synthetic CPE 500",
            networkProfiles = listOf(profile),
        )
        val original = catalog(
            project(
                networks = listOf(primaryNetwork, profileNetwork),
                receivers = listOf(existing),
            ),
        )

        val result = useCase(now = 235L)(
            original,
            RfAssetMutationCommand.UpdateReceiver(
                projectId = PROJECT_ID,
                expected = existing,
                input = receiverInput(
                    name = "Updated Receiver",
                    sensitivityDbm = -101.5,
                ),
                requestId = "update-receiver-preserve-profiles",
            ),
        )

        val updated = result.project!!.receivers.single()
        assertEquals(RfAssetMutationStatus.UPDATED, result.receipt.status)
        assertEquals("Synthetic CPE 500", updated.equipmentModel)
        assertEquals(listOf(profile), updated.networkProfiles)
        assertEquals(listOf(existing), original.projects.single().receivers)
    }

    @Test
    fun `referenced network and populated site deletion report explicit impact`() {
        val sector = sector()
        val network = network()
        val backupNetwork = network(id = "network-backup", name = "Backup Network")
        val site = site(sectors = listOf(sector))
        val receiver = receiver().copy(
            networkId = backupNetwork.id,
            networkProfiles = listOf(ReceiverNetworkProfile(networkId = network.id)),
        )
        val original = catalog(
            project(
                networks = listOf(network, backupNetwork),
                sites = listOf(site),
                receivers = listOf(receiver),
            ),
        )
        val useCase = useCase(now = 240L)

        val blockedNetwork = useCase(
            original,
            RfAssetMutationCommand.DeleteNetwork(PROJECT_ID, network, "delete-network"),
        )
        assertSame(original, blockedNetwork.catalog)
        assertEquals(RfAssetMutationStatus.BLOCKED_REFERENCES, blockedNetwork.receipt.status)
        assertEquals(RfDeletionImpact(sectorCount = 1, receiverCount = 1), blockedNetwork.receipt.impact)

        val blockedSite = useCase(
            original,
            RfAssetMutationCommand.DeleteSite(
                projectId = PROJECT_ID,
                expected = site,
                deleteContainedSectors = false,
                requestId = "delete-site-without-confirmation",
            ),
        )
        assertSame(original, blockedSite.catalog)
        assertEquals(RfAssetMutationStatus.BLOCKED_REFERENCES, blockedSite.receipt.status)
        assertEquals(1, blockedSite.receipt.impact.sectorCount)

        val cascaded = useCase(
            original,
            RfAssetMutationCommand.DeleteSite(
                projectId = PROJECT_ID,
                expected = site,
                deleteContainedSectors = true,
                requestId = "delete-site-with-confirmation",
            ),
        )
        assertEquals(RfAssetMutationStatus.DELETED, cascaded.receipt.status)
        assertTrue(cascaded.project!!.sites.isEmpty())
        assertEquals(listOf(receiver), cascaded.project!!.receivers)
    }

    @Test
    fun `stale and unchanged commands never overwrite the latest entity`() {
        val current = network(name = "Current Network")
        val original = catalog(project(networks = listOf(current)))
        var clockCalls = 0
        val useCase = RfAssetCrudUseCase(
            idGenerator = RfEntityIdGenerator { "unused-id" },
            clock = EpochMillisClock {
                clockCalls += 1
                999L
            },
        )

        val stale = useCase(
            original,
            RfAssetMutationCommand.UpdateNetwork(
                projectId = PROJECT_ID,
                expected = current.copy(name = "Reviewed Snapshot"),
                input = networkInput("Attempted Overwrite"),
                requestId = "stale-update",
            ),
        )
        assertSame(original, stale.catalog)
        assertEquals(RfAssetMutationStatus.STALE, stale.receipt.status)

        val unchanged = useCase(
            original,
            RfAssetMutationCommand.UpdateNetwork(
                projectId = PROJECT_ID,
                expected = current,
                input = networkInput(current.name),
                requestId = "unchanged-update",
            ),
        )
        assertSame(original, unchanged.catalog)
        assertEquals(RfAssetMutationStatus.UNCHANGED, unchanged.receipt.status)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `missing projects and entities return typed no-op receipts`() {
        val original = catalog()
        val useCase = useCase(now = 250L)

        val missingProject = useCase(
            original,
            RfAssetMutationCommand.CreateNetwork(
                projectId = "project-missing",
                input = networkInput("Missing Project Network"),
                requestId = "missing-project",
            ),
        )
        assertSame(original, missingProject.catalog)
        assertEquals(RfAssetMutationStatus.NOT_FOUND, missingProject.receipt.status)
        assertEquals(null, missingProject.project)

        val missingEntity = useCase(
            original,
            RfAssetMutationCommand.DeleteNetwork(
                projectId = PROJECT_ID,
                expected = network(id = "network-missing"),
                requestId = "missing-network",
            ),
        )
        assertSame(original, missingEntity.catalog)
        assertEquals(RfAssetMutationStatus.NOT_FOUND, missingEntity.receipt.status)
    }

    @Test
    fun `unknown references and generated ID collisions fail closed`() {
        val original = catalog(project(networks = listOf(network())))
        val collisionUseCase = RfAssetCrudUseCase(
            idGenerator = RfEntityIdGenerator { NETWORK_ID },
            clock = EpochMillisClock { 300L },
        )

        assertThrows(IllegalArgumentException::class.java) {
            collisionUseCase(
                original,
                RfAssetMutationCommand.CreateSite(
                    projectId = PROJECT_ID,
                    input = siteInput("Collision Site"),
                    requestId = "collision",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            useCase(now = 300L)(
                original,
                RfAssetMutationCommand.CreateReceiver(
                    projectId = PROJECT_ID,
                    input = receiverInput("Orphan Receiver", networkId = "network-missing"),
                    requestId = "orphan-receiver",
                ),
            )
        }
        assertEquals(listOf(network()), original.projects.single().networks)
    }

    private fun useCase(now: Long) = RfAssetCrudUseCase(
        idGenerator = RfEntityIdGenerator { kind ->
            when (kind) {
                RfEntityKind.NETWORK -> "network-generated"
                RfEntityKind.SITE -> "site-generated"
                RfEntityKind.SECTOR -> "sector-generated"
                RfEntityKind.RECEIVER -> "receiver-generated"
            }
        },
        clock = EpochMillisClock { now },
    )

    private fun catalog(project: PlannerProject = project()) = ProjectCatalog(
        selectedProjectId = project.id,
        projects = listOf(project),
    )

    private fun project(
        networks: List<RfNetwork> = emptyList(),
        sites: List<RadioSite> = emptyList(),
        receivers: List<Receiver> = emptyList(),
    ) = PlannerProject(
        id = PROJECT_ID,
        name = "RF CRUD Project",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        networks = networks,
        sites = sites,
        receivers = receivers,
    )

    private fun network(
        id: String = NETWORK_ID,
        name: String = "Current Network",
    ) = RfNetwork(
        id = id,
        name = name,
        system = RadioSystem.LTE,
        downlinkFrequencyMHz = 758.0,
        bandwidthMHz = 10.0,
    )

    private fun site(
        id: String = SITE_ID,
        sectors: List<Sector> = emptyList(),
    ) = RadioSite(
        id = id,
        name = "Current Site",
        location = GeoPoint(-23.5, -46.6),
        groundElevationM = 780.0,
        towerHeightM = 35.0,
        sectors = sectors,
    )

    private fun sector() = Sector(
        id = SECTOR_ID,
        name = "Current Sector",
        active = true,
        azimuthDegrees = 90.0,
        electricalTiltDegrees = 2.0,
        antennaHeightM = 30.0,
        transmitPowerDbm = 43.0,
        antennaGainDbi = 17.0,
        feederLossDb = 1.5,
        frequencyMHz = 758.0,
        networkId = NETWORK_ID,
    )

    private fun receiver() = Receiver(
        id = RECEIVER_ID,
        name = "Current Receiver",
        networkId = NETWORK_ID,
        location = coordinate(-23.55, -46.63),
        antennaHeightM = HeightM(10.0),
        antennaGainDbi = GainDbi(12.0),
        systemLossDb = LossDb(1.0),
        sensitivityDbm = PowerDbm(-98.0),
        noiseFigureDb = LossDb(5.0),
    )

    private fun networkInput(
        name: String,
        active: Boolean = true,
    ) = RfNetworkInput(
        name = name,
        system = RadioSystem.LTE,
        downlinkFrequencyMHz = FrequencyMHz(758.0),
        bandwidthMHz = BandwidthMHz(10.0),
        active = active,
    )

    private fun siteInput(
        name: String,
        towerHeightM: Double? = 35.0,
    ) = RfSiteInput(
        name = name,
        location = GeoPoint(-23.5, -46.6),
        groundElevationM = 780.0,
        towerHeightM = towerHeightM,
        notes = "Reviewed site",
    )

    private fun sectorInput(
        name: String,
        active: Boolean = true,
    ) = RfSectorInput(
        name = name,
        active = active,
        networkId = NETWORK_ID,
        frequencyMHz = FrequencyMHz(758.0),
        azimuthDegrees = AzimuthDegrees(90.0),
        electricalTiltDegrees = TiltDegrees(2.0),
        antennaHeightM = HeightM(30.0),
        transmitPowerDbm = PowerDbm(43.0),
        antennaGainDbi = GainDbi(17.0),
        feederLossDb = LossDb(1.5),
    )

    private fun receiverInput(
        name: String,
        networkId: String = NETWORK_ID,
        sensitivityDbm: Double = -98.0,
    ) = RfReceiverInput(
        name = name,
        networkId = networkId,
        location = coordinate(-23.55, -46.63),
        antennaHeightM = HeightM(10.0),
        antennaGainDbi = GainDbi(12.0),
        systemLossDb = LossDb(1.0),
        sensitivityDbm = PowerDbm(sensitivityDbm),
        noiseFigureDb = LossDb(5.0),
        azimuthDegrees = AzimuthDegrees(180.0),
        electricalTiltDegrees = TiltDegrees(0.0),
        notes = "Reviewed receiver",
    )

    private fun coordinate(latitude: Double, longitude: Double) = GeoCoordinate(
        latitude = LatitudeDegrees(latitude),
        longitude = LongitudeDegrees(longitude),
    )

    private companion object {
        const val PROJECT_ID = "project-rf-crud"
        const val NETWORK_ID = "network-current"
        const val SITE_ID = "site-current"
        const val SECTOR_ID = "sector-current"
        const val RECEIVER_ID = "receiver-current"
    }
}
