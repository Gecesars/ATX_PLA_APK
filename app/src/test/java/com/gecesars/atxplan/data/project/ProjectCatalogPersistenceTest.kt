package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
    fun `schema 1 fixture is migrated through schema 2 and atomically promoted to schema 4`() = runBlocking {
        val legacyPayload = fixture("project_catalog_v1.json")
        val expected = codec.decode(codec.parse(legacyPayload)).copy(
            schemaVersion = 4,
            archivedProjects = emptyList(),
        )
        val storage = InMemoryCatalogStorage(legacyPayload)
        val persistence = persistence(storage)

        val migrated = persistence.loadCatalog()

        assertEquals(expected, migrated)
        assertEquals(4, migrated.schemaVersion)
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
        assertEquals(4, codec.parse(storage.snapshot()).schemaVersion)
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
    fun `schema 2 fixture is migrated and atomically promoted to schema 4`() = runBlocking {
        val schema2Payload = fixture("project_catalog_v2.json")
        val storage = InMemoryCatalogStorage(schema2Payload)
        val persistence = persistence(storage)

        val migrated = persistence.loadCatalog()

        assertEquals(4, migrated.schemaVersion)
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
        assertEquals(4, codec.parse(storage.snapshot()).schemaVersion)
        assertEquals(migrated, codec.decode(storage.snapshot()))

        assertEquals(migrated, persistence.loadCatalog())
        assertEquals(1, storage.writeAttempts.get())
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

        assertEquals(4, migrated.schemaVersion)
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

        assertEquals(4, migrated.schemaVersion)
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
    fun `negative archive timestamp is rejected without replacing the schema 4 payload`() {
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
              "schemaVersion": 5,
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
