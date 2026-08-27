package com.gecesars.atxplan.domain.study

import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.ReceiverNetworkProfile
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.rf.RfCalculator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ProjectLinkStudyTest {
    @Test
    fun `mean Earth inverse matches desktop one kilometer and cardinal vectors`() {
        val origin = coordinate(0.0, 0.0)

        val eastKilometer = MeanEarthGeodesy.inverse(origin, coordinate(0.0, 0.008_993_2))
        val northDegree = MeanEarthGeodesy.inverse(origin, coordinate(1.0, 0.0))
        val eastDegree = MeanEarthGeodesy.inverse(origin, coordinate(0.0, 1.0))

        assertEquals(999.999_593_403, eastKilometer.horizontalDistanceM, 0.001)
        assertEquals(90.0, eastKilometer.initialBearingDegrees, 1e-12)
        assertEquals(111_195.080_233_522, northDegree.horizontalDistanceM, 0.001)
        assertEquals(0.0, northDegree.initialBearingDegrees, 1e-12)
        assertEquals(111_195.080_233_522, eastDegree.horizontalDistanceM, 0.001)
        assertEquals(90.0, eastDegree.initialBearingDegrees, 1e-12)
    }

    @Test
    fun `mean Earth inverse follows the short antimeridian path`() {
        val result = MeanEarthGeodesy.inverse(
            coordinate(0.0, 179.0),
            coordinate(0.0, -179.0),
        )

        assertEquals(222_390.160_467_060, result.horizontalDistanceM, 0.001)
        assertEquals(90.0, result.initialBearingDegrees, 1e-12)
    }

    @Test
    fun `mean Earth inverse rejects undefined coincident and antipodal bearings`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeanEarthGeodesy.inverse(coordinate(10.0, 20.0), coordinate(10.0, 20.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeanEarthGeodesy.inverse(coordinate(0.0, 0.0), coordinate(0.0, 180.0))
        }
    }

    @Test
    fun `project engine snapshots effective receiver profile and exposes every scalar term`() {
        val fixture = fixture(
            receiverHeightM = 40.0,
            receiverProfiles = listOf(
                ReceiverNetworkProfile(
                    networkId = NETWORK_ID,
                    antennaGainDbi = 7.5,
                    systemLossDb = 1.25,
                    sensitivityDbm = -101.5,
                ),
            ),
        )

        val study = ProjectLinkStudyEngine.calculate(
            id = "link-study-1",
            name = "East Test Link",
            createdAtEpochMillis = 1_000L,
            projectId = "project-test",
            projectName = "Test Project",
            network = fixture.network,
            site = fixture.site,
            sector = fixture.sector,
            receiver = fixture.receiver,
        )

        assertEquals(
            hypot(study.geometry.horizontalDistanceM, 10.0),
            study.geometry.inclinedDistanceM,
            1e-9,
        )
        assertEquals(90.0, study.geometry.initialBearingDegrees, 1e-12)
        assertEquals(45.0, study.geometry.relativeAzimuthDegrees, 1e-12)
        assertTrue(study.input.receiverCompatibilityProfilePresent)
        assertTrue(study.input.receiverCompatibilityOverridesApplied)
        assertEquals(7.5, study.input.linkBudget.receiveAntennaGainDbi, 0.0)
        assertEquals(1.25, study.input.linkBudget.receiveLossDb, 0.0)
        assertEquals(-101.5, study.input.linkBudget.receiverSensitivityDbm, 0.0)
        assertEquals(10.0, study.input.linkBudget.bandwidthMHz, 0.0)
        assertEquals(
            study.result.eirpDbm - study.result.freeSpacePathLossDb + 7.5 - 1.25,
            study.result.receivedPowerDbm,
            1e-9,
        )
        assertEquals(LinkStudyTerrainState.NO_DATA, study.terrainState)
        assertEquals(125.0, study.input.transmitter.storedSiteGroundElevationM ?: 0.0, 0.0)
        assertTrue(study.warnings.any { it.contains("Terrain profile is NoData") })
        assertTrue(study.warnings.any { it.contains("Nominal isotropic-referenced") })
    }

    @Test
    fun `compatibility-only receiver profile is distinguished from applied overrides`() {
        val fixture = fixture(
            receiverNetworkId = "network-other",
            receiverProfiles = listOf(ReceiverNetworkProfile(networkId = NETWORK_ID)),
        )

        val study = ProjectLinkStudyEngine.calculate(
            id = "link-study-compatibility-only",
            name = "Compatibility Only Link",
            createdAtEpochMillis = 1_500L,
            projectId = "project-test",
            projectName = "Test Project",
            network = fixture.network,
            site = fixture.site,
            sector = fixture.sector,
            receiver = fixture.receiver,
        )

        assertTrue(study.input.receiverCompatibilityProfilePresent)
        assertTrue(!study.input.receiverCompatibilityOverridesApplied)
        assertEquals(2.0, study.input.linkBudget.receiveAntennaGainDbi, 0.0)
        assertTrue(study.warnings.any { it.contains("without receive-chain overrides") })
    }

    @Test
    fun `project study fingerprint and complete result survive strict JSON round trip`() {
        val fixture = fixture()
        val study = ProjectLinkStudyEngine.calculate(
            id = "link-study-round-trip",
            name = "Round Trip Link",
            createdAtEpochMillis = 2_000L,
            projectId = "project-test",
            projectName = "Test Project",
            network = fixture.network,
            site = fixture.site,
            sector = fixture.sector,
            receiver = fixture.receiver,
        )
        val json = Json { encodeDefaults = true }

        val restored = json.decodeFromString<ProjectLinkStudyRecord>(json.encodeToString(study))

        assertEquals(study, restored)
        assertEquals(
            ProjectLinkStudyFingerprint.calculate(restored.input, restored.geometry),
            restored.inputFingerprintSha256,
        )
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(
                input = restored.input.copy(
                    linkBudget = restored.input.linkBudget.copy(transmitPowerDbm = 99.0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(
                result = restored.result.copy(receivedPowerDbm = restored.result.receivedPowerDbm + 1.0),
            )
        }
        val withinDeclaredTolerance = restored.copy(
            result = restored.result.copy(
                receivedPowerDbm = restored.result.receivedPowerDbm + 5e-10,
            ),
        )
        assertEquals(
            restored.result.receivedPowerDbm + 5e-10,
            withinDeclaredTolerance.result.receivedPowerDbm,
            0.0,
        )
        val alteredGeometry = restored.geometry.copy(
            relativeAzimuthDegrees = (restored.geometry.relativeAzimuthDegrees + 1.0) % 360.0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(
                geometry = alteredGeometry,
                inputFingerprintSha256 = ProjectLinkStudyFingerprint.calculate(
                    restored.input,
                    alteredGeometry,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(warnings = restored.warnings.dropLast(1))
        }
        val extraLossInput = restored.input.copy(
            linkBudget = restored.input.linkBudget.copy(additionalPathLossDb = 1.0),
        )
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(
                input = extraLossInput,
                inputFingerprintSha256 = ProjectLinkStudyFingerprint.calculate(
                    extraLossInput,
                    restored.geometry,
                ),
                result = RfCalculator.linkBudget(extraLossInput.linkBudget),
            )
        }
        val mismatchedBandwidthInput = restored.input.copy(
            linkBudget = restored.input.linkBudget.copy(bandwidthMHz = 20.0),
        )
        assertThrows(IllegalArgumentException::class.java) {
            restored.copy(
                input = mismatchedBandwidthInput,
                inputFingerprintSha256 = ProjectLinkStudyFingerprint.calculate(
                    mismatchedBandwidthInput,
                    restored.geometry,
                ),
                result = RfCalculator.linkBudget(mismatchedBandwidthInput.linkBudget),
            )
        }
    }

    @Test
    fun `project engine rejects a receiver outside the selected network`() {
        val fixture = fixture(receiverNetworkId = "network-other")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ProjectLinkStudyEngine.calculate(
                id = "link-study-invalid",
                name = "Invalid Network Link",
                createdAtEpochMillis = 3_000L,
                projectId = "project-test",
                projectName = "Test Project",
                network = fixture.network,
                site = fixture.site,
                sector = fixture.sector,
                receiver = fixture.receiver,
            )
        }

        assertTrue(error.message.orEmpty().contains("not compatible"))
    }

    private fun fixture(
        receiverHeightM: Double = 30.0,
        receiverNetworkId: String = NETWORK_ID,
        receiverProfiles: List<ReceiverNetworkProfile> = emptyList(),
    ): Fixture {
        val network = RfNetwork(
            id = NETWORK_ID,
            name = "Test Network",
            system = RadioSystem.GENERIC,
            downlinkFrequencyMHz = 900.0,
            bandwidthMHz = 10.0,
        )
        val sector = Sector(
            id = "sector-east",
            name = "East Sector",
            azimuthDegrees = 45.0,
            antennaHeightM = 30.0,
            transmitPowerDbm = 43.0,
            antennaGainDbi = 15.0,
            feederLossDb = 2.0,
            frequencyMHz = 900.0,
            networkId = network.id,
        )
        val site = RadioSite(
            id = "site-origin",
            name = "Origin Site",
            location = GeoPoint(0.0, 0.0),
            groundElevationM = 125.0,
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = "receiver-east",
            name = "East Receiver",
            networkId = receiverNetworkId,
            location = GeoCoordinate(
                LatitudeDegrees(0.0),
                LongitudeDegrees(0.008_993_2),
            ),
            antennaHeightM = HeightM(receiverHeightM),
            antennaGainDbi = GainDbi(2.0),
            systemLossDb = LossDb(1.0),
            sensitivityDbm = PowerDbm(-95.0),
            noiseFigureDb = LossDb(6.0),
            azimuthDegrees = AzimuthDegrees(270.0),
            networkProfiles = receiverProfiles,
        )
        return Fixture(network, site, sector, receiver)
    }

    private fun coordinate(latitude: Double, longitude: Double) =
        LinkStudyCoordinate(latitude, longitude)

    private data class Fixture(
        val network: RfNetwork,
        val site: RadioSite,
        val sector: Sector,
        val receiver: Receiver,
    )

    private companion object {
        const val NETWORK_ID = "network-test"
    }
}
