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
    fun `new catalogs use schema 5`() {
        assertEquals(5, PROJECT_CATALOG_SCHEMA_VERSION)
        assertEquals(5, ProjectCatalog().schemaVersion)
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
    fun `archived aggregate and lifecycle metadata survive schema 4 round trip`() {
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
    fun `schema 4 carrier records survive project JSON round trip with their references`() {
        val antennaArtifact = artifactReference(
            id = "artifact-antenna",
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "antenna-pattern.json",
            digestCharacter = 'a',
        )
        val gisArtifact = artifactReference(
            id = "artifact-gis",
            role = ProjectArtifactRole.GIS_LAYER,
            fileName = "terrain-layer.tif",
            digestCharacter = 'b',
        )
        val coverageArtifact = artifactReference(
            id = "artifact-coverage",
            role = ProjectArtifactRole.COVERAGE_RESULT,
            fileName = "coverage-result.bin",
            digestCharacter = 'c',
        )
        val regulatoryArtifact = artifactReference(
            id = "artifact-regulatory",
            role = ProjectArtifactRole.REGULATORY_RESULT,
            fileName = "regulatory-result.json",
            digestCharacter = 'd',
        )
        val pattern = AntennaPatternRecord(
            id = "pattern-main",
            name = "Main Horizontal Pattern",
            nominalFrequencyHz = 758_000_000.0,
            peakGainDbi = 17.5,
            sourceFormat = "ATX JSON",
            sourceSha256 = antennaArtifact.sha256,
            dataArtifactId = antennaArtifact.id,
        )
        val gisLayer = GisLayerRecord(
            id = "gis-terrain",
            name = "Synthetic Terrain",
            geometryType = GisGeometryType.RASTER,
            coordinateReferenceSystem = "EPSG:31983",
            featureCount = 1L,
            dataArtifactId = gisArtifact.id,
            sourceSha256 = gisArtifact.sha256,
        )
        val scenario = StudyScenarioRecord(
            id = "scenario-baseline",
            name = "Baseline Coverage",
            modelId = "free-space-reference",
            modelEdition = "2026-test",
            settingsJson = "{\"resolutionM\":30}",
        )
        val coverage = CoverageSnapshotRecord(
            id = "coverage-baseline",
            name = "Baseline RSRP",
            scenarioId = scenario.id,
            metricId = "rsrp",
            unit = "dBm",
            noDataMeaning = "No evaluated sample is available.",
            dataArtifactId = coverageArtifact.id,
            createdAtEpochMillis = 40L,
        )
        val regulatory = RegulatoryStudyRecord(
            id = "regulatory-screening",
            name = "Synthetic Screening",
            serviceId = "land-mobile",
            status = RegulatoryRecordStatus.INCONCLUSIVE,
            rulesetId = "synthetic-rules-v1",
            dataArtifactId = regulatoryArtifact.id,
            updatedAtEpochMillis = 50L,
        )
        val network = testNetwork()
        val receiver = testReceiver(network.id).copy(
            equipmentModel = "Synthetic CPE",
            networkProfiles = listOf(
                ReceiverNetworkProfile(
                    networkId = network.id,
                    antennaGainDbi = 17.0,
                    systemLossDb = 1.25,
                    sensitivityDbm = -98.5,
                ),
            ),
        )
        val site = RadioSite(
            id = "site-schema-4",
            name = "Schema 4 Site",
            location = GeoPoint(-23.55, -46.63),
            towerHeightM = 42.0,
            sectors = listOf(
                Sector(
                    id = "sector-schema-4",
                    name = "Schema 4 Sector",
                    azimuthDegrees = 90.0,
                    antennaHeightM = 35.0,
                    transmitPowerDbm = 43.0,
                    antennaGainDbi = 17.5,
                    feederLossDb = 1.5,
                    frequencyMHz = 758.0,
                    networkId = network.id,
                    transmitAntennaPatternId = pattern.id,
                    receiveAntennaPatternId = pattern.id,
                    equipmentModel = "Synthetic Radio",
                ),
            ),
        )
        val provenance = ImportProvenance(
            sourceFormat = "Synthetic ATX fixture",
            sourceSha256 = "e".repeat(64),
            sourceVersion = "4-test",
            importedAtEpochMillis = 25L,
            warnings = listOf("Synthetic warning retained for audit."),
            losses = listOf("Synthetic unsupported field retained for audit."),
        )
        val project = PlannerProject(
            id = "project-schema-4-records",
            name = "Schema 4 Records",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 50L,
            networks = listOf(network),
            sites = listOf(site),
            receivers = listOf(receiver),
            antennaPatterns = listOf(pattern),
            gisLayers = listOf(gisLayer),
            studyScenarios = listOf(scenario),
            activeStudyScenarioId = scenario.id,
            coverageSnapshots = listOf(coverage),
            regulatoryStudies = listOf(regulatory),
            artifacts = listOf(
                antennaArtifact,
                gisArtifact,
                coverageArtifact,
                regulatoryArtifact,
            ),
            importProvenance = provenance,
        )
        val json = Json { encodeDefaults = true }

        val restored = json.decodeFromString<PlannerProject>(json.encodeToString(project))

        assertEquals(project, restored)
        assertEquals(pattern.id, restored.sites.single().sectors.single().transmitAntennaPatternId)
        assertEquals(scenario.id, restored.activeStudyScenarioId)
        assertEquals(coverageArtifact.id, restored.coverageSnapshots.single().dataArtifactId)
        assertEquals(regulatoryArtifact.id, restored.regulatoryStudies.single().dataArtifactId)
        assertEquals(provenance, restored.importProvenance)
        assertEquals(receiver.networkProfiles, restored.receivers.single().networkProfiles)
    }

    @Test
    fun `schema 4 carrier records reject malformed bounded metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            artifactReference(digestCharacter = 'A')
        }
        assertThrows(IllegalArgumentException::class.java) {
            AntennaPatternRecord(
                id = "pattern-invalid",
                name = "Invalid Pattern",
                sourceSha256 = "not-a-sha-256-digest",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GisLayerRecord(
                id = "gis-invalid",
                name = "Invalid GIS Layer",
                geometryType = GisGeometryType.UNKNOWN,
                featureCount = -1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyScenarioRecord(
                id = "scenario-invalid",
                name = "Invalid Scenario",
                modelId = "",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageSnapshotRecord(
                id = "coverage-invalid",
                name = "Invalid Coverage",
                metricId = "rsrp",
                unit = "dBm",
                noDataMeaning = "",
                createdAtEpochMillis = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegulatoryStudyRecord(
                id = "regulatory-invalid",
                name = "Invalid Regulatory Record",
                serviceId = "land-mobile",
                rulesetId = "synthetic-rules-v1",
                updatedAtEpochMillis = -1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportProvenance(
                sourceFormat = "Synthetic fixture",
                sourceSha256 = "invalid",
                importedAtEpochMillis = 1L,
            )
        }
    }

    @Test
    fun `schema 4 project rejects missing antenna scenario and artifact references`() {
        val network = testNetwork()
        val sectorWithMissingPattern = Sector(
            id = "sector-missing-pattern",
            name = "Missing Pattern Sector",
            azimuthDegrees = 0.0,
            antennaHeightM = 30.0,
            transmitPowerDbm = 43.0,
            antennaGainDbi = 10.0,
            feederLossDb = 1.0,
            frequencyMHz = 758.0,
            networkId = network.id,
            transmitAntennaPatternId = "pattern-missing",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "project-missing-pattern",
                name = "Missing Pattern",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                networks = listOf(network),
                sites = listOf(
                    RadioSite(
                        id = "site-missing-pattern",
                        name = "Missing Pattern Site",
                        location = GeoPoint(0.0, 0.0),
                        sectors = listOf(sectorWithMissingPattern),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "project-missing-scenario",
                name = "Missing Scenario",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                coverageSnapshots = listOf(
                    CoverageSnapshotRecord(
                        id = "coverage-missing-scenario",
                        name = "Missing Scenario Coverage",
                        scenarioId = "scenario-missing",
                        metricId = "signal-level",
                        unit = "dBm",
                        noDataMeaning = "No evaluated sample is available.",
                        createdAtEpochMillis = 1L,
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "project-missing-artifact",
                name = "Missing Artifact",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                antennaPatterns = listOf(
                    AntennaPatternRecord(
                        id = "pattern-missing-artifact",
                        name = "Missing Artifact Pattern",
                        dataArtifactId = "artifact-missing",
                    ),
                ),
            )
        }
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
        assertThrows(IllegalArgumentException::class.java) {
            PlannerProject(
                id = "orphaned-receiver-profile",
                name = "Orphaned Receiver Profile",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                networks = listOf(network),
                receivers = listOf(
                    receiver.copy(
                        networkProfiles = listOf(
                            ReceiverNetworkProfile(networkId = "missing-profile-network"),
                        ),
                    ),
                ),
            )
        }
    }

    private fun artifactReference(
        id: String = "artifact-test",
        role: ProjectArtifactRole = ProjectArtifactRole.OTHER,
        fileName: String = "artifact.bin",
        digestCharacter: Char = 'f',
    ) = ProjectArtifactReference(
        id = id,
        role = role,
        fileName = fileName,
        mediaType = "application/octet-stream",
        sha256 = digestCharacter.toString().repeat(64),
        byteCount = 128L,
        createdAtEpochMillis = 5L,
    )

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
