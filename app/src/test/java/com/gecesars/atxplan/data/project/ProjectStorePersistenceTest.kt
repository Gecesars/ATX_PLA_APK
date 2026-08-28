package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.AntennaPatternCoordinateConvention
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProjectStorePersistenceTest {
    private val legacyCodec = ProjectCatalogCodec()
    private val indexCodec = ProjectCatalogIndexCodec()

    @Test
    fun `schema 3 migrates to an atomic index and immutable project documents`() = runBlocking {
        val activeA = project("project-a", "Active A", 10L)
        val activeB = project("project-b", "Active B", 20L)
        val archived = project("project-archived", "Archived", 30L)
        val legacy = ProjectCatalog(
            schemaVersion = 3,
            selectedProjectId = activeB.id,
            projects = listOf(activeA, activeB),
            archivedProjects = listOf(ArchivedProject(archived, 40L, 1)),
        )
        val original = legacyCodec.encode(legacy)
        val control = MemoryControlStorage(original)
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents)

        val migrated = persistence.loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertEquals(activeB.id, migrated.selectedProjectId)
        assertEquals(listOf(activeA.id, activeB.id), migrated.projects.map { it.id })
        assertEquals(archived, migrated.archivedProjects.single().project)
        assertEquals(1, migrated.archivedProjects.single().originalProjectIndex)
        assertEquals(3, documents.payloads.size)
        assertEquals(3, documents.writeAttempts)
        val index = indexCodec.decode(control.snapshot())
        assertEquals(PROJECT_STORE_FORMAT, index.format)
        assertEquals(listOf(activeA.id, activeB.id), index.projects.map { it.projectId })
        assertEquals(archived.id, index.archivedProjects.single().project.projectId)

        assertEquals(migrated, persistence.loadCatalog())
        assertEquals(1, control.writeAttempts)
        assertEquals(3, documents.writeAttempts)
    }

    @Test
    fun `schema 1 and schema 2 fixtures migrate through the indexed store`() = runBlocking {
        val fixtures = listOf(
            "project_catalog_v1.json" to "Legacy Mountain Link",
            "project_catalog_v2.json" to "Schema 2 Mountain Path",
        )

        fixtures.forEach { (fixtureName, expectedProjectName) ->
            val original = fixture(fixtureName)
            val control = MemoryControlStorage(original)
            val documents = MemoryDocumentStorage()
            val migrated = persistence(control, documents).loadCatalog()

            assertEquals(6, migrated.schemaVersion)
            assertEquals(expectedProjectName, migrated.selectedProject?.name)
            assertEquals(1, documents.payloads.size)
            assertEquals(PROJECT_STORE_FORMAT, indexCodec.decode(control.snapshot()).format)
        }
    }

    @Test
    fun `schema 4 indexed store migrates documents before publishing a schema 6 index`() = runBlocking {
        val sourceProject = project("project-schema-4", "Indexed Schema 4", 40L)
        val documentCodec = ProjectDocumentCodec()
        val encoded = documentCodec.encode(
            ProjectDocument(
                projectSchemaVersion = 4,
                project = sourceProject,
            ),
        ).toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"linkStudies\":[]"))
        val sourceDocument = encoded.replace(
            "\"linkStudies\":[]",
            "\"linkStudies\":[{\"untrusted\":true}]",
        ).toByteArray(Charsets.UTF_8)
        val sourceReference = ProjectDocumentReference(
            projectId = sourceProject.id,
            sha256 = sha256Hex(sourceDocument),
            byteLength = sourceDocument.size.toLong(),
        )
        val sourceIndex = indexCodec.encode(
            ProjectCatalogIndex(
                projectSchemaVersion = 4,
                selectedProjectId = sourceProject.id,
                projects = listOf(sourceReference),
            ),
        )
        val control = MemoryControlStorage(sourceIndex)
        val documents = MemoryDocumentStorage().apply {
            payloads[sourceReference.sha256] = sourceDocument
        }
        val persistence = persistence(control, documents)

        val migrated = persistence.loadCatalog()

        assertEquals(6, migrated.schemaVersion)
        assertEquals(sourceProject, migrated.selectedProject)
        assertTrue(migrated.selectedProject!!.linkStudies.isEmpty())
        assertEquals(1, control.writeAttempts)
        assertEquals(1, documents.writeAttempts)
        assertEquals(2, documents.payloads.size)
        assertEquals(6, indexCodec.decode(control.snapshot()).projectSchemaVersion)

        assertEquals(migrated, persistence.loadCatalog())
        assertEquals(1, control.writeAttempts)
        assertEquals(1, documents.writeAttempts)
    }

    @Test
    fun `schema 5 indexed document strips injected antenna calculation fields before schema 6`() =
        runBlocking {
            val legacyPattern = AntennaPatternRecord(
                id = "legacy-pattern",
                name = "Legacy Pattern",
                sourceFormat = "PRN",
            )
            val sourceProject = project(
                id = "project-schema-5-pattern",
                name = "Indexed Schema 5 Pattern",
                timestamp = 50L,
            ).copy(antennaPatterns = listOf(legacyPattern))
            val documentCodec = ProjectDocumentCodec()
            val encodedRoot = Json.parseToJsonElement(
                documentCodec.encode(
                    ProjectDocument(
                        projectSchemaVersion = 5,
                        project = sourceProject,
                    ),
                ).toString(Charsets.UTF_8),
            ).jsonObject
            val encodedProject = encodedRoot.getValue("project").jsonObject
            val encodedPattern = encodedProject.getValue("antennaPatterns")
                .jsonArray
                .single()
                .jsonObject
            val injectedPattern = JsonObject(
                encodedPattern + mapOf(
                    "sourceArtifactId" to JsonPrimitive("injected-source-artifact"),
                    "canonicalDataVersion" to JsonPrimitive(1),
                    "origin" to JsonPrimitive("MEASURED"),
                    "coordinateConvention" to JsonPrimitive("INJECTED_CONVENTION"),
                    "horizontalCut" to JsonObject(mapOf("untrusted" to JsonPrimitive(true))),
                    "verticalCut" to JsonObject(mapOf("untrusted" to JsonPrimitive(true))),
                    "normalizedContentSha256" to JsonPrimitive("d".repeat(64)),
                    "warnings" to JsonArray(listOf(JsonPrimitive("Injected warning."))),
                ),
            )
            val injectedProject = JsonObject(
                encodedProject +
                    ("antennaPatterns" to JsonArray(listOf(injectedPattern))),
            )
            val sourceDocument = JsonObject(
                encodedRoot + ("project" to injectedProject),
            ).toString().toByteArray(Charsets.UTF_8)
            val sourceReference = ProjectDocumentReference(
                projectId = sourceProject.id,
                sha256 = sha256Hex(sourceDocument),
                byteLength = sourceDocument.size.toLong(),
            )
            val sourceIndex = indexCodec.encode(
                ProjectCatalogIndex(
                    projectSchemaVersion = 5,
                    selectedProjectId = sourceProject.id,
                    projects = listOf(sourceReference),
                ),
            )
            val control = MemoryControlStorage(sourceIndex)
            val documents = MemoryDocumentStorage().apply {
                payloads[sourceReference.sha256] = sourceDocument
            }

            val migrated = persistence(control, documents).loadCatalog()
            val migratedPattern = migrated.selectedProject!!.antennaPatterns.single()

            assertEquals(6, migrated.schemaVersion)
            assertEquals("PRN", migratedPattern.sourceFormat)
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
            assertEquals(6, indexCodec.decode(control.snapshot()).projectSchemaVersion)
            assertEquals(1, control.writeAttempts)
            assertEquals(1, documents.writeAttempts)
            assertEquals(2, documents.payloads.size)
        }

    @Test
    fun `failed schema 4 indexed migration preserves the previous authoritative index`() {
        val sourceProject = project("project-schema-4-failure", "Schema 4 Failure", 41L)
        val sourceDocument = ProjectDocumentCodec().encode(
            ProjectDocument(projectSchemaVersion = 4, project = sourceProject),
        )
        val sourceReference = ProjectDocumentReference(
            projectId = sourceProject.id,
            sha256 = sha256Hex(sourceDocument),
            byteLength = sourceDocument.size.toLong(),
        )
        val sourceIndex = indexCodec.encode(
            ProjectCatalogIndex(
                projectSchemaVersion = 4,
                projects = listOf(sourceReference),
            ),
        )
        val control = MemoryControlStorage(sourceIndex)
        val documents = MemoryDocumentStorage().apply {
            payloads[sourceReference.sha256] = sourceDocument
            failNextWrite = true
        }

        assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }

        assertArrayEquals(sourceIndex, control.snapshot())
        assertEquals(0, control.writeAttempts)
        assertEquals(1, documents.writeAttempts)
    }

    @Test
    fun `schema 3 migration preserves stale selection fallback semantics`() = runBlocking {
        val active = project("project-active", "Active Project", 10L)
        val archived = project("project-archived", "Archived Project", 20L)
        val legacy = ProjectCatalog(
            schemaVersion = 3,
            selectedProjectId = archived.id,
            projects = listOf(active),
            archivedProjects = listOf(ArchivedProject(archived, 30L, 0)),
        )
        val control = MemoryControlStorage(legacyCodec.encode(legacy))
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents)

        val migrated = persistence.loadCatalog()

        assertEquals(archived.id, migrated.selectedProjectId)
        assertEquals(active, migrated.selectedProject)
        assertEquals(archived.id, indexCodec.decode(control.snapshot()).selectedProjectId)
        assertEquals(migrated, persistence.loadCatalog())
    }

    @Test
    fun `schema 3 migration preserves sector IDs that repeat across different sites`() = runBlocking {
        val source = ProjectFactory.demonstration(nowEpochMillis = 10L).copy(
            id = "project-repeated-sector-ids",
            name = "Repeated Sector IDs",
        )
        val repeatedSectorId = "legacy-sector-shared"
        val legacyProject = source.copy(
            sites = source.sites.take(2).map { site ->
                site.copy(
                    sectors = listOf(site.sectors.single().copy(id = repeatedSectorId)),
                )
            },
        )
        val legacy = ProjectCatalog(
            schemaVersion = 3,
            selectedProjectId = legacyProject.id,
            projects = listOf(legacyProject),
        )
        val control = MemoryControlStorage(legacyCodec.encode(legacy))
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents)

        val migrated = persistence.loadCatalog()

        assertEquals(
            listOf(repeatedSectorId, repeatedSectorId),
            migrated.selectedProject?.sites?.flatMap { site ->
                site.sectors.map { sector -> sector.id }
            },
        )
        assertEquals(migrated, persistence.loadCatalog())
    }

    @Test
    fun `document failure leaves legacy schema 3 bytes authoritative`() {
        val legacy = ProjectCatalog(
            schemaVersion = 3,
            projects = listOf(project("project-a", "Active A", 10L)),
        )
        val original = legacyCodec.encode(legacy)
        val control = MemoryControlStorage(original)
        val documents = MemoryDocumentStorage().apply { failNextWrite = true }

        assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }

        assertArrayEquals(original, control.snapshot())
        assertEquals(0, control.writeAttempts)
    }

    @Test
    fun `failed index publication leaves legacy schema 3 bytes authoritative`() {
        val legacy = ProjectCatalog(
            schemaVersion = 3,
            projects = listOf(project("project-a", "Active Project", 10L)),
        )
        val original = legacyCodec.encode(legacy)
        val control = MemoryControlStorage(original).apply { failNextWrite = true }
        val documents = MemoryDocumentStorage()

        assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }

        assertArrayEquals(original, control.snapshot())
        assertEquals(1, control.writeAttempts)
        assertEquals(1, documents.payloads.size)
    }

    @Test
    fun `failed index publication preserves the previous reachable catalog`() = runBlocking {
        val first = project("project-a", "Original", 10L)
        val control = MemoryControlStorage()
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents) {
            ProjectCatalog(selectedProjectId = first.id, projects = listOf(first))
        }
        assertEquals(first, persistence.loadCatalog().selectedProject)
        control.failNextWrite = true

        assertThrows(ProjectStorageException::class.java) {
            runBlocking {
                persistence.updateCatalog { catalog ->
                    catalog.copy(
                        projects = listOf(first.copy(name = "Uncommitted", updatedAtEpochMillis = 20L)),
                    )
                }
            }
        }

        val reopened = persistence(control, documents).loadCatalog()
        assertEquals("Original", reopened.selectedProject?.name)
        assertEquals(2, documents.payloads.size)
    }

    @Test
    fun `selection archive and restore reuse immutable documents`() = runBlocking {
        val first = project("project-a", "Alpha", 10L)
        val second = project("project-b", "Beta", 20L)
        val control = MemoryControlStorage()
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents) {
            ProjectCatalog(selectedProjectId = first.id, projects = listOf(first, second))
        }
        persistence.loadCatalog()
        val initialDocumentWrites = documents.writeAttempts

        persistence.updateCatalog { it.copy(selectedProjectId = second.id) }
        persistence.updateCatalog { catalog ->
            catalog.copy(
                selectedProjectId = first.id,
                projects = listOf(first),
                archivedProjects = listOf(ArchivedProject(second, 30L, 1)),
            )
        }
        persistence.updateCatalog { catalog ->
            catalog.copy(
                selectedProjectId = second.id,
                projects = listOf(first, second),
                archivedProjects = emptyList(),
            )
        }

        assertEquals(initialDocumentWrites, documents.writeAttempts)
        assertEquals(4, control.writeAttempts)
    }

    @Test
    fun `editing one project writes only one new immutable document`() = runBlocking {
        val first = project("project-a", "Alpha", 10L)
        val second = project("project-b", "Beta", 20L)
        val control = MemoryControlStorage()
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents) {
            ProjectCatalog(selectedProjectId = first.id, projects = listOf(first, second))
        }
        persistence.loadCatalog()
        val initialWrites = documents.writeAttempts

        val updated = persistence.updateCatalog { catalog ->
            catalog.copy(
                projects = listOf(first.copy(name = "Renamed", updatedAtEpochMillis = 30L), second),
            )
        }

        assertEquals("Renamed", updated.projects.first().name)
        assertEquals(initialWrites + 1, documents.writeAttempts)
        assertEquals(3, documents.payloads.size)
    }

    @Test
    fun `no-op transaction writes neither index nor documents`() = runBlocking {
        val project = project("project-a", "Alpha", 10L)
        val control = MemoryControlStorage()
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents) {
            ProjectCatalog(selectedProjectId = project.id, projects = listOf(project))
        }
        val loaded = persistence.loadCatalog()
        val controlWrites = control.writeAttempts
        val documentWrites = documents.writeAttempts

        val result = persistence.updateCatalog { it }

        assertEquals(loaded, result)
        assertEquals(controlWrites, control.writeAttempts)
        assertEquals(documentWrites, documents.writeAttempts)
    }

    @Test
    fun `missing or corrupt document fails closed without replacing the index`() = runBlocking {
        val project = project("project-a", "Alpha", 10L)
        val control = MemoryControlStorage()
        val documents = MemoryDocumentStorage()
        val persistence = persistence(control, documents) {
            ProjectCatalog(selectedProjectId = project.id, projects = listOf(project))
        }
        persistence.loadCatalog()
        val indexBytes = control.snapshot()
        val reference = indexCodec.decode(indexBytes).projects.single()
        documents.payloads.remove(reference.sha256)

        val missing = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }
        assertTrue(missing.message.orEmpty().contains("could not be opened"))
        assertArrayEquals(indexBytes, control.snapshot())

        documents.payloads[reference.sha256] = byteArrayOf(1, 2, 3)
        val corrupt = assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }
        assertTrue(corrupt.message.orEmpty().contains("integrity check"))
        assertArrayEquals(indexBytes, control.snapshot())
    }

    @Test
    fun `future index schema is rejected without a repair write`() {
        val future = """
            {"format":"$PROJECT_STORE_FORMAT","storeSchemaVersion":2,"projectSchemaVersion":6,
             "selectedProjectId":null,"projects":[],"archivedProjects":[]}
        """.trimIndent().toByteArray()
        val control = MemoryControlStorage(future)

        assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, MemoryDocumentStorage()).loadCatalog() }
        }

        assertArrayEquals(future, control.snapshot())
        assertEquals(0, control.writeAttempts)
    }

    @Test
    fun `index and project document codecs require explicit version discriminators`() {
        val indexPayload = indexCodec.encode(ProjectCatalogIndex())
        listOf("format", "storeSchemaVersion", "projectSchemaVersion").forEach { field ->
            assertThrows(SerializationException::class.java) {
                indexCodec.decode(indexPayload.withoutTopLevelField(field))
            }
        }

        val documentCodec = ProjectDocumentCodec()
        val documentPayload = documentCodec.encode(
            ProjectDocument(project = project("project-a", "Active Project", 10L)),
        )
        listOf("documentSchemaVersion", "projectSchemaVersion").forEach { field ->
            assertThrows(SerializationException::class.java) {
                documentCodec.decode(documentPayload.withoutTopLevelField(field))
            }
        }
    }

    @Test
    fun `schema 6 monolithic catalog with unknown data is rejected without replacement`() {
        val catalog = ProjectCatalog(
            projects = listOf(project("project-a", "Active Project", 10L)),
        )
        val encoded = legacyCodec.encode(catalog).toString(Charsets.UTF_8).trimEnd()
        val withUnknownData = (
            encoded.dropLast(1) +
                ",\n  \"unknownPhaseOneField\": {\"mustBePreserved\": true}\n}"
            ).toByteArray(Charsets.UTF_8)
        val control = MemoryControlStorage(withUnknownData)
        val documents = MemoryDocumentStorage()

        assertThrows(ProjectStorageException::class.java) {
            runBlocking { persistence(control, documents).loadCatalog() }
        }

        assertArrayEquals(withUnknownData, control.snapshot())
        assertEquals(0, control.writeAttempts)
        assertTrue(documents.payloads.isEmpty())
    }

    @Test
    fun `project document references enforce the conservative eight MiB ceiling`() {
        assertEquals(8 * 1024 * 1024, MAX_PROJECT_DOCUMENT_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectDocumentReference(
                projectId = "project-a",
                sha256 = "0".repeat(64),
                byteLength = MAX_PROJECT_DOCUMENT_BYTES.toLong() + 1L,
            )
        }
    }

    @Test
    fun `unsupported project store discriminator is never treated as a legacy catalog`() {
        val unsupportedPayloads = listOf(
            """
                {"format":"another-project-store","storeSchemaVersion":1,
                 "projectSchemaVersion":6,"selectedProjectId":null,
                 "projects":[],"archivedProjects":[]}
            """.trimIndent().toByteArray(),
            """
                {"storeSchemaVersion":1,"projectSchemaVersion":6,
                 "selectedProjectId":null,"projects":[],"archivedProjects":[]}
            """.trimIndent().toByteArray(),
        )

        unsupportedPayloads.forEach { payload ->
            val control = MemoryControlStorage(payload)
            val documents = MemoryDocumentStorage()

            val error = assertThrows(ProjectStorageException::class.java) {
                runBlocking { persistence(control, documents).loadCatalog() }
            }

            assertTrue(error.message.orEmpty().contains("not supported"))
            assertArrayEquals(payload, control.snapshot())
            assertEquals(0, control.writeAttempts)
            assertTrue(documents.payloads.isEmpty())
        }
    }

    private fun persistence(
        control: MemoryControlStorage,
        documents: MemoryDocumentStorage,
        seed: () -> ProjectCatalog = { ProjectCatalog() },
    ) = ProjectStorePersistence(
        controlStorage = control,
        documentStorage = documents,
        seedCatalog = seed,
    )

    private fun project(id: String, name: String, timestamp: Long) = ProjectFactory.create(
        name = name,
        customer = "",
        nowEpochMillis = timestamp,
    ).copy(id = id)

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing catalog fixture: $name"
        }.use { it.readBytes() }

    private fun ByteArray.withoutTopLevelField(field: String): ByteArray {
        val root = Json.parseToJsonElement(toString(Charsets.UTF_8)).jsonObject
        return JsonObject(root.filterKeys { key -> key != field })
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

private class MemoryControlStorage(
    initialPayload: ByteArray? = null,
) : ProjectCatalogStorage {
    private var payload = initialPayload?.copyOf()
    var writeAttempts: Int = 0
    var failNextWrite: Boolean = false

    override fun exists(): Boolean = payload != null

    override fun read(maxBytes: Int): ByteArray = payload?.copyOf() ?: throw IOException("Missing control file")

    override fun writeAtomically(payload: ByteArray) {
        writeAttempts += 1
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Injected index failure")
        }
        this.payload = payload.copyOf()
    }

    fun snapshot(): ByteArray = payload?.copyOf() ?: byteArrayOf()
}

private class MemoryDocumentStorage : ProjectDocumentStorage {
    val payloads = linkedMapOf<String, ByteArray>()
    var writeAttempts: Int = 0
    var failNextWrite: Boolean = false

    override fun read(reference: ProjectDocumentReference, maximumBytes: Int): ByteArray =
        payloads[reference.sha256]?.also { payload ->
            if (payload.size > maximumBytes) throw ProjectCatalogSizeLimitException()
        }?.copyOf() ?: throw IOException("Missing document")

    override fun write(reference: ProjectDocumentReference, payload: ByteArray) {
        writeAttempts += 1
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Injected document failure")
        }
        val existing = payloads[reference.sha256]
        if (existing != null && !existing.contentEquals(payload)) {
            throw IOException("Existing immutable document is corrupt")
        }
        payloads.putIfAbsent(reference.sha256, payload.copyOf())
    }
}
