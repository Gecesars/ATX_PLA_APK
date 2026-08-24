package com.gecesars.atxplan.data.project

import android.content.Context
import android.util.AtomicFile
import com.gecesars.atxplan.domain.model.PROJECT_CATALOG_SCHEMA_VERSION
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class FileProjectRepository(context: Context) : ProjectRepository {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val storage = AtomicFile(File(context.filesDir, CATALOG_FILE_NAME))

    override suspend fun loadCatalog(): ProjectCatalog = withContext(Dispatchers.IO) {
        if (!storage.baseFile.exists()) {
            return@withContext ProjectCatalog(
                selectedProjectId = DEMONSTRATION_PROJECT_ID,
                projects = listOf(ProjectFactory.demonstration()),
            ).also(::writeCatalog)
        }
        if (storage.baseFile.length() > MAX_CATALOG_BYTES) {
            throw ProjectStorageException("The local catalog exceeds the safe 5 MB limit.")
        }
        try {
            val payload = storage.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val catalog = json.decodeFromString<ProjectCatalog>(payload)
            if (catalog.schemaVersion > PROJECT_CATALOG_SCHEMA_VERSION) {
                throw ProjectStorageException(
                    "The catalog was created by a newer version of ATX Plan.",
                )
            }
            catalog
        } catch (error: ProjectStorageException) {
            throw error
        } catch (error: SerializationException) {
            throw ProjectStorageException(
                "The local catalog could not be parsed. The original file was preserved.",
                error,
            )
        } catch (error: Exception) {
            throw ProjectStorageException("The local catalog could not be opened.", error)
        }
    }

    override suspend fun saveCatalog(catalog: ProjectCatalog) = withContext(Dispatchers.IO) {
        require(catalog.schemaVersion == PROJECT_CATALOG_SCHEMA_VERSION) {
            "Only the current schema can be saved."
        }
        writeCatalog(catalog)
    }

    private fun writeCatalog(catalog: ProjectCatalog) {
        val bytes = json.encodeToString(ProjectCatalog.serializer(), catalog).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CATALOG_BYTES) {
            throw ProjectStorageException("The catalog exceeds the safe 5 MB limit.")
        }
        var output: FileOutputStream? = null
        try {
            output = storage.startWrite()
            output.write(bytes)
            output.fd.sync()
            storage.finishWrite(output)
        } catch (error: Exception) {
            output?.let(storage::failWrite)
            throw ProjectStorageException("The catalog could not be saved atomically.", error)
        }
    }

    private companion object {
        const val CATALOG_FILE_NAME = "atx_project_catalog_v1.json"
        const val DEMONSTRATION_PROJECT_ID = "project-demo-sao-paulo"
        const val MAX_CATALOG_BYTES = 5L * 1024L * 1024L
    }
}
