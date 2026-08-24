package com.gecesars.atxplan.data.project

import android.content.Context
import android.util.AtomicFile
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class FileProjectRepository private constructor(
    private val persistence: ProjectCatalogPersistence,
) : ProjectRepository {
    constructor(context: Context) : this(
        ProjectCatalogPersistence(
            storage = AndroidAtomicProjectCatalogStorage(
                File(context.applicationContext.filesDir, CATALOG_FILE_NAME),
            ),
            codec = ProjectCatalogCodec(),
            seedCatalog = {
                ProjectCatalog(
                    selectedProjectId = DEMONSTRATION_PROJECT_ID,
                    projects = listOf(ProjectFactory.demonstration()),
                )
            },
            operationMutex = APPLICATION_CATALOG_MUTEX,
        ),
    )

    override suspend fun loadCatalog(): ProjectCatalog = withContext(Dispatchers.IO) {
        persistence.loadCatalog()
    }

    override suspend fun updateCatalog(
        transform: (ProjectCatalog) -> ProjectCatalog,
    ): ProjectCatalog = withContext(Dispatchers.IO) {
        persistence.updateCatalog(transform)
    }

    private companion object {
        // Keep the original filename so schema 1 installations are discovered and migrated.
        const val CATALOG_FILE_NAME = "atx_project_catalog_v1.json"
        const val DEMONSTRATION_PROJECT_ID = "project-demo-sao-paulo"

        // A process-wide lock protects complete transactions across repository instances.
        val APPLICATION_CATALOG_MUTEX = Mutex()
    }
}

private class AndroidAtomicProjectCatalogStorage(file: File) : ProjectCatalogStorage {
    private val atomicFile = AtomicFile(file)

    override fun exists(): Boolean =
        atomicFile.baseFile.exists() || File("${atomicFile.baseFile.path}.bak").exists()

    override fun read(maxBytes: Int): ByteArray = atomicFile.openRead().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_READ_BUFFER_BYTES))
        val buffer = ByteArray(DEFAULT_READ_BUFFER_BYTES)
        var totalBytes = 0
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            totalBytes += bytesRead
            if (totalBytes > maxBytes) {
                throw ProjectCatalogSizeLimitException()
            }
            output.write(buffer, 0, bytesRead)
        }
        output.toByteArray()
    }

    override fun writeAtomically(payload: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(payload)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            try {
                output?.let(atomicFile::failWrite)
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw IOException("Atomic catalog write failed.", error)
        }
    }

    private companion object {
        const val DEFAULT_READ_BUFFER_BYTES = 8 * 1024
    }
}
