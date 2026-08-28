package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.AntennaPatternCoordinateConvention
import com.gecesars.atxplan.domain.model.AntennaPatternCutAvailability
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.ChannelPlanPoint
import com.gecesars.atxplan.domain.model.CoverageSnapshotRecord
import com.gecesars.atxplan.domain.model.DuplexMode
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.GisGeometryType
import com.gecesars.atxplan.domain.model.GisLayerRecord
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.ImportProvenance
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.RadioTechnologyProfile
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.ReceiverNetworkProfile
import com.gecesars.atxplan.domain.model.RegulatoryRecordStatus
import com.gecesars.atxplan.domain.model.RegulatoryStudyRecord
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.StudyScenarioRecord
import com.gecesars.atxplan.domain.model.TiltDegrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProjectCatalogPersistenceTest {
    private val codec = ProjectCatalogCodec()

    @Test
    fun `codec round trip preserves the complete catalog`() {
        val archivedProject = ProjectFactory.demonstration(nowEpochMillis = 40L).copy(
            id = "project-archived",
            name = "Archived Planning",
        )
        val catalog = catalog(name = "Sao Paulo Planning", timestamp = 42L).copy(
            archivedProjects = listOf(
                ArchivedProject(
                    project = archivedProject,
                    archivedAtEpochMillis = 41L,
                    originalProjectIndex = 3,
                ),
            ),
        )

        val restored = codec.decode(codec.encode(catalog))

        assertEquals(catalog, restored)
        assertEquals(3, restored.selectedProject?.sites?.size)
        assertEquals(archivedProject, restored.archivedProjects.single().project)
        assertEquals(3, restored.archivedProjects.single().originalProjectIndex)
    }

    @Test
    fun `schema six record without explicit cut availability stays readable and fails closed`() {
        val base = catalog(name = "Legacy Availability", timestamp = 42L)
        val artifact = ProjectArtifactReference(
            id = "artifact-availability",
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "availability.atx-antenna.json",
            mediaType = "application/vnd.atx-plan.antenna+json;version=1",
            sha256 = "e".repeat(64),
            byteCount = 1L,
            createdAtEpochMillis = 42L,
        )
        val record = CanonicalAntennaPattern.isotropic(
            nominalFrequencyHz = 99_500_000.0,
        ).toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-availability",
                name = "Legacy Availability",
                peakGainDbi = 6.5,
                sourceFormat = "ATX test",
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = artifact.id,
                origin = AntennaPatternOrigin.SYNTHESIZED,
            ),
        )
        val selected = requireNotNull(base.selectedProject)
        val source = base.copy(
            projects = base.projects.map { project ->
                if (project.id == selected.id) {
                    project.copy(
                        antennaPatterns = listOf(record),
                        artifacts = project.artifacts + artifact,
                    )
                } else {
                    project
                }
            },
        )
        val legacyText = codec.encode(source).toString(Charsets.UTF_8).replace(
            Regex(",\\s*\"availability\"\\s*:\\s*\"AVAILABLE\""),
            "",
        )
        assertFalse(legacyText.contains("\"availability\""))

        val restored = codec.decode(legacyText.toByteArray())
        val restoredPattern = requireNotNull(restored.selectedProject).antennaPatterns.single()

        assertEquals(6, restored.schemaVersion)
        assertEquals(
            AntennaPatternCutAvailability.LEGACY_UNSPECIFIED,
            requireNotNull(restoredPattern.horizontalCut).availability,
        )
        assertEquals(
            AntennaPatternCutAvailability.LEGACY_UNSPECIFIED,
            requireNotNull(restoredPattern.verticalCut).availability,
        )
        assertFalse(restoredPattern.hasVerifiedNormalizedContentIdentity())
    }

    @Test
    fun `schema 1 fixture is migrated through schema 2 and atomically promoted to schema 6`() = runBlocking {
        val legacyPayload = fixture("project_catalog_v1.json")
        val expected = codec.decode(codec.parse(legacyPayload)).copy(
            schemaVersion = 6,
            archivedProjects = emptyList(),
        )
        val storage = InMemoryCatalogStorage(legacyPayload)
        val persistence = persistence(storage)

        val migrated = persistence.loadCatalog()

        assertEquals(expected, migrated)
        assertEquals(6, migrated.schemaVersion)
        assertEquals("legacy-project", migrated.selectedProjectId)
        assertEquals("Legacy Mountain Link", migrated.selectedProject?.name)
        assertEquals("Legacy Carrier", migrated.selectedProject?.customer)
        assertEquals(
            450.25,
            migrated.selectedProject?.networks?.single()?.downlinkFrequencyMHz ?: 0.0,
            0.0,
        )
        assertEquals(
            760.5,
            migrated.selectedProject?.sites?.single()?.groundElevationM ?: 0.0,
            0.0,
        )
        assertEquals(
            -2.0,
            migrated.selectedProject?.sites?.single()?.sectors?.single()?.electricalTiltDegrees
                ?: 0.0,
            0.0,
        )
        assertTrue(migrated.selectedProject?.receivers?.isEmpty() == true)
        assertNull(migrated.selectedProject?.sites?.single()?.sectors?.single()?.networkId)
        assertTrue(migrated.archivedProjects.isEmpty())
        assertEquals(1, storage.writeAttempts.get())
        assertEquals(6, codec.parse(storage.snapshot()).schemaVersion)
        assertEquals(migrated, codec.decode(storage.snapshot()))

        assertEquals(migrated, persistence.loadCatalog())
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `failed schema migration write preserves the complete schema 1 fixture`() {
        val legacyPayload = fixture("project_catalog_v1.json")
        val storage = InMemoryCatalogStorage(legacyPayload).apply {
            failNextWrite = true
        }
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("existing file was preserved"))
        assertArrayEquals(legacyPayload, storage.snapshot())
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `schema 2 fixture is migrated and atomically promoted to schema 6`() = runBlocking {
        val schema2Payload = fixture("project_catalog_v2.json")
        val storage = InMemoryCatalogStorage(schema2Payload)
        val persistence = persistence(storage)

        val migrated = persistence.loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertEquals("schema-2-project", migrated.selectedProjectId)
        assertEquals("Schema 2 Mountain Path", migrated.selectedProject?.name)
        assertEquals(
            3_550.125,
            migrated.selectedProject?.networks?.single()?.downlinkFrequencyMHz ?: 0.0,
            0.0,
        )
        assertEquals(
            "schema-2-network",
            migrated.selectedProject?.sites?.single()?.sectors?.single()?.networkId,
        )
        assertEquals(
            "schema-2-network",
            migrated.selectedProject?.receivers?.single()?.networkId,
        )
        assertTrue(migrated.archivedProjects.isEmpty())
        assertEquals(1, storage.writeAttempts.get())
        assertEquals(6, codec.parse(storage.snapshot()).schemaVersion)
        assertEquals(migrated, codec.decode(storage.snapshot()))

        assertEquals(migrated, persistence.loadCatalog())
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `schema 4 is promoted to schema 6 and cannot inject link study records`() = runBlocking {
        val schema4Catalog = catalog(
            name = "Schema 4 Transactional Project",
            timestamp = 45L,
        ).copy(schemaVersion = 4)
        val encoded = codec.encode(schema4Catalog).toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"linkStudies\": []"))
        val injected = encoded.replace(
            "\"linkStudies\": []",
            "\"linkStudies\": [{\"untrusted\": true}]",
        ).toByteArray(Charsets.UTF_8)
        val storage = InMemoryCatalogStorage(injected)

        val migrated = persistence(storage).loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertEquals("Schema 4 Transactional Project", migrated.selectedProject?.name)
        assertTrue(migrated.selectedProject?.linkStudies?.isEmpty() == true)
        assertEquals(1, storage.writeAttempts.get())
        assertEquals(migrated, codec.decode(storage.snapshot()))
    }

    @Test
    fun `schema 4 promotion preserves rich active and archived carrier records`() = runBlocking {
        val active = richSchema4Project("project-schema-4-rich", "Rich Active Project")
        val archived = richSchema4Project("project-schema-4-archived", "Rich Archived Project")
        val source = ProjectCatalog(
            schemaVersion = 4,
            selectedProjectId = active.id,
            projects = listOf(active),
            archivedProjects = listOf(
                ArchivedProject(
                    project = archived,
                    archivedAtEpochMillis = 90L,
                    originalProjectIndex = 2,
                ),
            ),
        )
        val storage = InMemoryCatalogStorage(codec.encode(source))

        val migrated = persistence(storage).loadCatalog()

        assertEquals(source.copy(schemaVersion = 6), migrated)
        assertEquals(6, migrated.schemaVersion)
        assertEquals(active, migrated.projects.single())
        assertEquals(archived, migrated.archivedProjects.single().project)
        assertEquals(2, migrated.archivedProjects.single().originalProjectIndex)
        val restoredNetwork = migrated.selectedProject!!.networks.single()
        val restoredSite = migrated.selectedProject!!.sites.single()
        val restoredSector = restoredSite.sectors.single()
        val restoredReceiver = migrated.selectedProject!!.receivers.single()
        assertTrue(!restoredNetwork.active)
        assertEquals(DuplexMode.FDD, restoredNetwork.duplexMode)
        assertEquals("channel-rich", restoredNetwork.channelPlan.single().id)
        assertEquals("NR test profile", restoredNetwork.technologyProfile?.variant)
        assertEquals(55.5, restoredSite.towerHeightM ?: 0.0, 0.0)
        assertEquals("pattern-rich", restoredSector.transmitAntennaPatternId)
        assertEquals("Synthetic feeder", restoredSector.cableType)
        assertEquals("Synthetic CPE", restoredReceiver.equipmentModel)
        assertEquals(17.25, restoredReceiver.networkProfiles.single().antennaGainDbi ?: 0.0, 0.0)
        assertEquals("scenario-rich", migrated.selectedProject!!.activeStudyScenarioId)
        assertEquals("Synthetic import", migrated.selectedProject!!.importProvenance?.sourceFormat)
        assertTrue(migrated.selectedProject!!.linkStudies.isEmpty())
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `schema 5 antenna calculation field injection is stripped before schema 6 migration`() =
        runBlocking {
            val pattern = AntennaPatternRecord(
                id = "legacy-pattern",
                name = "Legacy Pattern",
                sourceFormat = "PAT",
            )
            val project = ProjectFactory.create(
                name = "Schema 5 Pattern Project",
                customer = "",
                nowEpochMillis = 50L,
            ).copy(antennaPatterns = listOf(pattern))
            val source = ProjectCatalog(
                schemaVersion = 5,
                selectedProjectId = project.id,
                projects = listOf(project),
            )
            val encodedRoot = Json.parseToJsonElement(
                codec.encode(source).toString(Charsets.UTF_8),
            ).jsonObject
            val encodedProjects = encodedRoot.getValue("projects").jsonArray
            val encodedProject = encodedProjects.single().jsonObject
            val encodedPattern = encodedProject.getValue("antennaPatterns")
                .jsonArray
                .single()
                .jsonObject
            val injectedPattern = JsonObject(
                encodedPattern + mapOf(
                    "sourceArtifactId" to JsonPrimitive("injected-source-artifact"),
                    "canonicalDataVersion" to JsonPrimitive(1),
                    "origin" to JsonPrimitive("SYNTHESIZED"),
                    "coordinateConvention" to JsonPrimitive("INJECTED_CONVENTION"),
                    "horizontalCut" to JsonObject(mapOf("untrusted" to JsonPrimitive(true))),
                    "verticalCut" to JsonObject(mapOf("untrusted" to JsonPrimitive(true))),
                    "normalizedContentSha256" to JsonPrimitive("c".repeat(64)),
                    "warnings" to JsonArray(listOf(JsonPrimitive("Injected warning."))),
                ),
            )
            val injectedProject = JsonObject(
                encodedProject +
                    ("antennaPatterns" to JsonArray(listOf(injectedPattern))),
            )
            val injectedRoot = JsonObject(
                encodedRoot + ("projects" to JsonArray(listOf(injectedProject))),
            )
            val storage = InMemoryCatalogStorage(
                injectedRoot.toString().toByteArray(Charsets.UTF_8),
            )

            val migrated = persistence(storage).loadCatalog()
            val migratedPattern = migrated.selectedProject!!.antennaPatterns.single()

            assertEquals(6, migrated.schemaVersion)
            assertEquals("PAT", migratedPattern.sourceFormat)
            assertNull(migratedPattern.sourceArtifactId)
            assertNull(migratedPattern.canonicalDataVersion)
            assertEquals(AntennaPatternOrigin.UNKNOWN, migratedPattern.origin)
            assertEquals(
                AntennaPatternCoordinateConvention.RELATIVE_AZIMUTH_CLOCKWISE_ELEVATION_UP,
                migratedPattern.coordinateConvention,
            )
            assertNull(migratedPattern.horizontalCut)
            assertNull(migratedPattern.verticalCut)
            assertNull(migratedPattern.normalizedContentSha256)
            assertTrue(migratedPattern.warnings.isEmpty())
            assertEquals(1, storage.writeAttempts.get())
            assertEquals(6, codec.parse(storage.snapshot()).schemaVersion)
        }

    @Test
    fun `failed schema 2 promotion preserves the complete source fixture`() {
        val schema2Payload = fixture("project_catalog_v2.json")
        val storage = InMemoryCatalogStorage(schema2Payload).apply {
            failNextWrite = true
        }
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("existing file was preserved"))
        assertArrayEquals(schema2Payload, storage.snapshot())
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `schema 2 archive injection is discarded before decoding and promotion`() = runBlocking {
        val schema2Text = fixture("project_catalog_v2.json")
            .toString(Charsets.UTF_8)
            .trim()
        val injectedPayload = (
            schema2Text.dropLast(1) +
                ",\n  \"archivedProjects\": {\"untrusted\": \"not a schema 2 field\"}\n}"
            ).toByteArray(Charsets.UTF_8)
        val storage = InMemoryCatalogStorage(injectedPayload)
        val persistence = persistence(storage)

        val migrated = persistence.loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertTrue(migrated.archivedProjects.isEmpty())
        assertEquals("Schema 2 Mountain Path", migrated.selectedProject?.name)
        assertEquals(1, storage.writeAttempts.get())
        assertEquals(migrated, codec.decode(storage.snapshot()))
    }

    @Test
    fun `schema 1 archive injection is discarded before chained migration`() = runBlocking {
        val schema1Text = fixture("project_catalog_v1.json")
            .toString(Charsets.UTF_8)
            .trim()
        val injectedPayload = (
            schema1Text.dropLast(1) +
                ",\n  \"archivedProjects\": [{\"invalid\": true}]\n}"
            ).toByteArray(Charsets.UTF_8)
        val storage = InMemoryCatalogStorage(injectedPayload)

        val migrated = persistence(storage).loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertTrue(migrated.archivedProjects.isEmpty())
        assertEquals("Legacy Mountain Link", migrated.selectedProject?.name)
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `schema 1 schema 2 field injection is discarded before chained migration`() = runBlocking {
        val schema1Text = fixture("project_catalog_v1.json")
            .toString(Charsets.UTF_8)
            .replace(
                "\"frequencyMHz\": 450.25",
                "\"frequencyMHz\": 450.25,\n              \"networkId\": \"legacy-network\"",
            )
            .replace(
                "      \"studies\": [",
                """
                      "receivers": [
                        {
                          "id": "injected-receiver",
                          "name": "Injected Receiver",
                          "networkId": "legacy-network",
                          "location": {"latitude": -23.4, "longitude": -46.7},
                          "antennaHeightM": 10.0,
                          "antennaGainDbi": 0.0,
                          "systemLossDb": 0.0,
                          "sensitivityDbm": -100.0,
                          "noiseFigureDb": 0.0,
                          "azimuthDegrees": 0.0,
                          "electricalTiltDegrees": 0.0
                        }
                      ],
                      "studies": [
                """.trimIndent(),
            )
            .toByteArray(Charsets.UTF_8)
        val storage = InMemoryCatalogStorage(schema1Text)

        val migrated = persistence(storage).loadCatalog()

        assertTrue(migrated.selectedProject?.receivers?.isEmpty() == true)
        assertNull(migrated.selectedProject?.sites?.single()?.sectors?.single()?.networkId)
        assertEquals(1, storage.writeAttempts.get())
    }

    @Test
    fun `missing storage is seeded once and can be loaded again`() = runBlocking {
        val seed = catalog(name = "Seed Catalog", timestamp = 100L)
        val storage = InMemoryCatalogStorage()
        var seedCalls = 0
        val persistence = persistence(storage) {
            seedCalls += 1
            seed
        }

        assertEquals(seed, persistence.loadCatalog())
        assertEquals(seed, persistence.loadCatalog())
        assertEquals(1, seedCalls)
        assertEquals(1, storage.writeAttempts.get())
        assertEquals(seed, codec.decode(storage.snapshot()))
    }

    @Test
    fun `parse failure preserves the untrusted source bytes`() {
        val original = "{this is not valid JSON".toByteArray()
        val storage = InMemoryCatalogStorage(original)
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("preserved"))
        assertArrayEquals(original, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `negative archive timestamp is rejected without replacing the current payload`() {
        val archivedProject = ProjectFactory.create(
            name = "Archived Fixture",
            customer = "",
            nowEpochMillis = 1L,
        )
        val validPayload = codec.encode(
            ProjectCatalog(
                archivedProjects = listOf(
                    ArchivedProject(
                        project = archivedProject,
                        archivedAtEpochMillis = 10L,
                        originalProjectIndex = 0,
                    ),
                ),
            ),
        )
        val invalidPayload = validPayload
            .toString(Charsets.UTF_8)
            .replace("\"archivedAtEpochMillis\": 10", "\"archivedAtEpochMillis\": -1")
            .toByteArray(Charsets.UTF_8)
        val storage = InMemoryCatalogStorage(invalidPayload)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(storage).loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("invalid data"))
        assertArrayEquals(invalidPayload, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `future schema is rejected without replacing its payload`() {
        val futurePayload = """
            {
              "schemaVersion": 7,
              "selectedProjectId": null,
              "projects": []
            }
        """.trimIndent().toByteArray()
        val storage = InMemoryCatalogStorage(futurePayload)
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("newer version"))
        assertArrayEquals(futurePayload, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `malformed UTF-8 is rejected without normalizing or replacing bytes`() {
        val prefix = (
            "{\"schemaVersion\":2,\"selectedProjectId\":null,\"projects\":[]," +
                "\"ignored\":\""
            ).toByteArray(Charsets.UTF_8)
        val suffix = "\"}".toByteArray(Charsets.UTF_8)
        val malformedPayload = prefix + byteArrayOf(0xC3.toByte(), 0x28) + suffix
        val storage = InMemoryCatalogStorage(malformedPayload)
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("not valid UTF-8"))
        assertArrayEquals(malformedPayload, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `failed atomic write leaves the last valid catalog readable`() = runBlocking {
        val original = catalog(name = "Last Valid", timestamp = 10L)
        val replacement = catalog(name = "Replacement", timestamp = 20L)
        val originalPayload = codec.encode(original)
        val storage = InMemoryCatalogStorage(originalPayload).apply {
            failNextWrite = true
        }
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.saveCatalog(replacement) }
        }

        assertTrue(error.message.orEmpty().contains("existing file was preserved"))
        assertArrayEquals(originalPayload, storage.snapshot())
        assertEquals(original, persistence.loadCatalog())
    }

    @Test
    fun `oversized payload is rejected without attempting a repair write`() {
        val original = ByteArray(MAX_PROJECT_CATALOG_BYTES + 1) { 'x'.code.toByte() }
        val storage = InMemoryCatalogStorage(original)
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence.loadCatalog() }
        }

        assertTrue(error.message.orEmpty().contains("5 MB"))
        assertArrayEquals(original, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `overlapping saves enter atomic storage one at a time`() = runBlocking {
        val first = catalog(name = "First Save", timestamp = 1L)
        val second = catalog(name = "Second Save", timestamp = 2L)
        val storage = BlockingCatalogStorage(codec.encode(catalog("Initial", 0L)))
        val persistence = persistence(storage)

        val firstSave = async(Dispatchers.Default) { persistence.saveCatalog(first) }
        assertTrue(
            "The first save did not reach storage.",
            storage.firstWriteEntered.await(5, TimeUnit.SECONDS),
        )

        val secondStarted = CountDownLatch(1)
        val secondSave = async(Dispatchers.Default) {
            secondStarted.countDown()
            persistence.saveCatalog(second)
        }
        assertTrue("The second save did not start.", secondStarted.await(5, TimeUnit.SECONDS))

        try {
            assertFalse(
                "A second atomic write entered storage before the first completed.",
                storage.secondWriteEntered.await(250, TimeUnit.MILLISECONDS),
            )
        } finally {
            storage.releaseFirstWrite.countDown()
        }

        firstSave.await()
        secondSave.await()
        assertEquals(1, storage.maximumConcurrentWrites.get())
        assertEquals(second, codec.decode(storage.snapshot()))
    }

    @Test
    fun `transactions from repository peers rebase on the latest durable catalog`() = runBlocking {
        val sharedMutex = Mutex()
        val storage = InMemoryCatalogStorage(codec.encode(catalog("Initial", 0L)))
        val firstPersistence = persistence(storage, operationMutex = sharedMutex)
        val secondPersistence = persistence(storage, operationMutex = sharedMutex)
        val firstTransformEntered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondTransformEntered = CountDownLatch(1)
        val releaseFirstTransform = CountDownLatch(1)

        val firstUpdate = async(Dispatchers.Default) {
            firstPersistence.updateCatalog { latest ->
                firstTransformEntered.countDown()
                if (!releaseFirstTransform.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Timed out waiting to release the first transform.")
                }
                latest.withProject("project-first", "First Transaction", 1L)
            }
        }
        assertTrue(firstTransformEntered.await(5, TimeUnit.SECONDS))

        val secondUpdate = async(Dispatchers.Default) {
            secondStarted.countDown()
            secondPersistence.updateCatalog { latest ->
                secondTransformEntered.countDown()
                latest.withProject("project-second", "Second Transaction", 2L)
            }
        }
        assertTrue("The peer transaction did not start.", secondStarted.await(5, TimeUnit.SECONDS))
        try {
            assertFalse(
                "A peer transform observed storage while the first transaction held the lock.",
                secondTransformEntered.await(250, TimeUnit.MILLISECONDS),
            )
        } finally {
            releaseFirstTransform.countDown()
        }

        firstUpdate.await()
        val secondResult = secondUpdate.await()
        assertEquals(
            listOf("Initial", "First Transaction", "Second Transaction"),
            secondResult.projects.map { it.name },
        )
        assertEquals(secondResult, codec.decode(storage.snapshot()))
    }

    @Test
    fun `failed transaction write leaves latest durable catalog unchanged`() {
        val original = catalog("Durable Transaction", 6L)
        val originalPayload = codec.encode(original)
        val storage = InMemoryCatalogStorage(originalPayload).apply {
            failNextWrite = true
        }
        val persistence = persistence(storage)

        val error = assertThrows(ProjectStorageException::class.java) {
            runBlocking {
                persistence.updateCatalog { latest ->
                    latest.withProject("project-unsaved", "Unsaved Transaction", 7L)
                }
            }
        }

        assertTrue(error.message.orEmpty().contains("existing file was preserved"))
        assertArrayEquals(originalPayload, storage.snapshot())
        assertEquals(original, codec.decode(storage.snapshot()))
    }

    @Test
    fun `no-op transaction returns latest catalog without writing`() = runBlocking {
        val latest = catalog("Latest", 7L)
        val storage = InMemoryCatalogStorage(codec.encode(latest))
        val persistence = persistence(storage)

        val result = persistence.updateCatalog { it }

        assertEquals(latest, result)
        assertEquals(0, storage.writeAttempts.get())
    }

    @Test
    fun `transaction validation exception propagates without changing storage`() {
        val original = catalog("Durable", 8L)
        val originalPayload = codec.encode(original)
        val storage = InMemoryCatalogStorage(originalPayload)
        val persistence = persistence(storage)
        val rejection = IllegalArgumentException("Rejected by the caller.")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                persistence.updateCatalog { throw rejection }
            }
        }

        assertTrue(thrown === rejection)
        assertArrayEquals(originalPayload, storage.snapshot())
        assertEquals(0, storage.writeAttempts.get())
    }

    private fun richSchema4Project(id: String, name: String): PlannerProject {
        val artifact = ProjectArtifactReference(
            id = "artifact-rich",
            role = ProjectArtifactRole.OTHER,
            fileName = "rich-carrier.bin",
            mediaType = "application/octet-stream",
            sha256 = "a".repeat(64),
            byteCount = 123L,
            createdAtEpochMillis = 20L,
        )
        val pattern = AntennaPatternRecord(
            id = "pattern-rich",
            name = "Rich Pattern",
            nominalFrequencyHz = 3_550_000_000.0,
            peakGainDbi = 18.5,
            sourceFormat = "Synthetic JSON",
            sourceSha256 = artifact.sha256,
            dataArtifactId = artifact.id,
        )
        val network = RfNetwork(
            id = "network-rich",
            name = "Rich Network",
            system = RadioSystem.NR_5G,
            downlinkFrequencyMHz = 3_550.0,
            bandwidthMHz = 80.0,
            active = false,
            uplinkFrequencyMHz = 3_450.0,
            duplexMode = DuplexMode.FDD,
            downlinkThresholdDbm = -95.5,
            uplinkThresholdDbm = -97.25,
            channelPlan = listOf(
                ChannelPlanPoint(
                    id = "channel-rich",
                    label = "Rich Channel",
                    downlinkFrequencyMHz = 3_550.0,
                    uplinkFrequencyMHz = 3_450.0,
                ),
            ),
            technologyProfile = RadioTechnologyProfile(
                variant = "NR test profile",
                adaptiveModulation = true,
                mimoLayers = 4,
                downlinkLoadPercent = 72.5,
                uplinkLoadPercent = 33.25,
            ),
            legacyParametersJson = "{\"legacyNetwork\":true}",
        )
        val sector = Sector(
            id = "sector-rich",
            name = "Rich Sector",
            active = false,
            azimuthDegrees = 123.5,
            electricalTiltDegrees = -2.25,
            antennaHeightM = 42.75,
            transmitPowerDbm = 43.125,
            antennaGainDbi = 17.875,
            feederLossDb = 1.625,
            frequencyMHz = 3_550.0,
            networkId = network.id,
            transmitAntennaPatternId = pattern.id,
            receiveAntennaPatternId = pattern.id,
            receiveAntennaHeightM = 12.5,
            receiveAntennaGainDbi = 16.75,
            receiveSystemLossDb = 1.25,
            cableType = "Synthetic feeder",
            cableLengthM = 31.5,
            equipmentModel = "Synthetic radio",
            mimoIndex = 3,
            simulcastDelayMicros = 4.75,
            legacyParametersJson = "{\"legacySector\":true}",
        )
        val site = RadioSite(
            id = "site-rich",
            name = "Rich Site",
            location = GeoPoint(-23.55, -46.63),
            groundElevationM = 760.5,
            towerHeightM = 55.5,
            notes = "Rich schema-4 site",
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = "receiver-rich",
            name = "Rich Receiver",
            networkId = network.id,
            location = GeoCoordinate(
                latitude = LatitudeDegrees(-23.54),
                longitude = LongitudeDegrees(-46.62),
            ),
            antennaHeightM = HeightM(12.0),
            antennaGainDbi = GainDbi(16.0),
            systemLossDb = LossDb(1.0),
            sensitivityDbm = PowerDbm(-98.0),
            noiseFigureDb = LossDb(5.0),
            azimuthDegrees = AzimuthDegrees(300.0),
            electricalTiltDegrees = TiltDegrees(-1.0),
            notes = "Rich schema-4 receiver",
            equipmentModel = "Synthetic CPE",
            networkProfiles = listOf(
                ReceiverNetworkProfile(
                    networkId = network.id,
                    antennaGainDbi = 17.25,
                    systemLossDb = 1.125,
                    sensitivityDbm = -99.5,
                ),
            ),
        )
        val gisLayer = GisLayerRecord(
            id = "gis-rich",
            name = "Rich GIS Layer",
            geometryType = GisGeometryType.RASTER,
            coordinateReferenceSystem = "EPSG:31983",
            featureCount = 1L,
            dataArtifactId = artifact.id,
            sourceSha256 = artifact.sha256,
        )
        val scenario = StudyScenarioRecord(
            id = "scenario-rich",
            name = "Rich Scenario",
            modelId = "synthetic-model",
            modelEdition = "schema-4",
            settingsJson = "{\"resolutionM\":30}",
        )
        val coverage = CoverageSnapshotRecord(
            id = "coverage-rich",
            name = "Rich Coverage",
            scenarioId = scenario.id,
            metricId = "rsrp",
            unit = "dBm",
            noDataMeaning = "No evaluated sample is available.",
            dataArtifactId = artifact.id,
            createdAtEpochMillis = 60L,
        )
        val regulatory = RegulatoryStudyRecord(
            id = "regulatory-rich",
            name = "Rich Screening",
            serviceId = "land-mobile",
            status = RegulatoryRecordStatus.INCONCLUSIVE,
            rulesetId = "synthetic-rules-v1",
            dataArtifactId = artifact.id,
            updatedAtEpochMillis = 70L,
        )
        return PlannerProject(
            id = id,
            name = name,
            customer = "Synthetic Carrier",
            notes = "Rich schema-4 migration fixture",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 80L,
            networks = listOf(network),
            sites = listOf(site),
            receivers = listOf(receiver),
            antennaPatterns = listOf(pattern),
            gisLayers = listOf(gisLayer),
            studyScenarios = listOf(scenario),
            activeStudyScenarioId = scenario.id,
            coverageSnapshots = listOf(coverage),
            regulatoryStudies = listOf(regulatory),
            artifacts = listOf(artifact),
            importProvenance = ImportProvenance(
                sourceFormat = "Synthetic import",
                sourceSha256 = "b".repeat(64),
                sourceVersion = "schema-4",
                importedAtEpochMillis = 15L,
                warnings = listOf("Synthetic migration warning."),
                losses = listOf("Synthetic migration loss."),
            ),
        )
    }

    private fun persistence(
        storage: ProjectCatalogStorage,
        operationMutex: Mutex = Mutex(),
        seed: () -> ProjectCatalog = { catalog("Seed", 99L) },
    ) = ProjectCatalogPersistence(
        storage = storage,
        codec = codec,
        seedCatalog = seed,
        operationMutex = operationMutex,
    )

    private fun catalog(name: String, timestamp: Long): ProjectCatalog {
        val project = ProjectFactory.demonstration(nowEpochMillis = timestamp).copy(name = name)
        return ProjectCatalog(
            selectedProjectId = project.id,
            projects = listOf(project),
        )
    }

    private fun ProjectCatalog.withProject(
        id: String,
        name: String,
        timestamp: Long,
    ): ProjectCatalog = copy(
        projects = projects + ProjectFactory.create(name, "", nowEpochMillis = timestamp).copy(id = id),
    )

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing catalog fixture: $name"
        }.use { it.readBytes() }
}

