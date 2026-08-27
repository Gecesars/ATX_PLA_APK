package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PROJECT_CATALOG_SCHEMA_VERSION
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ProjectDocumentStorage {
    fun read(reference: ProjectDocumentReference, maximumBytes: Int): ByteArray

    /** Writes and verifies immutable content. Existing matching content is reused. */
    fun write(reference: ProjectDocumentReference, payload: ByteArray)
}

/**
 * Persists a small atomic index backed by immutable SHA-256-addressed project documents.
 *
 * Documents are durable and verified before the index is replaced. A failed commit therefore
 * leaves the previous index fully reachable; a pre-index failure can only leave an unreferenced
 * immutable document, which is safe to reuse or clean later.
 */
internal class ProjectStorePersistence(
    private val controlStorage: ProjectCatalogStorage,
    private val documentStorage: ProjectDocumentStorage,
    private val seedCatalog: () -> ProjectCatalog,
    private val operationMutex: Mutex = Mutex(),
    private val legacyCodec: ProjectCatalogCodec = ProjectCatalogCodec(),
    private val legacyMigrator: ProjectCatalogMigrator = ProjectCatalogMigrator(),
    private val indexCodec: ProjectCatalogIndexCodec = ProjectCatalogIndexCodec(),
    private val documentCodec: ProjectDocumentCodec = ProjectDocumentCodec(),
) {
    suspend fun loadCatalog(): ProjectCatalog = operationMutex.withLock {
        loadStoreLocked().catalog
    }

    suspend fun updateCatalog(
        transform: (ProjectCatalog) -> ProjectCatalog,
    ): ProjectCatalog = operationMutex.withLock {
        val current = loadStoreLocked()
        val updated = transform(current.catalog)
        if (updated == current.catalog) return@withLock current.catalog
        persistCatalogLocked(updated, current.reusableDocuments)
        updated
    }

    private fun loadStoreLocked(): LoadedProjectStore {
        val exists = try {
            controlStorage.exists()
        } catch (error: Exception) {
            throw ProjectStorageException("The local project index could not be accessed.", error)
        }
        if (!exists) {
            val seed = seedCatalog()
            val reusableDocuments = persistCatalogLocked(seed, emptyMap())
            return LoadedProjectStore(seed, reusableDocuments)
        }

        val payload = try {
            controlStorage.read(MAX_PROJECT_CATALOG_BYTES)
        } catch (error: ProjectCatalogSizeLimitException) {
            throw ProjectStorageException("The local project index exceeds the safe 5 MB limit.", error)
        } catch (error: Exception) {
            throw ProjectStorageException("The local project index could not be opened.", error)
        }

        return when {
            indexCodec.isIndex(payload) -> loadIndexedCatalog(payload)
            indexCodec.hasProjectStoreDiscriminator(payload) -> throw ProjectStorageException(
                "The local project index format is not supported. Existing files were preserved.",
            )
            else -> migrateLegacyCatalog(payload)
        }
    }

    private fun loadIndexedCatalog(payload: ByteArray): LoadedProjectStore {
        val index = try {
            indexCodec.decode(payload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The local project index is invalid. Existing files were preserved.",
                error,
            )
        }

        val active = index.projects.map(::loadProject)
        val archived = index.archivedProjects.map { archivedReference ->
            ArchivedProject(
                project = loadProject(archivedReference.project),
                archivedAtEpochMillis = archivedReference.archivedAtEpochMillis,
                originalProjectIndex = archivedReference.originalProjectIndex,
            )
        }
        val catalog = try {
            ProjectCatalog(
                schemaVersion = index.projectSchemaVersion,
                selectedProjectId = index.selectedProjectId,
                projects = active,
                archivedProjects = archived,
            )
        } catch (error: IllegalArgumentException) {
            throw ProjectStorageException(
                "The local project index contains inconsistent references. Existing files were preserved.",
                error,
            )
        }
        val references = buildMap {
            index.projects.forEach { reference -> put(reference.projectId, reference) }
            index.archivedProjects.forEach { archivedReference ->
                put(archivedReference.project.projectId, archivedReference.project)
            }
        }
        return LoadedProjectStore(
            catalog = catalog,
            reusableDocuments = (active + archived.map(ArchivedProject::project)).associate { project ->
                project.id to ReusableProjectDocument(project, references.getValue(project.id))
            },
        )
    }

    private fun loadProject(reference: ProjectDocumentReference): PlannerProject {
        val payload = try {
            documentStorage.read(reference, MAX_PROJECT_DOCUMENT_BYTES)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "Project '${reference.projectId}' could not be opened. The index was preserved.",
                error,
            )
        }
        if (payload.size.toLong() != reference.byteLength || sha256Hex(payload) != reference.sha256) {
            throw ProjectStorageException(
                "Project '${reference.projectId}' failed its size or SHA-256 integrity check.",
            )
        }
        val document = try {
            documentCodec.decode(payload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "Project '${reference.projectId}' contains an invalid document. The index was preserved.",
                error,
            )
        }
        if (document.project.id != reference.projectId) {
            throw ProjectStorageException(
                "Project '${reference.projectId}' does not match its document identity.",
            )
        }
        return document.project
    }

    private fun migrateLegacyCatalog(payload: ByteArray): LoadedProjectStore {
        val document = try {
            legacyCodec.parse(payload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The legacy project catalog could not be parsed. The original file was preserved.",
                error,
            )
        }
        if (document.schemaVersion > PROJECT_CATALOG_SCHEMA_VERSION) {
            throw ProjectStorageException(
                "The project catalog was created by a newer version of ATX Plan. " +
                    "The original file was preserved.",
            )
        }
        val decoded = try {
            legacyCodec.decode(legacyMigrator.documentForDecode(document))
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The legacy project catalog contains invalid data. The original file was preserved.",
                error,
            )
        }
        val migrated = try {
            legacyMigrator.migrate(decoded, document.schemaVersion)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The legacy project catalog schema is not supported. The original file was preserved.",
                error,
            )
        }
        // The legacy control bytes remain authoritative until every document is durable and the
        // final AtomicFile index replacement succeeds.
        val reusableDocuments = persistCatalogLocked(migrated, emptyMap())
        return LoadedProjectStore(migrated, reusableDocuments)
    }

    private fun persistCatalogLocked(
        catalog: ProjectCatalog,
        reusableDocuments: Map<String, ReusableProjectDocument>,
    ): Map<String, ReusableProjectDocument> {
        if (catalog.schemaVersion != PROJECT_CATALOG_SCHEMA_VERSION) {
            throw ProjectStorageException(
                "Only the current project schema can be saved. Existing files were preserved.",
            )
        }
        val allProjects = catalog.projects + catalog.archivedProjects.map(ArchivedProject::project)
        val references = LinkedHashMap<String, ProjectDocumentReference>(allProjects.size)
        allProjects.forEach { project ->
            val reusable = reusableDocuments[project.id]
            val reference = if (reusable?.project == project) {
                reusable.reference
            } else {
                encodeAndStoreProject(project)
            }
            references[project.id] = reference
        }
        val index = ProjectCatalogIndex(
            selectedProjectId = catalog.selectedProjectId,
            projects = catalog.projects.map { project -> references.getValue(project.id) },
            archivedProjects = catalog.archivedProjects.map { archived ->
                ArchivedProjectDocumentReference(
                    project = references.getValue(archived.project.id),
                    archivedAtEpochMillis = archived.archivedAtEpochMillis,
                    originalProjectIndex = archived.originalProjectIndex,
                )
            },
        )
        val indexPayload = try {
            indexCodec.encode(index)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The project index could not be encoded. Existing files were preserved.",
                error,
            )
        }
        if (indexPayload.size > MAX_PROJECT_CATALOG_BYTES) {
            throw ProjectStorageException(
                "The project index exceeds the safe 5 MB limit. Existing files were preserved.",
            )
        }
        try {
            controlStorage.writeAtomically(indexPayload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The project index could not be committed atomically. Existing files were preserved.",
                error,
            )
        }

        return allProjects.associate { project ->
            project.id to ReusableProjectDocument(
                project = project,
                reference = references.getValue(project.id),
            )
        }
    }

    private fun encodeAndStoreProject(project: PlannerProject): ProjectDocumentReference {
        val payload = try {
            documentCodec.encode(ProjectDocument(project = project))
        } catch (error: Exception) {
            throw ProjectStorageException(
                "Project '${project.id}' could not be encoded. Existing files were preserved.",
                error,
            )
        }
        if (payload.isEmpty() || payload.size > MAX_PROJECT_DOCUMENT_BYTES) {
            throw ProjectStorageException(
                "Project '${project.id}' exceeds the safe 8 MB document limit.",
            )
        }
        val reference = ProjectDocumentReference(
            projectId = project.id,
            sha256 = sha256Hex(payload),
            byteLength = payload.size.toLong(),
        )
        try {
            documentStorage.write(reference, payload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "Project '${project.id}' could not be stored safely. Existing files were preserved.",
                error,
            )
        }
        return reference
    }

    private data class ReusableProjectDocument(
        val project: PlannerProject,
        val reference: ProjectDocumentReference,
    )

    private data class LoadedProjectStore(
        val catalog: ProjectCatalog,
        val reusableDocuments: Map<String, ReusableProjectDocument>,
    )
}
