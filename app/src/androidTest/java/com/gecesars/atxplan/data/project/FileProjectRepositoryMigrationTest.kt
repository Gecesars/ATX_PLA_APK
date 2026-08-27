package com.gecesars.atxplan.data.project

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.domain.model.PROJECT_CATALOG_SCHEMA_VERSION
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileProjectRepositoryMigrationTest {
    @Test
    fun schema4IndexedCatalogIsAtomicallyPromotedAndCannotInjectLinkStudies() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            targetContext.cacheDir,
            "project-schema-4-migration-${System.nanoTime()}",
        ).canonicalFile
        val cacheRoot = targetContext.cacheDir.canonicalFile
        assertTrue(root.path.startsWith(cacheRoot.path + File.separator))

        try {
            assertTrue(root.mkdirs())
            val repositoryContext = IsolatedFilesContext(targetContext, root)
            val seededCatalog = FileProjectRepository(repositoryContext).loadCatalog()
            assertEquals(PROJECT_CATALOG_SCHEMA_VERSION, seededCatalog.schemaVersion)

            val indexFile = File(root, CATALOG_FILE_NAME)
            val currentIndex = parseObject(indexFile.readBytes())
            assertEquals(
                PROJECT_CATALOG_SCHEMA_VERSION,
                currentIndex.getValue("projectSchemaVersion").jsonPrimitive.int,
            )
            val currentReference = currentIndex.getValue("projects")
                .jsonArray
                .single()
                .jsonObject
            val currentDigest = currentReference.getValue("sha256").jsonPrimitive.content
            val currentDocumentFile = projectDocumentFile(root, currentDigest)
            val currentDocument = parseObject(currentDocumentFile.readBytes())
            val currentProject = currentDocument.getValue("project").jsonObject
            assertTrue(currentProject.getValue("linkStudies").jsonArray.isEmpty())

            val injectedProject = JsonObject(
                currentProject + mapOf(
                    "linkStudies" to JsonArray(
                        listOf(JsonObject(mapOf("untrusted" to JsonPrimitive(true)))),
                    ),
                ),
            )
            val legacyDocument = JsonObject(
                currentDocument + mapOf(
                    "projectSchemaVersion" to JsonPrimitive(LEGACY_PROJECT_SCHEMA_VERSION),
                    "project" to injectedProject,
                ),
            ).toString().toByteArray(Charsets.UTF_8)
            val legacyDigest = sha256(legacyDocument)
            val legacyReference = JsonObject(
                currentReference + mapOf(
                    "sha256" to JsonPrimitive(legacyDigest),
                    "byteLength" to JsonPrimitive(legacyDocument.size.toLong()),
                ),
            )
            val legacyIndex = JsonObject(
                currentIndex + mapOf(
                    "projectSchemaVersion" to JsonPrimitive(LEGACY_PROJECT_SCHEMA_VERSION),
                    "projects" to JsonArray(listOf(legacyReference)),
                ),
            ).toString().toByteArray(Charsets.UTF_8)

            val documentRoot = File(root, PROJECT_DOCUMENT_DIRECTORY)
            assertTrue(documentRoot.deleteRecursively())
            val legacyDocumentFile = projectDocumentFile(root, legacyDigest)
            writeAtomically(legacyDocumentFile, legacyDocument)
            writeAtomically(indexFile, legacyIndex)
            assertArrayEquals(legacyIndex, indexFile.readBytes())
            assertEquals(
                "atx-project-index",
                parseObject(indexFile.readBytes()).getValue("format").jsonPrimitive.content,
            )
            assertEquals(
                LEGACY_PROJECT_SCHEMA_VERSION,
                parseObject(indexFile.readBytes()).getValue("projectSchemaVersion").jsonPrimitive.int,
            )
            assertEquals(
                LEGACY_PROJECT_SCHEMA_VERSION,
                parseObject(legacyDocumentFile.readBytes())
                    .getValue("projectSchemaVersion")
                    .jsonPrimitive
                    .int,
            )
            assertTrue(legacyDocumentFile.readText().contains("\"untrusted\":true"))

            val migrated = FileProjectRepository(repositoryContext).loadCatalog()

            assertEquals(PROJECT_CATALOG_SCHEMA_VERSION, migrated.schemaVersion)
            assertEquals(seededCatalog, migrated)
            assertTrue(migrated.selectedProject?.linkStudies?.isEmpty() == true)
            assertArrayEquals(legacyDocument, legacyDocumentFile.readBytes())

            val migratedIndexBytes = indexFile.readBytes()
            assertFalse(legacyIndex.contentEquals(migratedIndexBytes))
            val migratedIndex = parseObject(migratedIndexBytes)
            assertEquals(
                PROJECT_CATALOG_SCHEMA_VERSION,
                migratedIndex.getValue("projectSchemaVersion").jsonPrimitive.int,
            )
            val migratedReference = migratedIndex.getValue("projects")
                .jsonArray
                .single()
                .jsonObject
            val migratedDigest = migratedReference.getValue("sha256").jsonPrimitive.content
            assertNotEquals(legacyDigest, migratedDigest)
            assertEquals(currentDigest, migratedDigest)

            val migratedDocumentFile = projectDocumentFile(root, migratedDigest)
            val migratedDocumentBytes = migratedDocumentFile.readBytes()
            assertEquals(
                migratedReference.getValue("byteLength").jsonPrimitive.long,
                migratedDocumentBytes.size.toLong(),
            )
            assertEquals(migratedDigest, sha256(migratedDocumentBytes))
            val migratedDocument = parseObject(migratedDocumentBytes)
            assertEquals(
                PROJECT_CATALOG_SCHEMA_VERSION,
                migratedDocument.getValue("projectSchemaVersion").jsonPrimitive.int,
            )
            assertTrue(
                migratedDocument.getValue("project")
                    .jsonObject
                    .getValue("linkStudies")
                    .jsonArray
                    .isEmpty(),
            )
            assertFalse(migratedDocumentBytes.toString(Charsets.UTF_8).contains("untrusted"))
            assertFalse(File("${indexFile.path}.bak").exists())
            assertFalse(File("${indexFile.path}.new").exists())

            val reopened = FileProjectRepository(repositoryContext).loadCatalog()
            assertEquals(migrated, reopened)
            assertArrayEquals(migratedIndexBytes, indexFile.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun parseObject(payload: ByteArray): JsonObject =
        Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject

    private fun projectDocumentFile(root: File, digest: String): File =
        File(File(File(root, PROJECT_DOCUMENT_DIRECTORY), "sha256"), digest.take(2))
            .resolve("$digest.json")

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun writeAtomically(target: File, payload: ByteArray) {
        assertTrue(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(payload)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private class IsolatedFilesContext(
        base: Context,
        private val isolatedFilesDir: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = isolatedFilesDir
    }

    private companion object {
        const val LEGACY_PROJECT_SCHEMA_VERSION = 4
        const val CATALOG_FILE_NAME = "atx_project_catalog_v1.json"
        const val PROJECT_DOCUMENT_DIRECTORY = "atx_project_documents"
    }
}
