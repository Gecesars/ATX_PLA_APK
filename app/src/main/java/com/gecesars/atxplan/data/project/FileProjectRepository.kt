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
import java.io.InputStream
import java.io.OutputStream

class FileProjectRepository private constructor(
    private val persistence: ProjectStorePersistence,
    private val artifactStore: FileContentAddressedArtifactStore,
) : ProjectRepository, ProjectArtifactRepository {
    constructor(context: Context) : this(
        ProjectStorePersistence(
            controlStorage = AndroidAtomicProjectCatalogStorage(
                File(context.applicationContext.filesDir, CATALOG_FILE_NAME),
            ),
            documentStorage = AndroidImmutableProjectDocumentStorage(
                File(context.applicationContext.filesDir, PROJECT_DOCUMENT_DIRECTORY),
            ),
            seedCatalog = {
                ProjectCatalog(
                    selectedProjectId = DEMONSTRATION_PROJECT_ID,
                    projects = listOf(ProjectFactory.demonstration()),
                )
            },
            operationMutex = APPLICATION_CATALOG_MUTEX,
        ),
        FileContentAddressedArtifactStore(
            File(context.applicationContext.filesDir, PROJECT_ARTIFACT_DIRECTORY),
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

    override suspend fun storeArtifact(
        role: com.gecesars.atxplan.domain.model.ProjectArtifactRole,
        fileName: String,
        mediaType: String,
        input: InputStream,
        maximumBytes: Long,
        expectedSha256: String?,
    ): com.gecesars.atxplan.domain.model.ProjectArtifactReference = withContext(Dispatchers.IO) {
        artifactStore.put(
            role = role,
            fileName = fileName,
            mediaType = mediaType,
            input = input,
            maximumBytes = maximumBytes,
            expectedSha256 = expectedSha256,
        )
    }

    override suspend fun artifactAvailability(
        reference: com.gecesars.atxplan.domain.model.ProjectArtifactReference,
    ): ArtifactAvailability = withContext(Dispatchers.IO) {
        artifactStore.availability(reference)
    }

    override suspend fun copyArtifact(
        reference: com.gecesars.atxplan.domain.model.ProjectArtifactReference,
        output: OutputStream,
        maximumBytes: Long,
    ) = withContext(Dispatchers.IO) {
        artifactStore.copy(reference, output, maximumBytes)
    }

    private companion object {
        // Keep the original filename so schema 1 installations are discovered and migrated.
        const val CATALOG_FILE_NAME = "atx_project_catalog_v1.json"
        const val PROJECT_DOCUMENT_DIRECTORY = "atx_project_documents"
        const val PROJECT_ARTIFACT_DIRECTORY = "atx_project_artifacts"
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
        input.readBounded(maxBytes)
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
}

private class AndroidImmutableProjectDocumentStorage(
    private val rootDirectory: File,
) : ProjectDocumentStorage {
    override fun read(reference: ProjectDocumentReference, maximumBytes: Int): ByteArray {
        val target = targetFile(reference.sha256)
        if (!target.hasAtomicFilePayload()) {
            throw IOException("The referenced project document is missing.")
        }
        return AtomicFile(target).openRead().use { input -> input.readBounded(maximumBytes) }
    }

    override fun write(reference: ProjectDocumentReference, payload: ByteArray) {
        if (
            payload.size.toLong() != reference.byteLength ||
            sha256Hex(payload) != reference.sha256
        ) {
            throw IOException("The project document does not match its reference.")
        }
        val target = targetFile(reference.sha256)
        if (target.hasAtomicFilePayload()) {
            val existing = AtomicFile(target).openRead().use { input ->
                input.readBounded(MAX_PROJECT_DOCUMENT_BYTES)
            }
            if (existing.contentEquals(payload)) return
            throw IOException("Existing immutable project content failed verification.")
        }
        val parent = target.parentFile
            ?: throw IOException("The project document directory is invalid.")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("The project document directory could not be created.")
        }
        val atomicFile = AtomicFile(target)
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
            throw IOException("Atomic project document write failed.", error)
        }
        val verified = atomicFile.openRead().use { input ->
            input.readBounded(MAX_PROJECT_DOCUMENT_BYTES)
        }
        if (!verified.contentEquals(payload)) {
            throw IOException("The committed project document failed verification.")
        }
    }

    private fun targetFile(sha256: String): File {
        if (!STORAGE_SHA256_PATTERN.matches(sha256)) {
            throw IOException("The project document digest is invalid.")
        }
        return File(File(File(rootDirectory, "sha256"), sha256.take(2)), "$sha256.json")
    }
}

private fun File.hasAtomicFilePayload(): Boolean =
    isFile || File("$path.bak").isFile

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_READ_BUFFER_BYTES))
    val buffer = ByteArray(DEFAULT_READ_BUFFER_BYTES)
    var totalBytes = 0
    while (true) {
        val bytesRead = read(buffer)
        if (bytesRead < 0) break
        totalBytes += bytesRead
        if (totalBytes > maxBytes) throw ProjectCatalogSizeLimitException()
        output.write(buffer, 0, bytesRead)
    }
    return output.toByteArray()
}

private const val DEFAULT_READ_BUFFER_BYTES = 8 * 1024