private class InMemoryCatalogStorage(initialPayload: ByteArray? = null) : ProjectCatalogStorage {
    @Volatile
    private var payload: ByteArray? = initialPayload?.copyOf()

    val writeAttempts = AtomicInteger()

    @Volatile
    var failNextWrite: Boolean = false

    override fun exists(): Boolean = payload != null

    override fun read(maxBytes: Int): ByteArray {
        val current = payload ?: throw FileNotFoundException("No catalog payload.")
        if (current.size > maxBytes) throw ProjectCatalogSizeLimitException()
        return current.copyOf()
    }

    override fun writeAtomically(payload: ByteArray) {
        writeAttempts.incrementAndGet()
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Injected atomic write failure.")
        }
        this.payload = payload.copyOf()
    }

    fun snapshot(): ByteArray = payload?.copyOf()
        ?: throw FileNotFoundException("No catalog payload.")
}

private class BlockingCatalogStorage(initialPayload: ByteArray) : ProjectCatalogStorage {
    @Volatile
    private var payload: ByteArray = initialPayload.copyOf()

    private val writeSequence = AtomicInteger()
    private val activeWrites = AtomicInteger()
    val maximumConcurrentWrites = AtomicInteger()
    val firstWriteEntered = CountDownLatch(1)
    val secondWriteEntered = CountDownLatch(1)
    val releaseFirstWrite = CountDownLatch(1)

    override fun exists(): Boolean = true

    override fun read(maxBytes: Int): ByteArray = payload.copyOf()

    override fun writeAtomically(payload: ByteArray) {
        val writeNumber = writeSequence.incrementAndGet()
        val concurrentWrites = activeWrites.incrementAndGet()
        maximumConcurrentWrites.accumulateAndGet(concurrentWrites, ::maxOf)
        try {
            when (writeNumber) {
                1 -> {
                    firstWriteEntered.countDown()
                    if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                        throw IOException("Timed out waiting to release the first write.")
                    }
                }

                2 -> secondWriteEntered.countDown()
            }
            this.payload = payload.copyOf()
        } finally {
            activeWrites.decrementAndGet()
        }
    }

    fun snapshot(): ByteArray = payload.copyOf()
}
